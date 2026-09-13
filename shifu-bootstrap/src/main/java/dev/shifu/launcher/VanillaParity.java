// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paper が vanilla から変えている挙動のうち、設定で戻せるものを戻す。
 *
 * <p>ここに入れてよいのは「Paper 26.2 のバイトコードで実際に読まれていること」と
 * 「どの値が vanilla 相当か」の両方を確認した項目だけ。
 * 確認していない設定を書くと、直すつもりで別の挙動を壊す。
 *
 * <p>戻せないものはバイトコード側で扱う({@code VanillaParityRules})。
 * どちらでも戻せないものは docs/VANILLA-PARITY.md に列挙する。
 */
final class VanillaParity {
	/**
	 * Spigot は既定でエンティティの活性化範囲を絞り、範囲外のエンティティを tick しない。
	 * 判定はプレイヤーの当たり判定を範囲ぶん膨らませた箱で、上限は無い
	 * ({@code io.papermc.paper.entity.activation.ActivationRange#activateEntities})。
	 * シミュレーション距離の最大は 32 チャンク = 512 ブロックなので、
	 * 512 にすればシミュレーション範囲内の全エンティティが活性 = vanilla と同じになる。
	 *
	 * <p>max-tnt-per-tick は {@code PrimedTnt#tick} が {@code > 0} のときだけ制限するので、
	 * 0 で vanilla と同じ(制限なし)になる。
	 */
	private static final String SPIGOT_YML = """
			# Shifu が vanilla 挙動に合わせるために書いた spigot.yml。
			# 書かなかった項目は Paper が既定値で埋める。
			#
			# ここに書いてあるものは Paper 26.2 のバイトコードで
			#   1. その設定が実際に読まれていること
			#   2. どの値が vanilla 相当か
			# の両方を確認した上で入れてある。詳細は docs/VANILLA-PARITY.md。
			world-settings:
			  default:
			    # vanilla はシミュレーション距離内のエンティティを全て tick する。
			    # 512 ブロック = 最大シミュレーション距離(32 チャンク)。
			    entity-activation-range:
			      animals: 512
			      monsters: 512
			      raiders: 512
			      misc: 512
			      water: 512
			      villagers: 512
			      flying-monsters: 512
			    # vanilla には 1 tick あたりの TNT 数の上限が無い。0 で制限が外れる。
			    max-tnt-per-tick: 0
			""";

	/**
	 * Paper の per-player mob spawns は湧き上限をプレイヤーごとに数える。
	 * vanilla はワールド単位の 1 つの上限なので、湧き効率の計算がまるごと変わる。
	 * 既定が true なので false に戻す。
	 * ({@code NaturalSpawner} / {@code ServerChunkCache} / {@code ChunkMap} で参照されている)
	 */
	private static final String PAPER_WORLD_DEFAULTS_YML = """
			# Shifu が vanilla 挙動に合わせるために書いた paper-world-defaults.yml。
			# 書かなかった項目は Paper が既定値で埋める。詳細は docs/VANILLA-PARITY.md。
			entities:
			  spawning:
			    # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと。
			    per-player-mob-spawns: false
			""";

	private VanillaParity() {
	}

	/**
	 * 1.19 より前の Paper はワールドの設定を {@code paper.yml} の {@code world-settings.default} で読む
	 * ({@code config/paper-world-defaults.yml} は 1.19 で入った)。書く場所を版で分ける。
	 * 1.18.2 では {@code config/} に書いても読まれず、per-player-mob-spawns が true のまま走っていた。
	 */
	private static final String PAPER_YML_LEGACY = """
			# Shifu が vanilla 挙動に合わせるために書いた paper.yml(1.19 より前)。
			# 書かなかった項目は Paper が既定値で埋める。詳細は docs/VANILLA-PARITY.md。
			world-settings:
			  default:
			    # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと。
			    per-player-mob-spawns: false
			""";

	static void apply(Path serverDir, String minecraftVersion) throws IOException {
		write(serverDir.resolve("spigot.yml"), SPIGOT_YML,
				"entity-activation-range.* = 512, max-tnt-per-tick = 0");

		if (before119(minecraftVersion)) {
			write(serverDir.resolve("paper.yml"), PAPER_YML_LEGACY,
					"world-settings.default.per-player-mob-spawns = false");
			return;
		}

		write(serverDir.resolve("config").resolve("paper-world-defaults.yml"), PAPER_WORLD_DEFAULTS_YML,
				"entities.spawning.per-player-mob-spawns = false");
	}

	/** {@code 1.18.2} のような版の文字列が 1.19 より前か。{@code 26.2} のような新しい形は前ではない。 */
	static boolean before119(String version) {
		final String[] parts = version.split("\\.");

		try {
			final int major = Integer.parseInt(parts[0]);
			final int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

			return major == 1 && minor < 19;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static void write(Path file, String content, String summary) throws IOException {
		if (Files.exists(file)) {
			Log.warn("%s already exists - leaving it alone.", file.getFileName());
			Log.warn("  for vanilla behaviour: %s", summary);
			return;
		}

		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Log.info("wrote %s with vanilla-parity settings", file.getFileName());
	}
}
