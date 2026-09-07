# -*- coding: utf-8 -*-
"""差し込む本体が使う局所変数の名前を、版のあいだで引き直す。

    python tools/rename_body_vars.py <vanilla-gap.txt> <古い木> <新しい木> \
        <規則の置き場...> [--write]

`reanchor.py` はアンカーの行に出てくる名前しか直せない。**差し込む本体は
アンカーの外の局所変数も参照する**ので、そこは当たったまま名前だけが違う。

    dev.shifu.event.BlockEvents.dispense(blockSource, item)
      1.21.11: execute(BlockSource blockSource, ItemStack item)
      1.20.6 : execute(BlockSource pointer,     ItemStack stack)

やること: `cannot find symbol: variable X` のエラーから、その行を囲むメソッドを
新しい木で見つけ、同じ名前と同じ引数の数のメソッドを古い木で見つけて、
**引数を位置で対応させる**。X が古い側の引数なら、同じ位置の新しい名前に替える。

対応が 1 つに定まらないときは触らない。`--write` を付けるまで書き換えない。
"""

import collections
import io
import os
import re
import sys

ERROR = re.compile(r"^(.+?\.java):(\d+): error: cannot find symbol\s*$")
SYMBOL = re.compile(r"^\s*symbol:\s+variable ([\w$]+)")
# メソッドの宣言。戻り値と名前と引数まで。
HEAD = re.compile(r"^(\s+)(?:(?:public|private|protected|static|final|abstract|synchronized|"
                  r"default|native)\s+)*[\w.<>,?\[\]$]+\s+([\w$]+)\s*\(([^;{]*)\)\s*\{?\s*$")
ARG = re.compile(r"^(.*?[\w\]>])\s+([\w$]+)$")


def args_of(text):
    """引数の並びを (型, 名前) にする。"""
    out = []
    depth = 0
    current = ""

    for char in text:
        if char in "<(":
            depth += 1
        elif char in ">)":
            depth -= 1

        if char == "," and depth == 0:
            out.append(current)
            current = ""
            continue

        current += char

    out.append(current)
    got = []

    for one in out:
        one = one.strip().replace("final ", "")

        if not one:
            continue

        hit = ARG.match(one)

        if not hit:
            return []

        got.append((hit.group(1).replace(" ", ""), hit.group(2)))

    return got


def methods(lines):
    """[(名前, [(型, 引数名)], 開始行, 字下げ)]。"""
    out = []

    for number, line in enumerate(lines):
        match = HEAD.match(line.rstrip())

        if not match or match.group(2) in ("if", "for", "while", "switch", "catch", "return"):
            continue

        out.append((match.group(2), args_of(match.group(3)), number, len(match.group(1))))

    return out


def enclosing(found, number):
    """その行を囲む一番近いメソッド。"""
    before = [m for m in found if m[2] < number]

    return before[-1] if before else None


def failures(gap):
    """(ファイル, 行番号) -> 見つからない変数の名前。"""
    out = collections.defaultdict(set)
    current = None

    for raw in io.open(gap, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        match = ERROR.match(line)

        if match:
            current = (match.group(1).replace("\\", "/"), int(match.group(2)))
            continue

        hit = SYMBOL.match(line)

        if hit and current:
            out[current].add(hit.group(1))
            current = None

    return out


def read(root, rel):
    path = os.path.join(root, rel.replace("/", os.sep))

    if not os.path.exists(path):
        return None

    return io.open(path, encoding="utf-8", errors="replace").read().split("\n")


def main():
    gap, old_root, new_root = sys.argv[1:4]
    write = "--write" in sys.argv
    roots = [a for a in sys.argv[4:] if a != "--write"]
    # ファイル -> 旧名 -> {新名: 回数}
    table = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))

    for (rel, number), names in sorted(failures(gap).items()):
        # gap のパスは main/java/... で始まる。木からの相対に直す
        cut = rel.split("/java/", 1)
        target = cut[1] if len(cut) > 1 else rel

        new = read(new_root, target)
        old = read(old_root, target)

        if new is None or old is None:
            continue

        here = enclosing(methods(new), number - 1)
        there = [m for m in methods(old) if here and m[0] == here[0]
                 and len(m[1]) == len(here[1])]

        if here is None or len(there) != 1:
            continue

        for want in names:
            for (old_type, old_name), (new_type, new_name) in zip(there[0][1], here[1]):
                if old_name == want and old_type == new_type:
                    table[target][want][new_name] += 1

    fixed = 0

    for root in roots:
        for base, _, names in sorted(os.walk(root)):
            for name in sorted(names):
                if not name.endswith(".rules"):
                    continue

                path = os.path.join(base, name)
                text = io.open(path, encoding="utf-8").read()
                out = text

                for target, pairs in table.items():
                    if f"file: {target}" not in out:
                        continue

                    for want, choices in pairs.items():
                        if len(choices) != 1:
                            continue

                        to = next(iter(choices))
                        # 直前が `.` の語(メンバー名)と直後が `(` の語(メソッド名)は触らない
                        out = re.sub(r"(?<![\w$.])" + re.escape(want) + r"(?![\w$]|\s*\()",
                                     to, out)

                if out == text:
                    continue

                fixed += 1
                print(f"{os.path.relpath(path)}")

                if write:
                    io.open(path, "w", encoding="utf-8", newline="\n").write(out)

    pairs = sum(len(v) for v in table.values())
    print(f"対応が付いた名前: {pairs} 件 / 直した規則のファイル: {fixed} 件"
          + ("" if write else "(--write でまだ書いていない)"))

    for target, got in sorted(table.items()):
        for want, choices in got.items():
            print(f"  {target}: {want} -> {', '.join(sorted(choices))}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
