# -*- coding: utf-8 -*-
"""Paper が出しているイベントのうち、Shifu が出せているものを数える。

    python tools/event_coverage.py [--list]

数え方:
  当てた木の `net/minecraft` を読み、そこから
  「発火層 dev.shifu.event.* の静的メソッド」と
  「CraftEventFactory の callXxx / handleXxx」を辿って、届くイベント型を集める。
  Paper 側は Paper-Server の HEAD の同じ場所から同じ手順で集める。差が残り。

`patches/events` を数えるやり方は使わない。shim が Paper のコードを木へ
持ち込むので、置いてあるだけで届かないものまで数に入る。
"""

import os
import re
import subprocess
import sys

import paths

EVENT = re.compile(r"(?<![\w.$])(?:new\s+)?((?:\w+\.)*[A-Z]\w*Event)\s*\(")
FACTORY = re.compile(r"CraftEventFactory\.((?:call|handle)\w+)\s*\(")
# 発火層に名前が出ていれば出していると数える。発火層は木から呼ばれるものしか置かない。
MENTION = re.compile(r"(?<![\w$])([A-Z]\w*Event)(?![\w])")
HELPER = re.compile(r"dev\.shifu\.event\.(\w+)\.(\w+)\s*\(")
METHOD = re.compile(r"^\s{4}(?:public|private|protected|static|final|\s)*[\w<>,.\[\]?\s]+\s(\w+)\(")


def short(name):
    return name.rsplit(".", 1)[-1]


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def walk(root, want=".java"):
    for base, _, files in os.walk(root):
        for name in sorted(files):
            if name.endswith(want):
                yield os.path.join(base, name)


def bodies(text):
    """{メソッド名: 本体の文字列}。字下げ 4 の宣言から次の宣言までを本体とみなす。"""
    lines = text.split("\n")
    out = {}
    name = None
    start = 0

    for at, line in enumerate(lines):
        found = METHOD.match(line)

        if found is None:
            continue

        if name is not None:
            out.setdefault(name, "")
            out[name] += "\n".join(lines[start:at])

        name = found.group(1)
        start = at

    if name is not None:
        out.setdefault(name, "")
        out[name] += "\n".join(lines[start:])

    return out


def helper_bodies(root):
    """{(クラス, メソッド): 本体}。発火層のソースから。"""
    out = {}

    for path in walk(root):
        cls = os.path.basename(path)[:-5]

        for name, body in bodies(read(path)).items():
            out[(cls, name)] = body

    return out


def collect(texts, helpers, factory, mention=()):
    """本文の並びから、届くイベント型の名前を集める。"""
    found = set()
    seen = set()
    todo = list(texts)

    for text in mention:
        found.update(MENTION.findall(text))
        todo.append(text)

    while todo:
        text = todo.pop()

        for name in EVENT.findall(text):
            found.add(short(name))

        for cls, name in HELPER.findall(text):
            if (cls, name) in seen:
                continue

            seen.add((cls, name))
            body = helpers.get((cls, name))

            if body is not None:
                todo.append(body)

        for name in FACTORY.findall(text):
            if ("CraftEventFactory", name) in seen:
                continue

            seen.add(("CraftEventFactory", name))
            body = factory.get(name)

            if body is not None:
                todo.append(body)

    return found


def paper_side():
    """Paper HEAD の net/minecraft と CraftEventFactory から。"""
    root = os.path.join(paths.PAPER, "Paper-Server")
    listing = subprocess.run(["git", "ls-tree", "-r", "--name-only", "HEAD",
                              "src/main/java/net/minecraft"],
                             cwd=root, capture_output=True)
    texts = []

    for name in listing.stdout.decode("utf-8", "replace").split("\n"):
        if not name.endswith(".java"):
            continue

        blob = subprocess.run(["git", "show", "HEAD:" + name], cwd=root, capture_output=True)
        texts.append(blob.stdout.decode("utf-8", "replace"))

    blob = subprocess.run(
        ["git", "show", "HEAD:src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java"],
        cwd=root, capture_output=True)
    factory = bodies(blob.stdout.decode("utf-8", "replace"))

    return collect(texts, {}, factory)


def shifu_side():
    """当てた木の net/minecraft と、そこから届く発火層 / CraftEventFactory から。"""
    tree = os.path.join(paths.TREE, "net", "minecraft")
    texts = [read(path) for path in walk(tree)]
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    root = os.path.join(here, "src", "event", "java", "dev", "shifu", "event")
    helpers = helper_bodies(root)
    factory = bodies(read(os.path.join(
        paths.PAPER, "Paper-Server", "src", "main", "java", "org", "bukkit",
        "craftbukkit", "event", "CraftEventFactory.java")))

    # 発火層は木から呼ばれているものしか置いていないので、名前が出ていれば数に入れる。
    # 木の方は shim が Paper のコードを持ち込むので、作っているか呼んでいるものだけ。
    return collect(texts, helpers, factory, [read(path) for path in walk(root)])


# 数え方の当たり。名前は出てくるが、Shifu 側は別の道で出しているか、
# そもそも Bukkit の催しではないもの。
KNOWN = {
    "CraftPortalEvent":
        "org.bukkit.craftbukkit.event.CraftPortalEvent。Bukkit の催しではなく、"
        "PortalInfo が持つ入れ物",
    "ServerExceptionEvent":
        "VillageSiege から ServerInternalException.reportInternalException 経由で出している",
}


def main():
    paper = paper_side()
    shifu = shifu_side()
    missing = sorted(paper - shifu)
    known = [name for name in missing if name in KNOWN]
    rest = [name for name in missing if name not in KNOWN]

    print("Paper %d / Shifu %d、差 %d(うち数え方の当たり %d、残り %d)"
          % (len(paper), len(paper & shifu), len(missing), len(known), len(rest)))

    if "--list" in sys.argv:
        for name in rest:
            print("   ", name)

        for name in known:
            print("    (当たり)", name, "-", KNOWN[name])

    return 0


if __name__ == "__main__":
    sys.exit(main())
