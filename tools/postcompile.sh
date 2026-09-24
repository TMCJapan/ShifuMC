#!/bin/sh
# コンパイルしたあと、jar を作る前にやること。
#
#     sh tools/postcompile.sh
#
# 0. 無名クラスの番号と、それが捕まえた欄(val$…)の名前を公式に合わせる(tools/lvtmatch の SyntheticMatch)
# 1. 局所変数の番号を公式に合わせる(tools/lvtmatch の LvtMatch)
#    mixin は局所変数を slot の順に並べた型で照合する。コンパイルし直すと並びが変わり、
#    Carpet や Architectury API が LVT has incompatible changes で当たらない。
#    2 段でやる。1 段目は slot ごと入れ替える(表に出ない一時変数も一緒に動く)。
#    2 段目は残りを変数ごとに置き直す(1 つの slot を寿命の違う変数が使い回していて
#    行き先が食い違うものは、slot ごとでは直せない)。
# 2. 合成メソッド(lambda)の形を公式に合わせる(tools/lvtmatch の LambdaMatch)
# 3. 書き換えたメソッドの stackmap frame を計算し直す(tools/lvtmatch の FrameFix)
# 4. 触っていないクラスは公式のバイトコードに戻す(tools/keep_vanilla_classes.py)
# 5. 戻したクラスに無いメンバーへの参照を数える(tools/lvtmatch の LinkCheck)
# 6. 公式と命令列が違うメソッドを数える(tools/lvtmatch の CodeDiff)
#
# 後処理はこのスクリプト 1 つだけにする。tools/run-server.ps1 もここを呼ぶ。
# ps1 の側に写しがあったころは、1.18.2 / 1.19.4 の jar に FrameFix と LvtMatch --vars が
# 入らず、1.20.6 の jar には LinkCheck の検査が無かった(同じソースから別の jar が出ていた)。
#
# gradle は出力の変化を見て作り直すので、このあと jar を作るときは
# -x ":paper-server:compileJava" で再コンパイルを飛ばす。
set -e
set -o pipefail

. "$(dirname "$0")/env.sh"
CLASSES=$PW/paper-server/build/classes/java/main

# 公式のバイトコード。26.1 以降は難読化が無いので、bundler が取り出した server.jar をそのまま使う。
# それより前はクラス名が当たらないので、paperweight が名前を戻したものを使う
# (バイトコードは公式のまま)。mache は codebook-minecraft.jar、classic はクローン直下の
# minecraft.jar(decompileJar.jar はソースの jar なので当たらない)。
# 版の表を持たずに済むように、bundler の jar に net/minecraft の名前が入っているかで見分ける。
BUNDLED=$PW/paper-server/.gradle/caches/paperweight/data/bundler/server.jar

if unzip -l "$BUNDLED" net/minecraft/world/entity/Entity.class >/dev/null 2>&1; then
    MOJANG=$BUNDLED
elif [ -f "$PW/paper-server/.gradle/caches/paperweight/taskCache/codebook-minecraft.jar" ]; then
    MOJANG=$PW/paper-server/.gradle/caches/paperweight/taskCache/codebook-minecraft.jar
else
    MOJANG=$PW/.gradle/caches/paperweight/taskCache/minecraft.jar
fi

if [ ! -f "$MOJANG" ]; then
    echo "公式のバイトコードの jar が無い: $MOJANG" >&2
    exit 1
fi

MOJANG=$(cygpath -m "$MOJANG")

# 道具を組み直すかどうかは、出力より新しいソースがあるかで決める。asm.cp の有無だけを
# 見ていたころは、直した LinkCheck が走らないまま「missing 0」が出ていた(9-16 のビルド)。
STAMP=$SHIFU/tools/build/lvtmatch/asm.cp

if [ ! -f "$STAMP" ] \
        || [ -n "$(find "$SHIFU/tools/lvtmatch" -name '*.java' -newer "$STAMP" -print -quit)" ] \
        || [ "$SHIFU/tools/lvtmatch/build.sh" -nt "$STAMP" ]; then
    sh "$SHIFU/tools/lvtmatch/build.sh"
fi

