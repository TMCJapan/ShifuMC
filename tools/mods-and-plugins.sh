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

# 組み直したクラスより jar が古いと、直したはずのものを試さずに終わる。
# 一度これで 3 回続けて古い jar を動かした。無いときだけでなく、古いときも組み直す。
CLASSES=$PW/Paper-Server/build/classes/java/main
if [ ! -f "$DIST/shifu-server.jar" ]         || { [ -d "$CLASSES" ] && [ -n "$(find "$CLASSES" -name "*.class" -newer "$DIST/shifu-server.jar" -print -quit)" ]; }; then
    sh "$SHIFU/tools/dist.sh" --build
fi

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

# bot に指示を出すプラグイン。前に別のバージョンで組んだものが残っていると
# api-version で弾かれて bot が何もしないまま終わるので、毎回組み直す。
# 組む相手は run-shifu(tools/run-server.sh が展開したもの)。$RUN のものは
# この下で消して、サーバーが起動しながら展開し直すため、この時点では無い。
if [ -d "$PW/run-shifu/libraries" ]; then
    DRIVE_CP="$(cygpath -w "$SERVER_JAR")"
    for jar in $(find "$PW/run-shifu/libraries" -name "*.jar"); do
        DRIVE_CP="$DRIVE_CP;$(cygpath -w "$jar")"
    done

    rm -rf "$SHIFU/tools/build/drive"
    "$JAVA_HOME/bin/javac" -encoding UTF-8 --release 21 -cp "$DRIVE_CP" -d "$SHIFU/tools/build/drive" "$SHIFU"/tools/plugin-drive/src/dev/shifu/drive/*.java
    cp "$SHIFU/tools/plugin-drive/plugin.yml" "$SHIFU/tools/build/drive/"
    (cd "$SHIFU/tools/build/drive" && "$JAVA_HOME/bin/jar" cf ../ShifuPluginDrive.jar .)
    cp "$SHIFU/tools/build/ShifuPluginDrive.jar" "$RUN/plugins/"
else
    echo "run-shifu が無いので bot に指示を出すプラグインは入れない" >&2
    rm -f "$RUN/plugins/ShifuPluginDrive.jar"
fi
[ -f "$PW/run-fabric/mods/ProbeMod.jar" ] && cp "$PW/run-fabric/mods/ProbeMod.jar" "$RUN/mods/"

# 26.2 で動かないもの。一覧は docs/STATUS.md
#   PacketEvents 2.13.0 / GrimAC(同梱)— packet id が引けずログインで切られる
#   FastAsyncWorldEdit — Paper の moonrise / starlight の内部を直に呼ぶ
rm -f "$RUN/plugins"/packetevents-*.jar "$RUN/plugins"/grimac-*.jar "$RUN/plugins"/fastasyncworldedit-*.jar

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

CP="$(cygpath -w "$RUN/versions/$MC_VERSION/paper-$MC_VERSION.jar")"
for jar in $(find "$RUN/libraries" -name "*.jar"); do
    CP="$CP;$(cygpath -w "$jar")"
done

"$JAVA_HOME/bin/javac" -encoding UTF-8 --release "$JDK_MIN" -cp "$CP" -d "$SHIFU/tools/build/bot" "$SHIFU"/tools/bot/src/dev/shifu/bot/*.java

"$JAVA" -cp "$(cygpath -w "$SHIFU/tools/build/bot");$CP" dev.shifu.bot.Bot 127.0.0.1 25593 ShifuBot 50 > bot.out 2>&1 || true
sleep 8
kill $SERVER 2>/dev/null || true

echo "==== MOD ===="
grep -a "Loading .* mods" -A 8 launch.out | head -12
echo "==== プラグイン ===="
grep -a "^ - \|Enabling" launch.out | head -4
echo "==== bot ===="
grep -ah "\[drive\]" launch.out launch.err | sed 's/.*\[drive\]/[drive]/' || true
grep -a "joined the game" launch.out
echo "==== 例外 ===="
grep -ac "Exception\|SEVERE" launch.out || true
