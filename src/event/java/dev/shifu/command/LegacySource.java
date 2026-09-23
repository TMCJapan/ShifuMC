// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.destroystokyo.paper.brigadier.BukkitBrigadierCommand;
import com.destroystokyo.paper.brigadier.BukkitBrigadierCommandSource;
import com.destroystokyo.paper.event.brigadier.CommandRegisteredEvent;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Paper-MojangAPI の旧 brigadier API と vanilla のコマンドの間の橋。
 *
 * <p>Paper は NMS の {@code CommandSourceStack} に {@code BukkitBrigadierCommandSource} を実装させて
 * 「同じオブジェクト」にしている。Shifu は vanilla のクラス宣言に interface を足さない
 * (docs/ARCHITECTURE.md の「Paper の brigadier API」。1.20.6 以降の {@code ApiSource} と同じ扱い)。
 *
 * <p>この API で NMS の source がプラグインに渡るのは {@code CommandRegisteredEvent} だけ
 * (Paper 1.19.4 の {@code BukkitCommandWrapper.register})。イベントには旧 API の型で書いた
 * 実行の口を渡し、プラグインが組んだノード(述語・実行・補完・リダイレクト)は vanilla の
 * dispatcher に入れる前に、NMS の source を受ける形に組み直す。
 *
 * <p>包みは呼ぶたびに作る。同じ source を包んだものは {@code equals} で等しいが、同一ではない。
 */
public record LegacySource(CommandSourceStack handle) implements BukkitBrigadierCommandSource {

    // 4 つとも Paper 1.19.4 の CommandSourceStack の本体と同じ。
    // 読んだ位置: D:/.pw194/Paper-Server の HEAD、src/main/java/net/minecraft/commands/CommandSourceStack.java:176-191, 420

    @Override
    public org.bukkit.entity.Entity getBukkitEntity() {
        return this.handle.getEntity() != null ? this.handle.getEntity().getBukkitEntity() : null;
    }

    @Override
    public org.bukkit.World getBukkitWorld() {
        return this.handle.getLevel() != null ? this.handle.getLevel().getWorld() : null;
    }

    @Override
    public org.bukkit.Location getBukkitLocation() {
        final Vec3 pos = this.handle.getPosition();
        final org.bukkit.World world = this.getBukkitWorld();
        final Vec2 rot = this.handle.getRotation();
        return world != null && pos != null ? new org.bukkit.Location(world, pos.x, pos.y, pos.z, rot != null ? rot.y : 0, rot != null ? rot.x : 0) : null;
    }

    @Override
    public org.bukkit.command.CommandSender getBukkitSender() {
        return this.handle.getBukkitSender();
    }

    /** NMS の source を旧 API の型で見せる。 */
    public static LegacySource wrap(final CommandSourceStack source) {
        return source == null ? null : new LegacySource(source);
    }

    /** 包んだものから NMS を取り出す。NMS がそのまま来ても通す。 */
    public static CommandSourceStack unwrap(final Object source) {
        if (source == null) {
            return null;
        }

        if (source instanceof final CommandSourceStack nms) {
            return nms;
        }

        if (source instanceof final LegacySource legacy) {
            return legacy.handle;
        }

        throw new IllegalArgumentException("知らない source: " + source.getClass());
    }

    // ------------------------------------------------------------ CommandRegisteredEvent

