package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Paper の access transformer(AT)が広げた可視性を、コンパイル済みのクラスの修飾子にだけ書き写す。
 * 命令列には触らない。
 *
 * <pre>
 * java -cp tools/build/lvtmatch dev.shifu.lvtmatch.AtFlags &lt;クラスの置き場&gt; &lt;AT&gt; &lt;公式の jar&gt;
 * </pre>
 *
 * <p>1.19.4 / 1.18.2 の vanilla の木は、paperweight の逆コンパイル(ForgeFlower)が通らないので
 * {@code fixJar.jar} を Vineflower で戻し直している(docs/DEVELOPING.md)。{@code fixJar.jar} は
 * AT を当てる前の jar なので、この木から組んだクラスは Paper が広げた可視性を持たない
 * (9-16 のビルドで 1.19.4 は 64 件、1.18.2 は 65 件。{@code Level.rainLevel} が protected のまま、
 * {@code SimpleContainer.items} が private のまま、など)。Paper の jar に向けて書かれたプラグインが
 * これを使うと、実行時に {@code IllegalAccessError} になる。
 * {@code keep_vanilla_classes.py} が公式に戻したクラスは、classic では AT を当てたあとの
 * {@code minecraft.jar} から取るので広がっている。mache の木は {@code paper ATs} のコミットを含むので、
 * そこから組んだクラスも広がっている。どちらもここでは何も変わらない。
 *
 * <p>広げるだけで、狭めない(patches/access が AT より広げたものはそのまま)。{@code -f} は final を外す。
 * クラスへの AT は、そのクラスの修飾子と、全クラスの InnerClasses の項目を書き換える
 * (paperweight の {@code applyMergedAt.jar} も {@code ServerLoginPacketListenerImpl$1} の項目まで
 * 書き換えている)。AT の行のうち、公式の jar に無いクラスや、記述子が spigot の名前のままで
 * 当たらないメンバーは飛ばす(paperweight も当てていない)。
 *
 * <p>private のメソッドを広げると、同じ名前と記述子を持つ子や親のメソッドとの間に上書きの関係が
 * 新しくでき、invokevirtual の行き先が変わる。パッケージ内のメソッドを別パッケージの子や親と
 * 並べたときも同じ。そうなるメソッドは広げずに {@code override} として出す。
 */
public final class AtFlags {
    private static final int VISIBILITY = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;

    /** AT の 1 行。member は欄なら名前、メソッドなら名前 + 記述子、クラスなら null。 */
    private record Entry(String owner, String member, int visibility, boolean unfinal) {
    }

    private static final Map<String, ClassNode> hierarchy = new HashMap<>();
    private static final Map<String, List<String>> children = new HashMap<>();

    public static void main(final String[] args) throws IOException {
        final Path classes = Path.of(args[0]);
        final List<Entry> entries = parse(Path.of(args[1]));
        final List<Path> files = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        }

        final Set<String> ownFiles = new HashSet<>();

        for (final Path file : files) {
            final String name = classes.relativize(file).toString().replace('\\', '/');
            ownFiles.add(name.substring(0, name.length() - ".class".length()));
            index(Files.readAllBytes(file));
        }

        final Set<String> vanilla = new HashSet<>();

        try (JarFile jar = new JarFile(args[2])) {
            for (final JarEntry e : (Iterable<JarEntry>) jar.stream()::iterator) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }

                final String name = e.getName().substring(0, e.getName().length() - ".class".length());
                vanilla.add(name);

