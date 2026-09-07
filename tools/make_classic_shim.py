# -*- coding: utf-8 -*-
"""Paper が NMS に足したメンバーを、宣言だけ取り出して `.add` に書き出す。

    python tools/make_classic_shim.py <Paper のソース> <vanilla の木> <出力先> \n        <required-members.txt> [--write]

`make_shim.py` は `patches/sources` の hunk を読む。1.21.4 より前(classic)には
それが無く、作っても **CraftBukkit が触る 550 件は逆コンパイラの方言が違う**ので、
追加行と方言の差が混ざって取り出せない。

こちらは差分を見ない。vanilla と Paper のクラスを**それぞれ読んで、型の直下にある
宣言だけを並べ、Paper にしか無いものを取る**。方言の差は本体と局所変数の名前に
出るので、宣言の並びには効かない。

鍵は「名前 + 引数の型」。引数の名前と `final` は落とす(版で変わるため)。

`make_shim.py` と同じく、**要求されているメンバーだけを足す**。
Paper が持っている宣言は 2614 件あるが、アダプタ層が要るのはその一部。
全部足すと vanilla ではなく Paper の NMS になる。

**元の行は 1 行も取らない。** 取るのは宣言と、その宣言に属する本体だけ。

宣言は Paper が書いたままなので、**Paper の側にしか無い import も要る**
(`CraftInventoryView`、`HumanEntity` など)。塊の中に出てくる名前に絞って、
vanilla のファイルの import の並びに足す。import を足しても既にある行の意味は
変わらない。同じ名前が二重に入れば javac が弾くので、黙って挙動が変わることはない。
"""

import collections
import importlib.util
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ms", os.path.join(HERE, "make_shim.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)
fs = ms.fs

# 宣言の行から名前を取る。make_shim が使っているものと同じ。
PATTERNS = (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD)
ARGS = re.compile(r"\(([^()]*)\)")
FINAL = re.compile(r"\bfinal\s+")
# 型だけを残す。`final ItemStack stack` -> `ItemStack`
ARG = re.compile(r"^(.*?[\w\]>])\s+\w+$")


ANNOTATION = re.compile(r"^\s*@[\w.]+(?:\([^()]*\))?\s*")


def head_of(block):
    """宣言の頭。折り返していれば繋いで、本体や初期化子の手前で切る。"""
    text = ""

    for line in block:
        clean = fs.STRING.sub('""', line).split("//")[0]
        text += " " + clean

        if "{" in clean or ";" in clean:
            break

    text = " ".join(text.split())
    cuts = [at for at in (text.find("{"), text.find(";")) if at >= 0]

    return text[:min(cuts)] if cuts else text


def key_of(name, block):
    """宣言の鍵。

    欄は名前だけ。**初期化子まで鍵にすると、逆コンパイラの方言の差
    (`1.0D` と `1.0`)で「Paper にしか無い」に化ける。**
    メソッドは名前と引数の型。引数の名前と `final` は版で変わるので落とす。
    """
    text = FINAL.sub("", head_of(block))

    while True:
        cut = ANNOTATION.match(text)

        if not cut:
            break

        text = text[cut.end():]

    equals = text.find("=")
    paren = text.find("(")

    # 初期化子の中の呼び出しをメソッドの引数と読まない
    if paren < 0 or (0 <= equals < paren):
        return name

    depth = 0

    for at in range(paren, len(text)):
        depth += (text[at] == "(") - (text[at] == ")")

        if depth == 0:
            break
    else:
        return name

    args = []

    for one in text[paren + 1:at].split(","):
        one = one.strip()

        if not one:
            continue

        hit = ARG.match(one)
        args.append((hit.group(1) if hit else one).replace(" ", ""))

    return f"{name}({','.join(args)})"


def members(lines):
    """(所有する型の名前, 鍵) -> 宣言の塊。型の直下にあるものだけ。

    3 つめに、そのファイルにある型の名前を返す。
    """
    bodies = fs.type_bodies(lines)
    out = {}
    order = []
    owners = set()

    for start, end, depth in bodies:
        owner = type_name(lines, start)
        owners.add(owner)
        number = start + 1

        while number <= end:
            line = lines[number]

            if ms.indent(line) != depth or not line.strip():
                number += 1
                continue

            name = None

            for pattern in PATTERNS:
                hit = pattern.match(line)

                if hit:
                    name = hit.group(1)
                    break

            if name is None:
                number += 1
                continue

            block = ms.capture(lines[number:], 0)

            if not block:
                number += 1
                continue

            key = (owner, key_of(name, block))

            if key not in out:
                out[key] = (name, block)
                order.append(key)

            number += len(block)

    return out, order, owners


