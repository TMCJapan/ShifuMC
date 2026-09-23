// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeAnnotationNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * 無名クラス・局所クラスの番号と、それが捕まえた欄({@code val$…})の名前を Mojang の公式クラスに合わせる。
 *
 * <pre>java dev.shifu.lvtmatch.SyntheticMatch &lt;クラスの置き場&gt; &lt;Mojang の jar&gt; &lt;残したものの一覧&gt;</pre>
 *
 * <h2>何が違うか</h2>
 *
 * javac は捕まえた局所変数を {@code val$<局所変数名>} という欄にする。逆コンパイラが付けた
 * 局所変数の名前は Mojang のものと違うので、欄の名前も変わる。
 *
 * <pre>
 * 公式  : ServerGamePacketListenerImpl$1  val$level, val$target
 * こちら: ServerGamePacketListenerImpl$1  val$serverLevel, val$entity
 * </pre>
 *
 * Fabric API の {@code fabric-events-interaction-v0} はこの欄を {@code @Shadow} しているので、
 * エンティティを右クリック/攻撃した時点で {@code InvalidMixinException} で落ちる。
 *
 * <p>無名クラスの番号も違う。Mojang の server jar はクライアント専用の無名クラスが抜けた穴の
 * ある番号のままで、コンパイルし直すと javac が詰め直す。1.20.6 の {@code Util$10} は公式が
 * {@code Function} のメモ化、こちらは {@code ThreadFactory} で、refmap の {@code Util$10} が別のクラスを指す。
 *
 * <h2>やること</h2>
 *
 * <ol>
 * <li>同じ外側のクラスの無名クラスどうしで、囲みのメソッド・親と interface・捕まえた欄の型・
 *     欄とメソッドの顔ぶれが全部同じものを対応させる。同じ顔ぶれが複数あるときは、
 *     数が同じなら番号の順に対応させ、数が違えばどれも触らない。Paper が欄やメソッドを足したものは、
 *     それ以外が同じで 1 対 1 に決まるときだけ対応させる</li>
 * <li>対応した公式の名前へクラスを付け替える。行き先を別の(対応の取れない)クラスが使っていれば、
 *     そのクラスは公式にもこちらにも無い番号へ退かす</li>
 * <li>対応した組の {@code val$} を、型の並びが同じなら位置で、型がどれも 1 つずつなら型で付け替える</li>
 * <li>付け替えた名前を、置き場の全クラスの参照(命令、InnerClasses、NestHost/NestMembers、
 *     EnclosingMethod、署名、frame、注釈)に当てる</li>
 * </ol>
 *
 * **命令の数も種類も変わらない。名前だけ。** 対応が取れなかったものは一覧に書いて残す。
 *
 * <p>asm-commons の {@code ClassRemapper} を使わないのは、tools/lvtmatch/build.sh が
 * asm / asm-tree / asm-analysis しか載せないため。
 */
public final class SyntheticMatch {

    /** {@code <外側>$<番号><局所クラスの名前>}。無名クラスは名前が空。 */
    private static final Pattern LOCAL = Pattern.compile("^(.*)\\$(\\d+)([^$/]*)$");

