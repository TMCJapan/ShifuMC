// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;

/**
 * Paper のサーバー jar を Fabric Loader のゲーム本体として供給する GameProvider。
 *
 * <p>fabric-loader は無改造のまま使う。標準の MinecraftGameProvider は
 * {@code -Dfabric.skipMcProvider=true} で無効化し、この実装が ServiceLoader 経由で選ばれる。
 *
 * <p>入力:
 * <ul>
 *   <li>{@code -Dshifu.paperJar=<path>} … paperclip が生成した paper-&lt;ver&gt;.jar</li>
 *   <li>{@code -Dshifu.librariesDir=<dir>} … paperclip が展開したライブラリのルート</li>
 * </ul>
 */
public final class ShifuGameProvider implements GameProvider {
	private static final String CRAFTBUKKIT_ENTRYPOINT = "org.bukkit.craftbukkit.Main";
	private static final String VANILLA_ENTRYPOINT = "net.minecraft.server.Main";

	private static final Pattern VERSION_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

	private static final Set<BuiltinTransform> GAME_TRANSFORMS =
			EnumSet.of(BuiltinTransform.WIDEN_ALL_PACKAGE_ACCESS, BuiltinTransform.CLASS_TWEAKS);
	private static final Set<BuiltinTransform> MOD_TRANSFORMS =
			EnumSet.of(BuiltinTransform.STRIP_ENVIRONMENT);

	/** Paper の jar に入っていて「ゲーム側」として扱うパッケージ。 */
	private static final String[] GAME_PACKAGES = {
		"net.minecraft.",
		"org.bukkit.",
		"io.papermc.paper.",
		"com.destroystokyo.paper.",
		"org.spigotmc.",
		"ca.spottedleaf.",
	};

	private final ShifuCompatTransformer transformer = new ShifuCompatTransformer();

	/**
	 * 素の vanilla サーバー jar も同じ経路で起動できるようにする。
	 *
	 * <p>「vanilla と完全一致」を検証するには、vanilla と候補実装に
	 * **同じ計測器**を当てて出力を突き合わせる必要がある。
	 * そのため CraftBukkit が無い jar でも動かせなければならない。
	 */
	private java.util.Map<String, java.util.List<ShifuCompatTransformer.Rule>> rules(boolean craftBukkit) {
		java.util.Map<String, java.util.List<ShifuCompatTransformer.Rule>> result = java.util.Map.of();

		if (craftBukkit) {
			result = Boolean.parseBoolean(System.getProperty("shifu.vanillaParity", "true"))
					? VanillaParityRules.merge(PaperCompatRules.of(), VanillaParityRules.of())
					: PaperCompatRules.of();
		}

		if (ShifuTrace.enabled()) {
			result = VanillaParityRules.merge(result, TraceRules.of());
		}

		return result;
	}

	/**
	 * Paper が持っていても Knot に載せてはいけないライブラリ。
	 * fabric-loader 自身が起動時に app クラスローダで読むため、二重ロードで LinkageError になる。
	 */
	private static final String[] SYSTEM_LIBRARY_MARKERS = {
		"/org/ow2/asm/",
		"/net/fabricmc/sponge-mixin/",
		"/net/fabricmc/fabric-loader/",
	};

	private EnvType envType;
	private Arguments arguments;
	private Path paperJar;
	private final List<Path> gameJars = new ArrayList<>();
	private final List<Path> gameLibraries = new ArrayList<>();
	private String rawVersion;
	private int classVersion = -1;
	private String entrypoint = VANILLA_ENTRYPOINT;

	@Override
	public String getGameId() {
		// Bukkit プラグインからも MOD からも「minecraft」として見える必要がある
		return "minecraft";
	}

	@Override
	public String getGameName() {
		return entrypoint.equals(CRAFTBUKKIT_ENTRYPOINT) ? "Paper (via Shifu)" : "Minecraft (via Shifu)";
	}

	@Override
	public String getRawGameVersion() {
		return rawVersion;
	}

