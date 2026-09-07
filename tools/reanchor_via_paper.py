# -*- coding: utf-8 -*-
"""当たらなくなったアンカーを、Paper のパッチをたどって引き直す。

    python tools/reanchor_via_paper.py <規則の置き場> <古い patches/sources> [--all]

`tools/reanchor.py` は「今の木の中で似ている行」を探す。バージョンが変わって
コードの形ごと変わっていると、似ている行が無くなって候補を出せない。

こちらは別の道をたどる。**Shifu の手書きの規則は、どれも Paper のハンクを読んで
書いてある。** Paper はバージョンごとに自分のパッチを保守しているので、
同じ発火は新しい版のパッチにも(だいたい)ある。

    規則の本文 → 発火層のメソッド → Bukkit のイベント名
      → 古い版の Paper のハンク → 新しい版の Paper のハンク
        → そのハンクの文脈行(= 新しい版の vanilla の行)→ アンカーの候補

26.2 と 1.21.11 で測った効き(規則 970 件を後追いで確認した):

* Paper のハンクは (ファイル, イベント) 単位で 88% が残る(行そのものの一致は 20%)
* 規則の 51% で候補を出せた
* そのうち 56% は、人が選んだのと同じ位置を上位 3 件に含んでいた

`reanchor.py` が「似た行を探す」で拾えるものは先にそちらで片付く。これは
**コードの形ごと変わっていて似た行が無い**ものに使う。2 つは補い合う。

**候補を出すだけで書き換えはしない。** `reanchor.py` と同じで、当てる位置は人が決める。

新しい版の木と `patches/sources` は `tools/env.sh` / `tools/paths.py` が指す方
(`SHIFU_PAPER`)を見る。古い版の `patches/sources` だけ引数で渡す。

**木は発火を当てる前の状態で見る。** 当て終わった木で回すと、差し込んだ行が
アンカーと同じ形になっているものが「2 箇所にある」に化ける。
`git -C <木> reset --hard $SHIFU_BASE` してから回すか、`closure.sh` の途中で見る。
"""

import collections
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import make_events as me
import paths
import scan_events
from apply_events import parse

EVENT = re.compile(r"\b([A-Z]\w*Event)\b")
NEW_EVENT = re.compile(r"new\s+(?:[\w.]*\.)?([A-Z]\w*Event)\s*\(")
CALL = re.compile(r"dev\.shifu\.event\.\w+\.(\w+)\(")
METHOD = re.compile(r"^    (?:public|private|static|final|\s)*[\w.<>,?\[\]@ ]+\s(\w+)\(")
MAX_ANCHOR = 8
TOP = 3
MAX_CANDIDATES = 40


def helper_events(src):
    """発火層のメソッド名 -> そのメソッドが作る Bukkit のイベント名。"""
    table = collections.defaultdict(set)

    for name in sorted(os.listdir(src)):
        if not name.endswith(".java"):
            continue

        current = None
        depth = 0

        for line in io.open(os.path.join(src, name), encoding="utf-8").read().split("\n"):
            match = METHOD.match(line)

            if match and depth <= 1:
                current = match.group(1)

            if current:
                table[current].update(EVENT.findall(line))

            depth += line.count("{") - line.count("}")

    return table


def events_of(rule, table):
    """規則が出すイベントの名前。"""
    body = "\n".join(rule.before + rule.after)
    names = set(EVENT.findall(body))

    for helper in CALL.findall(body):
        names |= table.get(helper, set())

    return names


def factory_events():
    """CraftEventFactory の callXxx / handleXxx -> そこで作る Bukkit のイベント名。

    Paper のパッチは `CraftEventFactory.callEntityChangeBlockEvent(...)` のように
    工場のメソッド名で書く。Shifu の規則から引けるのはイベントの クラス 名なので、
    その 2 つを繋がないと同じ発火だと分からない。
    """
    path = os.path.join(paths.PAPER, "paper-server", "src", "main", "java",
                        "org", "bukkit", "craftbukkit", "event", "CraftEventFactory.java")
    table = collections.defaultdict(set)

    if not os.path.exists(path):
        return table

    current = None
    depth = 0

    for line in io.open(path, encoding="utf-8", errors="replace").read().splitlines():
        match = METHOD.match(line)

        if match and depth <= 1:
            current = match.group(1)

        if current:
            table[current].update(NEW_EVENT.findall(line))

        depth += line.count("{") - line.count("}")

    return table


def hunk_index(root, factory):
    """(ファイル, イベント名) -> [ハンクの本文]。"""
    index = collections.defaultdict(list)

    for base, _, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".java.patch"):
                continue

            rel = os.path.relpath(os.path.join(base, name), root)
            rel = rel.replace("\\", "/")[:-len(".patch")]

            for hunk in scan_events.parse(os.path.join(base, name)):
                fired = [l[1:] for l in hunk if l.startswith("+") and scan_events.FIRE.search(l)]

                if not fired:
                    continue

                names = set()

                for event in scan_events.events_in(fired):
                    names.add(event)
                    names |= factory.get(event, set())

                for event in names:
                    index[(rel, event)].append(hunk)

    return index


def vanilla_lines(hunk):
    """ハンクの中で vanilla に在る行(文脈と、Paper が消した行)。

    消した行も vanilla の行なので、Shifu の側では残っている。アンカーに使える。
    """
    out = []

    for line in hunk:
        if line.startswith("+"):
            out.append(None)
        elif line.startswith("-"):
            out.append(line[1:])
        else:
            out.append(line[1:] if line[:1] == " " else line)

    return out


