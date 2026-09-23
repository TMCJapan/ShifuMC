"""spigot 名 → mojang 名の対応表を組む(実行時にプラグインのバイトコードを写すための表)。

    python tools/make_plugin_mappings.py <Paper の置き場> <出力ファイル>

Spigot 向けに組んだプラグインのバイトコードは、クラスは Spigot の名前
(BuildData の bukkit-<版>-cl.csrg)、欄とメソッドは難読化名(official)のままで書かれている
(1.18.2 の BuildData に members.csrg は無い)。Shifu は mojang 名で動く。
official を鍵に、cl.csrg と paperweight の official-mojang+yarn.tiny を合わせて
spigot 名 → mojang 名の表にする。

読むもの:
  <Paper>/work/BuildData/mappings/bukkit-*-cl.csrg          official → spigot(クラスだけ)
  <Paper>/.gradle/caches/paperweight/mappings/official-mojang+yarn.tiny  official → mojang
cl.csrg に無いクラスは official のまま(内部クラスは外側の名前から導く)。

BuildData の .exclude にあるクラス(data ジェネレータ、gametest、package-info など)は
cl.csrg に無いので、spigot 側は既定パッケージの難読化名(`a`、`jh$1`)のまま残る。
Spigot はその .exclude のクラスを server jar から外すのでプラグインは呼べない。
一方、難読化したプラグインは既定パッケージに `a`、`b` を持つ。表に残すと
プラグイン自身の `a` を com/mojang/math/Constants に書き換えて NoClassDefFoundError に
なるので、所有クラスがパッケージに入っていない行は書かない。

出力(tab 区切り。名前が同じものは書かない):
  c  <spigot クラス>  <mojang クラス>
  f  <spigot 所有クラス>  <spigot 欄名>  <spigot 記述子>  <mojang 欄名>
  m  <spigot 所有クラス>  <spigot メソッド名>  <spigot 記述子>  <mojang メソッド名>
記述子は spigot 名で書く(プラグインの呼び出し箇所と同じ形)。
読むのは dev.shifu.remap.PluginRemapper。

CRAFTBUKKIT_METHODS の行も足す。CraftBukkit が戻り値の型を変えた vanilla のメソッドで、
Shifu では同じ名前・同じ引数で型だけ違うメソッドを Java で宣言できないので、
patches/hand の別名へ向ける。記述子で引くので vanilla の版(型が違う)の呼び出しは写らない。
"""
import glob
import os
import re
import sys

CLASS_IN_DESC = re.compile(r"L([^;]+);")

# (mojang 所有クラス, CraftBukkit のメソッド名, mojang 記述子, patches/hand の名前)
# nextContainerCounter: vanilla は ()V、CraftBukkit は ()I に変えて番号を返す。
# InvSee++ の impl_1_18_2_R2 / impl_1_19_4_R3 が ()I の版を呼び、NoSuchMethodError になっていた。
CRAFTBUKKIT_METHODS = [
    ("net/minecraft/server/level/ServerPlayer", "nextContainerCounter", "()I", "shifuNextContainerCounter"),
]


def read_tiny(path):
    """tiny v2(official が先頭)を {official クラス: (名前, {(欄名, 記述子): 名前}, {(メソッド名, 記述子): 名前})} に読む。"""
    classes = {}
    current = None
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        if header[0] != "tiny" or header[3] != "official":
            raise SystemExit(f"{path}: official を先頭に置いた tiny v2 ではない: {header}")
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "c":
                current = parts[1]
                classes[current] = (parts[2] or parts[1], {}, {})
            elif parts[0] == "" and parts[1] == "f":
                classes[current][1][(parts[3], parts[2])] = parts[4] or parts[3]
            elif parts[0] == "" and parts[1] == "m":
                classes[current][2][(parts[3], parts[2])] = parts[4] or parts[3]
    return classes


def read_csrg(path):
    classes = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            official, spigot = line.split()
            classes[official] = spigot
    return classes


def main():
    paper, out = sys.argv[1], sys.argv[2]
    csrgs = glob.glob(os.path.join(paper, "work", "BuildData", "mappings", "bukkit-*-cl.csrg"))
    if len(csrgs) != 1:
        raise SystemExit(f"bukkit-*-cl.csrg が 1 つでない: {csrgs}")
    csrg = read_csrg(csrgs[0])
    mojang = read_tiny(os.path.join(paper, ".gradle", "caches", "paperweight", "mappings", "official-mojang+yarn.tiny"))

    def spigot_class(official):
        if official in csrg:
            return csrg[official]
        if "$" in official:
            outer, inner = official.rsplit("$", 1)
            return spigot_class(outer) + "$" + inner
        return official

    def spigot_desc(desc):
        return CLASS_IN_DESC.sub(lambda m: "L" + spigot_class(m.group(1)) + ";", desc)

    lines = []
    counts = {"c": 0, "f": 0, "m": 0}
    skipped = 0
    for official, (mojang_name, fields, methods) in mojang.items():
        spigot_name = spigot_class(official)
        if "/" not in spigot_name:
            skipped += 1
            continue
        if spigot_name != mojang_name:
            lines.append(f"c\t{spigot_name}\t{mojang_name}")
            counts["c"] += 1
        for (name, desc), mapped in fields.items():
            if name != mapped:
                lines.append(f"f\t{spigot_name}\t{name}\t{spigot_desc(desc)}\t{mapped}")
                counts["f"] += 1
        for (name, desc), mapped in methods.items():
            if name != mapped:
                lines.append(f"m\t{spigot_name}\t{name}\t{spigot_desc(desc)}\t{mapped}")
                counts["m"] += 1

    to_spigot = {mojang_name: spigot_class(official) for official, (mojang_name, _, _) in mojang.items()}
    for owner, name, desc, mapped in CRAFTBUKKIT_METHODS:
        spigot_desc_ = CLASS_IN_DESC.sub(lambda m: "L" + to_spigot.get(m.group(1), m.group(1)) + ";", desc)
        lines.append(f"m	{to_spigot[owner]}	{name}	{spigot_desc_}	{mapped}")
        counts["m"] += 1

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{out}: {counts['c']} classes, {counts['f']} fields, {counts['m']} methods"
          f" ({skipped} classes outside a package skipped)")


if __name__ == "__main__":
    main()
