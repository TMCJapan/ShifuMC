// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * <p>{@code ClickType} と {@code InventoryAction} の導き方は Paper 1.20.6 の
 * {@code handleContainerClick} をそのまま写したもの。画面の中身を読むだけで、
 * 何も書き換えない。
 *
 * <p>読んだ位置: Paper-Server 1.20.6
 * {@code src/main/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java}
 * の {@code handleContainerClick}。
 */
final class InventoryClicks {
    /** 予備の手のマス。Paper もこの数を直に書いている。 */
    private static final int SLOT_OFFHAND = 40;

    /** 画面の外を押したときの番号。Paper もこの数を直に書いている。 */
    private static final int SLOT_OUTSIDE = -999;

    private InventoryClicks() {
    }

}
