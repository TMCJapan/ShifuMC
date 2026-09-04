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
LOCATION = re.compile(r"^\s*location: (?:class|interface|variable \w+ of type|@?interface) ([\w.<>]+)")
MEMBER = re.compile(r"method ([\w$]+) in (?:class|interface) ([\w.]+) cannot be applied")
ACCESS = re.compile(r"([\w$]+) has (?:private|protected) access in ([\w.]+)")
ABSTRACT = re.compile(r"does not override abstract method (\w+)\(")


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

        for name, owner in ACCESS.findall(line):
            add(owner, "access", name)

        for name in ABSTRACT.findall(line):
            add("(supertype)", "abstract", name)

        match = SYMBOL.match(line)

        if not match or number + 1 >= len(lines):
            continue

        place = LOCATION.match(lines[number + 1])

        if place:
            add(place.group(1), match.group(1), match.group(2))
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
