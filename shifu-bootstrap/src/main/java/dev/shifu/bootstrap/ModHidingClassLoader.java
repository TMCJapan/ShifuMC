// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;

import net.fabricmc.loader.impl.util.UrlUtil;

/**
 * プラグインのクラスローダの親。Knot に問い合わせ、MOD の jar にしか無いクラスは無かったことにする。
 *
 * <p>Paper のプラグインのクラスローダは親(= Paper のクラスを読んだ Knot)を先に見る
 * (読んだ位置: Paper-API PluginClassLoader.loadClass0 の {@code super.loadClass}、
 * Paper-Server PaperPluginClassLoader.loadClass の {@code super.loadClass})。
 * Knot のクラスパスには MOD の jar も載っているので、MOD とプラグインが同じパッケージを
 * 持っていると MOD 側が返る。1.18.2 で Chunky の MOD 1.2.164 とプラグイン 1.3.146 を入れると、
 * プラグインの {@code ChunkyBukkit} が MOD の {@code org.popcraft.chunky.Chunky} を掴んで
 * {@code NoSuchMethodError: Chunky.getApi()} で落ちた。ChunkyBorder は Chunky プラグインの
 * {@code org.popcraft.chunky.platform.Folia} を探して MOD の jar に行き当たり、
 * ClassNotFoundException で有効化に失敗した。
 *
 * <p>ここで MOD のクラスを隠すと、プラグインのクラスローダは自分の jar、ライブラリ、
 * 他のプラグインの順に探し直す。素の Paper と同じ見え方になる。
 *
 * <p>次のものは隠さない。
 * <ul>
 *   <li>サーバーのパッケージ。MOD が同じ名前で持っていても、Knot が返したものをそのまま返す。</li>
 *   <li>Paper の jar とライブラリ(bundler の libraries.list)から来たクラス、
 *       起動クラスパス(loader / mixin / asm / shifu 自身)から来たクラス。</li>
 *   <li>MOD の jar から来ていても、同じクラスをサーバーの jar も持っているもの。
 *       Knot は MOD の jar をライブラリより先に載せるので、この場合は MOD 側が定義済みになっている。
 *       Ledger は {@code org.sqlite} を同梱していて、Paper のライブラリ sqlite-jdbc より先に見つかる。
 *       隠すと AuthMe が {@code ClassNotFoundException: org.sqlite.JDBC} でサーバーを止めた。</li>
 * </ul>
 */
final class ModHidingClassLoader extends ClassLoader {
	static {
		registerAsParallelCapable();
	}

	/** プラグインに差し替えさせないパッケージ。Paper のプラグインのクラスローダが親を先に見るのと同じ扱いにする。 */
	private static final String[] SERVER_PACKAGES = {
		"java.", "javax.", "jdk.", "sun.",
		"net.minecraft.", "com.mojang.",
		"org.bukkit.", "io.papermc.", "com.destroystokyo.", "org.spigotmc.", "co.aikar.",
		"net.kyori.",
	};

	private final Set<Path> serverCodeSources;
	private final URLClassLoader serverJars;

	ModHidingClassLoader(ClassLoader knot, Set<Path> serverCodeSources, URLClassLoader serverJars) {
		super("shifu-plugin-parent", knot);
		this.serverCodeSources = serverCodeSources;
		this.serverJars = serverJars;
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		ClassLoader knot = getParent();
		Class<?> result = knot.loadClass(name);

		if (result.getClassLoader() == knot && !isServerPackage(name) && !isServerClass(result)
				&& serverJars.findResource(name.replace('.', '/') + ".class") == null) {
			throw new ClassNotFoundException(name);
		}

		return result;
	}

	private static boolean isServerPackage(String name) {
		for (String pkg : SERVER_PACKAGES) {
			if (name.startsWith(pkg)) return true;
		}

		return false;
	}

	/** Knot は jar の実パスを CodeSource にする(fabric-loader@0.19.5 KnotClassDelegate.getMetadata)。 */
	private boolean isServerClass(Class<?> cls) {
		Path source = UrlUtil.getCodeSource(cls);

		return source == null || serverCodeSources.contains(source);
	}
}
