# -*- coding: utf-8 -*-
"""agent の jar を組む。build.sh から呼ぶ。

    python tools/tickstop/make_jar.py <classes のディレクトリ> <asm の jar> <出力 jar>
"""
import os
import sys
import zipfile


def main():
    classes, asm, dest = sys.argv[1:4]

    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as out:
        # Boot-Class-Path で bootstrap 側にも同じ jar を載せる。載せないと、差し込んだ呼び出しが
        # サーバーのクラスローダから TickStop を見つけられない(Paper の bundler は system loader へ
        # 委譲しない)。同じ jar なので委譲で 1 つのクラスに解決され、静的な状態も 1 つで済む。
        out.writestr("META-INF/MANIFEST.MF",
                     "Manifest-Version: 1.0\n"
                     "Premain-Class: dev.shifu.tickstop.TickStop\n"
                     "Boot-Class-Path: %s\n\n" % os.path.basename(dest))

        for base, _, names in os.walk(classes):
            for name in sorted(names):
                path = os.path.join(base, name)
                out.write(path, os.path.relpath(path, classes).replace(os.sep, "/"))

        with zipfile.ZipFile(asm) as src:
            for info in src.infolist():
                if info.filename.startswith("org/") and info.filename.endswith(".class"):
                    out.writestr(info.filename, src.read(info.filename))

        count = len(out.namelist())

    print("-> %s (%d entries)" % (dest, count))


if __name__ == "__main__":
    main()
