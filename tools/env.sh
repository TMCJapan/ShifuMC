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

# 呼び出し元の位置からリポジトリのルートを探す。tools/ の直下でも
# tools/<道具>/ の下でも同じように効くように、目印が見つかるまで上へ辿る。
SHIFU=$(cd "$(dirname "$0")" && pwd)

while [ "$SHIFU" != "/" ] && { [ ! -f "$SHIFU/settings.gradle.kts" ] || [ ! -d "$SHIFU/patches" ]; }; do
    SHIFU=$(dirname "$SHIFU")
done

# $0 は呼び出し側のスクリプトなので、tools/*.sh 以外から読むと別の場所を指す。
# 黙って進むと Paper のクローンを別の場所に作りにいくので、ここで止める。
if [ ! -f "$SHIFU/settings.gradle.kts" ] || [ ! -d "$SHIFU/patches" ]; then
    echo "リポジトリのルートを決められなかった: $(dirname "$0")" >&2
    echo "  tools の中のスクリプトとして呼ぶ(sh tools/<名前>.sh、sh tools/<道具>/build.sh)。" >&2
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
        # Paper が同梱している Alternate Current(別のレッドストーン実装)を外す。
        # vanilla の挙動を変えるものなので Shifu は使わず、NMS からの参照も無い。
        # 残すと、同じパッケージ名の Fabric MOD(alternate-current)がサーバー側の
        # クラスに隠され、WireHandler の構築子が見つからずに世界の読み込みで落ちる。
        rm -rf "$PW/Paper-Server/src/main/java/alternate"
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

# Fabric Loader の版。MOD が要求する下限が版で違う
# (1.20.6 の fabric-language-kotlin 1.14.1 は 0.19.5 以上)。
FABRIC_LOADER=${SHIFU_FABRIC_LOADER:-0.19.5}
export FABRIC_LOADER

# Minecraft のバージョン。jar の名前と run-shifu/versions/<版>/ に入る。
# Paper のクローンの gradle.properties が持っている。
if [ -z "${MC_VERSION:-}" ] && [ -f "$PW/gradle.properties" ]; then
    MC_VERSION=$(sed -n 's/^mcVersion=//p' "$PW/gradle.properties" | head -1)
fi

# 組んだ bundler の jar。名前に版が入り、1.21.x は mojmap と reobf に分かれる。
# classic はタスクが根のプロジェクトにあるので出力先も根の build/libs。
# まだ組んでいなければ空。set -e で止まらないように受ける。
if [ "$LAYOUT" = classic ]; then
    BUNDLER_JAR=$(ls "$PW"/build/libs/*bundler*.jar 2>/dev/null | grep -v reobf | head -1 || true)
else
    BUNDLER_JAR=$(ls "$PW"/paper-server/build/libs/*bundler*.jar 2>/dev/null | grep -v reobf | head -1 || true)
fi

# vanilla 一致の比較で基準に置く、Mojang の公式サーバー jar。
# mache は bundler の展開先、classic は paperweight がダウンロードしたもの。
if [ "$LAYOUT" = classic ]; then
    VANILLA_JAR=$PW/.gradle/caches/paperweight/taskCache/downloadServerJar.jar
else
    VANILLA_JAR=$PW/paper-server/.gradle/caches/paperweight/data/bundler/server.jar
fi

export VANILLA_JAR

# vanilla 一致の比較に使う 2 つの置き場を用意する。
# **同じ設定でなければ比べる意味が無い**ので、走らせるたびに両方へ同じものを書く。
shifu_parity_dirs() {
    _port=25595

    for _dir in "$PW/run-vanilla" "$PW/run-parity"; do
        mkdir -p "$_dir"
        printf 'eula=true\n' > "$_dir/eula.txt"
        printf 'online-mode=false\nserver-port=%s\nlevel-seed=1234567890\nsync-chunk-writes=true\n' \
            "$_port" > "$_dir/server.properties"
        _port=$((_port + 1))
    done

    unset _dir _port

    # Shifu 側だけ、起動側(dev.shifu.launcher.VanillaParity)が書くのと同じ設定を置く。
    # **これが無いと Paper の既定値で走る。**entity-activation-range と
    # per-player-mob-spawns は動物の tick と湧きを変えるので、羊が草を食べるかどうかが
    # 変わり、ブロックの差として出る(1.20.6 で 2 チャンクが実差に見えた原因)。
    # 中身を変えるときは VanillaParity.java と docs/VANILLA-PARITY.md も合わせる。
    mkdir -p "$PW/run-parity/config"

    cat > "$PW/run-parity/spigot.yml" <<'SPIGOT'
# vanilla 一致の比較用。起動側が書くものと同じ内容。
world-settings:
  default:
    entity-activation-range:
      animals: 512
      monsters: 512
      raiders: 512
      misc: 512
      water: 512
      villagers: 512
      flying-monsters: 512
    max-tnt-per-tick: 0
SPIGOT

    cat > "$PW/run-parity/config/paper-world-defaults.yml" <<'PAPER'
# vanilla 一致の比較用。起動側が書くものと同じ内容。
entities:
  spawning:
    per-player-mob-spawns: false
PAPER

    # 難読化されている版では、公式の jar をそのまま走らせると
    # tools/tickstop の agent が MinecraftServer.tickServer を見つけられない。
    # paperweight が mojmap へ写した公式 jar(バイトコードは公式のまま)を
    # vanilla の libraries と組んで走らせる。cp.txt があればそちらが使われる。
    _moj=$PW/.gradle/caches/paperweight/taskCache/minecraft.jar

    if [ "$LAYOUT" = classic ] && [ -f "$_moj" ] && [ -d "$PW/run-vanilla/libraries" ] \
            && [ ! -f "$PW/run-vanilla/cp.txt" ]; then
        {
            printf -- '-cp "%s' "$(cygpath -m "$_moj")"
            find "$PW/run-vanilla/libraries" -name "*.jar" | sort | while read -r _jar; do
                printf ';%s' "$(cygpath -m "$_jar")"
            done
            printf '"\n'
        } > "$PW/run-vanilla/cp.txt"
        echo "[shifu] run-vanilla/cp.txt を作った(mojmap の公式 jar で vanilla を走らせる)"
    fi

    unset _moj
}

# bundler が起動時に展開するサーバー本体。プラグインを組むときのクラスパス。
SERVER_JAR=$PW/run-shifu/versions/$MC_VERSION/paper-$MC_VERSION.jar
export MC_VERSION SERVER_JAR BUNDLER_JAR

export PYTHONIOENCODING=utf-8
