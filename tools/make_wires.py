# -*- coding: utf-8 -*-
"""足したが誰も代入しない欄(docs/backlog/null-fields.txt)に、Paper と同じ代入を配線で足す規則を出す。

    python tools/make_wires.py docs/backlog/null-fields.txt <patches/sources> <出力先.rules> [--report]

Paper は vanilla の構築子やメソッドの中で `this.field = expr;` と代入している。Shifu は
vanilla の行を触らないので、その代入の直前にある vanilla の行(パッチの文脈行)を
アンカーにして、代入を `insert-after` で差し込む。出せる条件:

  * Paper の代入が 1 行の `this.field = expr;` で、同じハンクに vanilla の文脈行がある
  * 直前の文脈行が vanilla のファイルの中で 1 箇所に定まる(定まらなければ前の文脈行を足す)
  * expr に出てくる名前が、その vanilla のメソッドの中にある(Paper が足した局所変数を弾く)

出せないものは理由と一緒に一覧にする(--report)。
"""
import io
import os
import re
import subprocess
import time
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_block_events import HUNK_HEAD, method_window, known_names, vanilla_line_number, hunks

import paths

TREE = paths.TREE
BASE = paths.BASE
ASSIGN = re.compile(r"^\s*this\.(\w+)\s*=\s*(.+);\s*(?://.*)?$")
# メソッド呼び出しの名前(.foo( )も vanilla の中に無くて当然
CALL = re.compile(r"\.\w+\s*\(")
FQN = re.compile(r"\b(?:org|net|io|com|java|javax)(?:\.\w+)+")


def vanilla_of(path, cache={}):
    if path not in cache:
        cache[path] = None

        for _ in range(3):
            try:
                done = subprocess.run(["git", "show", "%s:%s" % (BASE, path)], cwd=TREE, capture_output=True)
            except OSError:
                # 負荷が高いと Windows がプロセスの生成を拒むことがある
                time.sleep(0.5)
                continue

            if done.returncode == 0:
                cache[path] = done.stdout.decode("utf-8")
                break

    return cache[path]


def read_fields(path):
    out = []

    for line in io.open(path, encoding="utf-8"):
        line = line.strip()

        if not line or line.startswith("#"):
            continue

        parts = line.split()

        if len(parts) >= 2 and parts[0].endswith(".java"):
            out.append((parts[0], parts[1]))

    return out


def context_anchor(hunk, index, vanilla_lines):
    """index(足した行)の直前にある文脈行から、vanilla の中で 1 箇所に定まるアンカーを作る。"""
    anchor = []
    at = index - 1
    stripped = [l.strip() for l in vanilla_lines]

    while at >= 0:
        line = hunk[at]

        if line[:1] == "+":
            # 直前が Paper の行なら、その前の文脈行までは同じ差し込み先。さらに遡る
            at -= 1
            continue

        if line[:1] == "-":
            return None

        if not line[1:].strip():
            at -= 1
            continue

        anchor.insert(0, line[1:])
        want = [a.strip() for a in anchor]
        hits = [i for i in range(len(stripped) - len(want) + 1) if stripped[i:i + len(want)] == want]

        if len(hits) == 1:
            return anchor

        if not hits:
            return None

        at -= 1

    return None


def main():
    fields = read_fields(sys.argv[1])
    sources = sys.argv[2]
    dest = sys.argv[3]
    report = "--report" in sys.argv

    wanted = {}

    for path, field in fields:
        wanted.setdefault(path, set()).add(field)

    out = []
    done = []
    skipped = []

    for path, names in sorted(wanted.items()):
        patch = os.path.join(sources, path + ".patch")

        if not os.path.exists(patch):
            for f in sorted(names):
                skipped.append((path, f, "Paper のパッチが無い"))
            continue

        vanilla = vanilla_of(path)

        if vanilla is None:
            for f in sorted(names):
                skipped.append((path, f, "vanilla に無い"))
            continue

        vanilla_lines = vanilla.split("\n")
        text = io.open(patch, encoding="utf-8").read()
        found = set()

        for start, hunk in hunks(text):
            for index, line in enumerate(hunk):
                if line[:1] != "+":
                    continue

                match = ASSIGN.match(line[1:])

                if not match or match.group(1) not in names:
                    continue

                field, expr = match.group(1), match.group(2)

                if field in found:
                    continue

                anchor = context_anchor(hunk, index, vanilla_lines)

                if anchor is None:
                    skipped.append((path, field, "直前の vanilla の行が 1 箇所に定まらない"))
                    found.add(field)
                    continue

                # vanilla のメソッドの中に expr の名前があるか
                number = vanilla_line_number(hunk, index, start)
                window = method_window(vanilla, anchor[-1], number - 1)

                # 右辺の完全修飾名(キャストや new の型)は vanilla に無くて当然なので外して見る
                bare = CALL.sub("(", FQN.sub("", expr))

                if not known_names(bare, window):
                    skipped.append((path, field, "代入の右辺に vanilla に無い名前がある: %s" % expr.strip()))
                    found.add(field)
                    continue

                found.add(field)
                done.append((path, field))
                out.append("# %s.%s(Paper が同じ位置で代入する)\nfile: %s\nanchor:\n%s\ninsert-after:\n    this.%s = %s;\n" % (
                    path.split("/")[-1][:-5], field, path, "\n".join("    " + a.strip() for a in anchor), field, expr.strip()))

        for f in sorted(names - found):
            skipped.append((path, f, "Paper のパッチに `this.%s = ...;` の 1 行の代入が無い" % f))

    with io.open(dest, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# 足したが誰も代入しない欄への配線。tools/make_wires.py が docs/backlog/null-fields.txt と\n"
                 "# Paper のパッチから出す。手で直さない。\n\n")
        fh.write("\n".join(out))

    print("出した配線: %d 件 -> %s" % (len(done), dest))
    print("出せなかった: %d 件" % len(skipped))

    if report:
        # 他のファイルのパッチも含めて、その欄に代入している Paper の行を探す(受け手は問わない)
        all_patches = {}

        for root, _, files in os.walk(sources):
            for f in files:
                if f.endswith(".java.patch"):
                    rel = os.path.relpath(os.path.join(root, f), sources).replace("\\", "/")
                    all_patches[rel] = io.open(os.path.join(root, f), encoding="utf-8").read().split(chr(10))

        for path, field, why in skipped:
            print("  %s %s: %s" % (path.split("/")[-1], field, why))
            pat = re.compile(r"^\+\s*[\w.()]*\b%s\s*=[^=]" % re.escape(field))

            for rel, lines in sorted(all_patches.items()):
                for line in lines:
                    if pat.match(line):
                        print("      %s: %s" % (rel[:-6].split("/")[-1], line[1:].strip()[:110]))


if __name__ == "__main__":
    main()
