# -*- coding: utf-8 -*-
"""触っていないクラスは、Mojang の公式 jar のバイトコードをそのまま使う。

    python tools/keep_vanilla_classes.py <コンパイル済みクラスの置き場> <Mojang の jar> [--report]

## なぜ要るか

Shifu は逆コンパイルした vanilla を**コンパイルし直して**いる。ソースの意味は同じでも、
出てくるバイトコードは Mojang の公式 jar と同じにはならない。とくに**局所変数の割り当てが
変わる**。Fabric の MOD には、mixin で局所変数を型の並びで捕まえるものがあり
(`@Inject(locals = ...)`、MixinExtras の `@Local`)、並びが変わると
`LVT ... has incompatible changes` で当たらずにサーバーが起動しない
(Architectury API の ServerPlayerGameMode への mixin で実際に踏んだ)。

Shifu が実際に手を入れているのは 4863 ファイル中 552。**残りは公式のバイトコードを
そのまま置けば、MOD から見て vanilla と同じになる。**

## 置き換えないもの

* Shifu が手を入れたファイル(shim・hand・発火・配線)
* Paper の access transformer が可視性を広げたファイル。公式のバイトコードに戻すと
  アダプタ層から見えなくなる
* Mojang の jar に無いクラス(Paper が足したものなど)
"""
import io
import os
import subprocess
import sys
import zipfile

import paths

TREE = paths.TREE


def git(args, cwd=TREE):
    done = subprocess.run(["git"] + args, cwd=cwd, capture_output=True)

    if done.returncode != 0:
        raise SystemExit("git %s: %s" % (" ".join(args), done.stderr.decode("utf-8", "replace")))

    return done.stdout.decode("utf-8", "replace").split("\n")


def _at(subject):
    """paperweight が積むコミットを題名で引く。ハッシュは版ごとに変わる。"""
    for line in git(["log", "--format=%H %s"]):
        if line.endswith(" " + subject):
            return line.split(" ", 1)[0]

    raise SystemExit("履歴に %r のコミットが無い" % subject)


def touched_sources():
    """Shifu が手を入れたファイルと、AT が触ったファイル(どちらも .java の相対パス)。"""
    vanilla = _at("Mache")     # 逆コンパイルの手直しまで
    with_ats = _at("paper ATs")  # 可視性を広げる
    ours = set()

    for line in git(["status", "--porcelain"]):
        path = line[3:].strip()

        if path.endswith(".java"):
            ours.add(path)

    ats = set(p.strip() for p in git(["diff", "--name-only", vanilla, with_ats]) if p.strip().endswith(".java"))

    return ours, ats


def class_prefixes(sources):
    """ソースの相対パスから、そのファイルが出すクラス名の接頭辞(内部クラス込み)。"""
    return set(path[:-len(".java")] for path in sources)


def owner_of(class_path):
    """クラスファイルの相対パス -> ソースの接頭辞(内部クラスは外側に寄せる)。"""
    name = class_path[:-len(".class")]
    at = name.find("$")

    return name if at < 0 else name[:at]


def mojang_classes(jar):
    """公式 jar の中の server jar から、クラス名 -> バイト列。"""
    # 公式の配布は bundler(中に server jar が入っている)。paperweight のキャッシュに
    # あるのは展開済みの server jar。どちらでも受ける
    with zipfile.ZipFile(jar) as outer:
        nested = [n for n in outer.namelist() if n.startswith("META-INF/versions/") and n.endswith(".jar")]

        if not nested:
            return {n: outer.read(n) for n in outer.namelist() if n.endswith(".class")}

        data = outer.read(nested[0])

    path = jar + ".server.tmp"
    io.open(path, "wb").write(data)
    out = {}

    with zipfile.ZipFile(path) as server:
        for name in server.namelist():
            if name.endswith(".class"):
                out[name] = server.read(name)

    os.remove(path)

    return out


def main():
    classes_dir = sys.argv[1]
    jar = sys.argv[2]
    report = "--report" in sys.argv

    ours, ats = touched_sources()
    keep = class_prefixes(ours | ats)
    official = mojang_classes(jar)

    replaced = 0
    kept_ours = 0
    missing = 0

    for base, _, files in os.walk(classes_dir):
        for name in files:
            if not name.endswith(".class"):
                continue

            path = os.path.join(base, name)
            rel = os.path.relpath(path, classes_dir).replace(os.sep, "/")

            if not rel.startswith("net/minecraft/"):
                continue

            if owner_of(rel) in keep:
                kept_ours += 1
                continue

            data = official.get(rel)

            if data is None:
                missing += 1
                continue

            io.open(path, "wb").write(data)
            replaced += 1

    print("公式のバイトコードに戻した: %d" % replaced)
    print("Shifu が触ったので残した  : %d" % kept_ours)
    print("公式 jar に無い           : %d" % missing)

    if report:
        print("内訳: Shifu が手を入れた .java %d、AT が触った .java %d" % (len(ours), len(ats)))


if __name__ == "__main__":
    main()
