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
"""
import collections
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_lvt import method_locals  # noqa: E402
from keep_vanilla_classes import class_prefixes, mojang_classes, owner_of, touched_sources  # noqa: E402


def overlaps(one, other):
    return one[3] < other[3] + other[4] and other[3] < one[3] + one[4]


def main():
    classes = sys.argv[1]
    official = mojang_classes(sys.argv[2])
    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    rows = []
    types = collections.Counter()

    for base, _, files in os.walk(classes):
        for name in files:
            if not name.endswith(".class"):
                continue

            rel = os.path.relpath(os.path.join(base, name), classes).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/") or owner_of(rel) not in keep or rel not in official:
                continue

            mine = method_locals(io.open(os.path.join(base, name), "rb").read())
            theirs = method_locals(official[rel])

            for key, entries in mine.items():
                if key not in theirs or not entries or not theirs[key]:
                    continue

                have = {(n, d) for _, n, d, _, _ in theirs[key]}
                extra = [e for e in entries if (e[1], e[2]) not in have]

                if not extra:
                    continue

                for one in extra:
                    same = [t for t in theirs[key] if t[2] == one[2] and overlaps(one, t)]

                    if same:
                        rows.append((rel[:-len(".class")], key[0], one[1], one[2], [t[1] for t in same]))
                        types[one[2]] += 1

    print("公式と同じ型を足している場所: %d" % len(rows))
    print("型ごと: %s" % ", ".join("%s x%d" % (d.split("/")[-1], n) for d, n in types.most_common(8)))

    if "--list" in sys.argv:
        lines = ["%s %s\t%s %s\t公式: %s" % (rel, method, desc, name, ", ".join(same))
                 for rel, method, name, desc, same in sorted(rows)]
        io.open("tools/build/extra-locals.txt", "w", encoding="utf-8",
                newline=chr(10)).write(chr(10).join(lines) + chr(10))
        print("一覧: tools/build/extra-locals.txt")

    return 1 if rows else 0


if __name__ == "__main__":
    sys.exit(main())