    /** 置き場のクラス名 -> ファイル。 */
    private static final Map<String, Path> files = new HashMap<>();
    /** 置き場のクラス名 -> 読んだノード(命令は読まない)。無名・局所クラスだけ。 */
    private static final Map<String, ClassNode> ours = new HashMap<>();
    /** 公式の外側のクラス -> その無名・局所クラスの名前。 */
    private static final Map<String, List<String>> officialByOuter = new HashMap<>();
    private static final Set<String> officialNames = new HashSet<>();
    /** 付け替えるクラス名(元 -> 先)。入れ子は外側の付け替えから導く。 */
    private static final Map<String, String> classRename = new HashMap<>();
    /** 付け替える欄(元の持ち主 + "." + 名前 + 型 -> 新しい名前)。 */
    private static final Map<String, String> fieldRename = new HashMap<>();
    private static final List<String> left = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        Path jar = Paths.get(args[1]);
        Path report = Paths.get(args[2]);

        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String rel = classes.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                files.put(rel.substring(0, rel.length() - ".class".length()), file);
            }
        }

        // 外側ごとのこちらの無名・局所クラス。浅いものから決める(入れ子の外側の名前が先に要る)
        Map<String, List<String>> oursByOuter = new TreeMap<>((a, b) -> {
            int depth = Long.compare(a.chars().filter(c -> c == '$').count(), b.chars().filter(c -> c == '$').count());

            return depth != 0 ? depth : a.compareTo(b);
        });

        for (String name : files.keySet()) {
            Matcher m = LOCAL.matcher(name);

            if (!m.matches()) {
                continue;
            }

            ClassNode node = read(Files.readAllBytes(files.get(name)), ClassReader.SKIP_CODE);

            if (!isLocal(node, m.group(3))) {
                continue;
            }

            ours.put(name, node);
            oursByOuter.computeIfAbsent(m.group(1), key -> new ArrayList<>()).add(name);
        }

        int movedClasses = 0;
        int displaced = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                String entry = e.nextElement().getName();

                if (!entry.endsWith(".class")) {
                    continue;
                }

                String name = entry.substring(0, entry.length() - ".class".length());
                officialNames.add(name);
                Matcher m = LOCAL.matcher(name);

                if (m.matches()) {
                    officialByOuter.computeIfAbsent(m.group(1), key -> new ArrayList<>()).add(name);
                }
            }

            Map<String, ClassNode> theirsCache = new HashMap<>();

            for (Map.Entry<String, List<String>> group : oursByOuter.entrySet()) {
                String outer = group.getKey();
                List<String> theirNames = officialByOuter.get(mapClass(outer));

                if (theirNames == null) {
                    continue;   // 公式に無い外側(Paper が足したクラスなど)
                }

                Map<String, ClassNode> theirs = new HashMap<>();

                for (String name : theirNames) {
                    ClassNode node = theirsCache.get(name);

                    if (node == null) {
                        try (InputStream in = zip.getInputStream(zip.getEntry(name + ".class"))) {
                            node = read(in.readAllBytes(), ClassReader.SKIP_CODE);
                        }

                        theirsCache.put(name, node);
                    }

                    Matcher m = LOCAL.matcher(name);
                    m.matches();

                    if (isLocal(node, m.group(3))) {
                        theirs.put(name, node);
                    }
                }

                int[] counts = matchGroup(outer, group.getValue(), theirs);
                movedClasses += counts[0];
                displaced += counts[1];
            }
        }

        rewrite(classes);

        Files.write(report, left, StandardCharsets.UTF_8);
        System.out.println("renamed class      : " + movedClasses);
        System.out.println("moved out of the way: " + displaced);
        System.out.println("renamed val$ field : " + fieldRename.size());
        System.out.println("left alone         : " + left.size() + " (" + report + ")");
    }

    /** InnerClasses の自分の行で、無名クラスか局所クラスかを確かめる。名前の形だけでは決めない。 */
    private static boolean isLocal(ClassNode node, String simple) {
        for (InnerClassNode inner : node.innerClasses) {
            if (inner.name.equals(node.name)) {
                return simple.isEmpty() ? inner.innerName == null
                        : inner.outerName == null && simple.equals(inner.innerName);
            }
        }

        return false;
    }

    /**
     * 1 つの外側のクラスの無名・局所クラスを対応させ、付け替えを積む。
     *
     * @return {付け替えたクラスの数, 退かしたクラスの数}
     */
    private static int[] matchGroup(String outer, List<String> mine, Map<String, ClassNode> theirs) {
        Map<String, List<String>> mineByKey = new LinkedHashMap<>();
        Map<String, List<String>> theirsByKey = new HashMap<>();

        for (String name : sortByNumber(mine)) {
            mineByKey.computeIfAbsent(key(ours.get(name), true), k -> new ArrayList<>()).add(name);
        }

        for (String name : sortByNumber(new ArrayList<>(theirs.keySet()))) {
            theirsByKey.computeIfAbsent(key(theirs.get(name), false), k -> new ArrayList<>()).add(name);
        }

        Map<String, String> pairs = new LinkedHashMap<>();   // こちらの元の名前 -> 公式の名前
        List<String> unpaired = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : mineByKey.entrySet()) {
            List<String> from = entry.getValue();
            List<String> to = theirsByKey.getOrDefault(entry.getKey(), List.of());

            if (from.size() != to.size()) {
                unpaired.addAll(from);

                if (!to.isEmpty()) {
                    left.add("ambiguous " + from + " -> " + to + " (同じ顔ぶれの数が違う)");
                }

                continue;
            }

            for (int i = 0; i < from.size(); i++) {
                pairs.put(from.get(i), to.get(i));
            }
        }

        // Paper が無名クラスに欄やメソッドを足していると顔ぶれが揃わない(1.21.11 の
        // RegistrySetBuilder$2 は getValueForCopying が増えていて、val$ の名前が直らなかった)。
        // 残ったものどうしで、欄とメソッド以外が同じで、公式の欄とメソッドが全部こちらにもあり、
        // それが 1 対 1 に決まるものだけ対応させる
        Map<String, List<String>> mineCore = new HashMap<>();
        Map<String, List<String>> theirsCore = new HashMap<>();

        for (String name : unpaired) {
            mineCore.computeIfAbsent(core(ours.get(name), true), k -> new ArrayList<>()).add(name);
        }

        for (String name : theirs.keySet()) {
            if (!pairs.containsValue(name)) {
                theirsCore.computeIfAbsent(core(theirs.get(name), false), k -> new ArrayList<>()).add(name);
            }
        }

        for (Map.Entry<String, List<String>> entry : mineCore.entrySet()) {
            List<String> to = theirsCore.get(entry.getKey());

            if (entry.getValue().size() != 1 || to == null || to.size() != 1) {
                continue;
            }

            String from = entry.getValue().get(0);

            if (members(ours.get(from), true).containsAll(members(theirs.get(to.get(0)), false))) {
                pairs.put(from, to.get(0));
                unpaired.remove(from);
            }
        }

        // 行き先の名前。対応の取れないクラスがその名前にいれば退かす
        Set<String> targets = new HashSet<>(pairs.values());
        Map<String, String> renames = new LinkedHashMap<>();

        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if (!mapClass(pair.getKey()).equals(pair.getValue())) {
                renames.put(pair.getKey(), pair.getValue());
            }
        }

        Set<String> used = new HashSet<>(targets);

        for (String name : mine) {
            if (!pairs.containsKey(name)) {
                used.add(mapClass(name));
            }
        }

        int top = 0;

        for (String name : mine) {
            top = Math.max(top, number(name));
        }

        for (String name : theirs.keySet()) {
            top = Math.max(top, number(name));
        }

        int displaced = 0;
        String newOuter = mapClass(outer);

        for (String name : unpaired) {
            String now = mapClass(name);

            if (theirs.containsKey(now)) {
                left.add("unmatched " + name + " (公式の " + now + " と顔ぶれが違う"
                        + (targets.contains(now) ? "。行き先に使うので退かす)" : "。名前はそのまま)"));
            } else {
                left.add("unmatched " + name + " (公式に対応するクラスが無い)");
            }

            if (!targets.contains(now)) {
                continue;
            }

            Matcher m = LOCAL.matcher(name);
            m.matches();
            String fresh;

            do {
                fresh = newOuter + "$" + (++top) + m.group(3);
            } while (officialNames.contains(fresh) || files.containsKey(fresh) || used.contains(fresh));

            used.add(fresh);
            renames.put(name, fresh);
            displaced++;
        }

        // 行き先が重ならないこと
        Map<String, String> finals = new HashMap<>();

        for (String name : mine) {
            String to = renames.containsKey(name) ? renames.get(name) : mapClass(name);
            String other = finals.put(to, name);

            if (other != null) {
                left.add("skipped " + outer + " (" + other + " と " + name + " がどちらも " + to + " になる)");

                return new int[] {0, 0};
            }
        }

        classRename.putAll(renames);

        // 行き先に、無名・局所クラスと認めなかったファイルが居座っていないこと
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            String to = rename.getValue();

            if (files.containsKey(to) && mapClass(to).equals(to)) {
                left.add("skipped " + outer + " (" + rename.getKey() + " の行き先 " + to + " に別のクラスがある)");
                renames.keySet().forEach(classRename::remove);

                return new int[] {0, 0};
            }
        }

        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            matchFields(ours.get(pair.getKey()), theirs.get(pair.getValue()));
        }

        return new int[] {renames.size() - displaced, displaced};
    }

    /** 対応の鍵。こちらの側は付け替え済みの名前で書く。 */
    private static String key(ClassNode node, boolean mine) {
        return core(node, mine) + "|" + members(node, mine);
    }

    /** 欄とメソッドを除いた鍵。捕まえた欄は型の顔ぶれだけ(名前は合わせる対象、並びは matchFields で見る)。 */
    private static String core(ClassNode node, boolean mine) {
        StringBuilder out = new StringBuilder();
        Matcher m = LOCAL.matcher(node.name);
        m.matches();
        out.append(m.group(3)).append('|');
        out.append(mine ? mapClass(node.outerClass) : node.outerClass).append('.').append(node.outerMethod)
                .append(node.outerMethodDesc == null ? "" : mine ? mapDesc(node.outerMethodDesc) : node.outerMethodDesc)
                .append('|');
        out.append(mine ? mapClass(node.superName) : node.superName).append('|');

        for (String itf : node.interfaces) {
            out.append(mine ? mapClass(itf) : itf).append(',');
        }

        out.append('|');
        List<String> captured = new ArrayList<>();

        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_SYNTHETIC) != 0 && (field.access & Opcodes.ACC_STATIC) == 0) {
                captured.add(mine ? mapDesc(field.desc) : field.desc);
            }
        }

        captured.sort(null);

        return out.append(captured).toString();
    }

    /** 捕まえた欄以外の欄とメソッド(名前ごと)。 */
    private static List<String> members(ClassNode node, boolean mine) {
        List<String> members = new ArrayList<>();

        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_SYNTHETIC) == 0 || (field.access & Opcodes.ACC_STATIC) != 0) {
                members.add("F" + field.name + ":" + (mine ? mapDesc(field.desc) : field.desc));
            }
        }

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_SYNTHETIC) != 0 || method.name.equals("<init>")
                    || method.name.equals("<clinit>")) {
                continue;
            }

            members.add("M" + method.name + (mine ? mapDesc(method.desc) : method.desc));
        }

        members.sort(null);

        return members;
    }

    /** 捕まえた欄の名前を公式に合わせる。並びも型でも決まらなければ触らない。 */
    private static void matchFields(ClassNode mine, ClassNode theirs) {
        List<FieldNode> from = valFields(mine);
        List<FieldNode> to = valFields(theirs);

        if (from.size() != to.size()) {
            left.add("fields " + mine.name + " (val$ の数が違う)");

            return;
        }

        Map<FieldNode, String> names = new HashMap<>();
        boolean sameOrder = true;

        for (int i = 0; i < from.size(); i++) {
            sameOrder &= mapDesc(from.get(i).desc).equals(to.get(i).desc);
        }

        if (sameOrder) {
            for (int i = 0; i < from.size(); i++) {
                names.put(from.get(i), to.get(i).name);
            }
        } else {
            Map<String, FieldNode> byDesc = new HashMap<>();

            for (FieldNode field : to) {
                if (byDesc.put(field.desc, field) != null) {
                    left.add("fields " + mine.name + " (並びが違い、同じ型の val$ が複数ある)");

                    return;
                }
            }

            for (FieldNode field : from) {
                FieldNode want = byDesc.get(mapDesc(field.desc));

                if (want == null || names.containsValue(want.name)) {
                    left.add("fields " + mine.name + " (val$ の型が合わない)");

                    return;
                }

                names.put(field, want.name);
            }
        }

        // 付け替えた後に同じ名前の欄が 2 つできないこと
        Set<String> after = new HashSet<>();

        for (FieldNode field : mine.fields) {
            if (!after.add(names.getOrDefault(field, field.name))) {
                left.add("fields " + mine.name + " (付け替えると欄の名前が重なる)");

                return;
            }
        }

        for (Map.Entry<FieldNode, String> entry : names.entrySet()) {
            FieldNode field = entry.getKey();

            if (!field.name.equals(entry.getValue())) {
                fieldRename.put(mine.name + "." + field.name + field.desc, entry.getValue());
            }
        }
    }

    private static List<FieldNode> valFields(ClassNode node) {
        List<FieldNode> out = new ArrayList<>();

        for (FieldNode field : node.fields) {
            if (field.name.startsWith("val$")) {
                out.add(field);
            }
        }

        return out;
    }

    private static List<String> sortByNumber(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.sort((a, b) -> {
            int n = Integer.compare(number(a), number(b));

            return n != 0 ? n : a.compareTo(b);
        });

        return out;
    }

    private static int number(String name) {
        Matcher m = LOCAL.matcher(name);

        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    // ---- 付け替えを当てる ----

    /** 付け替えた後のクラス名。入れ子は付け替えた外側の名前に続ける。 */
    private static String mapClass(String name) {
        if (name == null || classRename.isEmpty()) {
            return name;
        }

        String direct = classRename.get(name);

        if (direct != null) {
            return direct;
        }

        for (int at = name.lastIndexOf('$'); at > 0; at = name.lastIndexOf('$', at - 1)) {
            String outer = classRename.get(name.substring(0, at));

            if (outer != null) {
                return outer + name.substring(at);
            }
        }

        return name;
    }

    /** 内部名か配列の記述子(TypeInsn、frame、owner に出る形)。 */
    private static String mapInternal(String name) {
        return name != null && name.startsWith("[") ? mapDesc(name) : mapClass(name);
    }

    private static String mapDesc(String desc) {
        if (desc == null || desc.indexOf('L') < 0) {
            return desc;
        }

        return mapType(Type.getType(desc)).getDescriptor();
    }

    private static Type mapType(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT:
                return Type.getObjectType(mapClass(type.getInternalName()));
            case Type.ARRAY:
                return Type.getType("[".repeat(type.getDimensions())
                        + mapType(type.getElementType()).getDescriptor());
            case Type.METHOD: {
                Type[] args = type.getArgumentTypes();

                for (int i = 0; i < args.length; i++) {
                    args[i] = mapType(args[i]);
                }

                return Type.getMethodType(mapType(type.getReturnType()), args);
            }
            default:
                return type;
        }
    }

    /** 総称の署名。無名・局所クラスは {@code .Inner} の形には出てこないので、クラス型だけ直す。 */
    private static String mapSignature(String signature, boolean type) {
        if (signature == null) {
            return null;
        }

        SignatureWriter writer = new SignatureWriter() {
            @Override
            public void visitClassType(String name) {
                super.visitClassType(mapClass(name));
            }
        };

        if (type) {
            new SignatureReader(signature).acceptType(writer);
        } else {
            new SignatureReader(signature).accept(writer);
        }

        return writer.toString();
    }

    private static String mapField(String owner, String name, String desc) {
        return fieldRename.getOrDefault(owner + "." + name + desc, name);
    }

    private static Object mapValue(Object value) {
        if (value instanceof Type type) {
            return mapType(type);
        }

        if (value instanceof Handle handle) {
            boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;

            return new Handle(handle.getTag(), mapInternal(handle.getOwner()),
                    field ? mapField(handle.getOwner(), handle.getName(), handle.getDesc()) : handle.getName(),
                    mapDesc(handle.getDesc()), handle.isInterface());
        }

        if (value instanceof ConstantDynamic condy) {
            Object[] args = new Object[condy.getBootstrapMethodArgumentCount()];

            for (int i = 0; i < args.length; i++) {
                args[i] = mapValue(condy.getBootstrapMethodArgument(i));
            }

            return new ConstantDynamic(condy.getName(), mapDesc(condy.getDescriptor()),
                    (Handle) mapValue(condy.getBootstrapMethod()), args);
        }

        return value;
    }

    private static void mapAnnotations(List<? extends AnnotationNode> list) {
        if (list == null) {
            return;
        }

        for (AnnotationNode annotation : list) {
            mapAnnotation(annotation);
        }
    }

    private static void mapAnnotation(AnnotationNode annotation) {
        annotation.desc = mapDesc(annotation.desc);

        if (annotation.values != null) {
            for (int i = 1; i < annotation.values.size(); i += 2) {
                annotation.values.set(i, mapAnnotationValue(annotation.values.get(i)));
            }
        }
    }

    private static Object mapAnnotationValue(Object value) {
        if (value instanceof Type type) {
            return mapType(type);
        }

        if (value instanceof String[] enumValue) {
            return new String[] {mapDesc(enumValue[0]), enumValue[1]};
        }

        if (value instanceof AnnotationNode nested) {
            mapAnnotation(nested);

            return nested;
        }

        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();

            for (Object item : list) {
                out.add(mapAnnotationValue(item));
            }

            return out;
        }

        return value;
    }

    private static void mapParameterAnnotations(List<AnnotationNode>[] lists) {
        if (lists != null) {
            for (List<AnnotationNode> list : lists) {
                mapAnnotations(list);
            }
        }
    }

    private static List<Object> mapFrameTypes(List<Object> types) {
        if (types == null) {
            return null;
        }

        List<Object> out = new ArrayList<>(types.size());

        for (Object type : types) {
            out.add(type instanceof String name ? mapInternal(name) : type);
        }

        return out;
    }

    /** 1 つのクラスに付け替えを当てる。欄の付け替えは元の持ち主の名前で引くので、クラス名より先に済ませる。 */
    private static void remap(ClassNode node) {
        String self = node.name;

        for (FieldNode field : node.fields) {
            field.name = mapField(self, field.name, field.desc);
            field.desc = mapDesc(field.desc);
            field.signature = mapSignature(field.signature, true);
            mapAnnotations(field.visibleAnnotations);
            mapAnnotations(field.invisibleAnnotations);
            mapAnnotations(field.visibleTypeAnnotations);
            mapAnnotations(field.invisibleTypeAnnotations);
        }

        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode f) {
                    f.name = mapField(f.owner, f.name, f.desc);
                    f.owner = mapInternal(f.owner);
                    f.desc = mapDesc(f.desc);
                } else if (insn instanceof MethodInsnNode m) {
                    m.owner = mapInternal(m.owner);
                    m.desc = mapDesc(m.desc);
                } else if (insn instanceof TypeInsnNode t) {
                    t.desc = mapInternal(t.desc);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    indy.desc = mapDesc(indy.desc);
                    indy.bsm = (Handle) mapValue(indy.bsm);

                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        indy.bsmArgs[i] = mapValue(indy.bsmArgs[i]);
                    }
                } else if (insn instanceof LdcInsnNode ldc) {
                    ldc.cst = mapValue(ldc.cst);
                } else if (insn instanceof MultiANewArrayInsnNode multi) {
                    multi.desc = mapDesc(multi.desc);
                } else if (insn instanceof FrameNode frame) {
                    frame.local = mapFrameTypes(frame.local);
                    frame.stack = mapFrameTypes(frame.stack);
                }

                mapAnnotations(insn.visibleTypeAnnotations);
                mapAnnotations(insn.invisibleTypeAnnotations);
            }

            method.desc = mapDesc(method.desc);
            method.signature = mapSignature(method.signature, false);

            if (method.exceptions != null) {
                method.exceptions.replaceAll(SyntheticMatch::mapClass);
            }

            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    block.type = mapClass(block.type);
                    mapAnnotations(block.visibleTypeAnnotations);
                    mapAnnotations(block.invisibleTypeAnnotations);
                }
            }

            if (method.localVariables != null) {
                for (LocalVariableNode local : method.localVariables) {
                    local.desc = mapDesc(local.desc);
                    local.signature = mapSignature(local.signature, true);
                }
            }

            if (method.visibleLocalVariableAnnotations != null) {
                for (LocalVariableAnnotationNode a : method.visibleLocalVariableAnnotations) {
                    mapAnnotation(a);
                }
            }

            if (method.invisibleLocalVariableAnnotations != null) {
                for (LocalVariableAnnotationNode a : method.invisibleLocalVariableAnnotations) {
                    mapAnnotation(a);
                }
            }

            mapAnnotations(method.visibleAnnotations);
            mapAnnotations(method.invisibleAnnotations);
            mapAnnotations(method.visibleTypeAnnotations);
            mapAnnotations(method.invisibleTypeAnnotations);
            mapParameterAnnotations(method.visibleParameterAnnotations);
            mapParameterAnnotations(method.invisibleParameterAnnotations);

            if (method.annotationDefault != null) {
                method.annotationDefault = mapAnnotationValue(method.annotationDefault);
            }
        }

        node.name = mapClass(node.name);
        node.superName = mapClass(node.superName);
        node.interfaces.replaceAll(SyntheticMatch::mapClass);
        node.signature = mapSignature(node.signature, false);
        node.outerClass = mapClass(node.outerClass);
        node.outerMethodDesc = mapDesc(node.outerMethodDesc);
        node.nestHostClass = mapClass(node.nestHostClass);

        if (node.nestMembers != null) {
            node.nestMembers.replaceAll(SyntheticMatch::mapClass);
        }

        if (node.permittedSubclasses != null) {
            node.permittedSubclasses.replaceAll(SyntheticMatch::mapClass);
        }

        // 局所クラスは番号だけが変わるので innerName はそのまま。無名クラスの innerName は null のまま
        for (InnerClassNode inner : node.innerClasses) {
            inner.name = mapClass(inner.name);
            inner.outerName = mapClass(inner.outerName);
        }

        if (node.recordComponents != null) {
            for (RecordComponentNode component : node.recordComponents) {
                component.descriptor = mapDesc(component.descriptor);
                component.signature = mapSignature(component.signature, true);
                mapAnnotations(component.visibleAnnotations);
                mapAnnotations(component.invisibleAnnotations);
                mapAnnotations(component.visibleTypeAnnotations);
                mapAnnotations(component.invisibleTypeAnnotations);
            }
        }

        mapAnnotations(node.visibleAnnotations);
        mapAnnotations(node.invisibleAnnotations);
        mapAnnotations(node.visibleTypeAnnotations);
        mapAnnotations(node.invisibleTypeAnnotations);
    }

    /** 付け替えた名前を参照していそうなクラスを書き直し、クラスのファイルを置き直す。 */
    private static void rewrite(Path classes) throws Exception {
        if (classRename.isEmpty() && fieldRename.isEmpty()) {
            return;
        }

        // 付け替えに関わる外側のクラスの名前。これを定数に含まないクラスは参照していない
        Set<String> needles = new HashSet<>();

        for (String name : classRename.keySet()) {
            needles.add(name.substring(0, name.indexOf('$') + 1));
        }

        for (String key : fieldRename.keySet()) {
            needles.add(key.substring(0, key.indexOf('$') + 1));
        }

        Map<String, byte[]> out = new LinkedHashMap<>();
        List<Path> gone = new ArrayList<>();

        for (Map.Entry<String, Path> entry : files.entrySet()) {
            byte[] data = Files.readAllBytes(entry.getValue());
            String text = new String(data, StandardCharsets.ISO_8859_1);
            boolean hit = false;

            for (String needle : needles) {
                if (text.contains(needle)) {
                    hit = true;
                    break;
                }
            }

            if (!hit) {
                continue;
            }

            ClassNode node = read(data, 0);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] before = writer.toByteArray();

            remap(node);
            writer = new ClassWriter(0);
            node.accept(writer);
            byte[] after = writer.toByteArray();

            if (java.util.Arrays.equals(before, after) && node.name.equals(entry.getKey())) {
                continue;
            }

            if (!node.name.equals(entry.getKey())) {
                gone.add(entry.getValue());
            }

            if (out.put(node.name, after) != null) {
                throw new IllegalStateException("2 つのクラスが " + node.name + " になる");
            }
        }

        // 付け替え先に、退かないクラスのファイルがあってはいけない(上書きして消してしまう)
        for (String from : classRename.keySet()) {
            String to = mapClass(from);

            if (files.containsKey(to) && mapClass(to).equals(to)) {
                throw new IllegalStateException("付け替え先が既にある: " + from + " -> " + to);
            }
        }

        for (Path file : gone) {
            Files.delete(file);
        }

        for (Map.Entry<String, byte[]> entry : out.entrySet()) {
            Path file = classes.resolve(entry.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        System.out.println("rewritten class    : " + out.size());
    }

    private static ClassNode read(byte[] data, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, flags);

        return node;
    }
}
