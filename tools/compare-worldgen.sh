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

# Done を待って、しばらく tick させてから stop を流す
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
    ) | "$JAVA" -Xmx4G -Duser.language=en -Duser.country=US "$@" --nogui > "$log" 2>&1 || true
    grep -q "Done (" "$log" || { echo "$dir: 起動しなかった($log)"; exit 1; }
}

run "$VANILLA" worldgen-a.log @cp.txt net.minecraft.server.Main
rm -rf "$VANILLA/world-a"
mv "$VANILLA/world" "$VANILLA/world-a"

run "$VANILLA" worldgen-b.log @cp.txt net.minecraft.server.Main
rm -rf "$VANILLA/world-b"
mv "$VANILLA/world" "$VANILLA/world-b"

run "$PARITY" worldgen.log -Dlog4j.configurationFile="file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")" -jar "$JAR"

cd "$SHIFU"
echo "==== vanilla-a vs Shifu(vanilla-b でばらつきを除く) ===="
python tools/compare_worlds.py "$VANILLA/world-a" "$PARITY/world" "$VANILLA/world-b" | tail -5
echo "==== vanilla-a vs vanilla-b(ばらつきそのもの) ===="
python tools/compare_worlds.py "$VANILLA/world-a" "$VANILLA/world-b" | tail -2
