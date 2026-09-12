// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * lambda の合成メソッドの形を Mojang の公式クラスに合わせる。
 *
 * <pre>java dev.shifu.lvtmatch.LambdaMatch &lt;クラスの置き場&gt; &lt;Mojang の jar&gt; &lt;対象の一覧&gt;</pre>
 *
 * <h2>何が違うか</h2>
 *
 * javac は {@code x -> this.foo(x)} の本体を **instance の** 合成メソッドにする。
 * Mojang の jar は ProGuard を通っていて、それが **static で受け手を第 1 引数に取る**
 * 形に変わっている。
 *
 * <pre>
 * 公式  : private static void lambda$load$0(ServerScoreboard, Objective$Packed)
 * こちら: private        void lambda$load$0(Objective$Packed)
 * </pre>
 *
 * 名前で狙う mixin は当たるが、署名まで書く mixin は当たらない。
 * ソースからは作り分けられない(javac は必ず instance にする)ので、
 * バイトコードで合わせる。
 *
 * <h2>やること</h2>
 *
 * instance のときの slot 0 は {@code this}、static で受け手を第 1 引数にしたときの
 * slot 0 はその引数。**本体の命令列は 1 つも変わらない。** 変えるのは 3 つだけ。
 *
 * <ol>
 * <li>メソッドを ACC_STATIC にして、署名の先頭に所有クラスを足す</li>
 * <li>その lambda を指す invokedynamic の method handle を
 *     {@code H_INVOKESPECIAL} から {@code H_INVOKESTATIC} に変え、署名を同じように直す</li>
 * <li>BasicVerifier に通し、通らなければ元に戻す</li>
 * </ol>
 *
 * indy が捕まえている引数({@code this})は変わらないので、呼び出し側は触らない。
 *
 * <h2>捕まえた引数の順</h2>
 *
 * javac は lambda が捕まえた変数を **本体で最初に触った順** に並べる。逆コンパイルで
 * 式の順が変わると、意味が同じでも並びが変わる。
 *
 * <pre>
 * 公式  : lambda$scheduleChunkGeneration$30(ChunkPos, ChunkHolder, ChunkStatus, Executor, ChunkResult)
 * こちら: lambda$scheduleChunkGeneration$30(ChunkPos, ChunkStatus, ChunkHolder, Executor, ChunkResult)
 * </pre>
 *
 * 署名で狙う mixin はこれで当たらない(C2ME の {@code MixinThreadedAnvilChunkStorage})。
 * 捕まえた値を積むのは invokedynamic の直前に並んだ xLOAD だけなので、
 * その並びと署名と本体の slot を一緒に入れ替えれば公式と同じ形になる。
 * **命令の数も種類も変わらない。**
 */
public final class LambdaMatch {

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        Path jar = Paths.get(args[1]);
        Path list = Paths.get(args[2]);

        if (!Files.exists(list)) {
            System.out.println("no list");

            return;
        }

