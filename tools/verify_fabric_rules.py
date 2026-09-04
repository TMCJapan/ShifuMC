"""Fabric の互換ルールが、いまの NMS に対してまだ要るかを確かめる。

旧構成(Paper のサーバー jar が土台)では、Paper と moonrise が書き換えた
メンバーに Fabric の注入点が当たらないので、40 件ほどの互換ルールで吸収していた。
うち半分は「その注入は落とす」= 機能を捨てるものだった。

Shifu の NMS は vanilla なので、**その前提はほとんど成り立たない。**
規則が根拠にしている「Paper がこう変えた」が本当かを、組んだ jar に対して確かめる。

    python tools/verify_fabric_rules.py [<paper-<ver>.jar>]

出るもの:

  * 規則が触る NMS のクラス・メンバーが jar にあるか
  * `RewriteInjectorSelector` の書き換え元と書き換え先のどちらがあるか
    (元があるなら書き換えは要らない。むしろ有害)
  * `AddField` の欄が既にあるか(あれば要らない)
"""

import os
import re
import subprocess
import sys

import paths

RULES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                     "shifu-bootstrap", "src", "main", "java", "dev", "shifu",
                     "bootstrap", "PaperCompatRules.java")
JAR = os.path.join(paths.RUN, "versions", "26.2", "paper-26.2.jar")
JAVAP = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javap")

ADD = re.compile(r'b\.add\("([\w.$]+)",\s*(.*?)\);', re.S)
DROP = re.compile(r'b\.drop\("([\w.$]+)",\s*"([\w$]+)"')
REWRITE = re.compile(r'new RewriteInjectorSelector\("([\w$]+)",\s*"([\w$]+)",\s*"([\w$]+)"\)')
ADD_FIELD = re.compile(r'new AddField\("([\w$]+)",\s*"([^"]+)"\)')
RETYPE_SHADOW = re.compile(r'new RetypeShadowField\("([\w$]+)",\s*"([^"]+)",\s*"([^"]+)"\)')


def members(cls):
    """jar の中のそのクラスのメンバー名。無ければ None。"""
    out = subprocess.run([JAVAP, "-p", "-cp", JAR, cls],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")

    if out.returncode != 0 or "Error" in out.stdout:
        return None

    return out.stdout


def has(body, name):
    return body is not None and re.search(r"\b%s\b" % re.escape(name), body) is not None


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else JAR
    globals()["JAR"] = jar
    text = open(RULES, encoding="utf-8").read()

    print("= NMS を触る規則 =")
    print()

    cache = {}

    def body_of(cls):
        if cls not in cache:
            cache[cls] = members(cls)
        return cache[cls]

    needed = 0
    stale = 0

    for cls, rest in ADD.findall(text):
        if not cls.startswith("net.minecraft"):
            continue

        body = body_of(cls)

        if body is None:
            print("  %s -> クラスが jar に無い" % cls)
            continue

        for name, desc in ADD_FIELD.findall(rest):
            if has(body, name):
                print("  不要  %s.%s は既にある(AddField は要らない)" % (cls, name))
                stale += 1
            else:
                print("  必要  %s.%s は無い(AddField が要る)" % (cls, name))
                needed += 1

    print()
    print("= Fabric の注入点を触る規則 =")
    print()

    for cls, rest in ADD.findall(text):
        for name, before, after in REWRITE.findall(rest):
            print("  %s#%s: %r -> %r" % (cls.split(".")[-1], name, before, after))
            print("        書き換え元 %r が NMS にあるか確かめること" % before)

        for name, old, new in RETYPE_SHADOW.findall(rest):
            print("  %s#%s: 型 %s -> %s" % (cls.split(".")[-1], name, old, new))
            print("        いまの NMS での型を確かめること")

    print()
    print("= 落としている注入(その機能は動かない) =")
    print()

    drops = DROP.findall(text)

    for cls, name in drops:
        print("  %s#%s" % (cls.split(".")[-1], name))

    print()
    print("AddField 必要 %d / 不要 %d、落としている注入 %d 件" % (needed, stale, len(drops)))

    return 0


if __name__ == "__main__":
    sys.exit(main())
