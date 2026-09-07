// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.craftbukkit.inventory.CraftItemType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.InventoryView;

/**
 * クリックの packet から {@link InventoryClickEvent} を組み立てる。
 *
 * <p>{@code ClickType} と {@code InventoryAction} の導き方は Paper の
 * {@code handleContainerClick} をそのまま写したもの。画面の中身を読むだけで、
 * 何も書き換えない。
 *
 * <p>参照した位置(Paper 26.2):
 * {@code paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch:1989-2274}
 */
final class InventoryClicks {
    private InventoryClicks() {
    }

}
