// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * {@code patches/decompile/exprs.rules} の規則が名指ししたメソッドが、公式と同じ命令列に
 * コンパイルされたことを確かめる。1 つでも違えば exit 1。
 *
 * <pre>java dev.shifu.lvtmatch.SemDiff &lt;組んだ class の置き場&gt; &lt;Mojang の jar&gt; &lt;規則のファイルか置き場&gt;...</pre>
 *
 * <p>exprs.rules は vanilla の式を書き換える(逆コンパイラが落とした cast を戻す、do-while の条件の
 * 向きを戻す、など)。ソースの形では安全かどうか言えないので、規則ごとに {@code method: <クラス> <名前><記述子>}
 * を書き、コンパイルした結果が公式と一致することをここで示す。{@code verify_additive.py} は
 * 書き換えがそのメソッドの中に収まっていることだけを見て、一致の証明はこの道具に任せている。
 *
 * <h2>比べ方</h2>
 *
 * <ul>
 * <li>命令を 1 つずつ比べる。ラベル・行番号・frame は落とし、分岐の飛び先は命令の番号で比べる
 *     (条件の向きが逆なら飛び先が変わる)</li>
 * <li>局所変数の番号は比べない({@code LvtMatch} が動かす)。代わりに、局所変数を読む命令ごとに
 *     値がどの store(か引数)から来たかを比べる。形が同じでも別の変数を読んでいれば落ちる</li>
 * <li>無名クラスの番号・{@code val$}・{@code lambda$} の名前は、出会った位置で対応を取る。
 *     対応した無名クラスと lambda も全部同じ道で比べる(メソッドの中で作ったものまでが証明の範囲)</li>
 * </ul>
 *
 * <p>2026-09-23 の調べで、1.20.6 以前の {@code AbstractHorse.getDismountLocationInDirection} は
 * {@code while (!(y < 上限))} の形で逆コンパイルされ、公式の {@code iflt} が {@code ifge} に
 * なっていた。命令の数も種類も同じで、{@code CodeDiff} の数には埋もれていた。
 */
