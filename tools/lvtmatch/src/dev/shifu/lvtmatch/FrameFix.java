package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * 後処理で命令や局所変数の slot を書き換えたクラスの stackmap frame を計算し直す。
 *
 * <p>{@code LvtMatch --vars} は変数ごとに slot を置き直す。try-with-resources の一時変数のように
 * frame では {@code top} で、LVT に無い slot に重ねると、分岐の先で frame と実際の型が食い違って
 * JVM の検証で落ちる({@code VerifyError: Inconsistent stackmap frames}、DimensionDataStorage.readTagFromDisk)。
 * MOD 入りの起動では Mixin がそのクラスの frame を計算し直すので見えない。ここでは Mixin と同じことを
 * ビルドの後処理で行う: {@link ClassWriter#COMPUTE_FRAMES} で書き直す。
 *
 * <p>共通の親クラスは、classes と与えた jar から {@link ClassReader} で継承関係だけ読んで決める
 * (クラスを初期化しない)。
 *
 * <pre>java -cp ... dev.shifu.lvtmatch.FrameFix &lt;classes&gt; &lt;jar か jar のあるディレクトリ&gt;... -- &lt;一覧&gt;...</pre>
 * 一覧は「クラス メソッド」の行(先頭の語をクラスとして拾う)。一覧を渡さなければ classes の下の全部。
 */
public final class FrameFix {
    private FrameFix() {
    }

    public static void main(String[] args) throws IOException {
        Path classes = Paths.get(args[0]);
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        Set<String> targets = new HashSet<>();
        boolean lists = false;

        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                lists = true;
                continue;
            }

            if (lists) {
                Path list = Paths.get(args[i]);

                if (Files.exists(list)) {
                    for (String line : Files.readAllLines(list)) {
                        String trimmed = line.trim();

                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            targets.add(trimmed.split("\\s+")[0]);
                        }
                    }
                }

                continue;
            }

            Path entry = Paths.get(args[i]);

            if (Files.isDirectory(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    for (Path jar : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            } else if (Files.exists(entry)) {
                urls.add(entry.toUri().toURL());
            }
        }

        // 一覧が無ければ classes の下の全部(LvtMatch の一覧は「まだ違うもの」に書き直されるので、
        // 書き換えたクラスの一覧としては使えない)。触っていないクラスはあとで公式に戻る
        if (targets.isEmpty()) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String rel = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    targets.add(rel.substring(0, rel.length() - ".class".length()));
                }
            }
        }

        int rewritten = 0;
        int failed = 0;

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Hierarchy hierarchy = new Hierarchy(loader);

            for (String name : targets) {
                Path file = classes.resolve(name + ".class");

                if (!Files.exists(file)) {
                    continue;
                }

                byte[] before = Files.readAllBytes(file);

                try {
                    ClassReader reader = new ClassReader(before);
                    // reader を渡すと、触っていないメソッドは元の bytes(元の frame ごと)を写して済ませるので、
                    // frame が計算し直されない。渡さずに組み直す
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String a, String b) {
                            return hierarchy.commonSuperClass(a, b);
                        }
                    };
                    reader.accept(writer, ClassReader.SKIP_FRAMES);
                    Files.write(file, writer.toByteArray());
                    rewritten++;
                } catch (RuntimeException e) {
                    failed++;
                    System.err.println("FrameFix: " + name + ": " + e);
                }
            }
        }

        System.out.println("frames recomputed: " + rewritten);
        System.out.println("frames failed    : " + failed);
    }

    /** 継承関係だけを読む。クラスは初期化しない。 */
    private static final class Hierarchy {
        private final ClassLoader loader;
        private final Map<String, String[]> parents = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        Hierarchy(ClassLoader loader) {
            this.loader = loader;
        }

        /** [親クラス, interface か ("1" / "0")] */
        private String[] info(String name) {
            String[] cached = parents.get(name);

            if (cached != null) {
                return cached;
            }

            String[] result;

            try (InputStream in = open(name)) {
                if (in == null) {
                    if (missing.add(name) && missing.size() <= 5) {
                        System.err.println("FrameFix: class not found: " + name);
                    }
                    result = new String[] {"java/lang/Object", "0"};
                } else {
                    ClassReader reader = new ClassReader(in);
                    boolean iface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
                    result = new String[] {reader.getSuperName(), iface ? "1" : "0"};
                }
            } catch (IOException e) {
                result = new String[] {"java/lang/Object", "0"};
            }

            parents.put(name, result);

            return result;
        }

        private InputStream open(String name) {
            InputStream in = loader.getResourceAsStream(name + ".class");

            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(name + ".class");
            }

            return in;
        }

        private boolean isInterface(String name) {
            return "1".equals(info(name)[1]);
        }

        private boolean isAssignableFrom(String parent, String child) {
            String current = child;

            while (current != null) {
                if (current.equals(parent)) {
                    return true;
                }

                if ("java/lang/Object".equals(current)) {
                    return false;
                }

                current = info(current)[0];
            }

            return false;
        }

        /** ClassWriter.getCommonSuperClass と同じ規則。interface は Object に落とす。 */
        String commonSuperClass(String a, String b) {
            if (a.equals(b)) {
                return a;
            }

            if (isAssignableFrom(a, b)) {
                return a;
            }

            if (isAssignableFrom(b, a)) {
                return b;
            }

            if (isInterface(a) || isInterface(b)) {
                return "java/lang/Object";
            }

            String current = a;

            do {
                current = info(current)[0];

                if (current == null) {
                    return "java/lang/Object";
                }
            } while (!isAssignableFrom(current, b));

            return current;
        }
    }
}