cd "$SHIFU"
CP="$(cygpath -w tools/build/lvtmatch)$(cat tools/build/lvtmatch/asm.cp)"
CLASSES_M="$(cygpath -m "$CLASSES")"
DIFFERS="$(cygpath -m "$SHIFU/tools/build/lvt-differs.txt")"
# --slots に渡した一覧は 2 度目の compare_lvt で上書きされる。FrameFix に渡すために取っておく
SLOTS=$SHIFU/tools/build/lvt-differs-slots.txt

# 無名クラスの番号と val$ の名前を先に公式に合わせる。LvtMatch 以降は公式の同じ名前のクラスと
# 比べるので、番号がずれたままだと別のクラスと比べることになる(1.20.6 の Util$5 は、こちらが
# createFileDeletedCheck、公式が createRenamer)。LvtMatch / LambdaMatch が書く一覧も
# 付け替えた後の名前で出るので、FrameFix に渡す一覧を書き直さずに済む。
# 名前が違うと、そこを @Shadow する Fabric API の mixin が当たらずに落ちる
# (ServerGamePacketListenerImpl$1 の val$level / val$target、fabric-events-interaction-v0)。
# 対応が取れずに残したものは tools/build/synthetic-left.txt に出る。
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.SyntheticMatch "$CLASSES_M" "$MOJANG" \
    "$(cygpath -m "$SHIFU/tools/build/synthetic-left.txt")"

python tools/compare_lvt.py "$CLASSES_M" "$MOJANG" --list
cp "$SHIFU/tools/build/lvt-differs.txt" "$SLOTS"
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LvtMatch "$CLASSES_M" "$MOJANG" "$DIFFERS" --slots

python tools/compare_lvt.py "$CLASSES_M" "$MOJANG" --list
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LvtMatch "$CLASSES_M" "$MOJANG" "$DIFFERS" --vars

# 差し込みが公式と同じ型の局所変数を作っていないかを見る。tools/extra-locals-allowed.txt に
# 無い場所が 1 つでもあれば止める(数えるだけだったころに系統ごとに 12〜61 か所たまっていた)。
# MixinExtras の @Local は「その型がちょうど 1 つ」でないと当たらない。
# frame に TOP と書いて隠すことはできない(mixin の Locals.java が TOP を無視する)。
# 直すには差し込みの側で局所変数を作らないようにするしかない。
python tools/check_extra_locals.py "$CLASSES_M" "$MOJANG" --list

python tools/check_lambdas.py "$CLASSES_M" "$MOJANG" --list || true
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LambdaMatch "$CLASSES_M" "$MOJANG" \
    "$(cygpath -m "$SHIFU/tools/build/lambda-differs.txt")"

# 書き換えたメソッド(LvtMatch が slot を置き直した分、LambdaMatch が並べ替えた分)の
# stackmap frame を計算し直す。--vars は try-with-resources の一時変数(frame では top)を
# 生きている slot に重ねることがあり、MOD 無しの起動で VerifyError(Inconsistent stackmap
# frames)になる。MOD 入りだと Mixin が frame を計算し直すので見えない。
# 全クラスを計算し直すと、javac が top と書いた死んだ slot にも型が入る
# (1.20.6 の 9-16 のクラスで、LVT の範囲外の frame 項目が 936 -> 2,266)ので、
# 書き換えた分だけに絞る。
# 共通の親を決めるのに、公式の jar(触っていないクラスは classes に無い)とライブラリが要る。
# run-shifu/libraries は bundler を 1 度走らせるまで無いので、そのときは gradle のキャッシュと
# Paper-API の jar を渡す(Bukkit API は gradle のキャッシュに無く、org/bukkit/Location が
# 引けずに LivingEntity など 5 クラスが失敗した)。
# 置き場は位置引数に積む。空白で区切った 1 つの変数にすると、空白を含む場所
# (C:/Program Files など)が 2 つに割れる。
if [ -d "$PW/run-shifu/libraries" ]; then
    set -- "$(cygpath -m "$PW/run-shifu/libraries")"
else
    set -- "$(cygpath -m "$HOME/.gradle/caches/modules-2/files-2.1")" "$(cygpath -m "$PW/Paper-API/build/libs")"
fi

