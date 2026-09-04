// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.shifu.bootstrap.ShifuCompatTransformer.InsertTraceAtHead;
import dev.shifu.bootstrap.ShifuCompatTransformer.Rule;

/**
 * 処理順の差分を取るための計測点。
 *
 * <p>vanilla と候補実装の両方に同じものを当てて、出力を突き合わせる。
 * 計測点は「vanilla と一致していなければならない順序」が現れる場所を選ぶ。
 *
 * <p>{@code -Dshifu.trace=<file>} を付けたときだけ適用する。
 */
final class TraceRules {
	private static final String HOOK = "dev/shifu/bootstrap/ShifuTrace";

	private TraceRules() {
	}

	static Map<String, List<Rule>> of() {
		Map<String, List<Rule>> map = new LinkedHashMap<>();

		// tick の区切り。順序を tick 単位で比較できるようにする。
		map.put("net.minecraft.server.MinecraftServer", List.of(
				new InsertTraceAtHead("tickServer", "(Ljava/util/function/BooleanSupplier;)V", HOOK, "tick")));

		// ブロックの書き換えと更新の伝播。技術検証で最も効く経路。
		map.put("net.minecraft.world.level.Level", List.of(
				new InsertTraceAtHead("setBlock",
						"(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
						HOOK, "setBlock"),
				new InsertTraceAtHead("updateNeighborsAt",
						"(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
						HOOK, "updateNeighborsAt"),
				new InsertTraceAtHead("neighborChanged",
						"(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;)V",
						HOOK, "neighborChanged")));

		// スケジュールされたブロック tick。
		map.put("net.minecraft.server.level.ServerLevel", List.of(
				new InsertTraceAtHead("tickBlock",
						"(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
						HOOK, "tickBlock")));

		return Map.copyOf(map);
	}
}