public final class SemDiff {

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err.println("使い方: SemDiff <class の置き場> <Mojang の jar> <規則>...");
			System.exit(2);
		}

		Map<String, byte[]> mine = classes(Paths.get(args[0]));
		Map<String, byte[]> theirs = classes(Paths.get(args[1]));
		// 同じメソッドを直す規則が複数あっても 1 回だけ比べる
		Set<String> keys = new LinkedHashSet<>();

		for (int i = 2; i < args.length; i++) {
			keys.addAll(methodKeys(Paths.get(args[i])));
		}

		int failed = 0;

		for (String key : keys) {
			List<String> why = new SemDiff(mine, theirs).prove(key);

			if (why.isEmpty()) {
				System.out.println("  一致: " + key);
				continue;
			}

			failed++;
			System.out.println("  違う: " + key);

			for (String line : why) {
				System.out.println("        " + line);
			}
		}

		System.out.println("exprs の証明: " + (keys.size() - failed) + " / " + keys.size() + " 件が公式と一致");

		if (failed > 0) {
			System.exit(1);
		}
	}

	/** 規則のファイル(置き場なら中の *.rules 全部)から {@code method:} の値を集める。 */
	static List<String> methodKeys(Path path) throws IOException {
		List<Path> files = new ArrayList<>();

		if (Files.isDirectory(path)) {
			try (var walk = Files.walk(path)) {
				walk.filter(p -> p.toString().endsWith(".rules")).sorted().forEach(files::add);
			}
		} else if (Files.exists(path)) {
			files.add(path);
		}

		List<String> out = new ArrayList<>();

		for (Path file : files) {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String stripped = line.strip();

				if (stripped.startsWith("method:")) {
					out.add(stripped.substring("method:".length()).strip());
				}
			}
		}

		return out;
	}

	private final Map<String, byte[]> oursBytes;
	private final Map<String, byte[]> offBytes;
	private final Map<String, ClassNode> oursNodes = new HashMap<>();
	private final Map<String, ClassNode> offNodes = new HashMap<>();
	private final Map<String, String> cls = new HashMap<>();
	private final Map<String, String> clsRev = new HashMap<>();
	private final Map<String, String> mem = new HashMap<>();
	private final Map<String, String> memRev = new HashMap<>();
	private final ArrayDeque<String[]> classQueue = new ArrayDeque<>();
	private final ArrayDeque<String[]> methodQueue = new ArrayDeque<>();
	private final Set<String> doneMethods = new HashSet<>();
	private final Set<String> doneClasses = new HashSet<>();
	private final List<String> problems = new ArrayList<>();

	private SemDiff(Map<String, byte[]> oursBytes, Map<String, byte[]> offBytes) {
		this.oursBytes = oursBytes;
		this.offBytes = offBytes;
	}

	/** {@code <クラス> <名前><記述子>} のメソッドを、中で作った無名クラスと lambda ごと比べる。違いの一覧を返す。 */
	private List<String> prove(String key) {
		int space = key.indexOf(' ');

		if (space < 0) {
			return List.of("method: の形が読めない(<クラス> <名前><記述子>)");
		}

		String owner = key.substring(0, space);
		String method = key.substring(space + 1);

		if (node(owner, true) == null || find(node(owner, true), method) == null) {
			return List.of("組んだ class にメソッドが無い");
		}

		if (node(owner, false) == null || find(node(owner, false), method) == null) {
			return List.of("公式の jar にメソッドが無い");
		}

		methodQueue.add(new String[] {owner, method, owner, method});

		while (!classQueue.isEmpty() || !methodQueue.isEmpty()) {
			if (!methodQueue.isEmpty()) {
				String[] p = methodQueue.poll();
				compareMethod(p[0], p[1], p[2], p[3]);
			} else {
				String[] p = classQueue.poll();
				compareClass(p[0], p[1]);
			}
		}

		return problems;
	}

	private static boolean synthetic(String name) {
		for (String part : name.substring(name.lastIndexOf('/') + 1).split("\\$")) {
			if (!part.isEmpty() && Character.isDigit(part.charAt(0))) {
				return true;
			}
		}

		return false;
	}

	private static boolean syntheticMember(String name) {
		return name.startsWith("lambda$") || name.startsWith("val$") || name.startsWith("access$");
	}

	private static Map<String, byte[]> classes(Path source) throws IOException {
		Map<String, byte[]> out = new HashMap<>();

		if (Files.isDirectory(source)) {
			try (var walk = Files.walk(source)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
					String rel = source.relativize(file).toString().replace('\\', '/');
					out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(file));
				}
			}

			return out;
		}

		try (ZipFile zip = new ZipFile(source.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (!entry.getName().endsWith(".class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					out.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), in.readAllBytes());
				}
			}
		}

		return out;
	}

	private ClassNode node(String name, boolean ours) {
		Map<String, ClassNode> cache = ours ? oursNodes : offNodes;
		ClassNode node = cache.get(name);

		if (node == null) {
			byte[] data = (ours ? oursBytes : offBytes).get(name);

			if (data == null) {
				return null;
			}

			node = new ClassNode();
			new ClassReader(data).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			cache.put(name, node);
		}

		return node;
	}

	private static MethodNode find(ClassNode node, String nameAndDesc) {
		for (MethodNode method : node.methods) {
			if (nameAndDesc.equals(method.name + method.desc)) {
				return method;
			}
		}

		return null;
	}

	// ---------------------------------------------------------------- 合成名の対応

	private boolean unifyClass(String a, String b) {
		if (a == null || b == null) {
			return a == b;
		}

		if (a.startsWith("[") || b.startsWith("[")) {
			return unifyDesc(a, b);
		}

		String known = cls.get(a);

		if (known != null) {
			return known.equals(b);
		}

		if (a.equals(b) && !synthetic(a)) {
			return true;
		}

		if (clsRev.containsKey(b) || !synthetic(a) || !synthetic(b)) {
			return false;
		}

		if (!oursBytes.containsKey(a) || !offBytes.containsKey(b)) {
			return a.equals(b);
		}

		cls.put(a, b);
		clsRev.put(b, a);
		classQueue.add(new String[] {a, b});
		return true;
	}

	private boolean unifyDesc(String a, String b) {
		List<String> ta = tokens(a);
		List<String> tb = tokens(b);

		if (ta.size() != tb.size()) {
			return false;
		}

		for (int i = 0; i < ta.size(); i++) {
			String x = ta.get(i);
			String y = tb.get(i);

			if (x.startsWith("L") && y.startsWith("L")) {
				if (!unifyClass(x.substring(1, x.length() - 1), y.substring(1, y.length() - 1))) {
					return false;
				}
			} else if (!x.equals(y)) {
				return false;
			}
		}

		return true;
	}

	private static List<String> tokens(String desc) {
		List<String> out = new ArrayList<>();
		int i = 0;

		while (i < desc.length()) {
			if (desc.charAt(i) == 'L') {
				int end = desc.indexOf(';', i);
				out.add(desc.substring(i, end + 1));
				i = end + 1;
			} else {
				out.add(String.valueOf(desc.charAt(i)));
				i++;
			}
		}

		return out;
	}

	private boolean unifyMember(String oOwner, String oName, String oDesc, String fOwner, String fName, String fDesc,
			boolean method) {
		if (!unifyClass(oOwner, fOwner) || !unifyDesc(oDesc, fDesc)) {
			return false;
		}

		if (!syntheticMember(oName) || !syntheticMember(fName)) {
			return oName.equals(fName);
		}

		if (oName.startsWith("lambda$") != fName.startsWith("lambda$")) {
			return false;
		}

		String key = oOwner + "." + oName + oDesc;
		String value = fOwner + "." + fName + fDesc;
		String known = mem.get(key);

		if (known != null) {
			return known.equals(value);
		}

		if (memRev.containsKey(value)) {
			return false;
		}

		mem.put(key, value);
		memRev.put(value, key);

		if (method && oursBytes.containsKey(oOwner) && offBytes.containsKey(fOwner)) {
			methodQueue.add(new String[] {oOwner, oName + oDesc, fOwner, fName + fDesc});
		}

		return true;
	}

	private String translate(String desc) {
		StringBuilder out = new StringBuilder();

		for (String token : tokens(desc)) {
			if (token.startsWith("L")) {
				String name = token.substring(1, token.length() - 1);
				out.append('L').append(cls.getOrDefault(name, name)).append(';');
			} else {
				out.append(token);
			}
		}

		return out.toString();
	}

	// ---------------------------------------------------------------- 比べる

	/** メソッドの中で作った無名クラス。欄の数と、全部のメソッドを比べる。 */
	private void compareClass(String a, String b) {
		if (!doneClasses.add(a)) {
			return;
		}

		ClassNode ours = node(a, true);
		ClassNode off = node(b, false);

		if (!unifyClass(ours.superName, off.superName) || !ours.interfaces.equals(off.interfaces)) {
			problems.add(a + ": 親か interface が違う");
		}

		int ov = 0;
		int fv = 0;

		for (FieldNode field : ours.fields) {
			ov += field.name.startsWith("val$") ? 1 : 0;
		}

		for (FieldNode field : off.fields) {
			fv += field.name.startsWith("val$") ? 1 : 0;
		}

		if (ov != fv) {
			problems.add(a + ": 捕まえた局所変数の数が違う(こちら " + ov + "、公式 " + fv + ")");
		}

		Set<String> matched = new HashSet<>();

		for (MethodNode method : ours.methods) {
			if (syntheticMember(method.name)) {
				continue;
			}

			String key = method.name + translate(method.desc);

			if (find(off, key) == null) {
				problems.add(a + " " + method.name + method.desc + ": 公式に無い");
				continue;
			}

			matched.add(key);
			methodQueue.add(new String[] {a, method.name + method.desc, b, key});
		}

		for (MethodNode method : off.methods) {
			if (!syntheticMember(method.name) && !matched.contains(method.name + method.desc)) {
				problems.add(b + " " + method.name + method.desc + ": こちらに無い");
			}
		}
	}

	private void compareMethod(String oc, String ond, String fc, String fnd) {
		if (!doneMethods.add(oc + "." + ond)) {
			return;
		}

		MethodNode ours = find(node(oc, true), ond);
		MethodNode off = find(node(fc, false), fnd);
		String name = oc + " " + ond;

		if (ours == null || off == null) {
			problems.add(name + ": 片方に無い");
			return;
		}

		List<AbstractInsnNode> oi = real(ours);
		List<AbstractInsnNode> fi = real(off);
		Map<LabelNode, Integer> ol = labels(ours);
		Map<LabelNode, Integer> fl = labels(off);
		int limit = Math.min(oi.size(), fi.size());

		for (int i = 0; i < limit; i++) {
			if (!same(oi.get(i), fi.get(i), ol, fl)) {
				problems.add(name + ": " + i + " 番目の命令が違う: " + text(oi.get(i), ol) + " / 公式 " + text(fi.get(i), fl));
				return;
			}
		}

		if (oi.size() != fi.size()) {
			problems.add(name + ": 命令の数が違う(こちら " + oi.size() + "、公式 " + fi.size() + ")");
			return;
		}

		if (!sameHandlers(ours, off, ol, fl)) {
			problems.add(name + ": 例外の受け口が違う");
			return;
		}

		String flow = flowDiff(oc, ours, fc, off, oi, fi);

		if (flow != null) {
			problems.add(name + ": 局所変数の出どころが違う: " + flow);
		}
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();

		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() >= 0) {
				out.add(insn);
			}
		}

		return out;
	}

	/** ラベル -> 次の実命令の番号。 */
	private static Map<LabelNode, Integer> labels(MethodNode method) {
		Map<LabelNode, Integer> out = new HashMap<>();
		List<LabelNode> pending = new ArrayList<>();
		int index = 0;

		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof LabelNode label) {
				pending.add(label);
			} else if (insn.getOpcode() >= 0) {
				for (LabelNode label : pending) {
					out.put(label, index);
				}

				pending.clear();
				index++;
			}
		}

		for (LabelNode label : pending) {
			out.put(label, index);
		}

		return out;
	}

	private boolean sameHandlers(MethodNode ours, MethodNode off, Map<LabelNode, Integer> ol, Map<LabelNode, Integer> fl) {
		if (ours.tryCatchBlocks.size() != off.tryCatchBlocks.size()) {
			return false;
		}

		for (int i = 0; i < ours.tryCatchBlocks.size(); i++) {
			TryCatchBlockNode a = ours.tryCatchBlocks.get(i);
			TryCatchBlockNode b = off.tryCatchBlocks.get(i);

			if (!ol.get(a.start).equals(fl.get(b.start)) || !ol.get(a.end).equals(fl.get(b.end))
					|| !ol.get(a.handler).equals(fl.get(b.handler)) || !unifyClass(a.type, b.type)) {
				return false;
			}
		}

		return true;
	}

	private boolean same(AbstractInsnNode a, AbstractInsnNode b, Map<LabelNode, Integer> al, Map<LabelNode, Integer> bl) {
		if (a.getOpcode() != b.getOpcode()) {
			return false;
		}

		if (a instanceof FieldInsnNode x && b instanceof FieldInsnNode y) {
			return unifyMember(x.owner, x.name, x.desc, y.owner, y.name, y.desc, false);
		}

		if (a instanceof MethodInsnNode x && b instanceof MethodInsnNode y) {
			return x.itf == y.itf && unifyMember(x.owner, x.name, x.desc, y.owner, y.name, y.desc, true);
		}

		if (a instanceof InvokeDynamicInsnNode x && b instanceof InvokeDynamicInsnNode y) {
			if (!x.name.equals(y.name) || !unifyDesc(x.desc, y.desc) || !sameHandle(x.bsm, y.bsm)
					|| x.bsmArgs.length != y.bsmArgs.length) {
				return false;
			}

			for (int i = 0; i < x.bsmArgs.length; i++) {
				if (!sameConstant(x.bsmArgs[i], y.bsmArgs[i])) {
					return false;
				}
			}

			return true;
		}

		if (a instanceof TypeInsnNode x && b instanceof TypeInsnNode y) {
			return unifyClass(x.desc, y.desc);
		}

		if (a instanceof LdcInsnNode x && b instanceof LdcInsnNode y) {
			return sameConstant(x.cst, y.cst);
		}

		if (a instanceof IntInsnNode x && b instanceof IntInsnNode y) {
			return x.operand == y.operand;
		}

		if (a instanceof IincInsnNode x && b instanceof IincInsnNode y) {
			return x.incr == y.incr;
		}

		if (a instanceof MultiANewArrayInsnNode x && b instanceof MultiANewArrayInsnNode y) {
			return x.dims == y.dims && unifyDesc(x.desc, y.desc);
		}

		if (a instanceof JumpInsnNode x && b instanceof JumpInsnNode y) {
			return al.get(x.label).equals(bl.get(y.label));
		}

		if (a instanceof TableSwitchInsnNode x && b instanceof TableSwitchInsnNode y) {
			return x.min == y.min && x.max == y.max && sameTargets(x.dflt, x.labels, y.dflt, y.labels, al, bl);
		}

		if (a instanceof LookupSwitchInsnNode x && b instanceof LookupSwitchInsnNode y) {
			return x.keys.equals(y.keys) && sameTargets(x.dflt, x.labels, y.dflt, y.labels, al, bl);
		}

		return true;
	}

	private static boolean sameTargets(LabelNode ad, List<LabelNode> as, LabelNode bd, List<LabelNode> bs,
			Map<LabelNode, Integer> al, Map<LabelNode, Integer> bl) {
		if (!al.get(ad).equals(bl.get(bd)) || as.size() != bs.size()) {
			return false;
		}

		for (int i = 0; i < as.size(); i++) {
			if (!al.get(as.get(i)).equals(bl.get(bs.get(i)))) {
				return false;
			}
		}

		return true;
	}

	private boolean sameHandle(Handle x, Handle y) {
		return x.getTag() == y.getTag() && unifyMember(x.getOwner(), x.getName(), x.getDesc(), y.getOwner(),
				y.getName(), y.getDesc(), x.getTag() >= Opcodes.H_INVOKEVIRTUAL);
	}

	private boolean sameConstant(Object x, Object y) {
		if (x instanceof Handle h && y instanceof Handle g) {
			return sameHandle(h, g);
		}

		if (x instanceof Type t && y instanceof Type u) {
			if (t.getSort() == Type.METHOD && u.getSort() == Type.METHOD) {
				return unifyDesc(t.getDescriptor(), u.getDescriptor());
			}

			if (t.getSort() == Type.OBJECT && u.getSort() == Type.OBJECT) {
				return unifyClass(t.getInternalName(), u.getInternalName());
			}

			return t.equals(u);
		}

		// 0.0 と -0.0、NaN を値ではなくビットで見る
		if (x instanceof Double d && y instanceof Double e) {
			return Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(e);
		}

		if (x instanceof Float d && y instanceof Float e) {
			return Float.floatToRawIntBits(d) == Float.floatToRawIntBits(e);
		}

		return Objects.equals(x, y);
	}

	// ---------------------------------------------------------------- 局所変数の出どころ

	/** 形が同じ 2 つのメソッドで、局所変数を読む命令ごとに値の出どころを比べる。違えば最初の 1 件。 */
	private static String flowDiff(String oc, MethodNode ours, String fc, MethodNode off,
			List<AbstractInsnNode> oi, List<AbstractInsnNode> fi) {
		Frame<SourceValue>[] of;
		Frame<SourceValue>[] ff;

		try {
			of = new Analyzer<>(new SourceInterpreter()).analyze(oc, ours);
			ff = new Analyzer<>(new SourceInterpreter()).analyze(fc, off);
		} catch (AnalyzerException e) {
			return "解析できない: " + e.getMessage();
		}

		Map<AbstractInsnNode, Integer> oidx = index(oi);
		Map<AbstractInsnNode, Integer> fidx = index(fi);

		for (int i = 0; i < oi.size(); i++) {
			int av;
			int bv;

			if (oi.get(i) instanceof VarInsnNode x && isLoad(x.getOpcode())) {
				av = x.var;
				bv = ((VarInsnNode) fi.get(i)).var;
			} else if (oi.get(i) instanceof IincInsnNode x) {
				av = x.var;
				bv = ((IincInsnNode) fi.get(i)).var;
			} else {
				continue;
			}

			Set<String> sa = roots(ours, of, oidx, oi.get(i), av, new HashSet<>());
			Set<String> sb = roots(off, ff, fidx, fi.get(i), bv, new HashSet<>());

			if (sa != null && sb != null && !sa.equals(sb)) {
				return i + " 番目: こちら " + sa + "、公式 " + sb;
			}
		}

		return null;
	}

	/**
	 * at で var を読むときの値の出どころ。store の命令の番号か、引数(p + slot)。
	 * {@code x = y;} の写し(load の直後の store)は元までたどる。逆コンパイラは写しを消したり
	 * 足したりするが、命令の形が同じならここに来るのは同じ並びのものだけ。
	 */
	private static Set<String> roots(MethodNode method, Frame<SourceValue>[] frames, Map<AbstractInsnNode, Integer> index,
			AbstractInsnNode at, int var, Set<AbstractInsnNode> seen) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(at)];

		if (frame == null) {
			return null; // 届かない命令
		}

		Set<String> out = new TreeSet<>();
		Set<AbstractInsnNode> producers = frame.getLocal(var).insns;

		if (producers.isEmpty()) {
			out.add("p" + var);
			return out;
		}

		for (AbstractInsnNode producer : producers) {
			AbstractInsnNode previous = producer.getPrevious();

			while (previous != null && previous.getOpcode() < 0) {
				previous = previous.getPrevious();
			}

			if (producer instanceof VarInsnNode store && previous instanceof VarInsnNode load && isLoad(load.getOpcode())
					&& load.getOpcode() + (Opcodes.ISTORE - Opcodes.ILOAD) == store.getOpcode() && seen.add(producer)) {
				Set<String> upstream = roots(method, frames, index, load, load.var, seen);

				if (upstream != null) {
					out.addAll(upstream);
					continue;
				}
			}

			out.add("i" + index.getOrDefault(producer, -1));
		}

		return out;
	}

	private static boolean isLoad(int opcode) {
		return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
	}

	private static Map<AbstractInsnNode, Integer> index(List<AbstractInsnNode> list) {
		Map<AbstractInsnNode, Integer> out = new IdentityHashMap<>();

		for (int i = 0; i < list.size(); i++) {
			out.put(list.get(i), i);
		}

		return out;
	}

	private static String targets(LabelNode dflt, List<LabelNode> cases, Map<LabelNode, Integer> labels) {
		List<Integer> out = new ArrayList<>();

		for (LabelNode label : cases) {
			out.add(labels.get(label));
		}

		return "-> " + out + " default " + labels.get(dflt);
	}

	private static String text(AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
		String op = String.valueOf(insn.getOpcode());

		if (insn instanceof FieldInsnNode x) {
			return op + " " + x.owner + "." + x.name;
		}

		if (insn instanceof MethodInsnNode x) {
			return op + " " + x.owner + "." + x.name + x.desc;
		}

		if (insn instanceof JumpInsnNode x) {
			return op + " -> " + labels.get(x.label);
		}

		if (insn instanceof LdcInsnNode x) {
			return op + " " + x.cst;
		}

		if (insn instanceof TableSwitchInsnNode x) {
			return op + " " + targets(x.dflt, x.labels, labels);
		}

		if (insn instanceof LookupSwitchInsnNode x) {
			return op + " " + x.keys + " " + targets(x.dflt, x.labels, labels);
		}

		if (insn instanceof TypeInsnNode x) {
			return op + " " + x.desc;
		}

		return op;
	}
}
