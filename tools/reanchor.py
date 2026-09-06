# -*- coding: utf-8 -*-
"""当たらなくなったアンカーに、別のバージョンの木での候補を出す。

    python tools/reanchor.py <規則の置き場> <木> [--min 0.8] [--write]

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
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from apply_events import parse


JAVA_WORDS = {
    "if", "else", "for", "while", "return", "new", "this", "super", "null",
    "true", "false", "int", "long", "float", "double", "boolean", "char",
    "byte", "short", "void", "final", "static", "public", "private",
    "protected", "instanceof", "switch", "case", "break", "continue", "throw",
    "try", "catch", "finally", "class", "var",
}

WORD = re.compile("([A-Za-z_$][A-Za-z0-9_$]*)")


def rename_map(old_lines, new_lines):
    """2 つの行の並びが「識別子の名前だけ」違うなら、その対応を返す。

    それ以外(演算子・リテラル・型名・メソッド名が違う)なら None。
    局所変数の名前は逆コンパイラの版で変わるが、意味は変わらない。
    型とメソッドが変わっていたら意味が変わっているので、機械では触らない。
    """
    if len(old_lines) != len(new_lines):
        return None

    mapping = {}
    used = {}

    for old, new in zip(old_lines, new_lines):
        a = WORD.split(old.strip())
        b = WORD.split(new.strip())

        if len(a) != len(b):
            return None

        for i, (x, y) in enumerate(zip(a, b)):
            if x == y:
                continue

            # 識別子でない場所(演算子・区切り・リテラル)が違う
            if i % 2 == 0 or not WORD.fullmatch(x) or not WORD.fullmatch(y):
                return None

            # 型・定数(大文字始まり)とメソッド名(直後が開き括弧)は触らない
            if x[:1].isupper() or y[:1].isupper():
                return None

            if x in JAVA_WORDS or y in JAVA_WORDS:
                return None

            if i + 1 < len(a) and a[i + 1].lstrip().startswith("("):
                return None

            if mapping.setdefault(x, y) != y or used.setdefault(y, x) != x:
                return None

    return mapping or None


def apply_names(line, mapping):
    parts = WORD.split(line)

    for i in range(1, len(parts), 2):
        parts[i] = mapping.get(parts[i], parts[i])

    return "".join(parts)


def rewrite(path, start, anchor_len, new_anchor, mapping):
    """規則ファイルの 1 件を書き換える。start は anchor: の行番号(1 起点)。"""
    text = io.open(path, encoding="utf-8").read()
    lines = text.split("\n")
    i = start          # anchor: の次の行(0 起点で start)
    body_start = i
    body = []

    while i < len(lines) and (lines[i].startswith("    ") or not lines[i].strip()):
        body.append(lines[i])
        i += 1

    while body and not body[-1].strip():
        body.pop()

    if len(body) != anchor_len:
        return False

    lines[body_start:body_start + len(body)] = ["    " + one.strip() for one in new_anchor]
    i = body_start + len(new_anchor)

    # 続く insert / insert-after の本体にも同じ対応を当てる
    while i < len(lines):
        head = lines[i].strip()

        if head in ("insert:", "insert-after:"):
            i += 1

            while i < len(lines) and (lines[i].startswith("    ") or not lines[i].strip()):
                lines[i] = apply_names(lines[i], mapping)
                i += 1

            continue

        if head.startswith("count:"):
            i += 1
            continue

        break

    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    return True


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
    write = "--write" in sys.argv

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

    proposed = missing = ambiguous = weak = ok = written = 0

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
                found = src[start:start + len(rule.anchor)]
                names = rename_map(rule.anchor, found)
                mark = ""

                if write and names:
                    name, _, no = rule.where.rpartition(":")
                    if rewrite(os.path.join(rules_root, name), int(no), len(rule.anchor), found, names):
                        mark = " [書き換えた " + ", ".join(f"{k}->{v}" for k, v in names.items()) + "]"
                        written += 1

                print(f"{rule.where}: {ratio:.2f} {target}:{start + 1}{mark}")

                for old, new in zip(rule.anchor, found):
                    print(f"    - {old.strip()}")
                    print(f"    + {new.strip()}")

                proposed += 1

    print(f"当たる {ok} / 候補あり {proposed} / 候補が定まらない {ambiguous} / "
          f"近い行が無い {weak} / ファイルが無い {missing}"
          + (f" / 書き換えた {written}" if write else ""), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
