# -*- coding: utf-8 -*-
"""1.21.4 より前の Paper のパッチ(`patches/api` `patches/server`)を当てる。

    python tools/apply_classic_patches.py <パッチの置き場> <当てる git リポジトリ>

paperweight 1.7.1 の `applyPatches` はこの形のパッチを当てられない。理由が 3 つある。

1. **並び順がファイル名ではなく `Date:` 行**。Paper は 1.20.5 で番号を外したが、
   依存関係のある並びはコミットの日付で保たれている。ファイル名の順に当てると、
   後の版に足された文脈を先に探すことになって落ちる
   (`API-for-an-entity-s-scoreboard-name` は `Folia-scheduler-...` の行を文脈にしている)
2. **ハンクの見出しが `@@ -0,0 +0,0 @@` に潰されている**。git 2.45 は
   「数が 0 なのに本文が続く」を corrupt patch として弾く。行数を数え直せば通る
3. **`index` 行のハッシュも 0 に潰されている**。`git am --3way` はこれを
   偽の祖先の材料にしようとして `cache entry has null sha1` で落ちる。
   行を落とすと 3-way を諦めて、文脈で場所を探す普通の適用に落ちる

どれも見出しの数字と index 行しか触らない。**足す行も消す行も 1 文字も変えない。**

`core.autocrlf=true` の環境では、パッチ(LF)と作業ツリー(CRLF)が食い違って
全部落ちる。git の設定は触らず、環境変数で 1 回だけ渡す。

**リセットも同じ設定で行うこと。** 素の git で `reset --hard` すると CRLF で展開され、
このツールが autocrlf=false で見たときに「index と違う」になって 1 件目から落ちる。
数え直しの効きを測るときに 0 件と 22 件を行き来したのはこれが原因だった。

    export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false

## 並び順(SHIFU_PATCH_ORDER)

1.20.6 の `patches/server`(1053 件)で測った当たり方:

| 並べ方 | 当たった件数 |
|---|---|
| `name` ファイル名順(paperweight と同じ) | 0 |
| `date` `Date:` 行の順 | 2 |
| `topo` 依存で並べる(既定) | 218 |

`topo` は「あるパッチの文脈行・消す行を、どのパッチが足しているか」で辺を張って
トポロジカルに並べる。同じ強さのものは Paper の履歴での初出順にする。
それでも 2 割しか当たらない。**積み順そのものはリポジトリに残っていない。**
"""

import collections
import io
import os
import re
import subprocess
import sys
import tempfile
from email.utils import parsedate_to_datetime

HEAD = re.compile(r"^@@ -0,0 \+0,0 @@(.*)$")
NULL_INDEX = re.compile(r"^index 0{40}\.\.0{40}(?: \d+)?\s*$")
DATE = re.compile(r"^Date: (.+)$")
DIFF = re.compile(r"^diff --git a/(\S+) b/(\S+)\s*$")

ENV = dict(os.environ)
ENV.update({"GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "core.autocrlf",
            "GIT_CONFIG_VALUE_0": "false"})


def pre_image(repo, rel):
    """当てる先の実ファイルの blob ハッシュ。無ければ None。"""
    if not rel:
        return None

    if not os.path.exists(os.path.join(repo, rel.replace("/", os.sep))):
        return None

    done = subprocess.run(["git", "-C", repo, "hash-object", "--", rel],
                          env=ENV, capture_output=True, text=True)

    return done.stdout.strip() or None


