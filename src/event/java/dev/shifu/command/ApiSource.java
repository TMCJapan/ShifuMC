// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommandSourceStack;

/**
 * Paper の brigadier API と vanilla のコマンドの間の橋。
 *
 * <p>Paper は NMS の {@code CommandSourceStack} に API の {@code PaperCommandSourceStack} を
 * 実装させて「同じオブジェクト」にしている。Shifu はそれを採らない。vanilla のクラス宣言を
 * 変えると、Fabric の MOD が当てる mixin と衝突するため
 * ({@code fabric-permission-api-v1} が「名前が with で始まり戻り値の型名が
 * CommandSourceStack で終わるメソッド」を全部拾う)。
 *
 * <p>代わりに、API に渡すときだけ包む。プラグインが組んだノード(述語・実行・補完・
 * リダイレクト)は API の型で書かれているので、vanilla の dispatcher に入れる前に
 * ここで包み直す。API から NMS に戻すときは {@link #unwrap} で外す。
 *
 * <p>包んだものは NMS の側に覚えておく({@code shifuApiSource})。同じ実行の中で
 * 何度包んでも同じオブジェクトになり、プラグインが同一性で見ても壊れない。
 */
public record ApiSource(net.minecraft.commands.CommandSourceStack handle) implements PaperCommandSourceStack {

    @Override
    public net.minecraft.commands.CommandSourceStack getHandle() {
        return this.handle;
    }

    /** {@code withLocation} は API の interface が求める。NMS の同名メソッド(hand)へ渡す。 */
    @Override
    public CommandSourceStack withLocation(final org.bukkit.Location location) {
        return wrap(this.handle.withLocation(location));
    }

    /** NMS の source を API の型で見せる。 */
    public static CommandSourceStack wrap(final net.minecraft.commands.CommandSourceStack source) {
        if (source == null) {
            return null;
        }

        if (source.shifuApiSource == null) {
            source.shifuApiSource = new ApiSource(source);
        }

        return (CommandSourceStack) source.shifuApiSource;
    }

    /** API の source(包んだもの)から NMS を取り出す。NMS がそのまま来ても通す。 */
    public static net.minecraft.commands.CommandSourceStack unwrap(final Object source) {
        return switch (source) {
            case null -> null;
            case net.minecraft.commands.CommandSourceStack nms -> nms;
            case PaperCommandSourceStack api -> api.getHandle();
            default -> throw new IllegalArgumentException("知らない source: " + source.getClass());
        };
    }

    // ------------------------------------------------------------ brigadier の部品を包み直す

    /** 実行の文脈の source だけを API の型にした写し。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static CommandContext forApi(final CommandContext context) {
        return context.copyFor(wrap(unwrap(context.getSource())));
    }

    /** API の述語を、NMS の source を受ける述語にする。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Predicate toNms(final Predicate<CommandSourceStack> requirement) {
        if (requirement == null) {
            return null;
        }

        return source -> requirement.test(wrap(unwrap(source)));
    }

    /** API の実行を、NMS の文脈で呼べるようにする。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Command toNms(final Command<CommandSourceStack> command) {
        if (command == null) {
            return null;
        }

        return context -> command.run(forApi(context));
    }

    /** API の補完を、NMS の文脈で呼べるようにする。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static SuggestionProvider toNms(final SuggestionProvider<CommandSourceStack> suggestions) {
        if (suggestions == null) {
            return null;
        }

        return (context, builder) -> suggestions.getSuggestions(forApi(context), builder);
    }

    /** API のリダイレクトを、NMS の文脈で呼べるようにする。返す source は NMS に戻す。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static RedirectModifier toNms(final RedirectModifier<CommandSourceStack> modifier) {
        if (modifier == null) {
            return null;
        }

        return context -> {
            final Collection<CommandSourceStack> results = modifier.apply(forApi(context));
            final List<net.minecraft.commands.CommandSourceStack> out = new ArrayList<>(results.size());

            for (final CommandSourceStack result : results) {
                out.add(unwrap(result));
            }

            return out;
        };
    }

    /**
     * API のノードを、vanilla の dispatcher に入れられるノードに組み直す。
     *
     * <p>述語・実行・補完・リダイレクトを NMS の source を受ける形に包む。子は呼ぶ側が足す。
     *
     * @param node     API のノード
     * @param redirect 包み直したあとのリダイレクト先(無ければ null)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static com.mojang.brigadier.tree.CommandNode unwrapNode(final com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node,
                                                                   final com.mojang.brigadier.tree.CommandNode redirect) {
        if (node instanceof final com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> literal) {
            return new com.mojang.brigadier.tree.LiteralCommandNode(
                    literal.getLiteral(), toNms(literal.getCommand()), toNms(literal.getRequirement()),
                    redirect, toNms(literal.getRedirectModifier()), literal.isFork());
        }

        if (node instanceof final com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, ?> argument) {
            return new com.mojang.brigadier.tree.ArgumentCommandNode(
                    argument.getName(), argument.getType(), toNms(argument.getCommand()), toNms(argument.getRequirement()),
                    redirect, toNms(argument.getRedirectModifier()), argument.isFork(), toNms(argument.getCustomSuggestions()));
        }

        throw new IllegalArgumentException("組み直せないノード: " + node.getClass());
    }

    /** 補完の CompletableFuture をそのまま返すだけの版(型合わせ)。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static CompletableFuture<Suggestions> suggest(final SuggestionProvider<CommandSourceStack> suggestions,
                                                         final CommandContext context, final SuggestionsBuilder builder)
            throws CommandSyntaxException {
        return suggestions.getSuggestions(forApi(context), builder);
    }
}
