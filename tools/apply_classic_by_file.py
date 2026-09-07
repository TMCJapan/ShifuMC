# -*- coding: utf-8 -*-
"""1.21.4 より前の Paper のパッチを、ファイルごとの塊に割って当てる。

    python tools/apply_classic_by_file.py <パッチの置き場> <当てる git リポジトリ>

**当てる前に `git reset --hard <素の状態>` しておくこと。**当たらなかったものを
やり直すときにファイルを索引から戻すので、索引が当て終わった状態だと戻せない。

`tools/apply_classic_patches.py` はパッチを 1 件ずつ `git am` に渡す。1053 件の
積み順がリポジトリから読めないので、どう並べても 2 割で止まる。

こちらは 1053 件を並べるのをやめる。パッチを `diff --git` で割って**対象ファイルごとの
塊**にし、当たるものから当てて、当たらなかったものを次の周回に回す。塊が要る文脈行を
別の塊が足すなら、その塊が当たった次の周回で当たる。

統一 diff は他のファイルを見ないので、依存は同じファイルの中で閉じている。
並べるのは 3426 件を通してではなく、1 ファイルあたり数件で済む。

## ファイルの中での順序

当たる順に当てるだけだと、**消す行**で取りこぼす。1.20.6 の SnifferEggBlock では

* `Call-BlockGrowEvent-for-missing-blocks` が `world.setBlock(pos, ...)` を消す
* `Add-and-fix-missing-BlockFadeEvents` はその行を文脈にしている

前者が先に当たると後者はもう当たらない。行の貸し借りで辺を張って並べ直す。

* j が足す行を i が要る -> j が先
* i が消す行を j が要る -> j が先

そのうえで、**貸し借りの相手が当たるまで手を出さない**。落ちた塊を飛ばして次を当てると、
次の塊が文脈行を書き換えて、落ちた塊が二度と当たらなくなる。

## ハンクまで割る

塊のままだと、同じパッチの中で貸し借りの向きが逆のハンクが混ざったときに輪になる。
1.20.6 の MinecraftServer では `Timings-v2` と `Server-Tick-Events` が互いを待って
10 件落ちた。落ちたファイルだけハンクまで割ってやり直すと、124 ハンク全部が入る。

## 当たり方(1.20.6)

| 当て方 | 当たった件数 |
|---|---|
| `git am` に 1 件ずつ(`apply_classic_patches.py`) | パッチ 218 / 1053 |
| ファイルごとの塊、当たる順 | 塊 3407 / 3425 |
| 貸し借りで並べる | 塊 3413 / 3425 |
| 相手を待つ + 落ちたファイルはハンクまで割る | 3499 / 3501 |

`patches/api`(1274 件)は全部入る。残る 2 件は `ServerGamePacketListenerImpl` の
`Brigadier-Mojang-API` と `Don-t-tab-complete-namespaced-commands`。1 つのハンクが
互いの追加行を文脈にしていて、どちらを先にしても当たらない。
`logo.png` の二値差分は `index` 行を落とすと当てられないので、最初から外す。
"""

import collections
import io
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from apply_classic_patches import DIFF, ENV, distinctive, introduced, recount

SIGNATURE = re.compile(r"^-- \s*$")
BINARY = re.compile(r"^GIT binary patch\s*$", re.M)


def chunks(path):
    """パッチを (対象ファイル, その塊の本文) に割る。"""
    lines = io.open(path, encoding="utf-8", errors="replace", newline="").read().split("\n")
    out = []
    current = None
    target = None

    def flush():
        # 末尾の空行はファイルの改行の余り。残すと git が「本文の途中に空行」と読んで
        # corrupt patch になる(--recount を付けても弾かれる)
        while current and not current[-1]:
            current.pop()

        if current:
            out.append((target, "\n".join(current) + "\n"))

    for line in lines:
        hit = DIFF.match(line)

        if hit:
            flush()
            target = hit.group(2)
            current = [line]
            continue

        if current is None:
            continue

        # format-patch の署名から下はパッチではない
        if SIGNATURE.match(line):
            break

        current.append(line)

    flush()

    return out


