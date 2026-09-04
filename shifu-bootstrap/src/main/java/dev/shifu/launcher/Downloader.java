// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

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
	 * @param sha256 期待するハッシュ。null なら存在確認だけで済ませる
	 */
	void download(String url, Path target, String sha256) throws IOException, InterruptedException {
		if (Files.exists(target) && (sha256 == null || sha256.equalsIgnoreCase(sha256(target)))) {
			return;
		}

		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".part");

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(15))
				.GET()
				.build();

		HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp));

		if (response.statusCode() != 200) {
			Files.deleteIfExists(temp);
			throw new IOException("GET " + url + " returned " + response.statusCode());
		}

		if (sha256 != null && !sha256.equalsIgnoreCase(sha256(temp))) {
			Files.deleteIfExists(temp);
			throw new IOException("checksum mismatch for " + url);
		}

		Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
