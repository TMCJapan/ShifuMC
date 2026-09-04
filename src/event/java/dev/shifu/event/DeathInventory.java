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

    /** 控えた中身を丸ごと戻す。 */
    void restore(final Inventory inventory) {
        for (int i = 0; i < this.items.size(); i++) {
            inventory.getNonEquipmentItems().set(i, this.items.get(i).copy());
        }

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            inventory.equipment.set(slot, this.equipment.getOrDefault(slot, ItemStack.EMPTY).copy());
        }
    }

    /** 残す指定に合う物だけ戻す。合った物は指定から外す。 */
    void restoreKept(final Inventory inventory, final List<org.bukkit.inventory.ItemStack> toKeep) {
        if (toKeep.isEmpty()) {
            return;
        }

        for (int i = 0; i < this.items.size(); i++) {
            if (shouldKeep(toKeep, this.items.get(i))) {
                inventory.getNonEquipmentItems().set(i, this.items.get(i).copy());
            }
        }

        for (Map.Entry<EquipmentSlot, ItemStack> entry : this.equipment.entrySet()) {
            if (shouldKeep(toKeep, entry.getValue())) {
                inventory.equipment.set(entry.getKey(), entry.getValue().copy());
            }
        }
    }

    /** 残す指定に合う物を除いて空にする。vanilla が残した(keepInventory の)持ち物に使う。 */
    void clear(final Inventory inventory, final List<org.bukkit.inventory.ItemStack> toKeep) {
        final NonNullList<ItemStack> live = inventory.getNonEquipmentItems();

        for (int i = 0; i < live.size(); i++) {
            if (!shouldKeep(toKeep, live.get(i))) {
                live.set(i, ItemStack.EMPTY);
            }
        }

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (!shouldKeep(toKeep, inventory.equipment.get(slot))) {
                inventory.equipment.set(slot, ItemStack.EMPTY);
            }
        }
    }

    /** Paper の {@code shouldKeepDeathEventItem} と同じ判定。 */
    private static boolean shouldKeep(final List<org.bukkit.inventory.ItemStack> toKeep, final ItemStack item) {
        if (toKeep.isEmpty() || item.isEmpty()
                || EnchantmentHelper.has(item, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
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
