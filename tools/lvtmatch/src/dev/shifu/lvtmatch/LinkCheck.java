package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 公式のバイトコードに戻したクラスに、残りのクラスが参照している欄やメソッドがあるかを確かめる。
 *
 * <pre>
 * java -cp tools/build/lvtmatch dev.shifu.lvtmatch.LinkCheck &lt;クラスの置き場&gt; &lt;戻したクラスの一覧&gt; &lt;出力先&gt; [公式の jar] [ライブラリ]...
 * </pre>
 *
 * <p>{@code -TouchedOnly} で組むと、Shifu が触っていない vanilla のクラスは Paper の版で compile され、
 * postcompile で公式のバイトコードに戻る({@code keep_vanilla_classes.py})。Paper がそのクラスに足した
 * 欄やメソッド(1.19.4 の {@code TicketType.PLUGIN})を別のクラスが参照していると、compile は通るのに
 * 起動時に {@code NoSuchFieldError} / {@code NoSuchMethodError} になる。ここで、戻したクラスを
 * 持ち主とする参照を全部集め、置き場にあるクラス(= 実行時に載るもの)で解決できるかを見る。
 * 持ち主が戻していないクラスでも、宣言が戻した親や interface にしか無いこと
 * (1.18.2 の {@code ServerLevel.addFreshEntity(Entity, SpawnReason)} は Paper が {@code LevelWriter} の
 * default で足したもの)があるので、持ち主で絞らず全部の参照を辿る。
 *
 * <p>クラスは置き場 → 公式の jar → JDK → 渡されたライブラリの順に読む。
 * 参照の持ち主そのものがどこにも無ければ、それはライブラリの型なので実行時のクラスパスに居るとみなす。
 * <b>親や interface を辿る途中で読めない型に当たったときは「ある」と答えない。</b>
 * {@code java/lang/Record} を経由する record の欄が全部素通りしていて、1.19.4 で 26 件、
 * 1.18.2 で 16 件の未解決の参照が 0 件と出ていた。読めなかったものは判定不能として
 * 出力先と同じディレクトリの {@code link-unknown.txt} に書く。
 *
 * <p>出力は 1 行 1 件: {@code <持ち主> <名前> <記述子> <参照元> <missing|access>}。
 * {@code tools/link_to_required.py} がこれを required-members.txt の形に直す。
 */
public final class LinkCheck {
    /** 親を辿る途中で読めない型に当たった。ある・無いのどちらとも言えない。 */
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private static Path root;
    private static JarFile official;
    private static URLClassLoader libraries;
    private static final Map<String, ClassNode> loaded = new HashMap<>();
    private static final Set<String> missingClasses = new HashSet<>();
    /** 直前の判定で読めなかった型。unknown の行に添える。 */
    private static String unreadable;
    /**
     * 直前の判定で宣言が見つかったクラス。見えるかどうかはこのクラスで決まる(JVMS 5.4.4)。
     * 参照の持ち主で比べていたころは、BlockBehaviour$BlockStateBase が {@code Block.attack}
     * (宣言は同じパッケージの BlockBehaviour の protected)を呼ぶのを、Block のパッケージが違うので
     * access と出していた(1.20.6 で 56 件のうち 56 件)。
     */
    private static String declaring;

    public static void main(final String[] args) throws IOException {
        root = Path.of(args[0]);
        official = args.length > 3 ? new JarFile(args[3]) : null;
        libraries = classpath(args);
        final Set<String> kept = new HashSet<>();

        for (final String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            final String rel = line.trim();

            if (rel.endsWith(".class")) {
                kept.add(rel.substring(0, rel.length() - ".class".length()));
            }
        }

        final Set<String> missing = new TreeSet<>();
        final Set<String> unknown = new TreeSet<>();
        final List<Path> files = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        }

