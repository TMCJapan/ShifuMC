# -*- coding: utf-8 -*-
"""1.21.4 より前の Paper から、`patches/sources` と同じ形の差分を作る。

    python tools/make_classic_sources.py <vanilla の木> <Paper のソース> <出力先>

1.21.4 以降(mache)の Paper は `paper-server/patches/sources/**.java.patch` を持っていて、
`scan_events.py` や `make_events.py` はそれを読む。classic にはそれが無い。

`tools/apply_classic_by_file.py` で `Paper-Server` を最後まで当てたあと、
vanilla の木と 1 ファイルずつ突き合わせれば同じ形が作れる。

## 逆コンパイラの方言

classic の `Paper-Server` は 2 つの逆コンパイラの出力が混ざっている。

* CraftBukkit が触るファイル(1.20.6 で 550 件)は spigot 側の逆コンパイル。
  `1.0D`、`blockposition1`、`(BlockState)` の余分な castが付く
* それ以外(503 件)は `decompileJar` と同じ vineflower の出力

**前者は文脈行が vanilla の木と一致しない。**そのまま `make_events.py` に渡しても
アンカーが当たらないので、名前を `.java.patch.cb` にして分けてある。
発火の位置を人が読む材料にはなる。
"""

import difflib
import io
import os
import sys


def read(path):
    return io.open(path, encoding="utf-8", errors="replace").read().replace("\r\n", "\n").split("\n")


def main():
    tree, paper, dest = sys.argv[1:4]
    made = 0
    cb = 0

    for base, _, names in os.walk(tree):
        for name in sorted(names):
            if not name.endswith(".java"):
                continue

            rel = os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/")
            other = os.path.join(paper, rel.replace("/", os.sep))

            if not os.path.exists(other):
                continue

            old = read(os.path.join(base, name))
            new = read(other)

            if old == new:
                continue

            # CraftBukkit が触ったファイルは vanilla との差が方言ごと出る。
            # 「Paper が足した行」だけの差分にならないので、名前で分ける
            marks = sum(1 for l in new if "// CraftBukkit" in l or "// Spigot" in l)
            body = list(difflib.unified_diff(old, new, n=3, lineterm=""))[2:]
            out = os.path.join(dest, rel.replace("/", os.sep)) + ".patch" + (".cb" if marks else "")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            io.open(out, "w", encoding="utf-8", newline="\n").write(
                f"--- a/{rel}\n+++ b/{rel}\n" + "\n".join(body) + "\n")

            if marks:
                cb += 1
            else:
                made += 1

    print(f"作った差分: {made} 件(方言が合うもの)+ {cb} 件(CraftBukkit が触ったもの、.cb)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
