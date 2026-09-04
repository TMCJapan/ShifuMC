# -*- coding: utf-8 -*-
"""vanilla が定数を返しているところを Paper が欄の読み出しに変えている箇所を出す。

    python tools/find_constant_returns.py <patches/sources>

この形は**足すだけで入る**。vanilla の `return null;` の前に
「入っていれば返す」を置けば、誰も入れなければ vanilla と同じ経路になる。

    public @Nullable Component getTabListDisplayName() {
        if (this.listName != null) {   // 足した行
            return this.listName;      // 足した行
        }                              // 足した行
        return null;                   // vanilla のまま
    }

Player.setPlayerListName が効いていなかったのはこれ。
"""
import io
import os
import re
import sys

CONSTANT = re.compile(r"^-(\s*)return (null|0|0\.0F?|false|true|-1);\s*$")
FIELD = re.compile(r"^\+(\s*)return (this\.[\w.]+|[\w.]+);(\s*//.*)?$")


def main():
    root = sys.argv[1]
    found = []

    for base, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".patch"):
                continue

            path = os.path.join(base, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")[:-len(".patch")]
            lines = io.open(path, encoding="utf-8", errors="replace").read().split("\n")

            for i in range(len(lines) - 1):
                gone = CONSTANT.match(lines[i])
                added = FIELD.match(lines[i + 1])

                if not gone or not added:
                    continue

                # 直前の文脈行からメソッドの見出しを拾う
                head = ""

                for back in range(i - 1, max(i - 8, -1), -1):
                    if re.search(r"\)\s*\{\s*$", lines[back]) and not lines[back].startswith("-"):
                        head = lines[back].lstrip("+ ").strip()
                        break

                found.append((rel, head, gone.group(2), added.group(2)))

    print("定数を欄の読み出しに変えている箇所: %d" % len(found))

    for rel, head, gone, added in sorted(found):
        print("  %s" % rel)
        print("      %s" % (head or "(見出し不明)"))
        print("      return %s  ->  return %s" % (gone, added))


if __name__ == "__main__":
    main()
