# tools/*.sh が最初に読む。パスと JDK をここで決める。
#
#     . "$(dirname "$0")/env.sh"
#
# 変えたいときは環境変数か tools/env.local.sh(git 管理外)で上書きする。
#
#   SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
#   SHIFU_BASE   vanilla の基点コミット  既定: tools/build/base-commit.txt、無ければ 6d83d4b
#   JAVA_HOME    JDK 25                 環境のものを使う。未設定なら止まる

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

# vanilla の基点。setup.sh が Paper のクローンから拾って base-commit.txt に書く。
if [ -z "${SHIFU_BASE:-}" ] && [ -f "$SHIFU/tools/build/base-commit.txt" ]; then
    SHIFU_BASE=$(cat "$SHIFU/tools/build/base-commit.txt")
fi

SHIFU_BASE=${SHIFU_BASE:-6d83d4b}
export SHIFU_BASE

TREE=$PW/paper-server/src/minecraft/java
export PYTHONIOENCODING=utf-8