        int changed = 0;
        int matched = 0;
        int reordered = 0;
        int renumbered = 0;
        int skipped = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String line : Files.readAllLines(list)) {
                String name = line.trim();

                if (name.isEmpty()) {
                    continue;
                }

                Path file = classes.resolve(name + ".class");
                ZipEntry official = zip.getEntry(name + ".class");

                if (!Files.exists(file) || official == null) {
                    skipped++;
                    continue;
                }

                ClassNode ours = read(Files.readAllBytes(file));
                ClassNode theirs;

                try (InputStream in = zip.getInputStream(official)) {
                    theirs = read(in.readAllBytes());
                }

                int made = makeStatic(ours, theirs);
                byte[] after = made > 0 ? write(ours) : Files.readAllBytes(file);

                // 番号の付け直しも別のクラスノードで試す
                ClassNode numbering = read(after);
                int renamed = rename(numbering, theirs);

                if (renamed > 0) {
                    after = write(numbering);
                    renumbered += renamed;
                }

                // 引数の入れ替えは別のクラスノードで試す。駄目なら static だけの状態に戻る
                ClassNode retry = read(after);
                int moved = reorder(retry, theirs);

                if (moved > 0) {
                    after = write(retry);
                    reordered += moved;
                }

                if (made > 0 || moved > 0 || renamed > 0) {
                    Files.write(file, after);
                    changed++;
                    matched += made;
                } else {
                    skipped++;
                }
            }
        }

        System.out.println("lambda made static : " + matched);
        System.out.println("lambda args moved  : " + reordered);
        System.out.println("lambda renumbered  : " + renumbered);
        System.out.println("rewritten class    : " + changed);
        System.out.println("left alone         : " + skipped);
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);

        return writer.toByteArray();
    }

    private static ClassNode read(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, ClassReader.EXPAND_FRAMES);

        return node;
    }

    private static int makeStatic(ClassNode ours, ClassNode theirs) {
        Map<String, MethodNode> official = new HashMap<>();

        for (MethodNode method : theirs.methods) {
            if (method.name.startsWith("lambda$")) {
                official.put(method.name, method);
            }
        }

        List<MethodNode> targets = new ArrayList<>();

        for (MethodNode method : ours.methods) {
            if (!method.name.startsWith("lambda$") || (method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }

            MethodNode moj = official.get(method.name);

            if (moj == null || (moj.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }

            // 公式の署名が「所有クラス + こちらの引数」になっているものだけ
            if (moj.desc.equals("(L" + ours.name + ";" + method.desc.substring(1))) {
                targets.add(method);
            }
        }

        if (targets.isEmpty()) {
            return 0;
        }

        Map<String, String> renamed = new HashMap<>();

        for (MethodNode method : targets) {
            renamed.put(method.name + method.desc, "(L" + ours.name + ";" + method.desc.substring(1));
        }

        // 呼び出し側(invokedynamic の method handle)を直す。
        // 直せた分だけを static にする。1 つでも取りこぼすと NoSuchMethodError になる
        List<Runnable> undo = new ArrayList<>();
        Map<String, Integer> fixed = new HashMap<>();

        for (MethodNode method : ours.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) {
                    continue;
                }

                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    if (!(indy.bsmArgs[i] instanceof Handle handle)) {
                        continue;
                    }

                    String key = handle.getName() + handle.getDesc();
                    String want = renamed.get(key);

                    if (want == null || !handle.getOwner().equals(ours.name)) {
                        continue;
                    }

                    if (handle.getTag() != Opcodes.H_INVOKESPECIAL && handle.getTag() != Opcodes.H_INVOKEVIRTUAL) {
                        continue;
                    }

                    final int at = i;
                    final Object before = indy.bsmArgs[i];
                    undo.add(() -> indy.bsmArgs[at] = before);
                    indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, ours.name,
                            handle.getName(), want, false);
                    fixed.merge(key, 1, Integer::sum);
                }
            }
        }

        List<MethodNode> ready = new ArrayList<>();

        for (MethodNode method : targets) {
            if (fixed.getOrDefault(method.name + method.desc, 0) == 1) {
                ready.add(method);
            }
        }

        if (ready.size() != targets.size()) {
            // 参照を全部は直せなかった。何もしない
            for (Runnable step : undo) {
                step.run();
            }

            return 0;
        }

        for (MethodNode method : ready) {
            method.access |= Opcodes.ACC_STATIC;
            method.desc = "(L" + ours.name + ";" + method.desc.substring(1);
        }

        for (MethodNode method : ready) {
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(ours.name, method);
            } catch (Throwable e) {
                for (MethodNode back : ready) {
                    back.access &= ~Opcodes.ACC_STATIC;
                    back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                }

                for (Runnable step : undo) {
                    step.run();
                }

                return 0;
            }
        }

        // 古い署名を指す参照が残っていないか
        for (MethodNode method : ours.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle && handle.getOwner().equals(ours.name)
                                && renamed.containsKey(handle.getName() + handle.getDesc())) {
                            for (MethodNode back : ready) {
                                back.access &= ~Opcodes.ACC_STATIC;
                                back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                            }

                            for (Runnable step : undo) {
                                step.run();
                            }

                            return 0;
                        }
                    }
                }

                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals(ours.name)
                        && renamed.containsKey(call.name + call.desc)) {
                    for (MethodNode back : ready) {
                        back.access &= ~Opcodes.ACC_STATIC;
                        back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                    }

                    for (Runnable step : undo) {
                        step.run();
                    }

                    return 0;
                }
            }
        }

        return ready.size();
    }

    /**
     * 捕まえた引数の順を公式に合わせる。
     *
     * @return 直した lambda の数。1 つでも検証に落ちたら 0(呼び出し側がノードを捨てる)
     */
    private static int reorder(ClassNode ours, ClassNode theirs) {
        Map<String, MethodNode> official = new HashMap<>();

        for (MethodNode method : theirs.methods) {
            if (method.name.startsWith("lambda$")) {
                official.put(method.name, method);
            }
        }

        int done = 0;

        for (MethodNode method : new ArrayList<>(ours.methods)) {
            if (!method.name.startsWith("lambda$")) {
                continue;
            }

            MethodNode moj = official.get(method.name);

            if (moj == null || moj.desc.equals(method.desc)
                    || (moj.access & Opcodes.ACC_STATIC) != (method.access & Opcodes.ACC_STATIC)) {
                continue;
            }

            if (move(ours, method, moj)) {
                done++;
            }
        }

        if (done == 0) {
            return 0;
        }

        for (MethodNode method : ours.methods) {
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(ours.name, method);
            } catch (Throwable e) {
                return 0;
            }
        }

        return done;
    }

    /** 1 つの lambda の引数を入れ替える。触れないと分かったら何もせず false。 */
    private static boolean move(ClassNode ours, MethodNode method, MethodNode moj) {
        Type[] mine = Type.getArgumentTypes(method.desc);
        Type[] want = Type.getArgumentTypes(moj.desc);

        if (mine.length != want.length
                || !Type.getReturnType(method.desc).equals(Type.getReturnType(moj.desc))) {
            return false;
        }

        List<InvokeDynamicInsnNode> sites = new ArrayList<>();
        List<MethodNode> owners = new ArrayList<>();
        int captured = -1;
        boolean receiver = false;

        for (MethodNode holder : ours.methods) {
            for (AbstractInsnNode insn : holder.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy) || !points(indy, ours.name, method)) {
                    continue;
                }

                Handle handle = implementation(indy, ours.name, method);
                boolean self = handle.getTag() != Opcodes.H_INVOKESTATIC;
                int count = Type.getArgumentTypes(indy.desc).length - (self ? 1 : 0);

                if (captured >= 0 && (captured != count || receiver != self)) {
                    return false;
                }

                captured = count;
                receiver = self;
                sites.add(indy);
                owners.add(holder);
            }
        }

        // 捕まえた引数が無い、または狙いの lambda を指す indy が無い
        if (sites.isEmpty() || captured <= 0 || captured > mine.length) {
            return false;
        }

        // 関数側の引数(捕まえた分より後ろ)は動かさない。ここが違うなら別物
        for (int i = captured; i < mine.length; i++) {
            if (!mine[i].equals(want[i])) {
                return false;
            }
        }

        int[] perm = new int[captured];   // こちらの位置 -> 公式の位置
        boolean[] taken = new boolean[captured];
        Arrays.fill(perm, -1);

        for (int i = 0; i < captured; i++) {
            for (int j = 0; j < captured; j++) {
                if (!taken[j] && mine[j].equals(want[i])) {
                    perm[j] = i;
                    taken[j] = true;
                    break;
                }
            }
        }

        boolean same = true;

        for (int j = 0; j < captured; j++) {
            if (perm[j] < 0) {
                return false;   // 型の顔ぶれが違う
            }

            same &= perm[j] == j;
        }

        if (same) {
            return false;
        }

        // 積む命令は indy の直前に並んだ xLOAD だけであること。
        // 式の途中に副作用があると並べ替えられない
        List<List<VarInsnNode>> pushes = new ArrayList<>();

        for (InvokeDynamicInsnNode indy : sites) {
            List<VarInsnNode> loads = new ArrayList<>();
            AbstractInsnNode at = indy.getPrevious();

            for (int i = 0; i < captured + (receiver ? 1 : 0); i++) {
                while (at != null && at.getOpcode() < 0) {
                    at = at.getPrevious();   // label と行番号は飛ばす
                }

                if (!(at instanceof VarInsnNode load) || load.getOpcode() < Opcodes.ILOAD
                        || load.getOpcode() > Opcodes.ALOAD) {
                    return false;
                }

                loads.add(0, load);
                at = at.getPrevious();
            }

            pushes.add(loads);
        }

        // 署名と本体の slot を入れ替える
        int base = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int[] slotMap = new int[method.maxLocals + 2];

        for (int i = 0; i < slotMap.length; i++) {
            slotMap[i] = i;
        }

        int at = base;
        int[] from = new int[captured];

        for (int j = 0; j < captured; j++) {
            from[j] = at;
            at += mine[j].getSize();
        }

        at = base;
        int[] to = new int[captured];

        for (int i = 0; i < captured; i++) {
            to[i] = at;
            at += want[i].getSize();
        }

        for (int j = 0; j < captured; j++) {
            slotMap[from[j]] = to[perm[j]];

            if (mine[j].getSize() == 2) {
                slotMap[from[j] + 1] = to[perm[j]] + 1;
            }
        }

        Type[] made = new Type[mine.length];
        System.arraycopy(mine, 0, made, 0, mine.length);

        for (int j = 0; j < captured; j++) {
            made[perm[j]] = mine[j];
        }

        String before = method.desc;
        method.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), made);
        applySlots(method, slotMap);

        // 呼び出し側。handle の署名、indy の署名、積む順の 3 つ
        for (int i = 0; i < sites.size(); i++) {
            InvokeDynamicInsnNode indy = sites.get(i);

            for (int k = 0; k < indy.bsmArgs.length; k++) {
                if (indy.bsmArgs[k] instanceof Handle handle && handle.getName().equals(method.name)
                        && handle.getDesc().equals(before) && handle.getOwner().equals(ours.name)) {
                    indy.bsmArgs[k] = new Handle(handle.getTag(), handle.getOwner(), handle.getName(),
                            method.desc, handle.isInterface());
                }
            }

            Type[] args = Type.getArgumentTypes(indy.desc);
            Type[] moved = new Type[args.length];
            System.arraycopy(args, 0, moved, 0, args.length);
            int shift = receiver ? 1 : 0;

            for (int j = 0; j < captured; j++) {
                moved[shift + perm[j]] = args[shift + j];
            }

            indy.desc = Type.getMethodDescriptor(Type.getReturnType(indy.desc), moved);

            List<VarInsnNode> loads = pushes.get(i);
            VarInsnNode[] order = new VarInsnNode[loads.size()];

            for (int j = 0; j < captured; j++) {
                order[shift + perm[j]] = loads.get(shift + j);
            }

            if (receiver) {
                order[0] = loads.get(0);
            }

            InsnList insns = owners.get(i).instructions;

            for (VarInsnNode load : loads) {
                insns.remove(load);
            }

            for (VarInsnNode load : order) {
                insns.insertBefore(indy, load);
            }
        }

        return true;
    }

    private static boolean points(InvokeDynamicInsnNode indy, String owner, MethodNode method) {
        return implementation(indy, owner, method) != null;
    }

    /** その indy が指している、このクラスの lambda の実装 handle。無ければ null。 */
    private static Handle implementation(InvokeDynamicInsnNode indy, String owner, MethodNode method) {
        for (Object arg : indy.bsmArgs) {
            if (arg instanceof Handle handle && handle.getOwner().equals(owner)
                    && handle.getName().equals(method.name) && handle.getDesc().equals(method.desc)) {
                return handle;
            }
        }

        return null;
    }

    /** slot の対応表を、命令・局所変数表・frame に当てる。 */
    private static void applySlots(MethodNode method, int[] slotMap) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var < slotMap.length) {
                var.var = slotMap[var.var];
            } else if (insn instanceof IincInsnNode iinc && iinc.var < slotMap.length) {
                iinc.var = slotMap[iinc.var];
            } else if (insn instanceof FrameNode frame && frame.local != null) {
                frame.local = permute(frame.local, slotMap);
            }
        }

        if (method.localVariables != null) {
            for (LocalVariableNode lv : method.localVariables) {
                if (lv.index < slotMap.length) {
                    lv.index = slotMap[lv.index];
                }
            }
        }
    }

    /** frame の locals は slot の並びそのもの。対応表どおりに置き直す。 */
    private static List<Object> permute(List<Object> types, int[] slotMap) {
        Object[] bySlot = new Object[slotMap.length];
        Arrays.fill(bySlot, Opcodes.TOP);
        int slot = 0;

        for (Object type : types) {
            if (slot >= bySlot.length) {
                return types;
            }

            bySlot[slot] = type;
            slot += (type == Opcodes.LONG || type == Opcodes.DOUBLE) ? 2 : 1;
        }

        Object[] out = new Object[slotMap.length];
        Arrays.fill(out, Opcodes.TOP);

        for (int i = 0; i < bySlot.length; i++) {
            if (bySlot[i] != Opcodes.TOP) {
                out[slotMap[i]] = bySlot[i];
            }
        }

        int last = -1;

        for (int i = 0; i < out.length; i++) {
            if (out[i] != Opcodes.TOP) {
                last = i + ((out[i] == Opcodes.LONG || out[i] == Opcodes.DOUBLE) ? 1 : 0);
            }
        }

        List<Object> result = new ArrayList<>();

        for (int i = 0; i <= last; i++) {
            result.add(out[i]);

            if (out[i] == Opcodes.LONG || out[i] == Opcodes.DOUBLE) {
                i++;
            }
        }

        return result;
    }

    /**
     * lambda の番号を公式に合わせる。
     *
     * <p>javac は合成メソッドを作った順に {@code lambda$<囲みのメソッド>$<番号>} と名付ける。
     * 逆コンパイラは {@code static} の初期化子をソースの別の位置に置くので、
     * 顔ぶれが同じでも番号の割り当てが入れ替わる。
     *
     * <pre>
     * 公式  : newInteractionMap$0, bootStrap$1..6, ... static$10..15
     * こちら: static$0..5, newInteractionMap$6, ... bootStrap$10..15
     * </pre>
     *
     * 名前で狙う mixin はこれで当たらない(Ledger が
     * {@code CauldronInteraction.lambda$bootStrap$5} を狙って 0 件になる)。
     *
     * <p>囲みのメソッドと署名が同じものどうしを、番号の順に対応させて付け直す。
     * **顔ぶれが 1 つでも違うクラスには触らない。**差し込みがラムダを足していると
     * 対応が取れず、別のラムダに当ててしまう。
     *
     * @return 付け直した数。取り消したいときのために、呼ぶ側は別のノードで試す
     */
    private static int rename(ClassNode ours, ClassNode theirs) {
        Map<String, List<MethodNode>> mine = lambdaGroups(ours);
        Map<String, List<MethodNode>> moj = lambdaGroups(theirs);

        if (mine.isEmpty() || !mine.keySet().equals(moj.keySet())) {
            return 0;
        }

        Map<String, String> renamed = new HashMap<>();

        for (Map.Entry<String, List<MethodNode>> entry : mine.entrySet()) {
            List<MethodNode> from = entry.getValue();
            List<MethodNode> to = moj.get(entry.getKey());

            if (from.size() != to.size()) {
                return 0;
            }

            for (int i = 0; i < from.size(); i++) {
                if (!from.get(i).name.equals(to.get(i).name)) {
                    renamed.put(from.get(i).name, to.get(i).name);
                }
            }
        }

        if (renamed.isEmpty()) {
            return 0;
        }

        // 行き先が重ならないこと。重なると 2 つの lambda が同じ名前になる
        Set<String> taken = new HashSet<>();

        for (String to : renamed.values()) {
            if (!taken.add(to)) {
                return 0;
            }
        }

        Set<String> keep = new HashSet<>();

        for (MethodNode method : ours.methods) {
            if (!renamed.containsKey(method.name)) {
                keep.add(method.name);
            }
        }

        for (String to : renamed.values()) {
            if (keep.contains(to)) {
                return 0;
            }
        }

        for (MethodNode method : ours.methods) {
            String want = renamed.get(method.name);

            if (want != null) {
                method.name = want;
            }
        }

        for (MethodNode method : ours.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (indy.bsmArgs[i] instanceof Handle handle && handle.getOwner().equals(ours.name)) {
                            String want = renamed.get(handle.getName());

                            if (want != null) {
                                indy.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), want,
                                        handle.getDesc(), handle.isInterface());
                            }
                        }
                    }
                } else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals(ours.name)) {
                    String want = renamed.get(call.name);

                    if (want != null) {
                        call.name = want;
                    }
                }
            }
        }

        return renamed.size();
    }

    /** {@code 囲みのメソッド + 署名} ごとに、番号の順で並べた lambda。 */
    private static Map<String, List<MethodNode>> lambdaGroups(ClassNode node) {
        Map<String, List<MethodNode>> out = new HashMap<>();

        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$")) {
                continue;
            }

            int mark = method.name.lastIndexOf('$');

            if (mark < 0 || number(method.name.substring(mark + 1)) < 0) {
                return Map.of();   // 見たことのない形。触らない
            }

            out.computeIfAbsent(method.name.substring(0, mark) + method.desc
                    + ((method.access & Opcodes.ACC_STATIC) != 0 ? "#static" : ""),
                    key -> new ArrayList<>()).add(method);
        }

        for (List<MethodNode> group : out.values()) {
            group.sort((a, b) -> Integer.compare(number(a.name.substring(a.name.lastIndexOf('$') + 1)),
                    number(b.name.substring(b.name.lastIndexOf('$') + 1))));
        }

        return out;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
