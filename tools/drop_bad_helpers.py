# -*- coding: utf-8 -*-
"""通らない発火層のメソッドを外す。コンパイラの言うことを唯一の根拠にする。

    python tools/drop_bad_helpers.py <vanilla-gap.txt> <発火層のソース> \
        <規則の置き場> <外したものの控え>

`drop_bad_events.py` は「差し込む規則」を外す。こちらは**差し込まれる側**、
`src/event/java/dev/shifu/event/*.java` のメソッドを外す。

版を下げると、その版に無い Bukkit / Paper のイベントを呼ぶメソッドが残る
(`VaultChangeStateEvent` は 1.21、`ClientTickEndEvent` は Paper 1.21 の API)。
そのメソッドを呼ぶ規則も一緒に外さないと、差し込んだ先で未定義になる。

やること:

1. エラーの出た行がどのメソッドの中かを見る
2. そのメソッドを丸ごと外す(直前の注釈と注記も一緒に)
3. `dev.shifu.event.<クラス>.<メソッド>(` を呼ぶ規則を外す
4. 何をどの型のせいで外したかを控えに書く

**黙って消さない。** 控えを見れば、どの発火がどの版で落ちたかが残る。
外したメソッドを別のメソッドが呼んでいたら、そちらは次の回のエラーとして出る。
"""

import collections
import io
import os
import re
import sys

ERROR = re.compile(r"^(.+?\.java):(\d+): error: (.*)$")
SYMBOL = re.compile(r"^\s*symbol:\s+(?:class|method|variable)\s+([\w$]+)")
METHOD = re.compile(r"^    (?:public|private|protected|static|final|\s)*[\w.<>,?\[\]@ ]+\s(\w+)\(")
CALL = re.compile(r"dev\.shifu\.event\.(\w+)\.(\w+)\s*\(")


def failures(gap):
    """(発火層のファイル名, 行番号) と、その行のエラーの手掛かり。"""
    out = collections.defaultdict(set)
    reason = collections.defaultdict(set)
    current = None

    for raw in io.open(gap, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        match = ERROR.match(line)

        if match:
            rel = match.group(1).replace("\\", "/")
            current = None

            if "/dev/shifu/event/" in rel:
                current = (rel.rsplit("/", 1)[-1], int(match.group(2)))
                out[current[0]].add(current[1])
                reason[current].add(match.group(3).split(":")[0].strip())

            continue

        hit = SYMBOL.match(line)

        if hit and current:
            reason[current].add(hit.group(1))

    return out, reason


def spans(lines):
    """行番号(1 始まり)-> そのメソッドの名前、と (名前 -> 範囲)。"""
    owner = {}
    where = {}
    current = None
    start = 0
    depth = 0

    for number, text in enumerate(lines, 1):
        match = METHOD.match(text)

        if match and depth <= 1:
            current = match.group(1)
            start = number

            # 直前に続く注釈と注記もそのメソッドのもの。空行で止める
            back = number - 1

            while back >= 1 and lines[back - 1].strip().startswith(("//", "@", "*", "/*", "*/")):
                start = back
                back -= 1

        owner[number] = current
        depth += text.count("{") - text.count("}")

        if current and depth <= 1 and text.rstrip().endswith("}"):
            where[current] = (start, number)
            current = None

    return owner, where


def main():
    gap, src, rules_root, note = sys.argv[1:5]
    bad, reason = failures(gap)
    dropped = []

    for name in sorted(bad):
        path = os.path.join(src, name)

        if not os.path.exists(path):
            continue

        lines = io.open(path, encoding="utf-8").read().split("\n")
        owner, where = spans(lines)
        names = {owner.get(n) for n in bad[name]} - {None}
        cut = set()

        # メソッドの外(import と欄)。その版に無い型を指しているので落とす
        for number in sorted(bad[name]):
            if owner.get(number) is not None or number > len(lines):
                continue

            text = lines[number - 1].strip()

            if text.startswith("import "):
                cut.add(number)
                dropped.append((name[:-len(".java")], text,
                                sorted(reason[(name, number)])))
            elif text.endswith(";") and not text.startswith(("//", "*", "@")):
                cut.add(number)
                dropped.append((name[:-len(".java")], text.split("=")[0].strip(),
                                sorted(reason[(name, number)])))

        if not names and not cut:
            continue

        for method in sorted(names):
            if method not in where:
                continue

            start, end = where[method]
            cut.update(range(start, end + 1))
            why = sorted({w for n in bad[name] if owner.get(n) == method
                          for w in reason[(name, n)]})
            dropped.append((name[:-len(".java")], method, why))

        io.open(path, "w", encoding="utf-8", newline="\n").write(
            "\n".join(text for number, text in enumerate(lines, 1) if number not in cut))

    # そのメソッドを呼ぶ規則を外す
    gone = {(cls, method) for cls, method, _ in dropped}
    removed = 0

    for base, _, names in os.walk(rules_root):
        for name in sorted(names):
            if not name.endswith(".rules"):
                continue

            path = os.path.join(base, name)
            text = io.open(path, encoding="utf-8").read()
            blocks = text.split("\nfile:")

            if len(blocks) < 2:
                continue

            keep = [blocks[0]]

            for block in blocks[1:]:
                if any((cls, method) in gone for cls, method in CALL.findall(block)):
                    removed += 1
                    continue

                keep.append(block)

            if len(keep) != len(blocks):
                io.open(path, "w", encoding="utf-8", newline="\n").write("\nfile:".join(keep))

    with io.open(note, "a", encoding="utf-8", newline="\n") as handle:
        for cls, method, why in dropped:
            handle.write(f"{cls}.{method}\t{', '.join(why)}\n")

    print(f"外したメソッド {len(dropped)} / 呼んでいた規則 {removed} -> {note}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
