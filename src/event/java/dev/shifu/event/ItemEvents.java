// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.enchantments.CraftEnchantment;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftInventoryLectern;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.inventory.InventoryView;

/**
 * アイテムとメニュー({@code net.minecraft.world.item} / {@code world.inventory})のイベント。
 * 条件と返り値の向きは {@link ShifuEvents} と同じ。登録が無ければ何も作らず、vanilla を続けてよいと返す。
 *
 * <p>Paper が vanilla のメソッドを作り変えているものは、vanilla の行を残したまま
 * {@code if (listening) { Paper と同じ手順 } else { vanilla の行 }} の形で囲む
 * (投擲物・花火・釣り竿・弓)。登録が無いときに実行される命令列は vanilla と同一。
 * 登録があるときも、通る経路の順番は vanilla に合わせてある。
 */
public final class ItemEvents {
    /** 取り消しを表す番号。ページ・レシピの番号として使われない値。 */
    public static final int CANCELLED = Integer.MIN_VALUE;

    private ItemEvents() {
    }

    /**
     * PlayerItemDamageEvent。耐久の減りが決まったあと、書き込む前。
     *
     * @return 減らす量。取り消されたら 0(vanilla は 0 なら何もしない)。登録が無ければ damage
     */
    public static int itemDamage(final ItemStack stack, final net.minecraft.server.level.ServerPlayer player,
                                 final int damage) {
        if (player == null || !ShifuEvents.listening(org.bukkit.event.player.PlayerItemDamageEvent.getHandlerList())) {
            return damage;
        }

        final org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(
                player.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack), damage);

