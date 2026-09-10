// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Bukkit のイベントを vanilla の処理から発火する。
 *
 * <p><b>条件:</b> そのイベントに登録が無ければ発火しない。発火しないとき、
 * 実行される命令列は vanilla と同一になる。プラグインを入れていないサーバーが
 * vanilla と一致するのはこのため。判定は
 * {@link HandlerList#getRegisteredListeners()} の長さを見るだけで、
 * イベントの生成もしない。
 *
 * <p><b>返り値は「vanilla の処理を続けてよいか」。</b> 取り消されたら false。
 * Bukkit の {@link org.bukkit.event.Event#callEvent()} と同じ向きにしてある。
 * 呼ぶ側は 2 つの形のどちらかで使う。
 *
 * <pre>
 * // 途中で抜ける形
 * if (!ShifuEvents.blockBreak(this.player, pos)) {
 *     return false;
 * }
 *
 * // vanilla の行を囲む形。行そのものは 1 文字も変えない
 * if (ShifuEvents.playerJoin(player, component)) {
 * this.broadcastSystemMessage(component.withStyle(ChatFormatting.YELLOW), false);
 * }
 * </pre>
 *
 * <p>取り消しが結果の状態を必要とするイベント(ブロック設置など)は、
 * vanilla の処理を先に済ませてから発火し、取り消されたときだけ戻す。
 * 成功する経路を vanilla のままにするため。Paper のブロックキャプチャは使わない。
 * あれは更新順そのものを変える。
 *
 * <p>プレイヤーの移動・テレポート・チャット・インベントリ操作・死亡・
 * リスポーンは、Paper が vanilla のメソッドを作り変えて発火しているもの。
 * ここでは vanilla の行をそのままにして、前後に差し込む形で同じ位置に発火する。
 * それぞれの制約は {@code docs/backlog/events-partial.txt}。
 *
 * <p>参照した API の位置(Paper 26.2):
 * <ul>
 *   <li>{@code paper-api org/bukkit/event/HandlerList.java:203} getRegisteredListeners</li>
 *   <li>{@code paper-api org/bukkit/event/Event.java:44} callEvent</li>
 *   <li>{@code paper-api org/bukkit/event/block/BlockBreakEvent.java:38} 構築子</li>
 *   <li>{@code paper-api org/bukkit/event/player/PlayerJoinEvent.java:24,41} 構築子と joinMessage</li>
 *   <li>{@code paper-api org/bukkit/event/player/PlayerQuitEvent.java:37,53} 構築子と quitMessage</li>
 *   <li>{@code paper-server org/bukkit/craftbukkit/block/CraftBlock.java:78} at</li>
 *   <li>{@code paper-server io/papermc/paper/adventure/PaperAdventure.java:174,200} 相互変換</li>
 *   <li>{@code paper-server net/minecraft/server/players/PlayerList.java:721} broadcastSystemMessage</li>
 *   <li>{@code paper-server net/minecraft/server/level/ServerLevel.java:1303} getServer</li>
 * </ul>
 */
public final class ShifuEvents {
    private ShifuEvents() {
    }

    /**
     * そのイベントを聞いている登録があるか。
     *
     * <p>イベントの本体はハンドラリストを親と共有することがある
     * ({@link BlockBreakEvent} は {@code BlockExpEvent} のものを使う)。
     * その場合、兄弟のイベントだけを聞いていても真になる。
     * 発火が余分になることはあっても、必要な発火を飛ばすことはない。
     */
    public static boolean listening(final HandlerList handlers) {
        return handlers.getRegisteredListeners().length != 0;
    }

    /**
     * 参加を聞いている登録が無いか。
     *
     * <p>呼ぶ側で先に見るためのもの。告知のメッセージを組み立てる式は
     * 引数として渡すと必ず評価されてしまう。短絡でそれを避ける。
     */
    public static boolean silentJoin() {
        return !listening(PlayerJoinEvent.getHandlerList());
    }

    /** 退出を聞いている登録が無いか。 */
    public static boolean silentQuit() {
        return !listening(PlayerQuitEvent.getHandlerList());
    }

    /**
     * エンティティが世界に入るときのイベントを聞いている登録があるか。
     *
     * <p>{@code CraftEventFactory.doEntityAddEventCalling} は分類に関わらず
     * イベントを組み立てるので、登録が無くてもエンティティが 1 つ増えるたびに
     * 仕事が増える。ここで先に見て、無ければ vanilla の命令列のまま通す。
     *
     * <p>見るのは 3 つ。{@code CreatureSpawnEvent} と {@code ItemSpawnEvent} と
     * {@code ProjectileLaunchEvent} は自分の HandlerList を持たず、
     * {@code EntitySpawnEvent} のものを使う。
     *
     * <p>飛ばすと CraftBukkit が足した「spawn-animals / spawn-monsters が false なら
     * 捨てる」も飛ぶ。vanilla はそこを {@code ServerChunkCache.tickChunks} で見ているので、
     * 飛ばした方が vanilla に近い。
     */
    public static boolean entityAddListening() {
        return listening(org.bukkit.event.entity.EntitySpawnEvent.getHandlerList())
                || listening(org.bukkit.event.vehicle.VehicleCreateEvent.getHandlerList())
                || listening(org.bukkit.event.weather.LightningStrikeEvent.getHandlerList());
    }

    // ------------------------------------------------------------ ブロック

    /**
     * ブロックの破壊。
     *
     * <p>差し込む位置は、vanilla の権限判定が全て終わったあと、
     * 最初の書き換え({@code playerWillDestroy})の直前。
     *
     * <p>未対応: {@code BlockBreakEvent#setExpToDrop}。
     * 経験値の量を vanilla の落下処理へ渡す経路がまだ無い。
     */
    public static boolean blockBreak(final ServerPlayer player, final BlockPos pos) {
        if (!listening(BlockBreakEvent.getHandlerList())) {
            return true;
        }

        return new BlockBreakEvent(
                CraftBlock.at(player.level(), pos), player.getBukkitEntity()).callEvent();
    }

    /**
     * ブロックの設置。
     *
     * <p>Paper はブロックキャプチャで「置く前に発火して、通ったら置く」に
     * 作り変える。あれは設置そのものを遅らせるので、周囲への更新順が変わる。
     * Shifu は <b>vanilla のとおり先に置いてから</b>発火し、取り消された
     * ときだけ控えておいた元の状態に戻す。通る経路が vanilla のままになる。
     *
     * @param replaced 置く前にその場所から取った控え。{@code null} なら発火しない
     * @return vanilla の続き(音・gameEvent・手持ちの減り)へ進んでよいか
     */
    public static boolean blockPlace(final ServerLevel level,
                                     final net.minecraft.world.entity.player.Player player,
                                     final net.minecraft.world.InteractionHand hand,
                                     final org.bukkit.block.BlockState replaced,
                                     final BlockPos clickedPos) {
        if (replaced == null || player == null) {
            return true;
        }

        final org.bukkit.event.block.BlockPlaceEvent event = CraftEventFactory.callBlockPlaceEvent(
                level, player, hand, replaced, clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());

        return !event.isCancelled() && event.canBuild();
    }

    /** ブロックの設置を聞いている登録があるか。置く前の様子を控えるかの判断に使う。 */
    public static boolean blockPlaceListening() {
        return listening(org.bukkit.event.block.BlockPlaceEvent.getHandlerList());
    }


    // ------------------------------------------------------------ エンティティ

    /**
     * 体力の回復。
     *
     * <p><b>登録が無ければ null を返す。</b> 呼ぶ側は null のとき vanilla の
     * 行をそのまま通す。Paper はここで vanilla の {@code setHealth} を
     * 書き換えるが、Shifu は vanilla の行を残したまま前後で分岐する。
     *
     * <p>理由は常に {@code CUSTOM}。vanilla の {@code heal} には理由を運ぶ
     * 引数が無く、Paper は呼び出し側の署名を増やして渡している。
     */
    public static EntityRegainHealthEvent entityRegainHealth(final LivingEntity entity, final float amount) {
        if (!listening(EntityRegainHealthEvent.getHandlerList())) {
            return null;
        }

        final EntityRegainHealthEvent event = new EntityRegainHealthEvent(
                entity.getBukkitEntity(), amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
        event.callEvent();

        return event;
    }

    /**
     * PlayerItemConsumeEvent。食べ終える直前。
     *
     * <p>未対応: {@code setItem} での差し替え(Paper は差し替えた物を消費するが、
     * それは vanilla の行の書き換えになる)。取り消しは効く。
     *
     * @return 消費してよいか
     */
    public static boolean itemConsume(final LivingEntity entity, final net.minecraft.world.InteractionHand hand) {
        if (!(entity instanceof ServerPlayer player)
                || !listening(org.bukkit.event.player.PlayerItemConsumeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.player.PlayerItemConsumeEvent event = new org.bukkit.event.player.PlayerItemConsumeEvent(
                player.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(player.getUseItem()),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand));

        if (event.callEvent()) {
            return true;
        }

        player.containerMenu.sendAllDataToRemote();
        player.getBukkitEntity().updateScaledHealth();
        player.stopUsingItem();

        return false;
    }

    /**
     * ServerCommandEvent。コンソールの入力を実行する直前。
     *
     * @return 実行する入力。取り消されたら null。文が差し替えられていれば新しい入力
     */
    public static net.minecraft.server.ConsoleInput serverCommand(final net.minecraft.server.MinecraftServer server,
                                                                  final net.minecraft.server.ConsoleInput input) {
        if (!listening(org.bukkit.event.server.ServerCommandEvent.getHandlerList())) {
            return input;
        }

        final org.bukkit.event.server.ServerCommandEvent event =
                new org.bukkit.event.server.ServerCommandEvent(server.console, input.msg);

        if (!event.callEvent()) {
            return null;
        }

        return event.getCommand().equals(input.msg)
                ? input
                : new net.minecraft.server.ConsoleInput(event.getCommand(), input.source);
    }


    /**
     * 死んだときに世界へ出ようとする物の控え。{@link #beginDeathDrops} か
     * {@link #beginPlayerDeath} から、{@link #entityDeath} か {@link #playerDeath}
     * までの間だけ入っている。
     *
     * <p>静的で持てるのは、エンティティを世界に入れるのがサーバースレッドだけ
     * だから。{@code ServerLevel.addEntity} は
     * {@code AsyncCatcher} が別スレッドからの呼び出しを弾く。
     *
     * <p>イベントの中でプラグインが別のエンティティを殺すと入れ子になるので、
     * 1 つ前の控えを持って戻す。
     */
    private static final class DeathCapture {
        final List<net.minecraft.world.entity.Entity.DefaultDrop> drops = new ArrayList<>();
        final List<ExperienceOrb> orbs = new ArrayList<>();
        final DeathCapture previous;
        DeathInventory inventory;

        DeathCapture(final DeathCapture previous) {
            this.previous = previous;
        }

        int experience() {
            int total = 0;

            for (ExperienceOrb orb : this.orbs) {
                total += orb.getValue();
            }

            return total;
        }
    }

    private static DeathCapture capture;

    /**
     * 世界に入ろうとしている物を控えに移す。移したら true。
     *
     * <p>{@code ServerLevel.addEntity} から呼ぶ。控えている最中でなければ
     * 何もしないので、プラグインを入れていないときに増える仕事は参照 1 つの比較。
     *
     * <p>落とし物は vanilla が作った {@link net.minecraft.world.entity.item.ItemEntity} を
     * そのまま控える。プラグインが触らなければ、あとで同じものを世界に入れるので、
     * 速度や向きも vanilla が決めたままになる。経験値オーブも同じ。
     */
    public static boolean catchDrop(final net.minecraft.world.entity.Entity entity) {
        final DeathCapture current = capture;

        if (current == null) {
            return false;
        }

        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
            current.drops.add(new net.minecraft.world.entity.Entity.DefaultDrop(item.getItem(), stack -> {
                item.setItem(stack);
                item.level().addFreshEntity(item);
            }));

            return true;
        }

        if (entity instanceof ExperienceOrb orb) {
            current.orbs.add(orb);

            return true;
        }

        return false;
    }

    /** 控えを閉じて返す。渡された落とし物の控えと食い違っていたら、それが見つかるまで戻す。 */
    private static DeathCapture endCapture(final List<net.minecraft.world.entity.Entity.DefaultDrop> drops) {
        DeathCapture current = capture;

        while (current != null && current.drops != drops) {
            current = current.previous;
        }

        capture = current == null ? null : current.previous;

        return current;
    }

    /**
     * 落とす物を実際に落とす。
     *
     * <p>プラグインが差し替えたものは Bukkit の側から、触っていないものは
     * vanilla が作った NMS の {@code ItemStack} のまま落とす({@code runConsumer} の判定)。
     */
    private static void dropAllItems(final List<net.minecraft.world.entity.Entity.DefaultDrop> drops,
                                     final org.bukkit.World world, final org.bukkit.Location at) {
        for (final net.minecraft.world.entity.Entity.DefaultDrop drop : drops) {
            if (drop == null || drop.stack() == null || drop.stack().getType() == org.bukkit.Material.AIR) {
                continue;
            }

            drop.runConsumer(world, at);
        }
    }


    /**
     * 控えた経験値オーブを世界へ出す。
     *
     * <p>プラグインが量を変えていなければ vanilla が作ったオーブをそのまま入れる。
     * 変えていれば作り直す。
     */
    private static void releaseExperience(final LivingEntity victim, final DeathCapture current, final int wanted) {
        if (wanted == current.experience()) {
            for (ExperienceOrb orb : current.orbs) {
                victim.level().addFreshEntity(orb);
            }
        } else if (wanted > 0) {
            ExperienceOrb.award((ServerLevel) victim.level(), victim.position(), wanted);
        }
    }


    /**
     * ダメージ。
     *
     * <p><b>登録が無ければ null。</b> 呼ぶ側は null のとき vanilla の
     * {@code actuallyHurt} をそのまま通す。
     *
     * <p><b>差分の内訳は載らない。</b> Paper は防具・耐性・吸収・受け流しの
     * 減衰を先に計算して {@code DamageModifier} ごとに詰め、減衰後の値を
     * {@code actuallyHurt} に渡す。Shifu は vanilla の {@code actuallyHurt} が
     * 中で減衰するのをそのままにするので、イベントに載るのは <b>減衰前</b>の
     * 素の値 1 つ({@code BASE})だけ。{@code getFinalDamage()} は
     * {@code getDamage()} と同じ値を返す。
     * {@code getDamage(DamageModifier.ARMOR)} などは 0。
     *
     * <p>取り消しと {@code setDamage} は効く。原因
     * ({@code DamageCause})は Paper と同じ導出をそのまま使う。
     *
     * <p>参照した位置(Paper 26.2):
     * {@code paper-server src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java:1228}
     * ({@code callNonLivingEntityDamageEvent}。名前に反して中身は生死を問わない)
     */
    public static org.bukkit.event.entity.EntityDamageEvent entityDamage(
            final Entity entity,
            final net.minecraft.world.damagesource.DamageSource source,
            final float damage) {
        if (!listening(org.bukkit.event.entity.EntityDamageEvent.getHandlerList())) {
            return null;
        }

        return CraftEventFactory.callNonLivingEntityDamageEvent(entity, source, damage, false);
    }

    /**
     * 満腹度の変化。
     *
     * <p>{@link #entityRegainHealth} と同じで、登録が無ければ null。
     *
     * <p>参照した位置(Paper 26.2):
     * {@code paper-server src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java:1315}
     */
    public static FoodLevelChangeEvent foodLevelChange(
            final net.minecraft.world.entity.player.Player player, final int level) {
        if (!listening(FoodLevelChangeEvent.getHandlerList())) {
            return null;
        }

        return CraftEventFactory.callFoodLevelChangeEvent(player, level);
    }

    /** 取り消された爆発。{@code ServerLevel.explode} が続きの告知を飛ばすために見る。 */



    // ------------------------------------------------------------ プレイヤー

    /**
     * 参加。
     *
     * <p>取り消せないが、メッセージは差し替えられる。
     * 差し替えられていなければ true を返し、vanilla の告知をそのまま通す。
     * 差し替えられたときはここで送って false を返す。
     */
    public static boolean playerJoin(final ServerPlayer player, final Component message) {
        if (!listening(PlayerJoinEvent.getHandlerList())) {
            return true;
        }

        net.kyori.adventure.text.Component original = PaperAdventure.asAdventure(message);
        PlayerJoinEvent event = new PlayerJoinEvent(player.getBukkitEntity(), original);
        event.callEvent();

        return announce(player, original, event.joinMessage());
    }

    /**
     * 退出。
     *
     * <p>参加と同じで、取り消せないがメッセージは差し替えられる。
     */
    public static boolean playerQuit(final ServerPlayer player, final Component message) {
        if (!listening(PlayerQuitEvent.getHandlerList())) {
            return true;
        }

        net.kyori.adventure.text.Component original = PaperAdventure.asAdventure(message);
        PlayerQuitEvent event = new PlayerQuitEvent(player.getBukkitEntity(), original);
        event.callEvent();

        return announce(player, original, event.quitMessage());
    }

    /**
     * 差し替えられたメッセージを送る。
     *
     * <p>差し替えが無ければ true を返し、vanilla の告知にそのまま任せる。
     * vanilla 側は色付けなどを行うので、触られていないものを送り直すと
     * 見た目が変わってしまう。触られたかどうかは、渡した参照と
     * 同じものが返ってきたかで見る。プラグインが触らなければ同一。
     *
     * <p>消されていれば何も送らずに false。
     */
    private static boolean announce(final ServerPlayer player,
                                    final net.kyori.adventure.text.Component original,
                                    final net.kyori.adventure.text.Component replaced) {
        if (replaced == original) {
            return true;
        }

        if (replaced != null) {
            player.level().getServer().getPlayerList()
                    .broadcastSystemMessage(PaperAdventure.asVanilla(replaced), false);
        }

        return false;
    }

    /**
     * PlayerToggleFlightEvent。飛行の切り替えの packet で、vanilla が書き換える直前。
     * 飛べない・変わらないなら発火しない(Paper と同じ)。取り消されたらクライアントに能力を送り直す。
     *
     * @return vanilla の代入へ進んでよいか
     */
    public static boolean toggleFlight(final ServerPlayer player, final boolean flying) {
        if (!listening(org.bukkit.event.player.PlayerToggleFlightEvent.getHandlerList())) {
            return true;
        }

        if (!player.getAbilities().mayfly || player.getAbilities().flying == flying) {
            return true;
        }

        if (new org.bukkit.event.player.PlayerToggleFlightEvent(player.getBukkitEntity(), flying).callEvent()) {
            return true;
        }

        player.onUpdateAbilities();

        return false;
    }

    /**
     * 手放し。{@code ServerPlayer.drop} で {@code ItemEntity} を作ったあと、
     * 世界に入れる直前。死んで落とす分では発火しない。
     *
     * <p>取り消されたら Paper と同じ手順で手元に戻す。
     *
     * @return 世界に入れてよいか
     */
    public static boolean playerDropItem(final ServerPlayer player,
                                         final net.minecraft.world.entity.item.ItemEntity entity,
                                         final boolean thrownFromHand) {
        if (player.isDeadOrDying()
                || !listening(org.bukkit.event.player.PlayerDropItemEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.entity.Player bukkit = player.getBukkitEntity();
        final org.bukkit.entity.Item drop = (org.bukkit.entity.Item) entity.getBukkitEntity();

        if (new org.bukkit.event.player.PlayerDropItemEvent(bukkit, drop).callEvent()) {
            return true;
        }

        final org.bukkit.inventory.ItemStack inHand = bukkit.getInventory().getItemInMainHand();

        if (thrownFromHand && inHand.getAmount() == 0) {
            // 手に持っていた分を丸ごと落とした
            bukkit.getInventory().setItemInMainHand(drop.getItemStack());
        } else if (thrownFromHand && inHand.isSimilar(drop.getItemStack())
                && inHand.getAmount() < inHand.getMaxStackSize() && drop.getItemStack().getAmount() == 1) {
            // 1 個だけ落とした
            inHand.setAmount(inHand.getAmount() + 1);
            bukkit.getInventory().setItemInMainHand(inHand);
        } else {
            bukkit.getInventory().addItem(drop.getItemStack());
        }

        return false;
    }

    // ------------------------------------------------------------ 移動


    // ------------------------------------------------------------ 移動

    /**
     * 移動。
     *
     * <p>{@code handleMovePlayer} の中で、vanilla が {@code absMoveTo(target...)} で
     * 位置を確定させる直前。その時点で位置は {@code move()} で動いたあと、
     * 回転は動く前のまま。
     *
     * <p>Paper と同じく、発火の前に位置を packet を受ける前の場所へ戻し、
     * {@code from} は直前に発火した {@code to}(接続が控えている)にする。
     * 1/256 ブロック・10 度に満たない動きでは発火しない。これも Paper と同じ。
     *
     * <p>乗り物に乗っている間の移動では発火しない。Paper はそこでプレイヤーを
     * 乗り物の位置へ動かす細工をしてから発火していて、細工そのものが vanilla の位置を変える。
     *
     * @return vanilla の {@code absMoveTo(target...)} へ進んでよいか
     */
    public static boolean playerMove(final net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                                     final double startX, final double startY, final double startZ,
                                     final double targetX, final double targetY, final double targetZ,
                                     final float targetYRot, final float targetXRot) {
        final boolean moveListening = listening(org.bukkit.event.player.PlayerMoveEvent.getHandlerList());

        if (!moveListening
                && !listening(com.destroystokyo.paper.event.player.PlayerJumpEvent.getHandlerList())) {
            return true;
        }

        final ServerPlayer player = connection.player;
        final float startYRot = player.getYRot();
        final float startXRot = player.getXRot();
        final org.bukkit.Location from = connection.shifuMoveFrom(startX, startY, startZ, startYRot, startXRot);
        final org.bukkit.Location to = new org.bukkit.Location(
                player.level().getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);

        // 1 ピクセルに満たない動きで 40 回発火するのを防ぐ(Paper と同じ閾値)
        final double delta = net.minecraft.util.Mth.square(from.getX() - to.getX())
                + net.minecraft.util.Mth.square(from.getY() - to.getY())
                + net.minecraft.util.Mth.square(from.getZ() - to.getZ());
        final float deltaAngle = Math.abs(from.getYaw() - to.getYaw()) + Math.abs(from.getPitch() - to.getPitch());

        if (!(delta > 1f / 256 || deltaAngle > 10.0F) || player.isImmobile()) {
            return true;
        }

        connection.shifuRememberMove(to);

        if (!moveListening) {
            return true;
        }

        // 発火の前に packet を受ける前の場所へ戻す(Paper と同じ)
        player.absMoveTo(startX, startY, startZ, startYRot, startXRot);

        final org.bukkit.Location oldTo = to.clone();
        final org.bukkit.event.player.PlayerMoveEvent event =
                new org.bukkit.event.player.PlayerMoveEvent(player.getBukkitEntity(), from, to);
        event.callEvent();

        if (event.isCancelled()) {
            connection.internalTeleport(from);

            return false;
        }

        if (!oldTo.equals(event.getTo())) {
            player.getBukkitEntity().teleport(event.getTo(), TeleportCause.PLUGIN);

            return false;
        }

        // イベントの中でプラグインがテレポートさせたなら、この packet の位置は使わない
        return from.equals(player.getBukkitEntity().getLocation()) || !connection.shifuTakeJustTeleported();
    }

    // ------------------------------------------------------------ テレポート

    /** 次に {@code connection.teleport} を通る相手のうち、発火しないもの。 */
    private static ServerPlayer silentTeleport;

    /** 行き先を変えられた {@code teleport(transition)} の、入れ子の呼び出しの相手。 */
    private static ServerPlayer suppressTransition;

    /** 呼び出し側が先に置いた理由。vanilla の署名には理由を運ぶ引数が無い。 */
    private static Entity causeEntity;
    private static TeleportCause cause;

    /**
     * 次のテレポートの理由を置く。
     *
     * <p>Paper はテレポートの署名を増やして理由を渡しているが、Shifu は
     * vanilla の署名を変えない。代わりに呼び出す直前で置いておき、
     * 発火するときに読む。登録が無ければ何もしない。
     */
    public static void teleportCause(final Entity entity, final TeleportCause reason) {
        if (!listening(PlayerTeleportEvent.getHandlerList())) {
            return;
        }

        causeEntity = entity;
        cause = reason;
    }

    /**
     * 次の {@code connection.teleport} では発火しない。
     *
     * <p>Paper が {@code internalTeleport} を使っている場所
     * (位置がずれたときの戻し、イベントの取り消し)で使う。
     */
    public static void silentTeleport(final ServerGamePacketListenerImpl connection) {
        if (!listening(PlayerTeleportEvent.getHandlerList())) {
            return;
        }

        silentTeleport = connection.player;
    }






    // ------------------------------------------------------------ チャット

    /**
     * チャットを聞いている登録が無いか。
     *
     * <p>Paper の {@code ChatProcessor} が扱う 4 つ(新旧 × 同期/非同期)を全部見る。
     */
    public static boolean silentChat() {
        return !listening(io.papermc.paper.event.player.AsyncChatEvent.getHandlerList())
                && !listening(io.papermc.paper.event.player.ChatEvent.getHandlerList())
                && !listening(org.bukkit.event.player.AsyncPlayerChatEvent.getHandlerList())
                && !listening(org.bukkit.event.player.PlayerChatEvent.getHandlerList());
    }

    /**
     * チャット。vanilla が全員へ送る行の代わりに Paper の {@code ChatProcessor} へ渡す。
     * 発火と送信は {@code ChatProcessor} が全部行うので、常に false を返して
     * vanilla の送信は飛ばす。
     *
     * <p><b>同期で発火する。</b> vanilla はチャットの送信をサーバースレッドで
     * 行う({@code FutureChain} がサーバーで実行する)ので、その順序を保つ。
     * {@code AsyncChatEvent} は {@code isAsynchronous()} が false になる。
     * Paper は受信スレッドで処理するように書き換えているが、それは
     * vanilla の順序を変える。
     *
     * <p>未対応: Bukkit の Conversation API({@code Player.isConversing()})。
     *
     * <p>参照した位置(Paper 26.2):
     * {@code paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch:1709}
     */
    public static boolean playerChat(final net.minecraft.server.MinecraftServer server, final ServerPlayer player,
                                     final net.minecraft.network.chat.PlayerChatMessage message) {
        new io.papermc.paper.adventure.ChatProcessor(server, player, message, false).process();

        return false;
    }

    // ------------------------------------------------------------ インベントリ

    /** 直前に発火したときに開いていた画面。イベントの中で閉じられたかを見る。 */
    private static net.minecraft.world.inventory.AbstractContainerMenu clickedMenu;


    /** イベントの中で別の画面が開かれたか(SPIGOT-1224)。 */
    public static boolean containerChanged(final ServerPlayer player,
                                           final org.bukkit.event.inventory.InventoryClickEvent event) {
        return event != null && player.containerMenu != clickedMenu;
    }

    /** クリックの処理が済んだあと。作業台と鍛冶台は中身を送り直す(Paper と同じ)。 */
    public static void afterInventoryClick(final ServerPlayer player,
                                           final org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event instanceof org.bukkit.event.inventory.CraftItemEvent
                || event instanceof org.bukkit.event.inventory.SmithItemEvent) {
            player.containerMenu.sendAllDataToRemote();
        }
    }


    // ------------------------------------------------------------ 死亡とリスポーン

    /**
     * プレイヤーが死んで落とし物を出す直前。登録が無ければ null で vanilla のまま。
     *
     * <p>{@code setKeepInventory} と {@code getItemsToKeep} を後から効かせられるよう、
     * 落とす前の持ち物を控える。Paper は先に発火して、通ってから落としているが、
     * その順序は vanilla と違う。
     */
    public static List<net.minecraft.world.entity.Entity.DefaultDrop> beginPlayerDeath(final ServerPlayer player) {
        if (!listening(org.bukkit.event.entity.PlayerDeathEvent.getHandlerList())) {
            return null;
        }

        capture = new DeathCapture(capture);
        capture.inventory = new DeathInventory(player.getInventory());

        return capture.drops;
    }

    /**
     * プレイヤーの死亡。vanilla が落とし物と経験値を出し終えた直後。
     *
     * <p>死亡メッセージの告知は、発火する前に vanilla が済ませないよう
     * 呼ぶ側で飛ばしてある。ここでイベントの結果に従って送る。
     *
     * <p>取り消されたら控えた持ち物を戻し、落とし物と経験値は捨て、
     * 体力を戻して false を返す。呼ぶ側は統計や死亡地点の記録へ進まない。
     * その前に走った肩の生き物の解放と、周りの生き物への通知は戻せない。
     *
     * <p>死亡音は vanilla の {@code LivingEntity.die} が鳴らしているので、ここでは鳴らさない
     * (Paper はイベントのあとに鳴らす形に変えていて、{@code setDeathSound} が効く)。
     *
     * @return vanilla の続き(統計・死亡地点)へ進んでよいか
     */
    public static boolean playerDeath(final ServerPlayer player,
                                      final net.minecraft.world.damagesource.DamageSource source,
                                      final List<net.minecraft.world.entity.Entity.DefaultDrop> drops,
                                      final boolean showDeathMessage) {
        if (drops == null) {
            return true;
        }

        final DeathCapture current = endCapture(drops);

        if (current == null) {
            return true;
        }

        final int experience = current.experience();
        final boolean keepInventoryRule =
                player.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        final boolean keepInventory = keepInventoryRule || player.isSpectator();
        final Component defaultMessage = player.getCombatTracker().getDeathMessage();

        player.keepLevel = keepInventory; // SPIGOT-2222: Paper と同じく先に入れておく

        final org.bukkit.event.entity.PlayerDeathEvent event = new org.bukkit.event.entity.PlayerDeathEvent(
                player.getBukkitEntity(),
                new org.bukkit.craftbukkit.damage.CraftDamageSource(source),
                new io.papermc.paper.util.TransformingRandomAccessList<>(
                        drops, net.minecraft.world.entity.Entity.DefaultDrop::stack,
                        org.bukkit.craftbukkit.event.CraftEventFactory.FROM_FUNCTION),
                experience,
                0,
                PaperAdventure.asAdventure(defaultMessage));
        event.setKeepInventory(keepInventory);
        event.setKeepLevel(player.keepLevel);
        event.setReviveHealth(player.getBukkitEntity()
                .getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
        event.callEvent();

        if (event.isCancelled()) {
            current.inventory.restore(player.getInventory());

            if (player.getHealth() <= 0) {
                player.setHealth((float) event.getReviveHealth());
            }

            return false;
        }

        player.keepLevel = event.getKeepLevel();
        player.newLevel = event.getNewLevel();
        player.newTotalExp = event.getNewTotalExp();
        player.expToDrop = event.getDroppedExp();
        player.newExp = event.getNewExp();
        player.shifuDeathEvent = true;
        player.shifuKeepInventory = event.getKeepInventory();

        if (event.getKeepInventory()) {
            if (!keepInventory) {
                // vanilla は落とし終えている。控えから戻す
                current.inventory.restore(player.getInventory());
            }
        } else if (keepInventory) {
            // vanilla は残している。Paper と同じく空にする(残す指定の物は除く)
            current.inventory.clear(player.getInventory(), event.getItemsToKeep());
        } else {
            current.inventory.restoreKept(player.getInventory(), event.getItemsToKeep());
        }

        // 残す指定に入っていて持ち物に無かった物(Paper と同じ扱い)
        for (final org.bukkit.inventory.ItemStack stack : event.getItemsToKeep()) {
            player.getBukkitEntity().getInventory().addItem(stack);
        }

        dropAllItems(drops, player.getBukkitEntity().getWorld(), player.getBukkitEntity().getLocation());
        releaseExperience(player, current, event.getDroppedExp());
        announceDeath(player, event, showDeathMessage);

        return true;
    }

    /**
     * 死亡メッセージを送る。vanilla の {@code ServerPlayer.die} の前半と同じ手順で、
     * 文だけをイベントのものにする。
     */
    private static void announceDeath(final ServerPlayer player,
                                      final org.bukkit.event.entity.PlayerDeathEvent event,
                                      final boolean showDeathMessage) {
        final net.kyori.adventure.text.Component apiMessage = event.deathMessage() != null
                ? event.deathMessage()
                : net.kyori.adventure.text.Component.empty();
        final Component message = PaperAdventure.asVanilla(apiMessage);

        if (apiMessage != net.kyori.adventure.text.Component.empty() && showDeathMessage) {
            sendCombatKill(player, true, message);

            final net.minecraft.world.scores.Team team = player.getTeam();
            final net.minecraft.server.players.PlayerList list = player.server.getPlayerList();

            if (team == null || team.getDeathMessageVisibility() == net.minecraft.world.scores.Team.Visibility.ALWAYS) {
                list.broadcastSystemMessage(message, false);
            } else if (team.getDeathMessageVisibility() == net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OTHER_TEAMS) {
                list.broadcastSystemToTeam(player, message);
            } else if (team.getDeathMessageVisibility() == net.minecraft.world.scores.Team.Visibility.HIDE_FOR_OWN_TEAM) {
                list.broadcastSystemToAllExceptTeam(player, message);
            }
        } else {
            sendCombatKill(player, showDeathMessage, message);
        }
    }

    /** 死亡画面の packet。vanilla の {@code die} と同じく、長すぎる文は差し替える。 */
    private static void sendCombatKill(final ServerPlayer player, final boolean display, final Component message) {
        if (!display || message == net.minecraft.network.chat.CommonComponents.EMPTY) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket(
                    player.getId(), net.minecraft.network.chat.CommonComponents.EMPTY));

            return;
        }

        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket(player.getId(), message),
                net.minecraft.network.PacketSendListener.exceptionallySend(() -> {
                    final String cut = message.getString(256);
                    final Component tooLong = Component.translatable("death.attack.message_too_long",
                            Component.literal(cut).withStyle(net.minecraft.ChatFormatting.YELLOW));
                    final Component fallback = Component.translatable("death.attack.even_more_magic", player.getDisplayName())
                            .withStyle(style -> style.withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, tooLong)));

                    return new net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket(player.getId(), fallback);
                }));
    }

    // ------------------------------------------------------------ ブロックの変化

    /**
     * ブロックを置き換える直前の控え。登録が無ければ null。
     *
     * <p>Paper は {@code handleBlockGrowEvent} などで「発火して、通ったら置く」に
     * 作り変える。Shifu は vanilla の {@code setBlock} をそのまま走らせ、
     * 置いたあとに発火して、取り消されたときだけこの控えに戻す。
     * 世界生成の途中({@code WorldGenRegion})では Paper と同じく発火しない。
     *
     * <p>規則は {@code tools/make_block_events.py} が Paper のパッチから出す。
     */
    public static org.bukkit.craftbukkit.block.CraftBlockState blockChangeBefore(
            final net.minecraft.world.level.LevelAccessor level, final BlockPos pos, final HandlerList handlers) {
        if (!(level instanceof net.minecraft.world.level.Level) || !listening(handlers)) {
            return null;
        }

        return org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
    }

    /**
     * 発火のあと。取り消されたら控えに戻す。プラグインが {@code getNewState()} を
     * 書き換えていたら、その状態で置き直す(Paper はイベント後の snapshot を置く)。
     */
    private static void finishBlockChange(final net.minecraft.world.level.LevelAccessor level, final BlockPos pos,
                                          final org.bukkit.craftbukkit.block.CraftBlockState before,
                                          final org.bukkit.craftbukkit.block.CraftBlockState placed,
                                          final boolean allowed, final int flags) {
        // 1.20.6 の CraftBlockState に place(int) は無い(26.2 の追加)。
        // 直に置き直す。この 5 つのイベントが相手にするのはどれも
        // ブロックエンティティを持たないブロックなので、状態だけで足りる。
        if (!allowed) {
            level.setBlock(pos, before.getHandle(), flags);

            return;
        }

        if (placed.getHandle() != level.getBlockState(pos)) {
            level.setBlock(pos, placed.getHandle(), flags);
        }
    }

    /** BlockGrowEvent。vanilla が置いたあと。 */
    public static void blockGrow(final net.minecraft.world.level.LevelAccessor level, final BlockPos pos,
                                 final org.bukkit.craftbukkit.block.CraftBlockState before, final int flags) {
        if (before == null) {
            return;
        }

        final org.bukkit.craftbukkit.block.CraftBlockState placed =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        final org.bukkit.event.block.BlockGrowEvent event =
                new org.bukkit.event.block.BlockGrowEvent(placed.getBlock(), placed);
        finishBlockChange(level, pos, before, placed, event.callEvent(), flags);
    }

    /** BlockSpreadEvent。vanilla が置いたあと。 */
    public static void blockSpread(final net.minecraft.world.level.LevelAccessor level, final BlockPos source,
                                   final BlockPos pos,
                                   final org.bukkit.craftbukkit.block.CraftBlockState before, final int flags) {
        if (before == null) {
            return;
        }

        final BlockPos from = CraftEventFactory.sourceBlockOverride != null
                ? CraftEventFactory.sourceBlockOverride : source;
        final org.bukkit.craftbukkit.block.CraftBlockState placed =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        final org.bukkit.event.block.BlockSpreadEvent event = new org.bukkit.event.block.BlockSpreadEvent(
                placed.getBlock(), CraftBlock.at(level, from), placed);
        finishBlockChange(level, pos, before, placed, event.callEvent(), flags);
    }

    /** BlockFormEvent / EntityBlockFormEvent。vanilla が置いたあと。 */
    public static void blockForm(final net.minecraft.world.level.LevelAccessor level, final BlockPos pos,
                                 final org.bukkit.craftbukkit.block.CraftBlockState before, final int flags,
                                 final net.minecraft.world.entity.Entity entity) {
        if (before == null) {
            return;
        }

        final org.bukkit.craftbukkit.block.CraftBlockState placed =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        final org.bukkit.event.block.BlockFormEvent event = entity == null
                ? new org.bukkit.event.block.BlockFormEvent(placed.getBlock(), placed)
                : new org.bukkit.event.block.EntityBlockFormEvent(entity.getBukkitEntity(), placed.getBlock(), placed);
        finishBlockChange(level, pos, before, placed, event.callEvent(), flags);
    }

    /** MoistureChangeEvent。vanilla が置いたあと。 */
    public static void moistureChange(final net.minecraft.world.level.LevelAccessor level, final BlockPos pos,
                                      final org.bukkit.craftbukkit.block.CraftBlockState before, final int flags) {
        if (before == null) {
            return;
        }

        final org.bukkit.craftbukkit.block.CraftBlockState placed =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        final org.bukkit.event.block.MoistureChangeEvent event =
                new org.bukkit.event.block.MoistureChangeEvent(placed.getBlock(), placed);
        finishBlockChange(level, pos, before, placed, event.callEvent(), flags);
    }

    /** CauldronLevelChangeEvent。vanilla が置いたあと。 */
    public static void cauldronLevelChange(final net.minecraft.world.level.LevelAccessor level, final BlockPos pos,
                                           final org.bukkit.craftbukkit.block.CraftBlockState before,
                                           final net.minecraft.world.entity.Entity entity,
                                           final org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason reason) {
        if (before == null) {
            return;
        }

        final org.bukkit.craftbukkit.block.CraftBlockState placed =
                org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        final org.bukkit.event.block.CauldronLevelChangeEvent event =
                new org.bukkit.event.block.CauldronLevelChangeEvent(
                        CraftBlock.at(level, pos), entity == null ? null : entity.getBukkitEntity(), reason, placed);
        finishBlockChange(level, pos, before, placed, event.callEvent(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------ クリック



    /** 右クリックのうち、手に持った物の使用だけが拒まれたか。拒まれていれば手元を送り直す。 */
    /**
     * PlayerInteractEvent(ブロックへの右クリック)。{@code useItemOn} の先頭、
     * ブロックの状態を読んだ直後。登録が無ければ null を返し、呼ぶ側は vanilla のまま進む。
     *
     * <p>Paper は {@code firedInteract} などの欄に控えて packet 側の二重発火を避けるが、
     * Shifu はその経路を持たないので控えない。
     */
    public static org.bukkit.event.player.PlayerInteractEvent interactBlock(
            final net.minecraft.server.level.ServerPlayerGameMode gameMode, final ServerPlayer player,
            final net.minecraft.world.level.Level level, final net.minecraft.world.item.ItemStack itemStack,
            final net.minecraft.world.InteractionHand hand, final net.minecraft.world.phys.BlockHitResult hitResult,
            final net.minecraft.world.level.block.state.BlockState state) {
        if (!listening(org.bukkit.event.player.PlayerInteractEvent.getHandlerList())) {
            return null;
        }

        final BlockPos pos = hitResult.getBlockPos();
        final boolean cancelledBlock = gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR
                && state.getMenuProvider(level, pos) == null;
        final boolean cancelledItem = player.getCooldowns().isOnCooldown(itemStack.getItem());

        return CraftEventFactory.callPlayerInteractEvent(
                player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, pos, hitResult.getDirection(), itemStack,
                cancelledBlock, cancelledItem, hand, hitResult.getLocation());
    }

    /**
     * ブロックへの右クリックが拒まれたとき。Paper と同じくクライアントの画面を直して返る。
     */
    public static net.minecraft.world.InteractionResult interactBlockDenied(
            final org.bukkit.event.player.PlayerInteractEvent event, final ServerPlayer player,
            final net.minecraft.world.level.block.state.BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CakeBlock) {
            player.getBukkitEntity().sendHealthUpdate(); // SPIGOT-1341 - ケーキの分の体力を戻す
        } else if (state.is(net.minecraft.world.level.block.Blocks.JIGSAW)
                || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK)
                || state.getBlock() instanceof net.minecraft.world.level.block.CommandBlock) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerClosePacket(player.containerMenu.containerId));
        }

        player.containerMenu.sendAllDataToRemote();

        return event.useItemInHand() != org.bukkit.event.Event.Result.ALLOW
                ? net.minecraft.world.InteractionResult.SUCCESS
                : net.minecraft.world.InteractionResult.PASS;
    }

    public static boolean interactItemDenied(final org.bukkit.event.player.PlayerInteractEvent event, final ServerPlayer player) {
        if (event == null || event.useItemInHand() != org.bukkit.event.Event.Result.DENY) {
            return false;
        }

        player.containerMenu.sendAllDataToRemote();

        return true;
    }


    // ------------------------------------------------------------ ログイン

    private static final java.util.concurrent.atomic.AtomicInteger PRE_LOGIN_THREADS = new java.util.concurrent.atomic.AtomicInteger();



    private static java.net.InetAddress inetAddress(final java.net.SocketAddress address) {
        return address instanceof java.net.InetSocketAddress inet ? inet.getAddress() : java.net.InetAddress.getLoopbackAddress();
    }



    // ------------------------------------------------------------ TNT

    private static org.bukkit.event.block.TNTPrimeEvent.PrimeCause primeCause;
    private static Entity primeEntity;
    private static BlockPos primeBlock;

    /**
     * 次の {@code TntBlock.prime} の理由を置く。Paper は prime の署名に発火の Supplier を
     * 足して呼び出し側から渡しているが、Shifu は呼ぶ直前に置く。登録が無ければ何もしない。
     */
    public static void tntPrimeCause(final org.bukkit.event.block.TNTPrimeEvent.PrimeCause cause,
                                     final Entity entity, final BlockPos block) {
        if (!listening(org.bukkit.event.block.TNTPrimeEvent.getHandlerList())) {
            return;
        }

        primeCause = cause;
        primeEntity = entity;
        primeBlock = block;
    }

    /**
     * TNTPrimeEvent。{@code TntBlock.prime} の中で、ゲームルールの判定を通ったあと、
     * {@code PrimedTnt} を作る前。Paper と同じ位置。
     *
     * @return 点火してよいか
     */
    public static boolean tntPrime(final net.minecraft.world.level.Level level, final BlockPos pos, final LivingEntity source) {
        if (!listening(org.bukkit.event.block.TNTPrimeEvent.getHandlerList())) {
            return true;
        }

        org.bukkit.event.block.TNTPrimeEvent.PrimeCause cause = primeCause;
        Entity entity = primeEntity;
        final BlockPos block = primeBlock;
        primeCause = null;
        primeEntity = null;
        primeBlock = null;

        if (cause == null) {
            // 理由を置いていない呼び出し。点火した生き物がいればそれを主とみなす
            cause = source instanceof net.minecraft.world.entity.player.Player
                    ? org.bukkit.event.block.TNTPrimeEvent.PrimeCause.PLAYER
                    : org.bukkit.event.block.TNTPrimeEvent.PrimeCause.REDSTONE;
            entity = source;
        }

        return CraftEventFactory.callTNTPrimeEvent(level, pos, cause, entity, block);
    }

    /** 爆発で誘爆するとき。vanilla は prime を通らず {@code wasExploded} で直に作る。 */
    // 1.20.6 の TntBlock.wasExploded は Level を受ける。CraftEventFactory も Level で足りる
    public static boolean tntPrimeByExplosion(final net.minecraft.world.level.Level level, final BlockPos pos, final net.minecraft.world.level.Explosion explosion) {
        if (!listening(org.bukkit.event.block.TNTPrimeEvent.getHandlerList())) {
            return true;
        }

        final Entity source = explosion.getDirectSourceEntity();

        return CraftEventFactory.callTNTPrimeEvent(level, pos, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.EXPLOSION,
                source, source == null ? BlockPos.containing(explosion.center()) : null);
    }

    // ------------------------------------------------------------ ゲームモード・飛行・ベッド・食事

    private static ServerPlayer gameModePlayer;
    private static org.bukkit.event.player.PlayerGameModeChangeEvent.Cause gameModeCause;

    /** 次のゲームモード変更の理由を置く。CraftPlayer(PLUGIN)と /gamemode(COMMAND)から。 */
    public static void gameModeCause(final ServerPlayer player, final org.bukkit.event.player.PlayerGameModeChangeEvent.Cause cause) {
        if (!listening(org.bukkit.event.player.PlayerGameModeChangeEvent.getHandlerList())) {
            return;
        }

        gameModePlayer = player;
        gameModeCause = cause;
    }



    /**
     * PlayerBedEnterEvent。vanilla が寝られると判定して、寝る直前。
     *
     * <p>Paper は寝られない理由(遠い・塞がれている・怪物)のときも発火して、プラグインが
     * {@code setUseBed(ALLOW)} で寝かせられる。Shifu は vanilla が通した場合だけ発火する。
     *
     * @return 寝られない理由。寝てよければ null
     */
    public static com.mojang.datafixers.util.Either<net.minecraft.world.entity.player.Player.BedSleepingProblem, net.minecraft.util.Unit> bedEnter(
            final ServerPlayer player, final BlockPos pos) {
        if (!listening(org.bukkit.event.player.PlayerBedEnterEvent.getHandlerList())) {
            return null;
        }

        final com.mojang.datafixers.util.Either<net.minecraft.world.entity.player.Player.BedSleepingProblem, net.minecraft.util.Unit> result =
                CraftEventFactory.callPlayerBedEnterEvent(player, pos, com.mojang.datafixers.util.Either.right(net.minecraft.util.Unit.INSTANCE));

        return result.left().isPresent() ? result : null;
    }


    // ------------------------------------------------------------ 爆発

    /**
     * 爆発。{@code Explosion.finalizeExplosion} で、壊す位置の並びが
     * 並べ替えられた直後。Paper と同じ位置。
     *
     * <p>{@code source} があれば {@code EntityExplodeEvent}、無ければ
     * {@code BlockExplodeEvent}。プラグインが直した位置の並びを
     * {@code targetBlocks} に書き戻す。
     *
     * <p><b>未対応:</b> {@code setYield}。落ちる確率は vanilla の戦利品表
     * ({@code explosion_decay})が爆発の半径から読むので、渡す先が無い。
     * Paper は {@code BlockBehaviour.onExplosionHit} を書き換えて通している。
     *
     * @return 壊してよいか。取り消されたら false
     */
    public static boolean explode(final net.minecraft.world.level.Explosion explosion,
                                  final net.minecraft.world.level.Level level, final Vec3 center,
                                  final List<BlockPos> targetBlocks) {
        final Entity source = explosion.getDirectSourceEntity();
        final HandlerList handlers = source != null
                ? org.bukkit.event.entity.EntityExplodeEvent.getHandlerList()
                : org.bukkit.event.block.BlockExplodeEvent.getHandlerList();

        if (!listening(handlers)) {
            return true;
        }

        final List<org.bukkit.block.Block> blockList = new ArrayList<>();

        for (int i = targetBlocks.size() - 1; i >= 0; i--) {
            final org.bukkit.block.Block block = CraftBlock.at(level, targetBlocks.get(i));

            if (!block.getType().isAir()) {
                blockList.add(block);
            }
        }

        // Paper が Explosion の構築子で決めている既定の yield と同じ式
        float yield = explosion.getBlockInteraction() == net.minecraft.world.level.Explosion.BlockInteraction.DESTROY_WITH_DECAY
                ? 1.0F / explosion.radius()
                : 1.0F;
        yield = Float.isFinite(yield) ? yield : 0.0F;

        final org.bukkit.Location location = CraftLocation.toBukkit(center, level.getWorld());
        final boolean allowed;
        final List<org.bukkit.block.Block> result;

        if (source != null) {
            final org.bukkit.event.entity.EntityExplodeEvent event = new org.bukkit.event.entity.EntityExplodeEvent(
                    source.getBukkitEntity(), location, blockList, yield);
            allowed = event.callEvent();
            result = event.blockList();
        } else {
            final org.bukkit.block.Block block = location.getBlock();
            final org.bukkit.event.block.BlockExplodeEvent event = new org.bukkit.event.block.BlockExplodeEvent(
                    block, block.getState(), blockList, yield);
            allowed = event.callEvent();
            result = event.blockList();
        }

        targetBlocks.clear();

        for (final org.bukkit.block.Block block : result) {
            targetBlocks.add(((CraftBlock) block).getPosition());
        }

        return allowed;
    }

    // ------------------------------------------------------------ コンソール


    // ------------------------------------------------------------ 世界

    /**
     * WeatherChangeEvent。雨の切り替え直前。
     *
     * <p>1.20.6 の vanilla に理由を運ぶ引数は無いので、理由は常に {@code UNKNOWN}。
     */
    public static boolean weatherChange(final String levelName, final boolean raining) {
        if (!listening(org.bukkit.event.weather.WeatherChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(levelName);

        if (world == null) {
            return true;
        }

        return new org.bukkit.event.weather.WeatherChangeEvent(world, raining,
                org.bukkit.event.weather.WeatherChangeEvent.Cause.UNKNOWN).callEvent();
    }

    /** ThunderChangeEvent。雷雨の切り替え直前。理由は常に {@code UNKNOWN}。 */
    public static boolean thunderChange(final String levelName, final boolean thundering) {
        if (!listening(org.bukkit.event.weather.ThunderChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.World world = org.bukkit.Bukkit.getWorld(levelName);

        if (world == null) {
            return true;
        }

        return new org.bukkit.event.weather.ThunderChangeEvent(world, thundering,
                org.bukkit.event.weather.ThunderChangeEvent.Cause.UNKNOWN).callEvent();
    }

    /** EntityAddToWorldEvent。世界に入り切った直後。 */
    public static void entityAddToWorld(final Entity entity, final ServerLevel level) {
        if (!listening(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent.getHandlerList())) {
            return;
        }

        new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(
                entity.getBukkitEntity(), level.getWorld()).callEvent();
    }

    /** EntityRemoveFromWorldEvent。世界から抜けた直後。 */
    public static void entityRemoveFromWorld(final Entity entity, final ServerLevel level) {
        if (!listening(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent.getHandlerList())) {
            return;
        }

        new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(
                entity.getBukkitEntity(), level.getWorld()).callEvent();
    }


    // ------------------------------------------------------------ インベントリ


    /**
     * インベントリのクリック。vanilla の {@code containerMenu.clicked(...)} の直前。
     *
     * <p>Paper と同じ手順で {@code ClickType} と {@code InventoryAction} を導き、
     * {@code InventoryClickEvent}(作業台・鍛冶台・製図台では
     * {@code CraftItemEvent} / {@code SmithItemEvent} / {@code CartographyItemEvent})を
     * 発火する。ドラッグ({@code QUICK_CRAFT})では発火しない。
     *
     * <p>観戦者のクリックは vanilla が {@code clicked} へ届く前に弾くので、
     * 発火しない。Paper は取り消し済みのイベントを発火している。
     *
     * @return 発火したイベント。登録が無ければ null で、vanilla のまま
     */
    public static org.bukkit.event.inventory.InventoryClickEvent inventoryClick(
            final ServerPlayer player,
            final net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,
            final int slotIndex) {
        if (!listening(org.bukkit.event.inventory.InventoryClickEvent.getHandlerList())) {
            return null;
        }

        final org.bukkit.event.inventory.InventoryClickEvent event =
                InventoryClicks.build(player, packet, slotIndex);

        if (event == null) {
            return null;
        }

        clickedMenu = player.containerMenu;
        event.callEvent();

        return event;
    }


    /** 取り消されたクリックのあと。画面の中身を送り直して食い違いを消す。 */
    public static void cancelledInventoryClick(final ServerPlayer player,
                                               final org.bukkit.event.inventory.InventoryClickEvent event) {
        player.containerMenu.sendAllDataToRemote();
    }



    /** WorldLoadEvent。世界を読み終えた直後。 */
    public static void worldLoad(final net.minecraft.server.MinecraftServer server) {
        if (!listening(org.bukkit.event.world.WorldLoadEvent.getHandlerList())) {
            return;
        }

        for (final ServerLevel level : server.getAllLevels()) {
            new org.bukkit.event.world.WorldLoadEvent(level.getWorld()).callEvent();
        }
    }


    /** ServerListPingEvent に登録があるか。 */
    public static boolean serverListPingListening() {
        return listening(org.bukkit.event.server.ServerListPingEvent.getHandlerList());
    }

    /**
     * ServerListPingEvent。一覧に出す情報を送る直前。
     *
     * <p>CraftBukkit は無名の派生クラスでプレイヤーの並びまで差し替えられるように
     * している。ここでは <b>説明文・最大人数・今の人数</b>だけを反映する。
     * アイコンとプレイヤーの見本は vanilla のものをそのまま通す。
     *
     * @return 送る情報
     */
    public static net.minecraft.network.protocol.status.ServerStatus serverListPing(
            final net.minecraft.network.Connection connection,
            final net.minecraft.network.protocol.status.ServerStatus status) {
        final java.net.InetAddress address =
                connection.getRemoteAddress() instanceof java.net.InetSocketAddress socket
                        && socket.getAddress() != null
                        ? socket.getAddress() : java.net.InetAddress.getLoopbackAddress();
        final int max = status.players().map(net.minecraft.network.protocol.status.ServerStatus.Players::max)
                .orElse(0);
        final int online = status.players().map(net.minecraft.network.protocol.status.ServerStatus.Players::online)
                .orElse(0);
        final org.bukkit.event.server.ServerListPingEvent event = new org.bukkit.event.server.ServerListPingEvent(
                connection.hostname, address, PaperAdventure.asAdventure(status.description()), online, max);

        if (!event.callEvent()) {
            return status;
        }

        return new net.minecraft.network.protocol.status.ServerStatus(
                PaperAdventure.asVanilla(event.motd()),
                status.players().map(players -> new net.minecraft.network.protocol.status.ServerStatus.Players(
                        event.getMaxPlayers(), players.online(), players.sample())),
                status.version(), status.favicon(), status.enforcesSecureChat());
    }

}
