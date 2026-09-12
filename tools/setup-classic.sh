#!/bin/sh
# 1.21.4 より前(classic)の Paper から、Shifu が使う木を用意する。
#
#     SHIFU_PAPER=/d/.pw194 JAVA_HOME=... sh tools/setup-classic.sh <Paper のコミット>
#
# 前提: $SHIFU_PAPER に Paper の worktree があること(無ければ作る。
# リポジトリの隣の .pw に Paper の履歴が要る)。JDK はその版の gradle が動くもの
# (1.20.5 以降は 21、それより前は 17)。
#
# paperweight の applyPatches は classic のパッチを当てられない(積み順が無い)。
# ここでは paperweight には API と NMS の逆コンパイルまでをやらせ、パッチは
# tools/apply_classic_by_file.py で当てる。出来上がるもの:
#
#   Paper-API/       Initial -> paper Patched
#   Paper-Server/    Initial -> paper Patched   (アダプタ層と、Paper が触った NMS)
#   vanilla-src/minecraft/java/   paper Imports  (decompileJar.jar そのまま。Shifu の基点)
#   sources/         make_classic_sources.py の出力(規則を書くときの材料)
#
# 1.20.6 で手で踏んだ手順をそのまま並べたもの。
set -e

. "$(dirname "$0")/env.sh"
COMMIT=${1:-}

say() {
    printf '%s\n' "$*"
}

# パッチは LF、作業ツリーは autocrlf で CRLF になると 1 件目から当たらない。
# git の設定は触らず、この中だけ環境変数で渡す。
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false

if [ ! -e "$PW/.git" ]; then
    if [ -z "$COMMIT" ]; then
        say "Paper の worktree が無い: $PW。作るにはコミットを渡す" >&2
        exit 1
    fi

    MAIN=$(cd "$SHIFU/.." && pwd)/.pw
    git -C "$MAIN" fetch --no-tags --depth=1 origin "$COMMIT" 2>/dev/null || true
    git -C "$MAIN" worktree add "$PW" "$COMMIT"
fi

say "Paper: $(git -C "$PW" log --oneline -1)"

if [ "$LAYOUT" != classic ]; then
    say "この Paper は mache(patches/server が無い)。tools/setup.sh を使う" >&2
    exit 1
fi

# 素の状態(Initial)に戻して、ファイルごとに割って当てる。
patch_repo() {
    _repo=$1
    _patches=$2
    _init=$(git -C "$_repo" log --format='%H %s' | awk '$0 ~ / Initial$/ { print $1; exit }')

    if [ -z "$_init" ]; then
        say "$_repo に Initial が無い" >&2
        exit 1
    fi

    if git -C "$_repo" log --format='%s' -1 | grep -q '^paper Patched$'; then
        say "$_repo は当て終わっている"
        return
    fi

    git -C "$_repo" am --abort 2>/dev/null || true
    git -C "$_repo" reset --hard -q "$_init"
    git -C "$_repo" clean -fdq
    python -u "$SHIFU/tools/apply_classic_by_file.py" "$_patches" "$_repo" || true
    git -C "$_repo" add -A
    git -C "$_repo" -c user.name=shifu -c user.email=shifu@local commit -qm "paper Patched"
    say "$_repo: $(git -C "$_repo" log --oneline -1)"
}

cd "$PW"

# 1. API。paperweight は Initial を作ったあと git am で落ちる。落ちてよい。
if [ ! -d "$PW/Paper-API/.git" ]; then
    "$GRADLEW" --no-daemon applyApiPatches "-Dorg.gradle.jvmargs=-Xmx6G" || true
fi

patch_repo "$PW/Paper-API" "$PW/patches/api"

# 2. NMS の逆コンパイルと Paper-Server の Initial。ここも git am で落ちてよい。
#    git の起動が WinError 5 で落ちて「git が PATH に無い」と言われることがある。掛け直す。
if [ ! -d "$PW/Paper-Server/.git" ]; then
    "$GRADLEW" --no-daemon applyServerPatches -x applyApiPatches "-Dorg.gradle.jvmargs=-Xmx6G" || true

    if [ ! -d "$PW/Paper-Server/.git" ]; then
        "$GRADLEW" --no-daemon applyServerPatches -x applyApiPatches "-Dorg.gradle.jvmargs=-Xmx6G" || true
    fi
fi

patch_repo "$PW/Paper-Server" "$PW/patches/server"

# 3. vanilla の木。decompileJar.jar の Java だけを取り出して 1 コミットにする。
if [ ! -d "$TREE/.git" ]; then
    mkdir -p "$TREE"
    unzip -qo "$PW/.gradle/caches/paperweight/taskCache/decompileJar.jar" -d "$TREE"
    (cd "$TREE" && rm -rf assets data flightrecorder-config.jfc version.json \
        && git init -q && git add -A \
        && git -c user.name=shifu -c user.email=shifu@local commit -qm "paper Imports")
fi

say "vanilla: $(git -C "$TREE" log --oneline -1)"

# 4. 規則を書くときの材料。mache の patches/sources と同じ形。
if [ ! -d "$SOURCES" ]; then
    python "$SHIFU/tools/make_classic_sources.py" "$TREE" "$PW/Paper-Server/src/main/java" "$SOURCES"
fi

say ""
say "次: sh tools/closure.sh(不動点まで回してコンパイル) -> sh tools/run-server.sh"
