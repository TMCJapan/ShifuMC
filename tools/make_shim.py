"""アダプタ層が要求するメンバーだけを vanilla に足す。

Shifu の NMS は vanilla のまま置く。ただし `org.bukkit.craftbukkit.*` は
`Entity.bukkitEntity` のような状態を NMS 側に置く前提で書かれているので、
その置き場所だけを足す必要がある。

**既存の行は 1 行も触らない。** 要求されているメンバーの宣言だけを抜き出して、
それが属する型の本体の末尾に足す。Paper のパッチは宣言と本体の書き換えを
同じ hunk に混ぜて出すので、hunk 単位で取ると挙動変更まで入ってしまう。

    python tools/make_shim.py <patches/sources> <src/minecraft/java> <required-members.txt>
"""

import importlib.util
import os
import re
import sys

spec = importlib.util.spec_from_file_location(
    "fs", os.path.join(os.path.dirname(os.path.abspath(__file__)), "filter_sources.py"))
fs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fs)

ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")


def load_required(path):
    """要求されているメンバーの名前を集める。

    どのクラスのものかは見ない。エラーに出る型は呼び出し側の静的な型で、
    宣言している型とは限らない。`DedicatedServer.addLevel` は MinecraftServer の
    宣言だし、`Container.onOpen` は実装側の型に足す必要がある。
    名前で絞ると、その両方が落ちる。
    """
    names = set()

    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = ENTRY.match(line.rstrip("\n"))

            if match:
                names.add(match.group(2))

    return names


def declaration(line, names):
    """その行が要求されている名前を宣言していれば、その名前を返す。

    メソッドの中の局所変数もフィールドの形に見えるので、
    呼ぶ側が本体の外(波括弧の深さ 0)でだけ使うこと。
    """
    for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD):
        match = pattern.match(line)

        if not match or match.group(1) not in names:
            continue

        return match.group(1)

    return None


def depth_at(original, line):
    """元のファイルのその行の直前での波括弧の深さ。"""
    return sum(fs.line_deltas(original[:line]))


def members_in(original, bodies, hunk, names):
    """hunk が足している要素を (名前, 塊, 入る位置) で返す。

    メソッドの中の局所変数は要素の宣言と同じ形をしている。
    見分けるには深さを見るしかないので、元のファイルでの深さから始めて、
    文脈行も含めて数えながら進む。型の直下にある追加行だけが要素の宣言。
    """
    pos = max(hunk.start - 1, 0)
    depth = depth_at(original, pos)
    seq = [(text.startswith("+"), text[1:])
           for text in hunk.lines if text[:1] in (" ", "+")]
    found = []
    index = 0

    while index < len(seq):
        is_added, text = seq[index]

        # 追加行はその時点の位置に入る。位置と深さの両方が合う型の直下に
        # あるものだけが要素の宣言。位置だけで見ると、要素の境目で
        # 隣の入れ子の型を拾ってしまう。
        at_member_level = any(b[0] <= pos <= b[1] and b[2] == depth for b in bodies)
        name = declaration(text, names) if is_added and at_member_level else None

        if name is None:
            depth += fs.structure_delta([text])[0]

            if not is_added:
                pos += 1

            index += 1
            continue

        block = capture([t for _, t in seq[index:]], 0)
        span = seq[index:index + len(block)]
        index += len(block)
        depth += fs.structure_delta(block)[0]
        pos += sum(1 for added, _ in span if not added)

        # 途中に文脈行が混じる = 元からある行を巻き込む。足せない。
        if all(added for added, _ in span):
            found.append((name, block, pos))

    return found


def parens(lines):
    """丸括弧の増減。宣言が複数行に折り返されているかの判定に使う。"""
    total = 0

    for line in lines:
        text = fs.STRING.sub('""', line).split("//")[0]
        total += text.count("(") - text.count(")")

    return total


def indent(line):
    """字下げから、その要素が属する型の深さを見る。"""
    return (len(line) - len(line.lstrip())) // 4


def sound(block):
    """切り出した塊がそれだけで閉じているか。

    Paper のパッチは 1 つの要素を複数の hunk に分けて出すことがある。
    途中で切れた塊をそのまま足すと構文が壊れるので、閉じていないものは捨てて
    自前で書く対象に回す。
    """
    if not block:
        return False

    braces, comments, _ = fs.structure_delta(block)

    # if / else の釣り合いは見ない。要素の中で完結していれば足りる。
    if braces or comments or parens(block):
        return False

    text = fs.STRING.sub('""', block[-1]).split("//")[0].rstrip()

    return text.endswith((";", "}"))


