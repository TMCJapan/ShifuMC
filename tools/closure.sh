#!/bin/sh
# 要求されるメンバーを不動点まで広げる。
#
# 足したメンバー自身が別の追加メンバーを参照するので、1 回では閉じない。
# コンパイル -> 足りないものを要求に足す -> やり直す、を繰り返す。
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

before=999999

for round in 1 2 3 4 5 6 7 8 9 10; do
    cd "$TREE"
    git reset --hard "$SHIFU_BASE" -q
    git clean -fdq
    
    # Paper 側を素の状態に戻す。アダプタ層の書き換えと、vanilla の挙動が変わるデータ。
    # 何を戻すかは版の並べ方で違うので、env.sh の関数に置いてある。
    shifu_reset_paper
    # Paper が丸ごと足すファイル。元の行が無いので挙動には触れない。
    python "$SHIFU/tools/add_new_files.py" "$SOURCES" . 2>/dev/null
    shifu_make_shim | tail -2

    # 自前で書いた追加。Paper のパッチから取れなかった分。
    # shim は write_members.py が書き出す(上書きされる)。hand は手で書く。
    # 可視性だけを広げる。修飾子 1 語だけで、命令列は変わらない。
    python "$SHIFU/tools/widen_access.py" "$SHIFU/patches/access" . $ACCESS_FLAGS
    python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/shim" . "$SHIFU/patches/hand" "$SHIFU/patches/access" | tail -1
    python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/patches/hand" . | tail -1

    # イベント発火層。自前のコードを置いて、vanilla の呼び出し箇所に差し込む。
    # アダプタ層の書き換え。vanilla ではないので挙動の条件には掛からない。
    python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/adapter" "$ADAPTER" $ACCESS_FLAGS

    mkdir -p "$ADAPTER/dev/shifu/event"
    cp "$SHIFU/src/event/java/dev/shifu/event/"*.java        "$ADAPTER/dev/shifu/event/"
    mkdir -p "$ADAPTER/dev/shifu/command"
    cp "$SHIFU/src/event/java/dev/shifu/command/"*.java      "$ADAPTER/dev/shifu/command/"
    # vanilla の中の無名クラスへの追加。型の名前が無いので hand では届かない。
    python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/anon" . 無名クラスへの追加 $EVENTS_FLAGS
    # Paper が vanilla のメソッドの中で行う代入。Bukkit 層の配線。
    python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/wire" . 配線 $EVENTS_FLAGS
    python "$SHIFU/tools/apply_events.py" "$SHIFU/patches/events" . $EVENTS_FLAGS
    # 逆コンパイルで消えた局所変数を戻す(tools/make_decompile_rules.py)
    if [ -z "${SHIFU_NO_DECOMPILE:-}" ]; then
        python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/decompile" . $ACCESS_FLAGS | tail -1
        python "$SHIFU/tools/patch_adapter.py" "$SHIFU/patches/expr" . $ACCESS_FLAGS | tail -1
    fi

    # classic は NMS もアダプタ層と同じソースセットなので、当て終わった木を写す。
    shifu_stage_tree

    cd "$PW"
    "$GRADLEW" --no-daemon -I "$INIT" ":paper-server:compileJava" \
        "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US" 2>&1 \
        | sed "s|$SRC_PREFIX||" > "$GAP" || true

    count=$(grep -c "error:" "$GAP" || true)
    echo "round $round: $count errors"

    if [ "$count" = "0" ]; then
        echo "COMPILED"
        break
    fi

    if [ "$count" -ge "$before" ]; then
        # 直前の回で足した要求が悪さをしている。戻して止める。
        # 要求はクラスではなく名前だけで突き合わせるので、`set` `close` `fill` のような
        # ありふれた名前が入ると、関係の無い型にも宣言が足されてエラーが増える。
        if [ -f "$REQ.bak" ]; then
            mv "$REQ.bak" "$REQ"
            echo "要求を 1 つ前に戻した"
        fi

        echo "STALLED"
        break
    fi

    before=$count

    cd "$SHIFU"
    cp "$REQ" "$REQ.bak"
    python tools/required_members.py "$GAP" "$TREE" "$ADAPTER" >> "$REQ"
    echo "round $round: required now $(grep -cE '^    (method|variable|class|access|abstract) ' "$REQ")"
done
