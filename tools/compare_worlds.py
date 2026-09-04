"""同じシードで作った世界を突き合わせる。

条件 1(vanilla と完全に一致する)を実測するための道具。

region ファイルは書き込み時刻と詰め方が実行のたびに変わるので、
**チャンクの中身(展開した NBT のバイト列)だけを比べる。**

それでも原点まわりの数十チャンクは実行のたびに変わる。起動から停止までに
tick される範囲で、mob の湧きも乱数の消費も走った時間で決まるため。
**vanilla を 2 回走らせて、そのばらつきを差し引く。**

    # ばらつきを差し引かない(全部出る)
    python tools/compare_worlds.py <vanilla の world> <Shifu の world>

    # vanilla をもう 1 回走らせた世界を渡すと、実行のたびに変わる分を除く
    python tools/compare_worlds.py <vanilla の world> <Shifu の world> <vanilla の world 2>

3 つめを渡した形で差が 0 なら、tick されない範囲では vanilla と一致している。
"""

import os
import struct
import sys
import zlib

SECTOR = 4096


def chunks(path):
    """region ファイルの チャンク座標 -> 展開した NBT のバイト列。

    先頭 8KB は位置表と時刻表。時刻は実行のたびに変わるので読まない。
    """
    out = {}

    with open(path, "rb") as handle:
        header = handle.read(SECTOR)

        if len(header) < SECTOR:
            return out

        for index in range(1024):
            entry = struct.unpack(">I", header[index * 4:index * 4 + 4])[0]
            offset = entry >> 8
            count = entry & 0xFF

            if offset == 0 or count == 0:
                continue

            handle.seek(offset * SECTOR)
            head = handle.read(5)

            if len(head) < 5:
                continue

            length, kind = struct.unpack(">IB", head)
            body = handle.read(length - 1)

            try:
                if kind == 1:
                    body = zlib.decompress(body, 16 + zlib.MAX_WBITS)
                elif kind == 2:
                    body = zlib.decompress(body)
                # kind 3 は無圧縮。4 以降(LZ4 など)はそのまま比べる
            except zlib.error as problem:
                body = b"<decompress failed: " + str(problem).encode() + b">"

            out[(index % 32, index // 32)] = body

    return out


def regions(world):
    """次元ごとの region ファイル。相対パスで返す。"""
    found = {}

    for base, _, names in os.walk(world):
        for name in sorted(names):
            if name.endswith(".mca"):
                path = os.path.join(base, name)
                found[os.path.relpath(path, world).replace(os.sep, "/")] = path

    return found


def world_chunks(world):
    """(region の相対パス, チャンク座標) -> 中身。"""
    out = {}

    for name, path in regions(world).items():
        for key, body in chunks(path).items():
            out[(name, key)] = body

    return out


def unstable_between(left, right):
    """2 回の実行で変わったチャンク。tick される範囲がここに入る。"""
    a, b = world_chunks(left), world_chunks(right)

    return {key for key in set(a) & set(b) if a[key] != b[key]} | (set(a) ^ set(b))


def main():
    left, right = sys.argv[1:3]
    control = sys.argv[3] if len(sys.argv) > 3 else None

    skip = set()

    if control:
        skip = unstable_between(left, control)
        print("vanilla を 2 回走らせて変わるチャンク: %d(この分は除く)" % len(skip))

    a, b = world_chunks(left), world_chunks(right)
    shared = (set(a) & set(b)) - skip
    differ = sorted(key for key in shared if a[key] != b[key])
    only_left = sorted(set(a) - set(b) - skip)
    only_right = sorted(set(b) - set(a) - skip)

    for name, key in differ:
        print("%s: チャンク %s の中身が違う (%d vs %d バイト)"
              % (name, key, len(a[(name, key)]), len(b[(name, key)])))

    for name, key in only_left:
        print("%s: チャンク %s が左にしか無い" % (name, key))

    for name, key in only_right:
        print("%s: チャンク %s が右にしか無い" % (name, key))

    print()
    print("比べたチャンク %d / 一致 %d、不一致 %d、片方だけ %d"
          % (len(shared), len(shared) - len(differ), len(differ),
             len(only_left) + len(only_right)))

    return 1 if (differ or only_left or only_right) else 0


if __name__ == "__main__":
    sys.exit(main())
