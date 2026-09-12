"""`patches/shim/**.add` を vanilla のソースに足す。

`.add` は「その型の本体の末尾に足すコード」の並び。各塊の前に
`// <所有クラス>.<名前>` の目印が付いている。目印を見て、その型の本体の
閉じ括弧の直前に入れる。**元の行は 1 行も触らない。**

    python tools/apply_shim_adds.py <patches/shim> <src/minecraft/java> [<譲る先> [<patches/access>]]

3 つめを渡すと、そこに同じ名前の要素があるものは足さない。`patches/shim` は
生成物で、`patches/hand` は手で書いたもの。同じ名前を両方から足すと宣言が 2 つになる。

4 つめに `patches/access` を渡すと、可視性を広げる要素も足さない。
それは vanilla に既にある宣言の修飾子を変えたものなので、Paper 側から
同じ名前を足すと宣言が 2 つになる。
"""

import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ms", os.path.join(HERE, "make_shim.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)
fs = ms.fs

MARK = re.compile(r"^// (\S+)\.([\w$]+)$")


def parse(text):
    """[((所有クラス, 名前), 足す行の並び)]。

    **辞書にしない。** 同じ名前の欄とメソッドが両方ある型がある
    (`DamageSource.directBlock` は `org.bukkit.block.Block` の欄と、
    それを入れて自分を返すメソッド)。辞書にすると後の 1 つしか残らない。
    """
    groups = []
    block = None

    for line in text.split("\n"):
        mark = MARK.match(line.strip())

        if mark:
            block = ["", f"    // Shifu - {mark.group(2)}"]
            groups.append(((mark.group(1), mark.group(2)), block))
            continue

        if block is None or line.startswith("//"):
            continue

        block.append(line)

    return groups


def existing(lines, body, name):
    """その型の直下に既にある、その名前の宣言の鍵。

    鍵は `make_shim.key_of` と同じ「名前 + 引数の型」。
    メソッドの中の局所変数を数えないよう、字下げが直下のものだけを見る。
    """
    start, end, depth = body
    out = set()
    number = start

    while number <= end:
        line = lines[number]

        if ms.indent(line) != depth or not line.strip():
            number += 1
            continue

        for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD):
            match = pattern.match(line)

            if match and match.group(1) == name:
                out.add(ms.key_of(name, lines[number:number + 4]))
                break

        number += 1

    return out


NAMED = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([\w$]+)")


def named_as(lines, body, owner):
    """その本体を開いている型の名前が owner か。

    `ms.is_type` は語で見るので、`record Data<T>(...)` は `Data<T>` になって
    `Data` に一致しない。入れ子の型が見つからないと一番外側に入って、
    同じ名前のメソッドが二重になる。
    """
    head = body[0]

    while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
        head -= 1

    # 前の行から続きを繋ぐと外側の型の宣言まで入るので、**最後の宣言**を見る
    found = NAMED.findall(" ".join(lines[head:body[0] + 1]))

    return bool(found) and found[-1] == owner


def body_for(lines, owner):
    """その所有クラスの本体。見つからなければ一番外側。

    まず宣言している名前で厳密に探す。`(supertype)` のように型の名前でない
    目印もあるので、見つからなければ語で探す従来の見方に落とす。
    """
    bodies = fs.type_bodies(lines)

    for body in bodies:
        if named_as(lines, body, owner):
            return body

    for body in bodies:
        if ms.is_type(lines, body, (owner,)):
            return body

    outer = [b for b in bodies if b[2] == 1]

    return outer[0] if outer else None


def defined_in(root, target, name):
    """その要素が別の場所で書かれているか。"""
    if not root:
        return False

    path = os.path.join(root, target.replace("/", os.sep) + ".add")

    if not os.path.exists(path):
        return False

    with open(path, encoding="utf-8") as handle:
        return re.search(r"^// \S+\.%s$" % re.escape(name), handle.read(), re.M) is not None


NAME = re.compile(r"[\w$]+(?=\s*[;=(])")


def widened(access_root):
    """`patches/access` で可視性を広げる要素を (対象, 名前) で集める。

    その要素は vanilla に既にあって修飾子だけを変えたものなので、
    Paper のパッチから同じ名前を足すと宣言が 2 つになる。
    """
    found = set()

    if not access_root or not os.path.isdir(access_root):
        return found

    for base, _, names in os.walk(access_root):
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

                        hit = NAME.search(text)

                        if hit:
                            found.add((target, hit.group(0)))

                        pending = False
                        continue

                    if text.startswith("file:"):
                        target = text[len("file:"):].strip()
                    elif text == "line:":
                        pending = True

    return found


def main():
    add_root, tree = sys.argv[1:3]
    yield_to = sys.argv[3] if len(sys.argv) > 3 else None
    access = widened(sys.argv[4] if len(sys.argv) > 4 else None)
    files = 0
    members = 0

    for base, _, names in os.walk(add_root):
        for name in sorted(names):
            if not name.endswith(".add"):
                continue

            add_path = os.path.join(base, name)
            target = os.path.relpath(add_path, add_root)[:-4].replace(os.sep, "/")
            path = os.path.join(tree, target.replace("/", os.sep))

            if not os.path.exists(path):
                print(f"{target}: 元のファイルが無い", file=sys.stderr)
                continue

            with open(path, encoding="utf-8") as handle:
                lines = handle.read().split("\n")

            with open(add_path, encoding="utf-8") as handle:
                groups = parse(handle.read())

            inserts = {}

            for (owner, member), block in groups:
                if defined_in(yield_to, target, member) or (target, member) in access:
                    continue

                body = body_for(lines, owner)

                if body is None:
                    print(f"{target}: {owner} の本体が無い", file=sys.stderr)
                    continue

                # shim が既に足していれば二重になる。名前で見ると、
                # 引数だけ違う版を「既にある」と誤って弾いてしまう。
                # **行そのもので見るのも足りない。**引数の名前や `final` は
                # 版で変わるので、同じメソッドでも文字列は一致しない
                # (1.20.6 の vanilla は `setSpawnSettings(boolean spawnMonsters,
                # boolean spawnAnimals)`、手書きの追加は
                # `setSpawnSettings(final boolean spawnEnemies, ...)`)。
                # 名前と引数の型で見る。
                # コメントと注釈は宣言ではない。`@Override` を宣言とみなすと、
                # それはどのファイルにもあるので塊が 1 つも入らなくなる。
                head = next((text.strip() for text in block
                             if text.strip()
                             and not text.strip().startswith("//")
                             and not text.strip().startswith("@")), None)

                if head and ms.key_of(member, block) in existing(lines, body, member):
                    continue

                # 初期化子の無い final は代入する場所が無い。final を外す。
                inserts.setdefault(body[1], []).extend(
                    [line for part in ([text] for text in block)
                     for line in ms.unfinal(part)])
                members += 1

            if not inserts:
                continue

            out = []

            for number, line in enumerate(lines):
                out.extend(inserts.get(number, []))
                out.append(line)

            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write("\n".join(out))

            files += 1

    print(f"足した塊: {members} / {files} ファイル")


if __name__ == "__main__":
    main()
