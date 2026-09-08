#!/bin/sh
# プレイヤー経路のイベントを、ヘッドレスクライアント(bot)を繋いで確かめる。
#
#     sh tools/player-events.sh          # 検証プラグインと bot を組んで、サーバーを起動し、bot を繋ぐ
#
# 前提: tools/run-server.sh で組んだ jar があり、run-shifu/ が展開済み(versions/ と libraries/)。
# 手順:
#   1. tools/probe のプラグインを paper-api に対して組み、run-shifu/plugins に置く
#   2. tools/bot を run-shifu の versions と libraries に対して組む
#   3. サーバーを起動して Done を待つ
#   4. bot を繋ぐ。プラグインが chat で bot に指示を出し、届いたイベントを [probe] で残す
#   5. プラグインが最後にサーバーを止める
# 出力は run-shifu/player-events.log(サーバー)と run-shifu/bot.log(bot)。
set -e

. "$(dirname "$0")/env.sh"
RUN=$PW/run-shifu
JAR=$BUNDLER_JAR
JAVAC="$JAVA_HOME/bin/javac"
OUT=$SHIFU/tools/build

# 自分の出力だけ消す(tools/build には測定用 agent の jar も置いてある)
rm -rf "$OUT/probe" "$OUT/bot"
mkdir -p "$OUT/probe" "$OUT/bot"

# サーバーの jar と展開済みのライブラリ。API と adventure はこの中にある
CP="$(cygpath -w "$RUN/versions/$MC_VERSION/paper-$MC_VERSION.jar")"
for jar in $(find "$RUN/libraries" -name "*.jar"); do
    CP="$CP;$(cygpath -w "$jar")"
done

# 1. 検証プラグイン
"$JAVAC" -encoding UTF-8 --release 21 -cp "$CP" -d "$OUT/probe" "$SHIFU"/tools/probe/src/dev/shifu/probe/*.java
cp "$SHIFU/tools/probe/plugin.yml" "$OUT/probe/"
(cd "$OUT/probe" && "$JAVA_HOME/bin/jar" cf ../ShifuPlayerProbe.jar .)
cp "$OUT/ShifuPlayerProbe.jar" "$RUN/plugins/"

# 2. bot
"$JAVAC" -encoding UTF-8 --release "$JDK_MIN" -cp "$CP" -d "$OUT/bot" "$SHIFU"/tools/bot/src/dev/shifu/bot/*.java

# 3. サーバー
cd "$RUN"
rm -f player-events.log bot.log
"$JAVA" -Xmx4G -Duser.language=en -Duser.country=US \
    -Dlog4j.configurationFile="file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")" \
    -jar "$JAR" --nogui > player-events.log 2>&1 &
SERVER=$!

for i in $(seq 1 120); do
    if grep -q "Done (" player-events.log 2>/dev/null; then
        break
    fi
    sleep 1
done

if ! grep -q "Done (" player-events.log; then
    echo "server did not start"
    kill $SERVER 2>/dev/null || true
    exit 1
fi

# 4. bot
"$JAVA" -cp "$(cygpath -w "$OUT/bot");$CP" dev.shifu.bot.Bot 127.0.0.1 25599 ShifuBot 60 > bot.log 2>&1 || true

# 5. プラグインが止めるのを待つ
for i in $(seq 1 60); do
    if ! kill -0 $SERVER 2>/dev/null; then
        break
    fi
    sleep 1
done
kill $SERVER 2>/dev/null || true

echo "==== probe ===="
grep "\[probe\]" player-events.log | sed 's/.*\[probe\]/[probe]/'
echo "==== bot ===="
cat bot.log
echo "==== errors ===="
grep -n "ERROR\|Exception\|Caused by" player-events.log | grep -v "Unsafe\|DedicatedServer.*Command exception" | head -40 || true