def split_hunks(text):
    """塊を 1 ハンクずつの差分に割る。

    `git apply` はファイル単位で全か無かなので、8 個中 1 個が当たらないと残り 7 個も
    落ちる。1.20.6 の MinecraftServer では、別のパッチが文脈行を書き換えたせいで
    `Timings-v2` の 8 個中 1 個だけが当たらない。割れば残りは入る。
    """
    lines = text.split("\n")
    head = []
    out = []
    body = None

    for line in lines:
        if line.startswith("@@ "):
            if body:
                out.append("\n".join(head + body) + "\n")

            body = [line]
            continue

        if body is None:
            head.append(line)
        else:
            body.append(line)

    if body:
        out.append("\n".join(head + body) + "\n")

    return out


def sides(text):
    """塊の (足す行, 消す行, 要る行)。要る行 = 文脈行と消す行。"""
    adds = set()
    dels = set()
    needs = set()
    body = False

    for raw in text.split("\n"):
        line = raw.rstrip()

        if line.startswith("@@ "):
            body = True
            continue

        if line.startswith(("diff --git ", "index ", "--- ", "+++ ")):
            body = False
            continue

        if not body:
            continue

        word = line[1:].strip()

        if not distinctive(word):
            continue

        if line.startswith("+"):
            adds.add(word)
        elif line.startswith("-"):
            dels.add(word)
            needs.add(word)
        elif line.startswith(" "):
            needs.add(word)

    return adds, dels, needs


def deps(items):
    """塊どうしの貸し借り。i -> i より先に当てるものの番号。

    * j が足す行を i が要る  -> j が先
    * i が消す行を j が要る  -> j が先
    """
    parts = [sides(text) for _, _, text in items]
    after = collections.defaultdict(set)

    for i, (_, i_del, i_need) in enumerate(parts):
        after[i]

        for j, (j_add, _, j_need) in enumerate(parts):
            if i == j:
                continue

            if (j_add & i_need) or (i_del & j_need):
                after[i].add(j)

    return after


def in_file_order(items):
    """1 つのファイルに当たる塊を、行の貸し借りで並べる。

    items は (履歴での順番, パッチ名, 本文) の並び。輪になったものは元の順に残す。
    """
    if len(items) < 2:
        return items

    after = deps(items)
    ready = [i for i in range(len(items)) if not after[i]]
    seen = set()
    out = []

    while ready:
        i = ready.pop(0)

        if i in seen:
            continue

        seen.add(i)
        out.append(i)
        grew = False

        for j in range(len(items)):
            if j in seen or i not in after[j]:
                continue

            after[j].discard(i)

            if not after[j]:
                ready.append(j)
                grew = True

        if grew:
            ready.sort()

    out += [i for i in range(len(items)) if i not in seen]

    return [items[i] for i in out]


def git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), env=ENV,
                          capture_output=True, text=True, errors="replace")


def apply_one(repo, text):
    handle = tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False,
                                         encoding="utf-8", newline="")
    handle.write(recount(text, repo))
    handle.close()

    try:
        return git(repo, "apply", "-C1", "--recount", "--ignore-whitespace",
                   "--whitespace=nowarn", handle.name)
    finally:
        os.unlink(handle.name)


def restore(repo, path):
    """1 つのファイルを当てる前に戻す。

    `git apply` は索引を触らないので、索引にはまだ元の中身が入っている。
    パッチが作ったファイルは索引に無いので、消す。
    """
    if git(repo, "checkout", "--", path).returncode == 0:
        return

    full = os.path.join(repo, path.replace("/", os.sep))

    if os.path.exists(full):
        os.remove(full)