        return event.callEvent() ? event.getDamage() : 0;
    }

    // ------------------------------------------------------------ InventoryDragEvent

    /**
     * ドラッグの控え。スロット → [置く前の参照, その写し]。登録が無ければ null。
     * 参照を控えるのは、vanilla が置いたスロットを「参照が変わった」ことで見分けるため
     * ({@code Slot.setByPlayer} は新しい {@code ItemStack} を置く)。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/inventory/AbstractContainerMenu.java.patch(InventoryDragEvent)
     */
    private static Map<Slot, ItemStack[]> dragSlots;
    private static ItemStack dragCarried;

    public static void dragBefore(final Set<Slot> slots, final AbstractContainerMenu menu) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.InventoryDragEvent.getHandlerList())) {
            dragSlots = null;
            dragCarried = null;

            return;
        }

        final Map<Slot, ItemStack[]> before = new LinkedHashMap<>();

        for (final Slot slot : slots) {
            before.put(slot, new ItemStack[] {slot.getItem(), slot.getItem().copy()});
        }

        dragSlots = before;
        dragCarried = menu.getCarried().copy();
    }

    /**
     * InventoryDragEvent。vanilla がスロットと手持ちを置いたあと。取り消されたら控えに戻す。
     * 通ったときはカーソルをイベントの値にする(Paper と同じ。プラグインが触っていなければ同じ中身)。
     */
    public static void drag(final AbstractContainerMenu menu, final boolean greedy) {
        final Map<Slot, ItemStack[]> before = dragSlots;
        final ItemStack oldCarried = dragCarried;
        dragSlots = null;
        dragCarried = null;

        if (before == null) {
            return;
        }

        final org.bukkit.inventory.InventoryView view = menu.getBukkitView();
        final Map<Integer, org.bukkit.inventory.ItemStack> items = new HashMap<>();

        for (final Map.Entry<Slot, ItemStack[]> entry : before.entrySet()) {
            final Slot slot = entry.getKey();

            if (slot.getItem() != entry.getValue()[0]) {
                items.put(slot.index, org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(slot.getItem()));
            }
        }

        final org.bukkit.event.inventory.InventoryDragEvent event = new org.bukkit.event.inventory.InventoryDragEvent(
                view,
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(menu.getCarried()),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(oldCarried),
                greedy, items);
        event.callEvent();

        if (event.getResult() != org.bukkit.event.Event.Result.DENY) {
            menu.setCarried(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getCursor()));

            return;
        }

        for (final Map.Entry<Slot, ItemStack[]> entry : before.entrySet()) {
            final Slot slot = entry.getKey();

            if (slot.getItem() != entry.getValue()[0]) {
                slot.set(entry.getValue()[1]);
            }
        }

        menu.setCarried(oldCarried);
    }


    // ------------------------------------------------------------ PrepareResultEvent 系


    // ------------------------------------------------------------ 砥石の経験値

    public static boolean grindstoneExpListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BlockExpEvent.getHandlerList());
    }

    /**
     * BlockExpEvent(砥石)。vanilla の {@code ExperienceOrb.award} を飛ばし、イベントの量で出す。
     * 量の計算(乱数)は vanilla と同じ式を呼ぶ側が 1 回だけ評価する。常に false を返して
     * vanilla の行を飛ばす。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/inventory/GrindstoneMenu.java.patch(BlockExpEvent)
     */
    public static boolean grindstoneExp(final ServerLevel level, final BlockPos pos, final int amount) {
        final org.bukkit.event.block.BlockExpEvent event = new org.bukkit.event.block.BlockExpEvent(CraftBlock.at(level, pos), amount);
        event.callEvent();
        ExperienceOrb.award(level, Vec3.atCenterOf(pos), event.getExpToDrop());

        return false;
    }

    // ------------------------------------------------------------ ビーコン


    public static Holder<net.minecraft.world.effect.MobEffect> effect(final org.bukkit.potion.PotionEffectType type) {
        return type == null ? null : CraftPotionEffectType.bukkitToMinecraftHolder(type);
    }

    // ------------------------------------------------------------ エンチャント台


    private static ItemStack enchantReplacement;

    /**
     * エンチャントする物の差し替え({@code EnchantItemEvent.setItem})。直前の
     * {@link #enchantItem} が置いたものを取り出して、元の物と違えば枠にも入れる。
     * 差し替えが無ければ渡された物をそのまま返す。
     *
     * <p>差し替えた物が本のときは効かない。vanilla の {@code transmuteCopy} は元の物から
     * 付与済みの本を作るので、そこで上書きされる。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/world/inventory/EnchantmentMenu.java.patch(EnchantItemEvent)
     */
    public static ItemStack enchantedItem(final Container slots, final ItemStack original, final ItemStack current) {
        final ItemStack replaced = enchantReplacement;
        enchantReplacement = null;

        if (replaced == null || replaced == current) {
            return current;
        }

        if (replaced != original) {
            slots.setItem(0, replaced);
        }

        return replaced;
    }

    // ------------------------------------------------------------ 書見台


    // ------------------------------------------------------------ 機織り機

    /** 機織り機の選び直し。index が {@link #CANCELLED} なら取り消し。 */
    public record LoomChoice(int index, Holder<BannerPattern> pattern) {
        public boolean cancelled() {
            return this.index == CANCELLED;
        }
    }


    // ------------------------------------------------------------ 石切台


    // ------------------------------------------------------------ BlockCanBuildEvent

    public static boolean canBuildListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BlockCanBuildEvent.getHandlerList());
    }


    // ------------------------------------------------------------ ボート・トロッコ

    /** ボートを置くときの PlayerInteractEvent(RIGHT_CLICK_BLOCK)。Paper と同じくボートを作る前。 */
    public static boolean boatInteract(final Player player, final BlockHitResult hit, final ItemStack stack, final InteractionHand hand) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerInteractEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                hit.getBlockPos(), hit.getDirection(), stack, false, hand, hit.getLocation()).isCancelled();
    }

    /** EntityPlaceEvent。世界に足す前。 */
    public static boolean entityPlace(final Level level, final BlockPos pos, final Direction face, final Player player,
                                      final Entity entity, final InteractionHand hand) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.EntityPlaceEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callEntityPlaceEvent(level, pos, face, player, entity, hand).isCancelled();
    }

    /**
     * addFreshEntity が false を返しうる(スポーン系のイベントに登録がある)か。
     * true のとき、呼ぶ側は Paper と同じく addFreshEntity の返り値を見て PASS で抜ける。
     */
    public static boolean spawnCancelListening() {
        return ShifuEvents.listening(org.bukkit.event.vehicle.VehicleCreateEvent.getHandlerList())
                || ShifuEvents.listening(org.bukkit.event.entity.EntitySpawnEvent.getHandlerList());
    }

    // ------------------------------------------------------------ クロスボウ


    // ------------------------------------------------------------ 染料


    // ------------------------------------------------------------ 投擲物(雪玉・卵・エンダーパール・経験値瓶・投げポーション)

    public static boolean launchListening() {
        return ShifuEvents.listening(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent.getHandlerList());
    }

    /**
     * PlayerLaunchProjectileEvent(花火をブロックに向けて使う)。
     *
     * <p>1.20.6 に {@code Projectile.spawnProjectile} は無いので、vanilla と同じく
     * {@code addFreshEntity} で出す。
     *
     * @return 手元を消費するか。取り消されたら null
     */
    public static Boolean launchFirework(final net.minecraft.world.level.Level level,
                                         final net.minecraft.world.entity.player.Player player,
                                         final net.minecraft.world.InteractionHand hand,
                                         final ItemStack stack, final net.minecraft.world.phys.Vec3 clickLocation,
                                         final Direction direction) {
        final net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                        level, player,
                        clickLocation.x + direction.getStepX() * 0.15,
                        clickLocation.y + direction.getStepY() * 0.15,
                        clickLocation.z + direction.getStepZ() * 0.15,
                        stack);

        if (player == null) {
            level.addFreshEntity(rocket);

            return Boolean.TRUE;
        }

        final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event =
                new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent(
                        (org.bukkit.entity.Player) player.getBukkitEntity(),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack),
                        (org.bukkit.entity.Firework) rocket.getBukkitEntity());

        if (!event.callEvent()) {
            return null;
        }

        level.addFreshEntity(rocket);

        return event.shouldConsume();
    }


    // ------------------------------------------------------------ 花火


    public static boolean elytraBoostListening() {
        return ShifuEvents.listening(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent.getHandlerList());
    }


    // ------------------------------------------------------------ 釣り竿

    public static boolean fishListening() {
        return ShifuEvents.listening(org.bukkit.event.player.PlayerFishEvent.getHandlerList());
    }


    // ------------------------------------------------------------ 弓・クロスボウの発射

    public static boolean shootBowListening() {
        return ShifuEvents.listening(org.bukkit.event.entity.EntityShootBowEvent.getHandlerList());
    }


    // ------------------------------------------------------------ 壊れた入れ物の中身

    public static boolean containerDropListening() {
        return ShifuEvents.listening(org.bukkit.event.entity.EntityDropItemEvent.getHandlerList());
    }


    // ------------------------------------------------------------ リード

    private static InteractionHand leashHand;
    private static Player leashHandPlayer;

    /**
     * 次の {@code LeadItem.bindPlayerMobs} で使った手を置く。vanilla の bindPlayerMobs は手を受け取らない
     * (Paper は引数を足している)。
     */
    public static void leashHand(final Player player, final InteractionHand hand) {
        leashHand = hand;
        leashHandPlayer = player;
    }

    /**
     * 置かれた手。1 回の bindPlayerMobs で結び目と繋ぐ相手(複数)の両方が読むので消さない。
     * 置いた相手と違うプレイヤー(柵を素手で右クリックした経路)なら MAIN_HAND とみなす。
     */
    private static InteractionHand peekLeashHand(final Player player) {
        return leashHand == null || leashHandPlayer != player ? InteractionHand.MAIN_HAND : leashHand;
    }

    /**
     * PlayerLeashEntityEvent(縄で柵に繋ぐ。1 匹ずつ)。
     * 1.20.6 に Leashable は無いので Mob を受ける。
     */
    public static boolean leash(final net.minecraft.world.entity.Mob mob,
                                final net.minecraft.world.entity.Entity holder, final Player player) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.PlayerLeashEntityEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callPlayerLeashEntityEvent(mob, holder, player, peekLeashHand(player)).isCancelled();
    }



    // ------------------------------------------------------------ メイス



    // ------------------------------------------------------------ 名札


    /** 通った PlayerNameEntityEvent の中身を書き込む(vanilla の 2 文の代わり)。 */
    public static void applyName(final io.papermc.paper.event.player.PlayerNameEntityEvent event) {
        final LivingEntity entity = ((org.bukkit.craftbukkit.entity.CraftLivingEntity) event.getEntity()).getHandle();
        entity.setCustomName(event.getName() != null ? PaperAdventure.asVanilla(event.getName()) : null);

        if (event.isPersistent() && entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
    }

    // ------------------------------------------------------------ 道具の耐久


    /**
     * SheepDyeWoolEvent。色を書き込む前。
     *
     * @return 使う色。取り消されたら null。登録が無ければ color
     */
    public static net.minecraft.world.item.DyeColor dyeWool(final net.minecraft.world.entity.animal.Sheep sheep,
                                                            final net.minecraft.world.entity.player.Player player,
                                                            final net.minecraft.world.item.DyeColor color) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.SheepDyeWoolEvent.getHandlerList())) {
            return color;
        }

        final org.bukkit.event.entity.SheepDyeWoolEvent event = new org.bukkit.event.entity.SheepDyeWoolEvent(
                (org.bukkit.entity.Sheep) sheep.getBukkitEntity(),
                org.bukkit.DyeColor.getByWoolData((byte) color.getId()),
                (org.bukkit.entity.Player) player.getBukkitEntity());

        if (!event.callEvent()) {
            return null;
        }

        return net.minecraft.world.item.DyeColor.byId(event.getColor().getWoolData());
    }

    /**
     * PlayerItemBreakEvent。壊れた合図を送る直前。持ち主がプレイヤーのときだけ。
     */
    public static void itemBreak(final net.minecraft.world.entity.LivingEntity entity, final ItemStack stack) {
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)
                || !ShifuEvents.listening(org.bukkit.event.player.PlayerItemBreakEvent.getHandlerList())) {
            return;
        }

        new org.bukkit.event.player.PlayerItemBreakEvent(
                player.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack)).callEvent();
    }


    /**
     * PlayerItemMendEvent。修繕で直す量が決まった直後。
     *
     * @return 直す量。取り消されたら -1
     */
    public static int itemMend(final Player player, final ExperienceOrb orb, final ItemStack item,
                               final net.minecraft.world.entity.EquipmentSlot slot, final int repair) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerItemMendEvent.getHandlerList())) {
            return repair;
        }

        final org.bukkit.event.player.PlayerItemMendEvent event =
                org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemMendEvent(
                        player, orb, item, slot, repair, orb::durabilityToXp);

        return event.isCancelled() ? -1 : event.getRepairAmount();
    }

    /**
     * 落ちている物をプレイヤーが拾うとき。PlayerAttemptPickupItemEvent と、
     * 拾える分があれば PlayerPickupItemEvent と EntityPickupItemEvent。
     *
     * <p>Paper は拾える分だけに数を書き換えてから拾わせる。vanilla の行を
     * 変えずには入らないので、<b>数の分割はしていない。</b>出すのと取り消しだけ。
     *
     * @return 拾ってよいか
     */
    public static boolean playerPickup(final Player player, final ItemEntity item, final ItemStack stack,
                                       final int pickupDelay) {
        if (pickupDelay > 0) {
            return true;
        }

        final int count = stack.getCount();
        final int remaining = count - player.getInventory().canHold(stack);
        final org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.getBukkitEntity();
        final org.bukkit.entity.Item bukkitItem = (org.bukkit.entity.Item) item.getBukkitEntity();

        if (ShifuEvents.listening(org.bukkit.event.player.PlayerAttemptPickupItemEvent.getHandlerList())) {
            final org.bukkit.event.player.PlayerAttemptPickupItemEvent attempt =
                    new org.bukkit.event.player.PlayerAttemptPickupItemEvent(bukkitPlayer, bukkitItem, remaining);

            if (!attempt.callEvent()) {
                if (attempt.getFlyAtPlayer()) {
                    player.take(item, count);
                }

                return false;
            }
        }

        if (remaining >= count) {
            return true;
        }

        if (ShifuEvents.listening(org.bukkit.event.player.PlayerPickupItemEvent.getHandlerList())) {
            final org.bukkit.event.player.PlayerPickupItemEvent legacy =
                    new org.bukkit.event.player.PlayerPickupItemEvent(bukkitPlayer, bukkitItem, remaining);
            legacy.setCancelled(!bukkitPlayer.getCanPickupItems());

            if (!legacy.callEvent()) {
                if (legacy.getFlyAtPlayer()) {
                    player.take(item, count);
                }

                return false;
            }
        }

        if (ShifuEvents.listening(org.bukkit.event.entity.EntityPickupItemEvent.getHandlerList())) {
            final org.bukkit.event.entity.EntityPickupItemEvent modern =
                    new org.bukkit.event.entity.EntityPickupItemEvent(bukkitPlayer, bukkitItem, remaining);
            modern.setCancelled(!bukkitPlayer.getCanPickupItems());

            if (!modern.callEvent()) {
                return false;
            }
        }

        return true;
    }


    /**
     * EntityShootBowEvent。矢を世界に置く直前。
     *
     * <p>Paper は耐久の減りをイベントのあとに移している。vanilla は先に減らすので、
     * <b>取り消しても耐久は戻らない。</b>差し替えた矢({@code setProjectile})も使っていない。
     */
    public static boolean shootBow(final net.minecraft.world.entity.LivingEntity shooter, final ItemStack bow,
                                   final ItemStack arrow, final Projectile projectile,
                                   final InteractionHand hand, final float speed) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.EntityShootBowEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityShootBowEvent(
                shooter, bow, arrow, projectile, hand, speed, true).isCancelled();
    }


    /** EntityLoadCrossbowEvent に登録があるか。 */
    public static boolean loadCrossbowListening() {
        return ShifuEvents.listening(io.papermc.paper.event.entity.EntityLoadCrossbowEvent.getHandlerList());
    }

    /**
     * EntityLoadCrossbowEvent。クロスボウに矢を込める直前。
     *
     * <p>{@code shouldConsumeItem} は vanilla の {@code tryLoadProjectiles} が
     * 常に矢を消すので使っていない。
     */
    public static boolean loadCrossbow(final net.minecraft.world.entity.LivingEntity user, final ItemStack crossbow) {
        return new io.papermc.paper.event.entity.EntityLoadCrossbowEvent(user.getBukkitLivingEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(crossbow),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(user.getUsedItemHand())).callEvent();
    }


    /** TradeSelectEvent。取引を選んだ直前。 */
    public static boolean tradeSelect(final net.minecraft.server.level.ServerPlayer player, final int index,
                                      final net.minecraft.world.inventory.MerchantMenu menu) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.TradeSelectEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callTradeSelectEvent(player, index, menu)
                .isCancelled();
    }

    /** PlayerStonecutterRecipeSelectEvent。石切台のレシピを選んだ直前。 */
    public static boolean stonecutterSelect(final net.minecraft.world.entity.player.Player player,
                                            final net.minecraft.world.inventory.StonecutterMenu menu,
                                            final net.minecraft.world.item.crafting.RecipeHolder<
                                                    net.minecraft.world.item.crafting.StonecutterRecipe> recipe) {
        if (!ShifuEvents.listening(
                io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent.getHandlerList())) {
            return true;
        }

        final boolean allowed = new io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.inventory.StonecutterInventory) menu.getBukkitView().getTopInventory(),
                (org.bukkit.inventory.StonecuttingRecipe) recipe.toBukkitRecipe()).callEvent();

        if (!allowed) {
            player.containerMenu.sendAllDataToRemote();
        }

        return allowed;
    }

    /** PlayerLoomPatternSelectEvent。機織り機の模様を選んだ直前。 */
    public static boolean loomSelect(final net.minecraft.world.entity.player.Player player,
                                     final net.minecraft.world.inventory.LoomMenu menu,
                                     final net.minecraft.core.Holder<
                                             net.minecraft.world.level.block.entity.BannerPattern> pattern) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerLoomPatternSelectEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.banner.PatternType type =
                org.bukkit.craftbukkit.block.banner.CraftPatternType.minecraftHolderToBukkit(pattern);
        final boolean allowed = new io.papermc.paper.event.player.PlayerLoomPatternSelectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.craftbukkit.inventory.CraftInventoryLoom) menu.getBukkitView().getTopInventory(),
                type).callEvent();

        if (!allowed) {
            player.containerMenu.sendAllDataToRemote();
        }

        return allowed;
    }

}
