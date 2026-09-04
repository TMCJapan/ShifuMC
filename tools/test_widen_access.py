"""widen_access.py の確認。

見つからない・複数ある・修飾子が違う、のどれでも止まること。
黙って読み飛ばすと、可視性を広げたつもりで広がっていない状態になる。
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("wa", os.path.join(HERE, "widen_access.py"))
wa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wa)

FAILURES = []


def check(name, got, want):
    if got != want:
        FAILURES.append(f"{name}\n      got  {got!r}\n      want {want!r}")


def fails(name, call):
    try:
        call()
    except SystemExit:
        return

    FAILURES.append(f"{name}\n      止まらなかった")


SOURCE = [
    "public class Listener {",
    "    protected final Connection connection;",
    "    private int count;",
    "",
    "    public void tick() {",
    "        int count = 0;",
    "    }",
    "}",
]


def rule(target, line):
    return wa.Rule(target, line, "<test>", 1)


def test_widen():
    lines = list(SOURCE)
    wa.widen(lines, rule("Listener.java", "    protected final Connection connection;"))
    check("widen: protected を public に",
          lines[1], "    public final Connection connection;")

    lines = list(SOURCE)
    wa.widen(lines, rule("Listener.java", "    private int count;"))
    check("widen: private を public に", lines[2], "    public int count;")

    # 修飾子 1 語だけ。型も名前も本体も変えない
    lines = list(SOURCE)
    wa.widen(lines, rule("Listener.java", "    private int count;"))
    check("widen: 他の行は変わらない", lines[:2] + lines[3:], SOURCE[:2] + SOURCE[3:])


def test_refuses():
    fails("widen: 見つからないときは止まる",
          lambda: wa.widen(list(SOURCE), rule("Listener.java", "    private int missing;")))

    twice = SOURCE + ["    private int count;"]
    fails("widen: 2 つあるときは止まる",
          lambda: wa.widen(twice, rule("Listener.java", "    private int count;")))

    fails("widen: public な行は止まる",
          lambda: wa.widen(list(SOURCE), rule("Listener.java", "public class Listener {")))


def test_implements():
    lines = list(SOURCE)
    r = wa.Rule("Listener.java", "public class Listener {", "<test>", 1, "a.b.Marker")
    wa.widen(lines, r)
    check("implements: implements が無ければ足す", lines[0], "public class Listener implements a.b.Marker {")

    lines = ["public class A implements B, C<D> {", "}"]
    wa.widen(lines, wa.Rule("A.java", "public class A implements B, C<D> {", "<test>", 1, "E"))
    check("implements: あれば末尾に , で足す", lines[0], "public class A implements B, C<D>, E {")

    fails("implements: 宣言の行でなければ止まる",
          lambda: wa.widen(list(SOURCE), wa.Rule("Listener.java", "    public void tick() {", "<test>", 1, "E")))

    rules = wa.parse("\n".join([
        "file: a/B.java",
        "line:",
        "    public class B {",
        "implements:",
        "    c.D",
        "line:",
        "    private int x;",
    ]))
    check("parse: implements を読む", rules[0].interface, "c.D")
    check("parse: 次の規則には引き継がない", rules[1].interface, None)
    fails("parse: implements: が line: より先だと止まる",
          lambda: wa.parse("file: a/B.java\nimplements:\n    c.D"))


def test_parse():
    rules = wa.parse("\n".join([
        "# 説明",
        "file: a/B.java",
        "line:",
        "    private int x;",
        "line:",
        "    protected int y;",
        "",
        "file: a/C.java",
        "line:",
        "    private int z;",
    ]))
    check("parse: 件数", len(rules), 3)
    check("parse: 2 つめも同じファイル", rules[1].target, "a/B.java")
    check("parse: 字下げごと読む", rules[1].line, "    protected int y;")
    check("parse: ファイルが切り替わる", rules[2].target, "a/C.java")

    fails("parse: file: が無いと止まる",
          lambda: wa.parse("line:\n    private int x;"))
    fails("parse: line: の中身が無いと止まる",
          lambda: wa.parse("file: a/B.java\nline:"))


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