	@Override
	public String getNormalizedGameVersion() {
		return rawVersion;
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName("Minecraft");

		if (classVersion > 0) {
			try {
				metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java",
						Collections.singletonList(String.format(Locale.ENGLISH, ">=%d", classVersion - 44))));
			} catch (VersionParsingException e) {
				throw new RuntimeException(e);
			}
		}

		return Collections.singletonList(new BuiltinMod(new ArrayList<>(gameJars), metadata.build()));
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		return Paths.get(".");
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return false;
	}

	@Override
	public Set<BuiltinTransform> getBuiltinTransforms(String className) {
		for (String pkg : GAME_PACKAGES) {
			if (className.startsWith(pkg)) return GAME_TRANSFORMS;
		}

		return MOD_TRANSFORMS;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		this.envType = launcher.getEnvironmentType();
		this.arguments = new Arguments();
		arguments.parse(args);

		String jarProp = System.getProperty("shifu.paperJar");
		if (jarProp == null) return false;

		paperJar = Paths.get(jarProp).toAbsolutePath().normalize();
		if (!Files.isRegularFile(paperJar)) {
			throw new IllegalStateException("shifu.paperJar does not exist: " + paperJar);
		}

		gameJars.add(paperJar);
		readVersion(paperJar);
		collectLibraries();
		transformer.setRules(rules(entrypoint.equals(CRAFTBUKKIT_ENTRYPOINT)));

		return true;
	}

	/**
	 * paperclip が展開したライブラリを集める。
	 *
	 * <p>paper-api とサーバー実装は同じクラスローダに載せる必要がある。
	 * Paper 内部は {@code META-INF/services} 経由の ServiceLoader を 30 件以上使っており、
	 * インタフェース(paper-api 側)と実装(paper-&lt;ver&gt;.jar 側)が分断されると全滅する。
	 */
	private void collectLibraries() {
		String dirProp = System.getProperty("shifu.librariesDir");
		if (dirProp == null) return;

		Path root = Paths.get(dirProp).toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) return;

		try (Stream<Path> stream = Files.walk(root)) {
			stream.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(ShifuGameProvider::isNotSystemLibrary)
					.forEach(gameLibraries::add);
		} catch (IOException e) {
			throw new RuntimeException("failed to scan " + root, e);
		}
	}

	private static boolean isNotSystemLibrary(Path jar) {
		String path = jar.toString().replace('\\', '/');

		for (String marker : SYSTEM_LIBRARY_MARKERS) {
			if (path.contains(marker)) return false;
		}

		return true;
	}

	/** paper jar 同梱の version.json から MC バージョンを読む。 */
	private void readVersion(Path jar) {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			ZipEntry entry = zf.getEntry("version.json");

			if (entry != null) {
				try (InputStream is = zf.getInputStream(entry)) {
					String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
					Matcher m = VERSION_ID.matcher(json);
					if (m.find()) rawVersion = m.group(1);
				}
			}

			entrypoint = zf.getEntry("org/bukkit/craftbukkit/Main.class") != null
					? CRAFTBUKKIT_ENTRYPOINT
					: VANILLA_ENTRYPOINT;

			ZipEntry serverMain = zf.getEntry("net/minecraft/server/MinecraftServer.class");

			if (serverMain != null) {
				try (InputStream is = zf.getInputStream(serverMain)) {
					byte[] head = is.readNBytes(8);
					if (head.length == 8) classVersion = ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("failed to read " + jar, e);
		}

		if (rawVersion == null) {
			throw new IllegalStateException("could not determine Minecraft version from " + jar);
		}
	}

	/**
	 * サーバーが持っていて、MOD も同じ名前で持っていることがあるパッケージ。
	 *
	 * <p>Fabric は MOD の jar を {@code loader.load()} の中で Knot のクラスパスへ足す
	 * (fabric-loader@0.19.3 FabricLoaderImpl.java:365-378)。これは
	 * {@code provider.unlockClassPath} より前なので(Knot.java:141-152)、
	 * 何もしないと MOD 側の写しが先に見つかる。素の Fabric では game jar に
	 * {@code net.minecraft.*} しか無いので起きないが、Shifu の game jar には Paper と
	 * Bukkit も入っている。VeryManyPlayers は {@code io/papermc/paper/util/MCUtil} を
	 * 同梱しているので、CraftServer の起動が NoSuchMethodError で落ちた。
	 */
	private static final String[] SERVER_PACKAGES = {
		"org.bukkit.", "io.papermc.", "com.destroystokyo.", "org.spigotmc.",
	};

	@Override
	public void initialize(FabricLauncher launcher) {
		// 起動クラスパス(loader / asm / mixin / shifu 自身)は親から見えてよい。
		// Paper 側の jar は unlockClassPath で Knot 側に足すので、ここには含めない。
		launcher.setValidParentClassPath(launcher.getClassPath());

		// Paper と Bukkit のクラスだけ、MOD より先に見えるようにしておく。
		// net.minecraft.* はここではまだ出さない(mixin の用意が済むまで触らせない)。
		for (Path jar : gameJars) {
			launcher.addToClassPath(jar, SERVER_PACKAGES);
		}

		// Paper は 26.1 以降 official 名前空間そのもの。リマップは行わない。
		transformer.setLauncher(launcher);
		transformer.locateEntrypoints(launcher, gameJars);
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return transformer;
	}

	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		// game jar は initialize で足してある。ここでは制限を外すだけ
		// (もう一度 addToClassPath すると同じ URL が二重に載る)。
		for (Path jar : gameJars) {
			launcher.setAllowedPrefixes(jar);
		}

		for (Path lib : gameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		// MOD の main/server エントリポイントはここでは呼ばない。
		// レジストリのブートストラップ後でなければ動かないため、
		// net.minecraft.server.Main.main の Bootstrap.validate() 直後に
		// ShifuHooks.onGameBootstrapped() を差し込んである(PaperCompatRules 参照)。
		Thread.currentThread().setContextClassLoader(loader);

		try {
			Class<?> main = loader.loadClass(entrypoint);
			Method mainMethod = main.getMethod("main", String[].class);
			mainMethod.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			throw FormattedException.ofLocalized("exception.minecraft", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw FormattedException.ofLocalized("exception.minecraft", e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return arguments == null ? new String[0] : arguments.toArray();
	}

	@Override
	public boolean canOpenErrorGui() {
		return false;
	}

	@Override
	public boolean hasAwtSupport() {
		return false;
	}
}
