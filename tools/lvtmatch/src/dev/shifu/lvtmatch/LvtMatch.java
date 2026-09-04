// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * コンパイルし直したクラスの局所変数の番号を、Mojang の公式クラスに合わせる。
 *
 * <pre>java dev.shifu.lvtmatch.LvtMatch &lt;クラスの置き場&gt; &lt;Mojang の jar&gt; &lt;対象の一覧&gt;</pre>
 *
 * mixin は局所変数を slot の順に並べた型で照合する。逆コンパイルしてコンパイルし直すと
 * 並びが変わり、LVT has incompatible changes や Found 0 candidate variables で当たらない。
 * 名前と型が同じ変数を公式と同じ番号に置き直せば合う。
 *
 * <h2>やり方</h2>
 *
 * <b>変数ごとに置き直す。</b> slot ごと入れ替える(置換)やり方では、1 つの slot を
 * 寿命の違う変数が使い回していると行き先が食い違って直らない
 * (Entity.interact の anyLeashed と mobsToLeash)。
 *
 * <ol>
 * <li>命令(xLOAD / xSTORE / IINC)を、その位置を覆う LVT の項目に結び付ける。
 *     変数を作る store は scope の 1 つ前にあるので、その分だけ手前も見る</li>
 * <li>どの項目にも結び付かない使い方がある slot は<b>動かさない</b>。
 *     javac が作った、表に出ない一時変数がそこに居る</li>
 * <li>公式と同じ名前・型の変数を、生きている区間が空いていれば公式の番号へ移す。
 *     長く生きているものから決める</li>
 * <li>命令・LVT・frame を書き換える。frame は slot の並びそのものなので、
 *     その位置で誰がその slot に居るかを見て置き直す</li>
 * <li>BasicVerifier に通し、通らなければクラスごと元に戻す</li>
 * </ol>
 *
 * 引数の slot(0 から argSize-1)は呼び出し規約で決まるので動かさない。
 * long と double は 2 つ分使うので隣も予約する。
 */
public final class LvtMatch {

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        Path jar = Paths.get(args[1]);
        Path list = Paths.get(args[2]);
        // --slots: slot ごと入れ替える(一時変数も一緒に動く)
        // --vars : 変数ごとに置き直す(1 つの slot を使い回していても直せる)
        boolean bySlot = args.length > 3 && "--slots".equals(args[3]);

