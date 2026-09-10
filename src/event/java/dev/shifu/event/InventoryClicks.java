// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.Slot;
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

    /** 発火するイベント。ドラッグと、Paper が弾く番号では null。 */
    static InventoryClickEvent build(final ServerPlayer player, final ServerboundContainerClickPacket packet,
                                     final int slotIndex) {
        final AbstractContainerMenu menu = player.containerMenu;

        if (packet.getClickType() == net.minecraft.world.inventory.ClickType.QUICK_CRAFT) {
            return null;
        }

        if (slotIndex < -1 && slotIndex != SLOT_OUTSIDE) {
            return null;
        }

        final InventoryView view = menu.getBukkitView();
        final SlotType type = view.getSlotType(slotIndex);
        final int button = packet.getButtonNum();
        ClickType click = ClickType.UNKNOWN;
        InventoryAction action = InventoryAction.UNKNOWN;

        switch (packet.getClickType()) {
            case PICKUP:
                if (button == 0) {
                    click = ClickType.LEFT;
                } else if (button == 1) {
                    click = ClickType.RIGHT;
                }

                if (button == 0 || button == 1) {
                    action = InventoryAction.NOTHING;

                    if (slotIndex == SLOT_OUTSIDE) {
                        if (!menu.getCarried().isEmpty()) {
                            action = button == 0 ? InventoryAction.DROP_ALL_CURSOR : InventoryAction.DROP_ONE_CURSOR;
                        }
                    } else if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                    } else {
                        final Slot slot = menu.getSlot(slotIndex);

                        if (slot != null) {
                            final ItemStack clickedItem = slot.getItem();
                            final ItemStack cursor = menu.getCarried();

                            if (clickedItem.isEmpty()) {
                                if (!cursor.isEmpty()) {
                                    action = button == 0 ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_ONE;
                                }
                            } else if (slot.mayPickup(player)) {
                                if (cursor.isEmpty()) {
                                    action = button == 0 ? InventoryAction.PICKUP_ALL : InventoryAction.PICKUP_HALF;
                                } else if (slot.mayPlace(cursor)) {
                                    if (ItemStack.isSameItemSameComponents(clickedItem, cursor)) {
                                        int toPlace = button == 0 ? cursor.getCount() : 1;
                                        toPlace = Math.min(toPlace, clickedItem.getMaxStackSize() - clickedItem.getCount());
                                        toPlace = Math.min(toPlace, slot.container.getMaxStackSize() - clickedItem.getCount());

                                        if (toPlace == 1) {
                                            action = InventoryAction.PLACE_ONE;
                                        } else if (toPlace == cursor.getCount()) {
                                            action = InventoryAction.PLACE_ALL;
                                        } else if (toPlace < 0) {
                                            action = toPlace != -1 ? InventoryAction.PICKUP_SOME : InventoryAction.PICKUP_ONE;
                                        } else if (toPlace != 0) {
                                            action = InventoryAction.PLACE_SOME;
                                        }
                                    } else if (cursor.getCount() <= slot.getMaxStackSize()) {
                                        action = InventoryAction.SWAP_WITH_CURSOR;
                                    }
                                } else if (ItemStack.isSameItemSameComponents(cursor, clickedItem)
                                        && clickedItem.getCount() >= 0
                                        && clickedItem.getCount() + cursor.getCount() <= cursor.getMaxStackSize()) {
                                    // 1.5 以降、結果のマスだけ
                                    action = InventoryAction.PICKUP_ALL;
                                }
                            }
                        }
                    }
                }
                break;
            case QUICK_MOVE:
                if (button == 0) {
                    click = ClickType.SHIFT_LEFT;
                } else if (button == 1) {
                    click = ClickType.SHIFT_RIGHT;
                }

                if (button == 0 || button == 1) {
                    if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                    } else {
                        final Slot slot = menu.getSlot(slotIndex);
                        action = slot != null && slot.mayPickup(player) && slot.hasItem()
                                ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.NOTHING;
                    }
                }
                break;
            case SWAP:
                if ((button >= 0 && button < 9) || button == SLOT_OFFHAND) {
                    if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                        break;
                    }

                    click = button == SLOT_OFFHAND ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;
                    final Slot clickedSlot = menu.getSlot(slotIndex);

                    if (clickedSlot.mayPickup(player)) {
                        final ItemStack hotbar = player.getInventory().getItem(button);
                        action = (!hotbar.isEmpty() && clickedSlot.mayPlace(hotbar))
                                || (hotbar.isEmpty() && clickedSlot.hasItem())
                                ? InventoryAction.HOTBAR_SWAP : InventoryAction.NOTHING;
                    } else {
                        action = InventoryAction.NOTHING;
                    }
                }
                break;
            case CLONE:
                if (button == 2) {
                    click = ClickType.MIDDLE;

                    if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                    } else {
                        final Slot slot = menu.getSlot(slotIndex);
                        action = slot != null && slot.hasItem() && player.getAbilities().instabuild
                                && menu.getCarried().isEmpty()
                                ? InventoryAction.CLONE_STACK : InventoryAction.NOTHING;
                    }
                } else {
                    click = ClickType.UNKNOWN;
                    action = InventoryAction.UNKNOWN;
                }
                break;
            case THROW:
                if (slotIndex >= 0) {
                    final Slot slot = menu.getSlot(slotIndex);
                    final boolean holds = slot != null && slot.hasItem() && slot.mayPickup(player)
                            && !slot.getItem().isEmpty() && slot.getItem().getItem() != Items.AIR;

                    if (button == 0) {
                        click = ClickType.DROP;
                        action = holds ? InventoryAction.DROP_ONE_SLOT : InventoryAction.NOTHING;
                    } else if (button == 1) {
                        click = ClickType.CONTROL_DROP;
                        action = holds ? InventoryAction.DROP_ALL_SLOT : InventoryAction.NOTHING;
                    }
                } else {
                    // 何も持っていないときに来る。Paper と同じ既定
                    click = button == 1 ? ClickType.RIGHT : ClickType.LEFT;
                    action = InventoryAction.NOTHING;
                }
                break;
            case PICKUP_ALL:
                click = ClickType.DOUBLE_CLICK;
                action = InventoryAction.NOTHING;

                if (slotIndex >= 0 && !menu.getCarried().isEmpty()) {
                    final ItemStack cursor = menu.getCarried();

                    if (view.getTopInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))
                            || view.getBottomInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))) {
                        action = InventoryAction.COLLECT_TO_CURSOR;
                    }
                }
                break;
            default:
                break;
        }

        InventoryClickEvent event = click == ClickType.NUMBER_KEY
                ? new InventoryClickEvent(view, type, slotIndex, click, action, button)
                : new InventoryClickEvent(view, type, slotIndex, click, action);
        final org.bukkit.inventory.Inventory top = view.getTopInventory();

        if (slotIndex == 0 && top instanceof org.bukkit.inventory.CraftingInventory craftingInventory) {
            final org.bukkit.inventory.Recipe recipe = craftingInventory.getRecipe();

            if (recipe != null) {
                event = click == ClickType.NUMBER_KEY
                        ? new CraftItemEvent(recipe, view, type, slotIndex, click, action, button)
                        : new CraftItemEvent(recipe, view, type, slotIndex, click, action);
            }
        }

        if (slotIndex == 3 && top instanceof org.bukkit.inventory.SmithingInventory smithingInventory
                && smithingInventory.getResult() != null) {
            event = click == ClickType.NUMBER_KEY
                    ? new SmithItemEvent(view, type, slotIndex, click, action, button)
                    : new SmithItemEvent(view, type, slotIndex, click, action);
        }

        if (slotIndex == CartographyTableMenu.RESULT_SLOT
                && top instanceof org.bukkit.inventory.CartographyInventory cartographyInventory) {
            final org.bukkit.inventory.ItemStack result = cartographyInventory.getResult();

            if (result != null && !result.isEmpty()) {
                event = click == ClickType.NUMBER_KEY
                        ? new io.papermc.paper.event.player.CartographyItemEvent(view, type, slotIndex, click, action, button)
                        : new io.papermc.paper.event.player.CartographyItemEvent(view, type, slotIndex, click, action);
            }
        }

        return event;
    }
}
