#!/bin/sh
# 発火層のクラスを、前回組んだサーバー jar とライブラリに対して単体でコンパイルする。
#
#     sh tools/javac-event.sh src/event/java/dev/shifu/event/AnimalEvents.java [...]
#
# once.sh(6 分)を回さずに、型や名前の間違いを 1〜2 秒で見る。
# 見ているのは run-shifu/versions/<版>/paper-<版>.jar(前回の once.sh + run-server.sh の
# 結果)なので、そのあとに patches/hand で足したメンバーはまだ無い。そこだけは
# エラーに出ても仕方がない(報告に残して once.sh で確かめる)。
set -e

. "$(dirname "$0")/env.sh"
RUN=$PW/run-shifu
OUT=$SHIFU/tools/build/javac-event
CP=$SHIFU/tools/build/cp-event.txt

mkdir -p "$OUT"

if [ ! -f "$CP" ] || [ "$SERVER_JAR" -nt "$CP" ]; then
    python - "$CP" "$(cygpath -m "$RUN")" <<'PYEOF'
import glob, io, os, sys
run = sys.argv[2]
jars = [os.environ["SERVER_JAR"]] + glob.glob(run + "/libraries/**/*.jar", recursive=True)
ann = [a for a in glob.glob(os.path.expanduser("~/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/*/*/annotations-*.jar")) if "sources" not in a]
jars += ann[-1:]
io.open(sys.argv[1], "w", encoding="utf-8").write(";".join(j.replace("/", "\\") for j in jars))
PYEOF
fi

"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -Xmaxerrs 200 \
    -cp "@$(cygpath -w "$CP")" -d "$(cygpath -w "$OUT")" \
    "$SHIFU/src/event/java/dev/shifu/event/ShifuEvents.java" \
    "$SHIFU/src/event/java/dev/shifu/event/InventoryClicks.java" \
    "$SHIFU/src/event/java/dev/shifu/event/DeathInventory.java" \
    "$SHIFU/src/event/java/dev/shifu/event/ShifuBootstrap.java" \
    "$@"
echo "javac: OK"