"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.FrameFix "$CLASSES_M" "$MOJANG" "$@" \
    -- "$(cygpath -m "$SLOTS")" "$DIFFERS" "$(cygpath -m "$SHIFU/tools/build/lambda-differs.txt")"

python tools/keep_vanilla_classes.py "$CLASSES_M" "$MOJANG"

# Paper の AT が広げた可視性を、組んだクラスの修飾子に書き写す(命令列は変えない)。
# 1.19.4 / 1.18.2 の vanilla の木は AT を当てる前の fixJar.jar から戻したものなので、
# そこから組んだクラスは Level.rainLevel が protected、SimpleContainer.items が private のままになる
# (9-16 のビルドで 64 件 / 65 件)。Paper の jar に向けて書かれたプラグインが触ると IllegalAccessError。
# 公式に戻したクラスは、classic では AT を当てたあとの minecraft.jar から取っているので、ここでは変わらない。
# 1.20.6 も AttributeModifier.name の 1 件が private のままだった。
# classic の mergeAdditionalAts.at を当てると、9-16 のビルドのクラスは applyMergedAt.jar
# (paperweight が AT を当てた jar)より狭いところが無くなる(残るのは javac が作る bridge と enum の構築子)。
if [ "$LAYOUT" = classic ]; then
    AT=$PW/.gradle/caches/paperweight/taskCache/mergeAdditionalAts.at
else
    AT=$PW/paper-server/.gradle/caches/paperweight/taskCache/mergePaperATs.at
fi

"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.AtFlags "$CLASSES_M" "$(cygpath -m "$AT")" "$MOJANG"

# 公式に戻したクラスに、残りのクラスが参照している欄やメソッドが無いと、起動時や
# そのメソッドに入った時点で NoSuchFieldError / NoSuchMethodError になる
# (1.19.4 の TicketType.PLUGIN、PlayerChatMessage.requireResult)。ここで数える。
# 親や interface を読むために、公式の jar とライブラリも渡す。
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.LinkCheck "$CLASSES_M" \
    "$(cygpath -m "$SHIFU/tools/build/kept-classes.txt")" \
    "$(cygpath -m "$SHIFU/tools/build/link-missing.txt")" "$MOJANG" "$@" | tail -2

# 無名クラスが捕まえた欄の名前が公式と違うと、そこを @Shadow している MOD の
# mixin が当たらずにサーバーが起動しない(理由は tools/check_synthetic_names.py)。
# 外側に 1 行足しただけでも起きるので、数を出しておく。
python tools/check_synthetic_names.py "$CLASSES_M" "$MOJANG" | tail -1 || true

# 公式と命令列が違うメソッドを数える(tools/lvtmatch の CodeDiff)。
# 差し込みで意図して増える分だけのはずなので、増えたら何かが余計に触っている。
# **同じ道具に組み上がった jar を渡して、同じ数になることも確かめること。**
# paperweight の fixJarForReobf は field の所有クラスを書き換えるので、
# class の側だけ見ていると 1844 件の食い違いを見落とす(tools/keepfields.gradle)。
"$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.CodeDiff "$CLASSES_M" "$MOJANG" \
    "$(cygpath -m "$SHIFU/tools/build/code-differs.txt")"

# patches/decompile/exprs.rules が method: で名指ししたメソッドが、公式と同じ命令列に
# コンパイルされたかを確かめる(tools/lvtmatch の SemDiff)。exprs.rules は vanilla の式を
# 書き換える規則で、verify_additive.py はメソッドの中に収まっていることしか見ない。
# 一致しなければここで止める(1.20.6 以前の AbstractHorse.getDismountLocationInDirection は
# 命令の数も種類も同じまま条件の向きだけが逆で、上の CodeDiff の数には出なかった)。
# SHIFU_NO_DECOMPILE で組んだとき(once.sh)は規則を当てていないので見ない。
if [ -z "${SHIFU_NO_DECOMPILE:-}" ]; then
    "$JAVA_HOME/bin/java" -cp "$CP" dev.shifu.lvtmatch.SemDiff "$CLASSES_M" "$MOJANG" \
        "$(cygpath -m "$SHIFU/patches/decompile/exprs.rules")"
fi
