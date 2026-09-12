# -*- coding: utf-8 -*-
"""通らない `.add` の塊を外す。コンパイラの言うことを唯一の根拠にする。

    python tools/drop_bad_adds.py <vanilla-gap.txt> <.add の置き場> <外したものの控え>

`drop_bad_events.py` は差し込む規則を、`drop_bad_helpers.py` は発火層のメソッドを外す。
こちらは `patches/hand/**.add`(手で書いた追加)の塊を外す。

版を下げると、その版に無い型を引数に取る追加が残る
(`internalTeleport(PositionMoveRotation, Set<Relative>)` は 1.21.2 から、
`setRespawnPosition(RespawnConfig, ...)` は 1.21.9 から)。

やること: エラーの出た行の中身を、塊が足す行の中から探す。見つかった塊を外す。

**黙って消さない。** 控えを見れば、どの追加がどの型のせいで落ちたかが残る。
"""

import collections
import io
import os
import re
import sys

ERROR = re.compile(r"^(.+?\.java):(\d+): error: (.*)$")
MARK = re.compile(r"^// (\S+)\.([\w$]+)$")
# 手掛かりにならない行。括弧だけ、注釈だけ、注記だけ
NOISE = re.compile(r"^\s*(?:[{}();]*|//.*|/\*.*|\*.*|@\w+)\s*$")


def errors(gap):
    """エラーの出た行の中身(そのファイルでの行そのもの)。"""
    out = collections.defaultdict(set)
    reason = collections.defaultdict(set)
    pending = None

    for raw in io.open(gap, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        match = ERROR.match(line)

        if match:
            pending = (match.group(1).replace("\\", "/").rsplit("/java/", 1)[-1],
                       match.group(3))
            continue

        if pending is None:
            continue

        text = line.strip()

        if text and not text.startswith(("symbol:", "location:", "required:", "found:",
                                         "reason:", "^")) and not NOISE.match(text):
            out[pending[0]].add(text)
            reason[(pending[0], text)].add(pending[1].split(":")[0].strip())

        pending = None

    return out, reason


def blocks(text):
    """[(目印, 行の並び)]。目印は `// <所有クラス>.<名前>` の行。"""
    out = []
    head = []
    current = None

    for line in text.split("\n"):
        if MARK.match(line.strip()):
            if current is not None:
                out.append(current)

            current = [line]
            continue

        (current if current is not None else head).append(line)

    if current is not None:
        out.append(current)

    return head, out


def main():
    gap, root, note = sys.argv[1:4]
    bad, reason = errors(gap)
    dropped = []

    for base, _, names in sorted(os.walk(root)):
        for name in sorted(names):
            if not name.endswith(".add"):
                continue

            path = os.path.join(base, name)
            target = os.path.relpath(path, root)[:-len(".add")].replace(os.sep, "/")
            want = bad.get(target)

            if not want:
                continue

            text = io.open(path, encoding="utf-8").read()
            head, found = blocks(text)
            keep = []

            for block in found:
                hit = next((line.strip() for line in block[1:]
                            if line.strip() in want), None)

                if hit is None:
                    keep.append(block)
                    continue

                dropped.append((target, block[0].strip()[3:],
                                sorted(reason[(target, hit)])))

            if len(keep) == len(found):
                continue

            body = head + [line for block in keep for line in block]
            io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(body))

    with io.open(note, "a", encoding="utf-8", newline="\n") as handle:
        for target, member, why in dropped:
            handle.write(f"{target}\t{member}\t{', '.join(why)}\n")

    print(f"外した塊 {len(dropped)} -> {note}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
