package dev.shifu.remap;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginDescriptionFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.yaml.snakeyaml.Yaml;

/**
 * Spigot 向けに組んだプラグインのバイトコードを、読み込むときに mojang 名へ写す。
 *
 * <p>Spigot のサーバーは NMS のクラスを Spigot の名前(EntityPlayer)、欄とメソッドを
 * 難読化名(b、fr)で持つ。Shifu は mojang 名(ServerPlayer、connection、getInventory)で
 * 動くので、そのままでは ClassNotFoundException / NoSuchMethodError になる。
 * Paper は 1.20.5 から同じことを起動時にやる(プラグイン jar を写して置き直す)。
 * ここではクラスを 1 つ定義する直前に写す(CraftMagicNumbers.processClass と、
 * 1.19.4 では PaperSimplePluginClassLoader.findClass)。
 *
 * <p>表は tools/make_plugin_mappings.py が組んで、jar の META-INF/mappings/shifu-plugin-remap.txt
 * に入る。無いときは何も写さず、SEVERE を 1 行出す。
 *
 * <p>メソッド名は宣言したクラスで引く必要がある(EntityPlayer.fr() の fr は Player の宣言)。
 * 呼び出し箇所の所有クラスから親へ辿る。親がサーバーのクラスなら reflection で、
 * プラグインのクラスなら jar を先に走査して控えた継承関係で辿る(index)。控えるのは
 * plugins フォルダ・update フォルダ・-add-plugin の jar 全部で、親が別のプラグインの
 * クラスでも辿れるようにしてある。
 *
 * <p>lambda が実装する interface のメソッド名(invokedynamic の名前)も写す。
 * ClassRemapper は写さないので、難読化名の関数型 interface を実装した lambda が
 * AbstractMethodError になっていた。
 *
 * <p>reflection で欄やメソッドを名前で引くところ(Class.getDeclaredField("h")、
 * MethodHandles.Lookup.findVirtual、Class.forName など)は、呼び出しを
 * {@link ReflectionProxy} に向け直して、実行時のクラスで名前を写す。
 *
 * <p>manifest に paperweight-mappings-namespace: mojang とあるプラグインは写さない
 * (Paper と同じ印)。
 */
public final class PluginRemapper {
    private static final Logger LOGGER = Logger.getLogger("Shifu");
    private static final String RESOURCE = "/META-INF/mappings/shifu-plugin-remap.txt";
    private static final String NAMESPACE_ATTRIBUTE = "paperweight-mappings-namespace";

    /** spigot 内部名 → mojang 内部名。 */
    static final Map<String, String> CLASSES = new HashMap<>();
    /** mojang 内部名 → spigot 内部名。 */
    static final Map<String, String> CLASSES_BACK = new HashMap<>();
    /** "所有クラス 名前 記述子"(spigot 名)→ mojang 名。 */
    static final Map<String, String> FIELDS = new HashMap<>();
    static final Map<String, String> METHODS = new HashMap<>();
    /** reflection 用。"所有クラス 名前" → mojang 名(欄)、"所有クラス 名前 (引数)" → mojang 名(メソッド)。 */
    static final Map<String, String> FIELDS_BY_NAME = new HashMap<>();
    static final Map<String, String> METHODS_BY_PARAMS = new HashMap<>();
    /**
     * 実行時の Field / Method から spigot 名を引く。鍵は "mojang 所有クラス mojang 名" と
     * "mojang 所有クラス mojang 名 (spigot 引数)"。getDeclaredFields() で並べた欄の名前を
     * 難読化名と突き合わせるプラグインのため。
     */
    static final Map<String, String> FIELDS_BACK = new HashMap<>();
    static final Map<String, String> METHODS_BACK = new HashMap<>();
    static final boolean ENABLED = load();

    /** プラグイン名(PluginDescriptionFile.getName() と同じ形)→ その jar の中身。 */
    private static final Map<String, JarIndex> INDEX = new ConcurrentHashMap<>();
    /** 走査した jar 全部のクラス → 親と interface。親が別のプラグインのクラスのときに引く。 */
    private static final Map<String, String[]> SHARED_SUPERS = new ConcurrentHashMap<>();
    /** 走査済みの jar("道 更新時刻 大きさ")。同じ jar を二度読まないため。 */
    private static final Set<String> SCANNED = ConcurrentHashMap.newKeySet();
    /** 走査し直しても見つからなかったプラグイン名。クラスごとに走査し直さないため。 */
    private static final Set<String> RESCANNED = ConcurrentHashMap.newKeySet();
    /** 控えが無いプラグインの分。自分の jar が無いだけで、他の jar とサーバーは辿れる。 */
    private static final Hierarchy UNINDEXED = new Hierarchy(Collections.emptyMap());
    private static volatile File pluginFolder;
    private static volatile File updateFolder;
    private static volatile List<File> extraJars = List.of();