def recount(text, repo):
    """潰された見出しに行数を入れ直し、潰された index 行に前像のハッシュを入れる。"""
    lines = text.split("\n")
    out = []
    i = 0
    current = None

    while i < len(lines):
        hit = DIFF.match(lines[i])

        if hit:
            current = hit.group(1)

        if NULL_INDEX.match(lines[i]):
            # 既定は行ごと落とす。git は 3-way を諦めて文脈で場所を探す。
            # SHIFU_PATCH_3WAY=1 のときだけ、当てる先の実ファイルから前像を埋める
            # (パッチが作られた中間状態とは違うので、ずれていると弾かれる)
            if os.environ.get("SHIFU_PATCH_3WAY"):
                blob = pre_image(repo, current)

                if blob:
                    out.append(f"index {blob}..{'0' * 40} 100644")

            i += 1
            continue

        match = HEAD.match(lines[i])

        if not match:
            out.append(lines[i])
            i += 1
            continue

        old = new = 0
        j = i + 1

        while j < len(lines):
            line = lines[j]

            # format-patch の署名(`-- ` だけの行)でハンクは終わる
            if line.rstrip("\r") == "-- " or line.startswith(("@@ ", "diff --git ")):
                break

            if line.startswith("-"):
                old += 1
            elif line.startswith("+"):
                new += 1
            elif line.startswith("\\"):
                pass
            elif line.startswith(" ") or (line == "" and j < len(lines) - 1):
                # 空行は空の文脈行。ファイル末尾の 1 つは split の余りなので数えない
                old += 1
                new += 1
            else:
                break

            j += 1

        # 先頭を 1 にすると git が「ファイルの先頭に固定」と解釈して探さなくなる
        # (apply.c の match_beginning)。2 から始めれば文脈で探しにいく
        out.append(f"@@ -2,{old} +2,{new} @@{match.group(1)}")
        i += 1

    return "\n".join(out)


def order(root):
    """パッチを `Date:` 行の順に並べる。"""
    items = []

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".patch"):
                continue

            path = os.path.join(base, name)
            when = None

            for line in io.open(path, encoding="utf-8", errors="replace"):
                match = DATE.match(line.rstrip("\n"))

                if match:
                    try:
                        when = parsedate_to_datetime(match.group(1))
                    except (TypeError, ValueError):
                        when = None

                    break

                if line.startswith("diff --git "):
                    break

            items.append((when, name, path))

    if any(w is None for w, _, _ in items):
        raise SystemExit("Date: が読めないパッチがある")

    mode = os.environ.get("SHIFU_PATCH_ORDER", "topo")

    if mode == "topo":
        return topo(items, introduced(root))

    if mode == "date":
        return [(n, p) for _, n, p in sorted(items, key=lambda x: (x[0], x[1]))]

    if mode == "name":
        return [(n, p) for _, n, p in sorted(items, key=lambda x: x[1])]

    # 既定は「Paper の履歴でそのパッチが最初に現れた順」。
    # 積み順はファイルから読めないが、後から書いたパッチは既にある積みの上に
    # 書かれているので、初出の順が積み順に一致する。
    rank = introduced(root)

    return [(n, p) for _, n, p in sorted(items, key=lambda x: (rank.get(x[1], len(rank)), x[1]))]


NOISE = re.compile(r"^[\s{}();]*$")


def distinctive(text):
    """依存の手掛かりになる行か。括弧だけの行や短い行は誰でも持っている。"""
    return len(text) >= 12 and not NOISE.match(text)


def sides(path):
    """パッチの (足す行, 要る行)。要る行 = 文脈行と消す行。

    消す行も「その行が既にある」ことを前提にしているので、足した側が先に来る。
    """
    added = set()
    needs = set()
    body = False

    for raw in io.open(path, encoding="utf-8", errors="replace"):
        line = raw.rstrip()

        if line.startswith("@@ "):
            body = True
            continue

        if line.startswith(("diff --git ", "index ", "--- ", "+++ ")):
            body = False
            continue

        if not body:
            continue

        text = line[1:].strip()

        if line.startswith("+"):
            if distinctive(text):
                added.add(text)
        elif line.startswith("-") or line.startswith(" "):
            if distinctive(text):
                needs.add(text)

    return added, needs


