#!/bin/sh
# 配布物を組んで、まっさらな場所で動くかを見る。
#
#     sh tools/dist.sh            # 組んで、動作確認まで
#     sh tools/dist.sh --build    # 組むだけ
#
# 出るもの(tools/build/dist):
#   shifu.jar             起動側(70 KB)。Minecraft も Paper も Shifu のサーバーも入っていない
#   shifu-server.jar      Shifu のサーバー(paperclip 形式)。Minecraft のクラスは入っていない
#
# 使う側の手順:
#   1. shifu.jar を空のフォルダに置く
#   2. java -jar shifu.jar   (初回に shifu.properties が出る)
#   3. shifu.properties の server-paperclip に shifu-server.jar のパスか URL を書く
#   4. もう一度 java -jar shifu.jar
# これで Mojang の server.jar が落ちてきて、Shifu のサーバーが組み上がり、
# Fabric Loader 越しに起動する(mods/ と plugins/ の両方が効く)。
set -e

. "$(dirname "$0")/env.sh"
DIST=$SHIFU/tools/build/dist
RUN=$PW/run-dist-check

mkdir -p "$DIST"

echo "==== 起動側 ===="
# :shifu-bootstrap は options.release = 21。JDK 17 を使う系統では
# 「release version 21 not supported」で止まるので、この段だけ 21 以上で組む。
BOOT_JDK=$(shifu_jdk21) || {
    echo "JDK 21 以上が見つからない。SHIFU_JAVA21 でその置き場を指す。" >&2
    exit 1
}
echo "起動側の JDK: $BOOT_JDK"
(cd "$SHIFU" && JAVA_HOME=$BOOT_JDK "$GRADLEW" --no-daemon :shifu-bootstrap:jar)
cp "$SHIFU"/shifu-bootstrap/build/libs/shifu-*.jar "$DIST/shifu.jar"

echo "==== サーバー(paperclip)===="
# コンパイル -> class の後処理 -> jar の順(後処理の中身は tools/postcompile.sh)。
# gradle は出力の変化を見て再コンパイルするので、jar を作るときは compileJava を飛ばす。
(cd "$PW" && "$GRADLEW" --no-daemon ":paper-server:compileJava" "-Dorg.gradle.jvmargs=-Xmx6G")
sh "$SHIFU/tools/postcompile.sh"
(cd "$PW" && "$GRADLEW" --no-daemon ":paper-server:createPaperclipJar" -x ":paper-server:compileJava" \
    "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US")
cp "$PW"/paper-server/build/libs/paper-paperclip-*.jar "$DIST/shifu-server.jar"

ls -la "$DIST"

if [ "$1" = "--build" ]; then
    exit 0
fi

echo "==== まっさらな場所で起動 ===="
rm -rf "$RUN"
mkdir -p "$RUN/mods" "$RUN/plugins"
cd "$RUN"
cp "$DIST/shifu.jar" .
printf 'eula=true\n' > eula.txt
printf 'online-mode=false\nserver-port=25594\nlevel-seed=1234567890\n' > server.properties
printf 'minecraft-version = 26.2\npaper-build = latest\nserver-paperclip = %s\nfabric-loader-version = 0.19.3\nvanilla-parity = true\njvm-args = -Xmx4G\n' \
    "$(cygpath -m "$DIST/shifu-server.jar")" > shifu.properties

# MOD とプラグインが両方効くかも見る
[ -f "$PW/run-fabric/mods/ProbeMod.jar" ] && cp "$PW/run-fabric/mods/ProbeMod.jar" mods/
[ -f "$SHIFU/tools/build/plugins/worldedit.jar" ] && cp "$SHIFU/tools/build/plugins/worldedit.jar" plugins/

# 起動側は class file 65(release 21)なので、組んだときと同じ JDK で走らせる。
# サーバーは ServerLaunch が同じ java.home で子 JVM として立ち上げる。
status=0
(
    for i in $(seq 1 240); do
        if grep -aq "Done (" launch.log 2>/dev/null; then
            break
        fi
        sleep 1
    done
    sleep 4
    echo stop
) | "$BOOT_JDK/bin/java" -jar shifu.jar nogui > launch.log 2>&1 || status=$?

echo "---- 組み立てと起動 ----"
grep -a "\[shifu\]\|Done (\|Loading .* mods" launch.log | head -12
echo "---- MOD とプラグイン ----"
grep -a "probemod\|Enabling" launch.log | head -6
echo "---- 例外 ----"
grep -ac "Exception\|SEVERE" launch.log || true

# 「Done (」が出なければ起動していない。9-13 の shifu.jar のまま通っていたのは
# この段を || true で流していたため。
if ! grep -aq "Done (" launch.log; then
    echo "起動が Done まで届かなかった(exit $status): $RUN/launch.log" >&2
    exit 1
fi
