# -*- coding: utf-8 -*-
"""プラグインが呼ぶ NMS のメンバーのうち、Shifu のサーバーに無いものを出す。

    python tools/missing_members.py <サーバーの jar> <プラグインの jar> [<接頭辞>]

Paper は NMS に独自のメソッドを足していて、**アダプタ層を通さずに直に呼ぶプラグインがある**
(FastAsyncWorldEdit、ProtocolLib、NBT-API など)。shim の閉包はアダプタ層の要求からしか
作らないので、そういうメンバーは落ちる。動かすまで気付けないので、先に洗い出す。

やること: プラグインの class ファイルの定数プールから `net/minecraft/...` への参照
(Methodref / Fieldref / InterfaceMethodref)を集め、サーバーの jar に同じ名前と
署名があるかを見る。継承は追う(親クラスと interface)。

接頭辞を渡すと、プラグインの中でその名前で始まるクラスだけを見る
(例: `com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_2/`)。
"""
import io
import struct
import sys
import zipfile

# NMS だけでなく、Paper と Bukkit も見る。MOD やプラグインがこれらのクラスを
# 同梱していることがあり、足りないメンバーは同じように NoSuchMethodError になる。
NMS = ("net/minecraft/", "io/papermc/", "com/destroystokyo/", "org/spigotmc/")


def parse_constant_pool(data):
    """(定数プール, インデックス -> 種類)。"""
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    at = 10
    index = 1

    while index < count:
        tag = data[at]
        at += 1

        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, at)[0]
            pool[index] = ("utf8", data[at + 2:at + 2 + length].decode("utf-8", "replace"))
            at += 2 + length
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pool[index] = (tag, struct.unpack_from(">H", data, at)[0])
            at += 2
        elif tag == 15:  # MethodHandle
            pool[index] = (tag, struct.unpack_from(">BH", data, at))
            at += 3
        elif tag in (3, 4):  # Integer, Float
            pool[index] = (tag, None)
            at += 4
        elif tag in (5, 6):  # Long, Double は 2 つ分使う
            pool[index] = (tag, None)
            at += 8
            index += 1
        else:  # Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
            pool[index] = (tag, struct.unpack_from(">HH", data, at))
            at += 4

        index += 1

    return pool


def utf8(pool, index):
    entry = pool[index]

    return entry[1] if entry and entry[0] == "utf8" else None


def class_name(pool, index):
    entry = pool[index]

    return utf8(pool, entry[1]) if entry and entry[0] == 7 else None


def references(data):
    """(所有クラス, 名前, 署名) の集合。NMS のものだけ。"""
    pool = parse_constant_pool(data)
    out = set()

    for entry in pool:
        if not entry or entry[0] not in (9, 10, 11):
            continue

        owner = class_name(pool, entry[1][0])
        name_and_type = pool[entry[1][1]]

        if owner is None or not owner.startswith(NMS) or not name_and_type:
            continue

        out.add((owner, utf8(pool, name_and_type[1][0]), utf8(pool, name_and_type[1][1])))

    return out


def declared(data):
    """(クラス名, 親, interface の並び, {(名前, 署名)})。"""
    pool = parse_constant_pool(data)
    at = 10
    index = 1
    count = struct.unpack_from(">H", data, 8)[0]

    # 定数プールを読み飛ばす(parse_constant_pool と同じ歩幅)
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

    at += 2  # access_flags
    this_class = class_name(pool, struct.unpack_from(">H", data, at)[0])
    at += 2
    super_index = struct.unpack_from(">H", data, at)[0]
    super_class = class_name(pool, super_index) if super_index else None
    at += 2
    interface_count = struct.unpack_from(">H", data, at)[0]
    at += 2
    interfaces = []

    for _ in range(interface_count):
        interfaces.append(class_name(pool, struct.unpack_from(">H", data, at)[0]))
        at += 2

    members = set()

    for _ in range(2):  # fields, methods
        n = struct.unpack_from(">H", data, at)[0]
        at += 2

        for _ in range(n):
            name = utf8(pool, struct.unpack_from(">H", data, at + 2)[0])
            desc = utf8(pool, struct.unpack_from(">H", data, at + 4)[0])
            members.add((name, desc))
            attrs = struct.unpack_from(">H", data, at + 6)[0]
            at += 8

            for _ in range(attrs):
                length = struct.unpack_from(">I", data, at + 2)[0]
                at += 6 + length

    return this_class, super_class, interfaces, members


def load_server(path):
    """クラス名 -> (親, interface, メンバー)。"""
    classes = {}

    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.startswith("net/minecraft/") and name.endswith(".class"):
                this, parent, interfaces, members = declared(jar.read(name))
                classes[this] = (parent, interfaces, members)

    return classes


def has(classes, owner, name, desc, seen=None):
    seen = seen or set()

    if owner in seen:
        return False

    seen.add(owner)
    entry = classes.get(owner)

    if entry is None:
        return None  # そのクラス自体が無い

    parent, interfaces, members = entry

    if (name, desc) in members:
        return True

    for up in ([parent] if parent else []) + list(interfaces):
        if up and up.startswith("net/minecraft/") and has(classes, up, name, desc, seen):
            return True

    return False


def main():
    server, plugin = sys.argv[1:3]
    prefix = sys.argv[3] if len(sys.argv) > 3 else ""
    classes = load_server(server)
    wanted = set()

    with zipfile.ZipFile(plugin) as jar:
        for name in jar.namelist():
            if name.endswith(".class") and name.startswith(prefix):
                try:
                    wanted |= references(jar.read(name))
                except Exception as e:
                    print("読めない %s: %s" % (name, e))

    missing = []
    unknown = []

    for owner, name, desc in sorted(wanted):
        result = has(classes, owner, name, desc)

        if result is None:
            unknown.append((owner, name, desc))
        elif not result:
            missing.append((owner, name, desc))

    print("見た参照: %d" % len(wanted))
    print("サーバーに無いメンバー: %d" % len(missing))

    for owner, name, desc in missing:
        print("  %s.%s%s" % (owner, name, desc))

    if unknown:
        print("そもそも無いクラス: %d" % len(set(o for o, _, _ in unknown)))

        for owner in sorted(set(o for o, _, _ in unknown)):
            print("  %s" % owner)


if __name__ == "__main__":
    main()