        Map<String, Set<String>> targets = readList(list);
        int changed = 0;
        int matched = 0;
        int reverted = 0;
        int skipped = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Map.Entry<String, Set<String>> entry : targets.entrySet()) {
                Path file = classes.resolve(entry.getKey() + ".class");
                ZipEntry official = zip.getEntry(entry.getKey() + ".class");

                if (!Files.exists(file) || official == null) {
                    skipped += entry.getValue().size();
                    continue;
                }

                ClassNode ours = read(Files.readAllBytes(file));
                ClassNode theirs;

                try (InputStream in = zip.getInputStream(official)) {
                    theirs = read(in.readAllBytes());
                }

                int touched = 0;

                for (String key : entry.getValue()) {
                    MethodNode mine = find(ours, key);
                    MethodNode moj = find(theirs, key);

                    if (mine == null || moj == null) {
                        skipped++;
                        continue;
                    }

                    int result = bySlot ? alignSlots(ours.name, mine, moj) : align(ours.name, mine, moj);

                    if (result == 1) {
                        matched++;
                        touched++;
                    } else if (result == -1) {
                        reverted++;
                    } else {
                        skipped++;
                    }
                }

                if (touched > 0) {
                    ClassWriter writer = new ClassWriter(0);
                    ours.accept(writer);
                    Files.write(file, writer.toByteArray());
                    changed++;
                }
            }
        }

        System.out.println("aligned methods : " + matched);
        System.out.println("rewritten class : " + changed);
        System.out.println("reverted        : " + reverted);
        System.out.println("left alone      : " + skipped);
    }

    private static Map<String, Set<String>> readList(Path list) throws IOException {
        Map<String, Set<String>> out = new HashMap<>();

        for (String line : Files.readAllLines(list)) {
            String text = line.trim();

            if (text.isEmpty()) {
                continue;
            }

            int space = text.indexOf(' ');
            out.computeIfAbsent(text.substring(0, space), k -> new HashSet<>()).add(text.substring(space + 1));
        }

        return out;
    }

    private static ClassNode read(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, ClassReader.EXPAND_FRAMES);

        return node;
    }

    private static MethodNode find(ClassNode node, String key) {
        for (MethodNode method : node.methods) {
            if (key.equals(method.name + method.desc)) {
                return method;
            }
        }

        return null;
    }

    /**
     * slot ごと入れ替える。表に出ない一時変数も一緒に動くので衝突しない。
     * 1 つの slot を寿命の違う変数が使い回していて行き先が食い違うときは、
     * 生きている長さの重い方を採る。残りは {@link #align} が変数ごとに直す。
     */
    private static int alignSlots(String owner, MethodNode mine, MethodNode moj) {
        if (mine.localVariables == null || moj.localVariables == null
                || mine.localVariables.isEmpty() || moj.localVariables.isEmpty()) {
            return 0;
        }

        int fixed = argumentSlots(mine);
        int size = Math.max(mine.maxLocals, maxSlot(moj) + 2) + 2;
        int[] perm = new int[size];
        Arrays.fill(perm, -1);
        boolean[] taken = new boolean[size];

        for (int i = 0; i < fixed && i < size; i++) {
            perm[i] = i;
            taken[i] = true;
        }

        List<LocalVariableNode> wanted = new ArrayList<>();

        for (LocalVariableNode lv : mine.localVariables) {
            if (lv.index >= fixed && lv.index < size) {
                wanted.add(lv);
            }
        }

        wanted.sort((a, b) -> Integer.compare(span(mine, b), span(mine, a)));
        Map<String, Integer> used = new HashMap<>();
        Map<Long, Integer> weight = new HashMap<>();
        Set<Long> wide = new HashSet<>();

        for (LocalVariableNode lv : wanted) {
            Integer target = officialSlot(moj, lv, used);

            if (target == null || target >= size || target < fixed) {
                continue;
            }

            long move = ((long) lv.index << 32) | target;
            weight.merge(move, Math.max(span(mine, lv), 1), Integer::sum);

            if ("J".equals(lv.desc) || "D".equals(lv.desc)) {
                wide.add(move);
            }
        }

        List<Long> moves = new ArrayList<>(weight.keySet());
        moves.sort((a, b) -> Integer.compare(weight.get(b), weight.get(a)));

        for (long move : moves) {
            int from = (int) (move >> 32);
            int target = (int) move;

            if (perm[from] != -1 || taken[target]) {
                continue;
            }

            if (wide.contains(move)) {
                if (target + 1 >= size || taken[target + 1] || from + 1 >= size || perm[from + 1] != -1) {
                    continue;
                }

                perm[from + 1] = target + 1;
                taken[target + 1] = true;
            }

            perm[from] = target;
            taken[target] = true;
        }

        for (int i = 0; i < size; i++) {
            if (perm[i] == -1 && !taken[i]) {
                perm[i] = i;
                taken[i] = true;
            }
        }

        for (int i = 0; i < size; i++) {
            if (perm[i] != -1) {
                continue;
            }

            for (int t = 0; t < size; t++) {
                if (!taken[t]) {
                    perm[i] = t;
                    taken[t] = true;
                    break;
                }
            }
        }

        boolean identity = true;

        for (int i = 0; i < size; i++) {
            if (perm[i] != i) {
                identity = false;
                break;
            }
        }

        if (identity) {
            return 0;
        }

        MethodNode backup = copy(mine);

        for (AbstractInsnNode insn : mine.instructions) {
            if (insn instanceof VarInsnNode var) {
                var.var = perm[var.var];
            } else if (insn instanceof IincInsnNode iinc) {
                iinc.var = perm[iinc.var];
            } else if (insn instanceof FrameNode frame && frame.local != null) {
                frame.local = permuteFrame(frame.local, perm, size);
            }
        }

        for (LocalVariableNode lv : mine.localVariables) {
            lv.index = perm[lv.index];
        }

        mine.maxLocals = Math.max(mine.maxLocals, size);

        try {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, mine);
        } catch (Throwable e) {
            restore(mine, backup);

            return -1;
        }

        return 1;
    }

    private static int span(MethodNode method, LocalVariableNode lv) {
        return method.instructions.indexOf(lv.end) - method.instructions.indexOf(lv.start);
    }

    /** frame の locals は slot の並びそのもの。TOP で埋めながら置き換える。 */
    private static List<Object> permuteFrame(List<Object> types, int[] perm, int size) {
        Object[] bySlot = new Object[size];
        Arrays.fill(bySlot, Opcodes.TOP);
        int slot = 0;

        for (Object type : types) {
            if (slot >= size) {
                break;
            }

            bySlot[slot] = type;
            slot += (type == Opcodes.LONG || type == Opcodes.DOUBLE) ? 2 : 1;
        }

        Object[] out = new Object[size];
        Arrays.fill(out, Opcodes.TOP);

        for (int i = 0; i < size; i++) {
            if (bySlot[i] != Opcodes.TOP) {
                out[perm[i]] = bySlot[i];
            }
        }

        int last = -1;

        for (int i = 0; i < size; i++) {
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

    /** 1 つの局所変数。命令番号での区間を持つ。 */
    private static final class Local {
        final LocalVariableNode node;
        final int from;   // 変数を作る store を含む
        final int to;     // scope の終わり(この番号は含まない)
        final boolean wide;
        int slot;

        Local(LocalVariableNode node, int from, int to) {
            this.node = node;
            this.from = from;
            this.to = to;
            this.wide = "J".equals(node.desc) || "D".equals(node.desc);
            this.slot = node.index;
        }

        boolean overlaps(Local other) {
            return this.from < other.to && other.from < this.to;
        }
    }

    /** 1: 合わせた / 0: 対象外 / -1: 検証に落ちたので戻した。 */
    private static int align(String owner, MethodNode mine, MethodNode moj) {
        if (mine.localVariables == null || moj.localVariables == null
                || mine.localVariables.isEmpty() || moj.localVariables.isEmpty()) {
            return 0;
        }

        InsnList insns = mine.instructions;
        int count = insns.size();
        int argSize = argumentSlots(mine);
        int size = Math.max(mine.maxLocals, maxSlot(moj) + 2) + 2;

        List<Local> locals = new ArrayList<>();

        for (LocalVariableNode lv : mine.localVariables) {
            int start = insns.indexOf(lv.start);
            int end = insns.indexOf(lv.end);

            if (start < 0 || end < 0 || lv.index >= size) {
                return 0;
            }

            locals.add(new Local(lv, Math.max(start - 1, 0), end));
        }

        // 命令をどの変数のものか決める。決まらないものは javac が作った一時変数。
        // その slot は、使われている範囲だけ塞がっているものとして扱う
        Map<AbstractInsnNode, Local> owners = new IdentityHashMap<>();
        boolean[] pinned = new boolean[size];
        int[] tempFrom = new int[size];
        int[] tempTo = new int[size];
        Arrays.fill(tempFrom, Integer.MAX_VALUE);
        Arrays.fill(tempTo, Integer.MIN_VALUE);

        for (int i = 0; i < argSize && i < size; i++) {
            pinned[i] = true;
        }

        for (int i = 0; i < count; i++) {
            AbstractInsnNode insn = insns.get(i);
            int slot;

            if (insn instanceof VarInsnNode var) {
                slot = var.var;
            } else if (insn instanceof IincInsnNode iinc) {
                slot = iinc.var;
            } else {
                continue;
            }

            if (slot >= size) {
                return 0;
            }

            Local held = at(locals, slot, i);

            if (held == null) {
                tempFrom[slot] = Math.min(tempFrom[slot], i);
                tempTo[slot] = Math.max(tempTo[slot], i + 1);
            } else {
                owners.put(insn, held);
            }
        }

        // 公式の番号を引く。同じ名前が複数あるときは出てくる順に対応させる
        List<Local> wanted = new ArrayList<>(locals);
        wanted.sort((a, b) -> Integer.compare(b.to - b.from, a.to - a.from));
        Map<String, Integer> used = new HashMap<>();
        Map<Local, Integer> target = new IdentityHashMap<>();

        for (Local local : wanted) {
            Integer slot = officialSlot(moj, local.node, used);

            if (slot != null && slot < size) {
                target.put(local, slot);
            }
        }

        // いま誰がどの slot に居るか
        List<List<Local>> occupied = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            occupied.add(new ArrayList<>());
        }

        for (Local local : locals) {
            occupied.get(local.slot).add(local);

            if (local.wide && local.slot + 1 < size) {
                occupied.get(local.slot + 1).add(local);
            }
        }

        // 動かしたい変数を、いったん誰も使っていない高い番号へ寄せる。
        // こうしないと、2 つの変数が互いの番号へ移りたいときにどちらも動けない
        List<Local> movable = new ArrayList<>();
        List<Integer> homes = new ArrayList<>();
        int park = size;

        for (Local local : wanted) {
            Integer slot = target.get(local);

            if (slot == null || slot == local.slot || local.slot < argSize
                    || pinned[slot] || slot < argSize) {
                continue;
            }

            movable.add(local);
            homes.add(local.slot);
            park += local.wide ? 2 : 1;
        }

        if (movable.isEmpty()) {
            return 0;
        }

        int room = park + 2;

        while (occupied.size() < room) {
            occupied.add(new ArrayList<>());
        }

        pinned = Arrays.copyOf(pinned, room);
        tempFrom = Arrays.copyOf(tempFrom, room);
        tempTo = Arrays.copyOf(tempTo, room);
        Arrays.fill(tempFrom, size, room, Integer.MAX_VALUE);
        Arrays.fill(tempTo, size, room, Integer.MIN_VALUE);
        int next = size;

        for (Local local : movable) {
            move(occupied, local, next);
            next += local.wide ? 2 : 1;
        }

        // 目標へ置く。駄目なら元の番号へ戻す。それも駄目なら寄せたまま
        boolean moved = false;

        for (int i = 0; i < movable.size(); i++) {
            Local local = movable.get(i);
            int want = target.get(local);

            if (fits(occupied, pinned, tempFrom, tempTo, want, local, room, argSize)) {
                move(occupied, local, want);
                moved = true;
            } else if (fits(occupied, pinned, tempFrom, tempTo, homes.get(i), local, room, argSize)) {
                move(occupied, local, homes.get(i));
            }
        }

        size = room;

        if (!moved) {
            return 0;
        }

        MethodNode backup = copy(mine);
        apply(mine, locals, owners, size);

        try {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, mine);
        } catch (Throwable e) {
            restore(mine, backup);

            return -1;
        }

        return 1;
    }

    /** その位置でその slot に居る変数。無ければ null(一時変数)。 */
    private static Local at(List<Local> locals, int slot, int index) {
        for (Local local : locals) {
            if (local.slot == slot && index >= local.from && index < local.to) {
                return local;
            }

            if (local.wide && local.slot + 1 == slot && index >= local.from && index < local.to) {
                return local;
            }
        }

        return null;
    }

    /** その番号へ置けるか。 */
    private static boolean fits(List<List<Local>> occupied, boolean[] pinned, int[] tempFrom, int[] tempTo,
                                int slot, Local local, int room, int argSize) {
        if (slot < argSize || slot >= room || pinned[slot]) {
            return false;
        }

        if (local.wide && (slot + 1 >= room || pinned[slot + 1])) {
            return false;
        }

        if (!open(tempFrom, tempTo, slot, local) || (local.wide && !open(tempFrom, tempTo, slot + 1, local))) {
            return false;
        }

        return free(occupied, slot, local) && (!local.wide || free(occupied, slot + 1, local));
    }

    /** その変数を別の番号へ移す。 */
    private static void move(List<List<Local>> occupied, Local local, int slot) {
        occupied.get(local.slot).remove(local);

        if (local.wide && local.slot + 1 < occupied.size()) {
            occupied.get(local.slot + 1).remove(local);
        }

        local.slot = slot;
        occupied.get(slot).add(local);

        if (local.wide) {
            occupied.get(slot + 1).add(local);
        }
    }

    /** その slot の一時変数と、その変数の生きている区間が重ならないか。 */
    private static boolean open(int[] tempFrom, int[] tempTo, int slot, Local local) {
        if (tempTo[slot] == Integer.MIN_VALUE) {
            return true;
        }

        return local.to <= tempFrom[slot] || tempTo[slot] <= local.from;
    }

    /** その slot が、その変数の生きている間ずっと空いているか。 */
    private static boolean free(List<List<Local>> occupied, int slot, Local local) {
        for (Local other : occupied.get(slot)) {
            if (other != local && other.overlaps(local)) {
                return false;
            }
        }

        return true;
    }

    private static Integer officialSlot(MethodNode moj, LocalVariableNode lv, Map<String, Integer> used) {
        String key = lv.name + lv.desc;
        int skip = used.getOrDefault(key, 0);
        int seen = 0;

        for (LocalVariableNode other : moj.localVariables) {
            if (other.name.equals(lv.name) && other.desc.equals(lv.desc)) {
                if (seen++ < skip) {
                    continue;
                }

                used.put(key, seen);

                return other.index;
            }
        }

        return null;
    }

    private static int maxSlot(MethodNode method) {
        int max = 0;

        for (LocalVariableNode lv : method.localVariables) {
            max = Math.max(max, lv.index + ("J".equals(lv.desc) || "D".equals(lv.desc) ? 1 : 0));
        }

        return max;
    }

    private static int argumentSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;

        for (Type type : Type.getArgumentTypes(method.desc)) {
            slots += type.getSize();
        }

        return slots;
    }

    private static void apply(MethodNode method, List<Local> locals,
                              Map<AbstractInsnNode, Local> owners, int size) {
        InsnList insns = method.instructions;
        int count = insns.size();
        int max = 0;

        for (int i = 0; i < count; i++) {
            AbstractInsnNode insn = insns.get(i);
            Local held = owners.get(insn);

            if (held != null) {
                if (insn instanceof VarInsnNode var) {
                    var.var = held.slot;
                } else if (insn instanceof IincInsnNode iinc) {
                    iinc.var = held.slot;
                }
            }

            if (insn instanceof FrameNode frame && frame.local != null) {
                frame.local = moveFrame(frame.local, locals, i, size);
            }
        }

        for (Local local : locals) {
            local.node.index = local.slot;
            max = Math.max(max, local.slot + (local.wide ? 2 : 1));
        }

        method.maxLocals = Math.max(method.maxLocals, max);
    }

    /**
     * frame の locals は slot の並びそのもの。その位置で誰がその slot に居るかを見て置き直す。
     * 変数に結び付かない分(一時変数)はそのまま。
     */
    private static List<Object> moveFrame(List<Object> types, List<Local> locals, int index, int size) {
        Object[] bySlot = new Object[size];
        Arrays.fill(bySlot, Opcodes.TOP);
        int slot = 0;

        for (Object type : types) {
            if (slot >= size) {
                break;
            }

            bySlot[slot] = type;
            slot += (type == Opcodes.LONG || type == Opcodes.DOUBLE) ? 2 : 1;
        }

        Object[] out = new Object[size];
        Arrays.fill(out, Opcodes.TOP);

        for (int i = 0; i < size; i++) {
            if (bySlot[i] == Opcodes.TOP) {
                continue;
            }

            Local held = frameOwner(locals, i, index);
            out[held == null ? i : held.slot] = bySlot[i];
        }

        int last = -1;

        for (int i = 0; i < size; i++) {
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
     * frame の位置で、元の slot に居た変数。命令と違って scope の 1 つ前は見ない
     * (frame は label の直後に来るので、scope が始まっていれば覆われている)。
     */
    private static Local frameOwner(List<Local> locals, int slot, int index) {
        for (Local local : locals) {
            int from = local.from + 1;

            if (local.node.index == slot && index >= from && index < local.to) {
                return local;
            }

            if (local.wide && local.node.index + 1 == slot && index >= from && index < local.to) {
                return local;
            }
        }

        return null;
    }

    private static MethodNode copy(MethodNode method) {
        MethodNode out = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                method.signature, method.exceptions.toArray(new String[0]));
        method.accept(out);

        return out;
    }

    private static void restore(MethodNode method, MethodNode backup) {
        method.instructions = backup.instructions;
        method.localVariables = backup.localVariables;
        method.tryCatchBlocks = backup.tryCatchBlocks;
        method.maxLocals = backup.maxLocals;
        method.maxStack = backup.maxStack;
        method.visibleLocalVariableAnnotations = backup.visibleLocalVariableAnnotations;
        method.invisibleLocalVariableAnnotations = backup.invisibleLocalVariableAnnotations;
    }
}
