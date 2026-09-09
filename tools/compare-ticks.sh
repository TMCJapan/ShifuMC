#!/bin/sh
# 同じシードで vanilla を 2 回、Shifu を 1 回、それぞれちょうど N tick 走らせて突き合わせる。
#
#     sh tools/compare-ticks.sh [N]      # 既定 1200 tick(1 分)
#
# compare-worldgen.sh との違いは、測定用 agent(tools/tickstop)を両方に付けること。
#   * 乱数の種を時刻で変えない(走らせるたびに同じ列)
#   * N tick 目で halt する(停止までの tick 数が揃う)
# これで vanilla 同士のばらつきが消えれば、vanilla ↔ Shifu の違いは処理順の違いだけになる。
#
# 前提: tools/tickstop/build.sh で agent を組んであること、run-server.sh で Shifu の jar を組んであること。
set -e

N=${1:-1200}
. "$(dirname "$0")/env.sh"
VANILLA=$PW/run-vanilla
PARITY=$PW/run-parity
JAR=$BUNDLER_JAR

if [ -z "$JAR" ]; then
    echo "bundler の jar が無い。先に sh tools/run-server.sh で組む" >&2
    exit 1
fi

shifu_parity_dirs

# vanilla の走らせ方。cp.txt を置いてあればそれを使い、無ければ公式の jar をそのまま。
# Mojang の server.jar は 1.18 以降それ自身が bundler なので -jar で動く。
if [ -f "$VANILLA/cp.txt" ]; then
    VANILLA_RUN="@cp.txt net.minecraft.server.Main"
else
    VANILLA_RUN="-jar $(cygpath -w "$VANILLA_JAR")"
fi
AGENT="-javaagent:$(cygpath -w "$SHIFU/tools/build/tickstop.jar")=$N"

# agent が無ければ組む(player-events.sh が tools/build を消していることがある)
[ -f "$SHIFU/tools/build/tickstop.jar" ] || sh "$SHIFU/tools/tickstop/build.sh"

# agent が halt するので stop は流さない。念のため 10 分で諦める
# 背景の実行器を 1 本にする。チャンク生成と光の計算がどこまで進むかは
# スレッドの巡り合わせで変わり、走らせるたびに保存される中身がずれる。
# **vanilla と Shifu の両方に同じだけ効かせる**ので、比較の偏りにはならない。
run() {
    dir=$1
    log=$2
    shift 2
    cd "$dir"
    rm -rf world world_nether world_the_end
    rm -f "$log"
    "$JAVA" -Xmx4G -Duser.language=en -Duser.country=US -Dmax.bg.threads=1 "$AGENT" "$@" --nogui < /dev/null > "$log" 2>&1 || true
    grep -q "tickstop\] tick" "$log" || { echo "$dir: N tick に届かなかった($log)"; exit 1; }
}

run "$VANILLA" ticks-a.log $VANILLA_RUN
rm -rf "$VANILLA/ticks-a"
mv "$VANILLA/world" "$VANILLA/ticks-a"

run "$VANILLA" ticks-b.log $VANILLA_RUN
rm -rf "$VANILLA/ticks-b"
mv "$VANILLA/world" "$VANILLA/ticks-b"

run "$PARITY" ticks.log -Dlog4j.configurationFile="file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")" -jar "$JAR"

cd "$SHIFU"
echo "==== vanilla-a vs vanilla-b(ばらつきそのもの、$N tick) ===="
python tools/compare_worlds.py "$VANILLA/ticks-a" "$VANILLA/ticks-b" | tail -3
echo "==== vanilla-a vs Shifu($N tick) ===="
python tools/compare_worlds.py "$VANILLA/ticks-a" "$PARITY/world" | tail -5
