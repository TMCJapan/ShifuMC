#!/bin/sh
# 発火の規則のうち、通らないものを落として不動点まで回す。
#
# `make_events.py` は Paper のパッチを機械的に規則にするので、単独では
# 成り立たないものが混じる。何が成り立たないかはコンパイラだけが知っている。
# コンパイル -> 通らない規則を外す、を繰り返す。
#
# 外したものは `docs/backlog/events-dropped.txt` に理由ごと残る。
# 黙って減らさないため。
set -e

. "$(dirname "$0")/env.sh"
GAP=$SHIFU/docs/backlog/vanilla-gap.txt
GEN=$SHIFU/patches/events/generated
NOTE=$SHIFU/docs/backlog/events-dropped.txt
export EVENTS_FLAGS=--report

# ツールの出力先。リポジトリには入っていないので作る。
# 要求リストが無い状態から始めると、closure.sh が不動点まで回して組み直す。
mkdir -p "$SHIFU/docs/backlog"
touch "$SHIFU/docs/backlog/required-members.txt"

for round in 1 2 3 4 5 6 7 8; do
    sh "$SHIFU/tools/once.sh" 2>/dev/null | tail -2
    count=$(grep -c "error:" "$GAP" || true)
    echo "round $round: $count errors"

    if [ "$count" = "0" ]; then
        echo "SETTLED"
        exit 0
    fi

    cd "$SHIFU"
    out=$(python tools/drop_bad_events.py "$GAP" "$GEN" "$NOTE" "$TREE")
    echo "$out" | head -1

    if echo "$out" | head -1 | grep -q "外した規則 0 "; then
        echo "これ以上は規則の外し方では減らない"
        exit 1
    fi
done

echo "回数切れ"
exit 1
