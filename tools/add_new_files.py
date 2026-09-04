"""Paper が丸ごと新しく足すファイルを置く。

`--- /dev/null` で始まるパッチは、vanilla に無いファイルを 1 つ作るもの。
元の行は 1 行も無いので、置いても vanilla の挙動には触れない。
`make_shim.py` は元のファイルが無いパッチを飛ばすので、ここで別に扱う。

    python tools/add_new_files.py <patches/sources> <src/minecraft/java>
"""

import os
import sys


def new_file(path):
    """`--- /dev/null` のパッチなら (置き場所, 中身) を返す。"""
    with open(path, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().split("\n")

    if not lines or not lines[0].startswith("--- /dev/null"):
        return None, None

    if len(lines) < 2 or not lines[1].startswith("+++ b/"):
        return None, None

    target = lines[1][len("+++ b/"):].strip()
    body = [line[1:] for line in lines[3:] if line.startswith("+")]

    return target, body


def main():
    patch_root, tree = sys.argv[1:3]
    written = 0

    for base, _, names in os.walk(patch_root):
        for name in sorted(names):
            if not name.endswith(".patch"):
                continue

            target, body = new_file(os.path.join(base, name))

            if target is None:
                continue

            path = os.path.join(tree, target.replace("/", os.sep))

            if os.path.exists(path):
                print(f"{target}: 既にある", file=sys.stderr)
                continue

            os.makedirs(os.path.dirname(path), exist_ok=True)

            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write("\n".join(body) + "\n")

            written += 1

    print(f"置いた新しいファイル: {written}")


if __name__ == "__main__":
    main()
