# -*- coding: utf-8 -*-
"""当たらなくなったアンカーに、別のバージョンの木での候補を出す。

    python tools/reanchor.py <規則の置き場> <木> [--min 0.8]

Minecraft のバージョンを移すと、規則のアンカー(vanilla の行そのもの)が
そのままでは当たらなくなる。同じ意味の行が少し形を変えて残っていることが多いので、
その候補を出す。**書き換えはしない。** 当たる位置を機械が選ぶと、
発火が別の場所に付いたことに気付けなくなる。

出るのは規則 1 件につき 1 行と、その下に差分:

    <規則ファイル>:<行> <一致度> <対象>:<行番号>
        - もとのアンカー
        + 候補の行

候補は、木の中で「アンカーと同じ行数の連なり」を総当たりして、一致度が最も高いものを
1 つ選ぶ。同じ一致度が 2 箇所以上あるときは出さない(どちらか分からないため)。
"""
import difflib
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from apply_events import parse


def best_block(src, anchor):
    """木の中で、アンカーに最も近い同じ行数の連なりを返す。"""
    want = "\n".join(line.strip() for line in anchor)
    size = len(anchor)
    matcher = difflib.SequenceMatcher()
    matcher.set_seq2(want)
    best = (0.0, -1)
    ties = 0

    for start in range(len(src) - size + 1):
        have = "\n".join(line.strip() for line in src[start:start + size])

        if not have.strip():
            continue

        matcher.set_seq1(have)

        if matcher.real_quick_ratio() < best[0] or matcher.quick_ratio() < best[0]:
            continue

        ratio = matcher.ratio()

        if ratio > best[0]:
            best = (ratio, start)
            ties = 1
        elif ratio == best[0]:
            ties += 1

    return best, ties


def main():
    rules_root, tree = sys.argv[1:3]
    floor = 0.8

    if "--min" in sys.argv:
        floor = float(sys.argv[sys.argv.index("--min") + 1])

    by_file = {}

    for base, _, files in os.walk(rules_root):
        for name in sorted(files):
            if not name.endswith(".rules"):
                continue

            with io.open(os.path.join(base, name), encoding="utf-8") as handle:
                for rule in parse(handle.read(), name):
                    by_file.setdefault(rule.target, []).append(rule)

    proposed = missing = ambiguous = weak = ok = 0

    for target, rules in sorted(by_file.items()):
        path = os.path.join(tree, target.replace("/", os.sep))

        if not os.path.isfile(path):
            for rule in rules:
                print(f"{rule.where}: ファイルが無い: {target}")
                missing += 1

            continue

        with io.open(path, encoding="utf-8") as handle:
            src = handle.read().split("\n")

        stripped = [line.strip() for line in src]

        for rule in rules:
            want = [line.strip() for line in rule.anchor]
            hits = [i for i in range(len(stripped) - len(want) + 1)
                    if stripped[i:i + len(want)] == want]

            if len(hits) == rule.count:
                ok += 1
                continue

            (ratio, start), ties = best_block(src, rule.anchor)

            if ratio < floor:
                print(f"{rule.where}: 近い行が無い ({ratio:.2f}): {target}")
                print(f"    - {rule.anchor[0].strip()}")
                weak += 1
            elif ties > 1:
                print(f"{rule.where}: 候補が {ties} 箇所 ({ratio:.2f}): {target}")
                print(f"    - {rule.anchor[0].strip()}")
                ambiguous += 1
            else:
                print(f"{rule.where}: {ratio:.2f} {target}:{start + 1}")

                for old, new in zip(rule.anchor, src[start:start + len(rule.anchor)]):
                    print(f"    - {old.strip()}")
                    print(f"    + {new.strip()}")

                proposed += 1

    print(f"当たる {ok} / 候補あり {proposed} / 候補が定まらない {ambiguous} / "
          f"近い行が無い {weak} / ファイルが無い {missing}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
