# -*- coding: utf-8 -*-
"""版を移したときに、置き場所が変わったクラスへ規則を付け替える。

    python tools/retarget_rules.py <規則の置き場> <vanilla の木> [--write]

Minecraft はときどきクラスをパッケージごと動かす。1.20.6 と 1.21.11 の間では
`world/entity/animal/Fox.java` が `world/entity/animal/fox/Fox.java` になった。
規則の `file:` はパスで書いてあるので、動いた分は全部「このファイルが無い」になる。

同じ名前のファイルが移った先に **1 つだけ** あるなら、それが行き先。
2 つ以上あるときと、1 つも無いとき(その版に無いクラス)は触らない。

`--write` を付けるまで書き換えない。`patches/anon` のように 1 つのファイルに
複数の対象が入っているものもあるので、`file:` の行だけを書き換える。
規則のファイル名が対象のパスから作られているもの(`patches/events/generated` など)は
名前も付け替える。

`patches/hand` と `patches/shim` は `.add` の置き場所そのものが対象なので、
ファイルを動かす。

対象の木は規則の種類で違う。`patches/adapter` は Paper のソース、
それ以外は vanilla の木。
"""

import collections
import io
import os
import re
import sys

FILE = re.compile(r"^(\s*file:\s*)(\S+)\s*$")


def index(tree):
    """ファイル名 -> [木の中のパス]。"""
    out = collections.defaultdict(list)

    for base, _, names in os.walk(tree):
        for name in names:
            if name.endswith(".java"):
                rel = os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/")
                out[name].append(rel)

    return out


def slug(target):
    """`patches/events/generated` の規則のファイル名の作り方。"""
    return target[:-len(".java")].replace("/", "-") + ".rules"


def main():
    root, tree = sys.argv[1:3]
    write = "--write" in sys.argv
    where = index(tree)
    moved = 0
    gone = collections.Counter()
    renamed = 0

    for base, _, names in sorted(os.walk(root)):
        for name in sorted(names):
            # .add は置き場所そのものが対象。ファイルごと動かす
            if name.endswith(".add"):
                rel = os.path.relpath(os.path.join(base, name), root).replace(os.sep, "/")
                target = rel[:-len(".add")]

                if os.path.exists(os.path.join(tree, target.replace("/", os.sep))):
                    continue

                cand = where.get(target.rsplit("/", 1)[-1], [])

                if len(cand) != 1:
                    gone["同じ名前が複数" if cand else "その版に無い"] += 1
                    continue

                print(f"{rel}\n    -> {cand[0]}")
                moved += 1

                if write:
                    dest = os.path.join(root, cand[0].replace("/", os.sep) + ".add")
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    os.rename(os.path.join(base, name), dest)
                    renamed += 1

                continue

            if not name.endswith(".rules"):
                continue

            path = os.path.join(base, name)
            lines = io.open(path, encoding="utf-8").read().split("\n")
            out = []
            hits = []
            touched = False

            for line in lines:
                match = FILE.match(line)

                if not match:
                    out.append(line)
                    continue

                target = match.group(2)
                hits.append(target)

                if os.path.exists(os.path.join(tree, target.replace("/", os.sep))):
                    out.append(line)
                    continue

                cand = where.get(target.rsplit("/", 1)[-1], [])

                if len(cand) != 1:
                    gone["同じ名前が複数" if cand else "その版に無い"] += 1
                    out.append(line)
                    continue

                print(f"{name}: {target}\n    -> {cand[0]}")
                out.append(match.group(1) + cand[0])
                touched = True
                moved += 1

            if not touched or not write:
                continue

            io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out))

            # 対象のパスから作った名前なら、名前も付け替える
            if len(set(hits)) == 1 and name == slug(hits[0]):
                fixed = next(FILE.match(l).group(2) for l in out if FILE.match(l))
                os.rename(path, os.path.join(base, slug(fixed)))
                renamed += 1

    print(f"付け替えた file:: {moved} 件"
          + (f"(規則のファイル名も {renamed} 件)" if write else "(--write でまだ書いていない)"))

    for reason, count in gone.most_common():
        print(f"  触らなかった: {reason} {count} 件")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
