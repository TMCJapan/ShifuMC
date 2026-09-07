# tools/*.sh が最初に読む。パスと JDK をここで決める。
#
#     . "$(dirname "$0")/env.sh"
#
# 変えたいときは環境変数か tools/env.local.sh(git 管理外)で上書きする。
#
#   SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
#   SHIFU_BASE   vanilla の基点コミット  既定: tools/build/base-commit.txt、無ければ 6d83d4b
#   JAVA_HOME    JDK 25                 環境のものを使う。未設定なら止まる
#   MC_VERSION   Minecraft の版         既定: Paper のクローンの gradle.properties
#
# 読める変数: SHIFU PW TREE RESOURCES JAVA GRADLEW MC_VERSION BUNDLER_JAR SERVER_JAR

SHIFU=$(cd "$(dirname "$0")/.." && pwd)

# $0 は呼び出し側のスクリプトなので、tools/*.sh 以外から読むと別の場所を指す。
# 黙って進むと Paper のクローンを別の場所に作りにいくので、ここで止める。
if [ ! -f "$SHIFU/settings.gradle.kts" ] || [ ! -d "$SHIFU/patches" ]; then
    echo "リポジトリのルートを決められなかった: $SHIFU" >&2
    echo "  tools/*.sh は 'sh tools/<名前>.sh' の形で呼ぶ。" >&2
    exit 1
fi

PW=${SHIFU_PAPER:-$(cd "$SHIFU/.." && pwd)/.pw}

# tools/paths.py が同じ値を使えるように渡す。
SHIFU_PAPER=$PW
export SHIFU_PAPER

# 個人の設定。SHIFU と PW が決まったあとに読むので、どちらも上書きできる。
if [ -f "$SHIFU/tools/env.local.sh" ]; then
    . "$SHIFU/tools/env.local.sh"
fi

if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME が設定されていない。JDK 25 を指すように設定する。" >&2
    echo "  例: export JAVA_HOME=/c/Program\\ Files/Eclipse\\ Adoptium/jdk-25.0.3.9-hotspot" >&2
    exit 1
fi

# JDK の release ファイルから版を見る(java を起動しない)。
# Java 25 が要るのは、逆コンパイルしたソースが class file 69 になるため。
if [ -f "$JAVA_HOME/release" ]; then
    _jver=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$JAVA_HOME/release")

    if [ -n "$_jver" ] && [ "$_jver" -lt 25 ]; then
        echo "JAVA_HOME が JDK $_jver を指している。25 以上が要る: $JAVA_HOME" >&2
        exit 1
    fi

    unset _jver
fi

JAVA="$JAVA_HOME/bin/java"
export JAVA_HOME

# Gradle ラッパー。cd した先からの相対で呼ぶ。
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) GRADLEW=./gradlew.bat ;;
    *)                    GRADLEW=./gradlew ;;
esac

TREE=$PW/paper-server/src/minecraft/java
RESOURCES=$PW/paper-server/src/minecraft/resources

# vanilla の基点。バージョンごとに違うので、そのクローンの履歴から拾う。
# 履歴がまだ無いとき(setup.sh を回す前)だけ base-commit.txt を見る。
if [ -z "${SHIFU_BASE:-}" ] && [ -d "$TREE" ]; then
    SHIFU_BASE=$(git -C "$TREE" log --format='%H %s' 2>/dev/null | awk '$0 ~ /paper Imports$/ { print $1; exit }' || true)
fi

if [ -z "${SHIFU_BASE:-}" ] && [ -f "$SHIFU/tools/build/base-commit.txt" ]; then
    SHIFU_BASE=$(cat "$SHIFU/tools/build/base-commit.txt")
fi

SHIFU_BASE=${SHIFU_BASE:-6d83d4b}
export SHIFU_BASE

# resources 側の基点。java と同じくパッチが当たる前。
if [ -z "${SHIFU_BASE_RESOURCES:-}" ] && [ -d "$RESOURCES" ]; then
    SHIFU_BASE_RESOURCES=$(git -C "$RESOURCES" log --format='%H %s' 2>/dev/null | awk '$0 ~ /Vanilla$/ { print $1; exit }' || true)
fi

export SHIFU_BASE_RESOURCES

# Minecraft のバージョン。jar の名前と run-shifu/versions/<版>/ に入る。
# Paper のクローンの gradle.properties が持っている。
if [ -z "${MC_VERSION:-}" ] && [ -f "$PW/gradle.properties" ]; then
    MC_VERSION=$(sed -n 's/^mcVersion=//p' "$PW/gradle.properties" | head -1)
fi

# 組んだ bundler の jar。名前に版が入り、1.21.x は mojmap と reobf に分かれる。
BUNDLER_JAR=$(ls "$PW"/paper-server/build/libs/*bundler*.jar 2>/dev/null | grep -v reobf | head -1)

# bundler が起動時に展開するサーバー本体。プラグインを組むときのクラスパス。
SERVER_JAR=$PW/run-shifu/versions/$MC_VERSION/paper-$MC_VERSION.jar
export MC_VERSION SERVER_JAR BUNDLER_JAR

export PYTHONIOENCODING=utf-8
