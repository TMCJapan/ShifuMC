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


def _at(subject, required=True):
    """paperweight が積むコミットを題名で引く。ハッシュは版ごとに変わる。

    classic(1.21.3 以前)の木は「paper Imports」1 つしか積まないので、
    Mache や paper ATs は無い。無いときは None を返す。
    classic で AT が触ったファイルを残さなくてよいのは、戻す先の minecraft.jar が AT を当てたあとの
    jar だから(9-16 の 1.20.6 / 1.19.4 / 1.18.2 で、net/minecraft の全クラスの修飾子が
    applyMergedAt.jar と一致した)。
    """
    for line in git(["log", "--format=%H %s"]):
        if line.endswith(" " + subject):
            return line.split(" ", 1)[0]

    if required:
        raise SystemExit("履歴に %r のコミットが無い" % subject)

    return None


def touched_sources():
    """Shifu が手を入れたファイルと、AT が触ったファイル(どちらも .java の相対パス)。"""
    vanilla = _at("Mache", required=False)     # 逆コンパイルの手直しまで
    with_ats = _at("paper ATs", required=False)  # 可視性を広げる
    ours = set()

    for line in git(["status", "--porcelain"]):
        path = line[3:].strip()

        if path.endswith(".java"):
            ours.add(path)

    ats = set()

    if vanilla and with_ats:
        ats = set(p.strip() for p in git(["diff", "--name-only", vanilla, with_ats])
                  if p.strip().endswith(".java"))

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


def restore_unreadable(classes_dir, swapped):
    """公式へ戻したクラスを JVM に読ませて、落ちるものは Shifu のものに戻す。

    codebook が名前を戻した公式 jar には、javap では正常に見えるのに JVM が
    `ClassFormatError: Illegal field name` で弾くクラスが混ざる(1.21.11 の
    BundlerInfo$1$1)。サーバーはそのクラスを使わないので起動は通るが、
    同じ jar を使う bot(クライアント側)が bundle を受けた瞬間に切れる。
    """
    if not swapped:
        return 0

    tools = os.path.dirname(os.path.abspath(__file__))
    lvtmatch = os.path.join(tools, "build", "lvtmatch")
    listing = os.path.join(tools, "build", "kept-classes.txt")
    io.open(listing, "w", encoding="utf-8", newline="\n").write("\n".join(sorted(swapped)) + "\n")
    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java")
    done = subprocess.run([java, "-cp", lvtmatch, "dev.shifu.lvtmatch.ClassCheck", classes_dir, listing],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")

    if done.returncode != 0:
        raise SystemExit("ClassCheck: %s%s" % (done.stdout, done.stderr))

    restored = set()

    for line in done.stdout.split("\n"):
        if not line.startswith("FAIL "):
            continue

        rel = line.split(" ", 2)[1]
        print("  公式が読めない: %s" % line[len("FAIL "):])
        # 内部クラスは外側と合成の欄(val$...)で結び付いている。1 つだけ Shifu のものに
        # 戻すと、公式の外側に無い欄を探しに行って NoSuchFieldError になる
        # (BundlerInfo$1$1 が val$bundler を、公式の $1 は val$constructor を持つ)。
        # 同じソースから出たクラスはまとめて戻す。
        owner = owner_of(rel)

        for sibling in swapped:
            if owner_of(sibling) == owner and sibling not in restored:
                io.open(os.path.join(classes_dir, sibling.replace("/", os.sep)), "wb").write(swapped[sibling])
                restored.add(sibling)

    return len(restored)


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
    # 公式へ戻したクラスと、戻す前の Shifu のバイト列。JVM に読めなかったら戻す
    swapped = {}

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

            swapped[rel] = io.open(path, "rb").read()
            io.open(path, "wb").write(data)
            replaced += 1

    unreadable = restore_unreadable(classes_dir, swapped)
    print("公式のバイトコードに戻した: %d" % (replaced - unreadable))
    print("公式が JVM に読めないので Shifu のものを残した: %d" % unreadable)
    print("Shifu が触ったので残した  : %d" % kept_ours)
    print("公式 jar に無い           : %d" % missing)

    if report:
        print("内訳: Shifu が手を入れた .java %d、AT が触った .java %d" % (len(ours), len(ats)))


if __name__ == "__main__":
    main()
