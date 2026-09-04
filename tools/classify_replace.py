# -*- coding: utf-8 -*-
"""vanilla の行が消えている塊を、直せる形かどうかで分ける。

`make_events.py` は「足すだけ」の塊しか規則にしない。残りは 332 件ある。
その中身は一様ではない。

  囲んだだけ   消えた行が、足した行の中にそのまま入っている。
               Paper は字下げを変えただけ。`insert` と `insert-after` で
               同じことが書ける。**vanilla の行は 1 文字も変わらない。**
  書き換え     消えた行が足した行の中に無い。中身が変わっている。
               Shifu の作りでは入れられない。

    python tools/classify_replace.py <patches/sources> [出力先]
"""
import io
import os
import sys
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_events
import make_events


def same(a, b):
    return a.strip() == b.strip()


def wrapped_only(removed, added):
    """消えた行が、足した行の中に順序どおり全部あるか。"""
    at = 0
    keep = [l for l in removed if l.strip()]

    for line in keep:
        while at < len(added) and not same(added[at], line):
            at += 1

        if at == len(added):
            return False

        at += 1

    return bool(keep)


def main():
    sources = sys.argv[1]
    dest = sys.argv[2] if len(sys.argv) > 2 else None

    kinds = collections.Counter()
    rows = []

    for root, _, files in os.walk(sources):
        for f in sorted(files):
            if not f.endswith(".java.patch"):
                continue

            rel = os.path.relpath(os.path.join(root, f),
                                  sources).replace("\\", "/")[:-len(".patch")]

            for hunk in scan_events.parse(os.path.join(root, f)):
                for kind, removed, added in make_events.hunk_runs(hunk):
                    if kind != "chg" or not removed:
                        continue

                    if not any(scan_events.FIRE.search(l) for l in added):
                        continue

                    how = ("囲んだだけ" if wrapped_only(removed, added)
                           else "書き換え")
                    kinds[how] += 1
                    rows.append((rel, how, scan_events.events_in(added),
                                 removed, added))

    print("vanilla の行が消えている塊 %d" % len(rows))

    for how, n in kinds.most_common():
        print("  %-12s %d" % (how, n))

    if dest:
        with io.open(dest, "w", encoding="utf-8", newline="\n") as fh:
            for rel, how, names, removed, added in rows:
                fh.write("=== %s [%s] %s\n" % (rel, how, ",".join(names)))

                for l in removed:
                    fh.write("  --  %s\n" % l)

                for l in added:
                    fh.write("  ++  %s\n" % l)

                fh.write("\n")

        print("書き出し", dest)


if __name__ == "__main__":
    main()
