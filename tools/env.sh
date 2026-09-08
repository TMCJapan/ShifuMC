# tools/*.sh が最初に読む。パスと JDK をここで決める。
#
#     . "$(dirname "$0")/env.sh"
#
# 変えたいときは環境変数か tools/env.local.sh(git 管理外)で上書きする。
#
#   SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
#   SHIFU_TREE   vanilla のソースの木    既定: $PW/paper-server/src/minecraft/java
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

# Paper の並べ方。1.21.4 以降は mache(paper-server と patches/sources)、
# それより前は classic(Paper-Server と patches/server)。
# Windows では paper-server と Paper-Server が同じ場所になるので、
# ディレクトリの有無ではなく patches の側で見分ける。
if [ -d "$PW/patches/server" ]; then
    LAYOUT=classic
else
    LAYOUT=mache
fi

# 要る JDK。mache は逆コンパイルしたソースが class file 69 になるので 25 が要る。
# classic の gradle ラッパーは 8.7 で、動くのは Java 21 まで。
# 22 でも「Unsupported class file major version 66」で落ちる。
if [ "$LAYOUT" = classic ]; then
    JDK_MIN=21
    JDK_MAX=21
else
    JDK_MIN=25
    JDK_MAX=99
fi

if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME が設定されていない。JDK $JDK_MIN を指すように設定する。" >&2
    echo "  例: export JAVA_HOME=/c/Program\\ Files/Eclipse\\ Adoptium/jdk-25.0.3.9-hotspot" >&2
    exit 1
fi

# JDK の release ファイルから版を見る(java を起動しない)。
if [ -f "$JAVA_HOME/release" ]; then
    _jver=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$JAVA_HOME/release")

    if [ -n "$_jver" ] && { [ "$_jver" -lt "$JDK_MIN" ] || [ "$_jver" -gt "$JDK_MAX" ]; }; then
        echo "JAVA_HOME が JDK $_jver を指している。$JDK_MIN 以上 $JDK_MAX 以下が要る: $JAVA_HOME" >&2
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

if [ "$LAYOUT" = classic ]; then
    # classic は vanilla のソースを配らない。decompileJar.jar から取り出して置く。
    TREE=${SHIFU_TREE:-$PW/vanilla-src/minecraft/java}
    # ファイルごとの差分は tools/make_classic_sources.py で作る。
    SOURCES=${SHIFU_SOURCES:-$PW/sources}
    # アダプタ層。classic は NMS と同じソースセットに入っている。
    ADAPTER=$PW/Paper-Server/src/main/java
    RESOURCES=
else
    TREE=${SHIFU_TREE:-$PW/paper-server/src/minecraft/java}
    SOURCES=${SHIFU_SOURCES:-$PW/paper-server/patches/sources}
    ADAPTER=$PW/paper-server/src/main/java
    RESOURCES=${SHIFU_RESOURCES:-$PW/paper-server/src/minecraft/resources}
fi

export SHIFU_TREE=$TREE
export SHIFU_SOURCES=$SOURCES

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

# classic の Paper-Server の基点。CraftBukkit と Spigot までが当たった状態。
# Paper のパッチは tools/apply_classic_by_file.py が当てて「paper Patched」に積む。
if [ "$LAYOUT" = classic ] && [ -z "${SHIFU_BASE_PAPER:-}" ] && [ -d "$PW/Paper-Server" ]; then
    SHIFU_BASE_PAPER=$(git -C "$PW/Paper-Server" log --format='%H %s' 2>/dev/null | awk '$0 ~ / Initial$/ { print $1; exit }' || true)
fi

export SHIFU_BASE_PAPER

