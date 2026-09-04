#!/bin/sh
# 測定用 agent(tools/tickstop)を組む。出力は tools/build/tickstop.jar。
#
#     sh tools/tickstop/build.sh
#
# ASM は gradle のキャッシュにあるものを使い、agent の jar に同梱する
# (サーバーの classpath には無いので)。jar の組み立ては MSYS の fork を避けて Python で行う。
set -e

. "$(dirname "$0")/../env.sh"
OUT=$SHIFU/tools/build/tickstop
ASM=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.9.1/*/asm-9.9.1.jar | head -1)

rm -rf "$OUT"
mkdir -p "$OUT/classes"

"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none \
    -cp "$(cygpath -w "$ASM")" -d "$(cygpath -w "$OUT/classes")" \
    "$SHIFU/tools/tickstop/src/dev/shifu/tickstop/TickStop.java"

python "$SHIFU/tools/tickstop/make_jar.py" "$OUT/classes" "$ASM" "$SHIFU/tools/build/tickstop.jar"
