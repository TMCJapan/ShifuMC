// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 生成した規則({@code patches/events/generated})から、発火の前に登録の有無を見るためのもの。
 *
 * <p>生成した規則は Paper の発火行をそのまま写しているので、イベントの型の名前しか手元に無い。
 * 手で書いた {@code ShifuEvents.listening(HandlerList)} は呼ぶ側が
 * {@code getHandlerList()} を書ける形だが、こちらは型から引く。
 *
 * <p>{@code getHandlerList} は Bukkit のイベントが静的に持つ約束で、
 * {@link Event#getHandlers()} が返すものと同じ {@link HandlerList} を返す。
 * 自分で持たない型(たとえば {@code EntityDamageByEntityEvent})は、
 * {@link Class#getMethod} が親の {@code getHandlerList} を見つけるので、
 * Bukkit が登録に使う {@code getRegistrationClass} と同じ列に辿り着く。
 * 読んだ位置: Paper-API {@code src/main/java/org/bukkit/plugin/SimplePluginManager.java:748}
 * の {@code getRegistrationClass}。
 *
 * <p>引くのは 1 つの型につき 1 度きりで、あとは {@link ClassValue} の表から取る。
 * 圧力板・蜘蛛の巣・焚き火の {@code entityInside} は毎 tick 通るので、
 * そのたびに反射で引くわけにはいかない。
 */
public final class EventGuard {
    /** イベントの型 → その {@link HandlerList}。 */
    private static final ClassValue<HandlerList> HANDLERS = new ClassValue<>() {
        @Override
        protected HandlerList computeValue(final Class<?> type) {
            try {
                return (HandlerList) type.getMethod("getHandlerList").invoke(null);
            } catch (final ReflectiveOperationException problem) {
                throw new IllegalStateException(type.getName() + " に getHandlerList が無い", problem);
            }
        }
    };

    private EventGuard() {
    }

    /** そのイベントを聞いている登録があるか。無ければ呼ぶ側はイベントを組み立てない。 */
    public static boolean listening(final Class<? extends Event> type) {
        return HANDLERS.get(type).getRegisteredListeners().length != 0;
    }
}
