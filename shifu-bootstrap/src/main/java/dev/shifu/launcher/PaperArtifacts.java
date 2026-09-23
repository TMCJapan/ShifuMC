// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
		Path list = serverDir.resolve(".shifu").resolve("versions").resolve(minecraftVersion)
				.resolve("libraries.list");
		if (!ownPaperclip.isEmpty()) {
			Path paperclip = localOrDownloaded(downloader, serverDir, ownPaperclip);
			requireVersion(paperclip, minecraftVersion);
			// 組み立て済みの jar が、いま指されている paperclip から出たものかを見る。
			// ファイル名は版が変わっても同じなので、中身のハッシュで見分ける。
			String hash = Downloader.sha256(paperclip);
			extractLibrariesList(paperclip, list);

			if (hash.equals(readStamp(stamp))
					&& isAssembled(paperclip, serverJar, librariesDir, vanillaJar, list)) {
				Log.info("Shifu %s is already assembled", minecraftVersion);
				return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
			}

			Log.info("assembling Shifu from %s", paperclip.getFileName());
			runPaperclip(serverDir, paperclip);

			requireAssembled(paperclip, serverJar, librariesDir, vanillaJar, list);

			Files.writeString(stamp, hash);

			return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
		}

		String url = FILL_API + "/versions/" + minecraftVersion + "/builds/" + build;
		Log.info("resolving Paper build: %s", url);

		Map<String, Object> download = Json.object(
				Json.parse(resolveBuild(downloader, serverDir, minecraftVersion, build, url)),
				"downloads", "server:default");

		String fileName = Json.string(download.get("name"));
		String sha256 = Json.string(Json.object(download, "checksums").get("sha256"));
		Path paperclip = serverDir.resolve("cache").resolve(fileName);

		Log.info("downloading %s", fileName);
		downloader.download(Json.string(download.get("url")), paperclip, sha256);
		extractLibrariesList(paperclip, list);

		if (sha256.equalsIgnoreCase(readStamp(stamp))
				&& isAssembled(paperclip, serverJar, librariesDir, vanillaJar, list)) {
			Log.info("Paper %s is already assembled", minecraftVersion);
			return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
		}

		Log.info("applying Paper patches (this downloads the official Mojang server jar)");
		runPaperclip(serverDir, paperclip);

		requireAssembled(paperclip, serverJar, librariesDir, vanillaJar, list);

		Files.writeString(stamp, sha256);

		return new PaperArtifacts(serverJar, librariesDir, vanillaJar, list);
	}

	/**
	 * fill.papermc.io の応答を版と paper-build ごとに控える。
	 * 毎回問い合わせるようにした結果、配布元へ届かないだけで組み立て済みのサーバーが
	 * 起動できなくなっていた。届かないときは前回の応答をそのまま使う
	 * ({@code latest} なら前回 latest が指していたビルドになる)。
	 */
	private static String resolveBuild(Downloader downloader, Path serverDir, String minecraftVersion,
			String build, String url) throws IOException, InterruptedException {
		Path cached = serverDir.resolve(".shifu").resolve("versions").resolve(minecraftVersion)
				.resolve("build-" + build.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
		String body;

		try {
			body = downloader.getString(url);
			// プロキシが 200 で返すエラーページを控えてしまうと、次に届かないときに使えない。
			Json.object(Json.parse(body), "downloads", "server:default");
		} catch (IOException | IllegalArgumentException e) {
			if (!Files.isRegularFile(cached)) {
				throw e instanceof IOException io ? io : new IOException(url + ": " + e.getMessage(), e);
			}

			Log.warn("%s: %s - using the build resolved last time", url, e);
			return Files.readString(cached, StandardCharsets.UTF_8);
		}

		Files.createDirectories(cached.getParent());
		Files.writeString(cached, body, StandardCharsets.UTF_8);

		return body;
	}

	/**
	 * paperclip が対象にしている版と {@code minecraft-version} を突き合わせる。
	 * 版ごとのブランチが出る前のランチャが書いた shifu.properties は 26.2 のままなので、
	 * そのまま進むと 1.20.6 の paperclip を 26.2 として組み立てようとして、
	 * 「paperclip did not produce a complete Paper cache」で落ちていた。
	 */
	private static void requireVersion(Path paperclip, String minecraftVersion) throws IOException {
		String bundled = bundledVersion(paperclip);

		if (bundled != null && !bundled.equals(minecraftVersion)) {
			throw new IOException("server-paperclip (" + paperclip + ") is for Minecraft " + bundled
					+ ", but shifu.properties says minecraft-version = " + minecraftVersion
					+ "; set minecraft-version = " + bundled);
		}
	}

	/** {@code META-INF/versions.list} の 1 行目、2 列目が paperclip の対象の版。 */
	private static String bundledVersion(Path paperclip) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(paperclip.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("META-INF/versions.list");

			if (entry == null) {
				return null;
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					zip.getInputStream(entry), StandardCharsets.UTF_8))) {
				String line = reader.readLine();
				String[] parts = line == null ? new String[0] : line.split("\\t", -1);

				return parts.length >= 2 && !parts[1].isBlank() ? parts[1].trim() : null;
			}
		}
	}

	/** 落ちてきたものが paperclip か。エラーページで正しい jar を潰さないため。 */
	private static boolean isPaperclip(Path file) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
			return zip.getEntry("META-INF/versions.list") != null;
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean isAssembled(Path paperclip, Path serverJar, Path librariesDir, Path vanillaJar, Path list)
			throws IOException {
		if (!Files.isRegularFile(serverJar) || !Files.isDirectory(librariesDir)
				|| !Files.isRegularFile(vanillaJar) || !Files.isRegularFile(list)) {
			return false;
		}

		String serverPath = serverJar.getParent().getFileName() + "/" + serverJar.getFileName();
		String expectedServer = bundledHash(paperclip, "META-INF/versions.list", serverPath);
		String expectedVanilla = bundledHash(paperclip, "META-INF/download-context",
				vanillaJar.getFileName().toString());

		if (expectedServer == null || expectedVanilla == null) {
			Log.warn("paperclip is missing artifact checksums; rebuilding Paper cache");
			return false;
		}
		if (!expectedServer.equalsIgnoreCase(Downloader.sha256(serverJar))) {
			Log.warn("Paper server checksum mismatch; rebuilding: %s", serverJar);
			return false;
		}
		if (!expectedVanilla.equalsIgnoreCase(Downloader.sha256(vanillaJar))) {
			Log.warn("Mojang server checksum mismatch; rebuilding: %s", vanillaJar);
			return false;
		}

		Path root = librariesDir.toAbsolutePath().normalize();
		boolean found = false;

		for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}

			String[] parts = line.split("\\t", -1);

			if (parts.length < 3 || !parts[0].trim().matches("[0-9a-fA-F]{64}")
					|| parts[2].isBlank()) {
				Log.warn("invalid libraries.list entry; rebuilding Paper cache: %s", line);
				return false;
			}

			Path library = root.resolve(parts[2].trim()).normalize();

			if (!library.startsWith(root) || !Files.isRegularFile(library)) {
				Log.warn("Paper library is missing or outside the cache; rebuilding: %s", library);
				return false;
			}
			if (!parts[0].trim().equalsIgnoreCase(Downloader.sha256(library))) {
				Log.warn("Paper library checksum mismatch; rebuilding: %s", library);
				return false;
			}

			found = true;
		}

		return found;
	}

	private static String bundledHash(Path paperclip, String entryName, String expectedPath) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(paperclip.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(entryName);

			if (entry == null) {
				return null;
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					zip.getInputStream(entry), StandardCharsets.UTF_8))) {
				for (String line; (line = reader.readLine()) != null;) {
					String[] parts = line.split("\\t", -1);
					if (parts.length >= 3 && expectedPath.equals(parts[2].trim())) {
						String hash = parts[0].trim();
						return hash.matches("[0-9a-fA-F]{64}") ? hash : null;
					}
				}
			}
		}

		return null;
	}

	private static void requireAssembled(Path paperclip, Path serverJar, Path librariesDir, Path vanillaJar,
			Path list)
			throws IOException {
		if (!isAssembled(paperclip, serverJar, librariesDir, vanillaJar, list)) {
			throw new IOException("paperclip did not produce a complete Paper cache under "
					+ serverJar.getParent().getParent().getParent());
		}
	}

	/** paperclip が持っている {@code META-INF/libraries.list} を取り出す。 */
	private static void extractLibrariesList(Path paperclip, Path out) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(paperclip.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("META-INF/libraries.list");

			if (entry == null) {
				Files.deleteIfExists(out);
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

		// URL の末尾をそのままファイル名にすると、?download=1 のような
		// query が Windows では不正なパスになる。URL ごとの差は Downloader の
		// metadata で判定できるため、管理下の固定名へ保存する。
		Path target = serverDir.resolve("cache").resolve("server-paperclip.jar");
		Log.info("downloading %s", target.getFileName());
		downloader.download(source, target, null, PaperArtifacts::isPaperclip);

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
