#!/bin/sh
# Fabric の MOD と Bukkit のプラグインを同時に入れて、bot をプレイヤーとして繋ぐ。
#
#     sh tools/mods-and-plugins.sh
#
# 前提: tools/dist.sh で組んだ tools/build/dist の 2 つ。無ければ先に組む。
# MOD とプラグインは tools/fetch_addons.py が Modrinth から取ってくる(初回だけ)。
#
# 出力: run-mix/launch.out(サーバーの標準出力)、launch.err(プラグインの
# java.util.logging はこちらに出る)、bot.out。
set -e
set -o pipefail

. "$(dirname "$0")/env.sh"
RUN=$PW/run-mix
DIST=$SHIFU/tools/build/dist
ADDONS=$SHIFU/tools/build/addons/$MC_VERSION

[ -f "$DIST/shifu-server.jar" ] || sh "$SHIFU/tools/dist.sh" --build

python "$SHIFU/tools/fetch_addons.py" "$ADDONS"

# 2026-09-03 まで、Fabric API の permission モジュールは Paper の CommandSourceStack と
# 衝突していた(NMS のクラス宣言に interface を足していたため)。アダプタ層で包む形に
# 直したので差し替えは要らない。古い差し替えが残っていたら消す。
rm -f "$ADDONS/mods/zz-fabric-permission-api-shifu.jar" "$RUN/mods/zz-fabric-permission-api-shifu.jar"

mkdir -p "$RUN/mods" "$RUN/plugins"
rm -f "$RUN"/mods/*.jar

# SHIFU_NO_MODS=1 でプラグインだけにする。名前空間の橋(intermediary -> mojmap)が
# 要るバージョンで、サーバー側とプラグイン側だけを切り分けて見るときに使う。
if [ -z "${SHIFU_NO_MODS:-}" ]; then
    cp "$ADDONS"/mods/*.jar "$RUN/mods/"
fi

cp "$ADDONS"/plugins/*.jar "$RUN/plugins/"
[ -f "$SHIFU/tools/build/ShifuPluginDrive.jar" ] && cp "$SHIFU/tools/build/ShifuPluginDrive.jar" "$RUN/plugins/"
[ -f "$PW/run-fabric/mods/ProbeMod.jar" ] && cp "$PW/run-fabric/mods/ProbeMod.jar" "$RUN/mods/"

# 26.2 で動かないもの。一覧は docs/STATUS.md
#   PacketEvents 2.13.0 / GrimAC(同梱)— packet id が引けずログインで切られる
#   FastAsyncWorldEdit — Paper の moonrise / starlight の内部を直に呼ぶ
rm -f "$RUN/plugins"/packetevents-*.jar "$RUN/plugins"/grimac-*.jar "$RUN/plugins"/fastasyncworldedit-*.jar

# 1.20.6 で動かない MOD。理由は docs/STATUS.md
if [ "$MC_VERSION" = 1.20.6 ]; then
    rm -f "$RUN/mods"/c2me-fabric-*.jar "$RUN/mods"/servercore-*.jar
    rm -f "$RUN/mods"/ledger-*.jar "$RUN/mods"/alternate-current-*.jar
fi

# 内容を足す MOD(ブロックやバイオームを登録するもの)を入れると、Fabric のレジストリ同期が
# 素のクライアントを弾く。bot は素のクライアントなので、遊びの確認をするときは外す。
# 起動だけを見るなら SHIFU_CONTENT_MODS=1 で残す
if [ -z "${SHIFU_CONTENT_MODS:-}" ]; then
    mkdir -p "$ADDONS/content"
    for jar in "$RUN"/mods/*.jar; do
        [ -f "$jar" ] || continue

        case "$(basename "$jar")" in
            fabric-api-*|lithium-*|ferrite-core-*|krypton-*|c2me-fabric-*|attributefix-*|prickle-*|ProbeMod.jar) ;;
            fabric-language-kotlin-*|carpet-*|alternate-current-*|servercore-*|vmp-fabric-*) ;;
            no-chat-reports-*|ledger-*|styled-chat-*|spark-*|chunky-*|architectury-api-*) ;;
            *) mv "$jar" "$ADDONS/content/" ;;
        esac
    done
fi

cp "$DIST/shifu.jar" "$RUN/"
[ -f "$RUN/eula.txt" ] || printf 'eula=true\n' > "$RUN/eula.txt"
[ -f "$RUN/server.properties" ] || printf 'online-mode=false\nserver-port=25593\nlevel-seed=1234567890\n' > "$RUN/server.properties"
printf 'minecraft-version = %s\npaper-build = latest\nserver-paperclip = %s\nfabric-loader-version = %s\nvanilla-parity = true\njvm-args = -Xmx4G\n' \
    "$MC_VERSION" "$(cygpath -m "$DIST/shifu-server.jar")" "$FABRIC_LOADER" > "$RUN/shifu.properties"

# 前に組んだサーバーが残っていると、新しい jar で組み直さない
rm -rf "$RUN/versions" "$RUN/libraries" "$RUN/cache" "$RUN/.shifu" "$RUN/world" "$RUN/world_nether" "$RUN/world_the_end"

cd "$RUN"
rm -f launch.out launch.err bot.out
"$JAVA" -jar shifu.jar nogui > launch.out 2> launch.err &
SERVER=$!

for i in $(seq 1 300); do
    if grep -aq "Done (" launch.out 2>/dev/null; then
        break
    fi
    sleep 1
done

if ! grep -aq "Done (" launch.out; then
    echo "起動しなかった"
    tail -5 launch.out
    kill $SERVER 2>/dev/null || true
    exit 1
fi

CP="$(cygpath -w "$SERVER_JAR")"
for jar in $(find "$PW/run-shifu/libraries" -name "*.jar"); do
    CP="$CP;$(cygpath -w "$jar")"
done

"$JAVA" -cp "$(cygpath -w "$SHIFU/tools/build/bot");$CP" dev.shifu.bot.Bot 127.0.0.1 25593 ShifuBot 50 > bot.out 2>&1 || true
sleep 8
kill $SERVER 2>/dev/null || true

echo "==== MOD ===="
grep -a "Loading .* mods" -A 8 launch.out | head -12
echo "==== プラグイン ===="
grep -a "^ - \|Enabling" launch.out | head -4
echo "==== bot ===="
grep -a "\[drive\]" launch.err | sed 's/.*\[drive\]/[drive]/'
grep -a "joined the game" launch.out
echo "==== 例外 ===="
grep -ac "Exception\|SEVERE" launch.out || true