def capture(added, start):
    """宣言の行から、その要素の終わりまでを切り出す。

    フィールドは `;` で終わる。メソッドや型は波括弧が閉じるまで。
    宣言そのものが複数行に折り返されていることがあるので、
    丸括弧が閉じるまでは終わりとみなさない。
    """
    block = []
    depth = 0
    open_parens = 0

    for index in range(start, len(added)):
        line = added[index]
        block.append(line)
        depth += fs.structure_delta([line])[0]
        open_parens += parens([line])

        if open_parens > 0 or depth != 0:
            continue

        text = fs.STRING.sub('""', line).split("//")[0].rstrip()

        if text.endswith((";", "}")):
            break

    return block



def is_type(lines, body, keywords):
    """その本体を開いている型の種類を見る。

    ヘッダーが複数行に折り返されていると、括弧が開く行にキーワードが無い。
    宣言の続きである行だけ遡って見る。
    """
    head = body[0]

    while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
        head -= 1

    words = " ".join(lines[head:body[0] + 1]).replace("(", " ").split()

    return any(word in words for word in keywords)


# 初期化子の無い欄の final。修飾子と注釈の後、型の前に来る。
EMPTY_FINAL = re.compile(
    r"^(\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:public\s+|protected\s+|private\s+)?)"
    r"final\s+(.*)$")


def unfinal(block):
    """初期化子の無い final の欄なら final を外して返す。

    Paper は足した欄を構築子の中で代入するが、vanilla の構築子は触らないので
    代入する場所が無い。javac は構築子 1 つにつき 1 件しか報告しないので、
    残したままだと 1 回のコンパイルで 1 件ずつしか見えない。

    final を外しても vanilla の挙動は変わらない。その欄は vanilla に無いものなので、
    vanilla のコードは 1 行も読まない。
    """
    if len(block) != 1:
        return block

    text = fs.STRING.sub('""', block[0]).split("//")[0]

    if "=" in text or "(" in text or not text.rstrip().endswith(";"):
        return block

    match = EMPTY_FINAL.match(block[0])

    return [match.group(1) + match.group(2)] if match else block


ARGS = re.compile(r"\(([^()]*)\)")
FINAL = re.compile(r"\bfinal\s+")
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



def signature(block):
    """既出を数える鍵。宣言の行そのものを正規化して使う。

    名前だけで数えると 1 つしか足せない形が 2 つある。

      * 状態と読み出しが同じ名前(`private boolean hasStopped` と
        `public boolean hasStopped()`)
      * 引数違いのオーバーロード(`knownCause()` と `knownCause(cause)`)

    Paper はどちらも普通に足してくるので、行の中身まで見る。
    """
    head = fs.STRING.sub('""', block[0]).split("//")[0]

    return " ".join(head.split())


def place(lines, bodies, line, block):
    """その要素を置く型の本体を選ぶ。

    基本は一番内側。ただし record の本体にインスタンスのフィールドは
    置けないので、その場合だけ 1 つ外側に移す。
    Paper のパッチの位置が入れ子の型の中を指していることがあるため。
    """
    is_field = (not any("{" in text for text in block)
                and block[-1].rstrip().endswith(";")
                and "static" not in block[0])

    # 塊は line の行の手前に入る。本体がその行から始まる型は、
    # 括弧が開く行の手前 = 型の外なので含めない。
    inside = sorted((b for b in bodies if b[0] < line <= b[1]),
                    key=lambda b: b[0], reverse=True)

    for body in inside:
        # interface のフィールドは暗黙に static なので置ける。
        # record は本体にインスタンスのフィールドを持てない。
        if is_field and is_type(lines, body, ("record",)):
            continue

        return body

    return None


def already(lines, body, name):
    """その名前がその型の直下に既にあるか。

    Paper は既にあるフィールドの可視性を広げるだけの変更も出す。
    それを追加として取ると同名が 2 つになる。
    メソッドの中の局所変数を数えないよう、字下げが直下のものだけを見る。
    """
    start, end, depth = body

    for line in lines[start:end + 1]:
        if indent(line) != depth:
            continue

        for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE):
            match = pattern.match(line)

            if match and match.group(1) == name:
                return True

    return False


IMPORT = re.compile(r"^import\s+(?:static\s+)?[\w.]+(?:\.\*)?;\s*(?://.*)?$")


def imports_in(hunks):
    """パッチが足している import。

    足した宣言は Paper が書いたままなので、Paper が同じパッチで足している
    import が要る。import を足しても、既にある行の意味は変わらない。
    同じ名前が二重に入れば javac がエラーにするので、黙って挙動が変わることはない。
    """
    found = []

    for hunk in sorted(hunks, key=lambda h: h.start):
        for line in hunk.added():
            text = line.strip()

            if IMPORT.match(text) and text not in found:
                found.append(text)

    return found


def import_spot(lines):
    """import を足す行。最後の import の次。import が無ければ package の次。"""
    spot = None

    for number, line in enumerate(lines):
        text = line.strip()

        if IMPORT.match(text) or text.startswith("package "):
            spot = number

    return spot + 1 if spot is not None else 0


