# -*- coding: utf-8 -*-
"""当たらなくなったアンカーに、別のバージョンの木での候補を出す。

    python tools/reanchor.py <規則の置き場> <木> [--min 0.8] [--write]

Minecraft のバージョンを移すと、規則のアンカー(vanilla の行そのもの)が
そのままでは当たらなくなる。同じ意味の行が少し形を変えて残っていることが多いので、
その候補を出す。

既定では**書き換えない**。当てる位置を機械が選ぶと、発火が別の場所に付いたことに
気付けなくなる。`--write` を付けたときだけ、次の 2 つに限って書き換える。

* 引数や局所変数の `final` の有無だけが違う(逆コンパイラの設定の差)
* 局所変数の名前だけが違う(型・メソッド名・演算子・リテラルは全て同じ)

どちらも差し込む本体が同じ変数を参照しているので、アンカーと本体の両方に
同じ対応を当てる。書き換えた対応は 1 件ずつ出るので、そこを読んで確かめる。
"""
import collections
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

# 逆コンパイラの版で付いたり付かなかったりする。`foo(final Player p)` のように
# 括弧に接する形があるので、空白区切りではなく語の境界で落とす。
FINAL = re.compile(r"\bfinal\b\s*")

# 目安で残す窓の数。ここに入らないほど語が重ならない窓が最も近い、ということは
# 起きない。同じ形の窓が 2 つあれば両方ともここに入るので、曖昧さの判定も落ちない。
KEEP = 60


def normalize(line):
    """比べる前に落とすもの。いまは修飾子の final だけ。"""
    return FINAL.sub("", line.strip())


def best_block(src, anchor):
    """木の中で、アンカーに最も近い同じ行数の連なりを返す。

    全部の窓に SequenceMatcher を掛けると規則の数だけ効いて遅いので、
    語の重なりで候補を絞ってから測る。
    """
    size = len(anchor)
    want = "\n".join(line.strip() for line in anchor)
    want_words = collections.Counter(WORD.findall(want))
    scored = []

    for start in range(len(src) - size + 1):
        text = "\n".join(line.strip() for line in src[start:start + size])

        if not text.strip():
            continue

        overlap = sum((want_words & collections.Counter(WORD.findall(text))).values())
        scored.append((overlap, start, text))

    scored.sort(key=lambda row: (-row[0], row[1]))
    best = (0.0, -1)
    ties = 0

    for _, start, text in scored[:KEEP]:
        ratio = difflib.SequenceMatcher(None, text, want).ratio()

        if ratio > best[0]:
            best = (ratio, start)
            ties = 1
        elif ratio == best[0]:
            ties += 1

    return best, ties


def rename_map(old_lines, new_lines):
    """2 つの行の並びが「final の有無」と「局所変数の名前」だけ違うなら、その対応を返す。

    それ以外(演算子・リテラル・型名・メソッド名が違う)なら None。
    局所変数の名前は逆コンパイラの版で変わるが、意味は変わらない。
    型とメソッドが変わっていたら意味が変わっているので、機械では触らない。
    """
    if len(old_lines) != len(new_lines):
        return None

    mapping = {}
    used = {}

    for old, new in zip(old_lines, new_lines):
        a = WORD.split(normalize(old))
        b = WORD.split(normalize(new))

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

    return mapping


def apply_names(line, mapping):
    """局所変数の名前だけを置き換える。

    直前が `.` の語は触らない。パッケージ名やメンバー名まで書き換えてしまうため
    (`dev.shifu.event` の `event` を別の名前にすると、その行はもう通らない)。
    """
    parts = WORD.split(line)

    for i in range(1, len(parts), 2):
        before = parts[i - 1]

        if before.endswith("."):
            continue

        parts[i] = mapping.get(parts[i], parts[i])

    return "".join(parts)


def rewrite(path, start, anchor_len, new_anchor, mapping):
    """規則ファイルの 1 件を書き換える。start は anchor: の行番号(1 起点)。"""
    lines = io.open(path, encoding="utf-8").read().split("\n")
    body_start = start
    body = []
    i = start

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
                continue

            if ties > 1:
                print(f"{rule.where}: 候補が {ties} 箇所 ({ratio:.2f}): {target}")
                print(f"    - {rule.anchor[0].strip()}")
                ambiguous += 1
                continue

            found = src[start:start + len(rule.anchor)]
            names = rename_map(rule.anchor, found)
            mark = ""

            if write and names is not None:
                name, _, no = rule.where.rpartition(":")

                if rewrite(os.path.join(rules_root, name), int(no), len(rule.anchor), found, names):
                    detail = ", ".join(f"{k}->{v}" for k, v in names.items()) or "final のみ"
                    mark = f" [書き換えた {detail}]"
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
