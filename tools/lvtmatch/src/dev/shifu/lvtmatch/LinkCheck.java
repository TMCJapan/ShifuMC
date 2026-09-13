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
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
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
 * java -cp tools/build/lvtmatch dev.shifu.lvtmatch.LinkCheck <クラスの置き場> <戻したクラスの一覧> <出力先>
 * </pre>
 *
 * <p>{@code -TouchedOnly} で組むと、Shifu が触っていない vanilla のクラスは Paper の版で compile され、
 * postcompile で公式のバイトコードに戻る({@code keep_vanilla_classes.py})。Paper がそのクラスに足した
 * 欄やメソッド(1.19.4 の {@code TicketType.PLUGIN})を別のクラスが参照していると、compile は通るのに
 * 起動時に {@code NoSuchFieldError} / {@code NoSuchMethodError} になる。ここで、戻したクラスを
 * 持ち主とする参照を全部集め、置き場にあるクラス(= 実行時に載るもの)で解決できるかを見る。
 * 親クラスや interface が置き場に無いもの(JDK やライブラリ)まで辿ったら、あるものとみなす。
 *
 * <p>出力は 1 行 1 件: {@code <持ち主> <名前> <記述子> <参照元>}。
 * {@code tools/link_to_required.py} がこれを required-members.txt の形に直す。
 */
public final class LinkCheck {
    private static Path root;
    private static final Map<String, ClassNode> loaded = new HashMap<>();
    private static final Set<String> missingClasses = new HashSet<>();

    public static void main(final String[] args) throws IOException {
        root = Path.of(args[0]);
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
                        if (!hasField(f.owner, f.name, f.desc)) {
                            missing.add(f.owner + " " + f.name + " " + f.desc + " " + internal + "." + method.name);
                        }
                    } else if (insn instanceof MethodInsnNode m && kept.contains(m.owner)) {
                        if (!hasMethod(m.owner, m.name, m.desc)) {
                            missing.add(m.owner + " " + m.name + " " + m.desc + " " + internal + "." + method.name);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (final Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle h && kept.contains(h.getOwner())) {
                                // H_GETFIELD=1 .. H_PUTSTATIC=4 が欄、それ以外はメソッド
                                final boolean field = h.getTag() >= 1 && h.getTag() <= 4;
                                final boolean ok = field ? hasField(h.getOwner(), h.getName(), h.getDesc())
                                        : hasMethod(h.getOwner(), h.getName(), h.getDesc());

                                if (!ok) {
                                    missing.add(h.getOwner() + " " + h.getName() + " " + h.getDesc() + " " + internal + "." + method.name);
                                }
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

        if (!Files.exists(file)) {
            missingClasses.add(internal);
            return null;
        }

        final ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES);
        loaded.put(internal, node);

        return node;
    }

    private static final Set<String> OBJECT_METHODS = Set.of(
            "<init>", "getClass", "hashCode", "equals", "clone", "toString", "notify", "notifyAll", "wait", "finalize");

    /** 置き場に無い型(JDK やライブラリ)まで辿ったら、あるものとみなす。java/lang/Object だけは中身を知っているので見る。 */
    private static boolean hasField(final String owner, final String name, final String desc) throws IOException {
        if (owner.equals("java/lang/Object")) {
            return false;
        }

        final ClassNode node = load(owner);

        if (node == null) {
            return true;
        }

        for (final FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return true;
            }
        }

        for (final String itf : node.interfaces) {
            if (hasField(itf, name, desc)) {
                return true;
            }
        }

        return node.superName != null && hasField(node.superName, name, desc);
    }

    private static boolean hasMethod(final String owner, final String name, final String desc) throws IOException {
        if (owner.equals("java/lang/Object")) {
            return OBJECT_METHODS.contains(name);
        }

        final ClassNode node = load(owner);

        if (node == null) {
            return true;
        }

        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }

        if (name.equals("<init>")) {
            return false;
        }

        for (final String itf : node.interfaces) {
            if (hasMethod(itf, name, desc)) {
                return true;
            }
        }

        return node.superName != null && hasMethod(node.superName, name, desc);
    }
}
