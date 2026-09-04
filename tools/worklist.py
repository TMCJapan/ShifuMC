"""まだ通っていないところを「何が足りないか」でまとめる。

`by-hand.txt` は Paper のパッチにある名前を並べたものなので、
実際には要らない項目が混ざる(vanilla に既にある `set` や、
上位クラスの追加で足りる `setTarget` など)。
作業の順番はこちらで決める。出ているエラーからしか作らない。

    python tools/worklist.py docs/backlog/vanilla-gap.txt
"""

import collections
import re
import sys

WHERE = re.compile(r"^(minecraft|main).java.(.+?\.java):(\d+): error: (.*)$")
APPLIED = re.compile(r"method (\w+) in (?:class|interface) ([\w.]+) cannot be applied")
ACCESS = re.compile(r"(\w+) has (?:private|protected) access in ([\w.]+)")
SYMBOL = re.compile(r"symbol:\s+(\w+) (\w+)")
LOCATION = re.compile(r"location: .*?([\w.]+)$")


def classify(message, detail):
    """(種別, 所有クラス, 名前)。分からないものは「その他」にまとめる。"""
    hit = APPLIED.search(message)

    if hit:
        return "引数違い", hit.group(2), hit.group(1)

    hit = ACCESS.search(message)

    if hit:
        return "可視性", hit.group(2), hit.group(1)

    if message == "cannot find symbol":
        symbol = next((d for d in detail if d.strip().startswith("symbol:")), None)
        place = next((d for d in detail if d.strip().startswith("location:")), None)

        if symbol:
            name = SYMBOL.search(symbol)
            owner = LOCATION.search(place.strip()) if place else None

            return "未定義", owner.group(1) if owner else "?", name.group(2) if name else "?"

    return "その他", "-", message[:60]


def entries(lines):
    """エラー 1 件を (種別, 所有クラス, 名前, 出た場所) で返す。"""
    index = 0

    while index < len(lines):
        head = WHERE.match(lines[index])

        if not head:
            index += 1
            continue

        detail = []
        end = index + 1

        while end < len(lines) and not WHERE.match(lines[end]):
            detail.append(lines[end])
            end += 1

        yield classify(head.group(4), detail) + (head.group(2),)
        index = end


def main():
    lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
    groups = collections.defaultdict(list)

    for kind, owner, name, where in entries(lines):
        groups[(kind, owner, name)].append(where)

    total = sum(len(v) for v in groups.values())
    print(f"エラー {total} 件 / まとまり {len(groups)}")
    print()

    for key, wheres in sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        kind, owner, name = key
        files = sorted({w.split("\\")[-1] for w in wheres})
        print(f"{kind:6} {owner:34} {name:32} {len(wheres):3} 件  [{', '.join(files[:4])}]")


if __name__ == "__main__":
    main()
