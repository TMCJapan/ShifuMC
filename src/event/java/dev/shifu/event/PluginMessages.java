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
 * <p>控えは復号したスレッドの中に置き、同じ packet が次の handler に着いたところで接続ごとの列へ移す
 * ({@code afterDecode})。復号と、その packet が handler に着くのは同じスレッドの同じ呼び出しの中なので、
 * 別の接続の分と混ざらない。
 *
 * <p><b>プラグインが 1 つも入っていなければ何もしない。</b>そのときに実行される命令列は vanilla と同じ。
 */
public final class PluginMessages {

    /** 復号したスレッドが、次の handler に渡すまでの間だけ持つ控え。 */
    private static final ThreadLocal<Deque<byte[]>> DECODED = ThreadLocal.withInitial(ArrayDeque::new);

    /** 接続ごとの控えの列と、登録しているチャンネル。 */
    private static final Map<Connection, Deque<byte[]>> PENDING = new ConcurrentHashMap<>();
    private static final Map<Connection, Set<String>> CHANNELS = new ConcurrentHashMap<>();

    private PluginMessages() {
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


    private static byte[] take(final Connection connection) {
        final Deque<byte[]> queue = PENDING.get(connection);

        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            return queue.pollFirst();
        }
    }


}
