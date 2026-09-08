# -*- coding: utf-8 -*-
"""差し込む本体の中で解決できない型を、完全修飾に直す。

    python tools/qualify_rule_types.py <vanilla-gap.txt> <vanilla の木> \
        <規則の置き場...> [--write]

規則の本体は Paper のパッチから写しているので、Paper のファイルの import に
頼った単純名が入っている。版が変わって差し込む先のファイルの import が違うと
`package HolderLookup does not exist` や `cannot find symbol: class X` になる。

Shifu の差し込みは `org.bukkit.craftbukkit.util.CraftLocation` のように
完全修飾で書くのが決まりなので、そこに揃える。木の中でその名前のファイルが
1 つに定まるときだけ直す。

**アンカーは触らない。** アンカーは vanilla の行そのもので、直すと当たらなくなる。
"""

import collections
import io
import os
import re
import sys

ERROR = re.compile(r"^(.+?\.java):(\d+): error: (?:cannot find symbol|package ([\w$]+) "
                   r"does not exist)\s*$")
SYMBOL = re.compile(r"^\s*symbol:\s+class ([\w$]+)")


def index(tree):
    """クラスの単純名 -> 完全修飾名。同じ名前が複数あれば入れない。"""
    out = collections.defaultdict(set)

    for base, _, names in os.walk(tree):
        for name in names:
            if name.endswith(".java"):
                rel = os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/")
                out[name[:-len(".java")]].add(rel[:-len(".java")].replace("/", "."))

    return {name: next(iter(full)) for name, full in out.items() if len(full) == 1}


def wanted(gap):
    """(差し込み先のファイル, 解決できない型の名前)。"""
    out = set()
    pending = None

    for raw in io.open(gap, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        match = ERROR.match(line)

        if match:
            rel = match.group(1).replace("\\", "/").rsplit("/java/", 1)[-1]

            if match.group(3):
                out.add((rel, match.group(3)))
                pending = None
            else:
                pending = rel

            continue

        hit = SYMBOL.match(line)

        if hit and pending:
            out.add((pending, hit.group(1)))
            pending = None

    return out


ANCHOR = re.compile(r"^(anchor|line|replace):\s*$")
BODY = re.compile(r"^(insert|insert-after|with|add):\s*$")
FILE = re.compile(r"^file:\s*(\S+)\s*$")


def main():
    gap, tree = sys.argv[1:3]
    write = "--write" in sys.argv
    roots = [a for a in sys.argv[3:] if a != "--write"]
    where = index(tree)
    todo = collections.defaultdict(set)

    for rel, name in wanted(gap):
        if name in where:
            todo[rel].add(name)

    fixed = 0

    for root in roots:
        for base, _, names in sorted(os.walk(root)):
            for name in sorted(names):
                if not name.endswith(".rules"):
                    continue

                path = os.path.join(base, name)
                lines = io.open(path, encoding="utf-8").read().split("\n")
                target = None
                inside = False
                touched = False

                for number, line in enumerate(lines):
                    hit = FILE.match(line)

                    if hit:
                        target = hit.group(1)
                        inside = False
                        continue

                    if ANCHOR.match(line):
                        inside = False
                        continue

                    if BODY.match(line):
                        inside = True
                        continue

                    if not inside or not target or not line.strip():
                        continue

                    for want in todo.get(target, ()):
                        # 直前が `.` の語(メンバー名)は触らない
                        spot = re.compile(r"(?<![\w$.])" + re.escape(want) + r"(?![\w$])")

                        if spot.search(line):
                            lines[number] = spot.sub(where[want], line)
                            touched = True
                            fixed += 1

                if not touched:
                    continue

                print(f"{os.path.relpath(path)}")

                if write:
                    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))

    print(f"完全修飾に直した箇所: {fixed} 件"
          + ("" if write else "(--write でまだ書いていない)"))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
