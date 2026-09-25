// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code shifu.properties}。無ければ既定値で作る。 */
record LauncherConfig(String minecraftVersion, String paperBuild, String loaderVersion,
		String serverPaperclip, boolean vanillaParity, List<String> jvmArgs) {

	private static final String TEMPLATE = """
			# Shifu launcher configuration.

			# 対象の Minecraft バージョン。Paper が対応しているものだけ指定できる。
			minecraft-version = %s

			# Paper のビルド番号。latest でその時点の最新を取る。
			paper-build = latest

			# Shifu 自身のサーバー(paperclip 形式)。パスか URL。
			# 空なら同じフォルダの shifu-server*.jar から minecraft-version の版のものを使う。
			# それも無ければ Paper 公式ビルドをそのまま組み立てる(イベント発火層は入らない)。
			# Windows のパスはそのまま書く(C:\\server\\shifu-server.jar)。
			server-paperclip =

			# fabric-loader のバージョン。
			fabric-loader-version = %s

			# Paper が vanilla から変えている挙動を、設定で戻せる範囲で戻す。
			# 何を戻しているかは docs/VANILLA-PARITY.md を参照。
			vanilla-parity = true

			# サーバー JVM に渡す引数。空白を含む引数は引用符で囲める
			# (jvm-args = -Xmx2G "-javaagent:C:\\Program Files\\agent.jar")。
			jvm-args = -Xmx2G
			""";

	static LauncherConfig load(Path serverDir, String defaultMinecraftVersion, String defaultLoaderVersion)
			throws IOException {
		Path file = serverDir.resolve("shifu.properties");

		if (!Files.exists(file)) {
			Files.writeString(file,
					String.format(TEMPLATE, defaultMinecraftVersion, defaultLoaderVersion),
					StandardCharsets.UTF_8);
			Log.info("wrote %s", file.getFileName());
		}

		Map<String, String> properties = read(file);

		return new LauncherConfig(
				properties.getOrDefault("minecraft-version", defaultMinecraftVersion),
				properties.getOrDefault("paper-build", "latest"),
				properties.getOrDefault("fabric-loader-version", defaultLoaderVersion),
				properties.getOrDefault("server-paperclip", ""),
				Boolean.parseBoolean(properties.getOrDefault("vanilla-parity", "true")),
				parseJvmArgs(properties.getOrDefault("jvm-args", "-Xmx2G")));
	}

	/**
	 * {@code java.util.Properties} は値の中のバックスラッシュをエスケープとして読む。
	 * {@code server-paperclip = C:\temp\x} は {@code C:<TAB>empx} になり、
	 * {@code C:\Users\me} は Malformed encoding の例外で起動が落ちていた。
	 * Windows のパスを見たままに書けるよう、エスケープを解釈しない読み方にする。
	 *
	 * <p>行の形はこれまで書いてきた shifu.properties と同じで、
	 * {@code #} か {@code !} で始まる行は注記、最初の {@code =} か {@code :} までが鍵。
	 * 行をまたぐ値(末尾のバックスラッシュ)は読まない。
	 */
	private static Map<String, String> read(Path file) throws IOException {
		Map<String, String> properties = new LinkedHashMap<>();

		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String trimmed = line.trim();

			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
				continue;
			}

			int separator = -1;

			for (int i = 0; i < trimmed.length() && separator < 0; i++) {
				if (trimmed.charAt(i) == '=' || trimmed.charAt(i) == ':') {
					separator = i;
				}
			}

			if (separator < 0) {
				properties.put(trimmed, "");
			} else {
				properties.put(trimmed.substring(0, separator).trim(),
						trimmed.substring(separator + 1).trim());
			}
		}

		return properties;
	}

	/** 空白、単一/二重引用符、バックスラッシュによる引用を解釈する。 */
	static List<String> parseJvmArgs(String value) throws IOException {
		List<String> args = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;
		boolean started = false;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (quote == 0 && Character.isWhitespace(c)) {
				if (started) {
					args.add(current.toString());
					current.setLength(0);
					started = false;
				}
				continue;
			}

			if ((c == '\'' && quote != '"') || (c == '"' && quote != '\'')) {
				quote = quote == 0 ? c : 0;
				started = true;
				continue;
			}

			if (c == '\\' && quote != '\'') {
				if (i + 1 >= value.length()) {
					throw new IOException("jvm-args ends with an escape character");
				}

				char next = value.charAt(i + 1);
				if (next == '\\' || next == '"' || next == '\'' || Character.isWhitespace(next)) {
					current.append(next);
					i++;
					started = true;
					continue;
				}
			}

			current.append(c);
			started = true;
		}

		if (quote != 0) {
			throw new IOException("jvm-args has an unterminated quote");
		}

		if (started) {
			args.add(current.toString());
		}

		return List.copyOf(args);
	}
}
