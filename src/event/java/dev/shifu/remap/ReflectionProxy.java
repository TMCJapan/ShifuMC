package dev.shifu.remap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Spigot 向けのプラグインが reflection で欄・メソッド・クラスを名前で引くところの代わり。
 * {@link PluginRemapper} が Class.getDeclaredField("h")、MethodHandles.Lookup.findVirtual、
 * Class.forName、Field.getName などの呼び出しをここへ向け直す。
 * 名前が難読化名(spigot 側)なら mojang 名に写してから引き、写せなければそのまま引く
 * (プラグイン自身のクラスや、初めから mojang 名で書いたものは変わらない)。
 *
 * <p>親を辿って候補を出すところは、候補を 1 つ選んで終わりにせず、順に引いてみて
 * 通ったものを返す。getField / getMethod / find* は public なものしか返さないので、
 * 先に当たった親の非 public な同名の欄を選ぶと、実際には継承している public な方を
 * 取り逃がす。
 */
public final class ReflectionProxy {
    /** Class.forName(String) の呼び手。読み込みに使うクラスローダを元と合わせるため。 */
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private ReflectionProxy() {
    }

    /**
     * 向け直す先の記述子。向け直さないときは null。
     * 仮想呼び出しは受け手を第 1 引数に足した記述子、static 呼び出しは同じ記述子を返す。
     */
    static String redirect(final int opcode, final String owner, final String name, final String descriptor) {
        if (opcode == Opcodes.INVOKESTATIC) {
            final boolean forName = "java/lang/Class".equals(owner) && "forName".equals(name)
                    && ("(Ljava/lang/String;)Ljava/lang/Class;".equals(descriptor)
                            || "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;".equals(descriptor));
            return forName ? descriptor : null;
        }
        if (opcode != Opcodes.INVOKEVIRTUAL || !handles(owner, name, descriptor)) {
            return null;
        }
        return "(L" + owner + ';' + descriptor.substring(1);
    }

    /** 向け直す対象か(所有クラスと名前と記述子)。 */
    private static boolean handles(final String owner, final String name, final String descriptor) {
        return switch (owner) {
            case "java/lang/Class" -> switch (name) {
                case "getDeclaredField", "getField" ->
                        "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(descriptor);
                case "getDeclaredMethod", "getMethod" ->
                        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(descriptor);
                case "getSimpleName" -> "()Ljava/lang/String;".equals(descriptor);
                default -> false;
            };
            case "java/lang/reflect/Field", "java/lang/reflect/Method" ->
                    "getName".equals(name) && "()Ljava/lang/String;".equals(descriptor);
            case "java/lang/invoke/MethodHandles$Lookup" -> switch (name) {
                case "findVirtual", "findStatic" ->
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;".equals(descriptor);
                case "findSpecial" ->
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;".equals(descriptor);
                case "findGetter", "findSetter", "findStaticGetter", "findStaticSetter" ->
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;".equals(descriptor);
                case "findVarHandle", "findStaticVarHandle" ->
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;".equals(descriptor);
                default -> false;
            };
            default -> false;
        };
    }

    // ------------------------------------------------------------ Class

    public static Field getDeclaredField(final Class<?> type, final String name) throws NoSuchFieldException {
        final String mapped = PluginRemapper.FIELDS_BY_NAME.get(PluginRemapper.spigotName(type) + ' ' + name);
        return type.getDeclaredField(mapped != null ? mapped : name);
    }

    public static Field getField(final Class<?> type, final String name) throws NoSuchFieldException {
        for (final String mapped : fieldNames(type, name)) {
            try {
                return type.getField(mapped);
            } catch (final NoSuchFieldException notInherited) {
                // その親の宣言は public でない。次の候補へ
            }
        }
        return type.getField(name);
    }

    public static Method getDeclaredMethod(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        final String mapped = PluginRemapper.METHODS_BY_PARAMS.get(PluginRemapper.spigotName(type) + ' ' + name + ' ' + params(params));
        return type.getDeclaredMethod(mapped != null ? mapped : name, params);
    }

    public static Method getMethod(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        for (final String mapped : methodNames(type, name, params)) {
            try {
                return type.getMethod(mapped, params);
            } catch (final NoSuchMethodException notInherited) {
                // その親の宣言は public でない。次の候補へ
            }
        }
        return type.getMethod(name, params);
    }

    /** 「EntityPlayer」などと突き合わせるプラグインのため、spigot 側の短い名前を返す。 */
    public static String getSimpleName(final Class<?> type) {
        if (type.isAnonymousClass() || type.isLocalClass()) {
            return type.getSimpleName();
        }
        final String spigot = PluginRemapper.CLASSES_BACK.get(type.getName().replace('.', '/'));
        if (spigot == null) {
            return type.getSimpleName();
        }
        return spigot.substring(Math.max(spigot.lastIndexOf('/'), spigot.lastIndexOf('$')) + 1);
    }

