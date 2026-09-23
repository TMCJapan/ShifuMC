// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.fabricmc.loader.impl.lib.mappingio.MappingReader;
import net.fabricmc.loader.impl.lib.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.loader.impl.lib.mappingio.format.MappingFormat;
import net.fabricmc.loader.impl.lib.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree;
import net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree;
import net.fabricmc.loader.impl.lib.tinyremapper.InputTag;
import net.fabricmc.loader.impl.lib.tinyremapper.NonClassCopyMode;
import net.fabricmc.loader.impl.lib.tinyremapper.OutputConsumerPath;
import net.fabricmc.loader.impl.lib.tinyremapper.TinyRemapper;
import net.fabricmc.loader.impl.lib.tinyremapper.TinyUtils;
import net.fabricmc.loader.impl.lib.tinyremapper.api.TrLogger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 難読化されているバージョンで、MOD の intermediary とサーバーの mojmap を繋ぐ。
 *
 * <p>fabric-loader が同梱している mapping-io と tiny-remapper を使うので、
 * 起動クラスパスに fabric-loader が要る。{@link Namespace} が子 JVM で呼ぶ。
 *
 * <pre>
 * java -cp shifu.jar;fabric-loader.jar;asm... dev.shifu.launcher.Bridge compose &lt;proguard&gt; &lt;intermediary&gt; &lt;出力&gt;
 * java -cp ... dev.shifu.launcher.Bridge remap &lt;マッピング&gt; &lt;入力 jar&gt; &lt;出力 jar&gt; &lt;classpath list&gt;
 * java -cp ... dev.shifu.launcher.Bridge loader-answer &lt;fabric-loader jar&gt; &lt;出力 jar&gt;
 * </pre>
 */
public final class Bridge {
	private Bridge() {
	}

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "compose" -> compose(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
			case "remap" -> remap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
			case "loader-answer" -> loaderAnswer(Path.of(args[1]), Path.of(args[2]));
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	/**
	 * Mojang の proguard(named -> official)と intermediary(official -> intermediary)を
	 * official を軸に合わせ、intermediary を起点にした tiny v2 を書く。
	 *
	 * <p>実行時に要るのは intermediary -> named。tiny v2 は起点の名前空間の
	 * ディスクリプタしか持たないので、起点を intermediary にして書く。
	 */
	private static void compose(Path proguard, Path intermediary, Path out) throws IOException {
		MemoryMappingTree official = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(proguard, StandardCharsets.UTF_8)) {
			ProGuardFileReader.read(reader, "named", "official", new MappingSourceNsSwitch(official, "official", false));
		}

		MappingReader.read(intermediary, MappingFormat.TINY_2_FILE, official);

		MemoryMappingTree tree = new MemoryMappingTree();
		official.accept(new MappingSourceNsSwitch(tree, "intermediary", true));

		write(tree, out);
	}

