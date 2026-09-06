#!/bin/sh
# 開発環境を 1 から用意する。Paper のクローンと、逆コンパイルした vanilla のソースまで。
#
#     sh tools/setup.sh            # 無ければ用意する。あれば状態だけ見る
#     sh tools/setup.sh --check    # 用意はせず、状態だけ見る
#
# やること:
#   1. PaperMC/Paper を $SHIFU_PAPER(既定はリポジトリの隣の .pw)に clone
#   2. paperweight の applyAllPatches(1.21.x では applyPatches)を回す。これで
#      paper-server/src/minecraft/java に逆コンパイルした vanilla が出て、
#      git の履歴が Vanilla → Mache → paper ATs → paper Imports → (file patches) になる
#   3. その履歴から「paper Imports」のハッシュを拾い、tools が使う基点として書き出す
#      (Shifu はここへ reset してから追加を当てる)
#
# 要るもの: JDK 25、git、ネット。逆コンパイルは 10〜30 分かかる。
set -e

. "$(dirname "$0")/env.sh"
CHECK=${1:-}

say() {
    printf '%s\n' "$*"
}

# git worktree だと .git はファイルなので -d では見つからない。
if [ ! -e "$PW/.git" ]; then
    if [ "$CHECK" = "--check" ]; then
        say "Paper のクローンが無い: $PW"
        exit 1
    fi

    say "Paper を clone する -> $PW"
    git clone https://github.com/PaperMC/Paper.git "$PW"
fi

say "Paper: $(git -C "$PW" log --oneline -1)"

if [ ! -d "$TREE/net/minecraft" ]; then
    if [ "$CHECK" = "--check" ]; then
        say "逆コンパイルした vanilla が無い: $TREE"
        exit 1
    fi

    # タスク名は paperweight の版で違う。1.21.x が使う 2.0.0-beta は applyPatches、
    # 26.x は applyAllPatches。名前が無いときだけもう一方を試す。
    say "パッチを当てて逆コンパイルする(10〜30 分)"
    LOG=$SHIFU/tools/build/apply.log
    mkdir -p "$SHIFU/tools/build"

    if ! (cd "$PW" && "$GRADLEW" --no-daemon applyAllPatches) 2>&1 | tee "$LOG"; then
        if grep -q "Task 'applyAllPatches' not found" "$LOG"; then
            say "applyAllPatches が無い。applyPatches で回す"
            (cd "$PW" && "$GRADLEW" --no-daemon applyPatches)
        else
            say "パッチ当てに失敗した。$LOG を見ること"
            exit 1
        fi
    fi
fi

# 「paper Imports」= file patches を当てる直前。Shifu の vanilla の基点
BASE=$(git -C "$TREE" log --format='%H %s' | awk '$0 ~ /paper Imports$/ { print $1; exit }')

if [ -z "$BASE" ]; then
    say "vanilla の基点(paper Imports)が履歴に無い。パッチ当てが途中で止まっていないか見ること"
    exit 1
fi

say "vanilla の基点: $(git -C "$TREE" log --oneline -1 "$BASE")"

# tools/env.sh がここを読んで SHIFU_BASE に入れる。以降のツールは全部これを見る。
mkdir -p "$SHIFU/tools/build"
say "$BASE" > "$SHIFU/tools/build/base-commit.txt"

if [ "$BASE" != "$(git -C "$TREE" rev-parse "$SHIFU_BASE" 2>/dev/null)" ]; then
    say ""
    say "基点が既定($SHIFU_BASE)から変わった -> $BASE"
    say "base-commit.txt に書いたので、ツールは次から新しい方を使う。"
    say "Paper 側が変わっているので、規則のアンカーが外れていないかを"
    say "python tools/check_events.py で先に見ること。"
fi

say ""
say "次: sh tools/once.sh(追加を当ててコンパイル) -> sh tools/run-server.sh(組んで起動)"
