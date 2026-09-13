package dev.shifu.lvtmatch;

import java.io.IOException;
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
 * java -cp tools/build/lvtmatch dev.shifu.lvtmatch.LinkCheck <クラスの置き場> <戻したクラスの一覧> <出力先> [公式の jar]
 * </pre>
 *
 * <p>{@code -TouchedOnly} で組むと、Shifu が触っていない vanilla のクラスは Paper の版で compile され、
 * postcompile で公式のバイトコードに戻る({@code keep_vanilla_classes.py})。Paper がそのクラスに足した
 * 欄やメソッド(1.19.4 の {@code TicketType.PLUGIN})を別のクラスが参照していると、compile は通るのに
 * 起動時に {@code NoSuchFieldError} / {@code NoSuchMethodError} になる。ここで、戻したクラスを
 * 持ち主とする参照を全部集め、置き場にあるクラス(= 実行時に載るもの)で解決できるかを見る。
 * 親クラスや interface が置き場に無いもの(JDK やライブラリ)まで辿ったら、あるものとみなす。
 * {@code -TouchedOnly} では触っていないクラスは置き場にも無い(実行時は公式 jar から載る)ので、
 * 公式の jar を渡してそこから読む。vanilla の型が jar にも無ければ「無い」。
 *
 * <p>出力は 1 行 1 件: {@code <持ち主> <名前> <記述子> <参照元> <missing|access>}。
 * {@code tools/link_to_required.py} がこれを required-members.txt の形に直す。
 */
public final class LinkCheck {
    private static Path root;
    private static JarFile official;
    private static final Map<String, ClassNode> loaded = new HashMap<>();
    private static final Set<String> missingClasses = new HashSet<>();

    public static void main(final String[] args) throws IOException {
        root = Path.of(args[0]);
        official = args.length > 3 ? new JarFile(args[3]) : null;
        final Set<String> kept = new HashSet<>();

        for (final String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            final String rel = line.trim();

            if (rel.endsWith(".class")) {
                kept.add(rel.substring(0, rel.length() - ".class".length()));
            }
        }

        final Set<String> missing = new TreeSet<>();
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
                    if (insn instanceof FieldInsnNode f && kept.contains(f.owner)) {
                        check(missing, internal, method.name, f.owner, f.name, f.desc, true);
                    } else if (insn instanceof MethodInsnNode m && kept.contains(m.owner)) {
                        check(missing, internal, method.name, m.owner, m.name, m.desc, false);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (final Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle h && kept.contains(h.getOwner())) {
                                // H_GETFIELD=1 .. H_PUTSTATIC=4 が欄、それ以外はメソッド
                                check(missing, internal, method.name, h.getOwner(), h.getName(), h.getDesc(),
                                        h.getTag() >= 1 && h.getTag() <= 4);
                            }
                        }
                    }
                }
            }
        }

        Files.write(Path.of(args[2]), missing, StandardCharsets.UTF_8);

        for (final String line : missing) {
            System.out.println(line);
        }

        System.out.println("missing " + missing.size());
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

    /**
     * 参照先が無い、または参照元から見えない(private・パッケージ内・別パッケージからの protected)ものを記録する。
     * 見えないものは、Paper が可視性を広げた宣言を Paper の版で compile したあと公式に戻ったときに
     * {@code IllegalAccessError} になる(1.19.4 の {@code CompoundTag.tags} を CraftItemStack が読む)。
     * 出力の 5 つめは {@code missing} か {@code access}。
     */
    private static void check(final Set<String> missing, final String from, final String fromMethod,
                              final String owner, final String name, final String desc, final boolean field) throws IOException {
        final int access = field ? fieldAccess(owner, name, desc) : methodAccess(owner, name, desc);

        if (access < 0) {
            missing.add(owner + " " + name + " " + desc + " " + from + "." + fromMethod + " missing");
            return;
        }

        if (access == Integer.MAX_VALUE || (access & Opcodes.ACC_PUBLIC) != 0) {
            return;
        }

        final String fromOuter = from.contains("$") ? from.substring(0, from.indexOf('$')) : from;
        final String ownerOuter = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;

        if (fromOuter.equals(ownerOuter)) {
            return;
        }

        final boolean samePackage = packageOf(from).equals(packageOf(owner));

        if ((access & Opcodes.ACC_PRIVATE) != 0
                || (!samePackage && ((access & Opcodes.ACC_PROTECTED) == 0 || !isSubclass(from, owner)))) {
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

    /** 欄の修飾子。無ければ -1、置き場に無い型(JDK やライブラリ)まで辿ったら Integer.MAX_VALUE(あるものとみなす)。 */
    // 親や interface を辿る途中で MAX_VALUE が返ると「ある」になる。vanilla の型は必ず置き場にあるので -1
    private static int fieldAccess(final String owner, final String name, final String desc) throws IOException {
        if (owner.equals("java/lang/Object")) {
            return -1;
        }

        final ClassNode node = load(owner);

        if (node == null) {
            // vanilla の型は置き場か公式の jar にある。どちらにも無いのはライブラリ(JDK、netty、brigadier)の型
            return owner.startsWith("net/minecraft/") ? -1 : Integer.MAX_VALUE;
        }

        for (final FieldNode f : node.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) {
                return f.access;
            }
        }

        for (final String itf : node.interfaces) {
            final int found = fieldAccess(itf, name, desc);

            if (found >= 0) {
                return found;
            }
        }

        return node.superName == null ? -1 : fieldAccess(node.superName, name, desc);
    }

    private static int methodAccess(final String owner, final String name, final String desc) throws IOException {
        if (owner.equals("java/lang/Object")) {
            return OBJECT_METHODS.contains(name) ? Opcodes.ACC_PUBLIC : -1;
        }

        final ClassNode node = load(owner);

        if (node == null) {
            // vanilla の型は置き場か公式の jar にある。どちらにも無いのはライブラリ(JDK、netty、brigadier)の型
            return owner.startsWith("net/minecraft/") ? -1 : Integer.MAX_VALUE;
        }

        for (final MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m.access;
            }
        }

        if (name.equals("<init>")) {
            return -1;
        }

        for (final String itf : node.interfaces) {
            final int found = methodAccess(itf, name, desc);

            if (found >= 0) {
                return found;
            }
        }

        return node.superName == null ? -1 : methodAccess(node.superName, name, desc);
    }

    private static final Set<String> OBJECT_METHODS = Set.of(
            "<init>", "getClass", "hashCode", "equals", "clone", "toString", "notify", "notifyAll", "wait", "finalize");
}
