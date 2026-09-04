"""apply_events の判定を確かめる。

    python tools/test_apply_events.py
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ae", os.path.join(HERE, "apply_events.py"))
ae = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ae)

FAILURES = []


def check(label, got, want):
    if got != want:
        FAILURES.append(f"{label}\n      got  {got!r}\n      want {want!r}")


SOURCE = [
    "public class Sample {",
    "    public boolean destroyBlock(final BlockPos pos) {",
    "        boolean changed = this.level.removeBlock(pos, false);",
    "        this.server",
    "            .getPlayerList()",
    "            .broadcastSystemMessage(message, false);",
    "        return changed;",
    "    }",
    "}",
]


def test_parse():
    rules = ae.parse("""
# ブロックの破壊
file: net/minecraft/server/level/ServerPlayerGameMode.java
anchor:
    boolean changed = this.level.removeBlock(pos, false);
insert:
    if (!dev.shifu.event.ShifuEvents.blockBreak(this.player, pos)) {
        return false;
    }
""")
    check("parse: 1 件", len(rules), 1)
    check("parse: 対象", rules[0].target,
          "net/minecraft/server/level/ServerPlayerGameMode.java")
    check("parse: アンカー", rules[0].anchor,
          ["boolean changed = this.level.removeBlock(pos, false);"])
    check("parse: 前に入れる", rules[0].before, [
        "if (!dev.shifu.event.ShifuEvents.blockBreak(this.player, pos)) {",
        "    return false;",
        "}",
    ])
    check("parse: 後ろは空", rules[0].after, [])


BREAK = """
file: x.java
anchor:
    boolean changed = this.level.removeBlock(pos, false);
insert:
    if (!ShifuEvents.blockBreak(this.player, pos)) {
        return false;
    }
"""


def test_insert_before():
    check("apply: アンカーの字下げに合わせる", ae.apply(SOURCE, ae.parse(BREAK))[:6], [
        "public class Sample {",
        "    public boolean destroyBlock(final BlockPos pos) {",
        "        // Shifu - イベント発火",
        "        if (!ShifuEvents.blockBreak(this.player, pos)) {",
        "            return false;",
        "        }",
    ])


WRAP = """
file: x.java
anchor:
    this.server
        .getPlayerList()
        .broadcastSystemMessage(message, false);
insert:
    if (ShifuEvents.playerQuit(this.player)) {
insert-after:
    }
"""


def test_wrap_multiline():
    """複数行の文を囲む。vanilla の行は 1 文字も変えない。"""
    result = ae.apply(SOURCE, ae.parse(WRAP))
    check("apply: 複数行の文を囲める", result[3:10], [
        "        // Shifu - イベント発火",
        "        if (ShifuEvents.playerQuit(this.player)) {",
        "        this.server",
        "            .getPlayerList()",
        "            .broadcastSystemMessage(message, false);",
        "        }",
        "        return changed;",
    ])


def test_missing_anchor():
    rules = ae.parse(BREAK.replace("pos, false", "pos, true"))

    try:
        ae.apply(SOURCE, rules)
        check("apply: 見つからなければ失敗する", "通ってしまった", "LookupError")
    except LookupError as problem:
        check("apply: 見つからなければ失敗する", str(problem), "見つからない")


def test_count():
    twice = SOURCE + ["    public void again() {", "        boolean changed = this.level.removeBlock(pos, false);", "    }"]
    rules = ae.parse("""
file: Sample.java
anchor:
    boolean changed = this.level.removeBlock(pos, false);
count: 2
insert:
    hook();
""")
    check("count: 読める", rules[0].count, 2)
    out = ae.apply(twice, rules)
    check("count: 両方に入る", sum(1 for l in out if l.strip() == "hook();"), 2)

    bad = ae.parse("""
file: Sample.java
anchor:
    boolean changed = this.level.removeBlock(pos, false);
count: 3
insert:
    hook();
""")
    try:
        ae.apply(twice, bad)
        FAILURES.append("count: 数が合わないのに止まらなかった")
    except LookupError:
        pass


def test_ambiguous_anchor():
    doubled = SOURCE[:3] + [SOURCE[2]] + SOURCE[3:]

    try:
        ae.apply(doubled, ae.parse(BREAK))
        check("apply: 複数あれば失敗する", "通ってしまった", "LookupError")
    except LookupError as problem:
        check("apply: 複数あれば失敗する", str(problem), "2 箇所にある")


def main():
    for name, test in sorted(globals().items()):
        if name.startswith("test_"):
            test()

    if not FAILURES:
        print("すべて通った")
        return 0

    print(f"{len(FAILURES)} 件が通らない\n")

    for failure in FAILURES:
        print(f"  {failure}\n")

    return 1


if __name__ == "__main__":
    sys.exit(main())