    /**
     * 名前を組み立てて呼ぶ Class.forName。定数の文字列は {@link PluginRemapper} が写すが、
     * 組み立てた名前は実行時にしか分からない。クラスローダは元の呼び手のものを使う
     * (Class.forName(String) は呼び手のローダで読むので、ここで引き継がないと
     * プラグイン自身のクラスが見えなくなる)。
     */
    public static Class<?> forName(final String name) throws ClassNotFoundException {
        return Class.forName(mapClassName(name), true, STACK.getCallerClass().getClassLoader());
    }

    public static Class<?> forName(final String name, final boolean initialize, final ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(mapClassName(name), initialize, loader);
    }

    private static String mapClassName(final String dotted) {
        final String mapped = PluginRemapper.CLASSES.get(dotted.replace('.', '/'));
        return mapped != null ? mapped.replace('/', '.') : dotted;
    }

    // ------------------------------------------------------------ Field / Method の名前

    /** getDeclaredFields() で並べた欄の名前を難読化名と突き合わせるプラグインのため。 */
    public static String getName(final Field field) {
        final String owner = field.getDeclaringClass().getName().replace('.', '/');
        if (!server(owner)) {
            return field.getName();
        }
        final String spigot = PluginRemapper.FIELDS_BACK.get(owner + ' ' + field.getName());
        return spigot != null ? spigot : field.getName();
    }

    public static String getName(final Method method) {
        final String owner = method.getDeclaringClass().getName().replace('.', '/');
        if (!server(owner)) {
            return method.getName();
        }
        final String spigot = PluginRemapper.METHODS_BACK.get(
                owner + ' ' + method.getName() + ' ' + params(method.getParameterTypes()));
        return spigot != null ? spigot : method.getName();
    }

    /** サーバーのクラスか。プラグイン自身のメソッドを並べるだけの呼び出しで引数の記述子を組まないため。 */
    private static boolean server(final String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/")
                || internalName.startsWith("org/bukkit/craftbukkit/");
    }

    // ------------------------------------------------------------ MethodHandles.Lookup

    public static MethodHandle findVirtual(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        for (final String mapped : methodNames(refc, name, type.parameterArray())) {
            try {
                return lookup.findVirtual(refc, mapped, type);
            } catch (final NoSuchMethodException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findVirtual(refc, name, type);
    }

    public static MethodHandle findStatic(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        for (final String mapped : methodNames(refc, name, type.parameterArray())) {
            try {
                return lookup.findStatic(refc, mapped, type);
            } catch (final NoSuchMethodException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findStatic(refc, name, type);
    }

    public static MethodHandle findSpecial(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final MethodType type, final Class<?> caller)
            throws NoSuchMethodException, IllegalAccessException {
        for (final String mapped : methodNames(refc, name, type.parameterArray())) {
            try {
                return lookup.findSpecial(refc, mapped, type, caller);
            } catch (final NoSuchMethodException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findSpecial(refc, name, type, caller);
    }

    public static MethodHandle findGetter(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findGetter(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findGetter(refc, name, type);
    }

    public static MethodHandle findSetter(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findSetter(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findSetter(refc, name, type);
    }

    public static MethodHandle findStaticGetter(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findStaticGetter(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findStaticGetter(refc, name, type);
    }

    public static MethodHandle findStaticSetter(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findStaticSetter(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findStaticSetter(refc, name, type);
    }

    public static VarHandle findVarHandle(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findVarHandle(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findVarHandle(refc, name, type);
    }

    public static VarHandle findStaticVarHandle(final MethodHandles.Lookup lookup, final Class<?> refc, final String name, final Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        for (final String mapped : fieldNames(refc, name)) {
            try {
                return lookup.findStaticVarHandle(refc, mapped, type);
            } catch (final NoSuchFieldException notInherited) {
                // 次の候補へ
            }
        }
        return lookup.findStaticVarHandle(refc, name, type);
    }

    // ------------------------------------------------------------ 候補

    /** 自分と親で引ける mojang 名を、辿った順に(重複なし)。 */
    private static Set<String> fieldNames(final Class<?> type, final String name) {
        final Set<String> names = new LinkedHashSet<>();
        for (final String owner : ancestors(type)) {
            final String mapped = PluginRemapper.FIELDS_BY_NAME.get(owner + ' ' + name);
            if (mapped != null) {
                names.add(mapped);
            }
        }
        return names;
    }

    private static Set<String> methodNames(final Class<?> type, final String name, final Class<?>[] params) {
        final String tail = ' ' + name + ' ' + params(params);
        final Set<String> names = new LinkedHashSet<>();
        for (final String owner : ancestors(type)) {
            final String mapped = PluginRemapper.METHODS_BY_PARAMS.get(owner + tail);
            if (mapped != null) {
                names.add(mapped);
            }
        }
        return names;
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
        final Set<String> seen = new LinkedHashSet<>();
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
