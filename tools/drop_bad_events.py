# -*- coding: utf-8 -*-
"""通らない発火の規則を外す。コンパイラの言うことを唯一の根拠にする。

`make_events.py` は Paper のパッチの「追加だけの塊」を機械的に規則にする。
その中には単独では成り立たないものがある。Paper が同じメソッドの別の場所で
宣言した局所変数を使っていたり、vanilla の局所変数が `final` で代入できな
かったりする。そういうものは「vanilla の行を書き換えないと入らない」ので、
Shifu の作りでは入れられない。

    python tools/drop_bad_events.py <vanilla-gap.txt> <patches/events/generated> \
        <外したものの控え>

やること: エラーの出た行の中身を、規則が差し込む行の中から探す。
見つかった規則を `.rules` から抜き、控えに理由ごと書き出す。
**黙って消さない。** 控えを見れば、どの発火がどの理由で入らなかったかが残る。
"""
import io
import os
import re
import sys
import collections

ERROR = re.compile(r"^(.+?\.java):(\d+): error: (.*)$")
# 突き合わせの手掛かりにならない行。括弧だけの行と注釈だけの行。
# `} else { inflate = false; }` のように括弧で始まっても中身のある行は残す。
NOISE = re.compile(r"^\s*(?:[{}();]+|//.*|/\*.*|\*.*|@\w+)\s*$")


def errors(path):
    """(ファイル名, 行番号, 種類, その行の中身) の並び。"""
    lines = io.open(path, encoding="utf-8", errors="replace").read().split("\n")
    out = []

    for i, line in enumerate(lines):
        hit = ERROR.match(line.strip())

        if not hit:
            continue

        body = lines[i + 1] if i + 1 < len(lines) else ""
        out.append((os.path.basename(hit.group(1)), int(hit.group(2)),
                    hit.group(3), body.strip()))

    return out


def rules_of(text):
    """規則ファイルを、頭書きと規則の塊に切る。"""
    parts = re.split(r"\n(?=# )", text)
    head = []
    blocks = []

    for part in parts:
        if "\nfile:" in part or part.startswith("file:"):
            blocks.append(part)
        else:
            head.append(part)

    return "\n".join(head), blocks


def inserted(block):
    """その規則が差し込む行の中身。"""
    out = []
    taking = False

    for line in block.split("\n"):
        key = line.strip().partition(":")[0]

        if key in ("insert", "insert-after"):
            taking = True
            continue

        if key in ("file", "anchor"):
            taking = False
            continue

        if taking and line.strip():
            out.append(line.strip())

    return out


MARK = "// Shifu - "


def near_mark(tree, java, number, index):
    """エラーの行の近くにある差し込みの印から規則を探す。

    行の中身がそのまま一致しないことがある。差し込みが vanilla の局所変数と
    同じ名前を作ってしまったときは、コンパイラは**あとに来る vanilla の行**を
    指す。ラムダの中身を書き換えたときは、外側の呼び出しの行を指す。
    どちらも差し込んだ行そのものではないので、印を手掛かりにする。
    """
    path = os.path.join(tree, java)

    if not os.path.exists(path):
        found = [os.path.join(base, name)
                 for base, _, names in os.walk(tree) for name in names
                 if name == java]

        if len(found) != 1:
            return []

        path = found[0]

    lines = io.open(path, encoding="utf-8", errors="replace").read().split("\n")
    at = number - 1

    for start in sorted(range(max(0, at - 40), min(len(lines), at + 10)),
                        key=lambda i: abs(i - at)):
        if MARK not in lines[start]:
            continue

        for line in lines[start + 1:start + 12]:
            hits = index.get(line.strip())

            if hits:
                return hits

    return []


def main():
    gap, generated, note = sys.argv[1:4]
    tree = sys.argv[4] if len(sys.argv) > 4 else None
    bad = errors(gap)

    index = collections.defaultdict(list)
    files = {}

    for name in sorted(os.listdir(generated)):
        if not name.endswith(".rules"):
            continue

        path = os.path.join(generated, name)
        text = io.open(path, encoding="utf-8").read()
        head, blocks = rules_of(text)
        files[path] = [head, blocks]

        for at, block in enumerate(blocks):
            for line in inserted(block):
                if not NOISE.match(line):
                    index[line].append((path, at))

    doomed = {}
    unmatched = []

    for java, number, why, body in bad:
        hits = index.get(body, [])

        if len(hits) != 1:
            hits = [h for h in hits
                    if os.path.basename(h[0]).endswith(
                        java[:-len(".java")] + ".rules")] or hits

        if not hits and tree:
            hits = near_mark(tree, java, number, index)

        if not hits:
            unmatched.append((java, number, why, body))
            continue

        for hit in hits:
            doomed.setdefault(hit, []).append("%s:%d %s" % (java, number, why))

    kept = 0
    removed = 0
    lines = ["# 入れられなかった発火。tools/drop_bad_events.py が外したもの。",
             "#",
             "# どれも「vanilla の行を書き換えないと入らない」もの。",
             "# Paper が同じメソッドの別の場所で作った局所変数を使う、",
             "# vanilla の final な局所変数に代入する、などが理由。", ""]

    for path, (head, blocks) in sorted(files.items()):
        out = []

        for at, block in enumerate(blocks):
            if (path, at) in doomed:
                removed += 1
                lines.append("=== %s" % os.path.basename(path))

                for reason in doomed[(path, at)]:
                    lines.append("  理由: %s" % reason)

                lines.extend("  " + l for l in block.rstrip().split("\n"))
                lines.append("")
                continue

            out.append(block)
            kept += 1

        io.open(path, "w", encoding="utf-8", newline="\n").write(
            head.rstrip() + "\n\n" + "\n".join(out) if out else head.rstrip() + "\n")

        if not out:
            os.remove(path)

    io.open(note, "a" if os.path.exists(note) else "w",
            encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")

    print("外した規則 %d / 残した %d" % (removed, kept))
    print("控え", note)

    if unmatched:
        print()
        print("規則に結び付かなかったエラー %d 件:" % len(unmatched))

        for java, number, why, body in unmatched[:20]:
            print("  %s:%d %s" % (java, number, why))
            print("      %s" % body)

    return 0


if __name__ == "__main__":
    sys.exit(main())
