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

    /** 発火するイベント。ドラッグと、Paper が弾く番号では null。 */
    static InventoryClickEvent build(final ServerPlayer player, final ServerboundContainerClickPacket packet, final int slotIndex) {
        final AbstractContainerMenu menu = player.containerMenu;

        if (packet.clickType() == net.minecraft.world.inventory.ClickType.QUICK_CRAFT) {
            return null;
        }

        if (slotIndex < -1 && slotIndex != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
            return null;
        }

        final InventoryView view = menu.getBukkitView();
        final SlotType type = view.getSlotType(slotIndex);
        final int button = packet.buttonNum();
        ClickType click = ClickType.UNKNOWN;
        InventoryAction action = InventoryAction.UNKNOWN;

        switch (packet.clickType()) {
            case PICKUP:
                if (button == 0) {
                    click = ClickType.LEFT;
                } else if (button == 1) {
                    click = ClickType.RIGHT;
                }
                if (button == 0 || button == 1) {
                    action = InventoryAction.NOTHING;
                    if (slotIndex == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                        if (!menu.getCarried().isEmpty()) {
                            action = button == 0 ? InventoryAction.DROP_ALL_CURSOR : InventoryAction.DROP_ONE_CURSOR;
                        }
                    } else if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                    } else {
                        Slot slot = menu.getSlot(slotIndex);
                        if (slot != null) {
                            ItemStack clickedItem = slot.getItem();
                            ItemStack cursor = menu.getCarried();
                            if (clickedItem.isEmpty()) {
                                if (!cursor.isEmpty()) {
                                    if (cursor.getItem() instanceof BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && button != 0) {
                                        action = cursor.get(DataComponents.BUNDLE_CONTENTS).isEmpty() ? InventoryAction.NOTHING : InventoryAction.PLACE_FROM_BUNDLE;
                                    } else {
                                        action = button == 0 ? InventoryAction.PLACE_ALL : InventoryAction.PLACE_ONE;
                                    }
                                }
                            } else if (slot.mayPickup(player)) {
                                if (cursor.isEmpty()) {
                                    if (slot.getItem().getItem() instanceof BundleItem && slot.getItem().has(DataComponents.BUNDLE_CONTENTS) && button != 0) {
                                        action = slot.getItem().get(DataComponents.BUNDLE_CONTENTS).isEmpty() ? InventoryAction.NOTHING : InventoryAction.PICKUP_FROM_BUNDLE;
                                    } else {
                                        action = button == 0 ? InventoryAction.PICKUP_ALL : InventoryAction.PICKUP_HALF;
                                    }
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
                                        if (cursor.getItem() instanceof BundleItem && cursor.has(DataComponents.BUNDLE_CONTENTS) && button == 0) {
                                            int toPickup = cursor.get(DataComponents.BUNDLE_CONTENTS).getMaxAmountToAdd(slot.getItem());
                                            if (toPickup >= slot.getItem().getCount()) {
                                                action = InventoryAction.PICKUP_ALL_INTO_BUNDLE;
                                            } else if (toPickup == 0) {
                                                action = InventoryAction.NOTHING;
                                            } else {
                                                action = InventoryAction.PICKUP_SOME_INTO_BUNDLE;
                                            }
                                        } else if (slot.getItem().getItem() instanceof BundleItem && slot.getItem().has(DataComponents.BUNDLE_CONTENTS) && button == 0) {
                                            int toPickup = slot.getItem().get(DataComponents.BUNDLE_CONTENTS).getMaxAmountToAdd(cursor);
                                            if (toPickup >= cursor.getCount()) {
                                                action = InventoryAction.PLACE_ALL_INTO_BUNDLE;
                                            } else if (toPickup == 0) {
                                                action = InventoryAction.NOTHING;
                                            } else {
                                                action = InventoryAction.PLACE_SOME_INTO_BUNDLE;
                                            }
                                        } else {
                                            action = InventoryAction.SWAP_WITH_CURSOR;
                                        }
                                    }
                                } else if (ItemStack.isSameItemSameComponents(cursor, clickedItem)) {
                                    if (clickedItem.getCount() >= 0) {
                                        if (clickedItem.getCount() + cursor.getCount() <= cursor.getMaxStackSize()) {
                                            // 1.5 以降、結果のマスだけ
                                            action = InventoryAction.PICKUP_ALL;
                                        }
                                    }
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
                        Slot slot = menu.getSlot(slotIndex);
                        if (slot != null && slot.mayPickup(player) && slot.hasItem()) {
                            action = InventoryAction.MOVE_TO_OTHER_INVENTORY;
                        } else {
                            action = InventoryAction.NOTHING;
                        }
                    }
                }
                break;
            case SWAP:
                if ((button >= 0 && button < 9) || button == Inventory.SLOT_OFFHAND) {
                    if (slotIndex < 0) {
                        action = InventoryAction.NOTHING;
                        break;
                    }
                    click = (button == Inventory.SLOT_OFFHAND) ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;
                    Slot clickedSlot = menu.getSlot(slotIndex);
                    if (clickedSlot.mayPickup(player)) {
                        ItemStack hotbar = player.getInventory().getItem(button);
                        if ((!hotbar.isEmpty() && clickedSlot.mayPlace(hotbar)) || (hotbar.isEmpty() && clickedSlot.hasItem())) {
                            action = InventoryAction.HOTBAR_SWAP;
                        } else {
                            action = InventoryAction.NOTHING;
                        }
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
                        Slot slot = menu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && player.getAbilities().instabuild && menu.getCarried().isEmpty()) {
                            action = InventoryAction.CLONE_STACK;
                        } else {
                            action = InventoryAction.NOTHING;
                        }
                    }
                } else {
                    click = ClickType.UNKNOWN;
                    action = InventoryAction.UNKNOWN;
                }
                break;
            case THROW:
                if (slotIndex >= 0) {
                    if (button == 0) {
                        click = ClickType.DROP;
                        Slot slot = menu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && slot.mayPickup(player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != Items.AIR) {
                            action = InventoryAction.DROP_ONE_SLOT;
                        } else {
                            action = InventoryAction.NOTHING;
                        }
                    } else if (button == 1) {
                        click = ClickType.CONTROL_DROP;
                        Slot slot = menu.getSlot(slotIndex);
                        if (slot != null && slot.hasItem() && slot.mayPickup(player) && !slot.getItem().isEmpty() && slot.getItem().getItem() != Items.AIR) {
                            action = InventoryAction.DROP_ALL_SLOT;
                        } else {
                            action = InventoryAction.NOTHING;
                        }
                    }
                } else {
                    // 何も持っていないときに来る。Paper と同じ既定
                    click = ClickType.LEFT;
                    if (button == 1) {
                        click = ClickType.RIGHT;
                    }
                    action = InventoryAction.NOTHING;
                }
                break;
            case PICKUP_ALL:
                click = ClickType.DOUBLE_CLICK;
                action = InventoryAction.NOTHING;
                if (slotIndex >= 0 && !menu.getCarried().isEmpty()) {
                    ItemStack cursor = menu.getCarried();
                    action = InventoryAction.NOTHING;
                    if (view.getTopInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))
                            || view.getBottomInventory().contains(CraftItemType.minecraftToBukkit(cursor.getItem()))) {
                        action = InventoryAction.COLLECT_TO_CURSOR;
                    }
                }
                break;
            default:
                break;
        }

        // ドラッグの途中で別の packet が来たら vanilla はドラッグを捨てて何もしない
        if (menu.quickcraftStatus != 0) {
            action = InventoryAction.NOTHING;
        }

        InventoryClickEvent event;

        if (click == ClickType.NUMBER_KEY) {
            event = new InventoryClickEvent(view, type, slotIndex, click, action, button);
        } else {
            event = new InventoryClickEvent(view, type, slotIndex, click, action);
        }

        final org.bukkit.inventory.Inventory top = view.getTopInventory();

        if (slotIndex == 0 && top instanceof org.bukkit.inventory.CraftingInventory craftingInv) {
            org.bukkit.inventory.Recipe recipe = craftingInv.getRecipe();
            if (recipe != null) {
                if (click == ClickType.NUMBER_KEY) {
                    event = new CraftItemEvent(recipe, view, type, slotIndex, click, action, button);
                } else {
                    event = new CraftItemEvent(recipe, view, type, slotIndex, click, action);
                }
            }
        }

        if (slotIndex == 3 && top instanceof org.bukkit.inventory.SmithingInventory smithingInv) {
            org.bukkit.inventory.ItemStack result = smithingInv.getResult();
            if (result != null) {
                if (click == ClickType.NUMBER_KEY) {
                    event = new SmithItemEvent(view, type, slotIndex, click, action, button);
                } else {
                    event = new SmithItemEvent(view, type, slotIndex, click, action);
                }
            }
        }

        if (slotIndex == CartographyTableMenu.RESULT_SLOT && top instanceof org.bukkit.inventory.CartographyInventory cartographyInventory) {
            org.bukkit.inventory.ItemStack result = cartographyInventory.getResult();
            if (result != null && !result.isEmpty()) {
                if (click == ClickType.NUMBER_KEY) {
                    event = new io.papermc.paper.event.player.CartographyItemEvent(view, type, slotIndex, click, action, button);
                } else {
                    event = new io.papermc.paper.event.player.CartographyItemEvent(view, type, slotIndex, click, action);
                }
            }
        }

        return event;
    }
}
