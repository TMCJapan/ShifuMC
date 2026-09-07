# -*- coding: utf-8 -*-
"""逆コンパイルで消えた局所変数を戻す規則を作る。

    python tools/make_decompile_rules.py <コンパイル済みクラスの置き場> <mojmap の公式 jar>

公式 jar は 26.1 より前は難読化されているので、そのまま渡すとクラス名が当たらず
規則が 1 件も出ない。paperweight が codebook で名前を戻したものを渡すこと。

    <Paper>/paper-server/.gradle/caches/paperweight/taskCache/codebook-minecraft.jar

バイトコードは公式のままなので、局所変数の表も公式のものが入っている。

先に `SHIFU_NO_DECOMPILE=1 sh tools/once.sh` を通しておくこと。どのファイルを見るかは
ツリーの `git status`(Shifu が触ったファイル)で決めるので当ててある必要があり、
一方で `replace:` は vanilla の行そのものなので、前の回の locals.rules が
当たっていると自分の出力に当ててしまう。この 2 つを同時に満たすのが
「差し込みは当てる・decompile と expr は当てない」状態。

## なぜ要るか

Mojang の元のソースは値をいったん変数に入れてから `instanceof` で見ている。

    BlockEntity blockEntity = level.getBlockEntity(pos);
    if (blockEntity instanceof CampfireBlockEntity campfire) {

逆コンパイラはこれを 1 行にまとめる。意味は同じだが、**変数が 1 つ消える**。

    if (level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire) {

Fabric の MOD には、この変数を `@Local` で捕まえるものがある(Ledger の
CampfireBlockMixin など)。無いと `Found 0 candidate variables` で当たらず、
サーバーが起動しない。

## 何を作るか

`patches/decompile/locals.rules`。tools/patch_adapter.py の形式で、1 行を 2 行にする。
`once.sh` は git reset の直後に当てる。Shifu の差し込みはこの直したソースに対して
行うので、「vanilla の行を変えない」という決まりはそのまま保てる。

対象のメソッドは、class ファイルの行番号表で範囲を出して絞る。同じ形の行が他の
メソッドにもあるため。ファイルの中で一つに決まるまで、上の行を足して照合する。

## 作らない場合

* `else if` の条件(前に出すと、通らないはずの式を評価してしまう)
* `instanceof` が条件の先頭に無いもの(短絡の順が変わる)
* メソッドの中で候補が 1 つに決まらないもの
"""
import collections
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_lvt import method_lines, method_locals  # noqa: E402
from keep_vanilla_classes import class_prefixes, mojang_classes, owner_of, touched_sources  # noqa: E402

import paths

TREE = paths.TREE
OUT = os.path.join(paths.SHIFU, "patches", "decompile", "locals.rules")

LINE = re.compile(r"^(?P<indent>\s*)if \((?P<expr>.+?) instanceof (?P<type>[\w.$<>\[\], ]+?) (?P<name>\w+)(?P<tail>[)&|].*)$")
# 拡張 for も同じ。`for (T x : E)` の E を受ける変数が消える
LOOP = re.compile(r"^(?P<indent>\s*)for \((?:final )?(?P<type>[\w.$<>\[\], ?]+?) (?P<name>\w+) : (?P<expr>.+)\) \{\s*$")


def source_type(desc):
    """フィールド記述子 -> ソースに書ける型名。"""
    depth = 0

    while desc.startswith("["):
        depth += 1
        desc = desc[1:]

    if desc.startswith("L"):
        name = desc[1:-1].replace("/", ".").replace("$", ".")
    else:
        name = {"Z": "boolean", "B": "byte", "C": "char", "S": "short",
                "I": "int", "J": "long", "F": "float", "D": "double"}[desc]

    return name + "[]" * depth


def missing_locals(mine, theirs):
    """公式にあってこちらに無い局所変数のうち、次の slot がこちらにあるもの。

    同じ slot は使い回される(catch の変数と後ろの変数が同じ番号を取る)ので、
    slot だけで選ぶと別の変数を拾う。**受け側が始まる位置で生きているもの**
    だけを候補にする。生きていなければ、その変数はその文とは関係が無い。
    """
    have = {(n, d) for _, n, d, _, _ in mine}
    entries = sorted(theirs)
    out = []

    for i, (slot, name, desc, start, length) in enumerate(entries):
        if (name, desc) in have:
            continue

        # 次にこちらにもある変数を探す。拡張 for は iterator の分だけ番号が空く
        for other_slot, other_name, other_desc, other_start, _ in entries[i + 1:]:
            if other_slot <= slot or (other_name, other_desc) not in have:
                continue

            # 受け側(instanceof で束ねる変数)が始まる時点で、こちらも生きている。
            # 生きていなければ別の文の変数なので、次の候補を見る。
            if not start <= other_start < start + length:
                continue

            out.append((name, desc, other_name))
            break

    return out


