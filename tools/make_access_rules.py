# -*- coding: utf-8 -*-
"""可視性が足りないというエラーから、`patches/access` の規則を作る。

    python tools/make_access_rules.py <vanilla-gap.txt> <vanilla の木> <出力先> [--write]

javac は届かない要素を

    zMin has private access in BitSetDiscreteVoxelShape
    getOrLoad(long) has protected access in SectionStorage

の形で出す。型と名前が分かるので、その型のファイルから宣言の行を探せる。
`widen_access.py` は**行そのもの**をアンカーにするので、その行を書き出す。

宣言が 1 つに定まらないとき(同じ名前のオーバーロードがある、内部クラスにも
同じ名前がある)は書かない。人が読む対象として並べる。

版を移すと同じ欄の修飾子が変わる(26.2 で `private`、1.21.11 で修飾子無し)ので、
アンカーはそのバージョンの木から取り直す必要がある。この道具はそれを機械でやる。
"""

import collections
import io
import os
import re
import sys

ACCESS = re.compile(r"\b([A-Za-z_$][\w$]*)(\([^)]*\))? has (?:private|protected) access "
                    r"in ([A-Za-z0-9_.$]+)")
# 宣言らしい行。修飾子か型で始まり、名前がある
DECL = re.compile(r"^\s{2,}(?:@[\w.]+\s+)*(?:public\s+|private\s+|protected\s+|static\s+|final\s+"
                  r"|abstract\s+|synchronized\s+|native\s+|transient\s+|volatile\s+|default\s+)*"
                  r"[\w.<>,?\[\]$]+(?:\s*\.\.\.)?\s+([\w$]+)\s*[;=({]")


def index(tree):
    """クラスの単純名 -> 木の中のパス。同じ名前が複数あれば None。"""
    out = collections.defaultdict(list)

    for base, _, names in os.walk(tree):
        for name in names:
            if name.endswith(".java"):
                out[name[:-len(".java")]].append(
                    os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/"))

    return out


def wanted(gap):
    """(型の単純名, 要素の名前, 引数の形) の集合。"""
    out = set()

    for line in io.open(gap, encoding="utf-8", errors="replace"):
        for name, args, owner in ACCESS.findall(line):
            out.add((owner.rsplit(".", 1)[-1], name, args or ""))

    return out


MEMBER = re.compile(r"^# ([\w$]+)(\([^)]*\))?$")
TARGET = re.compile(r"^file: (\S+)$")


def already(path):
    """前の回に書いた規則。**消さずに積み増す。**

    エラーは直ると出なくなるので、そのとき出ているものだけで書き直すと
    直した規則が消え、次の回にまた同じエラーが出る(1 <-> 25 で振動した)。
    """
    out = set()

    if not os.path.exists(path):
        return out

    member = None

    for line in io.open(path, encoding="utf-8"):
        text = line.rstrip("\n")
        hit = MEMBER.match(text)

        if hit:
            member = (hit.group(1), hit.group(2) or "")
            continue

        hit = TARGET.match(text)

        if hit and member:
            out.add((hit.group(1).rsplit("/", 1)[-1][:-len(".java")],) + member)
            member = None

    return out


def find(lines, name, args):
    """その要素の宣言の行番号。1 つに定まらなければ None。"""
    hits = []

    for number, line in enumerate(lines):
        match = DECL.match(line)

        if not match or match.group(1) != name:
            continue

        # メソッドは引数の数で絞る。名前だけだと欄と読み出しが混ざる
        if args:
            if "(" not in line:
                continue

            want = len([a for a in args[1:-1].split(",") if a.strip()])
            inner = line[line.index("(") + 1:]
            inner = inner[:inner.find(")")] if ")" in inner else inner
            got = len([a for a in inner.split(",") if a.strip()])

            if want != got:
                continue
        elif "(" in line.split("=")[0]:
            continue

        hits.append(number)

    return hits[0] if len(hits) == 1 else None


def main():
    gap, tree, dest = sys.argv[1:4]
    write = "--write" in sys.argv
    where = index(tree)
    by_file = collections.defaultdict(list)
    unsure = []

    for owner, name, args in sorted(wanted(gap) | already(dest)):
        paths = where.get(owner, [])

        if len(paths) != 1:
            unsure.append(f"{owner}.{name}{args}: 型が {len(paths)} 箇所")
            continue

        lines = io.open(os.path.join(tree, paths[0].replace("/", os.sep)),
                        encoding="utf-8", errors="replace").read().split("\n")
        at = find(lines, name, args)

        if at is None:
            unsure.append(f"{owner}.{name}{args}: 宣言が 1 つに定まらない")
            continue

        by_file[paths[0]].append((name + args, lines[at].rstrip()))

    body = ["# tools/make_access_rules.py が作ったもの。",
            "# 可視性が足りないというエラーから、宣言の行をそのまま写している。"]

    for path, items in sorted(by_file.items()):
        for member, line in items:
            body.append("")
            body.append(f"# {member}")
            body.append(f"file: {path}")
            body.append("line:")
            body.append(line)

    print(f"作った規則: {sum(len(v) for v in by_file.values())} 件 / {len(by_file)} ファイル"
          + ("" if write else "(--write でまだ書いていない)"))

    for line in unsure:
        print(f"  書かなかった: {line}")

    if write:
        os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
        io.open(dest, "w", encoding="utf-8", newline="\n").write("\n".join(body) + "\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
