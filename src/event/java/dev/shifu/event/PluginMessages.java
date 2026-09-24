// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/**
 * プラグインメッセージ(Bukkit の Messenger)の受け口。
 *
 * <p>vanilla の {@code ServerCommonPacketListenerImpl.handleCustomPayload} は空で、
 * {@code DiscardedPayload} の codec は本文を {@code skipBytes} で捨てる。Paper は record に
 * {@code byte[] data} を足して codec を書き換えているが、Shifu は vanilla の行を触らないので、
 * <b>捨てる直前に控えを取る</b>形にする({@code remember})。読むだけで reader index は動かさないので、
 * vanilla の {@code skipBytes} は同じ量を飛ばす。
 *
 * <p>控えは復号したスレッドの中に置き、同じ packet が次の handler に着いたところで、その packet と
 * 組にして接続ごとの列へ移す({@code afterDecode})。復号と、その packet が handler に着くのは同じスレッドの
 * 同じ呼び出しの中なので、別の接続の分と混ざらない。取り出すときも packet で引く({@code take})。
 * 順番だけで対応させていたときは、取り出さずに戻る経路(設定フェーズ、本文 0 バイト)があるたびに
 * 以降の本文が 1 つずつずれていた。
 *
 * <p><b>プラグインが 1 つも入っていなければ何もしない。</b>そのときに実行される命令列は vanilla と同じ。
 */
public final class PluginMessages {

    /** 復号したスレッドが、次の handler に渡すまでの間だけ持つ控え。 */
    private static final ThreadLocal<Deque<byte[]>> DECODED = ThreadLocal.withInitial(ArrayDeque::new);

    /** 接続ごとの控えの列(packet と本文の組)と、設定フェーズで受けたチャンネルの登録と名乗り。 */
    private static final Map<Connection, Deque<Pending>> PENDING = new ConcurrentHashMap<>();
    private static final Map<Connection, Set<String>> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Connection, String> BRANDS = new ConcurrentHashMap<>();

    /** 復号した packet と、その本文の控え。 */
    private record Pending(Object packet, byte[] data) {
    }

    private PluginMessages() {
    }

    /** プラグインが 1 つでも入っているか。入っていなければ vanilla のまま何もしない。 */
    private static boolean enabled() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();

