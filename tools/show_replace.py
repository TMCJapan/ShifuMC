# -*- coding: utf-8 -*-
"""Paper のパッチのうち、イベントを発火している塊を、前後の文脈と vanilla のメソッドごと見る。

    python tools/show_replace.py <ファイル名の一部> [イベント名の一部]

例: python tools/show_replace.py Leashable EntityUnleashEvent

出すもの(塊ごと):
  1. パッチのハンク全体(' ' が文脈、'-' が消えた vanilla の行、'+' が Paper の行)
  2. 消えた行(無ければ足した行の直前の文脈行)を含む、vanilla のメソッド全体
     (`git show $SHIFU_BASE:<パス>` から、字下げ 4 の宣言から次の字下げ 4 の行まで)
規則を書くときは 2 の行をアンカーにする。
"""
import io
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_events

import paths

SOURCES = paths.SOURCES
TREE = paths.TREE
BASE = paths.BASE
HEAD = re.compile(r"^@@ -(\d+)")


def vanilla_of(path):
    out = subprocess.run(["git", "show", "%s:%s" % (BASE, path)], cwd=TREE,
                         capture_output=True, check=True).stdout
    return out.decode("utf-8").split("\n")


def method_around(lines, number):
    """number(1 始まり)の行を含むメソッドの (開始, 終了) 行番号。"""
    start = number - 1

    while start > 0 and not (lines[start].startswith("    ") and not lines[start].startswith("     ") and lines[start].strip()):
        start -= 1

    end = number

    while end < len(lines) and not (lines[end].startswith("    ") and not lines[end].startswith("     ") and lines[end].strip()):
        end += 1

    return start, min(end, len(lines) - 1)


def hunks_with_numbers(path):
    """(vanilla 側の開始行, ハンクの行の並び)。"""
    start = 0
    body = None

    for raw in io.open(path, encoding="utf-8").read().split("\n"):
        if raw.startswith(("--- ", "+++ ")):
            continue

        match = HEAD.match(raw)

        if match:
            if body:
                yield start, body

            start = int(match.group(1))
            body = []
            continue

        if body is not None:
            body.append(raw)

    if body:
        yield start, body


def main():
    want_file = sys.argv[1]
    want_event = sys.argv[2] if len(sys.argv) > 2 else None

    for root, _, files in os.walk(SOURCES):
        for f in sorted(files):
            if not f.endswith(".java.patch") or want_file not in f:
                continue

            rel = os.path.relpath(os.path.join(root, f), SOURCES).replace("\\", "/")[:-len(".patch")]
            vanilla = vanilla_of(rel)

            for start, hunk in hunks_with_numbers(os.path.join(root, f)):
                added = [l[1:] for l in hunk if l[:1] == "+"]

                if not any(scan_events.FIRE.search(l) for l in added):
                    continue

                if want_event and not any(want_event in l for l in added):
                    continue

                print("=" * 100)
                print("%s @@ -%d" % (rel, start))
                print("-" * 100)

                for line in hunk:
                    print(line)

                # 消えた行、無ければ発火の直前の文脈行の、vanilla の行番号
                number = None
                count = 0

                for index, line in enumerate(hunk):
                    if line[:1] in " -":
                        count += 1

                    if line[:1] == "-" and number is None:
                        number = start + count - 1

                if number is None:
                    for index, line in enumerate(hunk):
                        if line[:1] == "+" and scan_events.FIRE.search(line):
                            number = start + count_before(hunk, index) - 1
                            break

                if number is None:
                    continue

                a, b = method_around(vanilla, number)
                print("-" * 40 + " vanilla %s:%d-%d " % (rel.split("/")[-1], a + 1, b + 1) + "-" * 40)

                for n in range(a, b + 1):
                    mark = ">>" if n == number - 1 else "  "
                    print("%s %4d  %s" % (mark, n + 1, vanilla[n]))


def count_before(hunk, index):
    return sum(1 for l in hunk[:index] if l[:1] in " -")


if __name__ == "__main__":
    main()
