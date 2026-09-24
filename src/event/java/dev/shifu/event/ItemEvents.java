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
import net.minecraft.core.IdMap;
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
import net.minecraft.world.inventory.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
        if (player == null) {
            return itemDamageByEntity(stack, damage);
        }

        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerItemDamageEvent.getHandlerList())) {
            return damage;
        }

        final org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(
                player.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack), damage);

        return event.callEvent() ? event.getDamage() : 0;
    }

    /**
     * EntityDamageItemEvent。持ち主がプレイヤーでないときの
     * {@link #itemDamage}。
     *
     * <p>vanilla の {@code hurtAndBreak} は 3 つめが {@code ServerPlayer} なので、
     * mob はそこまで届かない。Paper は署名を {@code LivingEntity} に広げている。
     * Shifu は呼ぶ手前で控えて、同じ {@code ItemStack} のときだけ使う。
     *
     * <p>読んだ位置(Paper 1.20.6):
     *   Paper-Server src/main/java/net/minecraft/world/item/ItemStack.java:683
     */
    private static ItemStack damagedStack;
    private static net.minecraft.world.entity.LivingEntity damagedBy;

    /** 誰の道具かを控える。{@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} の直前。 */
    public static void armItemDamage(final ItemStack stack, final net.minecraft.world.entity.LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.player.Player
                || !ShifuEvents.listening(io.papermc.paper.event.entity.EntityDamageItemEvent.getHandlerList())) {
            damagedStack = null;
            damagedBy = null;

            return;
        }

        damagedStack = stack;
        damagedBy = entity;
    }

    private static int itemDamageByEntity(final ItemStack stack, final int damage) {
        final ItemStack armed = damagedStack;
        final net.minecraft.world.entity.LivingEntity entity = damagedBy;
        damagedStack = null;
        damagedBy = null;

        if (armed != stack || entity == null) {
            return damage;
        }

        final io.papermc.paper.event.entity.EntityDamageItemEvent event =
                new io.papermc.paper.event.entity.EntityDamageItemEvent(entity.getBukkitLivingEntity(),
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
    public static void drag(final AbstractContainerMenu menu, final boolean greedy, final Player player) {
        final Map<Slot, ItemStack[]> before = dragSlots;
        final ItemStack oldCarried = dragCarried;
        dragSlots = null;
        dragCarried = null;

        if (before == null) {
            return;
        }

        final org.bukkit.inventory.InventoryView view = view(menu, player);

        if (view == null) {
            return;
        }

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


    // ------------------------------------------------------------ MOD のメニューの InventoryView

    /**
     * 型ごとに、getBukkitView の本体を持っているか。
     *
     * <p>shim は AbstractContainerMenu に getBukkitView を抽象メソッドとして足し、vanilla の
     * メニューには Paper の本体を足している。AbstractContainerMenu を直に継いだ MOD のメニューは
     * 本体を持たないので、呼ぶと AbstractMethodError になる(InventoryClickEvent に登録があると
     * MOD の画面をクリックした瞬間に落ちていた)。
     */
    private static final ClassValue<Boolean> OWN_VIEW = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            try {
                return !java.lang.reflect.Modifier.isAbstract(type.getMethod("getBukkitView").getModifiers());
            } catch (final NoSuchMethodException e) {
                return false;
            }
        }
    };

    /**
     * {@code menu.getBukkitView()} の代わり。本体を持たない MOD のメニューには、画面のマスを
     * そのまま読み書きする view を作って返す(上の段は CHEST として見える)。
     * プレイヤーは持ち物のマスか、その画面を開いている人から引く。どちらも無ければ null。
     *
     * <p>プレイヤーを渡さずに呼ぶのは prepareResult(ItemCombinerMenu と vanilla の結果の枠を持つ
     * メニュー)だけ。ItemCombinerMenu は構築子で必ず持ち物のマスを足すので、null にはならない。
     * ほかの呼び手は、その呼び出しの対象のプレイヤーを {@link #view(AbstractContainerMenu, Player)} に渡す。
     *
     * <p>shim の抽象メソッドに既定の本体を付ける形にしないのは、closure が vanilla のメニューに
     * getBukkitView を足す手掛かりが「抽象メソッドを実装していない」というコンパイルエラー
     * (docs/backlog/required-members.txt の {@code abstract getBukkitView})だから。
     * 本体を付けるとその要求が出なくなり、vanilla のメニューも汎用の view になる。
     */
    public static InventoryView view(final AbstractContainerMenu menu) {
        return view(menu, null);
    }

    /**
     * {@link #view(AbstractContainerMenu)} の、開いているプレイヤーが分かっているときの形。
     * 開く途中の画面は、まだ誰の containerMenu にもなっていないので探せない。
     *
     * @param known 開いているプレイヤー。null なら持ち物のマスと、その画面を開いている人から探す
     */
    public static InventoryView view(final AbstractContainerMenu menu, final Player known) {
        if (OWN_VIEW.get(menu.getClass())) {
            return menu.getBukkitView();
        }

        final Player viewer = known != null ? known : MenuSlots.viewer(menu);

        if (viewer == null) {
            return null;
        }

        return new org.bukkit.craftbukkit.inventory.CraftInventoryView(viewer.getBukkitEntity(),
                new org.bukkit.craftbukkit.inventory.CraftInventory(new MenuSlots(menu)), menu);
    }

    /**
     * {@code CraftHumanEntity.openInventory(InventoryView)} が画面を開く packet に入れる種類。
     * Paper は上の段の Bukkit の種類から引くが、汎用の view の上の段は CHEST なので、
     * MOD の画面では MOD のメニュー自身の種類を使う(大きさが 9 の倍数でなければ引けず null になる)。
     */
    public static net.minecraft.world.inventory.MenuType<?> menuType(final AbstractContainerMenu menu,
                                                                     final org.bukkit.inventory.Inventory top) {
        if (OWN_VIEW.get(menu.getClass())) {
            return org.bukkit.craftbukkit.inventory.CraftContainer.getNotchInventoryType(top);
        }

        return menu.getType();
    }

    /**
     * {@code from.transferTo(to, who)}(shim が足した Paper の本体)の代わり。本体は両方の view の
     * 上下の段に onClose / onOpen を渡すだけなので、同じことを {@link #view} の view で行う。
     *
     * <p>読んだ位置: Paper-Server@HEAD src/main/java/net/minecraft/world/inventory/AbstractContainerMenu.java:83
     */
    public static void transferTo(final AbstractContainerMenu from, final AbstractContainerMenu to,
                                  final org.bukkit.craftbukkit.entity.CraftHumanEntity who) {
        if (OWN_VIEW.get(from.getClass()) && OWN_VIEW.get(to.getClass())) {
            from.transferTo(to, who);

            return;
        }

        final InventoryView source = view(from, who.getHandle());
        final InventoryView destination = view(to, who.getHandle());

        if (source != null) {
            ((org.bukkit.craftbukkit.inventory.CraftInventory) source.getTopInventory()).getInventory().onClose(who);
            ((org.bukkit.craftbukkit.inventory.CraftInventory) source.getBottomInventory()).getInventory().onClose(who);
        }

        if (destination != null) {
            ((org.bukkit.craftbukkit.inventory.CraftInventory) destination.getTopInventory()).getInventory().onOpen(who);
            ((org.bukkit.craftbukkit.inventory.CraftInventory) destination.getBottomInventory()).getInventory().onOpen(who);
        }
    }

    /**
     * 本体を持たない MOD のメニューの上の段。プレイヤーの持ち物より前にあるマスを、番号どおりに
     * 1 つずつ読み書きする。Bukkit の生の番号(上の段が先、持ち物が後)と揃えるため、
     * 数えるのは先頭から最初の持ち物のマスの手前まで。
     *
     * <p>MOD の Container を CraftInventory に直に渡さないのは、MOD が Container を直に実装していると
     * shim が Container に足した getViewers などの本体も無く、同じ AbstractMethodError になるから。
     */
    private static final class MenuSlots implements Container {
        /** onOpen / onClose で入った人。キーは弱参照で、閉じたメニューはそのまま消える。 */
        private static final Map<AbstractContainerMenu, List<org.bukkit.entity.HumanEntity>> MENU_VIEWERS =
                new java.util.WeakHashMap<>();

        private final AbstractContainerMenu menu;
        private final int size;

        MenuSlots(final AbstractContainerMenu menu) {
            int count = 0;

            while (count < menu.slots.size()
                    && !(menu.slots.get(count).container instanceof net.minecraft.world.entity.player.Inventory)) {
                count++;
            }

            this.menu = menu;
            this.size = count;
        }

        /** その画面を開いているプレイヤー。持ち物のマスから引き、無ければ開いている人を探す。 */
        static Player viewer(final AbstractContainerMenu menu) {
            for (final Slot slot : menu.slots) {
                if (slot.container instanceof net.minecraft.world.entity.player.Inventory inventory) {
                    return inventory.player;
                }
            }

            for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
                if (player.containerMenu == menu) {
                    return player;
                }
            }

            return null;
        }

        @Override
        public int getContainerSize() {
            return this.size;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < this.size; i++) {
                if (!this.menu.getSlot(i).getItem().isEmpty()) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public ItemStack getItem(final int slot) {
            return this.menu.getSlot(slot).getItem();
        }

        @Override
        public ItemStack removeItem(final int slot, final int amount) {
            return this.menu.getSlot(slot).remove(amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(final int slot) {
            final ItemStack stack = this.menu.getSlot(slot).getItem();
            this.menu.getSlot(slot).set(ItemStack.EMPTY);

            return stack;
        }

        @Override
        public void setItem(final int slot, final ItemStack stack) {
            this.menu.getSlot(slot).set(stack);
        }

        @Override
        public void setChanged() {
            for (int i = 0; i < this.size; i++) {
                this.menu.getSlot(i).setChanged();
            }
        }

        @Override
        public boolean stillValid(final Player player) {
            return this.menu.stillValid(player);
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < this.size; i++) {
                this.menu.getSlot(i).set(ItemStack.EMPTY);
            }
        }

        @Override
        public List<ItemStack> getContents() {
            final List<ItemStack> contents = new ArrayList<>(this.size);

            for (int i = 0; i < this.size; i++) {
                contents.add(this.menu.getSlot(i).getItem());
            }

            return contents;
        }

        // 開いている人は、transferTo が渡す onOpen / onClose(Paper の transaction と同じ)と、
        // その画面を containerMenu にしている人の和。view は呼ぶたびに作り直すので、控えはメニューに結ぶ。
        // containerMenu だけで数えると、InventoryOpenEvent の中(まだ containerMenu になっていない)で
        // 開く人が入らない。onOpen だけで数えると、InventoryOpenEvent の登録が無く transferTo を
        // 通らずに開いた人が入らない。
        @Override
        public void onOpen(final org.bukkit.craftbukkit.entity.CraftHumanEntity who) {
            synchronized (MENU_VIEWERS) {
                final List<org.bukkit.entity.HumanEntity> viewers =
                        MENU_VIEWERS.computeIfAbsent(this.menu, key -> new ArrayList<>());

                if (!viewers.contains(who)) {
                    viewers.add(who);
                }
            }
        }

        @Override
        public void onClose(final org.bukkit.craftbukkit.entity.CraftHumanEntity who) {
            synchronized (MENU_VIEWERS) {
                final List<org.bukkit.entity.HumanEntity> viewers = MENU_VIEWERS.get(this.menu);

                if (viewers != null) {
                    viewers.remove(who);
                }
            }
        }

        @Override
        public List<org.bukkit.entity.HumanEntity> getViewers() {
            final List<org.bukkit.entity.HumanEntity> viewers = new ArrayList<>();

            synchronized (MENU_VIEWERS) {
                viewers.addAll(MENU_VIEWERS.getOrDefault(this.menu, List.of()));
            }

            for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
                if (player.containerMenu == this.menu && !viewers.contains(player.getBukkitEntity())) {
                    viewers.add(player.getBukkitEntity());
                }
            }

            return viewers;
        }

        public org.bukkit.inventory.InventoryHolder getOwner() {
            return null;
        }

        @Override
        public void setMaxStackSize(final int size) {
        }

        @Override
        public org.bukkit.Location getLocation() {
            return null;
        }
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


    public static net.minecraft.world.effect.MobEffect effect(final org.bukkit.potion.PotionEffectType type) {
        return type == null ? null : net.minecraft.world.effect.MobEffect.byId(type.getId());
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
    public record LoomChoice(int index, BannerPattern pattern) {
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

        // 1.18.2 の callEntityPlaceEvent は手を取らない
        return !CraftEventFactory.callEntityPlaceEvent(level, pos, face, player, entity).isCancelled();
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


    /**
     * PlayerLaunchProjectileEvent(雪玉・卵・エンダーパール・経験値瓶・投げポーション)。
     *
     * <p>vanilla は 音 → 生成 → addFreshEntity → 統計 → 消費 を分岐なしで並べる。
     * Paper は音と統計と消費を addFreshEntity の後ろへ動かして、取り消したら
     * 何も起きない形にしている。Shifu は vanilla の行を動かせないので、登録が
     * あるときだけ音・統計・消費を飛ばし({@code if (!launchListening())})、
     * その分をここで行う。飛ばしたままだと投擲物が減らず、無限に投げられた。
     *
     * <p>{@code shouldConsume()} が false のときは手元を送り直す(MC-99075)。
     *
     * <p>読んだ位置: Paper-Server
     * src/main/java/net/minecraft/world/item/SnowballItem.java(PlayerLaunchProjectileEvent)
     *
     * @return vanilla の続きへ進んでよいか。false なら呼ぶ側は fail を返す
     */
    public static boolean launch(final net.minecraft.world.level.Level level, final Player user,
                                 final ItemStack stack, final net.minecraft.world.entity.Entity projectile,
                                 final net.minecraft.sounds.SoundEvent sound,
                                 final net.minecraft.sounds.SoundSource source,
                                 final int cooldown) {
        final com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event =
                new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent(
                        (org.bukkit.entity.Player) user.getBukkitEntity(),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack),
                        (org.bukkit.entity.Projectile) projectile.getBukkitEntity());

        if (!event.callEvent() || !level.addFreshEntity(projectile)) {
            if (user instanceof net.minecraft.server.level.ServerPlayer sender) {
                sender.getBukkitEntity().updateInventory();
            }

            return false;
        }

        user.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(stack.getItem()));

        if (event.shouldConsume()) {
            if (!user.getAbilities().instabuild) {
                stack.shrink(1);
            }
        } else if (user instanceof net.minecraft.server.level.ServerPlayer sender) {
            sender.getBukkitEntity().updateInventory();
        }

        if (sound != null) {
            level.playSound(null, user.getX(), user.getY(), user.getZ(), sound, source,
                    0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        }

        if (cooldown > 0) {
            user.getCooldowns().addCooldown(stack.getItem(), cooldown);
        }

        return true;
    }

    /**
     * PlayerFishEvent(FISHING)。釣り竿を投げるとき。
     *
     * <p>投げ入れの音も、登録があるときは item-menu.rules で飛ばしてあるので、
     * 取り消されなかったときにここで鳴らす。取り消されたら浮きは出さず、
     * {@code user.fishing} を戻す。
     *
     * <p>読んだ位置: Paper-Server
     * src/main/java/net/minecraft/world/item/FishingRodItem.java(PlayerFishEvent)
     *
     * @return vanilla の続きへ進んでよいか。false なら呼ぶ側は pass を返す
     */
    public static boolean castFishingHook(final net.minecraft.world.level.Level level, final Player user,
                                          final net.minecraft.world.InteractionHand hand,
                                          final net.minecraft.world.entity.projectile.FishingHook hook) {
        final org.bukkit.event.player.PlayerFishEvent event = new org.bukkit.event.player.PlayerFishEvent(
                (org.bukkit.entity.Player) user.getBukkitEntity(), null,
                (org.bukkit.entity.FishHook) hook.getBukkitEntity(),
                org.bukkit.event.player.PlayerFishEvent.State.FISHING);

        if (!event.callEvent()) {
            user.fishing = null;

            return false;
        }

        level.playSound(null, user.getX(), user.getY(), user.getZ(),
                net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW, net.minecraft.sounds.SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        level.addFreshEntity(hook);

        return true;
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

    /**
     * PlayerLeashEntityEvent(縄で柵に繋ぐ。1 匹ずつ)。
     * 1.18.2 に Leashable は無いので Mob を受ける。
     */
    public static boolean leash(final net.minecraft.world.entity.Mob mob,
                                final net.minecraft.world.entity.Entity holder, final Player player) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.PlayerLeashEntityEvent.getHandlerList())) {
            return true;
        }

        // 1.18.2 の callPlayerLeashEntityEvent は手を取らない
        return !CraftEventFactory.callPlayerLeashEntityEvent(mob, holder, player).isCancelled();
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
                // 1.18.2 の callPlayerItemMendEvent は枠も耐久 → 経験値の変換も取らない
                org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemMendEvent(
                        player, orb, item, repair);

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
                ShifuEvents.hand(user.getUsedItemHand())).callEvent();
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
                                            final net.minecraft.world.item.crafting.StonecutterRecipe recipe) {
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

    /**
     * PlayerLoomPatternSelectEvent。機織り機の模様を選んだ直前。
     *
     * <p>読んだ位置: Paper-Server@HEAD src/main/java/net/minecraft/world/inventory/LoomMenu.java:178
     */
    public static boolean loomSelect(final net.minecraft.world.entity.player.Player player,
                                     final net.minecraft.world.inventory.LoomMenu menu,
                                     final net.minecraft.world.level.block.entity.BannerPattern pattern) {
        if (!ShifuEvents.listening(io.papermc.paper.event.player.PlayerLoomPatternSelectEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.banner.PatternType type =
                org.bukkit.block.banner.PatternType.getByIdentifier(pattern.getHashname());
        final boolean allowed = new io.papermc.paper.event.player.PlayerLoomPatternSelectEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.craftbukkit.inventory.CraftInventoryLoom) menu.getBukkitView().getTopInventory(),
                type).callEvent();

        if (!allowed) {
            player.containerMenu.sendAllDataToRemote();
        }

        return allowed;
    }


    /**
     * PrepareItemCraftEvent。作業台の結果が決まった直後。
     *
     * @return 結果のアイテム。プラグインが差し替えたらそれ
     */
    public static ItemStack prepareCraft(final AbstractContainerMenu menu, final Player player, final ItemStack result) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.PrepareItemCraftEvent.getHandlerList())) {
            return result;
        }

        final org.bukkit.inventory.InventoryView view = view(menu, player);

        if (view == null || !(view.getTopInventory() instanceof org.bukkit.inventory.CraftingInventory inventory)) {
            return result;
        }

        inventory.setResult(org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(result));
        new org.bukkit.event.inventory.PrepareItemCraftEvent(inventory, view, false).callEvent();

        return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(inventory.getResult());
    }


    /**
     * EnchantItemEvent。エンチャントを付ける直前。
     *
     * <p>Paper はプラグインが差し替えた付与内容と経験値の量を使う。vanilla の
     * 行はその場で決めた並びをそのまま使うので、<b>渡しているのは取り消しだけ。</b>
     *
     * <p>1.19.4 の EnchantItemEvent は当たりの見せ札を持たないので、clueId と clueLevel は渡していない。
     *
     * <p>読んだ位置: Paper-Server@HEAD src/main/java/net/minecraft/world/inventory/EnchantmentMenu.java:243
     */
    public static boolean enchantItem(final net.minecraft.world.inventory.EnchantmentMenu menu,
                                      final net.minecraft.world.entity.player.Player player,
                                      final net.minecraft.world.item.ItemStack item,
                                      final java.util.List<net.minecraft.world.item.enchantment.EnchantmentInstance> list,
                                      final int cost, final int button, final int clueId, final int clueLevel,
                                      final net.minecraft.world.level.Level level,
                                      final net.minecraft.core.BlockPos pos) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || !ShifuEvents.listening(org.bukkit.event.enchantment.EnchantItemEvent.getHandlerList())) {
            return true;
        }

        final java.util.Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new java.util.LinkedHashMap<>();

        for (final net.minecraft.world.item.enchantment.EnchantmentInstance one : list) {
            enchants.put(bukkitEnchantment(one.enchantment), one.level);
        }

        return new org.bukkit.event.enchantment.EnchantItemEvent(serverPlayer.getBukkitEntity(),
                menu.getBukkitView(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(item),
                cost, enchants, button).callEvent();
    }


    /**
     * PrepareItemEnchantEvent。3 つの候補が決まった直後。
     *
     * <p>プラグインが直した候補({@code EnchantmentOffer})の必要レベルは
     * {@code costs} に書き戻す。取り消されたら候補を全部消す。
     * 付ける中身({@code enchantClue} / {@code levelClue})は vanilla が
     * 付与のときに引き直すので、書き戻していない。
     *
     * <p>読んだ位置: Paper-Server@HEAD src/main/java/net/minecraft/world/inventory/EnchantmentMenu.java:178
     */
    public static void prepareEnchant(final net.minecraft.world.inventory.EnchantmentMenu menu,
                                      final net.minecraft.world.item.ItemStack item,
                                      final int[] costs, final int[] enchantClue, final int[] levelClue,
                                      final int bookshelves,
                                      final net.minecraft.world.level.Level level,
                                      final net.minecraft.core.BlockPos pos) {
        if (!ShifuEvents.listening(org.bukkit.event.enchantment.PrepareItemEnchantEvent.getHandlerList())) {
            return;
        }

        if (!(menu.getBukkitView().getPlayer() instanceof org.bukkit.entity.Player player)) {
            return;
        }

        final org.bukkit.enchantments.EnchantmentOffer[] offers = new org.bukkit.enchantments.EnchantmentOffer[3];

        for (int i = 0; i < 3; i++) {
            final org.bukkit.enchantments.Enchantment enchantment = enchantClue[i] >= 0
                    ? bukkitEnchantment(net.minecraft.world.item.enchantment.Enchantment.byId(enchantClue[i]))
                    : null;
            offers[i] = enchantment != null
                    ? new org.bukkit.enchantments.EnchantmentOffer(enchantment, levelClue[i], costs[i]) : null;
        }

        final org.bukkit.event.enchantment.PrepareItemEnchantEvent event =
                new org.bukkit.event.enchantment.PrepareItemEnchantEvent(player, menu.getBukkitView(),
                        org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(item), offers, bookshelves);
        event.setCancelled(!item.isEnchantable());
        event.callEvent();

        for (int i = 0; i < 3; i++) {
            if (event.isCancelled()) {
                costs[i] = 0;
                enchantClue[i] = -1;
                levelClue[i] = -1;
            } else if (event.getOffers()[i] != null) {
                costs[i] = event.getOffers()[i].getCost();
            }
        }
    }


    /** AnvilDamagedEvent に登録があるか。 */
    public static boolean anvilDamagedListening() {
        return ShifuEvents.listening(com.destroystokyo.paper.event.block.AnvilDamagedEvent.getHandlerList());
    }

    /**
     * AnvilDamagedEvent。金床が傷む直前。
     *
     * @param before 傷む前の状態。取り消されたときの印にも使う
     * @param after  vanilla が決めた傷んだあとの状態。壊れるときは null
     * @return 実際に置く状態。{@code before} がそのまま返ったら取り消し
     */
    public static net.minecraft.world.level.block.state.BlockState anvilDamaged(
            final net.minecraft.world.inventory.AnvilMenu menu,
            final net.minecraft.world.level.block.state.BlockState before,
            final net.minecraft.world.level.block.state.BlockState after) {
        final com.destroystokyo.paper.event.block.AnvilDamagedEvent event =
                new com.destroystokyo.paper.event.block.AnvilDamagedEvent(menu.getBukkitView(),
                        after != null ? org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(after) : null);

        if (!event.callEvent()) {
            return before;
        }

        if (event.getDamageState() == com.destroystokyo.paper.event.block.AnvilDamagedEvent.DamageState.BROKEN) {
            return null;
        }

        return ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getDamageState().getMaterial()
                .createBlockData()).getState().setValue(
                        net.minecraft.world.level.block.AnvilBlock.FACING,
                        before.getValue(net.minecraft.world.level.block.AnvilBlock.FACING));
    }


    /**
     * PlayerChangeBeaconEffectEvent。ビーコンの効果を決める直前。
     *
     * <p>差し替えた効果({@code setPrimary} / {@code setSecondary})は
     * vanilla の行が引数を持つので使っていない。渡しているのは取り消しだけ。
     */
    public static boolean changeBeaconEffect(final net.minecraft.world.inventory.BeaconMenu menu,
                                             final net.minecraft.world.entity.player.Player player,
                                             final java.util.Optional<net.minecraft.world.effect.MobEffect> primary,
                                             final java.util.Optional<net.minecraft.world.effect.MobEffect> secondary) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || !ShifuEvents.listening(
                        io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent(serverPlayer.getBukkitEntity(),
                convert(primary), convert(secondary),
                menu.getBukkitView().getTopInventory().getLocation() == null
                        ? null : menu.getBukkitView().getTopInventory().getLocation().getBlock()).callEvent();
    }



    /**
     * PrepareResultEvent 系(金床は PrepareAnvilEvent、砥石は PrepareGrindstoneEvent、
     * 鍛冶台は PrepareSmithingEvent)。結果の枠が決まった直後。
     */
    public static void prepareResult(final AbstractContainerMenu menu, final int resultSlot) {
        if (!ShifuEvents.listening(com.destroystokyo.paper.event.inventory.PrepareResultEvent.getHandlerList())) {
            return;
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(menu, resultSlot);
    }


    /**
     * InventoryCreativeEvent。クリエイティブで枠に直接入れる直前。
     *
     * <p>差し替えたアイテム({@code setCursor})は vanilla の行が持つので使っていない。
     *
     * @return 入れてよいか
     */
    public static boolean inventoryCreative(final net.minecraft.server.level.ServerPlayer player,
                                            final int slot, final ItemStack stack) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.InventoryCreativeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.inventory.InventoryView view = player.inventoryMenu.getBukkitView();
        final org.bukkit.event.inventory.InventoryType.SlotType type = view.getSlotType(slot);

        return new org.bukkit.event.inventory.InventoryCreativeEvent(view, type, slot,
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack)).callEvent();
    }


    /** 書見台の入れ物から Bukkit の {@code Lectern} を引く。ブロックでなければ null。 */
    private static org.bukkit.block.Lectern lecternOf(final net.minecraft.world.Container lectern) {
        if (!(lectern instanceof net.minecraft.world.level.block.entity.LecternBlockEntity entity)
                || entity.getLevel() == null) {
            return null;
        }

        final org.bukkit.block.BlockState state =
                org.bukkit.craftbukkit.block.CraftBlock.at(entity.getLevel(), entity.getBlockPos()).getState();

        return state instanceof org.bukkit.block.Lectern found ? found : null;
    }

    /**
     * PlayerLecternPageChangeEvent。書見台のページを送る直前。
     *
     * @return 送ってよいか
     */
    public static boolean lecternPage(final net.minecraft.world.entity.player.Player player,
                                      final net.minecraft.world.Container lectern,
                                      final int from, final int to) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || !ShifuEvents.listening(
                        io.papermc.paper.event.player.PlayerLecternPageChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.Lectern holder = lecternOf(lectern);

        if (holder == null) {
            return true;
        }

        final io.papermc.paper.event.player.PlayerLecternPageChangeEvent.PageChangeDirection direction = to > from
                ? io.papermc.paper.event.player.PlayerLecternPageChangeEvent.PageChangeDirection.RIGHT
                : io.papermc.paper.event.player.PlayerLecternPageChangeEvent.PageChangeDirection.LEFT;

        return new io.papermc.paper.event.player.PlayerLecternPageChangeEvent(serverPlayer.getBukkitEntity(),
                holder, holder.getInventory().getItem(0), direction, from, to).callEvent();
    }

    /**
     * PlayerTakeLecternBookEvent。書見台から本を取る直前。
     *
     * @return 取ってよいか
     */
    public static boolean lecternTake(final net.minecraft.world.entity.player.Player player,
                                      final net.minecraft.world.Container lectern) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || !ShifuEvents.listening(
                        org.bukkit.event.player.PlayerTakeLecternBookEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.Lectern holder = lecternOf(lectern);

        if (holder == null) {
            return true;
        }

        return new org.bukkit.event.player.PlayerTakeLecternBookEvent(serverPlayer.getBukkitEntity(),
                holder).callEvent();
    }

    /** 1.18.2 の CraftEnchantment に nms から引く口は無い。CraftBukkit と同じく鍵で引く。 */
    private static org.bukkit.enchantments.Enchantment bukkitEnchantment(
            final net.minecraft.world.item.enchantment.Enchantment enchantment) {
        return org.bukkit.enchantments.Enchantment.getByKey(CraftNamespacedKey.fromMinecraft(
                net.minecraft.core.Registry.ENCHANTMENT.getKey(enchantment)));
    }

    /** 1.18.2 のビーコンは MobEffect を Optional で持つ。Bukkit の型へ。 */
    private static org.bukkit.potion.PotionEffectType convert(
            final java.util.Optional<net.minecraft.world.effect.MobEffect> effect) {
        return effect.map(one -> org.bukkit.potion.PotionEffectType.getById(net.minecraft.world.effect.MobEffect.getId(one)))
                .orElse(null);
    }
}
