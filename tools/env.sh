# tools/*.sh が最初に読む。パスと JDK をここで決める。
#
#     . "$(dirname "$0")/env.sh"
#
# 変えたいときは環境変数か tools/env.local.sh(git 管理外)で上書きする。
#
#   SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
#   SHIFU_BASE   vanilla の基点コミット  既定: tools/build/base-commit.txt、無ければ 6d83d4b
#   JAVA_HOME    JDK 25                 環境のものを使う。未設定なら止まる

# 呼び出し元が tools/ 直下でも tools/<道具>/ 配下でも動くよう、
# settings.gradle.kts と patches/ が見つかるまで上へ辿る。
SHIFU=$(cd "$(dirname "$0")" && pwd)

while [ "$SHIFU" != "/" ] && { [ ! -f "$SHIFU/settings.gradle.kts" ] || [ ! -d "$SHIFU/patches" ]; }; do
    SHIFU=$(dirname "$SHIFU")
done

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

# ブランチと $PW の版が合っているか。tools/env.local.sh(git 管理外)が無い worktree では
# $PW がリポジトリの親の .pw になるので、ver/1.18.2 の木から main の 26.2 のクローンを
# 見にいく。mache と見なされて JDK 25 が要ることになり、tools/build/lvtmatch の class が
# class file 69 で作られていた(1.18.2 は JDK 17 で走るので UnsupportedClassVersionError)。
_branch=$(git -C "$SHIFU" rev-parse --abbrev-ref HEAD 2>/dev/null || true)

case "$_branch" in
    ver/*)
        _want=${_branch#ver/}
        # クローンを作る前(setup.sh を回す前)は gradle.properties がまだ無い。そのときは見ない。
        _have=$(sed -n 's/^mcVersion=//p' "$PW/gradle.properties" 2>/dev/null | head -1)

        if [ -n "$_have" ] && [ "$_have" != "$_want" ]; then
            echo "$PW は Minecraft $_have の木だが、$_branch は $_want で組む。" >&2
            echo "  tools/env.local.sh で SHIFU_PAPER を $_want のクローンに向ける。" >&2
            exit 1
        fi

        unset _want _have
        ;;
esac

unset _branch

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

# JDK 21 以上の置き場を返す。:shifu-bootstrap は options.release = 21 なので、
# 1.18.2 / 1.19.4 が使う JDK 17 では「release version 21 not supported」で組めない。
# $JAVA_HOME と、その隣に入っている JDK から探す。SHIFU_JAVA21 で直接指定もできる。
shifu_jdk21() {
    for _home in "${SHIFU_JAVA21:-$JAVA_HOME}" "$(dirname "$JAVA_HOME")"/*; do
        [ -f "$_home/release" ] || continue
        _v=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$_home/release")

        if [ -n "$_v" ] && [ "$_v" -ge 21 ]; then
            printf '%s' "$_home"
            unset _home _v

            return 0
        fi
    done

    unset _home _v

    return 1
}

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
export PYTHONIOENCODING=utf-8
