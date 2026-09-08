#!/bin/sh
# コンパイルしたあと、jar を作る前にやること。
#
#     sh tools/postcompile.sh
#
# 1. 局所変数の番号を公式に合わせる(tools/lvtmatch)
#    mixin は局所変数を slot の順に並べた型で照合する。コンパイルし直すと並びが変わり、
#    Carpet や Architectury API が LVT has incompatible changes で当たらない。
#    2 段でやる。1 段目は slot ごと入れ替える(表に出ない一時変数も一緒に動く)。
#    2 段目は残りを変数ごとに置き直す(1 つの slot を寿命の違う変数が使い回していて
#    行き先が食い違うものは、slot ごとでは直せない)。
# 2. 合成メソッド(lambda)の形を公式に合わせる(tools/lvtmatch の LambdaMatch)
# 3. 触っていないクラスは公式のバイトコードに戻す(tools/keep_vanilla_classes.py)
#
# gradle は出力の変化を見て作り直すので、このあと jar を作るときは
# -x ":paper-server:compileJava" で再コンパイルを飛ばす。
set -e
set -o pipefail

. "$(dirname "$0")/env.sh"
CLASSES=$PW/paper-server/build/classes/java/main
# 26.1 より前の公式 jar は難読化されていてクラス名が当たらない。paperweight が
# codebook で名前を戻したものを使う(バイトコードは公式のまま)。
MOJANG=$(cygpath -m "$PW/paper-server/.gradle/caches/paperweight/taskCache/codebook-minecraft.jar")

# classic(1.21.3 以前)は codebook を使わない。キャッシュはクローンの直下にあって、
# mojmap に戻したものは minecraft.jar(decompileJar.jar はソースの jar なので当たらない)。
if [ ! -f "$PW/paper-server/.gradle/caches/paperweight/taskCache/codebook-minecraft.jar" ]; then
    MOJANG=$(cygpath -m "$PW/.gradle/caches/paperweight/taskCache/minecraft.jar")
fi

[ -f "$SHIFU/tools/build/lvtmatch/asm.cp" ] || sh "$SHIFU/tools/lvtmatch/build.sh"

cd "$SHIFU"
CP="$(cygpath -w tools/build/lvtmatch)$(cat tools/build/lvtmatch/asm.cp)"
CLASSES_M="$(cygpath -m "$CLASSES")"
DIFFERS="$(cygpath -m "$SHIFU/tools/build/lvt-differs.txt")"

python tools/compare_lvt.py "$CLASSES_M" "$MOJANG" --list
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LvtMatch "$CLASSES_M" "$MOJANG" "$DIFFERS" --slots

python tools/compare_lvt.py "$CLASSES_M" "$MOJANG" --list
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LvtMatch "$CLASSES_M" "$MOJANG" "$DIFFERS" --vars

# 差し込みが公式と同じ型の局所変数を作っていないかを見る(数えるだけ)。
# MixinExtras の @Local は「その型がちょうど 1 つ」でないと当たらない。
# frame に TOP と書いて隠すことはできない(mixin の Locals.java が TOP を無視する)。
# 直すには差し込みの側で局所変数を作らないようにするしかない。
python tools/check_extra_locals.py "$CLASSES_M" "$MOJANG" --list || true

python tools/check_lambdas.py "$CLASSES_M" "$MOJANG" --list || true
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LambdaMatch "$CLASSES_M" "$MOJANG" \
    "$(cygpath -m "$SHIFU/tools/build/lambda-differs.txt")"

python tools/keep_vanilla_classes.py "$CLASSES_M" "$MOJANG"