    private PluginRemapper() {
    }

    private static boolean load() {
        try (InputStream in = PluginRemapper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.severe("[Shifu] plugin remap table " + RESOURCE + " is not in the server jar;"
                        + " every plugin built for Spigot will fail with ClassNotFoundException /"
                        + " NoSuchMethodError. tools/make_plugin_mappings.py did not run (tools/closure.sh)");
                return false;
            }
            final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] parts = line.split("\t");
                switch (parts[0]) {
                    case "c" -> {
                        CLASSES.put(parts[1], parts[2]);
                        CLASSES_BACK.put(parts[2], parts[1]);
                    }
                    case "f" -> {
                        FIELDS.put(parts[1] + ' ' + parts[2] + ' ' + parts[3], parts[4]);
                        FIELDS_BY_NAME.put(parts[1] + ' ' + parts[2], parts[4]);
                        FIELDS_BACK.put(CLASSES.getOrDefault(parts[1], parts[1]) + ' ' + parts[4], parts[2]);
                    }
                    case "m" -> {
                        final String params = parts[3].substring(0, parts[3].indexOf(')') + 1);
                        METHODS.put(parts[1] + ' ' + parts[2] + ' ' + parts[3], parts[4]);
                        METHODS_BY_PARAMS.put(parts[1] + ' ' + parts[2] + ' ' + params, parts[4]);
                        METHODS_BACK.put(CLASSES.getOrDefault(parts[1], parts[1]) + ' ' + parts[4] + ' ' + params, parts[2]);
                    }
                    default -> {
                    }
                }
            }
            LOGGER.info("[Shifu] plugin remap: " + CLASSES.size() + " classes, " + FIELDS.size() + " fields, "
                    + METHODS.size() + " methods (spigot -> mojang)");
            return true;
        } catch (final IOException broken) {
            LOGGER.log(Level.SEVERE, "[Shifu] plugin remap table could not be read", broken);
            return false;
        }
    }

    // ------------------------------------------------------------ jar の走査

    /**
     * plugins フォルダ・update フォルダ・-add-plugin の jar を走査して、
     * プラグインごとの継承関係を控える。update フォルダの jar は plugins の同名を置き換える
     * ので、あとから読んで控えを上書きする(読んだ位置: Paper-API
     * src/main/java/org/bukkit/plugin/SimplePluginManager.java の checkUpdate)。
     */
    public static void index(final File folder, final File update, final List<File> extra) {
        if (!ENABLED) {
            return;
        }
        pluginFolder = folder;
        updateFolder = update;
        extraJars = extra == null ? List.of() : List.copyOf(extra);
        scan();
    }

    /** まだ読んでいない jar だけを読む。 */
    private static void scan() {
        final List<File> jars = new ArrayList<>();
        jars.addAll(listJars(pluginFolder));
        jars.addAll(extraJars);
        jars.addAll(listJars(updateFolder));
        boolean added = false;
        for (final File jar : jars) {
            if (SCANNED.add(jar.getAbsolutePath() + ' ' + jar.lastModified() + ' ' + jar.length())) {
                added |= indexJar(jar);
            }
        }
        if (added) {
            UNINDEXED.forget();
            for (final JarIndex index : INDEX.values()) {
                index.hierarchy.forget();
            }
        }
    }

    private static List<File> listJars(final File folder) {
        if (folder == null) {
            return List.of();
        }
        final File[] listed = folder.listFiles((dir, name) -> name.endsWith(".jar"));
        return listed == null ? List.of() : List.of(listed);
    }

    private static boolean indexJar(final File file) {
        try (JarFile jar = new JarFile(file)) {
            final String name = pluginName(jar);
            if (name == null) {
                return false;
            }
            final Manifest manifest = jar.getManifest();
            final boolean mojang = manifest != null
                    && "mojang".equals(manifest.getMainAttributes().getValue(NAMESPACE_ATTRIBUTE));
            final Map<String, String[]> supers = new HashMap<>();
            if (!mojang) {
                final Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(entry)) {
                        final ClassReader reader = new ClassReader(in.readAllBytes());
                        final String[] interfaces = reader.getInterfaces();
                        final String[] parents = new String[interfaces.length + 1];
                        parents[0] = reader.getSuperName();
                        System.arraycopy(interfaces, 0, parents, 1, interfaces.length);
                        supers.put(reader.getClassName(), parents);
                    } catch (final Exception notAClass) {
                        // 壊れた class は定義するときに Bukkit 側が落とす
                    }
                }
            }
            SHARED_SUPERS.putAll(supers);
            INDEX.put(key(name), new JarIndex(mojang, supers));
            return true;
        } catch (final IOException unreadable) {
            // 開けない jar は Bukkit 側が断る
            return false;
        }
    }

    /** plugin.yml か paper-plugin.yml の name。どちらも無ければ null。 */
    private static String pluginName(final JarFile jar) {
        for (final String yml : new String[] {"plugin.yml", "paper-plugin.yml"}) {
            final JarEntry entry = jar.getJarEntry(yml);
            if (entry == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                final Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map && map.get("name") instanceof String name) {
                    return name;
                }
            } catch (final Exception unreadable) {
                // 読めない yml は Bukkit 側が断る
            }
        }
        return null;
    }

    /**
     * 控えの鍵。yml の name そのままではなく、PluginDescriptionFile と同じ形に直す
     * (読んだ位置: Paper-API src/main/java/org/bukkit/plugin/PluginDescriptionFile.java の
     * loadMap、name.replace(' ', '_'))。直さないと、名前に空白のあるプラグイン
     * ("Multiverse-Core" ではなく "My Plugin" のような yml)の控えが引けず、
     * 継承関係が空のまま写してしまう。
     */
    private static String key(final String name) {
        return name.replace(' ', '_');
    }

    // ------------------------------------------------------------ クラスの書き換え

    /** CraftMagicNumbers.processClass から(Bukkit のプラグイン)。 */
    public static byte[] remap(final PluginDescriptionFile pdf, final String path, final byte[] bytes) {
        return remap(pdf.getName(), path, bytes);
    }

    /** クラスを定義する直前に。写すものが無ければそのまま返す。 */
    public static byte[] remap(final String plugin, final String path, final byte[] bytes) {
        if (!ENABLED) {
            return bytes;
        }
        final String key = key(plugin);
        JarIndex index = INDEX.get(key);
        if (index == null && pluginFolder != null && RESCANNED.add(key)) {
            scan(); // あとから置かれた jar(reload、PlugMan)。見つからなければもう走査し直さない
            index = INDEX.get(key);
        }
        if (index != null && index.mojang) {
            return bytes;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, 0);
            final Hierarchy hierarchy = index == null ? UNINDEXED : index.hierarchy;
            reader.accept(new ReflectionAwareRemapper(writer, new SpigotToMojang(hierarchy)), 0);
            return writer.toByteArray();
        } catch (final Exception broken) {
            LOGGER.log(Level.SEVERE, "[Shifu] plugin remap failed for " + plugin + ":" + path, broken);
            return bytes;
        }
    }

    private static final class JarIndex {
        private final boolean mojang;
        private final Hierarchy hierarchy;

        JarIndex(final boolean mojang, final Map<String, String[]> supers) {
            this.mojang = mojang;
            this.hierarchy = new Hierarchy(supers);
        }
    }

    /** 所有クラス(spigot 内部名)から、自分と親をすべて spigot 内部名で並べる。 */
    static final class Hierarchy {
        private final Map<String, String[]> ownSupers;
        private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

        Hierarchy(final Map<String, String[]> ownSupers) {
            this.ownSupers = ownSupers;
        }

        /** 新しい jar を控えたあと。前に「親を辿れなかった」と答えた分を捨てる。 */
        void forget() {
            this.cache.clear();
        }

        List<String> of(final String owner) {
            List<String> found = this.cache.get(owner);
            if (found != null) {
                return found;
            }
            final Set<String> seen = new LinkedHashSet<>();
            final Deque<String> queue = new ArrayDeque<>();
            queue.add(owner);
            while (!queue.isEmpty()) {
                final String current = queue.poll();
                if (current == null || !seen.add(current) || current.startsWith("java/")) {
                    continue;
                }
                String[] fromPlugin = this.ownSupers.get(current);
                if (fromPlugin == null) {
                    fromPlugin = SHARED_SUPERS.get(current);
                }
                if (fromPlugin != null) {
                    Collections.addAll(queue, fromPlugin);
                    continue;
                }
                Collections.addAll(queue, serverParents(current));
            }
            found = new ArrayList<>(seen);
            this.cache.put(owner, found);
            return found;
        }

        /** サーバーのクラスの親を spigot 内部名で。サーバーに無いクラス(ライブラリ)は親を辿らない。 */
        private static String[] serverParents(final String spigotName) {
            final String mojangName = CLASSES.getOrDefault(spigotName, spigotName);
            if (!mojangName.startsWith("net/minecraft/") && !mojangName.startsWith("com/mojang/")
                    && !mojangName.startsWith("org/bukkit/craftbukkit/")) {
                return new String[0];
            }
            final Class<?> type;
            try {
                type = Class.forName(mojangName.replace('/', '.'), false, PluginRemapper.class.getClassLoader());
            } catch (final Throwable missing) {
                return new String[0];
            }
            return parentsOf(type);
        }
    }

    /** 実行時のクラスの親(spigot 内部名)。reflection の向け直しでも使う。 */
    static String[] parentsOf(final Class<?> type) {
        final List<String> parents = new ArrayList<>();
        if (type.getSuperclass() != null) {
            parents.add(spigotName(type.getSuperclass()));
        }
        for (final Class<?> face : type.getInterfaces()) {
            parents.add(spigotName(face));
        }
        return parents.toArray(new String[0]);
    }

    static String spigotName(final Class<?> type) {
        final String mojangName = type.getName().replace('.', '/');
        return CLASSES_BACK.getOrDefault(mojangName, mojangName);
    }

    private static final class SpigotToMojang extends Remapper {
        private final Hierarchy hierarchy;

        SpigotToMojang(final Hierarchy hierarchy) {
            this.hierarchy = hierarchy;
        }

        @Override
        public String map(final String internalName) {
            return CLASSES.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(final String owner, final String name, final String descriptor) {
            if (name.startsWith("<")) {
                return name;
            }
            return member(METHODS, owner, name, descriptor);
        }

        @Override
        public String mapFieldName(final String owner, final String name, final String descriptor) {
            return member(FIELDS, owner, name, descriptor);
        }

        private String member(final Map<String, String> table, final String owner, final String name, final String descriptor) {
            final String tail = ' ' + name + ' ' + descriptor;
            for (final String type : this.hierarchy.of(owner)) {
                final String mapped = table.get(type + tail);
                if (mapped != null) {
                    return mapped;
                }
            }
            return name;
        }

        /** reflection 用の文字列。Spigot のクラス名そのもの(dotted)なら写す。 */
        @Override
        public Object mapValue(final Object value) {
            if (value instanceof String text && text.startsWith("net.minecraft.")) {
                final String mapped = CLASSES.get(text.replace('.', '/'));
                if (mapped != null) {
                    return mapped.replace('/', '.');
                }
            }
            return super.mapValue(value);
        }
    }

    /**
     * ClassRemapper に、reflection で名前を引く呼び出しを {@link ReflectionProxy} の
     * static メソッドへ向け直す処理と、invokedynamic の名前を写す処理を足したもの。
     * reflection の名前は実行時にしか分からないので、写すのは呼ばれたときに行う。
     */
    private static final class ReflectionAwareRemapper extends ClassRemapper {
        private static final String PROXY = "dev/shifu/remap/ReflectionProxy";

        ReflectionAwareRemapper(final ClassVisitor next, final Remapper remapper) {
            super(next, remapper);
        }

        @Override
        protected MethodVisitor createMethodRemapper(final MethodVisitor next) {
            return new MethodRemapper(this.api, next, this.remapper) {
                @Override
                public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
                    final String proxy = ReflectionProxy.redirect(opcode, owner, name, descriptor);
                    if (proxy != null) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, PROXY, name, proxy, false);
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }

                /**
                 * lambda が実装する interface のメソッド名。MethodRemapper は
                 * mapInvokeDynamicMethodName を呼ぶが、既定は名前をそのまま返すので写らない
                 * (読んだ位置: org.ow2.asm:asm-commons の
                 * org/objectweb/asm/commons/MethodRemapper.java の visitInvokeDynamicInsn と
                 * Remapper.java の mapInvokeDynamicMethodName)。
                 * LambdaMetafactory の第 1 引数が消去後の SAM の型なので、それを記述子に使う。
                 */
                @Override
                public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bootstrap, final Object... arguments) {
                    String sam = name;
                    final Type owner = Type.getReturnType(descriptor);
                    if (owner.getSort() == Type.OBJECT && arguments.length > 0
                            && arguments[0] instanceof Type erased && erased.getSort() == Type.METHOD) {
                        sam = this.remapper.mapMethodName(owner.getInternalName(), name, erased.getDescriptor());
                    }
                    super.visitInvokeDynamicInsn(sam, descriptor, bootstrap, arguments);
                }
            };
        }
    }
}
