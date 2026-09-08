// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 組み立てたクラスパスで子 JVM を起動する。
 *
 * <p>同一 JVM 内で済ませられないのは、fabric-loader の {@code Knot} が
 * {@code java.class.path} を直接読んでクラスパスを決めるため。
 * {@code URLClassLoader} を作っても Knot からは見えない。
 */
final class ServerLaunch {
	private ServerLaunch() {
	}

	static String javaBinary() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	static int run(Path serverDir, LauncherConfig config, PaperArtifacts paper, FabricArtifacts fabric,
			Namespace namespace, Path shifuJar, List<String> serverArgs) throws IOException, InterruptedException {
		// 起動クラスパスに載せるのは Shifu 自身と fabric-loader 一式だけ。
		// Paper のライブラリは ShifuGameProvider が Knot 側に足す。
		// 両方に載せると、同じ jar が親と Knot の双方から見えてクラスローダが分断される。
		List<String> classPath = new ArrayList<>();
		classPath.add(shifuJar.toAbsolutePath().toString());
		fabric.classPath().forEach(p -> classPath.add(p.toAbsolutePath().toString()));

		List<String> command = new ArrayList<>();
		command.add(javaBinary());
		command.addAll(config.jvmArgs());
		command.add("-Dfabric.skipMcProvider=true");
		command.add("-Dshifu.paperJar=" + paper.serverJar().toAbsolutePath());
		command.add("-Dshifu.librariesDir=" + paper.librariesDir().toAbsolutePath());
		command.add("-Dshifu.vanillaJar=" + paper.vanillaJar().toAbsolutePath());
		command.add("-Dshifu.vanillaParity=" + config.vanillaParity());

		if (namespace != null) {
			command.addAll(namespace.jvmArgs());
		}

		command.add("-cp");
		command.add(String.join(File.pathSeparator, classPath));
		command.add(fabric.mainClass());
		command.addAll(serverArgs);

		Log.info("starting server (%d classpath entries)", classPath.size());

		Process process = new ProcessBuilder(command)
				.directory(serverDir.toFile())
				.inheritIO()
				.start();

		return process.waitFor();
	}

}
