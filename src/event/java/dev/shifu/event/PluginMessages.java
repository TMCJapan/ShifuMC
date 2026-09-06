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
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
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
 * <p>控えは復号したスレッドの中に置き、同じ packet が次の handler に着いたところで接続ごとの列へ移す
 * ({@code afterDecode})。復号と、その packet が handler に着くのは同じスレッドの同じ呼び出しの中なので、
 * 別の接続の分と混ざらない。
 *
 * <p><b>プラグインが 1 つも入っていなければ何もしない。</b>そのときに実行される命令列は vanilla と同じ。
 */
public final class PluginMessages {
    private static final Identifier REGISTER = Identifier.withDefaultNamespace("register");
    private static final Identifier UNREGISTER = Identifier.withDefaultNamespace("unregister");

    /** 復号したスレッドが、次の handler に渡すまでの間だけ持つ控え。 */
    private static final ThreadLocal<Deque<byte[]>> DECODED = ThreadLocal.withInitial(ArrayDeque::new);

    /** 接続ごとの控えの列と、登録しているチャンネル。 */
    private static final Map<Connection, Deque<byte[]>> PENDING = new ConcurrentHashMap<>();
    private static final Map<Connection, Set<String>> CHANNELS = new ConcurrentHashMap<>();

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
        if (length == 0 || !enabled()) {
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

        if (!(message instanceof ServerboundCustomPayloadPacket)) {
            decoded.clear();

            return;
        }

        final Deque<byte[]> queue = PENDING.computeIfAbsent(connection, key -> new ArrayDeque<>());

        synchronized (queue) {
            while (!decoded.isEmpty()) {
                if (queue.size() >= 64) {
                    queue.removeFirst();
                }

                queue.addLast(decoded.removeFirst());
            }
        }
    }

    /** その接続が登録しているチャンネル。設定フェーズと本編で同じものを使う。 */
    public static Set<String> channelsFor(final Connection connection) {
        return CHANNELS.computeIfAbsent(connection, key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /** 接続を閉じたら忘れる。 */
    public static void forget(final Connection connection) {
        PENDING.remove(connection);
        CHANNELS.remove(connection);
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

    /**
     * {@code handleCustomPayload}(vanilla は空)の中身。
     *
     * <p>Paper と同じことをする: {@code minecraft:register} / {@code unregister} は
     * チャンネルの登録、{@code minecraft:brand} はクライアントの名乗り、それ以外は
     * Bukkit の Messenger へ渡す。
     *
     * <p>Paper は読めない本文で接続を切るが、vanilla は何もしないので切らずに記録だけ残す。
     */
    public static void handle(final ServerCommonPacketListenerImpl listener, final ServerboundCustomPayloadPacket packet) {
        if (!enabled()) {
            return;
        }

        if (packet.payload() instanceof BrandPayload brand) {
            listener.playerBrand = brand.brand();

            return;
        }

        if (!(packet.payload() instanceof DiscardedPayload discarded)) {
            return;
        }

        // vanilla は捨てる packet なので、ここから先はサーバースレッドで行う。
        // 控えを取り出すのは待ち行列へ回したあと(先に取り出すと、回された 2 回目で列がずれる)。
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, listener, net.minecraft.server.MinecraftServer.getServer().packetProcessor());

        final byte[] data = take(listener.connection);

        if (data == null) {
            return;
        }

        final Identifier identifier = discarded.id();

        try {
            final boolean register = REGISTER.equals(identifier);

            if (register || UNREGISTER.equals(identifier)) {
                int start = 0;

                for (int at = 0; at < data.length; at++) {
                    if (data[at] == 0) {
                        channel(listener, data, start, at, register);
                        start = at + 1;
                    }
                }

                channel(listener, data, start, data.length, register);

                return;
            }

            listener.cserver.getMessenger().dispatchIncomingMessage(listener.paperConnection(), identifier.toString(), data);
        } catch (final RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PluginMessages.class)
                    .error("チャンネル {} のプラグインメッセージを処理できない", identifier, e);
        }
    }

    private static byte[] take(final Connection connection) {
        final Deque<byte[]> queue = PENDING.get(connection);

        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            return queue.pollFirst();
        }
    }

    private static void channel(final ServerCommonPacketListenerImpl listener, final byte[] data,
                                final int from, final int to, final boolean register) {
        if (to - from <= 0) {
            return;
        }

        final io.papermc.paper.connection.PluginMessageBridgeImpl bridge = bridgeOf(listener);

        if (bridge == null) {
            return;
        }

        final String channel = new String(data, from, to - from, StandardCharsets.US_ASCII);

        if (register) {
            bridge.addChannel(channel);
        } else {
            bridge.removeChannel(channel);
        }
    }

    private static io.papermc.paper.connection.PluginMessageBridgeImpl bridgeOf(final ServerCommonPacketListenerImpl listener) {
        return switch (listener) {
            case net.minecraft.server.network.ServerGamePacketListenerImpl game -> game.player.getBukkitEntity();
            case net.minecraft.server.network.ServerConfigurationPacketListenerImpl configuration -> configuration.paperConnection;
            default -> null;
        };
    }
}
