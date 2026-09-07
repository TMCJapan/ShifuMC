// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftVector;

/**
 * ブロック(ディスペンサー・ブロックエンティティ・流体・レッドストーン)のイベント。
 *
 * <p>作りは {@link ShifuEvents} と同じ。登録が無ければ何も作らず、
 * 「vanilla の処理を続けてよいか」を返す。差し込む位置は Paper と同じにしてある。
 * 状態の控えと戻しは {@link ShifuEvents#blockChangeBefore} と同じ考え方で、
 * 置いたあとに発火して、取り消されたら控えに戻す。
 *
 * <p>Paper が呼び出し側からメソッドの署名を変えて渡している値(ディスペンサーの
 * {@code BlockSource}、かまどの位置など)は、呼ぶ直前に置いて中で取り出す
 * ({@link ShifuEvents#tntPrimeCause} と同じ方式)。
 */
public final class BlockEvents {
    private BlockEvents() {
    }

    private static boolean listening(final org.bukkit.event.HandlerList handlers) {
        return ShifuEvents.listening(handlers);
    }

    private static org.bukkit.block.Block bukkit(final LevelAccessor level, final BlockPos pos) {
        return CraftBlock.at(level, pos);
    }

    // ------------------------------------------------------------ ディスペンサー

    /**
     * BlockFailedDispenseEvent。空のディスペンサー(ドロッパー)が音を出す直前。
     *
     * @return 音と GameEvent を出してよいか
     */
    public static boolean failedDispense(final ServerLevel level, final BlockPos pos) {
        if (!listening(io.papermc.paper.event.block.BlockFailedDispenseEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.handleBlockFailedDispenseEvent(level, pos);
    }

    /**
     * BlockPreDispenseEvent。ディスペンサー(ドロッパー)が挙動を呼ぶ直前。
     *
     * @return 呼んでよいか
     */
    public static boolean preDispense(final ServerLevel level, final BlockPos pos, final ItemStack itemStack, final int slot) {
        if (!listening(io.papermc.paper.event.block.BlockPreDispenseEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.handleBlockPreDispenseEvent(level, pos, itemStack, slot);
    }

    private static BlockSource dispenseSource;
    private static ItemStack dispenseRemaining;

    /**
     * 既定の挙動({@code DefaultDispenseItemBehavior.execute})が {@code spawnItem} を呼ぶ直前。
     * vanilla の static な {@code spawnItem} には呼び出し元が渡らないので、ここで置く。
     */
    public static void dispensing(final BlockSource source, final ItemStack dispensed) {
        if (!listening(org.bukkit.event.block.BlockDispenseEvent.getHandlerList())) {
            return;
        }

        dispenseSource = source;
        dispenseRemaining = dispensed;
    }

    /**
     * BlockDispenseEvent(既定の挙動)。{@code spawnItem} で ItemEntity を作って速度を決めたあと、
     * 世界に足す直前。Paper と同じ位置で、取り消し・アイテムの差し替え・速度の差し替えが効く。
     *
     * <p>効かないもの: 種類の違うアイテムに差し替えたときに、その種類の挙動へ渡し直すこと
     * (Paper の chain)。差し替えたアイテムをそのまま落とす。
     *
     * @return 世界に足してよいか
     */
    public static boolean dispenseItem(final Level level, final ItemEntity itemEntity) {
        final BlockSource source = dispenseSource;
        final ItemStack remaining = dispenseRemaining;
        dispenseSource = null;
        dispenseRemaining = null;

        if (source == null || !listening(org.bukkit.event.block.BlockDispenseEvent.getHandlerList())) {
            return true;
        }

        final CraftItemStack craftItem = CraftItemStack.asCraftMirror(itemEntity.getItem());
        final org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(
                bukkit(level, source.pos()), craftItem.clone(), CraftVector.toBukkit(itemEntity.getDeltaMovement()));

        if (!event.callEvent()) {
            // Paper と同じく、取り出した 1 個を戻す
            remaining.grow(1);

            return false;
        }

        itemEntity.setItem(CraftItemStack.asNMSCopy(event.getItem()));
        itemEntity.setDeltaMovement(CraftVector.toVec3(event.getVelocity()));

        return true;
    }

    /**
     * BlockDispenseEvent(専用の挙動)。ボート・トロッコ・投射物・防具立て・バケツ・TNT・
     * スポーンエッグ・シュルカーボックス・ハサミ・ブラシなど、Paper が挙動ごとに発火しているもの。
     * 取り出す前に発火し、取り消されたら挙動を抜ける(ディスペンサーの音は vanilla のまま鳴る。Paper も同じ)。
     *
     * <p>効かないもの: {@code setItem} での差し替え、{@code setVelocity} での位置の差し替え。
     * どちらも vanilla の行が式の中で使う値なので書き換えられない。
     *
     * @param velocity Paper がイベントに入れている値(投射物は向き、ボート等は置く位置、それ以外は 0)
     * @return 続けてよいか
     */
    public static boolean dispense(final BlockSource source, final ItemStack dispensed, final Vec3 velocity) {
        if (!listening(org.bukkit.event.block.BlockDispenseEvent.getHandlerList())) {
            return true;
        }

        final CraftItemStack craftItem = CraftItemStack.asCraftMirror(dispensed.isDamageableItem() ? dispensed : dispensed.copyWithCount(1));

        return new org.bukkit.event.block.BlockDispenseEvent(
                bukkit(source.level(), source.pos()), craftItem.clone(), CraftVector.toBukkit(velocity)).callEvent();
    }

    /** 位置を渡す版。 */
    public static boolean dispense(final BlockSource source, final ItemStack dispensed, final BlockPos to) {
        return dispense(source, dispensed, Vec3.atLowerCornerOf(to));
    }

    /** 速度も位置も無い版。 */
    public static boolean dispense(final BlockSource source, final ItemStack dispensed) {
        return dispense(source, dispensed, Vec3.ZERO);
    }

    /**
     * BlockDispenseArmorEvent。装備をディスペンサーで着せる直前(ラマの箱、アルマジロのブラシも)。
     *
     * <p>効かないもの: {@code setItem} での差し替え。
     *
     * @return 着せてよいか
     */
    public static boolean dispenseArmor(final BlockSource source, final ItemStack dispensed, final LivingEntity target) {
        if (!listening(org.bukkit.event.block.BlockDispenseArmorEvent.getHandlerList())) {
            return true;
        }

        final CraftItemStack craftItem = CraftItemStack.asCraftMirror(dispensed.isDamageableItem() ? dispensed : dispensed.copyWithCount(1));

        return new org.bukkit.event.block.BlockDispenseArmorEvent(
                bukkit(source.level(), source.pos()), craftItem.clone(),
                (org.bukkit.craftbukkit.entity.CraftLivingEntity) target.getBukkitEntity()).callEvent();
    }

    /**
     * BlockShearEntityEvent。ディスペンサーのハサミが生き物を刈る直前。
     *
     * <p>効かないもの: {@code setDrops} での落とし物の差し替え(vanilla の {@code shear} が自分で落とす)。
     * イベントの drops は空(vanilla の {@code Shearable} は落とし物を先に作れない)。
     *
     * @return 刈ってよいか
     */
    public static boolean shearByBlock(final ServerLevel level, final BlockPos dispenser, final Entity entity, final ItemStack tool) {
        if (!listening(org.bukkit.event.block.BlockShearEntityEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callBlockShearEntityEvent(entity, bukkit(level, dispenser),
                CraftItemStack.asCraftMirror(tool), List.of()).isCancelled();
    }

    /** ディスペンサーのハサミの側で、刈る生き物の位置は分かるがディスペンサーの位置が分からないので置く。 */
    private static BlockPos shearingDispenser;

    public static void shearing(final BlockSource source) {
        if (!listening(org.bukkit.event.block.BlockShearEntityEvent.getHandlerList())) {
            return;
        }

        shearingDispenser = source.pos();
    }

    public static BlockPos shearingDispenser(final BlockPos fallback) {
        final BlockPos at = shearingDispenser;
        shearingDispenser = null;

        return at == null ? fallback : at;
    }

    // ------------------------------------------------------------ 中に入った・踏んだ・触れた

    /**
     * EntityInsideBlockEvent。{@code entityInside} の先頭。
     *
     * @return 続けてよいか
     */
    public static boolean insideBlock(final Entity entity, final Level level, final BlockPos pos) {
        if (!listening(io.papermc.paper.event.entity.EntityInsideBlockEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), bukkit(level, pos)).callEvent();
    }

    /**
     * 感圧板・レッドストーン鉱石・大きなドリップリーフを踏んだ。プレイヤーなら
     * PlayerInteractEvent(PHYSICAL)、それ以外は EntityInteractEvent。
     *
     * @return 続けてよいか
     */
    public static boolean physicalInteract(final Entity entity, final Level level, final BlockPos pos) {
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            if (!listening(org.bukkit.event.player.PlayerInteractEvent.getHandlerList())) {
                return true;
            }

            return !CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null).isCancelled();
        }

        if (!listening(org.bukkit.event.entity.EntityInteractEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), bukkit(level, pos)).callEvent();
    }

    private static boolean listeningPhysical() {
        return listening(org.bukkit.event.player.PlayerInteractEvent.getHandlerList())
                || listening(org.bukkit.event.entity.EntityInteractEvent.getHandlerList());
    }

    /**
     * 感圧板の信号。Paper は上に乗っている全部について発火し、取り消されなかったものだけ数える。
     * vanilla の {@code getEntityCount} は数しか返さないので、同じ絞り方で自分で引く
     * (登録があるときだけ。絞り方は {@code BasePressurePlateBlock.getEntityCount} と同じ)。
     *
     * @return 取り消されなかったものの数
     */
    public static int pressurePlateCount(final Level level, final BlockPos pos, final net.minecraft.world.phys.AABB box,
                                         final Class<? extends Entity> entityClass) {
        int count = 0;

        for (final Entity entity : level.getEntitiesOfClass(entityClass, box,
                net.minecraft.world.entity.EntitySelector.NO_SPECTATORS.and(e -> !e.isIgnoringBlockTriggers()))) {
            if (physicalInteract(entity, level, pos)) {
                count++;
            }
        }

        return count;
    }

    /** 感圧板の側で、登録があるときだけ生き物の一覧を取るための判定。 */
    public static boolean listeningPressurePlate() {
        return listeningPhysical();
    }

    private static Entity oreToucher;

    /** レッドストーン鉱石を光らせる直前に、触れたものを置く(EntityChangeBlockEvent の主)。 */
    public static void oreToucher(final Entity entity) {
        if (!listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return;
        }

        oreToucher = entity;
    }

    /**
     * EntityChangeBlockEvent(レッドストーン鉱石が光る)。置く前。
     *
     * @return 光らせてよいか
     */
    public static boolean oreLight(final Level level, final BlockPos pos, final BlockState lit) {
        final Entity entity = oreToucher;
        oreToucher = null;

        if (entity == null || !listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callEntityChangeBlockEvent(entity, pos, lit);
    }

    /**
     * EntityChangeBlockEvent。置く前に発火する形(Paper と同じ)。
     *
     * @return 置いてよいか
     */
    public static boolean entityChangeBlock(final Entity entity, final BlockPos pos, final BlockState newState) {
        if (!listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callEntityChangeBlockEvent(entity, pos, newState);
    }

    public static boolean listeningEntityChangeBlock() {
        return listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList());
    }

    /**
     * ケーキを食べる。EntityChangeBlockEvent。取り消されたら体力の表示を送り直して PASS。
     *
     * @return 食べてよいか
     */
    public static boolean cakeEat(final LevelAccessor level, final BlockPos pos, final BlockState state,
                                  final net.minecraft.world.entity.player.Player player) {
        if (!listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return true;
        }

        final int bites = state.getValue(net.minecraft.world.level.block.CakeBlock.BITES);
        final BlockState newState = bites < net.minecraft.world.level.block.CakeBlock.MAX_BITES
                ? state.setValue(net.minecraft.world.level.block.CakeBlock.BITES, bites + 1)
                : level.getFluidState(pos).createLegacyBlock();

        if (CraftEventFactory.callEntityChangeBlockEvent(player, pos, newState)) {
            return true;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.getBukkitEntity().sendHealthUpdate();
        }

        return false;
    }

    /**
     * EntityCombustByBlockEvent。火のブロックが燃やす直前。
     *
     * @return 燃やしてよいか。取り消されたら Paper と同じく残り火の時間を 1 戻す
     */
    public static boolean combustByBlock(final Entity entity, final BlockPos pos) {
        if (!listening(org.bukkit.event.entity.EntityCombustByBlockEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.entity.EntityCombustByBlockEvent event = new org.bukkit.event.entity.EntityCombustByBlockEvent(
                bukkit(entity.level(), pos), entity.getBukkitEntity(), 8.0F);

        if (event.callEvent()) {
            // 効かないもの: 燃える長さ(vanilla の 8 秒のまま)
            return true;
        }

        entity.setRemainingFireTicks(entity.getRemainingFireTicks() - 1);

        return false;
    }

    // ------------------------------------------------------------ ブロックの消滅・生成・変化

    /**
     * BlockFadeEvent。消える(溶ける・燃え尽きる・足場が落ちる)直前。
     *
     * @return 消してよいか
     */
    public static boolean fade(final net.minecraft.world.level.LevelReader level, final BlockPos pos, final BlockState newState) {
        if (!(level instanceof Level accessor) || !listening(org.bukkit.event.block.BlockFadeEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callBlockFadeEvent(accessor, pos, newState).isCancelled();
    }

    public static boolean listeningFade() {
        return listening(org.bukkit.event.block.BlockFadeEvent.getHandlerList());
    }

    /**
     * 氷が溶ける。消えるか水になるかは vanilla と同じ判定で決める。
     *
     * @return 溶かしてよいか
     */
    public static boolean iceMelt(final Level level, final BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockFadeEvent.getHandlerList())) {
            return true;
        }

        final boolean evaporates = level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, pos);

        return fade(level, pos, evaporates ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
    }

    /**
     * BlockFormEvent(返り値で状態を決める形: コンクリートパウダーが固まる)。
     *
     * <p>効かないもの: {@code getNewState()} の書き換え(vanilla が返す式を変えられない)。
     *
     * @return 固めてよいか
     */
    public static boolean listeningForm() {
        return listening(org.bukkit.event.block.BlockFormEvent.getHandlerList());
    }

    public static boolean formReturn(final LevelAccessor level, final BlockPos pos, final BlockState newState) {
        if (!(level instanceof Level world) || !listening(org.bukkit.event.block.BlockFormEvent.getHandlerList())) {
            return true;
        }

        final CraftBlockState snapshot = CraftBlockStates.getBlockState(world, pos);
        snapshot.setData(newState);

        return new org.bukkit.event.block.BlockFormEvent(snapshot.getBlock(), snapshot).callEvent();
    }

    /**
     * BlockPhysicsEvent(草花が支えを失って消える)。
     *
     * @return 消してよいか
     */
    public static boolean vegetationPhysics(final BlockState state, final net.minecraft.world.level.LevelReader level, final BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !listening(org.bukkit.event.block.BlockPhysicsEvent.getHandlerList())) {
            return true;
        }

        if (state.canSurvive(level, pos)) {
            return true;
        }

        return !CraftEventFactory.callBlockPhysicsEvent(serverLevel, pos).isCancelled();
    }

    /**
     * 火が広がる。BlockIgniteEvent(SPREAD)と BlockSpreadEvent。置く前。
     *
     * <p>効かないもの: BlockSpreadEvent の {@code getNewState()} の書き換え。
     *
     * @return 置いてよいか
     */
    public static boolean fireSpread(final ServerLevel level, final BlockPos from, final BlockPos to, final BlockState fire) {
        if (level.getBlockState(to).is(net.minecraft.world.level.block.Blocks.FIRE)) {
            return true;
        }

        if (listening(org.bukkit.event.block.BlockIgniteEvent.getHandlerList())
                && CraftEventFactory.callBlockIgniteEvent(level, to, from).isCancelled()) {
            return false;
        }

        if (!listening(org.bukkit.event.block.BlockSpreadEvent.getHandlerList())) {
            return true;
        }

        final CraftBlockState snapshot = CraftBlockStates.getBlockState(level, to);
        snapshot.setData(fire);

        return new org.bukkit.event.block.BlockSpreadEvent(snapshot.getBlock(), bukkit(level, from), snapshot).callEvent();
    }

    /** 火が広がるときだけ、vanilla は同じ判定を先に済ませているので二重に判定しない。 */
    public static boolean listeningFireSpread() {
        return listening(org.bukkit.event.block.BlockIgniteEvent.getHandlerList())
                || listening(org.bukkit.event.block.BlockSpreadEvent.getHandlerList());
    }

    private static BlockPos fireSource;

    /** 火の tick で、燃やす側の位置を置く(BlockBurnEvent の igniting block)。 */
    public static void fireSource(final BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockBurnEvent.getHandlerList())) {
            return;
        }

        fireSource = pos;
    }

    /**
     * BlockBurnEvent。隣のブロックを燃やす(消す)直前。
     *
     * @return 燃やしてよいか
     */
    public static boolean burn(final Level level, final BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockBurnEvent.getHandlerList())) {
            return true;
        }

        final BlockPos source = fireSource;

        return new org.bukkit.event.block.BlockBurnEvent(bukkit(level, pos), source == null ? null : bukkit(level, source)).callEvent();
    }

    // EntityConstructEvent は 26.x で入った Paper のイベントで、1.21.11 の API には無い。

    // ------------------------------------------------------------ コンポスター

    private static boolean compostNotConsumed;

    /**
     * CompostItemEvent / EntityCompostItemEvent。段が上がるときは上がる直前、上がらないときは
     * vanilla がそう決めたあと。そのあと EntityChangeBlockEvent(上がるときだけ)。
     *
     * <p>効かないもの: 上がらないと決まったあとに {@code setWillRaiseLevel(true)} で上げること。
     *
     * @return 続けてよいか(上げてよいか)。取り消されたときはアイテムを消費しない
     */
    public static boolean compostItem(final Entity sourceEntity, final LevelAccessor level, final BlockPos pos,
                                      final ItemStack itemStack, final BlockState state, final boolean willRaiseLevel) {
        final boolean listeningCompost = sourceEntity == null
                ? listening(io.papermc.paper.event.block.CompostItemEvent.getHandlerList())
                : listening(io.papermc.paper.event.entity.EntityCompostItemEvent.getHandlerList());

        if (!listeningCompost && !(willRaiseLevel && sourceEntity != null && listeningEntityChangeBlock())) {
            return true;
        }

        boolean raise = willRaiseLevel;

        if (listeningCompost) {
            final io.papermc.paper.event.block.CompostItemEvent event = sourceEntity == null
                    ? new io.papermc.paper.event.block.CompostItemEvent(bukkit(level, pos), CraftItemStack.asCraftMirror(itemStack), willRaiseLevel)
                    : new io.papermc.paper.event.entity.EntityCompostItemEvent(sourceEntity.getBukkitEntity(), bukkit(level, pos), CraftItemStack.asCraftMirror(itemStack), willRaiseLevel);

            if (!event.callEvent()) {
                compostNotConsumed = true;

                return false;
            }

            raise = event.willRaiseLevel();
        }

        if (!willRaiseLevel || !raise) {
            return raise && willRaiseLevel;
        }

        if (sourceEntity != null && listeningEntityChangeBlock()
                && !CraftEventFactory.callEntityChangeBlockEvent(sourceEntity, pos,
                        state.setValue(net.minecraft.world.level.block.ComposterBlock.LEVEL, state.getValue(net.minecraft.world.level.block.ComposterBlock.LEVEL) + 1))) {
            compostNotConsumed = true;

            return false;
        }

        return true;
    }

    /** 入れた側で、取り消されたときはアイテムを減らさない。 */
    public static boolean compostConsumes() {
        final boolean cancelled = compostNotConsumed;
        compostNotConsumed = false;

        return !cancelled;
    }

    // ------------------------------------------------------------ ホッパー・ドロッパー

    private static org.bukkit.inventory.Inventory inventoryOf(final Container container) {
        if (container instanceof net.minecraft.world.CompoundContainer compound) {
            return new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest(compound);
        }

        if (container.getOwner() != null) {
            return container.getOwner().getInventory();
        }

        return new org.bukkit.craftbukkit.inventory.CraftInventory(container);
    }

    /**
     * InventoryMoveItemEvent。ホッパーとドロッパーが容器へ 1 個入れる直前、ホッパーが容器から
     * 1 個取る直前。
     *
     * <p>効かないもの: {@code setItem} での差し替え(vanilla の行が取り出して渡す)。
     * Spigot の hopperAmount(まとめて運ぶ数)は入れていない。
     *
     * @return 運んでよいか。取り消されたら Paper と同じくホッパーに待ち時間 8 tick を置く
     */
    public static boolean moveItem(final Container from, final Container to, final ItemStack itemStack, final boolean didSourceInitiate,
                                   final net.minecraft.world.level.block.entity.HopperBlockEntity cooldownTarget) {
        if (!listening(org.bukkit.event.inventory.InventoryMoveItemEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.inventory.InventoryMoveItemEvent event = new org.bukkit.event.inventory.InventoryMoveItemEvent(
                inventoryOf(from), CraftItemStack.asCraftMirror(itemStack.copyWithCount(1)), inventoryOf(to), didSourceInitiate);

        if (event.callEvent()) {
            return true;
        }

        if (cooldownTarget != null) {
            cooldownTarget.setCooldown(8);
        }

        return false;
    }

    /**
     * InventoryPickupItemEvent。ホッパーが落ちているアイテムを拾う直前。
     *
     * @return 拾ってよいか
     */
    public static boolean pickupItem(final Container container, final ItemEntity entity) {
        if (!listening(org.bukkit.event.inventory.InventoryPickupItemEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.inventory.InventoryPickupItemEvent(
                inventoryOf(container), (org.bukkit.entity.Item) entity.getBukkitEntity()).callEvent();
    }

    public static boolean listeningHopperSearch() {
        return listening(org.bukkit.event.inventory.HopperInventorySearchEvent.getHandlerList());
    }

    /**
     * HopperInventorySearchEvent。ホッパーが相手の容器を探した結果を差し替える。
     * 登録があるときだけ呼ばれ、vanilla の返り値の代わりになる。
     */
    public static Container hopperSearch(final Level level, final BlockPos hopper, final BlockPos searched, final Container found,
                                         final org.bukkit.event.inventory.HopperInventorySearchEvent.ContainerType type) {
        final org.bukkit.event.inventory.HopperInventorySearchEvent event = new org.bukkit.event.inventory.HopperInventorySearchEvent(
                found == null ? null : new org.bukkit.craftbukkit.inventory.CraftInventory(found), type, bukkit(level, hopper), bukkit(level, searched));
        event.callEvent();

        return event.getInventory() == null ? null : ((org.bukkit.craftbukkit.inventory.CraftInventory) event.getInventory()).getInventory();
    }

    // ------------------------------------------------------------ レッドストーン

    /**
     * BlockRedstoneEvent(0 か 15 の二値)。
     *
     * @return 変えてよいか(プラグインが値を元に戻していないか)
     */
    public static boolean binaryRedstone(final LevelAccessor level, final BlockPos pos, final boolean willBePowered) {
        if (!listening(org.bukkit.event.block.BlockRedstoneEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callBinaryRedstoneChange(level, pos, willBePowered);
    }

    /**
     * BlockRedstoneEvent(強さ)。レッドストーンダストの評価と的ブロック。
     *
     * @return プラグインが決めた強さ。登録が無ければそのまま
     */
    public static int redstoneChange(final LevelAccessor level, final BlockPos pos, final int oldCurrent, final int newCurrent) {
        if (oldCurrent == newCurrent || !listening(org.bukkit.event.block.BlockRedstoneEvent.getHandlerList())) {
            return newCurrent;
        }

        return CraftEventFactory.callRedstoneChange(level, pos, oldCurrent, newCurrent).getNewCurrent();
    }

    /**
     * レッドストーンダスト。Paper は「置かれている状態が今の状態のとき」だけ発火する。
     */
    public static int redstoneWire(final Level level, final BlockPos pos, final BlockState state, final int targetStrength) {
        final int previous = state.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);

        if (previous == targetStrength || level.getBlockState(pos) != state) {
            return targetStrength;
        }

        return redstoneChange(level, pos, previous, targetStrength);
    }

    /**
     * TargetHitEvent。的に投射物が当たった。
     *
     * @return プラグインが決めた強さ。取り消されたら -1
     */
    public static int targetHit(final LevelAccessor level, final net.minecraft.world.phys.BlockHitResult hitResult, final Entity entity, final int strength) {
        if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile)
                || !listening(io.papermc.paper.event.block.TargetHitEvent.getHandlerList())) {
            return strength;
        }

        final io.papermc.paper.event.block.TargetHitEvent event = new io.papermc.paper.event.block.TargetHitEvent(
                (org.bukkit.entity.Projectile) entity.getBukkitEntity(), CraftBlock.at(level, hitResult.getBlockPos()),
                CraftBlock.notchToBlockFace(hitResult.getDirection()), strength);

        return event.callEvent() ? event.getSignalStrength() : -1;
    }

    // ------------------------------------------------------------ トリップワイヤーフック

    /**
     * BlockRedstoneEvent(トリップワイヤーフック)。電源が変わるときだけ発火する。
     *
     * @return そのフックの状態を書いてよいか
     */
    public static boolean tripwireHook(final Level level, final BlockPos pos, final boolean wasPowered, final boolean powered) {
        if (wasPowered == powered) {
            return true;
        }

        return binaryRedstone(level, pos, powered);
    }

    // ------------------------------------------------------------ スポンジ

    private static List<CraftBlockState> spongeAbsorbed;

    /** 吸い始める前に、控えの入れ物を用意する。登録が無ければ何もしない。 */
    public static void spongeBegin() {
        spongeAbsorbed = listening(org.bukkit.event.block.SpongeAbsorbEvent.getHandlerList()) ? new ArrayList<>() : null;
    }

    /** 吸う(空気にする)ブロックの控え。探索のラムダの中で、置く前に呼ぶ。 */
    public static void spongeBefore(final Level level, final BlockPos pos) {
        if (spongeAbsorbed != null) {
            spongeAbsorbed.add(CraftBlockStates.getBlockState(level, pos));
        }
    }

    /**
     * SpongeAbsorbEvent。vanilla が水を消したあと、スポンジを濡らす前。取り消されたら控えに戻す。
     *
     * <p>効かないもの: 昆布などの落とし物は取り消しても出る(vanilla が先に落とす)。
     * イベントの blocks の書き換え。
     *
     * @return スポンジを濡らしてよいか
     */
    public static boolean spongeAbsorb(final Level level, final BlockPos sponge) {
        final List<CraftBlockState> before = spongeAbsorbed;
        spongeAbsorbed = null;

        if (before == null || before.isEmpty()) {
            return true;
        }

        final List<org.bukkit.block.BlockState> after = new ArrayList<>();

        for (final CraftBlockState state : before) {
            after.add(CraftBlockStates.getBlockState(level, state.getPosition()));
        }

        if (new org.bukkit.event.block.SpongeAbsorbEvent(bukkit(level, sponge), after).callEvent()) {
            return true;
        }

        for (int i = before.size() - 1; i >= 0; i--) {
            before.get(i).place(Block.UPDATE_ALL);
        }

        return false;
    }

    // ------------------------------------------------------------ 流体

    /**
     * BlockFromToEvent。流体が隣へ広がる直前。
     *
     * @return 広がってよいか
     */
    public static boolean fromTo(final ServerLevel level, final BlockPos from, final Direction direction) {
        if (!listening(org.bukkit.event.block.BlockFromToEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.block.BlockFromToEvent(bukkit(level, from), CraftBlock.notchToBlockFace(direction)).callEvent();
    }

    /**
     * FluidLevelChangeEvent。流体の段が変わる直前。
     *
     * @return 置く状態。取り消されたら null
     */
    public static BlockState fluidLevelChange(final ServerLevel level, final BlockPos pos, final BlockState newState) {
        if (!listening(org.bukkit.event.block.FluidLevelChangeEvent.getHandlerList())) {
            return newState;
        }

        final org.bukkit.event.block.FluidLevelChangeEvent event = CraftEventFactory.callFluidLevelChangeEvent(level, pos, newState);

        if (event.isCancelled()) {
            return null;
        }

        return ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getNewData()).getState();
    }

    // ------------------------------------------------------------ 振動

    public static boolean listeningReceiveGameEvent() {
        return listening(org.bukkit.event.block.BlockReceiveGameEvent.getHandlerList());
    }

    /**
     * BlockReceiveGameEvent。スカルクセンサーなどが振動を受ける直前。登録があるときだけ呼ばれる。
     *
     * <p>効かないもの: vanilla が受けないと判定したものを、取り消しを外して受けさせること。
     *
     * @return 受けてよいか
     */
    public static boolean receiveGameEvent(final ServerLevel level, final net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                                           final net.minecraft.world.level.gameevent.GameEvent.Context context, final Vec3 destination, final boolean vanillaReceives) {
        final Entity entity = context.sourceEntity();
        final org.bukkit.event.block.BlockReceiveGameEvent bukkitEvent = new org.bukkit.event.block.BlockReceiveGameEvent(
                org.bukkit.craftbukkit.CraftGameEvent.minecraftHolderToBukkit(event), bukkit(level, BlockPos.containing(destination)),
                entity == null ? null : entity.getBukkitEntity());
        bukkitEvent.setCancelled(!vanillaReceives);
        bukkitEvent.callEvent();

        return !bukkitEvent.isCancelled();
    }

    // ------------------------------------------------------------ ポータル

    /**
     * EntityPortalEnterEvent。ネザーポータルの中に入った。
     *
     * @return 続けてよいか
     */
    public static boolean portalEnter(final Entity entity, final Level level, final BlockPos pos) {
        if (!listening(org.bukkit.event.entity.EntityPortalEnterEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(),
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level), org.bukkit.PortalType.NETHER).callEvent();
    }

    /**
     * EntityPortalReadyEvent。行き先の世界が決まった直後。
     *
     * @return 行き先の世界。取り消されたら null(vanilla は null なら移動しない)
     */
    public static ServerLevel portalReady(final Entity entity, final ServerLevel newLevel) {
        if (!listening(io.papermc.paper.event.entity.EntityPortalReadyEvent.getHandlerList())) {
            return newLevel;
        }

        final io.papermc.paper.event.entity.EntityPortalReadyEvent event = new io.papermc.paper.event.entity.EntityPortalReadyEvent(
                entity.getBukkitEntity(), newLevel == null ? null : newLevel.getWorld(), org.bukkit.PortalType.NETHER);

        if (!event.callEvent()) {
            return null;
        }

        return event.getTargetWorld() == null ? null : ((org.bukkit.craftbukkit.CraftWorld) event.getTargetWorld()).getHandle();
    }

    /**
     * EntityPortalEvent / PlayerPortalEvent。ネザーの出口を探す直前(Paper の handlePortalEvents)。
     *
     * <p>効かないもの: 探す半径・作る半径の差し替え(vanilla の {@code getExitPortal} は自分の値を使う)。
     * 行き先の世界と位置の差し替えは効く。
     *
     * @return 行き先。取り消されたら null
     */
    public static org.bukkit.craftbukkit.event.PortalEventResult portalDestination(final Entity entity, final ServerLevel newLevel, final BlockPos approximateExitPos) {
        if (!listening(org.bukkit.event.entity.EntityPortalEvent.getHandlerList())
                && !listening(org.bukkit.event.player.PlayerPortalEvent.getHandlerList())) {
            return null;
        }

        return CraftEventFactory.handlePortalEvents(entity, org.bukkit.craftbukkit.util.CraftLocation.toBukkit(approximateExitPos, newLevel),
                org.bukkit.PortalType.NETHER, 128, 16);
    }

    public static boolean listeningPortal() {
        return listening(org.bukkit.event.entity.EntityPortalEvent.getHandlerList())
                || listening(org.bukkit.event.player.PlayerPortalEvent.getHandlerList());
    }

    // ------------------------------------------------------------ 書見台・看板・鐘

    /**
     * PlayerInsertLecternBookEvent。本を置く直前。
     *
     * <p>効かないもの: {@code setBook} での差し替え(vanilla の行が渡す本を変えられない)。
     *
     * @return 置いてよいか
     */
    public static boolean insertLecternBook(final LivingEntity sourceEntity, final Level level, final BlockPos pos, final ItemStack book) {
        if (!(sourceEntity instanceof ServerPlayer player)
                || !listening(io.papermc.paper.event.player.PlayerInsertLecternBookEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PlayerInsertLecternBookEvent(player.getBukkitEntity(), bukkit(level, pos),
                CraftItemStack.asCraftMirror(book.copyWithCount(1))).callEvent();
    }

    /**
     * BlockRedstoneEvent(書見台のページ送りの信号)。
     *
     * @return 変えてよいか
     */
    public static boolean lecternPowered(final Level level, final BlockPos pos, final BlockState state, final boolean isPowered) {
        if (state.getValue(net.minecraft.world.level.block.LecternBlock.POWERED) == isPowered) {
            return true;
        }

        return binaryRedstone(level, pos, isPowered);
    }

    private static boolean signFront = true;

    /** 看板の面。setMessages には渡らないので、書き換える側で置く。 */
    public static void signSide(final boolean front) {
        signFront = front;
    }

    /**
     * SignChangeEvent。文を組み立てたあと、返す直前。
     *
     * @return 返す文。取り消されたら元の文
     */
    public static net.minecraft.world.level.block.entity.SignText signChange(final net.minecraft.world.level.block.entity.SignBlockEntity sign,
                                                                              final net.minecraft.world.entity.player.Player player,
                                                                              final net.minecraft.world.level.block.entity.SignText original,
                                                                              final net.minecraft.world.level.block.entity.SignText text,
                                                                              final List<net.minecraft.server.network.FilteredText> lines) {
        if (!(player instanceof ServerPlayer serverPlayer) || !listening(org.bukkit.event.block.SignChangeEvent.getHandlerList())) {
            return text;
        }

        final List<net.kyori.adventure.text.Component> componentLines = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            componentLines.add(io.papermc.paper.adventure.PaperAdventure.asAdventure(text.getMessage(i, player.isTextFilteringEnabled())));
        }

        final org.bukkit.event.block.SignChangeEvent event = new org.bukkit.event.block.SignChangeEvent(
                bukkit(sign.getLevel(), sign.getBlockPos()), serverPlayer.getBukkitEntity(), new ArrayList<>(componentLines),
                signFront ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK);

        if (!event.callEvent()) {
            return original;
        }

        net.minecraft.world.level.block.entity.SignText result = text;
        final net.minecraft.network.chat.Component[] components = org.bukkit.craftbukkit.block.CraftSign.sanitizeLines(event.lines());

        for (int i = 0; i < components.length; i++) {
            if (!java.util.Objects.equals(componentLines.get(i), event.line(i))) {
                result = result.setMessage(i, components[i]);
            }
        }

        return result;
    }

    /**
     * PlayerSignCommandPreprocessEvent。看板のコマンドを実行する直前。
     *
     * <p>効かないもの: {@code setMessage} での差し替え、実行するプレイヤーの差し替え。
     *
     * @return 実行してよいか
     */
    public static boolean signCommand(final net.minecraft.world.level.block.entity.SignBlockEntity sign, final ServerLevel level,
                                      final net.minecraft.world.entity.player.Player player, final String command, final boolean isFrontText) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !listening(io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent.getHandlerList())) {
            return true;
        }

        final String commandLine = command.startsWith("/") ? command : "/" + command;

        return new io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent(serverPlayer.getBukkitEntity(), commandLine,
                new org.bukkit.craftbukkit.util.LazyPlayerSet(level.getServer()),
                (org.bukkit.block.Sign) bukkit(sign.getLevel(), sign.getBlockPos()).getState(),
                isFrontText ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK).callEvent();
    }

    private static BlockPos bellPos;
    private static java.util.Set<LivingEntity> bellHidden;

    /**
     * BellResonateEvent。光らせる襲撃者の一覧をプラグインが変えられる。vanilla の stream は
     * 変えられないので、外されたものは {@link #revealRaider} で止め、足されたものはここで光らせる。
     *
     * @param inRange vanilla と同じ条件で絞った襲撃者
     */
    public static void bellResonate(final Level level, final BlockPos pos, final List<LivingEntity> nearby,
                                    final java.util.function.Predicate<LivingEntity> inRange) {
        bellPos = pos;
        bellHidden = null;

        if (!listening(org.bukkit.event.block.BellResonateEvent.getHandlerList())) {
            return;
        }

        final List<LivingEntity> vanilla = nearby.stream().filter(inRange).toList();
        final List<org.bukkit.entity.LivingEntity> raiders = new ArrayList<>();

        for (final LivingEntity entity : vanilla) {
            raiders.add((org.bukkit.entity.LivingEntity) entity.getBukkitEntity());
        }

        final java.util.Set<LivingEntity> chosen = new java.util.HashSet<>(
                CraftEventFactory.handleBellResonateEvent(level, pos, raiders).toList());
        final java.util.Set<LivingEntity> hidden = new java.util.HashSet<>(vanilla);
        hidden.removeAll(chosen);
        bellHidden = hidden;

        for (final LivingEntity entity : chosen) {
            if (!vanilla.contains(entity) && revealRaider(entity)) {
                entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.GLOWING, 60));
            }
        }
    }

    /**
     * BellRevealRaiderEvent。1 体ずつ光らせる直前。
     *
     * @return 光らせてよいか
     */
    public static boolean revealRaider(final LivingEntity raider) {
        if (bellHidden != null && bellHidden.contains(raider)) {
            return false;
        }

        if (bellPos == null || !listening(io.papermc.paper.event.block.BellRevealRaiderEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.block.BellRevealRaiderEvent(bukkit(raider.level(), bellPos),
                (org.bukkit.entity.Raider) raider.getBukkitEntity()).callEvent();
    }

    // ------------------------------------------------------------ ミツバチ

    /**
     * EntityEnterBlockEvent。ハチが巣に入る直前。
     *
     * @return 入ってよいか
     */
    public static boolean enterHive(final BlockEntity hive, final net.minecraft.world.entity.animal.bee.Bee bee) {
        if (hive.getLevel() == null || !listening(org.bukkit.event.entity.EntityEnterBlockEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityEnterBlockEvent(bee.getBukkitEntity(), bukkit(hive.getLevel(), hive.getBlockPos())).callEvent();
    }

    // ------------------------------------------------------------ 醸造台

    private static int brewingFuelPower = -1;
    private static boolean brewingConsumes = true;

    /**
     * BrewingStandFuelEvent。燃料を使う直前。
     *
     * @return 使ってよいか(取り消されたら tick 全体を抜ける。Paper と同じ)
     */
    public static boolean brewingFuel(final Level level, final BlockPos pos, final ItemStack fuel) {
        brewingFuelPower = -1;
        brewingConsumes = true;

        if (!listening(org.bukkit.event.inventory.BrewingStandFuelEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.inventory.BrewingStandFuelEvent event = new org.bukkit.event.inventory.BrewingStandFuelEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(fuel), 20);

        if (!event.callEvent()) {
            return false;
        }

        brewingFuelPower = event.getFuelPower();
        brewingConsumes = event.getFuelPower() > 0 && event.isConsuming();

        return true;
    }

    /** vanilla が 20 を入れたあと、イベントの値に差し替える。 */
    public static int brewingFuelPower(final int vanilla) {
        return brewingFuelPower < 0 ? vanilla : brewingFuelPower;
    }

    /** 燃料を 1 減らしてよいか。 */
    public static boolean brewingConsumes() {
        return brewingConsumes;
    }

    /**
     * BrewingStartEvent。醸造を始めた直後(vanilla が 400 を入れたあと)。
     *
     * <p>効かないもの: {@code setRecipeBrewTime}(Paper だけの欄)。
     *
     * @return 醸造にかかる時間
     */
    public static int brewingStart(final Level level, final BlockPos pos, final ItemStack ingredient, final int vanilla) {
        if (!listening(org.bukkit.event.block.BrewingStartEvent.getHandlerList())) {
            return vanilla;
        }

        final org.bukkit.event.block.BrewingStartEvent event = new org.bukkit.event.block.BrewingStartEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(ingredient), vanilla);
        event.callEvent();

        return event.getBrewingTime();
    }

    /**
     * BrewEvent。醸造の結果を入れる直前。結果は vanilla と同じ {@code mix} で先に求める。
     *
     * @return 入れる結果(3 つ)。取り消されたら null。登録が無ければ null でなく空の一覧
     */
    public static List<ItemStack> brew(final Level level, final BlockPos pos, final net.minecraft.core.NonNullList<ItemStack> items,
                                       final ItemStack ingredient, final net.minecraft.world.item.alchemy.PotionBrewing potionBrewing) {
        if (!listening(org.bukkit.event.inventory.BrewEvent.getHandlerList())) {
            return List.of();
        }

        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity entity)
                || entity.getOwner() == null) {
            return List.of();
        }

        final List<org.bukkit.inventory.ItemStack> results = new ArrayList<>(3);

        for (int dest = 0; dest < 3; dest++) {
            results.add(dest, CraftItemStack.asCraftMirror(potionBrewing.mix(ingredient, items.get(dest))));
        }

        final org.bukkit.event.inventory.BrewEvent event = new org.bukkit.event.inventory.BrewEvent(
                bukkit(level, pos), (org.bukkit.inventory.BrewerInventory) entity.getOwner().getInventory(), results, entity.fuel);

        if (!event.callEvent()) {
            return null;
        }

        final List<ItemStack> out = new ArrayList<>(3);

        for (int dest = 0; dest < 3; dest++) {
            out.add(dest < results.size() ? CraftItemStack.asNMSCopy(results.get(dest)) : ItemStack.EMPTY);
        }

        return out;
    }

    /** vanilla が結果を入れたあと、プラグインが差し替えた結果で置き直す。 */
    public static void brewApply(final net.minecraft.core.NonNullList<ItemStack> items, final List<ItemStack> results) {
        if (results.isEmpty()) {
            return;
        }

        for (int dest = 0; dest < 3; dest++) {
            if (!ItemStack.matches(items.get(dest), results.get(dest))) {
                items.set(dest, results.get(dest));
            }
        }
    }

    // ------------------------------------------------------------ かまど

    private static boolean furnaceConsumesFuel = true;

    /**
     * FurnaceBurnEvent。燃料に火を付ける直前。
     *
     * <p>{@code setBurning(false)} は燃焼時間 0 として扱う(Paper は時間を入れたまま火を付けない)。
     *
     * @return 燃焼時間。取り消されたら -1(tick を抜ける。Paper と同じ)
     */
    public static int furnaceBurn(final ServerLevel level, final BlockPos pos, final ItemStack fuel, final int burnTime) {
        furnaceConsumesFuel = true;

        if (!listening(org.bukkit.event.inventory.FurnaceBurnEvent.getHandlerList())) {
            return burnTime;
        }

        final org.bukkit.event.inventory.FurnaceBurnEvent event = new org.bukkit.event.inventory.FurnaceBurnEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(fuel), burnTime);

        if (!event.callEvent()) {
            return -1;
        }

        furnaceConsumesFuel = event.willConsumeFuel();

        return event.isBurning() ? event.getBurnTime() : 0;
    }

    public static boolean furnaceConsumesFuel() {
        return furnaceConsumesFuel;
    }

    public static boolean listeningFurnaceStart() {
        return listening(org.bukkit.event.inventory.FurnaceStartSmeltEvent.getHandlerList());
    }

    /**
     * FurnaceStartSmeltEvent。焼き始め(cookingTimer が 0)のとき。登録があるときだけ呼ばれる。
     *
     * @return 焼くのにかかる時間
     */
    public static int furnaceStartSmelt(final ServerLevel level, final BlockPos pos, final ItemStack ingredient,
                                        final net.minecraft.world.item.crafting.RecipeHolder<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> recipe) {
        final org.bukkit.event.inventory.FurnaceStartSmeltEvent event = new org.bukkit.event.inventory.FurnaceStartSmeltEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(ingredient),
                (org.bukkit.inventory.CookingRecipe<?>) recipe.toBukkitRecipe(), recipe.value().cookingTime());
        event.callEvent();

        return event.getTotalCookTime();
    }

    /**
     * FurnaceSmeltEvent。焼き上がりを結果の枠に入れる直前(呼び出し側で)。
     *
     * @return 入れる結果。取り消されたら null。差し替えた結果が枠の物と重ねられないときも null
     */
    public static ItemStack furnaceSmelt(final ServerLevel level, final BlockPos pos, final net.minecraft.core.NonNullList<ItemStack> items,
                                         final ItemStack ingredient, final ItemStack result,
                                         final net.minecraft.world.item.crafting.RecipeHolder<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> recipe) {
        if (!listening(org.bukkit.event.inventory.FurnaceSmeltEvent.getHandlerList())) {
            return result;
        }

        final org.bukkit.event.inventory.FurnaceSmeltEvent event = new org.bukkit.event.inventory.FurnaceSmeltEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(ingredient), CraftItemStack.asBukkitCopy(result),
                (org.bukkit.inventory.CookingRecipe<?>) recipe.toBukkitRecipe());

        if (!event.callEvent()) {
            return null;
        }

        final ItemStack replaced = CraftItemStack.asNMSCopy(event.getResult());
        final ItemStack slot = items.get(2);

        if (!replaced.isEmpty() && !slot.isEmpty() && !CraftItemStack.asCraftMirror(slot).isSimilar(event.getResult())) {
            return null;
        }

        return replaced;
    }

    private static ServerLevel furnaceSmeltLevel;
    private static BlockPos furnaceSmeltPos;

    /** 焼き上がりを入れる前に、かまどの位置を置く(static な burn には渡らない)。 */
    public static void furnaceSmeltAt(final ServerLevel level, final BlockPos pos) {
        if (!listening(org.bukkit.event.inventory.FurnaceSmeltEvent.getHandlerList())) {
            return;
        }

        furnaceSmeltLevel = level;
        furnaceSmeltPos = pos;
    }

    /** 位置を控えから取る版。 */
    public static ItemStack furnaceSmelt(final net.minecraft.core.NonNullList<ItemStack> items, final ItemStack ingredient, final ItemStack result,
                                         final net.minecraft.world.item.crafting.RecipeHolder<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> recipe) {
        final ServerLevel level = furnaceSmeltLevel;
        final BlockPos pos = furnaceSmeltPos;
        furnaceSmeltLevel = null;
        furnaceSmeltPos = null;

        if (level == null || pos == null) {
            return result;
        }

        return furnaceSmelt(level, pos, items, ingredient, result, recipe);
    }

    private static BlockPos furnaceAt;

    /** 経験値を出す前に、かまどの位置を置く(static な createExperience には渡らない)。 */
    public static void furnaceExpAt(final BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockExpEvent.getHandlerList())) {
            return;
        }

        furnaceAt = pos;
    }

