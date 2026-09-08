// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * 死ぬ前の持ち物の控え。
 *
 * <p>vanilla は {@code dropAllDeathLoot} の中で持ち物を落として空にする。
 * Shifu はその順序を変えないので、{@code PlayerDeathEvent} が発火する時点で
 * 持ち物は既に空になっている。{@code setKeepInventory} や {@code getItemsToKeep} を
 * 効かせるために、落とす前の中身をここに写しておき、イベントのあとで戻す。
 *
 * <p>1.20.6 の {@code Inventory} は持ち物・防具・利き手でない側の 3 つの区画を
 * {@code compartments} で並べている(1.21.11 の {@code equipment} は無い)。
 * 区画ごとにそのまま写す。
 *
 * <p>参照した位置(Paper 26.2):
 * {@code paper-server patches/sources/net/minecraft/server/level/ServerPlayer.java.patch:405,520}
 * ({@code shouldKeepDeathEventItem} と、持ち物を空にする手順)
 */
final class DeathInventory {
    private final List<NonNullList<ItemStack>> compartments = new ArrayList<>();

    DeathInventory(final Inventory inventory) {
        for (NonNullList<ItemStack> source : inventory.compartments) {
            final NonNullList<ItemStack> saved = NonNullList.withSize(source.size(), ItemStack.EMPTY);

            for (int i = 0; i < source.size(); i++) {
                saved.set(i, source.get(i).copy());
            }

            this.compartments.add(saved);
        }
    }

    /** 控えた中身を丸ごと戻す。 */
    void restore(final Inventory inventory) {
        for (int c = 0; c < this.compartments.size(); c++) {
            final NonNullList<ItemStack> saved = this.compartments.get(c);
            final NonNullList<ItemStack> live = inventory.compartments.get(c);

            for (int i = 0; i < saved.size(); i++) {
                live.set(i, saved.get(i).copy());
            }
        }
    }

    /** 残す指定に合う物だけ戻す。合った物は指定から外す。 */
    void restoreKept(final Inventory inventory, final List<org.bukkit.inventory.ItemStack> toKeep) {
        if (toKeep.isEmpty()) {
            return;
        }

        for (int c = 0; c < this.compartments.size(); c++) {
            final NonNullList<ItemStack> saved = this.compartments.get(c);
            final NonNullList<ItemStack> live = inventory.compartments.get(c);

            for (int i = 0; i < saved.size(); i++) {
                if (shouldKeep(toKeep, saved.get(i))) {
                    live.set(i, saved.get(i).copy());
                }
            }
        }
    }

    /** 残す指定に合う物を除いて空にする。vanilla が残した(keepInventory の)持ち物に使う。 */
    void clear(final Inventory inventory, final List<org.bukkit.inventory.ItemStack> toKeep) {
        for (NonNullList<ItemStack> live : inventory.compartments) {
            for (int i = 0; i < live.size(); i++) {
                if (!shouldKeep(toKeep, live.get(i))) {
                    live.set(i, ItemStack.EMPTY);
                }
            }
        }
    }

    /**
     * Paper の {@code shouldKeepDeathEventItem} と同じ判定。
     *
     * <p>1.21.11 は {@code EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP} で見るが、
     * 1.20.6 に効果の成分は無い。同じことを消滅の呪いで見る。
     */
    private static boolean shouldKeep(final List<org.bukkit.inventory.ItemStack> toKeep, final ItemStack item) {
        if (toKeep.isEmpty() || item.isEmpty() || EnchantmentHelper.hasVanishingCurse(item)) {
            return false;
        }

        final org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asCraftMirror(item);
        final Iterator<org.bukkit.inventory.ItemStack> iterator = toKeep.iterator();

        while (iterator.hasNext()) {
            if (bukkit.equals(iterator.next())) {
                iterator.remove();

                return true;
            }
        }

        return false;
    }
}
