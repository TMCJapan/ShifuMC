"""まだ足りない要素を、自前の追加として書き出す。

Paper のパッチには宣言そのものは載っている。ただし本体まで一緒に書き換える
hunk の中にあるので、`make_shim.py` は塊として取り出せない。
ここでは追加行だけを、宣言から始めて連続している範囲で取る。
文脈行(元からある行)に当たった時点で打ち切る。**元の行は 1 行も取らない。**

閉じた塊になったものは `patches/shim/<パス>.add` に書き出す。
閉じなかったものは手で書く対象として `docs/backlog/by-hand.txt` に並べる。

    python tools/write_members.py <write-ourselves.txt> <patches/sources> <src/minecraft/java>
"""

import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("ms", os.path.join(HERE, "make_shim.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)
fs = ms.fs

ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")
OWNER = re.compile(r"^(\S+)\s+\((\d+)\)$")


def wanted(path):
    """名前 -> 所有クラス名の集合。"""
    owners = {}
    owner = None

    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            head = OWNER.match(line)

            if head:
                owner = head.group(1)
                continue

            entry = ENTRY.match(line)

            if entry and owner:
                owners.setdefault(entry.group(2), set()).add(owner)

    return owners


def contiguous(added_flags, texts, start):
    """宣言から、追加行だけで閉じるところまで取る。

    文脈行に当たったら打ち切る。元の行を巻き込まないため。
    """
    block = []
    depth = 0
    open_parens = 0

    for index in range(start, len(texts)):
        if not added_flags[index]:
            return None

        line = texts[index]
        block.append(line)
        depth += fs.structure_delta([line])[0]
        open_parens += ms.parens([line])

        if open_parens > 0 or depth != 0:
            continue

        text = fs.STRING.sub('""', line).split("//")[0].rstrip()

        if text.endswith((";", "}")):
            return block

    return None


def collect(patch_root, tree, names):
    """対象ファイル -> [(名前, 塊)]。閉じなかったものは別に返す。

    メソッドの中の局所変数は宣言と同じ形をしている。元のファイルでの深さから
    数えて、型の直下にある追加行だけを宣言とみなす。
    """
    taken = {}
    open_ended = []

    for target, hunks in fs.patches(patch_root):
        _, original = fs.source(tree, target)

        if not original:
            continue

        bodies = fs.type_bodies(original)
        seen = set()

        for hunk in sorted(hunks, key=lambda h: h.start):
            rows = [(text.startswith("+"), text[1:])
                    for text in hunk.lines if text[:1] in (" ", "+")]
            flags = [row[0] for row in rows]
            texts = [row[1] for row in rows]
            pos = max(hunk.start - 1, 0)
            depth = ms.depth_at(original, pos)

            for index, line in enumerate(texts):
                at_member_level = any(b[0] <= pos <= b[1] and b[2] == depth
                                      for b in bodies)
                name = (ms.declaration(line, names)
                        if flags[index] and at_member_level else None)

                if name is None or name in seen:
                    depth += fs.structure_delta([line])[0]

                    if not flags[index]:
                        pos += 1

                    continue

                block = contiguous(flags, texts, index)
                depth += fs.structure_delta([line])[0]

                if block is None:
                    open_ended.append((target, name))
                    continue

                seen.add(name)
                taken.setdefault(target, []).append((name, block))

    return taken, open_ended


def body_for(lines, owner):
    """その所有クラスの本体。見つからなければ一番外側。"""
    bodies = fs.type_bodies(lines)

    for body in bodies:
        if ms.is_type(lines, body, (owner,)):
            return body

    outer = [b for b in bodies if b[2] == 1]

    return outer[0] if outer else None


def by_hand(target, name):
    """その要素を `patches/hand` で自前で書いてあるか。

    書き出しは上書きなので、手で書いたものと同じ要素をここで出すと
    宣言が 2 つになる。手で書いた方を優先する。
    """
    path = os.path.join(ROOT, "patches", "hand", target.replace("/", os.sep) + ".add")

    if not os.path.exists(path):
        return False

    with open(path, encoding="utf-8") as handle:
        return re.search(r"^// \S+\.%s$" % re.escape(name), handle.read(), re.M) is not None


def main():
    missing, patch_root, tree = sys.argv[1:4]
    owners = wanted(missing)
    taken, open_ended = collect(patch_root, tree, set(owners))

    out_root = os.path.join(ROOT, "patches", "shim")
    written = 0
    files = 0

    for target, members in sorted(taken.items()):
        _, original = fs.source(tree, target)

        if not original:
            continue

        path = os.path.join(out_root, target.replace("/", os.sep) + ".add")
        os.makedirs(os.path.dirname(path), exist_ok=True)

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("// Paper のパッチから宣言だけを取ったもの。追加のみで、元の行は触らない。\n")

            for name, block in members:
                if by_hand(target, name):
                    continue

                owner = sorted(owners[name])[0]
                handle.write(f"\n// {owner}.{name}\n")
                handle.write("\n".join(block) + "\n")
                written += 1

        files += 1

    todo = os.path.join(ROOT, "docs", "backlog", "by-hand.txt")

    with open(todo, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# 追加行だけでは閉じない要素。本体を自分で書く。\n")
        handle.write(f"# {len(set(open_ended))} 件\n\n")

        for target, name in sorted(set(open_ended)):
            handle.write(f"{target} {name}\n")

    print(f"書き出した要素: {written} / {files} ファイル -> patches/shim")
    print(f"手で書く対象  : {len(set(open_ended))} 件 -> docs/backlog/by-hand.txt")


if __name__ == "__main__":
    main()
