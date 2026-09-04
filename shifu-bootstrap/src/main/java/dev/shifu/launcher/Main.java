// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shifu のエントリポイント。
 *
 * <pre>
 * java -jar shifu.jar [nogui ...]
 * </pre>
 *
 * <p>やること:
 * <ol>
 *   <li>Paper 公式ビルドを取得し、ユーザーのマシン上でサーバー jar を組み立てる</li>
 *   <li>fabric-loader とその依存を取得する</li>
 *   <li>vanilla 挙動に合わせる設定を書く</li>
 *   <li>クラスパスを組んで子 JVM でサーバーを起動する</li>
 * </ol>
 *
 * <p>Paper と Minecraft のバイナリは配布物に含まれず、実行時に公式ソースから取得する。
 */
public final class Main {
	private static final String DEFAULT_MINECRAFT_VERSION = "26.2";
	private static final String DEFAULT_LOADER_VERSION = "0.19.3";

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		Path serverDir = Path.of("").toAbsolutePath();
		LauncherConfig config = LauncherConfig.load(serverDir, DEFAULT_MINECRAFT_VERSION, DEFAULT_LOADER_VERSION);

		Log.info("Minecraft %s / Paper build %s / fabric-loader %s",
				config.minecraftVersion(), config.paperBuild(), config.loaderVersion());

		Downloader downloader = new Downloader();

		PaperArtifacts paper = PaperArtifacts.obtain(downloader, serverDir,
				config.minecraftVersion(), config.paperBuild(), config.serverPaperclip());
		// Paper の libraries/ とは分ける。混ぜると ShifuGameProvider が
		// fabric-loader 自身を Knot 側にも載せてしまい、二重ロードで起動できない。
		FabricArtifacts fabric = FabricArtifacts.obtain(downloader, serverDir.resolve(".shifu").resolve("libraries"),
				config.minecraftVersion(), config.loaderVersion());

		if (config.vanillaParity()) {
			VanillaParity.apply(serverDir);
		} else {
			Log.warn("vanilla-parity is off - Paper's own behaviour changes are left in place");
		}

		int exit = ServerLaunch.run(serverDir, config, paper, fabric, ownJar(), List.of(args));

		if (exit != 0) {
			Log.warn("server exited with %d", exit);
		}

		System.exit(exit);
	}

	/** 子 JVM のクラスパスに載せるため、自分自身の jar を探す。 */
	private static Path ownJar() throws URISyntaxException {
		Path path = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());

		if (!path.getFileName().toString().endsWith(".jar")) {
			throw new IllegalStateException("Shifu must be run from a jar, got: " + path);
		}

		return path;
	}
}
