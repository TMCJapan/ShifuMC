// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Predicate;

final class Downloader {
	private static final String USER_AGENT = "Shifu/0.1 (+https://github.com/)";

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

	String getString(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(2))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (response.statusCode() != 200) {
			throw new IOException("GET " + url + " returned " + response.statusCode());
		}

		return response.body();
	}

	/**
	 * 既にあって内容が一致するならダウンロードしない。
	 *
	 * @param sha256 期待するハッシュ。null なら HTTP validator で再検証する
	 */
	void download(String url, Path target, String sha256) throws IOException, InterruptedException {
		download(url, target, sha256, null);
	}

	/**
	 * @param accept 取得した中身を受け取ってよいか。sha256 が無いときだけ見る。
	 *               キャプティブポータルやプロキシが 200 でエラーページを返したときに、
	 *               組み立て済みのサーバーの元になっている jar を潰さないため
	 */
	void download(String url, Path target, String sha256, Predicate<Path> accept)
			throws IOException, InterruptedException {
		if (Files.exists(target) && sha256 != null && sha256.equalsIgnoreCase(sha256(target))) {
			return;
		}

		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".part");
		Path metadata = target.resolveSibling(target.getFileName() + ".http");
		Properties cached = sha256 == null && Files.isRegularFile(target)
				? readMetadata(metadata, url) : new Properties();
		if (!cached.isEmpty() && !matchesCachedContent(cached, target)) {
			cached.clear();
		}

		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(15));
		addValidator(request, "If-None-Match", cached.getProperty("etag"));
		addValidator(request, "If-Modified-Since", cached.getProperty("last-modified"));

		Files.deleteIfExists(temp);
		HttpResponse<Path> response;

		try {
			response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofFile(temp));
		} catch (IOException e) {
			// 配布元に届かないだけで、組み立て済みのサーバーが起動できなくなっていた。
			// 検証できないときは手元のものをそのまま使う。
			Files.deleteIfExists(temp);
			fallBackToCache(url, target, sha256, accept, e.toString());
			return;
		}

		if (response.statusCode() == 304 && Files.isRegularFile(target)
				&& matchesCachedContent(cached, target)) {
			Files.deleteIfExists(temp);
			return;
		}

		if (response.statusCode() != 200) {
			Files.deleteIfExists(temp);
			fallBackToCache(url, target, sha256, accept,
					"GET " + url + " returned " + response.statusCode());
			return;
		}

		String downloadedHash = sha256(temp);

		if (sha256 != null && !sha256.equalsIgnoreCase(downloadedHash)) {
			Files.deleteIfExists(temp);
			throw new IOException("checksum mismatch for " + url);
		}

		if (sha256 == null && accept != null && !accept.test(temp)) {
			Files.deleteIfExists(temp);
			fallBackToCache(url, target, sha256, accept,
					"GET " + url + " returned something that is not the expected artifact");
			return;
		}

		if (sha256 == null && Files.isRegularFile(target) && sha256(target).equals(downloadedHash)) {
			Files.delete(temp);
		} else {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}

		if (sha256 == null) {
			writeMetadata(metadata, url,
					response.headers().firstValue("ETag").orElse(""),
					response.headers().firstValue("Last-Modified").orElse(""),
					downloadedHash);
		}
	}

	/** 取り直せなかったときに、手元のものを使ってよいか決める。使えなければ元の失敗を投げる。 */
	private static void fallBackToCache(String url, Path target, String sha256, Predicate<Path> accept,
			String reason) throws IOException {
		if (sha256 == null && Files.isRegularFile(target) && (accept == null || accept.test(target))) {
			Log.warn("%s: %s - using the cached %s", url, reason, target.getFileName());
			return;
		}

		throw new IOException(reason);
	}

	private static boolean matchesCachedContent(Properties cached, Path target) throws IOException {
		String expected = cached.getProperty("sha256");
		return expected != null && !expected.isBlank()
				&& Files.isRegularFile(target)
				&& expected.equalsIgnoreCase(sha256(target));
	}

	private static void addValidator(HttpRequest.Builder request, String name, String value) {
		if (value != null && !value.isBlank()) {
			request.header(name, value);
		}
	}

	private static Properties readMetadata(Path file, String url) throws IOException {
		Properties properties = new Properties();

		if (Files.isRegularFile(file)) {
			try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(in);
			} catch (IOException | IllegalArgumentException e) {
				Log.warn("ignoring invalid HTTP cache metadata %s: %s", file.getFileName(), e.getMessage());
				properties.clear();
			}
		}

		if (!url.equals(properties.getProperty("url"))) {
			properties.clear();
		}

		return properties;
	}

	private static void writeMetadata(Path file, String url, String etag, String lastModified, String sha256)
			throws IOException {
		Properties properties = new Properties();
		properties.setProperty("url", url);
		properties.setProperty("etag", etag);
		properties.setProperty("last-modified", lastModified);
		properties.setProperty("sha256", sha256);

		Path temp = file.resolveSibling(file.getFileName() + ".part");

		try {
			try (var out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				properties.store(out, "Shifu HTTP cache validators");
			}

			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];

			try (var in = Files.newInputStream(file)) {
				for (int read; (read = in.read(buffer)) != -1;) {
					digest.update(buffer, 0, read);
				}
			}

			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
