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
SRC_PREFIX=$(cygpath -m "$PW/paper-server/src/" | tr '/' '.')

# ツールの出力先。リポジトリには入っていないので作る。
# 要求リストが無い状態から始めると、closure.sh が不動点まで回して組み直す。
mkdir -p "$SHIFU/docs/backlog"
touch "$SHIFU/docs/backlog/required-members.txt"

cd "$TREE"
git reset --hard "$SHIFU_BASE" -q
git clean -fdq

# データも Paper のパッチが当たった状態で置かれている。resources は別のリポジトリ。
# 岩盤生成を paper:optionally_flat_bedrock_condition_source に差し替え、
# 戦利品表から set_damage を 1 件落としている。どちらも vanilla の挙動が変わる。
git -C "$PW/paper-server/src/minecraft/resources" reset --hard ba9af23 -q
python "$SHIFU/tools/add_new_files.py" "$PW/paper-server/patches/sources" . 2>/dev/null
python "$SHIFU/tools/make_shim.py" "$PW/paper-server/patches/sources" . "$REQ" | tail -3
# 可視性だけを広げる。修飾子 1 語だけで、命令列は変わらない。
python "$SHIFU/tools/widen_access.py" "$SHIFU/patches/access" .
python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/shim" . "$SHIFU/patches/hand" "$SHIFU/patches/access" | tail -1
python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/hand" . | tail -1

# アダプタ層の書き換え。vanilla ではないので挙動の条件には掛からない。
# src/minecraft/java は別のリポジトリなので、上の git reset では戻らない。
# 前の回の書き換えが残っていると当て直せないので、ここで戻す。
git -C "$PW" checkout -- paper-server/src/main/java
python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/adapter" "$PW/paper-server/src/main/java"

mkdir -p "$PW/paper-server/src/main/java/dev/shifu/event"
cp "$SHIFU/src/event/java/dev/shifu/event/"*.java "$PW/paper-server/src/main/java/dev/shifu/event/"
mkdir -p "$PW/paper-server/src/main/java/dev/shifu/command"
cp "$SHIFU/src/event/java/dev/shifu/command/"*.java "$PW/paper-server/src/main/java/dev/shifu/command/"
# vanilla の中の無名クラスへの追加。型の名前が無いので hand では届かない。
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/anon" . 無名クラスへの追加
# Paper が vanilla のメソッドの中で行う代入。Bukkit 層の配線。
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/wire" . 配線
python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/events" . $EVENTS_FLAGS

# 逆コンパイルで消えた局所変数を戻す(理由は tools/make_decompile_rules.py)。
# 差し込みのアンカーを崩さないように最後に当てる。規則を作り直すときは
# SHIFU_NO_DECOMPILE=1 で 1 度回して、その状態のクラスから作る。
if [ -z "${SHIFU_NO_DECOMPILE:-}" ]; then
    python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/decompile" . | tail -1
    # vanilla の式の末尾に発火を足す(理由は patches/expr の頭)
    python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/expr" . | tail -1
fi

cd "$PW"
"$GRADLEW" --no-daemon -I "$INIT" ":paper-server:compileJava" \
    "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US" 2>&1 \
    | sed "s|$SRC_PREFIX||" > "$GAP" || true

echo "errors: $(grep -c 'error:' "$GAP" || true)"
