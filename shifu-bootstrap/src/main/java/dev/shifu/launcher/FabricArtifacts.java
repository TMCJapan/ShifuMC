// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * fabric-loader とその依存(ASM / sponge-mixin)を取得する。
 *
 * <p>どれを入れるかは meta.fabricmc.net が持っているので、そこから取る。
 * ASM のバージョンをここで決めないのが重要で、
 * fabric-loader は起動時に {@code LoaderUtil.verifyClasspath} でクラスパス上の
 * ASM 重複を検査し、2 つあると起動を止める。
 */
record FabricArtifacts(List<Path> classPath, String mainClass) {
	private static final String META_API = "https://meta.fabricmc.net/v2/versions/loader";
	private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

	static FabricArtifacts obtain(Downloader downloader, Path librariesDir,
			String minecraftVersion, String loaderVersion) throws IOException, InterruptedException {
		String url = META_API + "/" + minecraftVersion + "/" + loaderVersion;
		Log.info("resolving Fabric Loader: %s", url);

		Object root = Json.parse(downloader.getString(url));

		List<Path> classPath = new ArrayList<>();
		classPath.add(fetch(downloader, librariesDir, Json.string(Json.object(root, "loader").get("maven")), FABRIC_MAVEN));

		for (Object library : Json.array(Json.object(root, "launcherMeta", "libraries").get("common"))) {
			Map<String, Object> entry = Json.object(library);
			String repository = entry.get("url") == null ? FABRIC_MAVEN : Json.string(entry.get("url"));
			classPath.add(fetch(downloader, librariesDir, Json.string(entry.get("name")), repository));
		}

		Object serverLibraries = Json.object(root, "launcherMeta", "libraries").get("server");

		if (serverLibraries != null) {
			for (Object library : Json.array(serverLibraries)) {
				Map<String, Object> entry = Json.object(library);
				String repository = entry.get("url") == null ? FABRIC_MAVEN : Json.string(entry.get("url"));
				classPath.add(fetch(downloader, librariesDir, Json.string(entry.get("name")), repository));
			}
		}

		String mainClass = Json.string(Json.object(root, "launcherMeta", "mainClass").get("server"));

		return new FabricArtifacts(classPath, mainClass);
	}

	private static Path fetch(Downloader downloader, Path librariesDir, String maven, String repository)
			throws IOException, InterruptedException {
		String[] parts = maven.split(":");
		String group = parts[0].replace('.', '/');
		String artifact = parts[1];
		String version = parts[2];

		String path = group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
		Path target = librariesDir.resolve(path.replace('/', java.io.File.separatorChar));

		downloader.download(repository + path, target, null);

		return target;
	}
}
