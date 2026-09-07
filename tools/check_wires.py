# -*- coding: utf-8 -*-
"""別のブランチの `patches/wire` が入れている呼び出しが、今の木にも入っているかを見る。

    python tools/check_wires.py [<比べる先>] [<木>]

既定は `main` と `tools/paths.py` の木。

バージョンを移すとアンカー(vanilla の行)は当たらなくなる。当たらない規則は
`apply_events.py` が止めるので黙って消えることは無い……のだが、**規則の本文を消して
`file:` の行だけ残す**という直し方をすると、何も入らないまま静かに通る。
これはそれを見つけるためのもの。

突き合わせはアンカーではなく**差し込む本体**で行う。本体に出てくる
「大文字で始まる名前」と「`.` を含む名前」が、当てた木の同じファイルに全部あるかを見る。
局所変数の名前が版で変わっているだけのものも「無い」と出るので、出たものは 1 件ずつ読む。
"""

import io
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths

WORD = re.compile(r"[A-Za-z_$][A-Za-z0-9_$.]*")


def stanzas(text):
    """(対象ファイル, 差し込む本体の行) の並び。"""
    out = []
    target = None
    body = []
    inside = False

    for line in text.split("\n"):
        stripped = line.strip()

        if stripped.startswith("file:"):
            if body:
                out.append((target, body))
                body = []

            target = stripped[len("file:"):].strip()
            inside = False
            continue

        if stripped == "anchor:":
            if body:
                out.append((target, body))
                body = []

            inside = False
            continue

        if stripped in ("insert:", "insert-after:"):
            inside = True
            continue

        if stripped.startswith("count:"):
            inside = False
            continue

        if inside and stripped and not stripped.startswith("#"):
            body.append(stripped)

    if body:
        out.append((target, body))

    return out


def main():
    other = sys.argv[1] if len(sys.argv) > 1 else "main"
    tree = sys.argv[2] if len(sys.argv) > 2 else paths.TREE
    listing = subprocess.run(["git", "ls-tree", "-r", "--name-only", other, "patches/wire"],
                             capture_output=True, text=True)

    if listing.returncode != 0:
        raise SystemExit(f"{other} が読めない: {listing.stderr.strip()}")

    missing = 0

    for path in listing.stdout.split():
        text = subprocess.run(["git", "show", f"{other}:{path}"],
                              capture_output=True, text=True, encoding="utf-8").stdout

        for target, body in stanzas(text):
            if target is None:
                continue

            full = os.path.join(tree, target.replace("/", os.sep))

            if not os.path.exists(full):
                print(f"{os.path.basename(path)}: {target} がこの版に無い")
                print(f"    {body[0][:110]}")
                missing += 1
                continue

            source = io.open(full, encoding="utf-8", errors="replace").read()
            names = {w for line in body for w in WORD.findall(line) if "." in w or w[0].isupper()}
            gone = sorted(n for n in names if n not in source)

            if gone:
                print(f"{os.path.basename(path)}: {target}")
                print(f"    {body[0][:110]}")
                print(f"    足りない名前: {', '.join(gone[:6])}")
                missing += 1

    print(f"--- 入っていない配線: {missing}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
