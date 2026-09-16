package dev.shifu.remap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.Type;

/**
 * Spigot 向けのプラグインが reflection で欄・メソッドを名前で引くところの代わり。
 * {@link PluginRemapper} が Class.getDeclaredField("h") などの呼び出しをここへ向け直す。
 * 名前が難読化名(spigot 側)なら mojang 名に写してから引き、写せなければそのまま引く
 * (プラグイン自身のクラスや、初めから mojang 名で書いたものは変わらない)。
 */
public final class ReflectionProxy {
    private ReflectionProxy() {
    }

    /** 向け直す対象か(名前と、Class のメソッドとしての記述子)。 */
    static boolean handles(final String name, final String descriptor) {
        return switch (name) {
            case "getDeclaredField", "getField" -> "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor);
            case "getDeclaredMethod", "getMethod" -> "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor);
            default -> false;
        };
    }

    public static Field getDeclaredField(final Class<?> type, final String name) throws NoSuchFieldException {
        final String mapped = PluginRemapper.FIELDS_BY_NAME.get(PluginRemapper.spigotName(type) + ' ' + name);
        return type.getDeclaredField(mapped != null ? mapped : name);
    }

    public static Field getField(final Class<?> type, final String name) throws NoSuchFieldException {
        for (final String owner : ancestors(type)) {
            final String mapped = PluginRemapper.FIELDS_BY_NAME.get(owner + ' ' + name);
            if (mapped != null) {
                return type.getField(mapped);
            }
        }
        return type.getField(name);
    }

    public static Method getDeclaredMethod(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        final String mapped = PluginRemapper.METHODS_BY_PARAMS.get(PluginRemapper.spigotName(type) + ' ' + name + ' ' + params(params));
        return type.getDeclaredMethod(mapped != null ? mapped : name, params);
    }

    public static Method getMethod(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        final String tail = ' ' + name + ' ' + params(params);
        for (final String owner : ancestors(type)) {
            final String mapped = PluginRemapper.METHODS_BY_PARAMS.get(owner + tail);
            if (mapped != null) {
                return type.getMethod(mapped, params);
            }
        }
        return type.getMethod(name, params);
    }

    /** 引数の型を spigot 名の記述子 "(...)" に。 */
    private static String params(final Class<?>[] params) {
        final StringBuilder out = new StringBuilder("(");
        for (final Class<?> param : params == null ? new Class<?>[0] : params) {
            final String descriptor = Type.getDescriptor(param);
            if (descriptor.startsWith("L") || descriptor.startsWith("[")) {
                final int start = descriptor.indexOf('L');
                if (start >= 0) {
                    final String inner = descriptor.substring(start + 1, descriptor.length() - 1);
                    out.append(descriptor, 0, start + 1)
                            .append(PluginRemapper.CLASSES_BACK.getOrDefault(inner, inner)).append(';');
                    continue;
                }
            }
            out.append(descriptor);
        }
        return out.append(')').toString();
    }

    /** 実行時のクラスとその親を spigot 内部名で。 */
    private static Iterable<String> ancestors(final Class<?> type) {
        final Set<String> seen = new java.util.LinkedHashSet<>();
        final Deque<Class<?>> queue = new ArrayDeque<>();
        final Set<Class<?>> visited = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            final Class<?> current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            seen.add(PluginRemapper.spigotName(current));
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            for (final Class<?> face : current.getInterfaces()) {
                queue.add(face);
            }
        }
        return seen;
    }
}
