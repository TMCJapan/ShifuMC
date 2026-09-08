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

    // ------------------------------------------------------------ ブロック


    /** ブロックの設置を聞いている登録があるか。置く前の様子を控えるかの判断に使う。 */
    public static boolean blockPlaceListening() {
        return listening(org.bukkit.event.block.BlockPlaceEvent.getHandlerList());
    }


    // ------------------------------------------------------------ エンティティ


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

    // ------------------------------------------------------------ 移動


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


    // ------------------------------------------------------------ コンソール

}