def topo(items, rank):
    """パッチ同士の依存で並べる。

    連番はどの版にも無く、ファイル名順も `Date:` 順も積み順にならない
    (`API-for-an-entity-s-scoreboard-name` は `Collision-API` が足した行を文脈にしている)。
    **文脈行を足しているのがどのパッチか**は中身から分かるので、それで順序を作る。
    同じ強さのものはファイル名順にする(結果が毎回同じになるように)。
    履歴の初出順を混ぜると 22 -> 0 に落ちたので、混ぜていない。
    """
    names = [n for _, n, _ in items]
    by_name = {n: p for _, n, p in items}
    adds = {}
    needs = {}

    for name in names:
        adds[name], needs[name] = sides(by_name[name])

    owner = collections.defaultdict(set)

    for name in names:
        for line in adds[name]:
            owner[line].add(name)

    after = collections.defaultdict(set)   # name -> 先に当てるもの
    count = collections.Counter()

    for name in names:
        for line in needs[name]:
            for other in owner.get(line, ()):
                if other != name and other not in after[name]:
                    after[name].add(other)
                    count[other] += 1

    key = lambda n: (rank.get(n, len(rank)), n)
    ready = sorted((n for n in names if not after[n]), key=key)
    seen = set()
    out = []

    while ready:
        name = ready.pop(0)

        if name in seen:
            continue

        seen.add(name)
        out.append(name)
        grew = False

        for other in names:
            if other in seen or name not in after[other]:
                continue

            after[other].discard(name)

            if not after[other]:
                ready.append(other)
                grew = True

        if grew:
            ready.sort(key=key)

    # 輪になって残ったものは、依存の少ない順に足す(そこは人が見る)
    left = [n for n in names if n not in seen]

    if left:
        print(f"依存が輪になって並べられなかった: {len(left)} 件", file=sys.stderr)
        out += sorted(left, key=lambda n: (len(after[n]), rank.get(n, len(rank)), n))

    return [(n, by_name[n]) for n in out]


def introduced(root):
    """パッチ名 -> Paper の履歴で最初に現れた順の番号。

    `patches/` を持つリポジトリ(worktree の親)の履歴を辿る。
    """
    repo = root

    while repo and not os.path.exists(os.path.join(repo, ".git")):
        parent = os.path.dirname(repo)
        repo = None if parent == repo else parent

    if not repo:
        raise SystemExit("パッチの置き場から git リポジトリを辿れない")

    done = subprocess.run(["git", "-C", repo, "log", "--reverse", "--format=C %H",
                           "--name-only", "--diff-filter=A", "HEAD", "--",
                           os.path.relpath(root, repo).replace(os.sep, "/")],
                          env=ENV, capture_output=True, text=True, errors="replace")
    rank = {}

    for line in done.stdout.splitlines():
        line = line.strip()

        if not line or line.startswith("C "):
            continue

        name = line.rsplit("/", 1)[-1]

        if name.endswith(".patch") and name not in rank:
            rank[name] = len(rank)

    return rank


def git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), env=ENV,
                          capture_output=True, text=True, errors="replace")


def main():
    root, repo = sys.argv[1:3]
    patches = order(root)
    print(f"パッチ {len(patches)} 件を {os.environ.get('SHIFU_PATCH_ORDER', 'topo')} の順に当てる")
    done = 0

    for name, path in patches:
        text = io.open(path, encoding="utf-8", errors="replace", newline="").read()
        handle = tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False,
                                             encoding="utf-8", newline="")
        handle.write(recount(text, repo))
        handle.close()

        try:
            args = ["am", "--ignore-whitespace", "--whitespace=nowarn"]

            if os.environ.get("SHIFU_PATCH_3WAY"):
                args.insert(1, "--3way")

            result = git(repo, *args, handle.name)
        finally:
            os.unlink(handle.name)

        if result.returncode != 0:
            git(repo, "am", "--abort")
            print(f"当たらなかった: {name}")
            print((result.stdout + result.stderr).strip()[:600])
            print(f"当たったパッチ: {done} / {len(patches)}")

            return 1

        done += 1

        if done % 100 == 0:
            print(f"  {done} / {len(patches)}")

    print(f"当たったパッチ: {done} / {len(patches)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
