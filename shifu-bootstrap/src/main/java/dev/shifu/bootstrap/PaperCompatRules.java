// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.shifu.bootstrap.ShifuCompatTransformer.DropInjector;
import dev.shifu.bootstrap.ShifuCompatTransformer.InsertFabricStartServer;
import dev.shifu.bootstrap.ShifuCompatTransformer.Rule;
import dev.shifu.bootstrap.ShifuCompatTransformer.WrapPluginParent;

/**
 * MOD 側 Mixin の対応表。
 *
 * <p>土台が Paper のサーバー jar だったときは 38 件あった。Paper と moonrise が
 * 作り変えたメンバを Fabric の注入点が踏むためで、うち 20 件は注入を落として
 * 機能ごと捨てていた(ワールド読み込みイベント、チャンクイベント、
 * 一部のエンティティイベント)。
 *
 * <p><b>いまの土台は vanilla の NMS なので、その 38 件は全て要らない。</b>
 * {@code tools/verify_fabric_rules.py} と javap で 1 件ずつ確かめた結果:
 *
 * <table>
 *   <tr><th>規則が前提にしていたこと</th><th>vanilla の実際</th></tr>
 *   <tr><td>createLevels が分割されて消えた</td><td>{@code protected void createLevels()} がある</td></tr>
 *   <tr><td>reloadResources が reloadTagData に移った</td><td>{@code reloadResources} がある</td></tr>
 *   <tr><td>PlayerList.remove が戻り値ありになった</td><td>{@code void remove(ServerPlayer)}</td></tr>
 *   <tr><td>PlayerList.respawn に引数が増えた</td><td>3 引数の版がある</td></tr>
 *   <tr><td>LevelChunk.level が ServerLevel に狭まった</td><td>{@code Level level}</td></tr>
 *   <tr><td>ChunkHolder.oldTicketLevel が消えた</td><td>ある</td></tr>
 *   <tr><td>MinecraftServer の構築子に 2 引数が前置された</td><td>11 引数のまま</td></tr>
 *   <tr><td>PackRepository の構築子に DirectoryValidator が前置された</td><td>可変長引数のまま</td></tr>
 * </table>
 *
 * <p>残すと直るどころか壊れる。{@code reloadResources -> reloadTagData} の書き換えは
 * vanilla にある方を無い方へ向けることになる。
 *
 * <p>MOD 側 Mixin に向けた規則で残っているのは MOD 初期化フックの 1 件だけ。これは Paper の都合ではなく、
 * 「Fabric のエントリポイントはレジストリのブートストラップ後でなければ動かない」
 * という Fabric 側の要件によるもの。
 *
 * <p>ほかに、プラグインのクラスローダの親を差し替える規則がある(MOD の jar のクラスをプラグインから隠す)。
 */
final class PaperCompatRules {
	private PaperCompatRules() {
	}

	static Map<String, List<Rule>> of() {
		Builder b = new Builder();

		// --- MOD 初期化フック ---
		// Fabric の main/server エントリポイントはレジストリのブートストラップ後でなければ動かない。
		// Bootstrap.bootStrap() / Bootstrap.validate() の直後がその位置にあたる。
		//
		// Shifu の NMS は vanilla なので、Main.main の署名は (String[]) のまま。
		// 素の Paper は (OptionSet) に書き換える。server-paperclip を空にすると
		// 素の Paper がそのまま起動するので、どちらの署名でも差し込む。
		// ここを間違えると、サーバーは正常に起動し例外も出ないまま
		// MOD が 1 つも初期化されない(ログに 1 行出るだけ)。
		//
		// 差し込むのは Fabric 標準の Hooks.startServer。自前のフックでも MOD は初期化できるが、
		// **この呼び出しが Main.main の中にあること**を当てにしている MOD がある
		// (owo-lib の MainMixin)。標準と同じものを同じ位置に置く。
		b.add("net.minecraft.server.Main",
				new InsertFabricStartServer("main",
						List.of("([Ljava/lang/String;)V", "(Ljoptsimple/OptionSet;)V"),
						"net/minecraft/server/Bootstrap", "validate"));

		// --- プラグインから MOD のクラスを隠す ---
		// プラグインのクラスローダは親の Knot を先に見るので、MOD とプラグインが同じパッケージを
		// 持つと MOD 側が返る(ModHidingClassLoader)。親を渡す URLClassLoader の構築子はこの 5 か所。
		// 版によって無いクラスは読まれないので、規則も当たらない。
		// 読んだ位置(各版の Paper-API / Paper-Server):
		//   PluginClassLoader の構築子 super(file.getName(), urls, parent)
		//   LibraryLoader.createLoader の new URLClassLoader(urls, getClass().getClassLoader())
		//   PaperSimplePluginClassLoader の構築子 super(name, urls, parentLoader)(1.19.4 以降。PaperPluginClassLoader もここを通る)
		//   PaperClasspathBuilder.buildClassLoader の new URLClassLoader(urls, getClass().getClassLoader())(1.19.4 以降)
		//   BytecodeModifyingURLClassLoader の構築子 super(urls, parent)(1.20.6 以降)
		for (String loader : List.of(
				"org.bukkit.plugin.java.PluginClassLoader",
				"org.bukkit.plugin.java.LibraryLoader",
				"io.papermc.paper.plugin.entrypoint.classloader.PaperSimplePluginClassLoader",
				"io.papermc.paper.plugin.loader.PaperClasspathBuilder",
				"io.papermc.paper.plugin.entrypoint.classloader.BytecodeModifyingURLClassLoader")) {
			b.add(loader, new WrapPluginParent());
		}

		return b.build();
	}

	private static final class Builder {
		private final Map<String, List<Rule>> map = new LinkedHashMap<>();

		void add(String mixinClass, Rule rule) {
			map.computeIfAbsent(mixinClass, k -> new ArrayList<>()).add(rule);
		}

		void drop(String mixinClass, String method, String reason) {
			add(mixinClass, new DropInjector(method, reason));
		}

		Map<String, List<Rule>> build() {
			return Map.copyOf(map);
		}
	}
}
