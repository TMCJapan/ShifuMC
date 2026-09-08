#!/bin/sh
# 当てたツリーからサーバーを組んで、プラグイン無しで起動する。
#
# `closure.sh` か `once.sh` を先に回して、ツリーに shim が当たっている状態で使う。
# 起動しない不具合はここで出る。出力は run-shifu/shifu.log に残る。
#
#     sh tools/run-server.sh          # 組んでから起動
#     sh tools/run-server.sh --skip-build   # 前に組んだ jar で起動
set -e

. "$(dirname "$0")/env.sh"
RUN=$PW/run-shifu
JAR=$BUNDLER_JAR

if [ "$1" != "--skip-build" ]; then
    cd "$PW"
    # createPaperclipJar はワーカーが落ちることがある。動作確認は bundler で足りる。
    # class の後処理(局所変数の番号合わせと、公式バイトコードへの戻し)
    "$GRADLEW" --no-daemon ":paper-server:compileJava" "-Dorg.gradle.jvmargs=-Xmx6G"
    sh "$SHIFU/tools/postcompile.sh"
    # 26.x は createBundlerJar の 1 つだけ。1.21.x は mojmap と reobf に分かれる。
    # Shifu は実行時も mojmap なので mojmap の方を組む。
    BUNDLE=:paper-server:createBundlerJar
    LIBS=$PW/paper-server/build/libs

    if "$GRADLEW" --no-daemon "tasks" --all 2>/dev/null | grep -q createMojmapBundlerJar; then
        BUNDLE=:createMojmapBundlerJar
        LIBS=$PW/build/libs
    fi

    "$GRADLEW" --no-daemon "$BUNDLE" -x ":paper-server:compileJava" \
        "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US"
    JAR=$(ls "$LIBS"/*bundler*.jar 2>/dev/null | grep -v reobf | head -1)
fi

if [ -z "$JAR" ]; then
    echo "bundler の jar が無い: $PW/paper-server/build/libs" >&2
    exit 1
fi

mkdir -p "$RUN"
cd "$RUN"

# PowerShell の Set-Content は BOM を付けて eula.txt が読めなくなる。printf で書く。
[ -f eula.txt ] || printf 'eula=true\n' > eula.txt

# --nogui で GUI を出さない。--forceUpgrade などは付けない(vanilla の経路のまま通す)。
#
# Paper の log4j 設定は非同期の appender を通す。落ちる直前のスタックトレースが
# 出力されないまま JVM が終わるので、原因が分からない(exit 0 で何も出ない)。
# 同期の設定に差し替えて走らせる。Java は MSYS の /d/... を解決できないので
# cygpath -m で Windows の形に直し、URL として解釈されるので file:/// を付けて渡す
# (付けないと "unknown protocol: d" になる)。
"$JAVA_HOME/bin/java" -Xmx4G -Duser.language=en -Duser.country=US \
    -Dlog4j.configurationFile="file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")" \
    -jar "$JAR" --nogui 2>&1 | tee shifu.log
