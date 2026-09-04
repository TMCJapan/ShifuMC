"""vanilla の宣言の可視性だけを広げる。

アダプタ層は `Entity.bukkitEntity` のような状態に直に触るので、
private / protected のままでは届かないものがある。同じ名前で同じ引数の
宣言はもう 1 つ足せないので、追加では閉じない。

**変えるのは修飾子 1 語だけ。** 型も名前も引数も本体も触らない。
可視性はコンパイル時の検査だけなので、実行される命令列は変わらない。

行番号ではなく、その行そのもの(アンカー)で指定する。
**アンカーが見つからない、または 2 つ以上あるときは失敗させる。**
Minecraft の更新で宣言が変わったら、黙って通さずに止める。

規則の書き方 (`patches/access/*.rules`):

    # CraftPlayer が接続に直に触る
    file: net/minecraft/server/network/ServerCommonPacketListenerImpl.java
    line:
        protected final Connection connection;

    python tools/widen_access.py <patches/access> <src/minecraft/java>

型の宣言に interface を 1 つ足す規則もここに置く(`implements:`)。
これも可視性と同じくコンパイル時の性質だけで、vanilla のメソッドの命令列は変わらない。
足す interface の実装は `patches/hand` に書く。

    # Paper の brigadier API は NMS の CommandSourceStack を API の型として扱う
    file: net/minecraft/commands/CommandSourceStack.java
    line:
        public class CommandSourceStack implements SharedSuggestionProvider, ExecutionCommandSource<CommandSourceStack> {
    implements:
        io.papermc.paper.command.brigadier.PaperCommandSourceStack
"""

import os
import re
import sys

NARROW = re.compile(r"^(\s*)(private|protected)(\s)")


class Rule:
    """1 つの宣言。"""

    def __init__(self, target, line, source, number, interface=None):
        self.target = target
        self.line = line
        self.where = f"{source}:{number}"
        # 入っていれば「可視性を広げる」ではなく「interface を 1 つ足す」
        self.interface = interface


def parse(text, source="<rules>"):
    """規則の並びを読む。"""
    rules = []
    target = None
    pending = False
    pending_interface = False

    for number, raw in enumerate(text.split("\n"), 1):
        stripped = raw.strip()

        if pending:
            if not stripped:
                continue

            rules.append(Rule(target, raw.rstrip(), source, number))
            pending = False
            continue

        if pending_interface:
            if not stripped:
                continue

            rules[-1].interface = stripped
            pending_interface = False
            continue

        if not stripped or stripped.startswith("#"):
            continue

        if stripped.startswith("file:"):
            target = stripped[len("file:"):].strip()
            continue

        if stripped == "line:":
            if target is None:
                raise SystemExit(f"{source}:{number}: file: が先に要る")

            pending = True
            continue

        if stripped == "implements:":
            if not rules or rules[-1].interface is not None:
                raise SystemExit(f"{source}:{number}: implements: の前に line: が要る")

            pending_interface = True
            continue

        raise SystemExit(f"{source}:{number}: 読めない行: {stripped}")

    if pending:
        raise SystemExit(f"{source}: line: の中身が無い")

    if pending_interface:
        raise SystemExit(f"{source}: implements: の中身が無い")

    return rules


DECLARATION = re.compile(r"^\s*(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|final\s+|static\s+)*"
                         r"(?:class|record|enum|interface)\s.*\{\s*$")


def implement(lines, rule):
    """型の宣言に interface を 1 つ足す。宣言の行でなければ失敗。"""
    hits = [number for number, line in enumerate(lines) if line.rstrip() == rule.line]

    if len(hits) != 1:
        raise SystemExit(
            f"{rule.where}: {rule.target} で {len(hits)} 件見つかった: {rule.line.strip()}")

    number = hits[0]
    line = lines[number].rstrip()

    if not DECLARATION.match(line):
        raise SystemExit(f"{rule.where}: 型の宣言ではない: {rule.line.strip()}")

    head = line[:-1].rstrip()
    joint = ", " if " implements " in head else " implements "
    lines[number] = head + joint + rule.interface + " {"

    return lines


def widen(lines, rule):
    """その行の可視性を public にする。1 つに定まらなければ失敗。"""
    if rule.interface:
        return implement(lines, rule)

    hits = [number for number, line in enumerate(lines) if line.rstrip() == rule.line]

    if len(hits) != 1:
        raise SystemExit(
            f"{rule.where}: {rule.target} で {len(hits)} 件見つかった: {rule.line.strip()}")

    number = hits[0]
    match = NARROW.match(lines[number])

    if not match:
        raise SystemExit(f"{rule.where}: private / protected で始まっていない: {rule.line.strip()}")

    lines[number] = NARROW.sub(r"\1public\3", lines[number], count=1)

    return lines


def main():
    rule_root, tree = sys.argv[1:3]
    rules = []

    for base, _, names in sorted(os.walk(rule_root)):
        for name in sorted(names):
            if not name.endswith(".rules"):
                continue

            path = os.path.join(base, name)

            with open(path, encoding="utf-8") as handle:
                rules.extend(parse(handle.read(), os.path.basename(path)))

    by_file = {}

    for rule in rules:
        by_file.setdefault(rule.target, []).append(rule)

    for target, group in sorted(by_file.items()):
        path = os.path.join(tree, target.replace("/", os.sep))

        if not os.path.exists(path):
            raise SystemExit(f"{group[0].where}: 元のファイルが無い: {target}")

        with open(path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")

        for rule in group:
            lines = widen(lines, rule)

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))

    print(f"広げた可視性: {len(rules)} 件 / {len(by_file)} ファイル")


if __name__ == "__main__":
    main()
