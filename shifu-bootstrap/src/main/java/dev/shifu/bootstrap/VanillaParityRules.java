// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.shifu.bootstrap.ShifuCompatTransformer.ReturnArgument;
import dev.shifu.bootstrap.ShifuCompatTransformer.Rule;

/**
 * Paper が vanilla から変えている挙動のうち、設定では戻せないものを戻す。
 *
 * <p>{@code -Dshifu.vanillaParity=true} のときだけ適用する。
 * Paper の挙動を望むサーバーもあるので、切れるようにしてある。
 *
 * <p>設定で戻せるものは扱わない(ランチャ側の {@code VanillaParity} が spigot.yml に書く)。
 * ここに入れてよいのは、Paper 26.2 のバイトコードで
 * 「その分岐が実際に vanilla の値を捨てている」ことを確認した項目だけ。
 */
final class VanillaParityRules {
	private VanillaParityRules() {
	}

	static Map<String, List<Rule>> of() {
		Map<String, List<Rule>> map = new LinkedHashMap<>();

		// Spigot はエンティティの追跡距離を players/animals/monsters/misc/display/other の
		// 6 分類に丸めて上書きする。vanilla は EntityType ごとに clientTrackingRange を持つ。
		//
		// TrackingRange.getEntityTrackingRange(entity, defaultRange) の defaultRange が
		// vanilla の値そのもので、Paper はそれを 0 のときしか返さない
		// (0: iload_1 / 1: ifne 6 / 4: iload_1 / 5: ireturn)。
		// 常に defaultRange を返せば vanilla の追跡距離に戻る。
		map.put("org.spigotmc.TrackingRange", List.of(new ReturnArgument(
				"getEntityTrackingRange",
				"(Lnet/minecraft/world/entity/Entity;I)I",
				1,
				"vanilla uses per-EntityType tracking ranges; Spigot overrides them with 6 buckets")));

		return Map.copyOf(map);
	}

	/** 互換ルールと素直に混ぜる(同じクラスに両方の規則が付くことがある)。 */
	static Map<String, List<Rule>> merge(Map<String, List<Rule>> base, Map<String, List<Rule>> extra) {
		Map<String, List<Rule>> merged = new LinkedHashMap<>(base);

		extra.forEach((key, rules) -> merged.merge(key, rules, (a, b) -> {
			List<Rule> combined = new ArrayList<>(a);
			combined.addAll(b);
			return combined;
		}));

		return Map.copyOf(merged);
	}
}
