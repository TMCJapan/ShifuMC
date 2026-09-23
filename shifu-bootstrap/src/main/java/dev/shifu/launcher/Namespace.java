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
		Path dir = serverDir.resolve(".shifu").resolve("versions").resolve(minecraftVersion)
				.resolve("namespace");
		Path intermediary = dir.resolve("intermediary.tiny");

		if (!Files.isRegularFile(intermediary) && !fetchIntermediary(downloader, dir, minecraftVersion, intermediary)) {
			return null;
		}

		List<Path> paperLibraries = declaredLibraries(paper);
		Path paperClassPath = dir.resolve("paper-classpath.txt");
		Files.createDirectories(dir);
		Files.write(paperClassPath,
				paperLibraries.stream().map(path -> path.toAbsolutePath().toString()).toList(),
				StandardCharsets.UTF_8);

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
			bridge(fabric, shifuJar, "remap", mappings, paper.serverJar(), remapped, paperClassPath);
			Files.writeString(stamp, hash);
		}

		Path classPath = dir.resolve("remap-classpath.txt");
		Files.writeString(classPath, remapClassPath(remapped, paperLibraries), StandardCharsets.UTF_8);

		return new Namespace(mappings, classPath);
	}

	/** 子 JVM に渡すシステムプロパティ。 */
	List<String> jvmArgs() {
		return List.of(
				"-Dfabric.mappingPath=" + this.mappings.toAbsolutePath(),
				"-Dfabric.runtimeMappingNamespace=named",
				"-Dfabric.defaultModDistributionNamespace=intermediary",
				// MOD を intermediary から named へ写すのは、fabric-loader では開発環境の経路。
				// MOD に見える答えは productionAnswer で本番と同じ false にする。
				"-Dfabric.development=true",
				// 開発環境の経路では fabric-loader が MOD の順を毎回シャッフルする(FabricLoaderImpl.setup)。
				// Mixin の適用順が起動ごとに変わり、同じ呼び出しを @Redirect する MOD 同士
				// (1.18.2 の krypton と lithium の ChunkMap.TrackedEntity)は後になった方が外れる。
				// 本番と同じ id 順で固定する
				"-Dfabric.debug.disableModShuffle=true",
				"-Dfabric.remapClasspathFile=" + this.remapClassPath.toAbsolutePath());
	}

	/**
	 * {@code FabricLoader.isDevelopmentEnvironment()} が、凍結後(MOD が動き出してから)は
	 * false を返す fabric-loader に差し替える(書き換えの中身と理由は {@link Bridge})。
	 * {@code -Dfabric.development=true} のままだと MOD にも true が見え、Fabric API の
	 * {@code /debugconfig} のような vanilla に無いコマンドが増えていた。
	 *
	 * <p>書き換えた jar は元の jar のハッシュで印を付け、fabric-loader の版が変わったら作り直す。
	 * 書き換えられなかったときは元の jar のまま起動する(MOD には true が見える)。
	 */
	static FabricArtifacts productionAnswer(FabricArtifacts fabric, Path shifuJar) throws InterruptedException {
		List<Path> classPath = new ArrayList<>(fabric.classPath());
		int index = -1;

		for (int i = 0; i < classPath.size(); i++) {
			if (classPath.get(i).getFileName().toString().startsWith("fabric-loader-")) {
				index = i;
			}
		}

		if (index < 0) {
			Log.warn("fabric-loader is not on the classpath - mods will see isDevelopmentEnvironment() = true");
			return fabric;
		}

		Path original = classPath.get(index);
		String name = original.getFileName().toString();
		Path patched = original.resolveSibling(name.substring(0, name.length() - ".jar".length()) + "-shifu.jar");
		Path stamp = patched.resolveSibling(patched.getFileName() + ".from");

		try {
			// 書き換えの形を変えたら REVISION を上げて、作り直させる。
			String from = Downloader.sha256(original) + " " + LOADER_ANSWER_REVISION;

			if (!Files.isRegularFile(patched) || !from.equals(read(stamp))) {
				Log.info("rewriting %s so that mods see isDevelopmentEnvironment() = false", name);
				Files.deleteIfExists(stamp);
				bridge(fabric, shifuJar, "loader-answer", original, patched);
				Files.writeString(stamp, from);
			}
		} catch (IOException e) {
			Log.warn("could not rewrite %s (%s) - mods will see isDevelopmentEnvironment() = true", name,
					e.getMessage());
			return fabric;
		}

		classPath.set(index, patched);

		return new FabricArtifacts(List.copyOf(classPath), fabric.mainClass());
	}

	private static final String LOADER_ANSWER_REVISION = "1";

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
			throw new IOException("Bridge " + task + " が " + exit + " で終わった");
		}
	}

	/** bundler が宣言した Paper のライブラリだけを読む。共有 libraries/ 全体は走査しない。 */
	private static List<Path> declaredLibraries(PaperArtifacts paper) throws IOException {
		Path list = paper.librariesList();

		if (!Files.isRegularFile(list)) {
			throw new IOException("paperclip has no META-INF/libraries.list: " + list);
		}

		Path root = paper.librariesDir().toAbsolutePath().normalize();
		List<Path> libraries = new ArrayList<>();

		for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}

			String[] parts = line.split("\\t", -1);

			if (parts.length < 3 || parts[2].isBlank()) {
				throw new IOException("invalid libraries.list entry: " + line);
			}

			Path library = root.resolve(parts[2].trim()).normalize();

			if (!library.startsWith(root)) {
				throw new IOException("libraries.list entry escapes libraries directory: " + parts[2]);
			}
			if (!Files.isRegularFile(library)) {
				throw new IOException("paper library is missing: " + library);
			}

			libraries.add(library);
		}

		if (libraries.isEmpty()) {
			throw new IOException("paperclip libraries.list is empty: " + list);
		}

		return List.copyOf(libraries);
	}

	/** tiny-remapper が MOD を写すときに読むクラスパス。入力側(intermediary)の名前空間で並べる。 */
	private static String remapClassPath(Path server, List<Path> libraries) {
		StringBuilder out = new StringBuilder(server.toAbsolutePath().toString());

		for (Path jar : libraries) {
			out.append(File.pathSeparatorChar).append(jar.toAbsolutePath());
		}

		return out.toString();
	}

	private static String read(Path file) throws IOException {
		return Files.isRegularFile(file) ? Files.readString(file).trim() : null;
	}
}
