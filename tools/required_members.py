"""アダプタ層が vanilla の NMS に要求しているものを列挙する。

素の vanilla に対して `org.bukkit.craftbukkit.*` をコンパイルすると、
足りないものが全部エラーとして出る。それを NMS のクラスごとにまとめる。
これが自前で埋める作業リストになる。

    python tools/required_members.py <vanilla-gap.txt> > docs/backlog/required-members.txt
"""

import re
import sys

WHERE = re.compile(r"^(?:minecraft|main)[\\/]java[\\/](.+?\.java):(\d+): error:")
SYMBOL = re.compile(r"^\s*symbol:\s+(class|method|variable) ([\w$]+)")
LOCATION = re.compile(r"^\s*location: (?:class|interface|variable \w+ of type|record component \w+ of type|@?interface) ([\w.<>]+)")
MEMBER = re.compile(r"(?:method|constructor) ([\w$]+) in (?:class|interface|record) ([\w.]+(?:<[^>]*>)?) cannot be applied")
ACCESS = re.compile(r"([\w$]+) has (?:private|protected) access in ([\w.]+)")
# 総称メソッドは `<T>getEntitiesByClass(` の形で出る。型引数を飛ばす
ABSTRACT = re.compile(r"does not override abstract method (?:<[^>]+>\s*)?(\w+)\(")
# 引数違いは 2 つの形で出る。片方だけ見ていると、Paper が足した多重定義
# (disconnect(Component, Cause) など)が要求に入らない。
NO_METHOD = re.compile(r"no suitable (?:method|constructor) found for ([\w$]+)\(")
# `cannot infer type arguments for PalettedContainer<>` は構築子の引数違い
INFER = re.compile(r"cannot infer type arguments for ([\w$]+)<>")
# `location: record component value of type T` の T は型変数。javac は続けて
# `T extends Recipe<?> declared in record RecipeHolder` と書くので、上限の型に読み替える。
# 型変数のままだと木に無い名前になって、要求元の型が分からなくなる。
BOUND = re.compile(r"^\s+([A-Z]\w*) extends ([\w.$]+)[\w.<>,?$ ]* declared in ")


def bound_of(lines, number, name):
    """型変数なら、その直後の `where` が書いている上限の型。そうでなければそのまま。

    型変数の名前はエラーごとに違うので、そのエラーの後ろだけを見る。
    ファイル全体で 1 つの辞書にすると、別のエラーの T で上書きされる。
    """
    if not re.fullmatch(r"[A-Z]\w?", name):
        return name

    for line in lines[number:number + 8]:
        hit = BOUND.match(line)

        if hit and hit.group(1) == name:
            return hit.group(2)

    return name


def main():
    lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().split("\n")
    wanted = {}
    where = None

    def add(owner, kind, name):
        owner = owner.split("<")[0]
        wanted.setdefault(owner, {}).setdefault((kind, name), set()).add(where)

    for number, line in enumerate(lines):
        spot = WHERE.match(line)

        if spot:
            where = spot.group(1).replace("\\", "/")

        for name, owner in MEMBER.findall(line):
            add(owner, "method", name)

        for name in NO_METHOD.findall(line):
            add("(unqualified)", "method", name)

        for name in INFER.findall(line):
            add(name, "method", name)

        for name, owner in ACCESS.findall(line):
            add(owner, "access", name)

        for name in ABSTRACT.findall(line):
            add("(supertype)", "abstract", name)

        match = SYMBOL.match(line)

        if not match or number + 1 >= len(lines):
            continue

        place = LOCATION.match(lines[number + 1])

        if place:
            add(bound_of(lines, number, place.group(1)), match.group(1), match.group(2))
        elif where:
            # javac が location を出さないことがある(`this.player` のような
            # 自分の型の要素)。そのときはエラーの出たファイルの型を owner にする。
            # `(unqualified)` にすると、その名前を要求している型が分からなくなり、
            # 生成器が「無関係な型の同名の宣言」と区別できない。
            add(where.rsplit("/", 1)[-1][:-len(".java")], match.group(1), match.group(2))
        else:
            add("(unqualified)", match.group(1), match.group(2))

    total = sum(len(members) for members in wanted.values())
    print(f"# アダプタ層が vanilla NMS に要求しているもの: {total} 件 / {len(wanted)} クラス")
    print("# 種別 method=引数違い variable=フィールドかメソッド class=型 access=可視性")
    print()

    for owner in sorted(wanted, key=lambda o: (-len(wanted[o]), o)):
        members = wanted[owner]
        print(f"{owner}  ({len(members)})")

        for kind, name in sorted(members):
            print(f"    {kind:9} {name}")

        print()


if __name__ == "__main__":
    main()
