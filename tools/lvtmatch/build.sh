#!/bin/sh
# 局所変数の番号を公式に合わせる道具を組む。出力は tools/build/lvtmatch。
#
#     sh tools/lvtmatch/build.sh
set -e

. "$(dirname "$0")/../env.sh"
OUT=$SHIFU/tools/build/lvtmatch

CP=""
for name in asm asm-tree asm-analysis; do
    jar=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.ow2.asm/$name/9.10.1/*/$name-9.10.1.jar | head -1)
    CP="$CP;$(cygpath -w "$jar")"
done

rm -rf "$OUT"
mkdir -p "$OUT"

# 後処理は gradle と同じ JDK で走る。版が変わっても載るように合わせる
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none --release "$JDK_MIN" \
    -cp "$CP" -d "$(cygpath -w "$OUT")" \
    "$SHIFU"/tools/lvtmatch/src/dev/shifu/lvtmatch/*.java

printf '%s' "$CP" > "$OUT/asm.cp"
echo "組んだ: $OUT"
