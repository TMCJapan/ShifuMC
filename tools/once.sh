#!/bin/sh
# closure.sh の 1 回分。要求リストは足さない。
#
# closure.sh は不動点まで回すので 2 回以上コンパイルする。
# 追加を 1 つ書くたびに数を見たいときはこちらを使う。
set -e
set -o pipefail

. "$(dirname "$0")/env.sh"
REQ=$SHIFU/docs/backlog/required-members.txt
GAP=$SHIFU/docs/backlog/vanilla-gap.txt
INIT=$(cygpath -w "$SHIFU/tools/maxerrs.gradle")

# gradle はエラーを Windows のパスで出す。読みやすくするために前置きを落とす。
# 区切りは / と \ が混ざるので、正規表現の . で受ける。
SRC_PREFIX=$(cygpath -m "$(dirname "$(dirname "$ADAPTER")")/" | tr '/' '.')

# ツールの出力先。リポジトリには入っていないので作る。
# 要求リストが無い状態から始めると、closure.sh が不動点まで回して組み直す。
mkdir -p "$SHIFU/docs/backlog"
touch "$SHIFU/docs/backlog/required-members.txt"

cd "$TREE"
git reset --hard "$SHIFU_BASE" -q
git clean -fdq

# Paper 側を素の状態に戻す。アダプタ層の書き換えと、vanilla の挙動が変わるデータ。
# 何を戻すかは版の並べ方で違うので、env.sh の関数に置いてある。
shifu_reset_paper
python "$SHIFU/tools/add_new_files.py" "$SOURCES" . 2>/dev/null
python "$SHIFU/tools/make_shim.py" "$SOURCES" . "$REQ" | tail -3
# 可視性だけを広げる。修飾子 1 語だけで、命令列は変わらない。
python "$SHIFU/tools/widen_access.py" "$SHIFU/patches/access" . $ACCESS_FLAGS
python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/shim" . "$SHIFU/patches/hand" "$SHIFU/patches/access" | tail -1
python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/hand" . | tail -1

# アダプタ層の書き換え。vanilla ではないので挙動の条件には掛からない。
python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/adapter" "$ADAPTER" $ACCESS_FLAGS

mkdir -p "$ADAPTER/dev/shifu/event"
cp "$SHIFU/src/event/java/dev/shifu/event/"*.java "$ADAPTER/dev/shifu/event/"
mkdir -p "$ADAPTER/dev/shifu/command"
cp "$SHIFU/src/event/java/dev/shifu/command/"*.java "$ADAPTER/dev/shifu/command/"
# vanilla の中の無名クラスへの追加。型の名前が無いので hand では届かない。
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/anon" . 無名クラスへの追加 $EVENTS_FLAGS
# Paper が vanilla のメソッドの中で行う代入。Bukkit 層の配線。
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/wire" . 配線 $EVENTS_FLAGS
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/events" . $EVENTS_FLAGS

# 逆コンパイルで消えた局所変数を戻す(理由は tools/make_decompile_rules.py)。
# 差し込みのアンカーを崩さないように最後に当てる。規則を作り直すときは
# SHIFU_NO_DECOMPILE=1 で 1 度回して、その状態のクラスから作る。
if [ -z "${SHIFU_NO_DECOMPILE:-}" ]; then
    python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/decompile" . $ACCESS_FLAGS | tail -1
    # vanilla の式の末尾に発火を足す(理由は patches/expr の頭)
    python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/expr" . $ACCESS_FLAGS | tail -1
fi

# classic は NMS もアダプタ層と同じソースセットなので、当て終わった木を写す。
shifu_stage_tree

cd "$PW"
"$GRADLEW" --no-daemon -I "$INIT" ":paper-server:compileJava" \
    "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US" 2>&1 \
    | sed "s|$SRC_PREFIX||" > "$GAP" || true

echo "errors: $(grep -c 'error:' "$GAP" || true)"
