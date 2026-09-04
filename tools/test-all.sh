#!/bin/sh
# 生成器の単体テストをまとめて走らせる。Paper のクローンは要らない。
#
#     sh tools/test-all.sh
#
# 生成器や規則の読み取りを触ったら、once.sh を回す前にこれを通す。
# コンパイルを伴う確認(check_events.py、once.sh)は別。
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
PYTHON=${PYTHON:-python}
fail=0

for test in "$HERE"/test_*.py; do
    name=$(basename "$test")
    printf '%-28s ' "$name"

    if out=$("$PYTHON" "$test" 2>&1); then
        echo "ok"
    else
        echo "NG"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    fi
done

# シェルスクリプトの構文。書き換えたときに気付けるようにする。
for script in "$HERE"/*.sh "$HERE"/*/build.sh; do
    [ -f "$script" ] || continue
    name=${script#"$HERE"/}
    printf '%-28s ' "sh -n $name"

    if out=$(sh -n "$script" 2>&1); then
        echo "ok"
    else
        echo "NG"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    fi
done

exit $fail
