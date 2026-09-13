# -*- coding: utf-8 -*-
"""LinkCheck の出力(公式に戻したクラスに無い欄・メソッド)を required-members.txt の項目にする。

    python tools/link_to_required.py <link-missing.txt> <required-members.txt>

LinkCheck は 1 行 1 件 `<持ち主> <名前> <記述子> <参照元> <missing|access>` を出す。持ち主の単純名を見出しに、
無いものは欄なら variable、メソッドなら method、見えないだけのものは access として足す(make_classic_shim が Paper の宣言を写す鍵と同じ)。
既にある項目は足さない。足した数を出す。
"""
import io
import os
import re
import sys

OWNER = re.compile(r"^([\w$.<>()]+)\s+\((\d+)\)$")
ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")


def load(path):
    """見出しごとの項目(順序つき)。"""
    owners = {}
    order = []
    current = None

    for line in io.open(path, encoding="utf-8"):
        text = line.rstrip("\n")
        hit = OWNER.match(text)

        if hit:
            current = hit.group(1)

            if current not in owners:
                owners[current] = []
                order.append(current)

            continue

        hit = ENTRY.match(text)

        if hit and current is not None:
            owners[current].append((hit.group(1), hit.group(2)))

    return owners, order


def main():
    missing, required = sys.argv[1:3]
    owners, order = load(required)
    added = 0
    access = []

    for line in io.open(missing, encoding="utf-8"):
        parts = line.split()

        if len(parts) < 3:
            continue

        owner, name, desc = parts[0], parts[1], parts[2]
        simple = owner.rsplit("/", 1)[-1].rsplit("$", 1)[-1]
        # 5 つめが access なら宣言はあって見えないだけ。可視性は patches/access で広げるので、
        # javac の誤りと同じ形にして書き出し、make_access_rules.py に渡す(要求一覧には入れない)
        if len(parts) > 4 and parts[4] == "access":
            args = "" if not desc.startswith("(") else "(" + ",".join(
                t.rsplit("/", 1)[-1].rstrip(";") for t in desc[1:desc.index(")")].split(";") if t) + ")"
            access.append(f"  {name}{args} has private access in {simple}")
            continue
        kind = "variable" if not desc.startswith("(") else "method"

        if name == "<init>":
            name = simple

        if simple not in owners:
            owners[simple] = []
            order.append(simple)

        if (kind, name) not in owners[simple]:
            owners[simple].append((kind, name))
            added += 1

    with io.open(required, "w", encoding="utf-8", newline="\n") as out:
        out.write("# アダプタ層が vanilla NMS に要求しているもの(コンパイルの誤りと LinkCheck から)\n")
        out.write("# 種別 method=メソッド variable=フィールド class=型 access=可視性 abstract=抽象\n")

        for owner in order:
            entries = owners[owner]
            out.write(f"\n{owner}  ({len(entries)})\n")

            for kind, name in sorted(entries, key=lambda e: (e[1], e[0])):
                out.write(f"    {kind:<9} {name}\n")

    print(f"足した: {added} 件")

    if access:
        gap = os.path.join(os.path.dirname(missing), "link-access-gap.txt")
        io.open(gap, "w", encoding="utf-8", newline="\n").write("\n".join(access) + "\n")
        print(f"見えないだけのもの: {len(access)} 件 -> {gap}(make_access_rules.py に渡す)")


if __name__ == "__main__":
    main()
