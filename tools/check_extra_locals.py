# -*- coding: utf-8 -*-
"""差し込みが足した局所変数のうち、公式にもある型と重なるものを出す。

    python tools/check_extra_locals.py <コンパイル済みクラスの置き場> <Mojang の jar> [--list]

MixinExtras の `@Local`(型だけで指す書き方)は、**その型の候補がちょうど 1 つ**でないと
当たらない。差し込みが公式と同じ型の変数を足すと候補が増えて壊れる。

    Found 3 candidate variables but exactly 1 is required.

carpet-tis-addition が `ItemEntity.playerTouch` の `@Local int` でこれを踏んだ。
公式は int が `orgCount` の 1 つだけ。Shifu は Bukkit のイベントのために
`canHold` と `remaining` を足していて 3 つになっていた。

**差し込みは局所変数を作らない。** 要る値は発火層のメソッドの中で計算する。
この道具は、その決まりが守れていない場所を出す。

## 数え方

メソッドごと・型ごとに、公式と次の 2 つを比べ、**両方とも公式より多い**ものを出す。名前は見ない。

* 同時に生きている変数の数(LVT の範囲が重なる数の最大)
* LVT の項目の数

名前で比べていたころは、LvtMatch が公式の名前に直しきれない変数(逆コンパイラの名前)まで
「足した変数」に数え、1.19.4 で 6,290 行出ていた。範囲の重なりだけで比べると、javac と
ProGuard の LVT の範囲の違いで `ServerPlayerGameMode.destroyBlock` の BlockState が
2 つに見えた(変数の数は公式と同じ)。

## 止める

`tools/extra-locals-allowed.txt` に載っていない場所が 1 つでもあれば終了コード 1。
postcompile.sh はそこで止まる。許す場所には、差し込みで直せない理由をその行に書く。
載っているのに出なくなった場所は、消してよいものとして名前だけ出す(止めない)。
"""
import collections
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_lvt import method_locals  # noqa: E402
from keep_vanilla_classes import class_prefixes, mojang_classes, owner_of, touched_sources  # noqa: E402

ALLOWED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extra-locals-allowed.txt")


def most_alive(entries):
    """LVT の項目の並びから、同時に生きている数の最大。範囲の終わりと始まりが接するだけなら重ねない。"""
    edges = []

    for _, _, _, start, length in entries:
        edges.append((start, 1))
        edges.append((start + length, -1))

    alive = most = 0

    for _, step in sorted(edges):
        alive += step
        most = max(most, alive)

    return most


def extra_rows(rel, mine, theirs):
    """1 クラス分。公式より多い (クラス, メソッド, 型, 足した名前, 公式の名前, 数) を返す。"""
    rows = []

    for key, entries in mine.items():
        if key not in theirs or not entries or not theirs[key]:
            continue

        by_type = collections.defaultdict(list)
        their_type = collections.defaultdict(list)

        for entry in entries:
            by_type[entry[2]].append(entry)

        for entry in theirs[key]:
            their_type[entry[2]].append(entry)

        for desc, ours in by_type.items():
            official = their_type.get(desc)

            if not official:
                continue

            alive, their_alive = most_alive(ours), most_alive(official)

            if alive <= their_alive or len(ours) <= len(official):
                continue

            names = sorted({e[1] for e in ours} - {e[1] for e in official})
            rows.append((rel, key[0], desc, names, sorted({e[1] for e in official}), alive, their_alive))

    return rows


def read_allowed():
    """許す場所の一覧。1 行 1 か所で `クラス メソッド<TAB>型`。`#` から後ろは理由。"""
    allowed = set()

    if not os.path.isfile(ALLOWED):
        return allowed

    for line in io.open(ALLOWED, encoding="utf-8"):
        body = line.split("#", 1)[0].strip()

        if body:
            where, desc = body.split("\t")[:2]
            owner, method = where.split(" ")
            allowed.add((owner, method, desc.strip()))

    return allowed


def main():
    classes = sys.argv[1]
    official = mojang_classes(sys.argv[2])
    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    rows = []

    for base, _, files in os.walk(classes):
        for name in files:
            if not name.endswith(".class"):
                continue

            rel = os.path.relpath(os.path.join(base, name), classes).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/") or owner_of(rel) not in keep or rel not in official:
                continue

            data = io.open(os.path.join(base, name), "rb").read()

            if data == official[rel]:
                continue

            rows.extend(extra_rows(rel[:-len(".class")], method_locals(data), method_locals(official[rel])))

    rows.sort()
    allowed = read_allowed()
    found = {(rel, method, desc) for rel, method, desc, _, _, _, _ in rows}
    new = [row for row in rows if (row[0], row[1], row[2]) not in allowed]
    types = collections.Counter(desc for _, _, desc, _, _, _, _ in rows)

    print("公式と同じ型を足している場所: %d(許している %d、それ以外 %d)" % (len(rows), len(rows) - len(new), len(new)))
    print("型ごと: %s" % ", ".join("%s x%d" % (d.split("/")[-1], n) for d, n in types.most_common(8)))

    for rel, method, desc, names, theirs, alive, their_alive in new:
        print("  %s %s\t%s\t同時 %d > 公式 %d\t足した名前: %s\t公式: %s"
              % (rel, method, desc, alive, their_alive, ", ".join(names) or "-", ", ".join(theirs)))

    for rel, method, desc in sorted(allowed - found):
        print("  (許しているが出なくなった: %s %s\t%s)" % (rel, method, desc))

    if "--list" in sys.argv:
        lines = ["%s %s\t%s\t同時 %d > 公式 %d\t足した名前: %s\t公式: %s"
                 % (rel, method, desc, alive, their_alive, ", ".join(names) or "-", ", ".join(theirs))
                 for rel, method, desc, names, theirs, alive, their_alive in rows]
        io.open("tools/build/extra-locals.txt", "w", encoding="utf-8",
                newline=chr(10)).write(chr(10).join(lines) + chr(10))
        print("一覧: tools/build/extra-locals.txt")

    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
