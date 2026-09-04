# -*- coding: utf-8 -*-
"""Modrinth から MOD とプラグインを取ってくる。tools/real-plugins.sh と tools/dist.sh が使う。

    python tools/fetch_addons.py <置き場所> [--only mods|plugins]

置き場所の下に mods/ と plugins/ を作って入れる。既にあるものは飛ばす。
対象は下の LIST。26.2 に対応した版が無いものは、その旨を出して飛ばす。
"""
import io
import json
import os
import sys
import urllib.parse
import urllib.request

GAME = "26.2"
API = "https://api.modrinth.com/v2"
AGENT = "shifu-dev (https://github.com/asutarisucu)"

# (slug, 置き場所, loader)。loader は Modrinth の facet に合わせる
LIST = [
    # --- Fabric の MOD ---
    # 土台
    ("fabric-api", "mods", "fabric"),
    ("architectury-api", "mods", "fabric"),
    ("owo-lib", "mods", "fabric"),
    ("puzzles-lib", "mods", "fabric"),
    ("bookshelf-lib", "mods", "fabric"),
    ("resourceful-lib", "mods", "fabric"),
    ("cristel-lib", "mods", "fabric"),
    ("iceberg", "mods", "fabric"),
    ("fzzy-config", "mods", "fabric"),
    ("balm", "mods", "fabric"),
    ("prickle", "mods", "fabric"),
    ("fabric-language-kotlin", "mods", "fabric"),
    ("almanac", "mods", "fabric"),
    ("forge-config-api-port", "mods", "fabric"),
    ("shogi", "mods", "fabric"),
    # 性能(vanilla の処理に mixin を当てる)
    ("lithium", "mods", "fabric"),
    ("ferrite-core", "mods", "fabric"),
    ("krypton", "mods", "fabric"),
    ("c2me-fabric", "mods", "fabric"),
    ("lmd", "mods", "fabric"),
    # 登録を足す(ブロック・アイテム・エンティティ・ルート)
    ("waystones", "mods", "fabric"),
    ("carry-on", "mods", "fabric"),
    ("lootr", "mods", "fabric"),
    ("toms-storage", "mods", "fabric"),
    ("travelersbackpack", "mods", "fabric"),
    ("natures-compass", "mods", "fabric"),
    ("visual-workbench", "mods", "fabric"),
    ("comforts", "mods", "fabric"),
    ("attributefix", "mods", "fabric"),
    ("netherportalfix", "mods", "fabric"),
    # 世界生成を変える
    ("glitchcore", "mods", "fabric"),
    ("terrablender", "mods", "fabric"),
    ("lithostitched", "mods", "fabric"),
    ("terralith", "mods", "fabric"),
    ("biomes-o-plenty", "mods", "fabric"),
    ("dungeons-and-taverns", "mods", "fabric"),
    # 保護・遊びの仕組み
    # --- Bukkit / Paper のプラグイン ---
    ("worldedit", "plugins", "paper"),
    ("worldguard", "plugins", "paper"),
    ("fastasyncworldedit", "plugins", "paper"),
    ("luckperms", "plugins", "paper"),
    ("multiverse-core", "plugins", "paper"),
    ("multiverse-inventories", "plugins", "paper"),
    ("viaversion", "plugins", "paper"),
    ("viabackwards", "plugins", "paper"),
    ("chunky", "plugins", "paper"),
    ("chunkyborder", "plugins", "paper"),
    ("tab-was-taken", "plugins", "paper"),
    ("tabtps", "plugins", "paper"),
    ("veinminer", "plugins", "paper"),
    ("placeholderapi", "plugins", "paper"),
    ("decentholograms", "plugins", "paper"),
    ("fancyholograms", "plugins", "paper"),
    ("fancynpcs", "plugins", "paper"),
    ("nbtapi", "plugins", "paper"),
    ("minimotd", "plugins", "paper"),
    ("invsee++", "plugins", "paper"),
    ("mclogs", "plugins", "paper"),
    ("grimac", "plugins", "paper"),
    # 遊びの仕組み・記録・スクリプト・地図
    ("skript", "plugins", "paper"),
    ("griefprevention", "plugins", "paper"),
    ("huskhomes", "plugins", "paper"),
    ("quickshop-hikari", "plugins", "paper"),
    ("authmereloaded", "plugins", "paper"),
    ("bluemap", "plugins", "paper"),
    # --- サーバー側で効く MOD(2 巡目)---
    ("carpet", "mods", "fabric"),
    ("alternate-current", "mods", "fabric"),
    ("servercore", "mods", "fabric"),
    ("vmp-fabric", "mods", "fabric"),
    ("no-chat-reports", "mods", "fabric"),
    ("fabric-language-kotlin", "mods", "fabric"),   # ledger が要る
    ("ledger", "mods", "fabric"),
    ("styled-chat", "mods", "fabric"),
    ("spark", "mods", "fabric"),
    ("chunky", "mods", "fabric"),
    # packetevents は 26.2 の packet id を持っていない(ログインで切られる)ので入れない
]


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})

    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def pick(slug, loader):
    """その loader で GAME に対応した、いちばん新しい版のファイル。"""
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([GAME])})
    versions = get("%s/project/%s/version?%s" % (API, slug, query))

    for version in versions:
        for file in version["files"]:
            if file.get("primary", True) and file["filename"].endswith(".jar"):
                return version["version_number"], file["url"], file["filename"]

    return None


def main():
    root = sys.argv[1]
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None

    for slug, kind, loader in LIST:
        if only and kind != only:
            continue

        target_dir = os.path.join(root, kind)
        os.makedirs(target_dir, exist_ok=True)

        if any(name.startswith(slug + "-") for name in os.listdir(target_dir)):
            print("  %-18s あり" % slug)
            continue

        try:
            found = pick(slug, loader)
        except Exception as e:
            print("  %-18s 取れない: %s" % (slug, e))
            continue

        if found is None:
            print("  %-18s %s に対応した %s 版が無い" % (slug, GAME, loader))
            continue

        number, url, name = found
        path = os.path.join(target_dir, "%s-%s.jar" % (slug, number))
        request = urllib.request.Request(url, headers={"User-Agent": AGENT})

        with urllib.request.urlopen(request, timeout=180) as response:
            data = response.read()

        io.open(path, "wb").write(data)
        print("  %-18s %s (%.1f MB)" % (slug, number, len(data) / 1048576.0))


if __name__ == "__main__":
    main()
