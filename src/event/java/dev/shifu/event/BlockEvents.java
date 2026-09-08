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



    private static boolean listeningPhysical() {
        return listening(org.bukkit.event.player.PlayerInteractEvent.getHandlerList())
                || listening(org.bukkit.event.entity.EntityInteractEvent.getHandlerList());
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


    /** 入れた側で、取り消されたときはアイテムを減らさない。 */
    public static boolean compostConsumes() {
        final boolean cancelled = compostNotConsumed;
        compostNotConsumed = false;

        return !cancelled;
    }

    // ------------------------------------------------------------ ホッパー・ドロッパー




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





    // ------------------------------------------------------------ トリップワイヤーフック


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


    // ------------------------------------------------------------ 流体

    /**
     * BlockFromToEvent。流体が隣へ広がる直前。
     *
     * @return 広がってよいか
     */
    // 1.20.6 の FlowingFluid.spread は Level を受ける。中で使うのは CraftBlock.at だけなので
    // LevelAccessor で足りる
    public static boolean fromTo(final net.minecraft.world.level.LevelAccessor level, final BlockPos from, final Direction direction) {
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


    // ------------------------------------------------------------ ポータル




    public static boolean listeningPortal() {
        return listening(org.bukkit.event.entity.EntityPortalEvent.getHandlerList())
                || listening(org.bukkit.event.player.PlayerPortalEvent.getHandlerList());
    }

    // ------------------------------------------------------------ 書見台・看板・鐘



    private static boolean signFront = true;

    /** 看板の面。setMessages には渡らないので、書き換える側で置く。 */
    public static void signSide(final boolean front) {
        signFront = front;
    }



    private static BlockPos bellPos;
    private static java.util.Set<LivingEntity> bellHidden;



    // ------------------------------------------------------------ ミツバチ


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


    // ------------------------------------------------------------ ハチの巣を刈る


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