    /**
     * {@code CommandRegisteredEvent} を作る。
     *
     * <p>{@code getBrigadierCommand()} で返す実行の口は旧 API の型で受けて、NMS に戻してから
     * Bukkit のコマンドの包み({@code BukkitCommandWrapper})へ渡す。ノードは型だけを合わせて
     * そのまま渡す(イベントの後で {@link #toNms} が組み直す)。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <W extends Command<CommandSourceStack> & Predicate<CommandSourceStack> & SuggestionProvider<CommandSourceStack>>
    CommandRegisteredEvent registeredEvent(final String label, final W wrapper, final org.bukkit.command.Command command,
                                           final RootCommandNode<CommandSourceStack> root,
                                           final LiteralCommandNode<CommandSourceStack> literal,
                                           final ArgumentCommandNode<CommandSourceStack, String> defaultArgs) {
        final BukkitBrigadierCommand brigadier = new BukkitBrigadierCommand() {
            @Override
            public int run(final CommandContext context) throws CommandSyntaxException {
                return wrapper.run(context.copyFor(unwrap(context.getSource())));
            }

            @Override
            public boolean test(final Object source) {
                return wrapper.test(unwrap(source));
            }

            @Override
            public CompletableFuture<Suggestions> getSuggestions(final CommandContext context, final SuggestionsBuilder builder)
                    throws CommandSyntaxException {
                return wrapper.getSuggestions(context.copyFor(unwrap(context.getSource())), builder);
            }
        };

        return new CommandRegisteredEvent(label, brigadier, command, (RootCommandNode) root, (LiteralCommandNode) literal,
                (ArgumentCommandNode) defaultArgs);
    }

    /**
     * イベントの後のリテラルを、vanilla の dispatcher に入れられる形にする。
     *
     * <p>組み直すのはプラグインが組んだノードだけ。{@code ours}(Shifu が作ったノード)と、
     * {@code root} からたどれるノード(dispatcher にもうあるもの)は NMS の source を受けるので
     * そのまま使う。{@code ours} の子にプラグインが足したノードは、その子だけを差し替える
     * (Paper の API は既定のリテラルを書き換えてよいとしている)。
     *
     * @param literal イベントの後の {@code getLiteral()}
     * @param root    登録先の dispatcher の根
     * @param ours    Shifu が作ったノード
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static LiteralCommandNode<CommandSourceStack> toNms(final LiteralCommandNode literal, final RootCommandNode<CommandSourceStack> root,
                                                               final CommandNode<CommandSourceStack>... ours) {
        final Set<CommandNode> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(mine, ours);

        if (untouched(literal, mine)) {
            return literal;
        }

        final Set<CommandNode> known = Collections.newSetFromMap(new IdentityHashMap<>());
        known.addAll(mine);
        final Deque<CommandNode> todo = new ArrayDeque<>();
        todo.add(root);

        while (!todo.isEmpty()) {
            final CommandNode node = todo.poll();

            if (!known.add(node)) {
                continue;
            }

            todo.addAll(node.getChildren());

            if (node.getRedirect() != null) {
                todo.add(node.getRedirect());
            }
        }

        return (LiteralCommandNode<CommandSourceStack>) convert(literal, mine, known, new IdentityHashMap<>(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Shifu のノードだけでできているか(プラグインが何もしていないとき)。 */
    private static boolean untouched(final CommandNode<?> node, final Set<CommandNode> mine) {
        if (!mine.contains(node)) {
            return false;
        }

        for (final CommandNode<?> child : node.getChildren()) {
            if (!untouched(child, mine)) {
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandNode convert(final CommandNode node, final Set<CommandNode> mine, final Set<CommandNode> known,
                                       final Map<CommandNode, CommandNode> done, final Set<CommandNode> building) {
        if (known.contains(node)) {
            if (mine.contains(node)) {
                for (final CommandNode child : new ArrayList<CommandNode>(node.getChildren())) {
                    final CommandNode converted = convert(child, mine, known, done, building);

                    if (converted != child) {
                        node.removeCommand(child.getName());
                        node.addChild(converted);
                    }
                }
            }

            return node;
        }

        final CommandNode already = done.get(node);

        if (already != null) {
            return already;
        }

        if (!building.add(node)) {
            throw new IllegalArgumentException("リダイレクトが輪になっていて組み直せない: " + node.getName());
        }

        final CommandNode redirect = node.getRedirect() == null ? null : convert(node.getRedirect(), mine, known, done, building);
        final CommandNode copy;

        if (node instanceof final LiteralCommandNode literal) {
            copy = new LiteralCommandNode(literal.getLiteral(), toNms(literal.getCommand()), toNms(literal.getRequirement()),
                    redirect, toNms(literal.getRedirectModifier()), literal.isFork());
        } else if (node instanceof final ArgumentCommandNode argument) {
            copy = new ArgumentCommandNode(argument.getName(), argument.getType(), toNms(argument.getCommand()),
                    toNms(argument.getRequirement()), redirect, toNms(argument.getRedirectModifier()), argument.isFork(),
                    toNms(argument.getCustomSuggestions()));
        } else {
            throw new IllegalArgumentException("組み直せないノード: " + node.getClass());
        }

        done.put(node, copy);
        building.remove(node);

        for (final CommandNode child : (Collection<CommandNode>) node.getChildren()) {
            copy.addChild(convert(child, mine, known, done, building));
        }

        return copy;
    }

    // ------------------------------------------------------------ brigadier の部品を包み直す

    /** 実行の文脈の source だけを旧 API の型にした写し。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandContext forLegacy(final CommandContext context) {
        return context.copyFor(wrap(unwrap(context.getSource())));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate toNms(final Predicate requirement) {
        if (requirement == null) {
            return null;
        }

        return source -> requirement.test(wrap(unwrap(source)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Command toNms(final Command command) {
        if (command == null) {
            return null;
        }

        return context -> command.run(forLegacy(context));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SuggestionProvider toNms(final SuggestionProvider suggestions) {
        if (suggestions == null) {
            return null;
        }

        return (context, builder) -> suggestions.getSuggestions(forLegacy(context), builder);
    }

    /** 返す source は NMS に戻す。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedirectModifier toNms(final RedirectModifier modifier) {
        if (modifier == null) {
            return null;
        }

        return context -> {
            final Collection<Object> results = modifier.apply(forLegacy(context));
            final List<CommandSourceStack> out = new ArrayList<>(results.size());

            for (final Object result : results) {
                out.add(unwrap(result));
            }

            return out;
        };
    }
}
