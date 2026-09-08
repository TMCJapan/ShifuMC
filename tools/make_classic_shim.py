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
# 型だけを残す。`final ItemStack stack` -> `ItemStack`
ARG = re.compile(r"^(.*?[\w\]>])\s+\w+$")


def tail(line):
    """1 行に並んだ 2 つめ以降の宣言。字下げを揃えて返す。"""
    text = fs.STRING.sub('""', line).split("//")[0]

    if text.count(";") < 2:
        return []

    lead = line[:len(line) - len(line.lstrip())]
    out = []
    at = text.find(";") + 1

    while at < len(text):
        end = text.find(";", at)

        if end < 0:
            break

        piece = line[at:end + 1].strip()

        if piece:
            out.append(lead + piece)

        at = end + 1

    return out


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

            # 宣言が折り返していると 1 行では `;` `=` `(` `{` まで届かない。
            # `public final ...ChunkDataController chunkDataControllerNew` のように
            # 名前で行が終わるものがある。続きを繋いでから見る。
            for probe in (line, line.rstrip() + " "
                          + " ".join(l.strip() for l in lines[number + 1:number + 3])):
                for pattern in PATTERNS:
                    hit = pattern.match(probe)

                    if hit:
                        name = hit.group(1)
                        break

                if name:
                    break

            if name is None:
                number += 1
                continue

            block = ms.capture(lines[number:], 0)

            if not block:
                number += 1
                continue

            key = (owner, ms.key_of(name, block))

            if key not in out:
                out[key] = (name, block)
                order.append(key)

            # **Paper は同じ行に宣言を並べる。** 差分を小さくするために
            # vanilla の宣言の後ろに足す
            # (`protected final ChunkPos chunkPos; public final long coordinateKey; ...`)。
            # 1 行 1 宣言で見ると、先頭の vanilla の宣言しか拾えない。
            if len(block) == 1:
                for extra in tail(block[0]):
                    hit = next((p.match(extra) for p in PATTERNS if p.match(extra)), None)

                    if not hit:
                        continue

                    more = (owner, ms.key_of(hit.group(1), [extra]))

                    if more not in out:
                        out[more] = (hit.group(1), [extra])
                        order.append(more)

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

        # `import a.b.C; // Paper` の C。Paper は import に注記を付ける
        head = text.split("//")[0].strip().rstrip(";")
        name = head.rsplit(".", 1)[-1]

        if name not in used or name == "*":
            continue

        if name in known:
            blocks = qualify(blocks, name, head.split()[-1])
            continue

        if text not in out:
            out.append(text)

    return out, blocks


OWNER = re.compile(r"^([\w$.<>()]+)\s+\(\d+\)$")
ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")
EXTENDS = re.compile(r"\b(?:class|interface|enum|record)\s+[\w$]+(?:<[^{]*?>)?\s*"
                     r"(?:extends\s+([\w$.<>,\s]+?))?\s*(?:implements\s+([\w$.<>,\s]+?))?\s*\{")


def required_owners(path):
    """要素の名前 -> それを要求している型の名前。

    `make_shim.load_required` は型を捨てる(エラーに出る型は呼び出し側の静的な型で、
    宣言している型とは限らないため)。**classic の生成器は Paper のソース全体から
    宣言を拾うので、型を捨てると同じ名前の無関係な宣言まで足してしまう。**
    型は残して、継承をたどって照合する。
    """
    out = collections.defaultdict(set)
    owner = None

    for line in io.open(path, encoding="utf-8"):
        text = line.rstrip("\n")
        hit = OWNER.match(text)

        if hit:
            owner = hit.group(1)
            continue

        hit = ENTRY.match(text)

        if hit and owner:
            out[hit.group(2)].add(owner)

    return out


