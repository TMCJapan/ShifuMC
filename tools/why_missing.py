"""まだ足りない要素が、なぜ取り出せていないのかを分類する。

宣言そのものは Paper のパッチに載っていることが多い。載っているのに
取れていないなら理由があるので、それを数える。多い理由から順に直す。

    python tools/why_missing.py <write-ourselves.txt> <patches/sources> <src/minecraft/java>
"""

import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ms", os.path.join(HERE, "make_shim.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)
fs = ms.fs

OWNER = re.compile(r"^(\S+)\s+\((\d+)\)$")
ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")


def wanted(path):
    names = set()

    with open(path, encoding="utf-8") as handle:
        for line in handle:
            entry = ENTRY.match(line.rstrip("\n"))

            if entry:
                names.add(entry.group(2))

    return names


def classify(original, bodies, hunk, name):
    """その hunk でその名前がどう扱われるか。"""
    pos = max(hunk.start - 1, 0)
    depth = ms.depth_at(original, pos)
    seq = [(text.startswith("+"), text[1:])
           for text in hunk.lines if text[:1] in (" ", "+")]
    index = 0

    while index < len(seq):
        is_added, text = seq[index]

        if not is_added or ms.declaration(text, {name}) is None:
            depth += fs.structure_delta([text])[0]

            if not is_added:
                pos += 1

            index += 1
            continue

        if not any(b[0] <= pos <= b[1] and b[2] == depth for b in bodies):
            return "型の直下でない(深さが合わない)"

        block = ms.capture([t for _, t in seq[index:]], 0)
        span = seq[index:index + len(block)]

        if not all(added for added, _ in span):
            return "文脈行が混ざる(元の行を巻き込む)"

        if not ms.sound(block):
            return "塊が閉じていない"

        body = ms.place(original, bodies, pos, block)

        if body is None:
            return "置ける型が無い"

        if ms.already(original, body, name):
            return "同名が既にある"

        return "取れるはず"

    return "その hunk に宣言が無い"


def main():
    missing, patch_root, tree = sys.argv[1:4]
    names = wanted(missing)
    reasons = {}
    absent = 0

    for target, hunks in fs.patches(patch_root):
        _, original = fs.source(tree, target)

        if not original:
            continue

        bodies = None

        for hunk in hunks:
            for line in hunk.added():
                for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD):
                    match = pattern.match(line)

                    if not match or match.group(1) not in names:
                        continue

                    if bodies is None:
                        bodies = fs.type_bodies(original)

                    why = classify(original, bodies, hunk, match.group(1))
                    reasons.setdefault(why, set()).add(f"{target} {match.group(1)}")
                    break

    print(f"足りない名前: {len(names)}")
    print()

    for why, hits in sorted(reasons.items(), key=lambda kv: -len(kv[1])):
        print(f"  {len(hits):4}  {why}")

        for one in sorted(hits)[:3]:
            print(f"          {one}")


if __name__ == "__main__":
    main()
