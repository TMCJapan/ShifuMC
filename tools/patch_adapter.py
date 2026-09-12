"""アダプタ層の行を書き換える。

`org.bukkit.craftbukkit.*` は Paper の NMS を前提に書かれているので、
vanilla に無い形(`nextContainerCounter()` が値を返す、`SLOT_SADDLE` が static)を
使っている箇所がある。NMS 側は追加でしか触らないと決めているので、
その差はアダプタ層の側で吸収する。

**アダプタ層は vanilla ではない。** ここを書き換えても、プラグインが
何もしないときに実行される vanilla の命令列は変わらない。

行番号ではなく、その行そのもの(アンカー)で指定する。
**見つからない、または数が合わないときは失敗させる。**
Minecraft や Paper の更新で行が変わったら、黙って通さずに止める。

規則の書き方 (`patches/adapter/*.rules`):

    # 鞍の位置は vanilla ではインスタンスの定数
    file: org/bukkit/craftbukkit/inventory/CraftInventorySaddledMount.java
    count: 2
    replace:
        AbstractMountInventoryMenu.SLOT_SADDLE
    with:
        net.minecraft.world.inventory.AbstractMountInventoryMenu.SHIFU_SLOT_SADDLE

`replace` と `with` は複数行書ける。`count` を省くと 1 回だけ。
`replace` が 1 行のときは行の一部としても照合する(部分一致)。
複数行のときは行の並びとして照合する。

    python tools/patch_adapter.py <patches/adapter> <src/main/java>
"""

import os
import sys


class Rule:
    """1 つの書き換え。"""

    def __init__(self, target, count, source, number):
        self.target = target
        self.count = count
        self.old = []
        self.new = []
        self.where = f"{source}:{number}"


def parse(text, source="<rules>"):
    """規則の並びを読む。"""
    rules = []
    target = None
    count = 1
    rule = None
    section = None

    for number, raw in enumerate(text.split("\n"), 1):
        stripped = raw.strip()

        if stripped.startswith("file:"):
            target = stripped[len("file:"):].strip()
            section = None
            continue

        if stripped.startswith("count:"):
            count = int(stripped[len("count:"):].strip())
            continue

        if stripped == "replace:":
            if target is None:
                raise SystemExit(f"{source}:{number}: file: が先に要る")

            rule = Rule(target, count, source, number)
            rules.append(rule)
            count = 1
            section = "old"
            continue

        if stripped == "with:":
            if rule is None or not rule.old:
                raise SystemExit(f"{source}:{number}: replace: が先に要る")

            section = "new"
            continue

        if section is None:
            if stripped and not stripped.startswith("#"):
                raise SystemExit(f"{source}:{number}: 読めない行: {stripped}")

            continue

        # 空行は塊の中に入れておく。中身に空行を含む置き換えがある。
        # 塊の終わりは次の file: / count: / replace: / with: で決まる。
        if stripped.startswith("#"):
            continue

        getattr(rule, section).append(raw.rstrip())

    for rule in rules:
        rule.old = trim(rule.old)
        rule.new = trim(rule.new)

        if not rule.old:
            raise SystemExit(f"{rule.where}: replace: の中身が無い")

    return rules


def trim(block):
    """塊の前後の空行を落とす。規則の間の空行が混ざるため。"""
    start = 0
    end = len(block)

    while start < end and not block[start].strip():
        start += 1

    while end > start and not block[end - 1].strip():
        end -= 1

    return block[start:end]


def find(lines, old):
    """一致する位置。1 行なら部分一致、複数行なら行の並びで照合する。"""
    if len(old) == 1:
        needle = old[0].strip()

        return [number for number, line in enumerate(lines) if needle in line]

    stripped = [text.strip() for text in old]
    hits = []

    for number in range(len(lines) - len(stripped) + 1):
        window = [line.strip() for line in lines[number:number + len(stripped)]]

        if window == stripped:
            hits.append(number)

    return hits


def apply(lines, rule):
    """その規則を当てる。数が合わなければ失敗。"""
    hits = find(lines, rule.old)

    if len(hits) != rule.count:
        raise SystemExit(
            f"{rule.where}: {rule.target} で {len(hits)} 件見つかった"
            f"({rule.count} 件のはず): {rule.old[0].strip()}")

    # 1 行を 1 行(以下)にするときだけ、行の一部の置き換えにする。
    # 1 行を複数行にするときは、下の「行ごと差し替え」に回す
    if len(rule.old) == 1 and len(rule.new) <= 1:
        needle = rule.old[0].strip()
        replacement = rule.new[0].strip() if rule.new else ""

        for number in hits:
            lines[number] = lines[number].replace(needle, replacement)

        return lines

    # 行ごと差し替える。後ろからやる(前を先に触ると位置がずれる)。
    # 規則の中での相対的な字下げは保つ。本体を入れ替えるときに要る。
    base = min((len(text) - len(text.lstrip())) for text in rule.new) if rule.new else 0

    for number in sorted(hits, reverse=True):
        indent = lines[number][:len(lines[number]) - len(lines[number].lstrip())]
        body = [indent + text[base:] if text.strip() else "" for text in rule.new]
        lines[number:number + len(rule.old)] = body

    return lines


def main():
    rule_root, tree = sys.argv[1:3]
    # 版を移した直後は当たらない規則が並ぶ。止めずに全部を並べる
    report = "--report" in sys.argv
    missed = []
    rules = []

    if os.path.isdir(rule_root):
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
            if not report:
                raise SystemExit(f"{group[0].where}: 元のファイルが無い: {target}")

            missed.append(f"{group[0].where}: 元のファイルが無い: {target}")
            continue

        with open(path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")

        for rule in group:
            if not report:
                lines = apply(lines, rule)
                continue

            try:
                lines = apply(lines, rule)
            except SystemExit as stop:
                missed.append(str(stop))

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))

    print(f"直したアダプタ層: {len(rules) - len(missed)} 件 / {len(by_file)} ファイル")

    if missed:
        print(f"当たらなかった規則: {len(missed)} 件", file=sys.stderr)

        for line in missed:
            print(f"  {line}", file=sys.stderr)


if __name__ == "__main__":
    main()
