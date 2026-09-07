// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
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
 * <p>参照した位置(Paper 26.2):
 * {@code paper-server patches/sources/net/minecraft/server/level/ServerPlayer.java.patch:405,520}
 * ({@code shouldKeepDeathEventItem} と、持ち物を空にする手順)
 */
final class DeathInventory {
    private final NonNullList<ItemStack> items;
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);

    DeathInventory(final Inventory inventory) {
        final NonNullList<ItemStack> source = inventory.getNonEquipmentItems();
        this.items = NonNullList.withSize(source.size(), ItemStack.EMPTY);

        for (int i = 0; i < source.size(); i++) {
            this.items.set(i, source.get(i).copy());
        }

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            final ItemStack stack = inventory.equipment.get(slot);

            if (!stack.isEmpty()) {
                this.equipment.put(slot, stack.copy());
            }
        }
    }




}