	/**
	 * tiny v2 で書き出す。
	 *
	 * <p>fabric-loader が同梱している mapping-io には読む側しか入っていないので、
	 * 書く側はここで持つ。名前に区切り文字が出ないので、逃がし({@code escaped-names})は要らない。
	 */
	private static void write(MappingTree tree, Path out) throws IOException {
		List<String> targets = new ArrayList<>(tree.getDstNamespaces());

		Files.createDirectories(out.getParent());

		try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
			writer.write("tiny\t2\t0\t" + tree.getSrcNamespace());

			for (String target : targets) {
				writer.write("\t" + target);
			}

			writer.write("\n");

			for (MappingTree.ClassMapping type : tree.getClasses()) {
				writer.write("c\t" + type.getSrcName());
				names(writer, type, targets.size());

				for (MappingTree.FieldMapping field : type.getFields()) {
					writer.write("\tf\t" + field.getSrcDesc() + "\t" + field.getSrcName());
					names(writer, field, targets.size());
				}

				for (MappingTree.MethodMapping method : type.getMethods()) {
					writer.write("\tm\t" + method.getSrcDesc() + "\t" + method.getSrcName());
					names(writer, method, targets.size());
				}
			}
		}
	}

	private static void names(BufferedWriter writer, MappingTree.ElementMapping element, int count)
			throws IOException {
		for (int i = 0; i < count; i++) {
			String name = element.getDstName(i);
			writer.write("\t" + (name == null ? "" : name));
		}

		writer.write("\n");
	}

	/**
	 * サーバーの jar を named から intermediary へ写す。
	 *
	 * <p>MOD を変換するときのクラスパスに要る。tiny-remapper は入力側の名前空間で
	 * 継承を解決するので、mojmap のままでは親クラスのメソッドを見つけられない。
	 */
	private static void remap(Path mappings, Path input, Path output, Path classPathFile) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(mappings, tree);

		List<Path> classPath = new ArrayList<>();

		for (String line : Files.readAllLines(classPathFile, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}

			Path library = Path.of(line);

			if (!Files.isRegularFile(library)) {
				throw new IOException("remap classpath entry is missing: " + library);
			}

			classPath.add(library);
		}

		TrLogger logger = (level, message) -> {
			if (level == TrLogger.Level.ERROR) {
				Log.warn("%s", message);
			}
		};

		TinyRemapper remapper = TinyRemapper.newRemapper(logger)
				.withMappings(TinyUtils.createMappingProvider(tree, "named", "intermediary"))
				.renameInvalidLocals(false)
				.build();

		Files.deleteIfExists(output);
		Files.createDirectories(output.getParent());

		try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
			consumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);
			remapper.readClassPathAsync(classPath.toArray(new Path[0]));
			remapper.readInputsAsync(null, input);
			remapper.apply(consumer, new InputTag[0]);
		} finally {
			remapper.finish();
		}
	}

	private static final String LOADER_IMPL = "net/fabricmc/loader/impl/FabricLoaderImpl";

	/**
	 * {@code isDevelopmentEnvironment()} を呼んでよい場所。ここに無い呼び出しがある版では書き換えない。
	 *
	 * <p>{@code FabricLoaderImpl} の中は {@code setup()} だけ({@code remapRegularMods} と MOD 順のシャッフル)。
	 * {@code setup()} は {@code load()} からしか呼ばれず、{@code load()} は凍結後だと例外にするので、
	 * 必ず凍結前に true を受け取る。{@code ModDiscoverer} も {@code setup()} の中でしか走らない。
	 * {@code ModContainerImpl} は警告を 1 度で止めるかどうかだけ。{@code MinecraftGameProvider} は
	 * {@code -Dfabric.skipMcProvider=true} で選ばれない。{@code launch.common.FabricLauncherBase} は
	 * MOD 向けの古い API で、MOD と同じ答えになる。
	 * (読んだ位置: fabric-loader 0.19.3 / 0.19.5 の jar を javap で全クラス走査。
	 * ソースは FabricLoaderImpl.java:196,210,260,472、launch/knot/Knot.java:135-141)
	 */
	private static final Set<String> KNOWN_CALLERS = Set.of(
			LOADER_IMPL + "#setup",
			"net/fabricmc/loader/impl/discovery/ModDiscoverer",
			"net/fabricmc/loader/impl/discovery/ModDiscoverer$ModScanTask",
			"net/fabricmc/loader/impl/ModContainerImpl",
			"net/fabricmc/loader/impl/game/minecraft/MinecraftGameProvider",
			"net/fabricmc/loader/launch/common/FabricLauncherBase");

	/**
	 * MOD に見える {@code FabricLoader.isDevelopmentEnvironment()} だけを、凍結後は false にした
	 * fabric-loader を書く。
	 *
	 * <p>MOD を intermediary から写す経路は開発環境の判定の中にしか無いので
	 * {@code -Dfabric.development=true} は外せない。そのままだと MOD にも true が見え、
	 * Fabric API が vanilla の開発用コマンド {@code /debugconfig} を登録し、C2ME が
	 * {@code /c2me debug} を出し、object-builder は遅い登録を例外にしていた。
	 * 読み込み側({@code MappingConfiguration}、{@code FabricMixinBootstrap}、{@code KnotClassDelegate})は
	 * {@code FabricLauncherBase.isDevelopment()} を直接読むので、書き換えの影響を受けない。
	 *
	 * <p>分岐を足すと StackMapTable を作り直すことになるので、
	 * {@code launcher.isDevelopment() & !frozen} の分岐の無い形で書く。
	 * 書き換えたクラスは署名と合わなくなるので、署名ファイルは落とす。
	 */
	private static void loaderAnswer(Path input, Path target) throws IOException {
		Path output = target.toAbsolutePath();
		Path temp = output.resolveSibling(output.getFileName() + ".part");
		Set<String> unknown = new LinkedHashSet<>();
		boolean[] patched = new boolean[1];

		Files.createDirectories(output.getParent());

		try (ZipFile zip = new ZipFile(input.toFile());
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();

				if (isSignature(name)) {
					continue;
				}

				byte[] bytes;

				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}

				if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
					String owner = name.substring(0, name.length() - ".class".length());
					collectCallers(bytes, owner, unknown);

					if (owner.equals(LOADER_IMPL)) {
						bytes = rewriteAnswer(bytes, patched);
					}
				}

				out.putNextEntry(new ZipEntry(name));
				out.write(bytes);
				out.closeEntry();
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temp);
			throw e;
		}

		if (!unknown.isEmpty() || !patched[0]) {
			Files.deleteIfExists(temp);
			throw new IOException(!patched[0]
					? LOADER_IMPL + " has no isDevelopmentEnvironment()Z / frozen:Z to rewrite"
					: "isDevelopmentEnvironment() is called from places not reviewed: " + unknown);
		}

		Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
	}

	/** jar 署名のファイル。1 つでも残っていると、書き換えたクラスの読み込みが SecurityException になる。 */
	private static boolean isSignature(String name) {
		String upper = name.toUpperCase(Locale.ROOT);

		return upper.startsWith("META-INF/") && upper.indexOf('/', "META-INF/".length()) < 0
				&& (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
						|| upper.endsWith(".EC"));
	}

	private static void collectCallers(byte[] bytes, String owner, Set<String> unknown) {
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
					String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String target, String name, String desc,
							boolean isInterface) {
						if (!name.equals("isDevelopmentEnvironment") || !desc.equals("()Z")) {
							return;
						}

						String site = owner.equals(LOADER_IMPL) ? owner + "#" + method : owner;

						if (!KNOWN_CALLERS.contains(site)) {
							unknown.add(site);
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	private static byte[] rewriteAnswer(byte[] bytes, boolean[] patched) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		boolean[] hasFrozen = new boolean[1];
		boolean[] hasMethod = new boolean[1];

		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature,
					Object value) {
				if (name.equals("frozen") && descriptor.equals("Z") && (access & Opcodes.ACC_STATIC) == 0) {
					hasFrozen[0] = true;
				}

				return super.visitField(access, name, descriptor, signature, value);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);

				if (!name.equals("isDevelopmentEnvironment") || !descriptor.equals("()Z")) {
					return method;
				}

				method.visitCode();
				method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/fabricmc/loader/impl/launch/FabricLauncherBase",
						"getLauncher", "()Lnet/fabricmc/loader/impl/launch/FabricLauncher;", false);
				method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/fabricmc/loader/impl/launch/FabricLauncher",
						"isDevelopment", "()Z", true);
				method.visitVarInsn(Opcodes.ALOAD, 0);
				method.visitFieldInsn(Opcodes.GETFIELD, LOADER_IMPL, "frozen", "Z");
				method.visitInsn(Opcodes.ICONST_1);
				method.visitInsn(Opcodes.IXOR);
				method.visitInsn(Opcodes.IAND);
				method.visitInsn(Opcodes.IRETURN);
				method.visitMaxs(3, 1);
				method.visitEnd();
				hasMethod[0] = true;

				// 元の本体は書かない。
				return null;
			}
		}, 0);

		patched[0] = hasFrozen[0] && hasMethod[0];

		return writer.toByteArray();
	}
}
