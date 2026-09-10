# -*- coding: utf-8 -*-
"""無名クラスが捕まえた欄の名前が、公式のバイトコードと食い違っていないかを見る。

    python tools/check_synthetic_names.py <コンパイル済みクラスの置き場> <Mojang の jar>

## なぜ要るか

`keep_vanilla_classes.py` は「触っていないクラスは公式のバイトコードに戻す」を
**ソースファイル単位**で決める。外側のメソッドに 1 行足すと、同じファイルの
**無名クラスまで** Shifu のコンパイル結果になる。

無名クラスが捕まえた局所変数は、javac が `val$<局所変数名>` という合成の欄にする。
逆コンパイラが付けた局所変数の名前は Mojang のものと違うので、欄の名前も変わる。

    Mojang:  val$base                    (局所変数 base)
    Shifu:   val$literalArgumentBuilder  (逆コンパイラが付けた名前)

Fabric の MOD はその合成の欄を intermediary の名前で `@Shadow` する。
橋(intermediary -> mojmap)は Mojang の名前を持っているので、名前が変わると

    @Shadow field field_19419 was not located in the target class …$1

で mixin が当たらず、そのクラスを読む時点でサーバーが落ちる
(`fabric-game-rule-api-v1` が `GameRuleCommand$1` で実際に踏んだ)。
**外側に 1 行足しただけなのに、触っていない無名クラスの名前でエラーが出る**ので、
原因から遠い。ここで先に気付けるようにする。

## 出るもの

Shifu が持っているクラスのうち、公式にも同じ名前のクラスがあって、
`val$` の欄の並びが違うものを出す。0 件なら MOD 側から見て名前は同じ。
"""
import os
import sys
import zipfile

import paths


def val_fields(data):
    """クラスのバイト列から `val$…` の欄の名前を順に取り出す。

    定数プールを読まずに、`val$` で始まる UTF-8 の定数を拾うだけで足りる。
    合成の欄の名前はそこにしか出てこない。
    """
    out = []
    at = 0

    while True:
        at = data.find(b"val$", at)

        if at < 0:
            return out

        # UTF-8 定数は 1 バイトのタグ(1)と 2 バイトの長さが前に付く
        head = at - 3

        if head >= 0 and data[head] == 1:
            length = int.from_bytes(data[head + 1:head + 3], "big")

            if head + 3 + length <= len(data):
                out.append(data[head + 3:head + 3 + length].decode("utf-8", "replace"))

        at += 4


def mojang_classes(jar):
    with zipfile.ZipFile(jar) as outer:
        nested = [n for n in outer.namelist()
                  if n.startswith("META-INF/versions/") and n.endswith(".jar")]

        if not nested:
            return {n: outer.read(n) for n in outer.namelist() if n.endswith(".class")}

        with outer.open(nested[0]) as raw:
            import io

            with zipfile.ZipFile(io.BytesIO(raw.read())) as inner:
                return {n: inner.read(n) for n in inner.namelist() if n.endswith(".class")}


def main():
    classes, jar = sys.argv[1], sys.argv[2]
    official = mojang_classes(jar)
    bad = 0

    for base, _, names in os.walk(classes):
        for name in names:
            if not name.endswith(".class") or "$" not in name:
                continue

            path = os.path.join(base, name)
            rel = os.path.relpath(path, classes).replace(os.sep, "/")

            if rel not in official:
                continue

            with open(path, "rb") as fh:
                mine = val_fields(fh.read())

            theirs = val_fields(official[rel])

            if mine != theirs:
                bad += 1
                print("%s" % rel)
                print("      Shifu : %s" % (", ".join(mine) or "(無し)"))
                print("      Mojang: %s" % (", ".join(theirs) or "(無し)"))

    print("名前の違う無名クラス: %d" % bad)

    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