def occurrences(stripped, block, text):
    """字下げを落とした行の並びが何回出てくるか。1 行なら部分一致でも数える。"""
    if len(block) == 1:
        return text.count(block[0])

    count = 0

    for i in range(len(stripped) - len(block) + 1):
        if stripped[i:i + len(block)] == block:
            count += 1

    return count


def rewritten(line, want, desc, after, skipped):
    """その行を「変数に受けてから」の形に書き直したもの。作れないときは None。"""
    found = LINE.match(line)

    if found:
        if "else" in line[:line.index("if (")]:
            skipped["形が合わない"] += 1

            return None

        expr = found.group("expr")

        if "&&" in expr or "||" in expr or expr.strip().startswith("!"):
            skipped["条件の先頭でない"] += 1

            return None

        indent = found.group("indent")

        return [
            "%s%s %s = %s;" % (indent, source_type(desc), want, expr),
            "%sif (%s instanceof %s %s%s" % (indent, want, found.group("type"), after, found.group("tail")),
        ]

    found = LOOP.match(line)

    if found:
        # 型は var に任せる。記述子は総称型を消したものになるので公式と合う
        indent = found.group("indent")

        return [
            "%svar %s = %s;" % (indent, want, found.group("expr")),
            "%sfor (%s %s : %s) {" % (indent, found.group("type"), after, want),
        ]

    skipped["形が合わない"] += 1

    return None


def make_rule(text, lines, at, want, desc, after, skipped):
    """1 件分の (照合する行, 置き換える行)。作れないときは None。"""
    line = lines[at]
    body = rewritten(line, want, desc, after, skipped)

    if body is None:
        return None

    if re.search(r"\b%s\b" % re.escape(want), line):
        skipped["同じ名前が既にある"] += 1

        return None

    # patch_adapter は字下げを落として照合する。数え方もそれに合わせる。
    # ファイルの中で一つに決まらなければ、上の行を足していく。
    # 規則の書式では空行が塊の終わりになるので、空行は跨がない
    stripped = [one.strip() for one in lines]
    top = at

    while occurrences(stripped, stripped[top:at + 1], text) > 1:
        if top == 0 or not stripped[top - 1]:
            skipped["一つに決まらない"] += 1

            return None

        top -= 1

    return lines[top:at + 1], lines[top:at] + body


def main():
    classes = sys.argv[1]
    jar = sys.argv[2]
    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    official = mojang_classes(jar)
    rules = []
    skipped = collections.Counter()

    for base, _, files in os.walk(classes):
        for name in files:
            if not name.endswith(".class"):
                continue

            rel = os.path.relpath(os.path.join(base, name), classes).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/") or owner_of(rel) not in keep or rel not in official:
                continue

            raw = io.open(os.path.join(base, name), "rb").read()
            mine = method_locals(raw)
            spans = method_lines(raw)
            theirs = method_locals(official[rel])
            source = owner_of(rel) + ".java"
            path = os.path.join(TREE, source)

            if not os.path.exists(path):
                continue

            text = io.open(path, encoding="utf-8").read()
            lines = text.split("\n")

            for key, entries in mine.items():
                if key not in theirs or key not in spans or not entries or not theirs[key]:
                    continue

                first, last = spans[key]

                for want, desc, after in missing_locals(entries, theirs[key]):
                    # after を宣言している行だけを拾う(使っているだけの行は拾わない)
                    declares = re.compile(r"instanceof [\w.$<>\[\], ]+ %s\b|for \((?:final )?[\w.$<>\[\], ?]+ %s :"
                                          % (re.escape(after), re.escape(after)))
                    hits = [n for n in range(first - 1, min(last, len(lines))) if declares.search(lines[n])]

                    if len(hits) != 1:
                        skipped["メソッド内に %d 件" % min(len(hits), 2)] += 1
                        continue

                    rule = make_rule(text, lines, hits[0], want, desc, after, skipped)

                    if rule:
                        rules.append((source, rule[0], rule[1]))

    seen = set()
    out = ["# 逆コンパイルで消えた局所変数を戻す。作り方は tools/make_decompile_rules.py",
           "#",
           "# Mojang の元のソースは値をいったん変数に入れてから instanceof で見ている。",
           "# 逆コンパイラが 1 行にまとめると変数が消え、@Local で捕まえる MOD が当たらない。",
           "# 式は同じ回数だけ、同じ順で評価されるので、挙動は変わらない。",
           ""]

    for source, old, new in rules:
        key = (source, "\n".join(old))

        if key in seen:
            continue

        seen.add(key)
        out.append("file: %s" % source.replace(os.sep, "/"))
        out.append("replace:")

        for line in old:
            out.append("    " + line.strip())

        out.append("with:")

        for line in new:
            out.append("    " + line.strip())

        out.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print("作った規則: %d" % len(seen))

    for reason, count in skipped.most_common():
        print("  見送り(%s): %d" % (reason, count))


if __name__ == "__main__":
    main()
