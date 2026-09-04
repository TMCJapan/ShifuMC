# -*- coding: utf-8 -*-
"""公式の lambda と名前が食い違っているメソッドを出す。

    python tools/check_lambdas.py <コンパイル済みクラスの置き場> <Mojang の jar>

javac は lambda に `lambda$<囲みのメソッド>$<番号>` という名前を付ける。番号は
**囲みのメソッドの中で、ソースに出てくる順**。Shifu の差し込みがラムダを 1 つ足すと、
その後ろにある vanilla のラムダが繰り下がる。

mixin はこの名前でラムダを狙う(`@Mixin` の `method = "lambda$award$0"`)。番号が
ずれると、別のラムダに当たるか、`Scanned 0 target(s)` で落ちる。styled-chat の
PlayerAdvancementsMixin で実際に踏んだ。

**差し込みにはラムダを使わない。** if 文と局所変数で書けば番号は動かない。
"""
import io
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from missing_members import parse_constant_pool  # noqa: E402
from keep_vanilla_classes import class_prefixes, mojang_classes, owner_of, touched_sources  # noqa: E402


def methods(data):
    """class ファイル -> [(メソッド名, 署名), ...]。"""
    pool = parse_constant_pool(data)
    count = struct.unpack_from(">H", data, 8)[0]
    at = 10
    index = 1

    while index < count:
        tag = data[at]
        at += 1

        if tag == 1:
            at += 2 + struct.unpack_from(">H", data, at)[0]
        elif tag in (7, 8, 16, 19, 20):
            at += 2
        elif tag == 15:
            at += 3
        elif tag in (3, 4):
            at += 4
        elif tag in (5, 6):
            at += 8
            index += 1
        else:
            at += 4

        index += 1

    at += 6
    at += 2 + struct.unpack_from(">H", data, at)[0] * 2

    def skip(where):
        n = struct.unpack_from(">H", data, where)[0]
        where += 2

        for _ in range(n):
            where += 6 + struct.unpack_from(">I", data, where + 2)[0]

        return where

    fields = struct.unpack_from(">H", data, at)[0]
    at += 2

    for _ in range(fields):
        at = skip(at + 6)

    n = struct.unpack_from(">H", data, at)[0]
    at += 2
    out = []

    for _ in range(n):
        name = pool[struct.unpack_from(">H", data, at + 2)[0]][1]
        desc = pool[struct.unpack_from(">H", data, at + 4)[0]][1]
        at = skip(at + 6)
        out.append((name, desc))

    return out


def main():
    classes = sys.argv[1]
    official = mojang_classes(sys.argv[2])
    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    problems = []

    for base, _, files in os.walk(classes):
        for name in files:
            if not name.endswith(".class"):
                continue

            rel = os.path.relpath(os.path.join(base, name), classes).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/") or owner_of(rel) not in keep or rel not in official:
                continue

            mine = {n: d for n, d in methods(io.open(os.path.join(base, name), "rb").read()) if n.startswith("lambda$")}
            theirs = {n: d for n, d in methods(official[rel]) if n.startswith("lambda$")}
            moved = sorted(n for n, d in theirs.items() if n in mine and mine[n] != d)
            gone = sorted(n for n in theirs if n not in mine)

            if moved or gone:
                problems.append((rel[:-len(".class")], moved, gone))

    if "--list" in sys.argv:
        lines = [rel for rel, _, _ in problems]
        io.open("tools/build/lambda-differs.txt", "w", encoding="utf-8",
                newline=chr(10)).write(chr(10).join(lines) + chr(10))

    print("名前が食い違うクラス: %d" % len(problems))

    for rel, moved, gone in problems:
        print("  %s" % rel)

        if moved:
            print("      署名が違う: %s" % ", ".join(moved))

        if gone:
            print("      公式にしかない: %s" % ", ".join(gone))

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
