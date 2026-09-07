#!/bin/sh
# 実際のプラグイン(WorldEdit と EssentialsX)を入れ、bot をプレイヤーとして繋いで
# プラグインのコマンドを叩かせる。
#
#     sh tools/real-plugins.sh
#
# 前提: tools/run-server.sh で組んだ jar と、run-shifu に展開済みの versions / libraries。
# プラグインは初回に取ってくる(tools/build/plugins に置く。ネットが要る)。
#
# 出力: run-plugins/server.log(サーバー)と run-plugins/bot.log(bot)。
# 見るもの: 有効化のログ、[drive] の行(ブロックが本当に変わったか)、例外。
set -e

. "$(dirname "$0")/env.sh"
RUN=$PW/run-plugins
SRC=$PW/run-shifu
JAR=$BUNDLER_JAR
CACHE=$SHIFU/tools/build/plugins
OUT=$SHIFU/tools/build
JAVAC="$JAVA_HOME/bin/javac"

mkdir -p "$CACHE"
[ -f "$CACHE/worldedit.jar" ] || curl -s -L -o "$CACHE/worldedit.jar" \
    "https://cdn.modrinth.com/data/1u6JkXh5/versions/F5ea2ov3/worldedit-bukkit-7.4.5.jar"
[ -f "$CACHE/essentialsx.jar" ] || curl -s -L -o "$CACHE/essentialsx.jar" \
    "https://github.com/EssentialsX/Essentials/releases/download/2.22.0/EssentialsX-2.22.0.jar"

CP="$(cygpath -w "$SRC/versions/$MC_VERSION/paper-$MC_VERSION.jar")"
for jar in $(find "$SRC/libraries" -name "*.jar"); do
    CP="$CP;$(cygpath -w "$jar")"
done

rm -rf "$OUT/drive"
mkdir -p "$OUT/drive" "$OUT/bot" "$RUN/plugins"
"$JAVAC" -encoding UTF-8 --release 21 -cp "$CP" -d "$OUT/drive" "$SHIFU"/tools/plugin-drive/src/dev/shifu/drive/*.java
cp "$SHIFU/tools/plugin-drive/plugin.yml" "$OUT/drive/"
(cd "$OUT/drive" && "$JAVA_HOME/bin/jar" cf ../ShifuPluginDrive.jar .)
"$JAVAC" -encoding UTF-8 --release 25 -cp "$CP" -d "$OUT/bot" "$SHIFU"/tools/bot/src/dev/shifu/bot/*.java

cp "$CACHE"/*.jar "$OUT/ShifuPluginDrive.jar" "$RUN/plugins/"
[ -f "$RUN/eula.txt" ] || printf 'eula=true\n' > "$RUN/eula.txt"
[ -f "$RUN/server.properties" ] || printf 'online-mode=false\nserver-port=25597\nlevel-seed=1234567890\n' > "$RUN/server.properties"

cd "$RUN"
rm -f server.log bot.log
"$JAVA" -Xmx4G -Duser.language=en -Duser.country=US \
    -Dlog4j.configurationFile="file:///$(cygpath -m "$SHIFU/tools/log4j2-sync.xml")" \
    -jar "$JAR" --nogui > server.log 2>&1 &
SERVER=$!

for i in $(seq 1 180); do
    if grep -q "Done (" server.log 2>/dev/null; then
        break
    fi
    sleep 1
done

if ! grep -q "Done (" server.log; then
    echo "server did not start"
    kill $SERVER 2>/dev/null || true
    exit 1
fi

"$JAVA" -cp "$(cygpath -w "$OUT/bot");$CP" dev.shifu.bot.Bot 127.0.0.1 25597 ShifuBot 40 > bot.log 2>&1 || true

for i in $(seq 1 60); do
    if ! kill -0 $SERVER 2>/dev/null; then
        break
    fi
    sleep 1
done
kill $SERVER 2>/dev/null || true

echo "==== 有効化 ===="
grep -ai "Enabling\|Done (" server.log | head -10
echo "==== drive ===="
grep -a "\[drive\]" server.log | sed 's/.*\[drive\]/[drive]/'
echo "==== プラグインの応答 ===="
grep -ai "issued server command\|WorldEdit\|Essentials" server.log | tail -20
echo "==== 例外 ===="
grep -ac "Exception\|SEVERE" server.log || true
grep -an "Exception\|SEVERE" server.log | head -20 || true
