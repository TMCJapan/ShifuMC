# -*- coding: utf-8 -*-
"""通らない差し込みの規則を外す。`patches/wire` `patches/anon` `patches/events` 向け。

    python tools/drop_bad_rules.py <vanilla-gap.txt> <規則の置き場> <外したものの控え>

`drop_bad_events.py` は `# ` で始まる行で規則を切る。`patches/wire` は本文にも
`# ` の注記が入るので、そこで切ると規則が半分に割れて
`file: が先に要る` で読めなくなる。

こちらは **`file:` の行を基点に切る**。`file:` の手前にある注記と空行までを
1 つの規則として扱う。

やること: エラーの出た行の中身を、規則が差し込む行の中から探す。
見つかった規則を外して、控えに理由ごと書く。**黙って消さない。**
"""

import collections
import io
import os
import re
import sys

ERROR = re.compile(r"^(.+?\.java):(\d+): error: (.*)$")
FILE = re.compile(r"^file:\s*(\S+)\s*$")
BODY = re.compile(r"^(insert|insert-after|with|add|replace):\s*$")
HEAD = re.compile(r"^(anchor|line|file|count|implements):")
NOISE = re.compile(r"^\s*(?:[{}();]*|//.*|/\*.*|\*.*|@\w+)\s*$")


def errors(gap):
    """差し込んだ行の中身 -> 理由。"""
    out = collections.defaultdict(set)
    pending = None

    for raw in io.open(gap, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        match = ERROR.match(line)

        if match:
            pending = match.group(3)
            continue

        if pending is None:
            continue

        text = line.strip()

        if (text and not text.startswith(("symbol:", "location:", "required:", "found:",
                                          "reason:", "^", "where "))
                and not NOISE.match(text)):
            out[text].add(pending.split(":")[0].strip())

        pending = None

    return out


def split(text):
    """規則ファイルを (頭書き, [規則の塊]) に切る。`file:` の行が規則の目印。"""
    lines = text.split("\n")
    starts = [n for n, line in enumerate(lines) if FILE.match(line)]

    if not starts:
        return text, []

    # `file:` の手前にある注記と空行までを、その規則のものにする
    heads = []

    for at in starts:
        back = at

        while back > 0 and (not lines[back - 1].strip()
                            or lines[back - 1].lstrip().startswith("#")):
            back -= 1

        heads.append(back)

    blocks = []

    for n, begin in enumerate(heads):
        end = heads[n + 1] if n + 1 < len(heads) else len(lines)
        blocks.append(lines[begin:end])

    return "\n".join(lines[:heads[0]]), blocks


def inserted(block):
    """その規則が差し込む行。"""
    out = []
    on = False

    for line in block:
        if BODY.match(line):
            on = True
            continue

        if HEAD.match(line) or (line.strip().startswith("#") and not on):
            on = False
            continue

        if on and line.strip():
            out.append(line.strip())

    return out


def main():
    gap, root, note = sys.argv[1:4]
    bad = errors(gap)
    dropped = []

    for base, _, names in sorted(os.walk(root)):
        for name in sorted(names):
            if not name.endswith(".rules"):
                continue

            path = os.path.join(base, name)
            text = io.open(path, encoding="utf-8").read()
            head, blocks = split(text)

            if not blocks:
                continue

            keep = []

            for block in blocks:
                hit = next((line for line in inserted(block) if line in bad), None)

                if hit is None:
                    keep.append(block)
                    continue

                target = next((FILE.match(l).group(1) for l in block if FILE.match(l)), "?")
                dropped.append((os.path.relpath(path), target, hit, sorted(bad[hit])))

            if len(keep) == len(blocks):
                continue

            body = head + "\n" + "\n".join("\n".join(block) for block in keep)
            io.open(path, "w", encoding="utf-8", newline="\n").write(body)

    with io.open(note, "a", encoding="utf-8", newline="\n") as handle:
        for where, target, line, why in dropped:
            handle.write(f"{where}\t{target}\t{line[:90]}\t{', '.join(why)}\n")

    print(f"外した規則 {len(dropped)} -> {note}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
