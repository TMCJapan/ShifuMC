# -*- coding: utf-8 -*-
"""Shifu が手を入れたクラスの局所変数の並びを、Mojang の公式クラスと見比べる。

    python tools/compare_lvt.py <コンパイル済みクラスの置き場> <Mojang の jar> [--list]

Fabric の MOD には、mixin で局所変数を **slot の順に並べた型** で捕まえるものがある
(`@Inject(locals = ...)`、MixinExtras の `@Local`)。並びが公式と違うと
`LVT ... has incompatible changes` で当たらず、サーバーが起動しない。

触っていないクラスは tools/keep_vanilla_classes.py が公式のバイトコードに戻すので、
残るのは手を入れたクラスだけ。ここではその中で **並びが違うメソッド** を数える。
"""
import io
import os
import struct
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from missing_members import parse_constant_pool  # noqa: E402
from keep_vanilla_classes import class_prefixes, mojang_classes, owner_of, touched_sources  # noqa: E402


def utf8(pool, index):
    return pool[index][1]


def skip_attributes(data, at, pool, want=None):
    """属性を読み飛ばす。want に名前を渡すと (名前, 中身の開始, 長さ) を集める。"""
    count = struct.unpack_from(">H", data, at)[0]
    at += 2
    found = []

    for _ in range(count):
        name = utf8(pool, struct.unpack_from(">H", data, at)[0])
        length = struct.unpack_from(">I", data, at + 2)[0]

        if want and name in want:
            found.append((name, at + 6, length))

        at += 6 + length

    return at, found


def method_tables(data):
    """class ファイル -> {(メソッド名, 署名): (局所変数の表, 行番号の一覧)}。"""
    pool = parse_constant_pool(data)
    count = struct.unpack_from(">H", data, 8)[0]
    at = 10
    index = 1

    # 定数プールの終わりまで飛ばす(parse_constant_pool と同じ歩き方)
    while index < count:
        tag = data[at]
        at += 1

        if tag == 1:
            at += 2 + struct.unpack_from(">H", data, at)[0]
        elif tag in (7, 8, 16, 19, 20):
            at += 2
        elif tag == 15:
            at += 3
        elif tag in (3, 4):
            at += 4
        elif tag in (5, 6):
            at += 8
            index += 1
        else:
            at += 4

        index += 1

    at += 6  # access_flags, this_class, super_class
    interfaces = struct.unpack_from(">H", data, at)[0]
    at += 2 + interfaces * 2

    for _ in range(2):  # fields, methods の 2 周。1 周目は飛ばすだけ
        pass

    fields = struct.unpack_from(">H", data, at)[0]
    at += 2

    for _ in range(fields):
        at += 6
        at, _ = skip_attributes(data, at, pool)

    methods = struct.unpack_from(">H", data, at)[0]
    at += 2
    out = {}

    for _ in range(methods):
        name = utf8(pool, struct.unpack_from(">H", data, at + 2)[0])
        desc = utf8(pool, struct.unpack_from(">H", data, at + 4)[0])
        at += 6
        at, code = skip_attributes(data, at, pool, want=("Code",))

        if not code:
            continue

        body = code[0][1]
        code_len = struct.unpack_from(">I", data, body + 4)[0]
        inner = body + 8 + code_len
        exceptions = struct.unpack_from(">H", data, inner)[0]
        inner += 2 + exceptions * 8
        _, tables = skip_attributes(data, inner, pool, want=("LocalVariableTable", "LineNumberTable"))
        entries = []
        source_lines = []

        for kind, start, _length in tables:
            n = struct.unpack_from(">H", data, start)[0]

            if kind == "LocalVariableTable":
                for i in range(n):
                    pc, span, name_i, desc_i, slot = struct.unpack_from(">HHHHH", data, start + 2 + i * 10)
                    entries.append((slot, utf8(pool, name_i), utf8(pool, desc_i), pc, span))
            else:
                for i in range(n):
                    _, line = struct.unpack_from(">HH", data, start + 2 + i * 4)
                    source_lines.append(line)

        out[(name, desc)] = (entries, source_lines)

    return out


def method_locals(data):
    """class ファイル -> {(メソッド名, 署名): [(slot, 名前, 型, 開始, 長さ), ...]}。"""
    return {key: value[0] for key, value in method_tables(data).items()}


def method_lines(data):
    """class ファイル -> {(メソッド名, 署名): (最初の行, 最後の行)}。"""
    out = {}

    for key, (_, lines) in method_tables(data).items():
        if lines:
            out[key] = (min(lines), max(lines))

    return out


def order(entries):
    """slot -> 名前 の対応(同じ slot を使い回すものは開始位置の順に並べる)。"""
    return tuple(sorted((slot, name, desc) for slot, name, desc, _, _ in entries))


def moved(mine, theirs):
    """公式にもこちらにもある変数のうち、番号が違うもの。

    mixin が見るのはこれ。こちらにしか無い変数(差し込みが足したもの)が
    余分に居ても、公式の変数が公式の番号に座っていれば型の並びは合う。
    """
    have = {}

    for slot, name, desc, _, _ in mine:
        have.setdefault((name, desc), set()).add(slot)

    out = []

    for slot, name, desc, _, _ in theirs:
        if (name, desc) in have and slot not in have[(name, desc)]:
            out.append((name, slot, sorted(have[(name, desc)])))

    return out


def main():
    classes_dir = sys.argv[1]
    jar = sys.argv[2]
    show = "--list" in sys.argv

    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    official = mojang_classes(jar)

    same = 0
    differ = 0
    no_lvt = 0
    shifted = 0
    lines = []

    for base, _, files in os.walk(classes_dir):
        for name in files:
            if not name.endswith(".class"):
                continue

            rel = os.path.relpath(os.path.join(base, name), classes_dir).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/") or owner_of(rel) not in keep:
                continue

            data = official.get(rel)

            if data is None:
                continue

            mine = method_locals(io.open(os.path.join(base, name), "rb").read())
            theirs = method_locals(data)

            for key, entries in mine.items():
                if key not in theirs:
                    continue

                if not entries or not theirs[key]:
                    no_lvt += 1
                    continue

                if moved(entries, theirs[key]):
                    shifted += 1

                if order(entries) == order(theirs[key]):
                    same += 1
                else:
                    differ += 1
                    lines.append("%s %s%s" % (rel[:-len(".class")], key[0], key[1]))

    print("並びが同じ            : %d" % same)
    print("並びが違う            : %d" % differ)
    print("うち公式の変数がずれた: %d" % shifted)
    print("表が無い              : %d" % no_lvt)

    if show:
        io.open("tools/build/lvt-differs.txt", "w", encoding="utf-8", newline="\n").write("\n".join(sorted(lines)) + "\n")
        print("一覧: tools/build/lvt-differs.txt")


if __name__ == "__main__":
    main()