# Paper 側を素の状態に戻す。前の回の書き換えが残っていると当て直せない。
shifu_reset_paper() {
    if [ "$LAYOUT" = classic ]; then
        # 前の回に shifu_stage_tree が写した vanilla が残る。Paper が持たない
        # 2974 件は checkout では戻らないので、消してから戻す。
        # 残すと make_classic_shim が Shifu 自身の出力を「Paper の宣言」と読む。
        git -C "$PW/Paper-Server" clean -qfd -- src/main/java
        git -C "$PW/Paper-Server" checkout -q -- src/main/java
        # Paper は岩盤生成を paper:optionally_flat_bedrock_condition_source に差し替える。
        # vanilla の挙動が変わるので、当たる前の中身に戻す。
        git -C "$PW/Paper-Server" checkout -q "$SHIFU_BASE_PAPER" -- \
            src/main/resources/data/minecraft/worldgen
    else
        git -C "$PW" checkout -- paper-server/src/main/java
        # 岩盤生成に加えて、戦利品表から set_damage を 1 件落としている。
        git -C "$RESOURCES" reset --hard "$SHIFU_BASE_RESOURCES" -q
    fi
}

# アダプタ層が要求するメンバーを vanilla に足す。今いる木の中で回す。
#
# mache は Paper のパッチ(patches/sources)の hunk から宣言を取る。
# classic はその差分が無く、作っても CraftBukkit が触る 550 件は
# 逆コンパイラの方言が違って取り出せないので、
# vanilla と Paper のクラスを読んで宣言の並びを突き合わせる。
shifu_make_shim() {
    if [ "$LAYOUT" != classic ]; then
        python "$SHIFU/tools/make_shim.py" "$SOURCES" . "$REQ"

        return
    fi

    rm -rf "$SHIFU/tools/build/classic-shim"
    python "$SHIFU/tools/make_classic_shim.py" "$ADAPTER" .         "$SHIFU/tools/build/classic-shim" "$REQ" --write
    python "$SHIFU/tools/apply_shim_adds.py" "$SHIFU/tools/build/classic-shim" .         "$SHIFU/patches/hand" "$SHIFU/patches/access"
}

# 当て終わった vanilla の木を、コンパイルするソースセットに置く。
# mache は src/minecraft/java が別のソースセットなのでそのままでよい。
# classic はソースセットが 1 つしかないので、写さないと Paper の NMS が使われる。
shifu_stage_tree() {
    [ "$LAYOUT" = classic ] || return 0

    # 写すのは 2 つ。**4029 件を全部ではない。**
    #
    #   1. Paper が持っている NMS のファイル。Paper のパッチが当たった中身なので、
    #      vanilla に戻さないと挙動が変わる
    #   2. Shifu が手を入れたファイル
    #
    # 残りは vanilla の jar から取る(Paper 自身がそうしている)。全部組み直すと
    # 逆コンパイラの出力がそのままでは通らないファイルで止まる(1.20.6 で 173 件)。
    # classic の decompileJar は paperweight 自身がコンパイルしないので、
    # 通る保証が無い。jar から取るほうが Mojang のバイトコードそのものなので、
    # vanilla 一致にも合う。
    (
        git -C "$PW/Paper-Server" ls-files -- src/main/java/net src/main/java/com \
            src/main/java/ca | sed 's|^src/main/java/||'
        git -C "$TREE" status --porcelain | sed 's/^...//'
    ) | sort -u | while read -r _rel; do
        [ -f "$TREE/$_rel" ] || continue
        mkdir -p "$ADAPTER/$(dirname "$_rel")"
        cp "$TREE/$_rel" "$ADAPTER/$_rel"
    done
}

# Minecraft のバージョン。jar の名前と run-shifu/versions/<版>/ に入る。
# Paper のクローンの gradle.properties が持っている。
if [ -z "${MC_VERSION:-}" ] && [ -f "$PW/gradle.properties" ]; then
    MC_VERSION=$(sed -n 's/^mcVersion=//p' "$PW/gradle.properties" | head -1)
fi

# 組んだ bundler の jar。名前に版が入り、1.21.x は mojmap と reobf に分かれる。
# まだ組んでいなければ空。set -e で止まらないように受ける。
BUNDLER_JAR=$(ls "$PW"/paper-server/build/libs/*bundler*.jar 2>/dev/null | grep -v reobf | head -1 || true)

# bundler が起動時に展開するサーバー本体。プラグインを組むときのクラスパス。
SERVER_JAR=$PW/run-shifu/versions/$MC_VERSION/paper-$MC_VERSION.jar
export MC_VERSION SERVER_JAR BUNDLER_JAR

export PYTHONIOENCODING=utf-8
