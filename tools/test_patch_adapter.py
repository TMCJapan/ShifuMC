"""patch_adapter.py の確認。

数が合わないときに止まること。黙って読み飛ばすと、直したつもりで
直っていない状態のままコンパイルが通ってしまう。
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("pa", os.path.join(HERE, "patch_adapter.py"))
pa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pa)

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
    "public class CraftInventorySaddledMount {",
    "    public ItemStack getSaddle() {",
    "        return this.getItem(AbstractMountInventoryMenu.SLOT_SADDLE);",
    "    }",
    "",
    "    public void setSaddle(ItemStack stack) {",
    "        this.setItem(AbstractMountInventoryMenu.SLOT_SADDLE, stack);",
    "    }",
    "}",
]


def rule(old, new, count=1):
    made = pa.Rule("X.java", count, "<test>", 1)
    made.old = old
    made.new = new
    return made


def test_single_line():
    lines = list(SOURCE)
    pa.apply(lines, rule(["AbstractMountInventoryMenu.SLOT_SADDLE"],
                         ["Shifu.SLOT_SADDLE"], count=2))
    check("apply: 部分一致で 2 箇所",
          lines[2], "        return this.getItem(Shifu.SLOT_SADDLE);")
    check("apply: 2 箇所目も直る",
          lines[6], "        this.setItem(Shifu.SLOT_SADDLE, stack);")
    check("apply: 他の行は変わらない", lines[0], SOURCE[0])


def test_multi_line():
    lines = list(SOURCE)
    pa.apply(lines, rule(["public ItemStack getSaddle() {",
                          "return this.getItem(AbstractMountInventoryMenu.SLOT_SADDLE);"],
                         ["    public ItemStack getSaddle() {",
                          "        return null;"]))
    check("apply: 字下げはアンカーに合わせる", lines[1], "    public ItemStack getSaddle() {")
    check("apply: 規則の中の相対的な字下げは保つ", lines[2], "        return null;")
    check("apply: 後ろはずれない", lines[3], "    }")


def test_refuses():
    fails("apply: 見つからないときは止まる",
          lambda: pa.apply(list(SOURCE), rule(["NOT_THERE"], ["x"])))
    fails("apply: 数が合わないときは止まる",
          lambda: pa.apply(list(SOURCE), rule(["AbstractMountInventoryMenu.SLOT_SADDLE"], ["x"])))
    fails("apply: 多すぎるときも止まる",
          lambda: pa.apply(list(SOURCE), rule(["AbstractMountInventoryMenu.SLOT_SADDLE"],
                                              ["x"], count=3)))


def test_parse():
    rules = pa.parse("\n".join([
        "# 説明",
        "file: a/B.java",
        "count: 2",
        "replace:",
        "    foo",
        "with:",
        "    bar",
        "",
        "replace:",
        "    baz",
        "with:",
        "    qux",
    ]))
    check("parse: 件数", len(rules), 2)
    check("parse: count は次の replace には残らない",
          [r.count for r in rules], [2, 1])
    check("parse: 中身", (rules[0].old, rules[0].new), (["    foo"], ["    bar"]))
    check("parse: 2 つめも同じファイル", rules[1].target, "a/B.java")

    # 置き換えの中身に空行が入っていても塊は切れない
    blanks = pa.parse("\n".join([
        "file: a/B.java",
        "replace:",
        "    foo() {",
        "",
        "    }",
        "with:",
        "    bar() {",
        "",
        "    }",
        "",
        "# 次の規則",
        "replace:",
        "    baz",
        "with:",
        "    qux",
    ]))
    check("parse: 中身の空行では切れない", len(blanks), 2)
    check("parse: 空行は塊の中に残る", blanks[0].old, ["    foo() {", "", "    }"])
    check("parse: 前後の空行は落とす", blanks[0].new, ["    bar() {", "", "    }"])
    check("parse: 次の規則も読める", blanks[1].old, ["    baz"])

    fails("parse: file: が無いと止まる",
          lambda: pa.parse("replace:\n    foo\nwith:\n    bar"))
    fails("parse: replace: の中身が無いと止まる",
          lambda: pa.parse("file: a/B.java\nreplace:\n\nwith:\n    bar"))


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
