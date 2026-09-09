// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 組み上がったサーバーの命令列を、Mojang の公式クラスと突き合わせる。
 *
 * <pre>java dev.shifu.lvtmatch.CodeDiff &lt;組んだ jar か class の置き場&gt; &lt;Mojang の jar&gt; [出力]</pre>
 *
 * <p>Shifu の最重要条件は「vanilla と処理順まで一致すること」。
 * 発火の差し込みは意図して命令を足すが、それ以外で命令列が動いていないことは
 * 機械で言えないと保てない。{@code verify_additive.py} はソースの行を見るので、
 * ビルドの後段(paperweight の {@code fixJarForReobf} など)で入る書き換えを見つけられない。
 *
 * <h2>比べ方</h2>
 *
 * 公式にもこちらにもあるメソッドについて、命令を次の形にして並べて比べる。
 *
 * <ul>
 * <li>ラベル・行番号・frame は落とす(実行されない)</li>
 * <li>局所変数の番号は落とす({@code LvtMatch} が動かす。意味は変わらない)</li>
 * <li>field と method の参照は<b>所有クラスまで</b>見る
 *     ({@code Cat.tickCount} と {@code Entity.tickCount} を別物として数える)</li>
 * <li>invokedynamic は handle と bsm の引数まで見る</li>
 * </ul>
 *
 * <p>公式に無いメソッド(shim と hand の追加)は数えない。
 */
public final class CodeDiff {

	public static void main(String[] args) throws Exception {
		Map<String, byte[]> mine = classes(Paths.get(args[0]));
		Map<String, byte[]> theirs = classes(Paths.get(args[1]));
		Path out = args.length > 2 ? Paths.get(args[2]) : null;

		int same = 0;
		int differ = 0;
		int onlyOurs = 0;
		List<String> lines = new ArrayList<>();

		for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(theirs).entrySet()) {
			byte[] ours = mine.get(entry.getKey());

			if (ours == null) {
				continue;
			}

			Map<String, MethodNode> official = methods(entry.getValue());

			for (Map.Entry<String, MethodNode> method : new java.util.TreeMap<>(methods(ours)).entrySet()) {
				MethodNode moj = official.get(method.getKey());

				if (moj == null) {
					onlyOurs++;
					continue;
				}

				List<String> a = shape(method.getValue());
				List<String> b = shape(moj);

				if (a.equals(b)) {
					same++;
				} else {
					differ++;
					lines.add(entry.getKey() + " " + method.getKey() + "\t" + where(a, b));
				}
			}
		}

		System.out.println("命令列が同じ  : " + same);
		System.out.println("命令列が違う  : " + differ);
		System.out.println("こちらだけ    : " + onlyOurs);

		if (out != null) {
			Files.write(out, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
			System.out.println("一覧: " + out);
		}
	}

	/** 最初に食い違った位置。 */
	private static String where(List<String> a, List<String> b) {
		int limit = Math.min(a.size(), b.size());

		for (int i = 0; i < limit; i++) {
			if (!a.get(i).equals(b.get(i))) {
				return "at " + i + ": " + a.get(i) + " <-> " + b.get(i);
			}
		}

		return "length " + a.size() + " <-> " + b.size();
	}

	private static Map<String, byte[]> classes(Path source) throws IOException {
		Map<String, byte[]> out = new HashMap<>();

		if (Files.isDirectory(source)) {
			try (var walk = Files.walk(source)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
					out.put(source.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
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
					out.put(entry.getName(), in.readAllBytes());
				}
			}
		}

		return out;
	}

	private static Map<String, MethodNode> methods(byte[] data) {
		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Map<String, MethodNode> out = new HashMap<>();

		for (MethodNode method : node.methods) {
			out.put(method.name + method.desc, method);
		}

		return out;
	}

	/** 命令の並び。実行されないものと、意味の変わらない番号は落とす。 */
	private static List<String> shape(MethodNode method) {
		List<String> out = new ArrayList<>();

		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) {
				continue;
			}

			out.add(text(insn));
		}

		return out;
	}

	private static String text(AbstractInsnNode insn) {
		int op = insn.getOpcode();

		if (insn instanceof FieldInsnNode field) {
			return op + " " + field.owner + "." + field.name + ":" + field.desc;
		}

		if (insn instanceof MethodInsnNode call) {
			return op + " " + call.owner + "." + call.name + call.desc + (call.itf ? " itf" : "");
		}

		if (insn instanceof InvokeDynamicInsnNode indy) {
			StringBuilder sb = new StringBuilder(op + " " + indy.name + indy.desc + " " + handle(indy.bsm));

			for (Object arg : indy.bsmArgs) {
				sb.append(' ').append(arg instanceof Handle handle ? handle(handle) : String.valueOf(arg));
			}

			return sb.toString();
		}

		if (insn instanceof TypeInsnNode type) {
			return op + " " + type.desc;
		}

		if (insn instanceof LdcInsnNode ldc) {
			return op + " " + (ldc.cst instanceof Handle handle ? handle(handle) : String.valueOf(ldc.cst));
		}

		if (insn instanceof IntInsnNode value) {
			return op + " " + value.operand;
		}

		if (insn instanceof IincInsnNode iinc) {
			return op + " +" + iinc.incr;
		}

		if (insn instanceof MultiANewArrayInsnNode array) {
			return op + " " + array.desc + " " + array.dims;
		}

		if (insn instanceof TableSwitchInsnNode table) {
			return op + " " + table.min + ".." + table.max;
		}

		if (insn instanceof LookupSwitchInsnNode lookup) {
			return op + " " + lookup.keys;
		}

		// var の番号は落とす。jump の飛び先は並びの長さと opcode で見る
		if (insn instanceof VarInsnNode || insn instanceof JumpInsnNode || insn instanceof InsnNode) {
			return String.valueOf(op);
		}

		return String.valueOf(op);
	}

	private static String handle(Handle handle) {
		return handle.getTag() + ":" + handle.getOwner() + "." + handle.getName() + handle.getDesc();
	}

	private CodeDiff() {
	}
}
