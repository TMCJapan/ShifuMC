// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

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
			# 空にすると Paper 公式ビルドをそのまま組み立てる(イベント発火層は入らない)。
			server-paperclip =

			# fabric-loader のバージョン。
			fabric-loader-version = %s

			# Paper が vanilla から変えている挙動を、設定で戻せる範囲で戻す。
			# 何を戻しているかは docs/VANILLA-PARITY.md を参照。
			vanilla-parity = true

			# サーバー JVM に渡す引数。
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

		Properties properties = new Properties();

		try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(in);
		}

		String jvmArgs = properties.getProperty("jvm-args", "-Xmx2G").trim();

		return new LauncherConfig(
				properties.getProperty("minecraft-version", defaultMinecraftVersion).trim(),
				properties.getProperty("paper-build", "latest").trim(),
				properties.getProperty("fabric-loader-version", defaultLoaderVersion).trim(),
				properties.getProperty("server-paperclip", "").trim(),
				Boolean.parseBoolean(properties.getProperty("vanilla-parity", "true").trim()),
				jvmArgs.isEmpty() ? List.of() : Arrays.asList(jvmArgs.split("\\s+")));
	}
}