        for (final Path file : files) {
            final String name = root.relativize(file).toString().replace('\\', '/');
            final String internal = name.substring(0, name.length() - ".class".length());

            if (kept.contains(internal)) {
                continue;
            }

            final ClassNode node = load(internal);

            if (node == null) {
                continue;
            }

            for (final MethodNode method : node.methods) {
                for (final AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode f) {
                        check(missing, unknown, internal, method.name, f.owner, f.name, f.desc, true);
                    } else if (insn instanceof MethodInsnNode m) {
                        check(missing, unknown, internal, method.name, m.owner, m.name, m.desc, false);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (final Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle h) {
                                // H_GETFIELD=1 .. H_PUTSTATIC=4 が欄、それ以外はメソッド
                                check(missing, unknown, internal, method.name, h.getOwner(), h.getName(), h.getDesc(),
                                        h.getTag() >= 1 && h.getTag() <= 4);
                            }
                        }
                    }
                }
            }
        }

        final Path out = Path.of(args[2]);
        Files.write(out, missing, StandardCharsets.UTF_8);
        Files.write(out.resolveSibling("link-unknown.txt"), unknown, StandardCharsets.UTF_8);

        for (final String line : missing) {
            System.out.println(line);
        }

        System.out.println("missing " + missing.size());
        System.out.println("unknown " + unknown.size() + " -> " + out.resolveSibling("link-unknown.txt"));
    }

    /** 4 つめより後ろは、親や interface を読むためのライブラリ(jar か jar のあるディレクトリ)。 */
    private static URLClassLoader classpath(final String[] args) throws IOException {
        final List<URL> urls = new ArrayList<>();

        for (int i = 4; i < args.length; i++) {
            final Path entry = Path.of(args[i]);

            if (Files.isDirectory(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    for (final Path jar : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                        urls.add(jar.toUri().toURL());
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                urls.add(entry.toUri().toURL());
            } else {
                throw new IllegalStateException("クラスパスが無い: " + entry);
            }
        }

        return urls.isEmpty() ? null : new URLClassLoader(urls.toArray(new URL[0]), null);
    }

    private static ClassNode load(final String internal) throws IOException {
        if (loaded.containsKey(internal)) {
            return loaded.get(internal);
        }

        if (missingClasses.contains(internal)) {
            return null;
        }

        final Path file = root.resolve(internal + ".class");
        byte[] bytes = null;

        if (Files.exists(file)) {
            bytes = Files.readAllBytes(file);
        } else if (official != null && official.getEntry(internal + ".class") != null) {
            try (var in = official.getInputStream(official.getEntry(internal + ".class"))) {
                bytes = in.readAllBytes();
            }
        } else {
            bytes = fromClasspath(internal);
        }

        if (bytes == null) {
            missingClasses.add(internal);
            return null;
        }

        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        loaded.put(internal, node);

        return node;
    }

    /** JDK(java/lang/Record、java/lang/Enum)と、渡されたライブラリから読む。 */
    private static byte[] fromClasspath(final String internal) throws IOException {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(internal + ".class")) {
            if (in != null) {
                return in.readAllBytes();
            }
        }

        if (libraries == null) {
            return null;
        }

        try (InputStream in = libraries.getResourceAsStream(internal + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /**
     * 参照先が無い、または参照元から見えない(private・パッケージ内・別パッケージからの protected)ものを記録する。
     * 見えないものは、Paper が可視性を広げた宣言を Paper の版で compile したあと公式に戻ったときに
     * {@code IllegalAccessError} になる(1.19.4 の {@code CompoundTag.tags} を CraftItemStack が読む)。
     * 出力の 5 つめは {@code missing} か {@code access}。
     */
    private static void check(final Set<String> missing, final Set<String> unknown, final String from,
                              final String fromMethod, final String owner, final String name, final String desc,
                              final boolean field) throws IOException {
        unreadable = null;
        declaring = owner;
        final int access = field ? fieldAccess(owner, name, desc, true) : methodAccess(owner, name, desc, true);

        if (access == UNKNOWN) {
            unknown.add(owner + " " + name + " " + desc + " " + from + "." + fromMethod + " unknown " + unreadable);
            return;
        }

        if (access < 0) {
            missing.add(owner + " " + name + " " + desc + " " + from + "." + fromMethod + " missing");
            return;
        }

        if (access == Integer.MAX_VALUE || (access & Opcodes.ACC_PUBLIC) != 0) {
            return;
        }

        final String fromOuter = from.contains("$") ? from.substring(0, from.indexOf('$')) : from;
        final String ownerOuter = declaring.contains("$") ? declaring.substring(0, declaring.indexOf('$')) : declaring;

        if (fromOuter.equals(ownerOuter)) {
            return;
        }

        final boolean samePackage = packageOf(from).equals(packageOf(declaring));

        if ((access & Opcodes.ACC_PRIVATE) != 0
                || (!samePackage && ((access & Opcodes.ACC_PROTECTED) == 0 || !isSubclass(from, declaring)))) {
            missing.add(owner + " " + name + " " + desc + " " + from + "." + fromMethod + " access");
        }
    }

    private static String packageOf(final String internal) {
        final int at = internal.lastIndexOf('/');

        return at < 0 ? "" : internal.substring(0, at);
    }

    private static boolean isSubclass(final String type, final String ancestor) throws IOException {
        String current = type;

        while (current != null) {
            if (current.equals(ancestor)) {
                return true;
            }

            final ClassNode node = load(current);
            current = node == null ? null : node.superName;
        }

        return false;
    }

    /**
     * 欄の修飾子。無ければ -1、読めない型まで辿ったら UNKNOWN。
     * 参照の持ち主そのものが読めないときだけ、ライブラリの型として Integer.MAX_VALUE(あるものとみなす)。
     */
    private static int fieldAccess(final String owner, final String name, final String desc, final boolean origin)
            throws IOException {
        if (owner.equals("java/lang/Object")) {
            return -1;
        }

        final ClassNode node = load(owner);

        if (node == null) {
            // vanilla の型は置き場か公式の jar にある。どちらにも無いのはライブラリ(netty、fastutil)の型
            if (owner.startsWith("net/minecraft/")) {
                return -1;
            }

            unreadable = owner;

            return origin ? Integer.MAX_VALUE : UNKNOWN;
        }

        for (final FieldNode f : node.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) {
                declaring = owner;
                return f.access;
            }
        }

        return up(node, name, desc, true);
    }

    private static int methodAccess(final String owner, final String name, final String desc, final boolean origin)
            throws IOException {
        if (owner.equals("java/lang/Object")) {
            return OBJECT_METHODS.contains(name) ? Opcodes.ACC_PUBLIC : -1;
        }

        // MethodHandle.invoke と VarHandle.compareAndExchange は signature polymorphic で、
        // 記述子を呼び出し側が作る。宣言と同じ記述子は無いので、名前で引くと全部「無い」になる
        // (1.20.6 で 225 件のうち 225 件がこれだった)。
        if (POLYMORPHIC.contains(owner)) {
            return Opcodes.ACC_PUBLIC;
        }

        final ClassNode node = load(owner);

        if (node == null) {
            if (owner.startsWith("net/minecraft/")) {
                return -1;
            }

            unreadable = owner;

            return origin ? Integer.MAX_VALUE : UNKNOWN;
        }

        for (final MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                declaring = owner;
                return m.access;
            }
        }

        if (name.equals("<init>")) {
            return -1;
        }

        return up(node, name, desc, false);
    }

    /** 親と interface を辿る。1 つでも読めないものがあれば、無いとは言えないので UNKNOWN を返す。 */
    private static int up(final ClassNode node, final String name, final String desc, final boolean field)
            throws IOException {
        boolean blind = false;

        for (final String itf : node.interfaces) {
            final int found = field ? fieldAccess(itf, name, desc, false) : methodAccess(itf, name, desc, false);

            if (found >= 0) {
                return found;
            }

            blind |= found == UNKNOWN;
        }

        if (node.superName != null) {
            final int found = field
                    ? fieldAccess(node.superName, name, desc, false)
                    : methodAccess(node.superName, name, desc, false);

            if (found >= 0) {
                return found;
            }

            blind |= found == UNKNOWN;
        }

        return blind ? UNKNOWN : -1;
    }

    private static final Set<String> OBJECT_METHODS = Set.of(
            "<init>", "getClass", "hashCode", "equals", "clone", "toString", "notify", "notifyAll", "wait", "finalize");

    /** 記述子を呼び出し側が作る型(JLS 15.12.3 の signature polymorphic)。 */
    private static final Set<String> POLYMORPHIC = Set.of(
            "java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle");
}
