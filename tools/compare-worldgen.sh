#!/bin/sh
# 同じシードで vanilla を 2 回、Shifu を 1 回走らせて、世界生成を突き合わせる。
#
#     sh tools/compare-worldgen.sh
#
# vanilla は Paper のビルドが取ってきた Mojang の server.jar
# (paper-server/.gradle/caches/paperweight/data/bundler/server.jar)を、
# run-shifu に展開済みのライブラリで動かす。Shifu は run-server.sh で組んだ jar を
# プラグイン無しの run-parity で動かす。
#
# どの走らせ方も、Done を待ってから 20 秒だけ tick させて stop する。
# 出力は run-vanilla/worldgen-a.log, worldgen-b.log, run-parity/worldgen.log と、
# 最後の python の数え上げ。
set -e

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

# Done を待って、しばらく tick させてから stop を流す
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
    (
        for i in $(seq 1 120); do
            if grep -q "Done (" "$log" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        sleep 20
        echo stop
    ) | "$JAVA" -Xmx4G -Duser.language=en -Duser.country=US -Dmax.bg.threads=1 "$@" --nogui > "$log" 2>&1 || true
    grep -q "Done (" "$log" || { echo "$dir: 起動しなかった($log)"; exit 1; }
}

# vanilla を 4 回走らせる。2 回だと「たまたま揃ったチャンク」がばらつきに
# 数えられず、Shifu 側の実差のように見える。1.20.6 では 2 回で 5 件、3 回で 3 件、
# 4 回で 0 件になった。SHIFU_VANILLA_RUNS で変えられる。
RUNS=${SHIFU_VANILLA_RUNS:-4}
NAMES=""

for n in $(seq 1 "$RUNS"); do
    run "$VANILLA" "worldgen-$n.log" $VANILLA_RUN
    rm -rf "$VANILLA/world-$n"
    mv "$VANILLA/world" "$VANILLA/world-$n"
    NAMES="$NAMES $VANILLA/world-$n"
done

SYNC="-Dlog4j.configurationFile=file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")"
run "$PARITY" worldgen.log "$SYNC" -jar "$JAR"
rm -rf "$PARITY/world-2nd"
mv "$PARITY/world" "$PARITY/world-2nd"

# Shifu 側のばらつきも差し引く。片側だけ引くと、もう片側のばらつきが実差に見える。
run "$PARITY" worldgen-2.log "$SYNC" -jar "$JAR"

cd "$SHIFU"
set -- $NAMES
BASE=$1
shift
echo "==== vanilla-1 vs Shifu(vanilla を $RUNS 回走らせてばらつきを除く)===="
python tools/compare_worlds.py "$BASE" "$PARITY/world" "$@" --right-control "$PARITY/world-2nd" | tail -6
echo "==== vanilla-1 vs vanilla-2(ばらつきそのもの)===="
python tools/compare_worlds.py "$BASE" "$VANILLA/world-2" | tail -2
