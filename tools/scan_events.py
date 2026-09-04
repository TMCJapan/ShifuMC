# -*- coding: utf-8 -*-
"""Paper のパッチから、イベントを発火している位置を取り出す。

Shifu の NMS は vanilla なので、Paper が入れた発火は 1 件も入っていない。
どこに何を入れるかは `paper-server/patches/sources/**.java.patch` が持っている。
統一 diff なので、変更の塊ごとに「前後の vanilla の行」と「足す文」が読める。
前後の vanilla の行がそのままアンカーになる。

    python tools/scan_events.py <patches/sources> [出力先]

形で分ける:

  insert   足すだけ。vanilla の行は 1 行も消えていない。
           直後(または直前)の vanilla 行をアンカーにしてそのまま入る。
  replace  vanilla の行が消えている。囲む形か、書き換えが要る。手で見る。
"""
import io
import os
import re
import sys
import collections

FIRE = re.compile(r"CraftEventFactory\.\w+|\.callEvent\(\)"
                  r"|new [\w.]*[A-Z]\w*Event\(|getPluginManager\(\)\.callEvent")
NAME = re.compile(r"CraftEventFactory\.(\w+)|new ([\w.]*?[A-Z]\w*Event)\(")


def events_in(lines):
    out = []
    for line in lines:
        for a, b in NAME.findall(line):
            out.append((a or b).split(".")[-1])
    return sorted(set(out))


def runs(body):
    """ハンクの本文を、変更のひと続きごとに切る。

    返すのは (前の文脈, 消えた行, 足した行, 後の文脈)。
    文脈は vanilla に在る行なのでアンカーに使える。
    """
    out = []
    i = 0
    while i < len(body):
        if body[i][:1] in "+-":
            j = i
            while j < len(body) and body[j][:1] in "+-":
                j += 1
            before = [l[1:] for l in body[max(0, i - 8):i] if l[:1] == " "]
            after = [l[1:] for l in body[j:j + 8] if l[:1] == " "]
            out.append((before,
                        [l[1:] for l in body[i:j] if l[:1] == "-"],
                        [l[1:] for l in body[i:j] if l[:1] == "+"],
                        after))
            i = j
        else:
            i += 1
    return out


def parse(path):
    body = []
    for raw in io.open(path, encoding="utf-8").read().split("\n"):
        if raw.startswith(("--- ", "+++ ")):
            continue
        if raw.startswith("@@"):
            if body:
                yield body
            body = []
            continue
        if body is not None:
            body.append(raw)
    if body:
        yield body


def main():
    sources = sys.argv[1]
    dest = sys.argv[2] if len(sys.argv) > 2 else None

    rows = []
    shapes = collections.Counter()
    events = collections.Counter()
    per_file = collections.Counter()

    for root, _, files in os.walk(sources):
        for f in sorted(files):
            if not f.endswith(".java.patch"):
                continue
            rel = os.path.relpath(os.path.join(root, f),
                                  sources).replace("\\", "/")[:-len(".patch")]
            for hunk in parse(os.path.join(root, f)):
                for before, removed, added, after in runs(hunk):
                    fired = [l for l in added if FIRE.search(l)]
                    if not fired:
                        continue
                    kind = "insert" if not removed else "replace"
                    names = events_in(fired)
                    shapes[kind] += 1
                    per_file[rel] += 1
                    for n in names:
                        events[n] += 1
                    rows.append({"file": rel, "kind": kind, "events": names,
                                 "before": before, "removed": removed,
                                 "added": added, "after": after})

    print("発火のある変更 %d 件 / insert %d、replace %d"
          % (len(rows), shapes["insert"], shapes["replace"]))
    print("ファイル %d / イベントの種類 %d" % (len(per_file), len(events)))
    print()
    print("多い順:")
    for name, n in events.most_common(15):
        print("  %-42s %d" % (name, n))

    if dest:
        with io.open(dest, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("# Paper のパッチから取り出した発火位置。\n")
            fh.write("# insert = 足すだけ。replace = vanilla の行が消えている。\n\n")
            for r in rows:
                fh.write("=== %s [%s] %s\n"
                         % (r["file"], r["kind"], ",".join(r["events"])))
                for l in r["before"][-4:]:
                    fh.write("  ctx- %s\n" % l)
                for l in r["removed"]:
                    fh.write("  --   %s\n" % l)
                for l in r["added"]:
                    fh.write("  ++   %s\n" % l)
                for l in r["after"][:4]:
                    fh.write("  ctx+ %s\n" % l)
                fh.write("\n")
        print()
        print("書き出し", dest)
    return rows


if __name__ == "__main__":
    main()
