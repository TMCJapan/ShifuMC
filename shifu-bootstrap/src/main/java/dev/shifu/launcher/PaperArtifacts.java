// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Paper 公式ビルドを取得し、ユーザーのマシン上でサーバー jar を組み立てる。
 *
 * <p>配布されているのは Paperclip というブートストラップで、Minecraft のクラスを含まない。
 * {@code -Dpaperclip.patchonly=true} で走らせると、Mojang 公式 jar をダウンロードして
 * bsdiff を当て、{@code versions/<ver>/paper-<ver>.jar} と {@code libraries/} を生成する。
 * Paper のコードも Minecraft のコードも Shifu の配布物には入らない。
 */
record PaperArtifacts(Path serverJar, Path librariesDir, Path vanillaJar, Path librariesList) {
	private static final String FILL_API = "https://fill.papermc.io/v3/projects/paper";

	static PaperArtifacts obtain(Downloader downloader, Path serverDir, String minecraftVersion, String build)
			throws IOException, InterruptedException {
		return obtain(downloader, serverDir, minecraftVersion, build, "");
	}

	/**
	 * @param ownPaperclip Shifu 自身のサーバー(paperclip 形式)のパスか URL。
	 *                     空なら Paper 公式ビルドを取る
	 */
	static PaperArtifacts obtain(Downloader downloader, Path serverDir, String minecraftVersion, String build,
			String ownPaperclip) throws IOException, InterruptedException {
		Path serverJar = serverDir.resolve("versions").resolve(minecraftVersion)
				.resolve("paper-" + minecraftVersion + ".jar");
		Path librariesDir = serverDir.resolve("libraries");
		Path vanillaJar = serverDir.resolve("cache").resolve("mojang_" + minecraftVersion + ".jar");
		Path stamp = serverJar.resolveSibling(serverJar.getFileName() + ".from");
		// bundler が宣言しているライブラリの一覧。プラグインが libraries/ へ落としたものと
		// 区別が付かなくなるので、glob ではなくこれを使う(理由は ShifuGameProvider)。
		Path list = serverDir.resolve(".shifu").resolve("libraries.list");
		boolean assembled = Files.isRegularFile(serverJar) && Files.isDirectory(librariesDir)
				&& Files.isRegularFile(vanillaJar);

		if (!ownPaperclip.isEmpty()) {
			Path paperclip = localOrDownloaded(downloader, serverDir, ownPaperclip);
			// 組み立て済みの jar が、いま指されている paperclip から出たものかを見る。
			// ファイル名は版が変わっても同じなので、中身のハッシュで見分ける。
			String hash = Downloader.sha256(paperclip);

			extractLibrariesList(paperclip, list);

			if (assembled && hash.equals(readStamp(stamp))) {
				Log.info("Shifu %s is already assembled", minecraftVersion);
				return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
			}

			Log.info("assembling Shifu from %s", paperclip.getFileName());
			runPaperclip(serverDir, paperclip);

			if (!Files.isRegularFile(serverJar)) {
				throw new IOException("paperclip did not produce " + serverJar);
			}

			Files.writeString(stamp, hash);

			return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
		}

		if (assembled && Files.isRegularFile(list)) {
			Log.info("Paper %s is already assembled", minecraftVersion);
			return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
		}

		String url = FILL_API + "/versions/" + minecraftVersion + "/builds/" + build;
		Log.info("resolving Paper build: %s", url);

		Map<String, Object> download = Json.object(Json.parse(downloader.getString(url)),
				"downloads", "server:default");

		String fileName = Json.string(download.get("name"));
		String sha256 = Json.string(Json.object(download, "checksums").get("sha256"));
		Path paperclip = serverDir.resolve("cache").resolve(fileName);

		Log.info("downloading %s", fileName);
		downloader.download(Json.string(download.get("url")), paperclip, sha256);

		extractLibrariesList(paperclip, list);

		if (assembled) {
			Log.info("Paper %s is already assembled", minecraftVersion);
			return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
		}

		Log.info("applying Paper patches (this downloads the official Mojang server jar)");
		runPaperclip(serverDir, paperclip);

		if (!Files.isRegularFile(serverJar)) {
			throw new IOException("paperclip did not produce " + serverJar);
		}

		return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
	}

	/** paperclip が持っている {@code META-INF/libraries.list} を取り出す。 */
	private static void extractLibrariesList(Path paperclip, Path out) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(paperclip.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("META-INF/libraries.list");

			if (entry == null) {
				return;
			}

			Files.createDirectories(out.getParent());

			try (var in = zip.getInputStream(entry)) {
				Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/** 前回どの paperclip から組み立てたか。無ければ null。 */
	private static String readStamp(Path stamp) throws IOException {
		return Files.isRegularFile(stamp) ? Files.readString(stamp).trim() : null;
	}

	/** パスならそのまま、URL なら cache へ落としてから返す。 */
	private static Path localOrDownloaded(Downloader downloader, Path serverDir, String source)
			throws IOException, InterruptedException {
		if (!source.startsWith("http://") && !source.startsWith("https://")) {
			Path local = Path.of(source);

			if (!Files.isRegularFile(local)) {
				throw new IOException("server-paperclip が指すファイルが無い: " + local);
			}

			return local;
		}

		Path target = serverDir.resolve("cache")
				.resolve(source.substring(source.lastIndexOf('/') + 1));
		Log.info("downloading %s", target.getFileName());
		downloader.download(source, target, null);

		return target;
	}

	private static void runPaperclip(Path serverDir, Path paperclip) throws IOException, InterruptedException {
		List<String> command = List.of(
				ServerLaunch.javaBinary(),
				"-Dpaperclip.patchonly=true",
				"-jar", paperclip.toAbsolutePath().toString());

		Process process = new ProcessBuilder(command)
				.directory(serverDir.toFile())
				.inheritIO()
				.start();

		int exit = process.waitFor();

		if (exit != 0) {
			throw new IOException("paperclip exited with " + exit);
		}
	}
}
