// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockSource;
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

    /**
     * EntityCombustByBlockEvent。火のブロックが燃やす直前。
     *
     * @return 燃やしてよいか。取り消されたら Paper と同じく残り火の時間を 1 戻す
     */
    public static boolean combustByBlock(final net.minecraft.world.entity.Entity entity, final BlockPos pos) {
        if (!ShifuEvents.listening(org.bukkit.event.entity.EntityCombustByBlockEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.entity.EntityCombustByBlockEvent event =
                new org.bukkit.event.entity.EntityCombustByBlockEvent(
                        bukkit(entity.level, pos), entity.getBukkitEntity(), 8);

        if (event.callEvent()) {
            // 効かないもの: 燃える長さ(vanilla の 8 秒のまま)
            return true;
        }

        entity.setRemainingFireTicks(entity.getRemainingFireTicks() - 1);

        return false;
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
                bukkit(source.getLevel(), source.getPos()), craftItem.clone(), CraftVector.toBukkit(velocity)).callEvent();
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
     * ディスペンサーのハサミの側で、刈る生き物の位置は分かるがディスペンサーの位置と
     * 使った道具が分からないので置く。1.20.6 の {@code tryShearLivingEntity} は
     * 位置しか受け取らない(Paper は引数を 2 つ増やしている)。
     */
    private static BlockPos shearingDispenser;
    private static ItemStack shearingTool;

    public static void shearing(final BlockSource source, final ItemStack tool) {
        if (!listening(org.bukkit.event.block.BlockShearEntityEvent.getHandlerList())) {
            return;
        }

        shearingDispenser = source.getPos();
        shearingTool = tool;
    }

    public static BlockPos shearingDispenser(final BlockPos fallback) {
        return shearingDispenser == null ? fallback : shearingDispenser;
    }

    public static ItemStack shearingTool(final ItemStack fallback) {
        return shearingTool == null ? fallback : shearingTool;
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
    // 1.20.6 の FlowingFluid.spreadTo は Level を受ける
    public static BlockState fluidLevelChange(final net.minecraft.world.level.Level level, final BlockPos pos, final BlockState newState) {
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



    /**
     * SignChangeEvent。1.19.4 は看板に面が無く、行は ServerGamePacketListenerImpl.updateSignText で
     * 1 行ずつ setMessage される。その手前で発火して、書き換えた行を返す。
     *
     * @return 書き込む行。取り消されたら null
     */
    public static java.util.List<net.minecraft.server.network.FilteredText> signChange(
            final net.minecraft.world.level.block.entity.SignBlockEntity sign,
            final net.minecraft.server.level.ServerPlayer player,
            final java.util.List<net.minecraft.server.network.FilteredText> lines) {
        if (!ShifuEvents.listening(org.bukkit.event.block.SignChangeEvent.getHandlerList())) {
            return lines;
        }

        final java.util.List<net.kyori.adventure.text.Component> componentLines = new java.util.ArrayList<>();

        for (final net.minecraft.server.network.FilteredText line : lines) {
            componentLines.add(net.kyori.adventure.text.Component.text(
                    player.isTextFilteringEnabled() ? line.filteredOrEmpty() : line.raw()));
        }

        final org.bukkit.event.block.SignChangeEvent event = new org.bukkit.event.block.SignChangeEvent(
                bukkit(sign.getLevel(), sign.getBlockPos()), player.getBukkitEntity(),
                new java.util.ArrayList<>(componentLines));

        if (!event.callEvent()) {
            return null;
        }

        final java.util.List<net.minecraft.server.network.FilteredText> result = new java.util.ArrayList<>(lines);

        for (int i = 0; i < result.size() && i < event.lines().size(); i++) {
            if (!java.util.Objects.equals(componentLines.get(i), event.line(i))) {
                result.set(i, net.minecraft.server.network.FilteredText.passThrough(
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.line(i))));
            }
        }

        return result;
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
    // 1.20.6 の serverTick は Level を受ける
    public static int furnaceBurn(final net.minecraft.world.level.Level level, final BlockPos pos, final ItemStack fuel, final int burnTime) {
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



    private static net.minecraft.world.level.Level furnaceSmeltLevel;
    private static BlockPos furnaceSmeltPos;

    /**
     * 焼き上がりを入れる前に、かまどの位置を置く(static な burn には渡らない)。
     * 1.20.6 の serverTick は Level を受ける。
     */
    public static void furnaceSmeltAt(final net.minecraft.world.level.Level level, final BlockPos pos) {
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

    /** 結果の枠から取り出した人。FurnaceExtractEvent に載せる。 */
    private static ServerPlayer furnaceTaker;

    /** 取り出した数と品。 */
    private static net.minecraft.world.item.ItemStack furnaceTaken;

    private static int furnaceCount;

    /**
     * かまどの結果を取り出した人を控える。{@code checkTakeAchievements} の頭。
     *
     * <p>控えるのも「登録があるとき」だけ。読んだあとは消す。
     */
    public static void furnaceTakeBy(final net.minecraft.world.entity.player.Player player,
                                     final net.minecraft.world.item.ItemStack stack, final int count) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !listening(org.bukkit.event.block.BlockExpEvent.getHandlerList())) {
            return;
        }

        furnaceTaker = serverPlayer;
        furnaceTaken = stack;
        furnaceCount = count;
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
     * TargetHitEvent。的に投射物が当たった。
     *
     * @return プラグインが決めた強さ。取り消されたら -1
     */
    public static int targetHit(final LevelAccessor level,
                                final net.minecraft.world.phys.BlockHitResult hitResult,
                                final net.minecraft.world.entity.Entity entity, final int strength) {
        if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile)
                || !ShifuEvents.listening(io.papermc.paper.event.block.TargetHitEvent.getHandlerList())) {
            return strength;
        }

        final io.papermc.paper.event.block.TargetHitEvent event = new io.papermc.paper.event.block.TargetHitEvent(
                (org.bukkit.entity.Projectile) entity.getBukkitEntity(),
                org.bukkit.craftbukkit.block.CraftBlock.at(level, hitResult.getBlockPos()),
                org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(hitResult.getDirection()), strength);

        return event.callEvent() ? event.getSignalStrength() : -1;
    }


    /**
     * MoistureChangeEvent。畑の湿り気を書き換える直前。
     *
     * @return 書き換えてよいか
     */
    public static boolean moistureChange(final net.minecraft.world.level.Level level,
                                         final net.minecraft.core.BlockPos pos,
                                         final net.minecraft.world.level.block.state.BlockState state,
                                         final int moisture) {
        if (!ShifuEvents.listening(org.bukkit.event.block.MoistureChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.BlockState snapshot =
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos).getState();
        snapshot.setBlockData(org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(
                state.setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, Integer.valueOf(moisture))));

        return new org.bukkit.event.block.MoistureChangeEvent(snapshot.getBlock(), snapshot).callEvent();
    }

    /** BlockCanBuildEvent に登録があるか。vanilla の判定を組み直す前に見る。 */
    public static boolean canBuildListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BlockCanBuildEvent.getHandlerList());
    }



    /** BellRingEvent。鐘が鳴る直前。 */
    public static boolean bellRing(final net.minecraft.world.level.Level level,
                                   final net.minecraft.core.BlockPos pos,
                                   final net.minecraft.core.Direction direction,
                                   final net.minecraft.world.entity.Entity entity) {
        if (!ShifuEvents.listening(io.papermc.paper.event.block.BellRingEvent.getHandlerList())) {
            return true;
        }

        return org.bukkit.craftbukkit.event.CraftEventFactory.handleBellRingEvent(level, pos, direction, entity);
    }

    /** BellResonateEvent か BellRevealRaiderEvent に登録があるか。 */
    public static boolean bellResonateListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BellResonateEvent.getHandlerList())
                || ShifuEvents.listening(io.papermc.paper.event.block.BellRevealRaiderEvent.getHandlerList());
    }

    /**
     * BellResonateEvent と BellRevealRaiderEvent。鐘の共鳴で襲撃者が光る直前。
     *
     * <p>登録があるときだけ vanilla の絞り込みを組み直して呼ぶ。
     * 光らせる中身({@code addEffect})は vanilla の {@code glow} と同じ。
     */
    public static void bellResonate(final net.minecraft.world.level.Level level,
                                    final net.minecraft.core.BlockPos pos,
                                    final java.util.List<net.minecraft.world.entity.LivingEntity> heard) {
        final java.util.List<org.bukkit.entity.LivingEntity> raiders = new java.util.ArrayList<>();

        for (final net.minecraft.world.entity.LivingEntity entity : heard) {
            if (entity.isAlive() && !entity.isRemoved() && pos.closerToCenterThan(entity.position(), 48.0)
                    && entity.getType().is(net.minecraft.tags.EntityTypeTags.RAIDERS)) {
                raiders.add((org.bukkit.entity.LivingEntity) entity.getBukkitEntity());
            }
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.handleBellResonateEvent(level, pos, raiders)
                .forEach(entity -> {
                    if (ShifuEvents.listening(io.papermc.paper.event.block.BellRevealRaiderEvent.getHandlerList())
                            && !new io.papermc.paper.event.block.BellRevealRaiderEvent(
                                    org.bukkit.craftbukkit.block.CraftBlock.at(entity.level, pos),
                                    (org.bukkit.entity.Raider) entity.getBukkitEntity()).callEvent()) {
                        return;
                    }

                    entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.GLOWING, 60));
                });
    }

    /**
     * CompostItemEvent と EntityCompostItemEvent。コンポスターの中身が 1 段上がる直前。
     *
     * <p>Paper は上がらないときも出す。vanilla はその判定を乱数ごと 1 つの式で
     * 済ませていて、外から作り直すと乱数を 2 度引く。<b>出しているのは上がるときだけ。</b>
     *
     * @return 上げてよいか
     */
    public static boolean compostItem(final net.minecraft.world.entity.Entity user,
                                      final net.minecraft.world.level.LevelAccessor level,
                                      final net.minecraft.core.BlockPos pos,
                                      final net.minecraft.world.item.ItemStack stack) {
        final boolean plain = ShifuEvents.listening(io.papermc.paper.event.block.CompostItemEvent.getHandlerList());
        final boolean byEntity = user != null
                && ShifuEvents.listening(io.papermc.paper.event.entity.EntityCompostItemEvent.getHandlerList());

        if (!plain && !byEntity) {
            return true;
        }

        final org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        final org.bukkit.inventory.ItemStack item =
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack);
        final io.papermc.paper.event.block.CompostItemEvent event = user == null
                ? new io.papermc.paper.event.block.CompostItemEvent(block, item, true)
                : new io.papermc.paper.event.entity.EntityCompostItemEvent(user.getBukkitEntity(), block, item, true);

        return event.callEvent() && event.willRaiseLevel();
    }





    /**
     * InventoryPickupItemEvent。ホッパーが落ちている物を吸う直前。
     *
     * <p>渡す入れ物は {@code CraftInventory} をそのまま作る。Paper は
     * 二重チェストなどを見分けているが、その振り分けは 1.20.6 の木に無い。
     */
    public static boolean inventoryPickup(final net.minecraft.world.Container container,
                                          final net.minecraft.world.entity.item.ItemEntity item) {
        if (!ShifuEvents.listening(org.bukkit.event.inventory.InventoryPickupItemEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.inventory.InventoryPickupItemEvent(
                new org.bukkit.craftbukkit.inventory.CraftInventory(container),
                (org.bukkit.entity.Item) item.getBukkitEntity()).callEvent();
    }

    /** BrewingStartEvent に登録があるか。 */
    public static boolean brewingStartListening() {
        return ShifuEvents.listening(org.bukkit.event.block.BrewingStartEvent.getHandlerList());
    }

    /**
     * BrewingStartEvent。醸造が始まる直後。
     *
     * @return 醸造にかける時間
     */
    public static int brewingStart(final net.minecraft.world.level.Level level, final net.minecraft.core.BlockPos pos,
                                   final net.minecraft.world.item.ItemStack ingredient, final int brewTime) {
        final org.bukkit.event.block.BrewingStartEvent event = new org.bukkit.event.block.BrewingStartEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(ingredient), brewTime);
        event.callEvent();

        return event.getTotalBrewTime();
    }

    /** CampfireStartEvent に登録があるか。 */
    public static boolean campfireStartListening() {
        return ShifuEvents.listening(org.bukkit.event.block.CampfireStartEvent.getHandlerList());
    }









    /** DragonEggFormEvent。ドラゴンを倒して卵を置く直前。 */
    public static boolean dragonEggForm(final net.minecraft.server.level.ServerLevel level,
                                        final net.minecraft.core.BlockPos pos,
                                        final net.minecraft.world.level.dimension.end.EndDragonFight fight) {
        if (!ShifuEvents.listening(io.papermc.paper.event.block.DragonEggFormEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.craftbukkit.block.CraftBlockState state =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        state.setData(net.minecraft.world.level.block.Blocks.DRAGON_EGG.defaultBlockState());

        return new io.papermc.paper.event.block.DragonEggFormEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), state,
                new org.bukkit.craftbukkit.boss.CraftDragonBattle(fight)).callEvent();
    }


    /**
     * BlockPistonExtendEvent と BlockPistonRetractEvent。動かす位置が決まった直後。
     *
     * <p>Paper は粘着でないピストンが空を引くときも RetractEvent を出す。
     * vanilla のその位置は分岐の外なので、<b>出しているのは動かす物があるときだけ。</b>
     *
     * @return 動かしてよいか
     */
    public static boolean pistonMove(final net.minecraft.world.level.Level level,
                                     final net.minecraft.core.BlockPos pos,
                                     final net.minecraft.core.Direction facing, final boolean retract,
                                     final java.util.List<net.minecraft.core.BlockPos> push,
                                     final java.util.List<net.minecraft.core.BlockPos> destroy) {
        final org.bukkit.event.HandlerList handlers = retract
                ? org.bukkit.event.block.BlockPistonRetractEvent.getHandlerList()
                : org.bukkit.event.block.BlockPistonExtendEvent.getHandlerList();

        if (!ShifuEvents.listening(handlers)) {
            return true;
        }

        final java.util.List<org.bukkit.block.Block> blocks = new java.util.ArrayList<>();

        for (final net.minecraft.core.BlockPos one : push) {
            blocks.add(org.bukkit.craftbukkit.block.CraftBlock.at(level, one));
        }

        for (final net.minecraft.core.BlockPos one : destroy) {
            blocks.add(org.bukkit.craftbukkit.block.CraftBlock.at(level, one));
        }

        final org.bukkit.block.Block piston = org.bukkit.craftbukkit.block.CraftBlock.at(level, pos);
        final org.bukkit.block.BlockFace face = org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(facing);

        return retract
                ? new org.bukkit.event.block.BlockPistonRetractEvent(piston, blocks, face).callEvent()
                : new org.bukkit.event.block.BlockPistonExtendEvent(piston, blocks, face).callEvent();
    }


    /**
     * WorldBorderBoundsChangeEvent。世界の境界の大きさを変える直前。
     *
     * @return 変えてよいか
     */
    public static boolean borderBounds(final net.minecraft.server.level.ServerLevel level,
                                       final double from, final double to, final long time) {
        if (level == null
                || !ShifuEvents.listening(
                        io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.getHandlerList())) {
            return true;
        }

        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type type = time > 0
                ? io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.STARTED_MOVE
                : io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE;

        return new io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent(level.getWorld(),
                level.getWorld().getWorldBorder(), type, from, to, time).callEvent();
    }

    /** WorldBorderCenterChangeEvent。世界の境界の中心を変える直前。 */
    public static boolean borderCenter(final net.minecraft.server.level.ServerLevel level,
                                       final double oldX, final double oldZ,
                                       final double newX, final double newZ) {
        if (level == null
                || !ShifuEvents.listening(
                        io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent(level.getWorld(),
                level.getWorld().getWorldBorder(),
                new org.bukkit.Location(level.getWorld(), oldX, 0.0, oldZ),
                new org.bukkit.Location(level.getWorld(), newX, 0.0, newZ)).callEvent();
    }




    /**
     * BlockDispenseArmorEvent。ディスペンサーが防具を着せる直前。
     *
     * <p>差し替えた品({@code setItem})は vanilla の行が持つので使っていない。
     */
    public static boolean dispenseArmor(final net.minecraft.core.BlockSource pointer,
                                        final net.minecraft.world.item.ItemStack armor,
                                        final net.minecraft.world.entity.LivingEntity target) {
        if (!ShifuEvents.listening(org.bukkit.event.block.BlockDispenseArmorEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.block.BlockDispenseArmorEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(pointer.getLevel(), pointer.getPos()),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(armor.copyWithCount(1)).clone(),
                (org.bukkit.craftbukkit.entity.CraftLivingEntity) target.getBukkitEntity()).callEvent();
    }





    /** WorldBorderBoundsChangeFinishEvent。境界の移動が終わった直後。 */
    public static void borderFinish(final net.minecraft.server.level.ServerLevel level,
                                    final double from, final double to, final double duration) {
        if (level == null
                || !ShifuEvents.listening(
                        io.papermc.paper.event.world.border.WorldBorderBoundsChangeFinishEvent.getHandlerList())) {
            return;
        }

        new io.papermc.paper.event.world.border.WorldBorderBoundsChangeFinishEvent(level.getWorld(),
                level.getWorld().getWorldBorder(), from, to, duration).callEvent();
    }







    // ------------------------------------------------------------ 木の育ち

    /**
     * 木が育つあいだのブロックを控える。{@code Level.setBlock} の先頭。
     *
     * <p>StructureGrowEvent と BlockFertilizeEvent は「どの位置が変わったか」を
     * プラグインに渡し、外された分は置かない。位置は生成の中で決まるので、
     * 置く手前で押さえるほかない。押さえるのは
     * {@code captureTreeGeneration} を立てているあいだだけで、立てるのは
     * 聞き手がいるときだけなので、いないときは真偽値 1 つの比較で終わる。
     *
     * <p>読んだ位置(Paper 1.20.6):
     *   Paper-Server src/main/java/net/minecraft/world/level/Level.java:898
     */
    public static boolean captureTreeBlock(final Level level, final BlockPos pos,
                                           final BlockState state, final int flags) {
        CraftBlockState captured = level.capturedBlockStates.get(pos);

        if (captured == null) {
            captured = org.bukkit.craftbukkit.block.CapturedBlockState.getTreeBlockState(level, pos, flags);
            level.capturedBlockStates.put(pos.immutable(), captured);
        }

        captured.setData(state);
        captured.setFlag(flags);

        return true;
    }

    /**
     * 育てようとしている木の種類を控える。{@code TreeGrower.growTree} の中、
     * 生成物が決まった直後。StructureGrowEvent が種類を要る。
     *
     * <p>控えている最中でなければ何もしない。Paper は知らない生成物で例外を
     * 投げるが、MOD が足した木で落ちるので、Shifu は種類を空のままにする
     * (StructureGrowEvent は出ず、BlockFertilizeEvent だけが出る)。
     *
     * <p>読んだ位置(Paper 1.20.6):
     *   Paper-Server src/main/java/net/minecraft/world/level/block/grower/TreeGrower.java:175
     */
    public static void treeType(final Level level,
                                final net.minecraft.core.Holder<
                                        net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> holder) {
        if (!level.captureTreeGeneration) {
            return;
        }

        final net.minecraft.resources.ResourceKey<
                net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> key =
                        holder.unwrapKey().orElse(null);

        net.minecraft.world.level.block.SaplingBlock.treeType = treeTypeOf(key);
    }


    /**
     * 木の育ちの控えを始める。育てる呼び出しの直前。
     *
     * <p>既に外側が控えているときは false を返す。骨粉から苗木を育てるときに
     * 二重に発火しないため。
     */
    public static boolean armTreeGrow(final Level level, final boolean bonemeal) {
        if (level.captureTreeGeneration) {
            return false;
        }

        if (!listening(org.bukkit.event.world.StructureGrowEvent.getHandlerList())
                && !(bonemeal && listening(org.bukkit.event.block.BlockFertilizeEvent.getHandlerList()))) {
            return false;
        }

        level.captureTreeGeneration = true;

        return true;
    }

    /** StructureGrowEvent。苗木が自分で育ったとき。 */
    public static void treeGrow(final Level level, final BlockPos pos) {
        fireGrow(level, pos, null, false, false);
    }

    /** StructureGrowEvent と BlockFertilizeEvent。骨粉をまいたとき。 */
    public static void fertilize(final Level level, final BlockPos pos, final Entity user) {
        fireGrow(level, pos, user instanceof ServerPlayer player ? player : null, true, true);
    }

    /** 木の feature の鍵から Bukkit の TreeType を引く。無ければ null(苗木でない育ち)。 */
    private static org.bukkit.TreeType treeTypeOf(
            final net.minecraft.resources.ResourceKey<
                    net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> key) {
        if (key == null) {
            return null;
        }

        if (key == net.minecraft.data.worldgen.features.TreeFeatures.OAK
                || key == net.minecraft.data.worldgen.features.TreeFeatures.OAK_BEES_005) {
            return org.bukkit.TreeType.TREE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.HUGE_RED_MUSHROOM) {
            return org.bukkit.TreeType.RED_MUSHROOM;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.HUGE_BROWN_MUSHROOM) {
            return org.bukkit.TreeType.BROWN_MUSHROOM;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_TREE) {
            return org.bukkit.TreeType.COCOA_TREE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_TREE_NO_VINE) {
            return org.bukkit.TreeType.SMALL_JUNGLE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.PINE) {
            return org.bukkit.TreeType.TALL_REDWOOD;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.SPRUCE) {
            return org.bukkit.TreeType.REDWOOD;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.ACACIA) {
            return org.bukkit.TreeType.ACACIA;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.BIRCH
                || key == net.minecraft.data.worldgen.features.TreeFeatures.BIRCH_BEES_005) {
            return org.bukkit.TreeType.BIRCH;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.SUPER_BIRCH_BEES_0002) {
            return org.bukkit.TreeType.TALL_BIRCH;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.SWAMP_OAK) {
            return org.bukkit.TreeType.SWAMP;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.FANCY_OAK
                || key == net.minecraft.data.worldgen.features.TreeFeatures.FANCY_OAK_BEES_005) {
            return org.bukkit.TreeType.BIG_TREE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.JUNGLE_BUSH) {
            return org.bukkit.TreeType.JUNGLE_BUSH;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK) {
            return org.bukkit.TreeType.DARK_OAK;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.MEGA_SPRUCE) {
            return org.bukkit.TreeType.MEGA_REDWOOD;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.MEGA_JUNGLE_TREE) {
            return org.bukkit.TreeType.JUNGLE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.AZALEA_TREE) {
            return org.bukkit.TreeType.AZALEA;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.MANGROVE) {
            return org.bukkit.TreeType.MANGROVE;
        } else if (key == net.minecraft.data.worldgen.features.TreeFeatures.TALL_MANGROVE) {
            return org.bukkit.TreeType.TALL_MANGROVE;
        }

        return null;
    }

    /**
     * 控えを閉じて発火する。通ったものだけ世界へ入れる。
     *
     * <p>読んだ位置(Paper 1.19.4):
     *   Paper-Server src/main/java/net/minecraft/world/level/block/SaplingBlock.java(StructureGrowEvent)
     *   Paper-Server src/main/java/net/minecraft/world/item/ItemStack.java(BlockFertilizeEvent)
     */
    private static void fireGrow(final Level level, final BlockPos pos, final ServerPlayer player,
                                 final boolean bonemeal, final boolean fertilize) {
        level.captureTreeGeneration = false;

        final org.bukkit.TreeType type = net.minecraft.world.level.block.SaplingBlock.treeType;
        net.minecraft.world.level.block.SaplingBlock.treeType = null;

        if (level.capturedBlockStates.isEmpty()) {
            return;
        }

        final List<org.bukkit.block.BlockState> blocks =
                new ArrayList<>(level.capturedBlockStates.values());
        level.capturedBlockStates.clear();

        final org.bukkit.entity.Player who = player == null
                ? null : (org.bukkit.entity.Player) player.getBukkitEntity();
        org.bukkit.event.world.StructureGrowEvent grow = null;

        if (type != null) {
            grow = new org.bukkit.event.world.StructureGrowEvent(
                    org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level.getWorld()),
                    type, bonemeal, who, blocks);
            grow.callEvent();
        }

        if (fertilize) {
            final org.bukkit.event.block.BlockFertilizeEvent event =
                    new org.bukkit.event.block.BlockFertilizeEvent(CraftBlock.at(level, pos), who, blocks);
            event.setCancelled(grow != null && grow.isCancelled());

            if (!event.callEvent()) {
                return;
            }
        } else if (grow != null && grow.isCancelled()) {
            return;
        }

        for (final org.bukkit.block.BlockState one : blocks) {
            one.update(true);
        }
    }
}
