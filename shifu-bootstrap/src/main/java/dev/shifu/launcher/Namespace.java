// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MOD の名前空間(intermediary)を、サーバーの名前空間(mojmap)へ繋ぐ。
 *
 * <p>26.1 で Mojang が難読化をやめ、Fabric も intermediary の更新を止めたので、
 * それ以降は MOD もサーバーも mojmap を見る。ここは要らない。
 * それより前は Paper 自身は mojmap で動くが MOD は intermediary のままなので、
 * 3 つを用意して fabric-loader に渡す。
 *
 * <ol>
 *   <li>intermediary と Mojang の {@code server.txt} を合わせたマッピング</li>
 *   <li>名前空間を伝えるシステムプロパティ</li>
 *   <li>入力側(intermediary)の名前空間にしたリマップ用クラスパス</li>
 * </ol>
 *
 * <p>intermediary が公開されていないバージョン(26.1 以降)は {@code null} を返す。
 */
record Namespace(Path mappings, Path remapClassPath) {
	private static final String INTERMEDIARY = "https://maven.fabricmc.net/net/fabricmc/intermediary/%1$s/intermediary-%1$s-v2.jar";
	private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

	static Namespace prepare(Downloader downloader, Path serverDir, String minecraftVersion,
			PaperArtifacts paper, FabricArtifacts fabric, Path shifuJar)
			throws IOException, InterruptedException {
		Path dir = serverDir.resolve(".shifu").resolve("namespace");
		Path intermediary = dir.resolve("intermediary.tiny");

		if (!Files.isRegularFile(intermediary) && !fetchIntermediary(downloader, dir, minecraftVersion, intermediary)) {
			return null;
		}

		Path proguard = dir.resolve("server-mappings.txt");

		if (!Files.isRegularFile(proguard)) {
			Log.info("downloading Mojang mappings for %s", minecraftVersion);
			downloader.download(serverMappings(downloader, minecraftVersion), proguard, null);
		}

		Path mappings = dir.resolve("mappings.tiny");

		if (!Files.isRegularFile(mappings)) {
			Log.info("composing intermediary -> mojmap mappings");
			bridge(fabric, shifuJar, "compose", proguard, intermediary, mappings);
		}

		Path remapped = dir.resolve("server-intermediary.jar");
		Path stamp = dir.resolve("server-intermediary.from");
		String hash = Downloader.sha256(paper.serverJar());

		if (!Files.isRegularFile(remapped) || !hash.equals(read(stamp))) {
			Log.info("writing the server jar in the intermediary namespace (this takes a minute)");
			bridge(fabric, shifuJar, "remap", mappings, paper.serverJar(), remapped, paper.librariesDir());
			Files.writeString(stamp, hash);
		}

		Path classPath = dir.resolve("remap-classpath.txt");
		Files.writeString(classPath, remapClassPath(remapped, paper.librariesDir()), StandardCharsets.UTF_8);

		return new Namespace(mappings, classPath);
	}

	/** 子 JVM に渡すシステムプロパティ。 */
	List<String> jvmArgs() {
		return List.of(
				"-Dfabric.mappingPath=" + this.mappings.toAbsolutePath(),
				"-Dfabric.runtimeMappingNamespace=named",
				"-Dfabric.defaultModDistributionNamespace=intermediary",
				// MOD を intermediary から named へ写すのは、fabric-loader では開発環境の経路。
				"-Dfabric.development=true",
				"-Dfabric.remapClasspathFile=" + this.remapClassPath.toAbsolutePath());
	}

	/** @return 取れたか。そのバージョンに intermediary が無ければ false */
	private static boolean fetchIntermediary(Downloader downloader, Path dir, String minecraftVersion, Path out)
			throws IOException, InterruptedException {
		Path jar = dir.resolve("intermediary.jar");
		String url = String.format(INTERMEDIARY, minecraftVersion);

		try {
			downloader.download(url, jar, null);
		} catch (IOException e) {
			if (e.getMessage() != null && e.getMessage().endsWith(" returned 404")) {
				Log.info("no intermediary for %s - mods and plugins share the mojmap namespace", minecraftVersion);

				return false;
			}

			throw e;
		}

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("mappings/mappings.tiny");

			if (entry == null) {
				throw new IOException("intermediary jar has no mappings/mappings.tiny: " + jar);
			}

			try (InputStream in = zip.getInputStream(entry)) {
				Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		return true;
	}

	/** Mojang が配っているサーバーの proguard マッピングの URL。 */
	private static String serverMappings(Downloader downloader, String minecraftVersion)
			throws IOException, InterruptedException {
		Map<String, Object> manifest = Json.object(Json.parse(downloader.getString(MANIFEST)));

		for (Object entry : Json.array(manifest.get("versions"))) {
			Map<String, Object> version = Json.object(entry);

			if (!minecraftVersion.equals(Json.string(version.get("id")))) {
				continue;
			}

			Map<String, Object> details = Json.object(Json.parse(downloader.getString(Json.string(version.get("url")))));

			return Json.string(Json.object(details, "downloads", "server_mappings").get("url"));
		}

		throw new IOException("Mojang の版一覧に " + minecraftVersion + " が無い");
	}

	/** {@link Bridge} を子 JVM で走らせる。fabric-loader の内部 API を使うので同居できない。 */
	private static void bridge(FabricArtifacts fabric, Path shifuJar, String task, Path... args)
			throws IOException, InterruptedException {
		List<String> classPath = new ArrayList<>();
		classPath.add(shifuJar.toAbsolutePath().toString());
		fabric.classPath().forEach(path -> classPath.add(path.toAbsolutePath().toString()));

		List<String> command = new ArrayList<>();
		command.add(ServerLaunch.javaBinary());
		command.add("-Xmx2G");
		command.add("-cp");
		command.add(String.join(File.pathSeparator, classPath));
		command.add(Bridge.class.getName());
		command.add(task);

		for (Path arg : args) {
			command.add(arg.toAbsolutePath().toString());
		}

		Process process = new ProcessBuilder(command).inheritIO().start();
		int exit = process.waitFor();

		if (exit != 0) {
			throw new IOException("マッピングの " + task + " が " + exit + " で終わった");
		}
	}

	/** tiny-remapper が MOD を写すときに読むクラスパス。入力側(intermediary)の名前空間で並べる。 */
	private static String remapClassPath(Path server, Path librariesDir) throws IOException {
		StringBuilder out = new StringBuilder(server.toAbsolutePath().toString());

		try (Stream<Path> walk = Files.walk(librariesDir)) {
			for (Path jar : walk.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
				out.append(File.pathSeparatorChar).append(jar.toAbsolutePath());
			}
		}

		return out.toString();
	}

	private static String read(Path file) throws IOException {
		return Files.isRegularFile(file) ? Files.readString(file).trim() : null;
	}
}