    /**
     * BlockExpEvent。かまどの経験値を出す直前。
     *
     * <p>効かないもの: FurnaceExtractEvent(取り出したプレイヤーと数は結果の枠の側にしか無い)。
     *
     * @return 出す経験値
     */
    public static int furnaceExp(final ServerLevel level, final int xp) {
        final BlockPos pos = furnaceAt;

        if (pos == null || !listening(org.bukkit.event.block.BlockExpEvent.getHandlerList())) {
            return xp;
        }

        final org.bukkit.event.block.BlockExpEvent event = new org.bukkit.event.block.BlockExpEvent(bukkit(level, pos), xp);
        event.callEvent();

        return event.getExpToDrop();
    }

    // ------------------------------------------------------------ 焚き火

    public static boolean listeningCook() {
        return listening(org.bukkit.event.block.BlockCookEvent.getHandlerList());
    }

    /**
     * BlockCookEvent。焚き火の焼き上がりを落とす直前。登録があるときだけ呼ばれる。
     *
     * @return 落とす物。取り消されたら null(tick を抜ける。Paper と同じ)
     */
    public static ItemStack cook(final Level level, final BlockPos pos, final ItemStack source, final ItemStack result,
                                 final org.bukkit.inventory.CookingRecipe<?> recipe) {
        final org.bukkit.event.block.BlockCookEvent event = new org.bukkit.event.block.BlockCookEvent(
                bukkit(level, pos), CraftItemStack.asCraftMirror(source), CraftItemStack.asBukkitCopy(result), recipe);

        if (!event.callEvent()) {
            return null;
        }

        return CraftItemStack.asNMSCopy(event.getResult());
    }

