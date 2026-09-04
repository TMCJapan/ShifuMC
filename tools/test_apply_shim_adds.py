"""apply_shim_adds.py の確認。

重複判定と、譲る先(`patches/hand` / `patches/access`)の扱い。
ここを間違えると、足したつもりの塊が黙って入らない。
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("asa", os.path.join(HERE, "apply_shim_adds.py"))
asa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asa)

FAILURES = []


def check(name, got, want):
    if got != want:
        FAILURES.append(f"{name}\n      got  {got!r}\n      want {want!r}")


def head_of(block):
    """main が重複判定に使う「宣言の行」。"""
    return next((text.strip() for text in block
                 if text.strip()
                 and not text.strip().startswith("//")
                 and not text.strip().startswith("@")), None)


def test_parse():
    groups = asa.parse("\n".join([
        "// 見出しのコメント",
        "",
        "// Menu.getBukkitView",
        "    @Override",
        "    public View getBukkitView() {",
        "        return null;",
        "    }",
        "",
        "// Menu.other",
        "    public int other() {",
        "        return 0;",
        "    }",
    ]))
    check("parse: 2 つの塊", sorted(groups), [("Menu", "getBukkitView"), ("Menu", "other")])
    check("parse: 印は塊に入らない",
          groups[("Menu", "other")][:2], ["", "    // Shifu - other"])


def test_head_skips_annotation():
    # `@Override` を宣言とみなすと、どのファイルにもあるので塊が 1 つも入らない
    block = ["", "    // Shifu - getBukkitView", "    // 説明", "    @Override",
             "    public View getBukkitView() {", "    }"]
    check("重複判定: 注釈は宣言ではない",
          head_of(block), "public View getBukkitView() {")

    check("重複判定: コメントも宣言ではない",
          head_of(["    // 説明", "    public int x;"]), "public int x;")


def test_head_matches_unfinal():
    # make_shim は初期化子の無い final から final を外して入れる。
    # 元の形のままで照合すると一致せず、同じ欄が 2 つ入る。
    head = "private final net.minecraft.server.level.ServerLevel level;"
    check("重複判定: final を外した形でも照合する",
          asa.ms.unfinal([head])[0].strip(),
          "private net.minecraft.server.level.ServerLevel level;")


def test_yield_to():
    root = os.path.join(HERE, "..", "patches", "hand")
    # 実物で確かめる。ServerPlayer には手で書いた sendChatMessage がある
    target = "net/minecraft/server/level/ServerPlayer.java"
    check("譲る先: 手で書いた名前は拾える",
          asa.defined_in(root, target, "sendChatMessage"), True)
    check("譲る先: 書いていない名前は拾わない",
          asa.defined_in(root, target, "notWrittenAnywhere"), False)
    check("譲る先: 渡さなければ何も拾わない",
          asa.defined_in(None, target, "sendChatMessage"), False)


def test_widened():
    root = os.path.join(HERE, "..", "patches", "access")
    found = asa.widened(root)
    check("可視性: CopperGolem.nextWeatheringTick を拾う",
          ("net/minecraft/world/entity/animal/golem/CopperGolem.java",
           "nextWeatheringTick") in found, True)
    check("可視性: メソッドも拾う",
          ("net/minecraft/world/entity/player/Player.java", "isImmobile") in found, True)
    check("可視性: 無い場所では空", asa.widened(None), set())


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
