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
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
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
import net.minecraft.world.item.crafting.SelectableRecipe;
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

    // ------------------------------------------------------------ InventoryDragEvent

    /**
     * ドラッグの控え。スロット → [置く前の参照, その写し]。登録が無ければ null。
     * 参照を控えるのは、vanilla が置いたスロットを「参照が変わった」ことで見分けるため
     * ({@code Slot.setByPlayer} は新しい {@code ItemStack} を置く)。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/inventory/AbstractContainerMenu.java.patch(InventoryDragEvent)
     */
    public static Map<Slot, ItemStack[]> dragBefore(final Set<Slot> slots) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.InventoryDragEvent.getHandlerList())) {
            return null;
        }

        final Map<Slot, ItemStack[]> before = new LinkedHashMap<>();

        for (final Slot slot : slots) {
            before.put(slot, new ItemStack[] {slot.getItem(), slot.getItem().copy()});
        }

        return before;
    }

    /**
     * InventoryDragEvent。vanilla がスロットと手持ちを置いたあと。取り消されたら控えに戻す。
     * 通ったときはカーソルをイベントの値にする(Paper と同じ。プラグインが触っていなければ同じ中身)。
     */
    public static void drag(final AbstractContainerMenu menu, final Map<Slot, ItemStack[]> before,
                            final ItemStack oldCarried, final boolean greedy) {
        if (before == null) {
            return;
        }

        final InventoryView view = menu.getBukkitView();
        final Map<Integer, org.bukkit.inventory.ItemStack> items = new HashMap<>();

        for (final Map.Entry<Slot, ItemStack[]> entry : before.entrySet()) {
            final Slot slot = entry.getKey();

            if (slot.getItem() != entry.getValue()[0]) {
                items.put(slot.index, CraftItemStack.asBukkitCopy(slot.getItem()));
            }
        }

        final org.bukkit.event.inventory.InventoryDragEvent event = new org.bukkit.event.inventory.InventoryDragEvent(
                view, CraftItemStack.asCraftMirror(menu.getCarried()), CraftItemStack.asBukkitCopy(oldCarried), greedy, items);
        event.callEvent();

        if (event.getResult() != Event.Result.DENY) {
            menu.setCarried(CraftItemStack.asNMSCopy(event.getCursor()));
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

    /**
     * PrepareAnvilEvent / PrepareGrindstoneEvent / PrepareSmithingEvent / PrepareResultEvent。
     * vanilla が結果スロットを埋めたあと(Paper の {@code callPrepareResultEvent} と同じ位置)。
     * イベントの結果を結果スロットに置き直し、Paper と同じく変更を送る。
     * どのイベントも {@code PrepareInventoryResultEvent} の HandlerList を共有している。
     *
     * <p>Paper が {@code createResult} の中で setItem を {@code callPrepareAnvilEvent} などに
     * 置き換えている箇所は、setItem と同じ働きしかしない(発火はここ)ので触らない。
     *
     * <p>読んだ位置: paper-server src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java callPrepareResultEvent
     */
    public static void prepareResult(final AbstractContainerMenu menu, final int resultSlot) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.PrepareInventoryResultEvent.getHandlerList())) {
            return;
        }

        final InventoryView view = menu.getBukkitView();
        final org.bukkit.inventory.ItemStack original = view.getTopInventory().getItem(resultSlot);
        final CraftItemStack result = original != null ? CraftItemStack.asCraftCopy(original) : null;
        final com.destroystokyo.paper.event.inventory.PrepareResultEvent event;

        if (menu instanceof AnvilMenu && view instanceof org.bukkit.inventory.view.AnvilView anvilView) {
            event = new org.bukkit.event.inventory.PrepareAnvilEvent(anvilView, result);
        } else if (menu instanceof GrindstoneMenu) {
            event = new org.bukkit.event.inventory.PrepareGrindstoneEvent(view, result);
        } else if (menu instanceof SmithingMenu) {
            event = new org.bukkit.event.inventory.PrepareSmithingEvent(view, result);
        } else {
            event = new com.destroystokyo.paper.event.inventory.PrepareResultEvent(view, result);
        }

        event.callEvent();
        event.getInventory().setItem(resultSlot, event.getResult());
        menu.broadcastChanges();
    }

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

    /**
     * PlayerChangeBeaconEffectEvent。効果を書き込む前。取り消しと効果の差し替えは呼ぶ側が行う
     * (vanilla の局所変数 primaryEffect / secondaryEffect に代入する)。登録が無ければ null。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/inventory/BeaconMenu.java.patch
     */
    public static io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent changeBeaconEffect(
            final Player player, final ContainerLevelAccess access,
            final Holder<net.minecraft.world.effect.MobEffect> primary, final Holder<net.minecraft.world.effect.MobEffect> secondary) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent.getHandlerList())) {
            return null;
        }

        final org.bukkit.block.Block block = access.evaluate((level, pos) -> CraftBlock.at(level, pos)).orElse(null);
        final io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent event = new io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                primary == null ? null : CraftPotionEffectType.minecraftHolderToBukkit(primary),
                secondary == null ? null : CraftPotionEffectType.minecraftHolderToBukkit(secondary),
                block);
        event.callEvent();

        return event;
    }

    public static Holder<net.minecraft.world.effect.MobEffect> effect(final org.bukkit.potion.PotionEffectType type) {
        return type == null ? null : CraftPotionEffectType.bukkitToMinecraftHolder(type);
    }

    // ------------------------------------------------------------ エンチャント台

    /**
     * EnchantItemEvent。経験値を払う前(Paper と同じ位置)。
     *
     * @return 付けるエンチャントの並び。取り消し(経験値不足、空、手掛かり無し)なら null。
     *         登録が無ければ渡された並びそのもの
     */
    public static List<EnchantmentInstance> enchantItem(final AbstractContainerMenu menu, final Level level, final BlockPos pos,
                                                        final Player player, final ItemStack item,
                                                        final List<EnchantmentInstance> enchantments, final int buttonId,
                                                        final int[] costs, final int[] enchantClue, final int[] levelClue) {
        if (!ShifuEvents.listening(org.bukkit.event.enchantment.EnchantItemEvent.getHandlerList())) {
            return enchantments;
        }

        final IdMap<Holder<Enchantment>> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
        final Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new HashMap<>();

        for (final EnchantmentInstance instance : enchantments) {
            enchants.put(CraftEnchantment.minecraftHolderToBukkit(instance.enchantment()), instance.level());
        }

        final Holder<Enchantment> holder = registry.byId(enchantClue[buttonId]);

        if (holder == null) {
            return null;
        }

        final CraftItemStack craftItem = CraftItemStack.asCraftMirror(item);
        final org.bukkit.event.enchantment.EnchantItemEvent event = new org.bukkit.event.enchantment.EnchantItemEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), menu.getBukkitView(), CraftBlock.at(level, pos),
                craftItem, costs[buttonId], enchants,
                CraftEnchantment.minecraftHolderToBukkit(holder), levelClue[buttonId], buttonId);
        enchantReplacement = null;
        event.callEvent();
        final int itemLevel = event.getExpLevelCost();

        if (event.isCancelled() || (itemLevel > player.experienceLevel && !player.getAbilities().instabuild)
                || event.getEnchantsToAdd().isEmpty()) {
            return null;
        }


        final List<EnchantmentInstance> out = new ArrayList<>();

        for (final Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : event.getEnchantsToAdd().entrySet()) {
            final Holder<Enchantment> enchantment = CraftEnchantment.bukkitToMinecraftHolder(entry.getKey());

            if (enchantment != null) {
                out.add(new EnchantmentInstance(enchantment, entry.getValue()));
            }
        }

        if (out.isEmpty()) {
            return null;
        }

        enchantReplacement = CraftItemStack.getOrCloneOnMutation(craftItem, event.getItem());

        return out;
    }

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

    /**
     * PlayerLecternPageChangeEvent。ページを書き込む前。
     *
     * @return 新しいページ。取り消されたら {@link #CANCELLED}。登録が無ければ next
     */
    public static int lecternPage(final AbstractContainerMenu menu, final Player player,
                                  final io.papermc.paper.event.player.PlayerLecternPageChangeEvent.PageChangeDirection direction,
                                  final int current, final int next) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerLecternPageChangeEvent.getHandlerList())) {
            return next;
        }

        final CraftInventoryLectern inventory = (CraftInventoryLectern) menu.getBukkitView().getTopInventory();
        final io.papermc.paper.event.player.PlayerLecternPageChangeEvent event = new io.papermc.paper.event.player.PlayerLecternPageChangeEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), inventory.getHolder(), inventory.getBook(), direction, current, next);

        return event.callEvent() ? event.getNewPage() : CANCELLED;
    }

    // ------------------------------------------------------------ 機織り機

    /** 機織り機の選び直し。index が {@link #CANCELLED} なら取り消し。 */
    public record LoomChoice(int index, Holder<BannerPattern> pattern) {
        public boolean cancelled() {
            return this.index == CANCELLED;
        }
    }

    /**
     * PlayerLoomPatternSelectEvent。模様を書き込む前。
     *
     * @return null なら vanilla のまま進める(登録が無い、または模様が変わっていない)。
     *         それ以外は呼ぶ側が index と pattern を置く(一覧に無い模様は index が -1)
     */
    public static LoomChoice loomPattern(final AbstractContainerMenu menu, final Player player,
                                        final List<Holder<BannerPattern>> patterns, final int buttonId) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerLoomPatternSelectEvent.getHandlerList())) {
            return null;
        }

        final io.papermc.paper.event.player.PlayerLoomPatternSelectEvent event = new io.papermc.paper.event.player.PlayerLoomPatternSelectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.inventory.LoomInventory) menu.getBukkitView().getTopInventory(),
                org.bukkit.craftbukkit.block.banner.CraftPatternType.minecraftHolderToBukkit(patterns.get(buttonId)));

        if (!event.callEvent()) {
            return new LoomChoice(CANCELLED, null);
        }

        final Holder<BannerPattern> chosen = org.bukkit.craftbukkit.block.banner.CraftPatternType.bukkitToMinecraftHolder(event.getPatternType());

        for (int i = 0; i < patterns.size(); i++) {
            if (chosen.equals(patterns.get(i))) {
                return i == buttonId ? null : new LoomChoice(i, patterns.get(i));
            }
        }

        return new LoomChoice(-1, chosen);
    }

    // ------------------------------------------------------------ 石切台

    /**
     * PlayerStonecutterRecipeSelectEvent。vanilla が番号を書き込んだあと、結果を置く前(Paper と同じ)。
     *
     * @return 使うレシピの番号。取り消されたら {@link #CANCELLED}。登録が無い・変わらないなら buttonId
     */
    public static int stonecutterRecipe(final AbstractContainerMenu menu, final Player player,
                                        final SelectableRecipe.SingleInputSet<StonecutterRecipe> recipes, final int buttonId) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent.getHandlerList())) {
            return buttonId;
        }

        final Optional<RecipeHolder<StonecutterRecipe>> recipe = recipes.entries().get(buttonId).recipe().recipe();

        if (recipe.isEmpty()) {
            return buttonId;
        }

        final io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent event = new io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.inventory.StonecutterInventory) menu.getBukkitView().getTopInventory(),
                (org.bukkit.inventory.StonecuttingRecipe) recipe.get().toBukkitRecipe());

        if (!event.callEvent()) {
            return CANCELLED;
        }

        final net.minecraft.resources.Identifier key = CraftNamespacedKey.toMinecraft(event.getStonecuttingRecipe().getKey());

        if (recipe.get().id().identifier().equals(key)) {
            return buttonId;
        }

        for (int i = 0; i < recipes.entries().size(); i++) {
            if (recipes.entries().get(i).recipe().recipe().filter(r -> r.id().identifier().equals(key)).isPresent()) {
                return i;
            }
        }

        return buttonId;
    }

    // ------------------------------------------------------------ BlockCanBuildEvent

    public static boolean canBuildListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BlockCanBuildEvent.getHandlerList());
    }

    /**
     * BlockCanBuildEvent。vanilla の判定の式は呼ぶ側が同じ形で評価して渡す。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/item/BlockItem.java.patch、StandingAndWallBlockItem.java.patch
     */
    public static boolean canBuild(final Level level, final BlockPos pos, final Player player, final BlockState state,
                                   final boolean vanilla, final InteractionHand hand) {
        final org.bukkit.event.block.BlockCanBuildEvent event = new org.bukkit.event.block.BlockCanBuildEvent(
                CraftBlock.at(level, pos),
                player instanceof ServerPlayer serverPlayer ? serverPlayer.getBukkitEntity() : null,
                org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(state), vanilla, CraftEquipmentSlot.getHand(hand));
        event.callEvent();

        return event.isBuildable();
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

    /**
     * EntityLoadCrossbowEvent。装填する前。
     *
     * <p>効かないもの: {@code setConsumeItem(false)}(vanilla の tryLoadProjectiles は必ず消費する)。
     */
    public static boolean loadCrossbow(final LivingEntity entity, final ItemStack stack) {
        if (!ShifuEvents.listening(io.papermc.paper.event.entity.EntityLoadCrossbowEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.EntityLoadCrossbowEvent(
                (org.bukkit.entity.LivingEntity) entity.getBukkitEntity(), CraftItemStack.asCraftMirror(stack),
                CraftEquipmentSlot.getHand(entity.getUsedItemHand())).callEvent();
    }

    // ------------------------------------------------------------ 染料

    /**
     * SheepDyeWoolEvent。色を書き込む前。
     *
     * @return 使う色。取り消されたら null。登録が無ければ color
     */
    public static DyeColor dyeWool(final Sheep sheep, final Player player, final DyeColor color) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.SheepDyeWoolEvent.getHandlerList())) {
            return color;
        }

        final org.bukkit.event.entity.SheepDyeWoolEvent event = new org.bukkit.event.entity.SheepDyeWoolEvent(
                (org.bukkit.entity.Sheep) sheep.getBukkitEntity(), org.bukkit.DyeColor.getByWoolData((byte) color.getId()),
                (org.bukkit.entity.Player) player.getBukkitEntity());

        if (!event.callEvent()) {
            return null;
        }

        return DyeColor.byId((byte) event.getColor().getWoolData());
    }

    // ------------------------------------------------------------ 投擲物(雪玉・卵・エンダーパール・経験値瓶・投げポーション)

    public static boolean launchListening() {
        return ShifuEvents.listening(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent.getHandlerList());
    }

    /**
     * PlayerLaunchProjectileEvent。登録があるとき、vanilla の「音 → 生成と発射 → 統計 → 消費」を
     * ここで同じ順に行い、生成と発火だけを音の前に置く(Paper と同じく、取り消されたら音も出ない)。
     * 生成は {@code Projectile.spawnProjectileFromRotation} と同じ手順。
     *
     * @param sound 音。無ければ null(投げポーション)
     * @return 続けてよいか。取り消されたら手元を送り直して false(呼ぶ側は FAIL を返す)
     */
    public static <T extends Projectile> boolean launch(final Projectile.ProjectileFactory<T> creator, final ServerLevel level,
                                                        final ItemStack stack, final Player player, final InteractionHand hand,
                                                        final float yOffset, final float pow, final float uncertainty,
                                                        final SoundEvent sound, final SoundSource source, final Item item) {
        final T projectile = creator.create(level, player, stack);
        projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), yOffset, pow, uncertainty);
        final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), CraftItemStack.asCraftMirror(stack),
                (org.bukkit.entity.Projectile) projectile.getBukkitEntity());

        if (!event.callEvent()) {
            if (projectile instanceof ThrownEnderpearl pearl && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.deregisterEnderPearl(pearl);
                serverPlayer.connection.send(new ClientboundCooldownPacket(player.getCooldowns().getCooldownGroup(stack), 0));
            }

            player.containerMenu.forceHeldSlot(hand);

            return false;
        }

        if (sound != null) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, source, 0.5F,
                    0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        }

        level.addFreshEntity(projectile);
        projectile.applyOnProjectileSpawned(level, stack);
        player.awardStat(Stats.ITEM_USED.get(item));

        if (event.shouldConsume()) {
            stack.consume(1, player);
        } else {
            player.containerMenu.forceHeldSlot(hand);
        }

        return true;
    }

    // ------------------------------------------------------------ 花火

    /**
     * PlayerLaunchProjectileEvent(ブロックに向けて使った花火)。
     *
     * @return null なら取り消し(PASS)。true なら消費する。false なら消費しない
     */
    public static Boolean launchFirework(final ServerLevel level, final Player player, final InteractionHand hand,
                                         final ItemStack stack, final Vec3 clickLocation, final Direction direction) {
        final FireworkRocketEntity rocket = new FireworkRocketEntity(
                level, player,
                clickLocation.x + direction.getStepX() * 0.15,
                clickLocation.y + direction.getStepY() * 0.15,
                clickLocation.z + direction.getStepZ() * 0.15,
                stack);

        if (player == null) {
            Projectile.spawnProjectile(rocket, level, stack);

            return Boolean.TRUE;
        }

        final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), CraftItemStack.asCraftMirror(stack),
                (org.bukkit.entity.Firework) rocket.getBukkitEntity());

        if (!event.callEvent()) {
            return null;
        }

        Projectile.spawnProjectile(rocket, level, stack);

        return event.shouldConsume();
    }

    public static boolean elytraBoostListening() {
        return ShifuEvents.listening(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent.getHandlerList());
    }

    /**
     * PlayerElytraBoostEvent。登録があるとき、vanilla の「リード切り → 生成と発射 → 消費 → 統計」を
     * ここで同じ順に行い、生成と発火だけを先に置く。取り消されたら手元を送り直すだけ。
     */
    public static void elytraBoost(final Level level, final ServerLevel serverLevel, final Player player,
                                   final InteractionHand hand, final ItemStack stack, final Item item) {
        final FireworkRocketEntity rocket = new FireworkRocketEntity(level, stack, player);
        final com.destroystokyo.paper.event.player.PlayerElytraBoostEvent event = new com.destroystokyo.paper.event.player.PlayerElytraBoostEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), CraftItemStack.asCraftMirror(stack),
                (org.bukkit.entity.Firework) rocket.getBukkitEntity(), CraftEquipmentSlot.getHand(hand));

        if (!event.callEvent()) {
            player.containerMenu.forceHeldSlot(hand);

            return;
        }

        if (player.dropAllLeashConnections(null)) {
            level.playSound(null, player, net.minecraft.sounds.SoundEvents.LEAD_BREAK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }

        Projectile.spawnProjectile(rocket, serverLevel, stack);

        if (event.shouldConsume()) {
            stack.consume(1, player);
        } else {
            player.containerMenu.forceHeldSlot(hand);
        }

        player.awardStat(Stats.ITEM_USED.get(item));
    }

    // ------------------------------------------------------------ 釣り竿

    public static boolean fishListening() {
        return ShifuEvents.listening(org.bukkit.event.player.PlayerFishEvent.getHandlerList());
    }

    /**
     * PlayerFishEvent(FISHING)。登録があるとき、浮きを作ってから発火し、通れば vanilla と同じく
     * 音を鳴らして世界に足す。取り消されたら {@code player.fishing} を外す(浮きの構築子が付けている)。
     */
    public static boolean fish(final Level level, final ServerLevel serverLevel, final Player player, final InteractionHand hand,
                               final ItemStack stack, final int luck, final int lureSpeed) {
        final FishingHook hook = new FishingHook(player, level, luck, lureSpeed);
        final org.bukkit.event.player.PlayerFishEvent event = new org.bukkit.event.player.PlayerFishEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), null, (org.bukkit.entity.FishHook) hook.getBukkitEntity(),
                CraftEquipmentSlot.getHand(hand), org.bukkit.event.player.PlayerFishEvent.State.FISHING);

        if (!event.callEvent()) {
            player.fishing = null;

            return false;
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(), net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL, 0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        Projectile.spawnProjectile(hook, serverLevel, stack);

        return true;
    }

    // ------------------------------------------------------------ 弓・クロスボウの発射

    public static boolean shootBowListening() {
        return ShifuEvents.listening(org.bukkit.event.entity.EntityShootBowEvent.getHandlerList());
    }

    /**
     * EntityShootBowEvent。矢を作って発射方向を付けたあと、世界に足す前(呼ぶ側が作る。
     * createProjectile / shootProjectile は protected なので中でしか呼べない)。
     *
     * @return 続けてよいか(耐久を減らす)。取り消し、または足したあと消えていたら false
     */
    public static boolean shootBow(final ServerLevel level, final LivingEntity shooter, final ItemStack weapon, final ItemStack ammo,
                                   final Projectile projectile, final InteractionHand hand, final float power) {
        final org.bukkit.event.entity.EntityShootBowEvent event = CraftEventFactory.callEntityShootBowEvent(
                shooter, weapon, ammo, projectile, hand, power, true);

        if (event.isCancelled()) {
            event.getProjectile().remove();

            return false;
        }

        if (event.getProjectile() == projectile.getBukkitEntity()) {
            return !Projectile.spawnProjectile(projectile, level, ammo).isRemoved();
        }

        return true;
    }

    // ------------------------------------------------------------ 壊れた入れ物の中身

    public static boolean containerDropListening() {
        return ShifuEvents.listening(org.bukkit.event.entity.EntityDropItemEvent.getHandlerList());
    }

    /** EntityDropItemEvent。入れ物(アイテムエンティティ)が壊れて中身を出すとき、1 つずつ。 */
    public static void containerDrop(final ItemEntity container, final Level level, final ItemStack stack) {
        final ItemEntity dropped = new ItemEntity(level, container.getX(), container.getY(), container.getZ(), stack);
        final org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(
                container.getBukkitEntity(), (org.bukkit.entity.Item) dropped.getBukkitEntity());

        if (event.callEvent()) {
            level.addFreshEntity(dropped);
        }
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

    /** HangingPlaceEvent。柵に新しく結び目を作るとき。 */
    public static boolean knotPlace(final LeashFenceKnotEntity knot, final Player player, final Level level, final BlockPos pos) {
        if (!ShifuEvents.listening(org.bukkit.event.hanging.HangingPlaceEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.hanging.HangingPlaceEvent event = new org.bukkit.event.hanging.HangingPlaceEvent(
                (org.bukkit.entity.Hanging) knot.getBukkitEntity(),
                player != null ? (org.bukkit.entity.Player) player.getBukkitEntity() : null,
                CraftBlock.at(level, pos), org.bukkit.block.BlockFace.SELF, CraftEquipmentSlot.getHand(peekLeashHand(player)));

        return event.callEvent();
    }

    /** PlayerLeashEntityEvent。1 匹ずつ、結び目に繋ぐ前。 */
    public static boolean leash(final Leashable leashable, final Entity holder, final Player player) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.PlayerLeashEntityEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.handlePlayerLeashEntityEvent(leashable, holder, player, peekLeashHand(player));
    }

    // ------------------------------------------------------------ メイス

    public static boolean smashListening() {
        return ShifuEvents.listening(io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent.getHandlerList());
    }

    /**
     * EntityAttemptSmashAttackEvent。
     *
     * <p>効かないもの: vanilla が打たない場面を {@code Result.ALLOW} で打たせること
     * (vanilla の判定の行は変えられない)。DENY は効く。
     *
     * @return vanilla の判定へ進んでよいか
     */
    public static boolean smashAttack(final ItemStack stack, final LivingEntity target, final LivingEntity attacker, final boolean vanilla) {
        final io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent event = new io.papermc.paper.event.entity.EntityAttemptSmashAttackEvent(
                (org.bukkit.entity.LivingEntity) attacker.getBukkitEntity(), (org.bukkit.entity.LivingEntity) target.getBukkitEntity(),
                CraftItemStack.asBukkitCopy(stack), vanilla);
        event.callEvent();

        return event.getResult() != Event.Result.DENY;
    }

    // ------------------------------------------------------------ 名札

    /** PlayerNameEntityEvent。名前を書き込む前。登録が無い、またはプレイヤーでなければ null。 */
    public static io.papermc.paper.event.player.PlayerNameEntityEvent nameEntity(final Player player, final LivingEntity target, final Component name) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !ShifuEvents.listening(io.papermc.paper.event.player.PlayerNameEntityEvent.getHandlerList())) {
            return null;
        }

        final io.papermc.paper.event.player.PlayerNameEntityEvent event = new io.papermc.paper.event.player.PlayerNameEntityEvent(
                serverPlayer.getBukkitEntity(), (org.bukkit.entity.LivingEntity) target.getBukkitEntity(),
                PaperAdventure.asAdventure(name), true);
        event.callEvent();

        return event;
    }

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
     * PlayerItemDamageEvent。耐久の減りが決まったあと、書き込む前。
     *
     * @return 減らす量。取り消されたら 0(vanilla は 0 なら何もしない)。登録が無ければ damage
     */
    public static int itemDamage(final ItemStack stack, final ServerPlayer player, final int damage, final int original) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerItemDamageEvent.getHandlerList())) {
            return damage;
        }

        final org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(
                player.getBukkitEntity(), CraftItemStack.asCraftMirror(stack), damage, original);

        return event.callEvent() ? event.getDamage() : 0;
    }
}
