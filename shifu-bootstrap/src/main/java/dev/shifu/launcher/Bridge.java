// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

/**
 * 難読化されているバージョンで、MOD の intermediary とサーバーの mojmap を繋ぐ。
 *
 * <p>fabric-loader が同梱している mapping-io と tiny-remapper を使うので、
 * 起動クラスパスに fabric-loader が要る。{@link Namespace} が子 JVM で呼ぶ。
 *
 * <pre>
 * java -cp shifu.jar;fabric-loader.jar;asm... dev.shifu.launcher.Bridge compose &lt;proguard&gt; &lt;intermediary&gt; &lt;出力&gt;
 * java -cp ... dev.shifu.launcher.Bridge remap &lt;マッピング&gt; &lt;入力 jar&gt; &lt;出力 jar&gt; &lt;libraries&gt;
 * </pre>
 */
public final class Bridge {
	private Bridge() {
	}

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "compose" -> compose(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
			case "remap" -> remap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
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
	private static void remap(Path mappings, Path input, Path output, Path librariesDir) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(mappings, tree);

		List<Path> classPath = new ArrayList<>();

		try (var walk = Files.walk(librariesDir)) {
			walk.filter(path -> path.toString().endsWith(".jar")).forEach(classPath::add);
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
}
