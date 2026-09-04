# -*- coding: utf-8 -*-
"""規則のアンカーが vanilla の木の中で定まるかを、パイプラインを回さずに確かめる。

    python tools/check_events.py [patches/events patches/wire ...]

引数が無ければ patches/events と patches/wire を見る。アンカーは vanilla の行なので、
パッチ適用前のコミット(tools/paths.py の BASE)を `git show` で読んで探す。shim や発火で足した行は
見ないので、当てた木を見るより厳しく、そして速い(コンパイル無しで数秒)。

見つからない・複数ある規則を 1 つずつ出し、あれば終了コード 1。
"""
import glob
import io
import os
import subprocess
import time
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apply_events

import paths

TREE = paths.TREE
BASE = paths.BASE
ADDS = ["patches/hand", "patches/shim"]


def vanilla_of(path, cache={}):
    if path not in cache:
        cache[path] = None

        # MSYS の下で子プロセスがまれに落ちるので 3 回まで
        for _ in range(3):
            try:
                done = subprocess.run(["git", "show", "%s:%s" % (BASE, path)], cwd=TREE, capture_output=True)
            except OSError:
                # 負荷が高いと Windows がプロセスの生成を拒むことがある
                time.sleep(0.5)
                continue

            if done.returncode == 0:
                cache[path] = done.stdout.decode("utf-8").split("\n")
                break

            if b"does not exist" in done.stderr or b"exists on disk" in done.stderr:
                break

    return cache[path]


def with_adds(path, lines, cache={}):
    """vanilla の行に、その型へ足す hand と shim の行を継ぎ足したもの。

    差し込みが当たるのは追加を当てたあとの木なので、hand が vanilla のメソッドの本体を
    写していると、vanilla では 1 箇所のアンカーが 2 箇所になる(AbstractThrownPotion で踏んだ)。
    """
    if path not in cache:
        extra = []

        for base in ADDS:
            add = os.path.join(base, path + ".add")

            if os.path.exists(add):
                extra.extend(io.open(add, encoding="utf-8").read().split("\n"))

        cache[path] = lines + extra

    return cache[path]


def main():
    dirs = sys.argv[1:] or ["patches/events", "patches/wire"]
    bad = 0
    total = 0

    for d in dirs:
        for f in sorted(glob.glob(os.path.join(d, "*.rules"))):
            text = io.open(f, encoding="utf-8").read()

            try:
                rules = apply_events.parse(text, f)
            except ValueError as e:
                print("読めない: %s" % e)
                bad += 1
                continue

            for rule in rules:
                total += 1
                lines = vanilla_of(rule.target)

                if lines is None:
                    print("%s: %s は vanilla に無い" % (rule.where, rule.target))
                    bad += 1
                    continue

                try:
                    apply_events.find_all(lines, rule.anchor, rule.count)
                except LookupError as e:
                    print("%s: %s: %s\n    %s" % (rule.where, rule.target, e, rule.anchor[0].strip()))
                    bad += 1
                    continue

                try:
                    apply_events.find_all(with_adds(rule.target, lines), rule.anchor, rule.count)
                except LookupError as e:
                    print("%s: %s: hand か shim を足すと %s\n    %s" % (rule.where, rule.target, e, rule.anchor[0].strip()))
                    bad += 1

    print("規則 %d 件、定まらない %d 件" % (total, bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