def fire_at(hunk):
    """発火の行が始まる位置。"""
    for i, line in enumerate(hunk):
        if line.startswith("+") and scan_events.FIRE.search(line):
            return i

    return None


def overlap(a, b):
    """2 つの行の並びが、空白を潰してどれだけ重なるか。"""
    left = {re.sub(r"\s+", " ", l.strip()) for l in a if l and l.strip()}
    right = {re.sub(r"\s+", " ", l.strip()) for l in b if l and l.strip()}

    if not left or not right:
        return 0.0

    return len(left & right) / len(left | right)


def added_of(hunk):
    return [l[1:] for l in hunk if l.startswith("+")]


def propose(hunk, body, want_before):
    """新しい版のハンクから、アンカーの候補を出す。

    want_before が真なら「発火の後ろにある vanilla の行」(insert のアンカー)、
    偽なら「発火の前にある vanilla の行」(insert-after のアンカー)。
    """
    at = fire_at(hunk)

    if at is None:
        return None

    lines = vanilla_lines(hunk)

    if want_before:
        rest = [l for l in lines[at:] if l is not None]
    else:
        rest = [l for l in reversed(lines[:at]) if l is not None]
        rest.reverse()

    rest = [l for l in rest if l.strip()]

    if not rest:
        return None

    for size in range(1, min(MAX_ANCHOR, len(rest)) + 1):
        want = rest[:size] if want_before else rest[-size:]
        probe = [l.strip() for l in want]
        hits = sum(1 for i in range(len(body) - len(probe) + 1)
                   if body[i:i + len(probe)] == probe)

        if hits == 1:
            return want

        if hits == 0:
            return None

    return None


def main():
    rules_root, old_sources = sys.argv[1:3]
    show_all = "--all" in sys.argv
    table = helper_events(os.path.join(paths.SHIFU, "src", "event", "java", "dev", "shifu", "event"))
    factory = factory_events()
    old_index = hunk_index(old_sources, factory)
    new_index = hunk_index(paths.SOURCES, factory)
    cache = {}
    counts = collections.Counter()

    for base, _, files in os.walk(rules_root):
        for name in sorted(files):
            if not name.endswith(".rules"):
                continue

            path = os.path.join(base, name)
            rules = parse(io.open(path, encoding="utf-8").read(), name)

            for rule in rules:
                body = me.vanilla(paths.TREE, rule.target, cache)

                if body is None:
                    counts["ファイルが無い"] += 1
                    continue

                want = [l.strip() for l in rule.anchor]
                hits = sum(1 for i in range(len(body) - len(want) + 1)
                           if body[i:i + len(want)] == want)

                if hits == rule.count and not show_all:
                    counts["当たる"] += 1
                    continue

                applies = hits == rule.count

                events = events_of(rule, table)

                if not events:
                    counts["イベント名が引けない"] += 1
                    continue

                old_hunks = [h for e in sorted(events) for h in old_index.get((rule.target, e), [])][:MAX_CANDIDATES]
                new_hunks = [h for e in sorted(events) for h in new_index.get((rule.target, e), [])][:MAX_CANDIDATES]

                if not new_hunks:
                    counts["新しい版に同じ発火が無い"] += 1
                    continue

                # 古い版のハンクのうち、今のアンカーに一番近いものを選ぶ
                best_old = None

                if old_hunks:
                    best_old = max(old_hunks, key=lambda h: overlap(vanilla_lines(h), rule.anchor))

                # 新しい版では、その古いハンクの追加行に一番近いものを選ぶ
                if best_old is not None:
                    ranked = sorted(new_hunks, key=lambda h: -overlap(added_of(h), added_of(best_old)))
                else:
                    ranked = new_hunks

                seen = []

                for hunk in ranked:
                    one = propose(hunk, body, bool(rule.before))

                    if one and one not in seen:
                        seen.append(one)

                    if len(seen) >= TOP:
                        break

                if not seen:
                    counts["ハンクは見つかるがアンカーが定まらない"] += 1
                    continue

                got = seen[0]

                if applies:
                    # 後追いの確認。今のアンカーと同じ位置を指したかを数える
                    here = next(i for i in range(len(body) - len(want) + 1)
                                if body[i:i + len(want)] == want)
                    at_now = here if rule.before else here + len(want)
                    probe = [l.strip() for l in got]
                    there = next((i for i in range(len(body) - len(probe) + 1)
                                  if body[i:i + len(probe)] == probe), None)
                    at_got = None if there is None else (there if rule.before else there + len(probe))
                    ok = False

                    for cand in seen:
                        probe = [l.strip() for l in cand]
                        there = next((i for i in range(len(body) - len(probe) + 1)
                                      if body[i:i + len(probe)] == probe), None)

                        if there is not None and (there if rule.before else there + len(probe)) == at_now:
                            ok = True
                            break

                    counts["1 つめが当たり" if at_got == at_now else
                           (f"上位 {TOP} に当たりがある" if ok else "候補に当たりが無い")] += 1

                    if ok or not show_all:
                        continue

                counts["候補を出せた"] += 1
                print(f"{rule.where}: {rule.target}  ({', '.join(sorted(events))})")

                for line in rule.anchor:
                    print(f"    - {line.strip()[:110]}")

                for n, cand in enumerate(seen, 1):
                    mark = "+" if n == 1 else f"{n}"

                    for line in cand:
                        print(f"    {mark} {line.strip()[:110]}")

                print()

    print(" / ".join(f"{k} {v}" for k, v in counts.most_common()), file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
