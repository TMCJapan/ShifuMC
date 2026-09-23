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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 後処理で命令や局所変数の slot を書き換えたメソッドの stackmap frame を計算し直す。
 *
 * <p>{@code LvtMatch --vars} は変数ごとに slot を置き直す。try-with-resources の一時変数のように
 * frame では {@code top} で、LVT に無い slot に重ねると、分岐の先で frame と実際の型が食い違って
 * JVM の検証で落ちる({@code VerifyError: Inconsistent stackmap frames}、DimensionDataStorage.readTagFromDisk)。
 * MOD 入りの起動では Mixin がそのクラスの frame を計算し直すので見えない。ここでは Mixin と同じことを
 * ビルドの後処理で行う: {@link ClassWriter#COMPUTE_FRAMES} で書き直す。
 *
 * <p>書き直すのは一覧に挙がったメソッドだけにする。全メソッドを計算し直すと、javac が
 * {@code top} と書いた死んだ slot にも型が入り、Mixin の {@code Locals} が公式には無い局所変数を
 * 見つける(1.20.6 の 9-16 のクラスで、LVT の範囲外の frame 項目が 936 -> 2,266)。一覧の行にメソッドが無ければ
 * そのクラスは丸ごと計算し直す(LambdaMatch はクラス名しか出さない)。
 *
 * <p>共通の親クラスは、classes と与えた jar から {@link ClassReader} で継承関係だけ読んで決める
 * (クラスを初期化しない)。
 *
 * <pre>java -cp ... dev.shifu.lvtmatch.FrameFix &lt;classes&gt; &lt;jar か jar のあるディレクトリ&gt;... -- &lt;一覧&gt;...</pre>
 * 一覧は「クラス」か「クラス メソッド名と記述子」の行。一覧を渡さなければ classes の下の全部。
 */
public final class FrameFix {
    private FrameFix() {
    }

    public static void main(String[] args) throws IOException {
        Path classes = Paths.get(args[0]);
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        // クラス -> 書き直すメソッド(名前 + 記述子)。whole に入っているクラスは丸ごと
        Map<String, Set<String>> targets = new HashMap<>();
        Set<String> whole = new HashSet<>();
        boolean lists = false;

        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                lists = true;
                continue;
            }

            Path entry = Paths.get(args[i]);

            if (lists) {
                if (!Files.isRegularFile(entry)) {
                    throw new IllegalStateException("一覧が無い: " + entry);
                }

                for (String line : Files.readAllLines(entry)) {
                    String trimmed = line.trim();

                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }

                    String[] parts = trimmed.split("\\s+");
                    targets.computeIfAbsent(parts[0], key -> new HashSet<>());

                    if (parts.length > 1) {
                        targets.get(parts[0]).add(parts[1]);
                    } else {
                        whole.add(parts[0]);
                    }
                }

                continue;
            }

            if (Files.isDirectory(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    for (Path jar : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                urls.add(entry.toUri().toURL());
            } else {
                // 黙って捨てると、ライブラリが 1 つも載らないまま「class not found」で
                // 全クラスが失敗する($PW/run-shifu/libraries は bundler を 1 度
                // 走らせるまで無いので、まっさらな $PW ではここに来る)。
                throw new IllegalStateException("クラスパスが無い: " + entry);
            }
        }

        // 一覧が無ければ classes の下の全部。触っていないクラスはあとで公式に戻る
        if (targets.isEmpty()) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String rel = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    String name = rel.substring(0, rel.length() - ".class".length());
                    targets.computeIfAbsent(name, key -> new HashSet<>());
                    whole.add(name);
                }
            }
        }

        int rewritten = 0;
        int methods = 0;
        int failed = 0;

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Hierarchy hierarchy = new Hierarchy(loader);

            for (Map.Entry<String, Set<String>> target : targets.entrySet()) {
                String name = target.getKey();
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
                    byte[] all = writer.toByteArray();

                    if (whole.contains(name)) {
                        Files.write(file, all);
                        rewritten++;
                        continue;
                    }

                    int grafted = graft(before, all, target.getValue(), file);

                    if (grafted > 0) {
                        rewritten++;
                        methods += grafted;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    System.err.println("FrameFix: " + name + ": " + e);
                }
            }
        }

        System.out.println("frames recomputed: " + rewritten + " classes, " + methods + " methods");
        System.out.println("frames failed    : " + failed);

        if (failed != 0) {
            throw new IllegalStateException("failed to recompute stackmap frames for " + failed + " classes");
        }
    }

    /**
     * 計算し直した版から、一覧のメソッドだけを元のクラスへ移す。
     *
     * <p>COMPUTE_FRAMES はクラス単位でしか効かないので、いったん全部を計算し直してから
     * 要るメソッドだけ差し替える。ほかのメソッドは javac が付けた frame のまま残る。
     */
    private static int graft(byte[] before, byte[] all, Set<String> wanted, Path file) throws IOException {
        ClassNode original = read(before);
        ClassNode recomputed = read(all);
        int moved = 0;

        for (int i = 0; i < original.methods.size(); i++) {
            MethodNode mine = original.methods.get(i);

            if (!wanted.contains(mine.name + mine.desc)) {
                continue;
            }

            for (MethodNode fresh : recomputed.methods) {
                if (fresh.name.equals(mine.name) && fresh.desc.equals(mine.desc)) {
                    original.methods.set(i, fresh);
                    moved++;
                    break;
                }
            }
        }

        if (moved == 0) {
            return 0;
        }

        ClassWriter writer = new ClassWriter(0);
        original.accept(writer);
        Files.write(file, writer.toByteArray());

        return moved;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        return node;
    }

    /** 継承関係だけを読む。クラスは初期化しない。 */
    private static final class Hierarchy {
        private final ClassLoader loader;
        private final Map<String, ClassInfo> parents = new HashMap<>();

        private record ClassInfo(String superName, boolean interfaceType, List<String> interfaces) {
        }

        Hierarchy(ClassLoader loader) {
            this.loader = loader;
        }

        private ClassInfo info(String name) {
            ClassInfo cached = parents.get(name);

            if (cached != null) {
                return cached;
            }

            ClassInfo result;

            try (InputStream in = open(name)) {
                if (in == null) {
                    throw new IllegalStateException("class not found: " + name);
                }

                ClassReader reader = new ClassReader(in);
                boolean iface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
                result = new ClassInfo(reader.getSuperName(), iface, List.of(reader.getInterfaces()));
            } catch (IOException e) {
                throw new IllegalStateException("could not read class hierarchy for " + name, e);
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
            return !name.startsWith("[") && info(name).interfaceType();
        }

        private boolean isAssignableFrom(String parent, String child) {
            if (parent.equals(child)) {
                return true;
            }

            if (parent.startsWith("[")) {
                if (!child.startsWith("[")) {
                    return false;
                }

                String parentComponent = parent.substring(1);
                String childComponent = child.substring(1);

                if (isPrimitiveDescriptor(parentComponent) || isPrimitiveDescriptor(childComponent)) {
                    return parentComponent.equals(childComponent);
                }

                return isAssignableFrom(referenceName(parentComponent), referenceName(childComponent));
            }

            if (child.startsWith("[")) {
                return "java/lang/Object".equals(parent)
                        || "java/lang/Cloneable".equals(parent)
                        || "java/io/Serializable".equals(parent);
            }

            List<String> pending = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            pending.add(child);

            for (int i = 0; i < pending.size(); i++) {
                String current = pending.get(i);

                if (!seen.add(current)) {
                    continue;
                }

                if (parent.equals(current)) {
                    return true;
                }

                ClassInfo currentInfo = info(current);

                if (currentInfo.superName() != null) {
                    pending.add(currentInfo.superName());
                }
                pending.addAll(currentInfo.interfaces());
            }

            return false;
        }

        private static boolean isPrimitiveDescriptor(String descriptor) {
            return descriptor.length() == 1 && "ZCBSIFJD".indexOf(descriptor.charAt(0)) >= 0;
        }

        private static String referenceName(String descriptor) {
            if (descriptor.startsWith("[")) {
                return descriptor;
            }
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                return descriptor.substring(1, descriptor.length() - 1);
            }
            throw new IllegalArgumentException("not a reference descriptor: " + descriptor);
        }

        /** ClassWriter.getCommonSuperClass と同じ規則。互いに代入できない interface は Object に落とす。 */
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

            if (a.startsWith("[") || b.startsWith("[")) {
                return "java/lang/Object";
            }

            String current = a;

            do {
                current = info(current).superName();

                if (current == null) {
                    return "java/lang/Object";
                }
            } while (!isAssignableFrom(current, b));

            return current;
        }
    }
}
