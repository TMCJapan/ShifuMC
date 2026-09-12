# -*- coding: utf-8 -*-
"""版を移したときに、置き場所が変わった NMS のクラスへ参照を引き直す。

    python tools/retarget_imports.py <vanilla の木> <直す場所...> \
        [--renames <対応表>] [--write]

`tools/retarget_rules.py` は規則の `file:` を直す。こちらはソースの中の
**完全修飾名**を直す。直す対象は `net.minecraft.` で始まるものだけ。

Minecraft は 1.21 系で entity を細かいパッケージに分けた。
1.21.11 向けに書いた発火層はその置き場所で import しているので、
1.20.6 では `package net.minecraft.world.entity.animal.pig does not exist` になる。

    import net.minecraft.world.entity.animal.pig.Pig;
    -> import net.minecraft.world.entity.animal.Pig;

**その版に無いクラス**は触らない。同じ名前が 2 つ以上あるときも触らない。
どちらも人が読む対象として並べる。

名前ごと変わったもの(`EntitySpawnReason` は 1.21.2 で `MobSpawnType` から改名)は
場所を探しても見つからない。`--renames` に対応表を渡すと、先にそちらを当てる。

    # patches/renames.txt
    net.minecraft.world.entity.EntitySpawnReason -> net.minecraft.world.entity.MobSpawnType

`--write` を付けるまで書き換えない。
"""

import collections
import io
import os
import re
import sys

# `net.minecraft.a.b.C` と、その後ろに続く入れ子の型(`.Inner`)は残す。
NAME = re.compile(r"\bnet\.minecraft\.(?:[a-z_][\w]*\.)+[A-Z]\w*")


def index(tree):
    """クラスの単純名 -> [木の中の完全修飾名]。"""
    out = collections.defaultdict(list)

    for base, _, names in os.walk(tree):
        for name in names:
            if not name.endswith(".java"):
                continue

            rel = os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/")
            out[name[:-len(".java")]].append(rel[:-len(".java")].replace("/", "."))

    return out


def renames(path):
    """`古い名前 -> 新しい名前` の対応表。"""
    out = {}

    if not path:
        return out

    for line in io.open(path, encoding="utf-8"):
        text = line.split("#")[0].strip()

        if "->" in text:
            old, new = (part.strip() for part in text.split("->", 1))
            out[old] = new

    return out


def main():
    tree = sys.argv[1]
    write = "--write" in sys.argv
    table = None

    if "--renames" in sys.argv:
        table = sys.argv[sys.argv.index("--renames") + 1]

    roots = [a for a in sys.argv[2:] if a != "--write" and a != "--renames" and a != table]
    known = renames(table)
    where = index(tree)
    fixed = collections.Counter()
    gone = collections.Counter()

    for root in roots:
        paths = []

        if os.path.isfile(root):
            paths.append(root)
        else:
            for base, _, names in os.walk(root):
                paths.extend(os.path.join(base, n) for n in sorted(names)
                             if n.endswith((".java", ".rules", ".add")))

        for path in sorted(paths):
            text = io.open(path, encoding="utf-8").read()
            hits = {}

            for full in set(NAME.findall(text)):
                # 名前ごと変わったものは場所を探しても見つからない。先に対応表を当てる
                if full in known:
                    hits[full] = known[full]
                    continue

                # 木の根には net/minecraft/ から入っている
                if os.path.exists(os.path.join(tree, full.replace(".", os.sep) + ".java")):
                    continue

                # `net.minecraft.a.b.Outer.Inner` は Outer で引く
                parts = full.split(".")
                head = next((n for n, part in enumerate(parts) if part[:1].isupper()), None)
                simple = parts[head]
                cand = [c for c in where.get(simple, []) if c != ".".join(parts[:head + 1])]

                if len(cand) != 1:
                    gone["同じ名前が複数" if cand else "その版に無い"] += 1
                    print(f"  そのまま: {full}"
                          + (f" -> {cand}" if cand else "(その版に無い)"))
                    continue

                hits[full] = ".".join([cand[0]] + parts[head + 1:])

            if not hits:
                continue

            for full, to in sorted(hits.items(), key=lambda kv: -len(kv[0])):
                text = re.sub(r"\b" + re.escape(full) + r"\b", to, text)
                fixed[full] += 1
                print(f"{os.path.relpath(path)}: {full} -> {to}")

            if write:
                io.open(path, "w", encoding="utf-8", newline="\n").write(text)

    print(f"引き直した名前: {sum(fixed.values())} 件 / {len(fixed)} 種"
          + ("" if write else "(--write でまだ書いていない)"))

    for reason, count in gone.most_common():
        print(f"  触らなかった: {reason} {count} 件")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