def ancestors(tree):
    """型の単純名 -> 自分と、その上にある型の単純名。"""
    head = {}

    for base, _, names in os.walk(tree):
        for name in names:
            if not name.endswith(".java"):
                continue

            text = io.open(os.path.join(base, name), encoding="utf-8",
                           errors="replace").read()
            match = EXTENDS.search(text)
            up = set()

            for part in (match.group(1), match.group(2)) if match else ():
                for one in (part or "").split(","):
                    one = one.split("<")[0].strip().rsplit(".", 1)[-1]

                    if one:
                        up.add(one)

            head[name[:-len(".java")]] = up

    def walk(name, seen):
        if name in seen:
            return seen

        seen.add(name)

        for up in head.get(name, ()):
            walk(up, seen)

        return seen

    return {name: walk(name, set()) for name in head}


ASSIGN = re.compile(r"^\s*this\.([\w$]+)\s*=[^=]")
# **final で初期化子が付いた欄**だけ。構築子で代入し直せないのはこれだけで、
# メソッドの中の局所変数を数えると足せる構築子まで弾く
INITIALISED = re.compile(r"^\s+(?:@[\w.]+\s+)*(?:(?:public|protected|private|static|transient|volatile)\s+)*final\s+[\w$<>\[\],.?@]+\s+([\w$]+)\s*=")


def skips(path):
    """足してはいけない宣言。(相対パス, 所有クラス, 名前) の集合。"""
    out = set()

    if not path or not os.path.exists(path):
        return out

    for line in io.open(path, encoding="utf-8"):
        text = line.split("#")[0].strip()

        if not text:
            continue

        rel, _, member = text.partition(" ")
        owner, _, name = member.strip().rpartition(".")
        out.add((rel, owner, name))

    return out


def buildable(block, old):
    """その構築子を足せるか。

    `AABB(double, ..., boolean)` のように多重定義を足すだけなら足せる。
    **vanilla が宣言のところで値を入れている欄に代入する構築子は足せない。**
    Paper が引数を足して中身を書き直した構築子がそれで、そのまま足すと
    `cannot assign a value to final variable` になる(1.20.6 の ServerLevel で 15 件)。
    そこは patches/hand の担当。
    """
    ready = {match.group(1) for line in old for match in [INITIALISED.match(line)] if match}

    return not any(match.group(1) in ready
                   for line in block for match in [ASSIGN.match(line)] if match)


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
    asked = required_owners(required)
    above = ancestors(tree)
    skip = skips(os.path.join(os.path.dirname(HERE), "patches", "shim-skip.txt"))
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

            try:
                have, _, mine = members(old)
                theirs, order, _ = members(new)
            except RuntimeError as problem:
                # 正規表現の再帰が深くなりすぎるファイルがある。
                # そこだけ諦めて、どれかを言う
                print(f"  読めなかった: {rel} ({problem})", file=sys.stderr)
                continue

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

                # 要求している型か、その上にある型に足す。名前だけで見ると
                # 同じ名前の無関係な宣言まで足して、かえってエラーが増える
                # 型が分かっているものが 1 つでもあれば、それで絞る。
                # `(unqualified)`(型が読めなかったエラー)が混ざったときに
                # 絞りを外すと、同じ名前の宣言を全部足して 402 <-> 448 で振動する。
                #
                # ただし**要求元が NMS の型でないときは絞りに使えない**。
                # `CraftBlock` が `AABB` を要求していても、AABB は CraftBlock の
                # 上にはいない。木にある型だけを手掛かりにする。
                # 構築子は必ず自分の型に属するので、名前が型と同じなら通す。
                #
                # 照合は**両向き**。`CommandSource.getBukkitSender` が足りないという
                # エラーは、実装している `Entity` の側に足して直る。要求元が上にいる
                # ことも下にいることもある(1.20.6 で 145 件がこれだった)。
                who = {one for one in asked.get(member, set()) if one in above}

                # 入れ子の型(BlockStateBase など)は木の索引に無い。
                # 継承をたどれないので、そこは絞らない
                if (member != owner and who and owner in above and not any(
                        owner in above[one] or one in above.get(owner, {owner}) for one in who)):
                    skipped += 1
                    continue

                # 中に文脈が要るもの(内部クラスなど)は塊で取れない。
                # 宣言だけを足しても意味が無いので飛ばす
                if not ms.sound(block):
                    continue

                if member == owner and not buildable(block, old):
                    continue

                if (rel, owner, member) in skip:
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