ACCESS_NAME = re.compile(r"[\w$]+(?=\s*[;=(])")
HAND_MARK = re.compile(r"^// \S+\.([\w$]+)$", re.M)


def hand_written(root):
    """`patches/hand` で自前で書いてある要素を (対象, 名前) で集める。

    Paper の宣言をそのまま入れると閉じないもの(インターフェースの抽象メソッドが
    vanilla の無名クラスを壊す、など)は手で書き直す。同じ名前を両方から足すと
    宣言が 2 つになるので、手で書いた方を採る。
    """
    found = set()

    if not root or not os.path.isdir(root):
        return found

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".add"):
                continue

            path = os.path.join(base, name)
            target = os.path.relpath(path, root)[:-4].replace(os.sep, "/")

            with open(path, encoding="utf-8") as handle:
                for member in HAND_MARK.findall(handle.read()):
                    found.add((target, member))

    return found


def access_widened(root):
    """`patches/access` で可視性を広げる要素を (対象, 名前) で集める。

    そこに出てくるのは vanilla に既にある宣言なので、Paper のパッチから
    同じ名前を足すと宣言が 2 つになる。
    """
    found = set()

    if not root or not os.path.isdir(root):
        return found

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".rules"):
                continue

            target = None
            pending = False

            with open(os.path.join(base, name), encoding="utf-8") as handle:
                for raw in handle:
                    text = raw.strip()

                    if pending:
                        if not text:
                            continue

                        hit = ACCESS_NAME.search(text)

                        if hit:
                            found.add((target, hit.group(0)))

                        pending = False
                        continue

                    if text.startswith("file:"):
                        target = text[len("file:"):].strip()
                    elif text == "line:":
                        pending = True

    return found


# 宣言と見分けが付かない行。ここだけは名指しで外す。
# PacketProcessor の `final boolean isEmpty = ...` はメソッドの中の局所変数だが、
# 開き括弧が文脈行なので追加行だけを見ると要素の宣言と区別できない。
EXCLUDE = {
    ("net/minecraft/network/PacketProcessor.java", "isEmpty"),
}



def main():
    patch_root, tree, required = sys.argv[1:4]
    patches = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "patches")
    access_root = sys.argv[4] if len(sys.argv) > 4 else os.path.join(patches, "access")
    hand_root = sys.argv[5] if len(sys.argv) > 5 else os.path.join(patches, "hand")
    access = access_widened(access_root) | hand_written(hand_root)
    names = load_required(required)
    print(f"要求されている名前: {len(names)}")

    stats = {"files": 0, "members": 0, "missed": 0, "imports": 0}
    missed = []

    for target, hunks in fs.patches(patch_root):
        path, original = fs.source(tree, target)

        if not original:
            continue

        bodies = fs.type_bodies(original)
        taken = {}
        seen = set()
        added = set()
        rejected = {}

        for hunk in sorted(hunks, key=lambda h: h.start):
            for name, block, pos in members_in(original, bodies, hunk, names):
                mark = signature(block)

                if mark in seen:
                    continue

                body = place(original, bodies, pos, block)

                # access は可視性で扱うもの、hand は手で書いたもの。どちらも
                # ここから足すと同じ名前が 2 つになる。
                if ((target, name) in EXCLUDE or (target, name) in access
                        or not sound(block) or body is None
                        or already(original, body, name)):
                    # ここで既出にしない。同じ名前が別の hunk にも出ることがあり、
                    # そちらは取り出せる形をしているかもしれない。
                    rejected.setdefault(target, set()).add(name)
                    continue

                seen.add(mark)
                added.add(name)
                taken.setdefault(body[1], []).extend(unfinal(block))
                stats["members"] += 1

        for name in sorted(rejected.get(target, set()) - added):
            missed.append(f"{target} {name}")
            stats["missed"] += 1

        if not taken:
            continue

        here = {line.strip() for line in original}
        extra = [text for text in imports_in(hunks) if text not in here]

        if extra:
            taken.setdefault(import_spot(original), []).extend(extra)
            stats["imports"] += len(extra)

        stats["files"] += 1
        out = []

        for number, line in enumerate(original):
            if number in taken:
                out.append("    // Shifu - アダプタ層が要求する追加")
                out.extend(taken[number])

            out.append(line)

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(out))

    print(f"足したファイル  : {stats['files']}")
    print(f"足したメンバー  : {stats['members']}")
    print(f"足した import   : {stats['imports']}")
    print(f"置き場所が不明  : {stats['missed']}")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "docs", "backlog", "shim-todo.txt")

    with open(out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# Paper のパッチからそのまま取れなかった要素。自前で書く対象。\n")
        handle.write(f"# {len(missed)} 件\n\n")
        handle.write("\n".join(sorted(set(missed))) + "\n")

    print(f"-> {os.path.normpath(out)}")


if __name__ == "__main__":
    main()