TYPE_HEAD = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([\w$]+)")


WORD = re.compile(r"[A-Za-z_$][\w$]*")


def qualify(blocks, name, full):
    """塊の中の単純名を完全修飾に置き換える。直前が `.` の語は触らない。"""
    where = re.compile(r"(?<![\w$.])" + re.escape(name) + r"(?![\w$])")

    return [(owner, member, [where.sub(full, line) for line in block])
            for owner, member, block in blocks]


def imports_for(old, new, blocks):
    """足す塊が使っていて、vanilla のファイルに無い import。

    宣言は Paper が書いたままなので、Paper の側にしか無い型がそのまま出てくる
    (`CraftInventoryView`、`HumanEntity` など)。

    **その単純名が vanilla のファイルに既にあるときは import しない。**
    `Player` は Paper のファイルでは `org.bukkit.entity.Player`、vanilla では
    `net.minecraft.world.entity.player.Player` を指す。import を足すと
    **既にある行の意味が変わる**(`reference to Player is ambiguous`)。
    そこは塊の側を完全修飾に直す。

    返すのは (足す import, 直した塊)。
    """
    have = {line.strip() for line in old if ms.IMPORT.match(line.strip())}
    body = "\n".join(line for line in old if not ms.IMPORT.match(line.strip()))
    known = set(WORD.findall(body)) | {text.rstrip(";").rsplit(".", 1)[-1] for text in have}
    used = set()

    for _, _, block in blocks:
        for line in block:
            used.update(WORD.findall(fs.STRING.sub('""', line).split("//")[0]))

    out = []

    for line in new:
        text = line.strip()

        if not ms.IMPORT.match(text) or text in have:
            continue

        # `import a.b.C;` の C、`import static a.b.C.D;` の D
        name = text.rstrip(";").rsplit(".", 1)[-1]

        if name not in used or name == "*":
            continue

        if name in known:
            blocks = qualify(blocks, name, text.rstrip(";").split()[-1])
            continue

        if text not in out:
            out.append(text)

    return out, blocks


def type_name(lines, start):
    """本体を開いている型の名前。"""
    head = start

    while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
        head -= 1

    match = TYPE_HEAD.search(" ".join(lines[head:start + 1]))

    return match.group(1) if match else "(supertype)"


def main():
    paper, tree, dest, required = sys.argv[1:5]
    write = "--write" in sys.argv
    wanted = ms.load_required(required)
    files = 0
    total = 0
    skipped = 0
    imports = 0

    for base, _, names in os.walk(tree):
        for name in sorted(names):
            if not name.endswith(".java"):
                continue

            rel = os.path.relpath(os.path.join(base, name), tree).replace(os.sep, "/")
            other = os.path.join(paper, rel.replace("/", os.sep))

            if not os.path.exists(other):
                continue

            old = io.open(os.path.join(base, name), encoding="utf-8", errors="replace").read().split("\n")
            new = io.open(other, encoding="utf-8", errors="replace").read().split("\n")

            if old == new:
                continue

            have, _, mine = members(old)
            theirs, order, _ = members(new)
            blocks = []

            for key in order:
                if key in have:
                    continue

                owner, _ = key
                member, block = theirs[key]

                # Paper が足した入れ子の型の中身。型ごと足すので、
                # 中の宣言を別に足すと外側の型に入って壊れる
                if owner not in mine:
                    continue

                if member not in wanted:
                    skipped += 1
                    continue

                # 中に文脈が要るもの(内部クラスなど)は塊で取れない。
                # 宣言だけを足しても意味が無いので飛ばす
                if not ms.sound(block):
                    continue

                blocks.append((owner, member, ms.unfinal(block)))

            if not blocks:
                continue

            files += 1
            total += len(blocks)
            adds, blocks = imports_for(old, new, blocks)
            imports += len(adds)

            if not write:
                continue

            if adds:
                spot = ms.import_spot(old)
                io.open(os.path.join(base, name), "w", encoding="utf-8", newline="\n").write(
                    "\n".join(old[:spot] + adds + old[spot:]))

            out = os.path.join(dest, rel.replace("/", os.sep)) + ".add"
            os.makedirs(os.path.dirname(out), exist_ok=True)
            body = ["// Paper のソースから宣言だけを取ったもの。追加のみで、元の行は触らない。"]

            for owner, member, block in blocks:
                body.append("")
                body.append(f"// {owner}.{member}")
                body.extend(block)

            io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(body) + "\n")

    print(f"Paper にしか無い宣言: {total} 件 / {files} ファイル"
          f"(要求されていないので置かなかった分 {skipped} 件、足した import {imports} 件)"
          + ("" if write else " -- --write でまだ書いていない"))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