                if (!ownFiles.contains(name)) {
                    try (InputStream in = jar.getInputStream(e)) {
                        index(in.readAllBytes());
                    }
                }
            }
        }

        for (final ClassNode node : hierarchy.values()) {
            if (node.superName != null) {
                children.computeIfAbsent(node.superName, k -> new ArrayList<>()).add(node.name);
            }

            for (final String itf : node.interfaces) {
                children.computeIfAbsent(itf, k -> new ArrayList<>()).add(node.name);
            }
        }

        final Map<String, Entry> members = new HashMap<>();
        final Map<String, Entry> types = new HashMap<>();

        for (final Entry e : entries) {
            if (!vanilla.contains(e.owner)) {
                continue;
            }

            if (e.member == null) {
                types.put(e.owner, e);
            } else {
                members.put(e.owner + " " + e.member, e);
            }
        }

        int changedClasses = 0;
        int changedMembers = 0;
        int skipped = 0;

        for (final Path file : files) {
            final String name = classes.relativize(file).toString().replace('\\', '/');
            final String internal = name.substring(0, name.length() - ".class".length());

            if (!vanilla.contains(internal)) {
                continue;
            }

            final byte[] bytes = Files.readAllBytes(file);
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, 0);
            // クラスの修飾子、メンバー、上書きができるので広げなかったメソッド
            final int[] count = {0, 0, 0};

            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(final int version, final int access, final String n, final String signature,
                                  final String superName, final String[] interfaces) {
                    final Entry e = types.get(n);
                    // クラスの修飾子に private / protected は無い(InnerClasses の側で持つ)。public だけ見る
                    final int to = e == null ? access : widen(access, e) & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                    count[0] += to != access ? 1 : 0;
                    super.visit(version, to, n, signature, superName, interfaces);
                }

                @Override
                public void visitInnerClass(final String n, final String outer, final String inner, final int access) {
                    final Entry e = types.get(n);
                    final int to = e == null ? access : widen(access, e);
                    count[0] += to != access ? 1 : 0;
                    super.visitInnerClass(n, outer, inner, to);
                }

                @Override
                public FieldVisitor visitField(final int access, final String n, final String desc,
                                               final String signature, final Object value) {
                    final Entry e = members.get(internal + " " + n);
                    final int to = e == null ? access : widen(access, e);
                    count[1] += to != access ? 1 : 0;
                    return super.visitField(to, n, desc, signature, value);
                }

                @Override
                public MethodVisitor visitMethod(final int access, final String n, final String desc,
                                                 final String signature, final String[] exceptions) {
                    final Entry e = members.get(internal + " " + n + desc);
                    int to = e == null ? access : widen(access, e);

                    if (to != access && overrides(internal, n, desc, access, to)) {
                        to = access;
                        count[2]++;
                    }

                    count[1] += to != access ? 1 : 0;
                    return super.visitMethod(to, n, desc, signature, exceptions);
                }
            }, 0);

            skipped += count[2];

            if (count[0] + count[1] > 0) {
                Files.write(file, writer.toByteArray());
                changedClasses++;
                changedMembers += count[1];
            }
        }

        System.out.println("AT の可視性を書き写したクラス: " + changedClasses + "(メンバー " + changedMembers
                + "、上書きができるので広げなかったメソッド " + skipped + ")");
    }

    /** 可視性は広げるだけ。-f なら final を外す。 */
    private static int widen(final int access, final Entry e) {
        int to = access;

        if (rank(e.visibility) > rank(access & VISIBILITY)) {
            to = (to & ~VISIBILITY) | e.visibility;
        }

        if (e.unfinal) {
            to &= ~Opcodes.ACC_FINAL;
        }

        return to;
    }

    private static int rank(final int visibility) {
        if ((visibility & Opcodes.ACC_PUBLIC) != 0) {
            return 3;
        }

        if ((visibility & Opcodes.ACC_PROTECTED) != 0) {
            return 2;
        }

        return (visibility & Opcodes.ACC_PRIVATE) != 0 ? 0 : 1;
    }

    /**
     * 広げたあと、親か子の同じ名前・記述子のインスタンスメソッドと上書きの関係ができるか。
     * 前から上書きしていた組(パッケージ内のまま、または protected 以上どうし)は数えない。
     */
    private static boolean overrides(final String owner, final String name, final String desc, final int from,
                                     final int to) {
        if ((from & Opcodes.ACC_STATIC) != 0 || name.startsWith("<")) {
            return false;
        }

        final List<String> hits = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final List<String> queue = new ArrayList<>(children.getOrDefault(owner, List.of()));

        while (!queue.isEmpty()) {
            final String c = queue.remove(queue.size() - 1);

            if (seen.add(c)) {
                if (declares(c, name, desc) != null && !linked(owner, from, c, declares(c, name, desc).access)) {
                    hits.add(c);
                }

                queue.addAll(children.getOrDefault(c, List.of()));
            }
        }

        final ClassNode self = hierarchy.get(owner);
        final List<String> up = new ArrayList<>();

        if (self.superName != null) {
            up.add(self.superName);
        }

        up.addAll(self.interfaces);

        while (!up.isEmpty()) {
            final String c = up.remove(up.size() - 1);
            final ClassNode node = hierarchy.get(c);

            if (node == null || !seen.add(c)) {
                continue;
            }

            final MethodNode m = declares(c, name, desc);

            if (m != null && (m.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0
                    && !linked(c, m.access, owner, from)) {
                hits.add(c);
            }

            if (node.superName != null) {
                up.add(node.superName);
            }

            up.addAll(node.interfaces);
        }

        for (final String c : hits) {
            System.out.println("override " + owner + "." + name + desc + " " + Integer.toHexString(from) + "->"
                    + Integer.toHexString(to) + " " + c);
        }

        return !hits.isEmpty();
    }

    private static MethodNode declares(final String type, final String name, final String desc) {
        final ClassNode node = hierarchy.get(type);

        if (node == null) {
            return null;
        }

        for (final MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_STATIC) == 0) {
                return m;
            }
        }

        return null;
    }

    /** 広げる前から、sub のメソッドが sup のメソッドを上書きしていたか(JVMS 5.4.5)。 */
    private static boolean linked(final String sup, final int supAccess, final String sub, final int subAccess) {
        if ((supAccess & Opcodes.ACC_PRIVATE) != 0 || (subAccess & Opcodes.ACC_PRIVATE) != 0) {
            return false;
        }

        if ((supAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
            return true;
        }

        return packageOf(sup).equals(packageOf(sub));
    }

    private static String packageOf(final String internal) {
        final int at = internal.lastIndexOf('/');

        return at < 0 ? "" : internal.substring(0, at);
    }

    private static void index(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        hierarchy.put(node.name, node);
    }

    /** {@code public-f net.minecraft.Foo bar} / {@code public net.minecraft.Foo baz(I)V} / {@code public net.minecraft.Foo$Bar}。 */
    private static List<Entry> parse(final Path at) throws IOException {
        final List<Entry> out = new ArrayList<>();

        for (final String raw : Files.readAllLines(at, StandardCharsets.UTF_8)) {
            final int hash = raw.indexOf('#');
            final String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();

            if (line.isEmpty()) {
                continue;
            }

            final String[] parts = line.split("\\s+");
            String modifier = parts[0];
            final boolean unfinal = modifier.endsWith("-f");

            if (unfinal) {
                modifier = modifier.substring(0, modifier.length() - 2);
            }

            final int visibility = switch (modifier) {
                case "public" -> Opcodes.ACC_PUBLIC;
                case "protected" -> Opcodes.ACC_PROTECTED;
                case "default" -> 0;
                case "private" -> Opcodes.ACC_PRIVATE;
                default -> throw new IllegalStateException("読めない AT の修飾子: " + raw);
            };

            final String owner = parts[1].replace('.', '/');
            out.add(new Entry(owner, parts.length > 2 ? parts[2] : null, visibility, unfinal));
        }

        return out;
    }
}