    /**
     * CampfireStartEvent。焚き火に置いた直後(vanilla が時間を入れたあと)。
     *
     * @return 焼くのにかかる時間
     */
    public static int campfireStart(final BlockEntity campfire, final ItemStack placeItem,
                                    final net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CampfireCookingRecipe> recipe, final int vanilla) {
        if (!listening(org.bukkit.event.block.CampfireStartEvent.getHandlerList())) {
            return vanilla;
        }

        final org.bukkit.event.block.CampfireStartEvent event = new org.bukkit.event.block.CampfireStartEvent(
                bukkit(campfire.getLevel(), campfire.getBlockPos()), CraftItemStack.asCraftMirror(placeItem),
                (org.bukkit.inventory.CampfireRecipe) recipe.toBukkitRecipe());
        event.callEvent();

        return event.getTotalCookTime();
    }

    // ------------------------------------------------------------ 怪しい砂・トライアルスポナー・宝物庫

    /**
     * EntityChangeBlockEvent(ブラシで怪しい砂を削る)。削る前。
     *
     * @param next 次の段の状態(壊れるなら中身のブロック)
     * @return 削ってよいか
     */
    public static boolean brush(final BlockEntity brushable, final LivingEntity user, final BlockState next) {
        if (!listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callEntityChangeBlockEvent(user, brushable.getBlockPos(), next);
    }

    /**
     * BlockDropItemEvent(怪しい砂の中身が出る)。世界に足す直前。
     *
     * @return 足してよいか(取り消されたか、一覧から外されたら足さない)
     */
    public static boolean brushDrop(final BlockEntity brushable, final Level level, final LivingEntity user, final ItemEntity entity) {
        if (!(user instanceof ServerPlayer player) || !listening(org.bukkit.event.block.BlockDropItemEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.Block block = bukkit(level, brushable.getBlockPos());
        final List<org.bukkit.entity.Item> items = new ArrayList<>();
        items.add((org.bukkit.entity.Item) entity.getBukkitEntity());
        final org.bukkit.event.block.BlockDropItemEvent event = new org.bukkit.event.block.BlockDropItemEvent(
                block, block.getState(), player.getBukkitEntity(), items);

        return event.callEvent() && items.contains(entity.getBukkitEntity());
    }

    /**
     * TrialSpawnerSpawnEvent。世界に足す直前。
     *
     * @return 足してよいか
     */
    public static boolean trialSpawn(final Entity entity, final BlockPos spawnerPos) {
        if (!listening(org.bukkit.event.entity.TrialSpawnerSpawnEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callTrialSpawnerSpawnEvent(entity, spawnerPos).isCancelled();
    }

    /**
     * BlockDispenseLootEvent。トライアルスポナーと宝物庫が報酬を出す直前。
     *
     * @return 出す物。取り消されたら null
     */
    public static List<ItemStack> dispenseLoot(final ServerLevel level, final BlockPos pos, final net.minecraft.world.entity.player.Player player,
                                               final List<ItemStack> loot, final net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> lootTable) {
        if (!listening(org.bukkit.event.block.BlockDispenseLootEvent.getHandlerList())) {
            return loot;
        }

        final org.bukkit.event.block.BlockDispenseLootEvent event = CraftEventFactory.callBlockDispenseLootEvent(
                level, pos, player, loot, level.getServer().reloadableRegistries().getLootTable(lootTable));

        if (event.isCancelled()) {
            return null;
        }

        return event.getDispensedLoot().stream().map(CraftItemStack::asNMSCopy).toList();
    }

    /**
     * VaultChangeStateEvent。状態を書く直前。
     *
     * <p>効かないもの: 鍵を入れたプレイヤーの紐付け(vanilla の setVaultState には渡らない)。
     * ACTIVE になるときだけ、繋がっているプレイヤーの 1 人を入れる(Paper と同じ)。
     *
     * @return 書いてよいか
     */
    public static boolean vaultState(final ServerLevel level, final BlockPos pos,
                                     final net.minecraft.world.level.block.entity.vault.VaultState from,
                                     final net.minecraft.world.level.block.entity.vault.VaultState to,
                                     final net.minecraft.world.level.block.entity.vault.VaultSharedData sharedData) {
        if (!listening(io.papermc.paper.event.block.VaultChangeStateEvent.getHandlerList())) {
            return true;
        }

        org.bukkit.entity.Player associated = null;

        if (to == net.minecraft.world.level.block.entity.vault.VaultState.ACTIVE) {
            final java.util.Set<java.util.UUID> connected = sharedData.getConnectedPlayers();

            if (!connected.isEmpty()) {
                associated = level.getCraftServer().getPlayer(connected.iterator().next());
            }
        }

        return new io.papermc.paper.event.block.VaultChangeStateEvent(bukkit(level, pos), associated,
                org.bukkit.craftbukkit.block.data.CraftBlockData.toBukkit(from, org.bukkit.block.data.type.Vault.State.class),
                org.bukkit.craftbukkit.block.data.CraftBlockData.toBukkit(to, org.bukkit.block.data.type.Vault.State.class)).callEvent();
    }

    /**
     * VaultDisplayItemEvent。飾るアイテムを決めた直後。
     *
     * @return 飾る物。取り消されたら null
     */
    public static ItemStack vaultDisplay(final ServerLevel level, final BlockPos pos, final ItemStack displayItem) {
        if (!listening(org.bukkit.event.block.VaultDisplayItemEvent.getHandlerList())) {
            return displayItem;
        }

        final org.bukkit.event.block.VaultDisplayItemEvent event = CraftEventFactory.callVaultDisplayItemEvent(level, pos, displayItem);

        if (event.isCancelled()) {
            return null;
        }

        return CraftItemStack.asNMSCopy(event.getDisplayItem());
    }

    // ------------------------------------------------------------ 錠

    public static boolean listeningLockCheck() {
        return listening(io.papermc.paper.event.block.BlockLockCheckEvent.getHandlerList());
    }

    // ------------------------------------------------------------ ビーコン

    /** BeaconActivatedEvent / BeaconDeactivatedEvent。段数が 0 と正の間を跨いだとき。 */
    public static void beaconLevels(final Level level, final BlockPos pos, final int previousLevels, final int levels) {
        if (previousLevels <= 0 && levels > 0) {
            if (listening(io.papermc.paper.event.block.BeaconActivatedEvent.getHandlerList())) {
                new io.papermc.paper.event.block.BeaconActivatedEvent(bukkit(level, pos)).callEvent();
            }
        } else if (previousLevels > 0 && levels <= 0) {
            beaconDeactivated(level, pos);
        }
    }

    public static void beaconDeactivated(final Level level, final BlockPos pos) {
        if (level == null || !listening(io.papermc.paper.event.block.BeaconDeactivatedEvent.getHandlerList())) {
            return;
        }

        new io.papermc.paper.event.block.BeaconDeactivatedEvent(bukkit(level, pos)).callEvent();
    }

    /**
     * BeaconEffectEvent。1 人ずつ効果を掛ける直前。
     *
     * <p>{@code setEffect} で差し替えられたときは、発火層が差し替えた効果を掛けて false を返す
     * (vanilla の行は飛ばす)。差し替えが無ければ vanilla の行がそのまま掛ける。
     *
     * @return vanilla の行で掛けてよいか
     */
    public static boolean beaconEffect(final Level level, final BlockPos pos, final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, final int duration, final int amplifier, final boolean primary) {
        if (!listening(com.destroystokyo.paper.event.block.BeaconEffectEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.potion.PotionEffect vanilla = org.bukkit.craftbukkit.potion.CraftPotionUtil.toBukkit(
                new net.minecraft.world.effect.MobEffectInstance(effect, duration, amplifier, true, true));
        final com.destroystokyo.paper.event.block.BeaconEffectEvent event = new com.destroystokyo.paper.event.block.BeaconEffectEvent(
                bukkit(level, pos), vanilla, (org.bukkit.entity.Player) player.getBukkitEntity(), primary);

        if (!event.callEvent()) {
            return false;
        }

        if (vanilla.equals(event.getEffect())) {
            return true;
        }

        player.addEffect(org.bukkit.craftbukkit.potion.CraftPotionUtil.fromBukkit(event.getEffect()));

        return false;
    }

    // ------------------------------------------------------------ ハチの巣を刈る

    /**
     * PlayerShearBlockEvent。ハチの巣をハサミで刈る直前。
     *
     * <p>効かないもの: {@code getDrops} の書き換え(落とすのは vanilla のルートテーブル)。
     * イベントの drops は空(落とす物は vanilla が決めるまで分からない)。
     *
     * @return 刈ってよいか
     */
    public static boolean shearBlock(final net.minecraft.world.entity.player.Player player, final Level level, final BlockPos pos,
                                     final ItemStack itemStack, final net.minecraft.world.InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !listening(io.papermc.paper.event.block.PlayerShearBlockEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.block.PlayerShearBlockEvent(serverPlayer.getBukkitEntity(), bukkit(level, pos),
                CraftItemStack.asCraftMirror(itemStack), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), new ArrayList<>()).callEvent();
    }

    // ------------------------------------------------------------ 生長と広がり(置く前に発火する形)

    /** 登録が無ければ true。重い式を引数に渡す前に短絡させるためのもの。 */
    public static boolean silentSpread() {
        return !listening(org.bukkit.event.block.BlockSpreadEvent.getHandlerList());
    }

    /** 登録が無ければ true。上と同じ。 */
    public static boolean silentGrow() {
        return !listening(org.bukkit.event.block.BlockGrowEvent.getHandlerList());
    }

    /**
     * BlockSpreadEvent。置く前。置く先の状態が分かっているので控えは要らない。
     *
     * <p>Paper は {@code handleBlockSpreadEvent} が発火と設置の両方を行うが、Shifu は
     * 発火だけを行い、設置は vanilla の行に任せる。
     *
     * @return 置いてよいか
     */
    public static boolean spreadTo(final LevelAccessor level, final BlockPos source, final BlockPos pos, final BlockState newState) {
        if (!(level instanceof Level) || !listening(org.bukkit.event.block.BlockSpreadEvent.getHandlerList())) {
            return true;
        }

        final CraftBlockState snapshot = CraftBlockStates.getBlockState(level, pos);
        snapshot.setData(newState);

        return new org.bukkit.event.block.BlockSpreadEvent(snapshot.getBlock(), bukkit(level, source), snapshot).callEvent();
    }

    /**
     * BlockGrowEvent。置く前。
     *
     * @return 置いてよいか
     */
    public static boolean growAt(final LevelAccessor level, final BlockPos pos, final BlockState newState) {
        if (!(level instanceof Level) || !listening(org.bukkit.event.block.BlockGrowEvent.getHandlerList())) {
            return true;
        }

        final CraftBlockState snapshot = CraftBlockStates.getBlockState(level, pos);
        snapshot.setData(newState);

        return new org.bukkit.event.block.BlockGrowEvent(snapshot.getBlock(), snapshot).callEvent();
    }

    /**
     * アメジストの芽。最初の 1 段だけ広がり(BlockSpreadEvent)で、その先は生長(BlockGrowEvent)。
     * Paper と同じ分け方。
     *
     * @return 置いてよいか
     */
    public static boolean amethystGrow(final LevelAccessor level, final BlockPos source, final BlockPos pos,
                                       final BlockState newState, final Block nextStage) {
        return nextStage == net.minecraft.world.level.block.Blocks.SMALL_AMETHYST_BUD
                ? spreadTo(level, source, pos, newState)
                : growAt(level, pos, newState);
    }

    private static BlockPos spreadSource;

    /**
     * 次の広がりの元の位置を置く。Paper がメソッドの署名に足して渡している値
     * ({@code SculkVeinBlock.originPos}、{@code SpeleothemBlock.source})の代わり。
     * 登録が無ければ何も置かない。
     */
    public static void spreadSource(final BlockPos source) {
        if (silentSpread()) {
            return;
        }

        spreadSource = source;
    }

    /**
     * 置いた元の位置を取り出して、BlockSpreadEvent を出す。元が置かれていなければ
     * 置き先そのものを元とみなす。
     *
     * @return 置いてよいか
     */
    public static boolean spreadFromSource(final LevelAccessor level, final BlockPos pos, final BlockState newState) {
        final BlockPos source = spreadSource;
        spreadSource = null;

        return spreadTo(level, source == null ? pos : source, pos, newState);
    }

    // ------------------------------------------------------------ 大釜の水位

    private static Entity cauldronEntity;
    private static org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason cauldronReason;

    /**
     * 次の {@code LayeredCauldronBlock.lowerFillLevel} の理由と主を置く。Paper は
     * メソッドの署名に足して渡しているが、Shifu は呼ぶ直前に置く。
     */
    public static void cauldronCause(final Entity entity, final org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason reason) {
        if (!listening(org.bukkit.event.block.CauldronLevelChangeEvent.getHandlerList())) {
            return;
        }

        cauldronEntity = entity;
        cauldronReason = reason;
    }

    /**
     * CauldronLevelChangeEvent(水位が 1 段下がる)。vanilla が置いたあと。
     * 理由が置かれていなければ UNKNOWN。
     *
     * <p>ただし: vanilla は瓶や防具の書き換えを先に済ませてから水位を下げるので、
     * 取り消しても手元の物は戻らない(Paper は下げる判定を先に行う)。
     */
    public static void cauldronLowered(final Level level, final BlockPos pos, final CraftBlockState before) {
        final Entity entity = cauldronEntity;
        final org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason reason = cauldronReason;
        cauldronEntity = null;
        cauldronReason = null;

        ShifuEvents.cauldronLevelChange(level, pos, before, entity,
                reason == null ? org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.UNKNOWN : reason);
    }

    /** 大釜の水位のイベントの控えを取る。{@link ShifuEvents#blockChangeBefore} の言い換え。 */
    public static CraftBlockState cauldronBefore(final Level level, final BlockPos pos) {
        return ShifuEvents.blockChangeBefore(level, pos, org.bukkit.event.block.CauldronLevelChangeEvent.getHandlerList());
    }

    // ------------------------------------------------------------ 雷が銅を戻す

    private static Entity lightningStriker;

    /** 次の {@code clearCopperOnLightningStrike} の落雷を置く(vanilla の static メソッドには渡らない)。 */
    public static void lightningStriker(final Entity lightning) {
        if (!listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return;
        }

        lightningStriker = lightning;
    }

    /**
     * EntityChangeBlockEvent(落雷が酸化した銅を戻す)。置く前。
     *
     * @return 戻してよいか
     */
    public static boolean lightningClearsCopper(final Level level, final BlockPos pos, final BlockState newState) {
        final Entity lightning = lightningStriker;
        lightningStriker = null;

        if (lightning == null || !listening(org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callEntityChangeBlockEvent(lightning, pos, newState);
    }
}