        return server != null && server.server != null && server.server.getPluginManager().getPlugins().length != 0;
    }

    /**
     * {@code DiscardedPayload} の codec が本文を捨てる直前。読むだけで reader index は動かさない。
     *
     * @param buf    これから捨てられる本文が入っている buffer
     * @param length 本文の長さ(vanilla が数えた値)
     */
    public static void remember(final FriendlyByteBuf buf, final int length) {
        // 本文 0 バイトも控える。控えない packet があると、その packet の本文として次の控えを渡してしまう
        if (!enabled()) {
            return;
        }

        final byte[] data = new byte[length];
        buf.getBytes(buf.readerIndex(), data);

        final Deque<byte[]> queue = DECODED.get();

        // 次の handler が拾わなかった分(プレイヤーの接続以外)が溜まらないように上限を置く
        if (queue.size() >= 8) {
            queue.removeFirst();
        }

        queue.addLast(data);
    }

    /** 復号した packet が handler に着いたところ。控えを接続ごとの列へ移す。 */
    private static void afterDecode(final Connection connection, final Object message) {
        final Deque<byte[]> decoded = DECODED.get();

        if (decoded.isEmpty()) {
            return;
        }

        // 1 つの packet の復号で控えは 1 つ。前に残っているのは、復号の途中で落ちた packet の分
        final byte[] data = decoded.peekLast();
        decoded.clear();

        if (!(message instanceof ServerboundCustomPayloadPacket)) {
            return;
        }

        final Deque<Pending> queue = PENDING.computeIfAbsent(connection, key -> new ArrayDeque<>());

        synchronized (queue) {
            if (queue.size() >= 64) {
                queue.removeFirst();
            }

            queue.addLast(new Pending(message, data));
        }
    }

    /** 設定フェーズで登録されたチャンネル。本編の listener ができたところで {@link #joined} が CraftPlayer へ移す。 */
    public static Set<String> channelsFor(final Connection connection) {
        return CHANNELS.computeIfAbsent(connection, key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /**
     * 本編の listener ができたところ(構築子で {@code player.connection} を入れた直後)。
     * 設定フェーズで受けたチャンネルの登録と名乗りを、プレイヤーに入れる。
     *
     * <p>Paper 1.20.6 は設定フェーズの時点で ServerPlayer を作っていて、その CraftPlayer に直に
     * {@code addChannel} し、{@code clientBrandName} を入れる。vanilla は設定フェーズの終わりに
     * ServerPlayer を作るので、それまで接続ごとに持っておいてここで渡す。
     * PlayerRegisterChannelEvent もここで出る(Paper は設定フェーズの中で出す)。
     *
     * <p>読んだ位置: Paper-Server@HEAD src/main/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java:150-172
     * と ServerConfigurationPacketListenerImpl.java:151(getPlayerForLogin に設定フェーズの player を渡す)
     */
    public static void joined(final net.minecraft.server.network.ServerGamePacketListenerImpl listener) {
        final Set<String> channels = CHANNELS.remove(listener.connection);
        final String brand = BRANDS.remove(listener.connection);

        if (brand != null) {
            listener.player.clientBrandName = brand;
        }

        if (channels == null || channels.isEmpty()) {
            return;
        }

        final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = listener.player.getBukkitEntity();

        for (final String channel : channels) {
            try {
                bukkit.addChannel(channel);
            } catch (final RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PluginMessages.class)
                        .error("チャンネル {} を登録できない", channel, e);
            }
        }
    }

    /** 接続を閉じたら忘れる。 */
    public static void forget(final Connection connection) {
        PENDING.remove(connection);
        CHANNELS.remove(connection);
        BRANDS.remove(connection);
    }

    /** 復号した packet を接続ごとの列と結びつける handler を差し込む。 */
    public static void install(final ChannelPipeline pipeline, final Connection connection) {
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, "shifu_plugin_messages", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(final ChannelHandlerContext context, final Object message) throws Exception {
                afterDecode(connection, message);
                super.channelRead(context, message);
            }
        });
    }


    private static final ResourceLocation REGISTER = new ResourceLocation("register");
    private static final ResourceLocation UNREGISTER = new ResourceLocation("unregister");

    /**
     * {@code handleCustomPayload}(vanilla は空)の中身。
     *
     * <p>Paper と同じことをする: {@code minecraft:register} / {@code unregister} は
     * チャンネルの登録、{@code minecraft:brand} はクライアントの名乗り、それ以外は
     * Bukkit の Messenger へ渡す。
     *
     * <p>Paper は読めない本文で接続を切るが、vanilla は何もしないので切らずに記録だけ残す。
     *
     * <p>1.20.6 の {@code ServerCommonPacketListenerImpl} は自分では人を持たない
     * (欄は本編の listener 側にある)ので、そちらから取る。設定フェーズはまだ人がいないので、
     * チャンネルの登録と名乗りは接続ごとに持っておき、{@link #joined} で入れる。
     * それ以外のチャンネルは渡す先の Player が無いので捨てる。
     */
    public static void handle(final ServerCommonPacketListenerImpl listener, final ServerboundCustomPayloadPacket packet) {
        if (!enabled()) {
            return;
        }

        final net.minecraft.server.level.ServerPlayer player =
                listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game ? game.player : null;

        if (packet.payload() instanceof BrandPayload brand) {
            if (player != null) {
                player.clientBrandName = brand.brand();
            } else {
                BRANDS.put(listener.connection, brand.brand());
            }

            return;
        }

        if (!(packet.payload() instanceof DiscardedPayload discarded)) {
            return;
        }

        // vanilla は捨てる packet なので、ここから先はサーバースレッドで行う。
        // 控えを取り出すのは待ち行列へ回したあと(取り出したあとで回すと、回された 2 回目で本文が無い)。
        if (player != null) {
            net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, listener, player.serverLevel());
        } else {
            net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, listener,
                    net.minecraft.server.MinecraftServer.getServer());
        }

        final byte[] data = take(listener.connection, packet);

        if (data == null) {
            return;
        }

        final ResourceLocation identifier = discarded.id();

        try {
            final boolean register = REGISTER.equals(identifier);

            if (register || UNREGISTER.equals(identifier)) {
                for (final String channel : new String(data, StandardCharsets.UTF_8).split("\0")) {
                    if (channel.isEmpty()) {
                        continue;
                    }

                    if (player == null) {
                        if (register) {
                            channelsFor(listener.connection).add(channel);
                        } else {
                            channelsFor(listener.connection).remove(channel);
                        }
                    } else if (register) {
                        player.getBukkitEntity().addChannel(channel);
                    } else {
                        player.getBukkitEntity().removeChannel(channel);
                    }
                }

                return;
            }

            if (player == null) {
                return;
            }

            final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = player.getBukkitEntity();

            net.minecraft.server.MinecraftServer.getServer().server.getMessenger()
                    .dispatchIncomingMessage(bukkit, identifier.toString(), data);
        } catch (final RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PluginMessages.class)
                    .error("チャンネル {} のプラグインメッセージを処理できない", identifier, e);
        }
    }

    /**
     * その packet の本文の控えを取り出す。packet は復号した順に処理されるので、列でそれより前にある控えは
     * handler まで来なかった packet の分で、ここで一緒に捨てる。見つからなければ列は触らない。
     */
    private static byte[] take(final Connection connection, final ServerboundCustomPayloadPacket packet) {
        final Deque<Pending> queue = PENDING.get(connection);

        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            if (queue.stream().noneMatch(pending -> pending.packet() == packet)) {
                return null;
            }

            while (true) {
                final Pending pending = queue.removeFirst();

                if (pending.packet() == packet) {
                    return pending.data();
                }
            }
        }
    }



    /**
     * 送る側のプラグインメッセージ。
     *
     * <p>vanilla の {@code DiscardedPayload} は成分が id 1 つで、codec の書く側は空
     * ({@code (value, buf) -> {}})。record の成分は足せないので、送るときだけ使う型を
     * こちらに置き、{@code CustomPacketPayload.codec} が id を書いたところで本文を書く
     * ({@code patches/wire/net-minecraft-network-protocol-common-custom-CustomPacketPayload.rules})。
     *
     * <p>vanilla は payload の型ではなく id で codec を選ぶので、控えを {@code DiscardedPayload} に
     * 結ぶ形にすると、プラグインのチャンネル名が vanilla や MOD の登録済みの型と重なったときに
     * encoder の cast で {@code ClassCastException} になる。型で先に分けるとそこを通らない。
     *
     * <p>読んだ位置: Paper の DiscardedPayload は record に {@code io.netty.buffer.ByteBuf data} を足して
     * codec の書く側で {@code writeBytes} する
     * ({@code Paper-Server src/main/java/net/minecraft/network/protocol/common/custom/DiscardedPayload.java:7,11})。
     */
    public record Outgoing(ResourceLocation id, byte[] data) implements CustomPacketPayload {

        @Override
        public CustomPacketPayload.Type<Outgoing> type() {
            return new CustomPacketPayload.Type<>(this.id);
        }
    }

    /**
     * {@code CustomPacketPayload.codec} が id を書いたあと。Shifu が作った送る側の payload なら
     * 本文を書いて、vanilla の codec 選びへは進まない。
     *
     * @return 本文を書いたか
     */
    public static boolean write(final FriendlyByteBuf buf, final CustomPacketPayload payload) {
        if (!(payload instanceof Outgoing outgoing)) {
            return false;
        }

        buf.writeBytes(outgoing.data());

        return true;
    }
}