def run_units(repo, path, units):
    """1 つのファイルに差分の塊を当てる。

    貸し借りの相手が当たるまでは手を出さない。**当たらなかった塊を飛ばして次を当てると、
    次の塊が文脈行を書き換えてしまい、先の塊が二度と当たらなくなる。**1.20.6 の
    MinecraftServer では `Timings-v2` の文脈行を
    `Throw-exception-on-world-create-while-being-ticked` が書き換えて、10 件落ちた。

    相手が落ちて止まったときだけ、待つのをやめて当たるものを当てる。
    """
    restore(repo, path)
    items = in_file_order(units)
    after = deps(items)
    applied = set()
    pending = list(range(len(items)))
    stuck = {}
    wait = True

    while pending:
        moved = False
        rest = []

        for i in pending:
            if wait and not after[i] <= applied:
                rest.append(i)
                continue

            result = apply_one(repo, items[i][2])

            if result.returncode == 0:
                applied.add(i)
                moved = True
            else:
                stuck[items[i][1]] = (result.stdout + result.stderr).strip()
                rest.append(i)

        pending = rest

        if moved:
            wait = True
        elif wait:
            wait = False
        else:
            break

    return ([items[i] for i in sorted(applied)],
            [items[i] for i in pending], stuck)


def hunk_units(items):
    """塊をハンクごとに割って、同じ形の並びにする。"""
    out = []

    for order, name, text in items:
        pieces = split_hunks(text)

        for n, piece in enumerate(pieces, 1):
            out.append((order, name if len(pieces) < 2 else f"{name} #{n}", piece))

    return out


def run_file(repo, path, items):
    """1 つのファイルに塊を当てる。落ちたらハンクまで割ってやり直す。

    塊のままだと、同じパッチの中で貸し借りの向きが逆のハンクが混ざったときに輪になる。
    1.20.6 の MinecraftServer では `Timings-v2` と `Server-Tick-Events` が互いを待って
    10 件落ちた。ハンクまで割ると輪がほどける。
    """
    applied, failed, stuck = run_units(repo, path, items)

    if not failed:
        return applied, failed, stuck

    lost = sum(len(split_hunks(t)) for _, _, t in failed)
    pieces = hunk_units(items)
    again, failed2, stuck2 = run_units(repo, path, pieces)

    if len(failed2) < lost:
        return again, failed2, stuck2

    return run_units(repo, path, items)


def main():
    root, repo = sys.argv[1:3]
    rank = introduced(root)
    work = collections.defaultdict(list)
    total = 0
    binaries = []

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".patch"):
                continue

            for target, text in chunks(os.path.join(base, name)):
                # 二値差分は index 行を落とすと当てられない(前像のハッシュが要る)
                if BINARY.search(text):
                    binaries.append((target, name))
                    continue

                work[target].append((rank.get(name, len(rank)), name, text))
                total += 1

    print(f"塊 {total} 件 / ファイル {len(work)} 件")
    done = 0
    seen = 0
    stuck = {}
    work = {t: sorted(v, key=lambda x: (x[0], x[1])) for t, v in work.items()}

    for n, (target, items) in enumerate(sorted(work.items()), 1):
        applied, failed, why = run_file(repo, target, items)
        # ハンクまで割った ファイル は数え方が変わるので、当てた単位で数える
        done += len(applied)
        seen += len(applied) + len(failed)
        work[target] = failed

        for item in failed:
            stuck[(target, item[1])] = why.get(item[1], "")

        if n % 200 == 0:
            print(f"  {n} / {len(work)} ファイル: 当たった {done} / {seen}")

    print(f"  当たった {done} / {seen}(塊 {total} 件をハンクまで割った数)")

    if binaries:
        print(f"二値差分なので当てていない: {len(binaries)} 件 "
              + ", ".join(t for t, _ in binaries))

    left = [(t, n) for t, v in sorted(work.items()) for _, n, _ in v]

    if left:
        print(f"\n当たらなかった塊: {len(left)} 件 / ファイル {len({t for t, _ in left})} 件")

        for target, name in left[:30]:
            print(f"  {target}\n    {name}\n    {stuck.get((target, name), '')[:200]}")

    return 0 if not left else 1


if __name__ == "__main__":
    raise SystemExit(main())
