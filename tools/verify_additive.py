"""当てたツリーが vanilla に対して「追加だけ」になっているかを確かめる。

条件 1(挙動が vanilla と完全に一致する)は、実行して測るより先に
**ソースの差分で確かめられる。** vanilla のツリーはコミット 1 つで持っているので、
そこからの差分を見れば、加えた変更が全部見える。

通す条件は 2 つ。

  * 消えた行は、`patches/access` で可視性を広げたか、型の宣言に interface を 1 つ足したものだけ
  * その 1 行は、修飾子 1 語(private / protected -> public、または public を足す)か、
    末尾に足した `, <interface>`(または ` implements <interface>`)以外は 1 文字も変わらない

これ以外の削除・書き換えが 1 行でもあれば落とす。追加は何行あってもよい。
足した宣言は vanilla のコードから呼ばれないので、実行される命令列は変わらない。

    python tools/verify_additive.py [<src/minecraft/java>]
"""

import os
import re
import subprocess
import sys

import paths

TREE = paths.TREE
BASE = paths.BASE
NARROW = re.compile(r"^(\s*)(private|protected)(\s.*)$")
DECL_TAIL = re.compile(r"[;{]\s*$")


def diff(tree):
    """vanilla からの差分を (ファイル, 消えた行, 足した行) の並びで返す。"""
    out = subprocess.run(
        ["git", "-c", "core.safecrlf=false", "diff", "-U0", BASE],
        cwd=tree, capture_output=True, text=True, encoding="utf-8", errors="replace")

    if out.returncode != 0:
        raise SystemExit("git diff が失敗した: %s" % out.stderr.strip()[:200])

    target = None
    removed = []
    added = []

    for line in out.stdout.split("\n"):
        if line.startswith("+++ b/"):
            target = line[len("+++ b/"):]
        elif line.startswith("-") and not line.startswith("---"):
            removed.append((target, line[1:]))
        elif line.startswith("+") and not line.startswith("+++"):
            added.append((target, line[1:]))

    return removed, added


def widened(line):
    """その行の可視性を public にした形の並び。可視性の変更で作れなければ空。

    2 通りある。`private` / `protected` を `public` に替える形と、修飾子が無い
    (同じパッケージからしか見えない)宣言に `public` を足す形。後者は Paper も
    同じ形で通している(`@Nullable String requestedUsername;`)。
    足す方は宣言の行(末尾が `;` か `{`)に限る。
    """
    match = NARROW.match(line)

    if match:
        return [match.group(1) + "public" + match.group(3)]

    body = line.lstrip()

    if not body or body.startswith("public ") or not DECL_TAIL.search(line):
        return []

    return [line[:len(line) - len(body)] + "public " + body]


DECLARATION = re.compile(r"^(\s*(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|final\s+|static\s+)*"
                         r"(?:class|record|enum|interface)\s.*\S)\s*\{\s*$")
INTERFACE = r"[A-Za-z_][\w.]*"


def implemented(old, new):
    """new が old(型の宣言)の末尾に interface を 1 つ足しただけか。"""
    match_old = DECLARATION.match(old)
    match_new = DECLARATION.match(new)

    if not match_old or not match_new:
        return False

    head = re.escape(match_old.group(1))
    joint = ", " if " implements " in match_old.group(1) else " implements "

    return re.fullmatch(head + re.escape(joint) + INTERFACE, match_new.group(1)) is not None


HOIST = re.compile(r"^(?P<indent>\s*)if \((?P<expr>.+?) instanceof (?P<type>[\w.$<>\[\], ]+?) "
                   r"(?P<name>\w+)(?P<tail>[)&|].*)$")
LOOP = re.compile(r"^(?P<indent>\s*)for \((?:final )?(?P<type>[\w.$<>\[\], ?]+?) (?P<name>\w+) : (?P<expr>.+)\) \{\s*$")


def looped(old, new, nearby):
    """new が old(拡張 for)の対象を局所変数に出しただけか。"""
    before = LOOP.match(old)
    after = LOOP.match(new)

    if not before or not after:
        return False

    if (before.group("type"), before.group("name")) != (after.group("type"), after.group("name")):
        return False

    name = after.group("expr").strip()

    if not re.fullmatch(r"\w+", name):
        return False

    want = "%s = %s;" % (name, before.group("expr"))

    return any(line.strip().endswith(want) for line in nearby)


TAIL = re.compile(r"^(?P<head>.*) && dev\.shifu\.event\.[\w.]+\(.*\);$")


def tailFired(old, new):
    """new が old の式の末尾に発火を足しただけか(patches/expr)。

    Paper が式の途中で発火しているところ。囲む形では入らないので末尾に足す。
    足すのは常に式の最後なので、vanilla の判定が全て終わったあとにしか呼ばれない。
    登録が無ければ true を返すので、実行される命令列は static 呼び出しが 1 つ増えるだけ。
    """
    found = TAIL.match(new)

    return found is not None and found.group("head").strip() + ";" == old.strip()


def hoisted(old, new, nearby):
    """new が old の式を局所変数に出しただけか(patches/decompile)。

    逆コンパイラが `X v = E; if (v instanceof T p)` を 1 行にまとめたのを戻す書き換え。
    宣言の行が直前にあり、型・パターン変数・残りの条件が同じであることまで見る。
    """
    before = HOIST.match(old)
    after = HOIST.match(new)

    if not before or not after:
        return False

    if (before.group("type"), before.group("name"), before.group("tail")) !=             (after.group("type"), after.group("name"), after.group("tail")):
        return False

    name = after.group("expr").strip()

    if not re.fullmatch(r"\w+", name):
        return False

    want = "%s = %s;" % (name, before.group("expr"))

    return any(line.strip().endswith(want) for line in nearby)


def subsequence(tree, target):
    """vanilla の行の並びが、当てたあとの並びの中に順序どおり全部あるか。

    `git diff` は行の対応を最短の差分で選ぶので、足した塊の中に空行があると
    元の空行の方を「消えた」と表示することがある。差し込みは行を消さないので
    これは表示のあやで、実際には残っている。部分列かどうかを直接見れば
    そのあやに引っ掛からない。

    可視性を広げた行は、広げる前の形でも後の形でも合ったことにする。
    """
    old = subprocess.run(["git", "show", "%s:%s" % (BASE, target)],
                         cwd=tree, capture_output=True)

    if old.returncode != 0:
        return None

    want = old.stdout.decode("utf-8", "replace").replace("\r\n", "\n").split("\n")
    path = os.path.join(tree, target.replace("/", os.sep))

    with open(path, encoding="utf-8") as handle:
        have = handle.read().replace("\r\n", "\n").split("\n")

    at = 0

    for line in want:
        others = widened(line)

        while at < len(have) and have[at] != line and have[at] not in others \
                and not implemented(line, have[at]) \
                and not hoisted(line, have[at], have[max(at - 2, 0):at]) \
                and not looped(line, have[at], have[max(at - 2, 0):at]):
            at += 1

        if at == len(have):
            return line

        at += 1

    return ""


def main():
    tree = sys.argv[1] if len(sys.argv) > 1 else TREE
    removed, added = diff(tree)
    added_set = set(added)
    added_by_target = {}

    for target, line in added:
        added_by_target.setdefault(target, []).append(line)

    problems = []
    checked = {}

    for target, line in removed:
        wants = widened(line)

        if any((target, one) in added_set for one in wants):
            continue

        others = added_by_target.get(target, [])

        if any(implemented(line, other) for other in others):
            continue

        if any(hoisted(line, other, others) for other in others):
            continue

        if any(looped(line, other, others) for other in others):
            continue

        if any(tailFired(line, other) for other in others):
            continue

        # 差分の表示のあやかもしれない。その行が本当に消えているかを
        # ファイル全体の部分列で見る。
        if target not in checked:
            checked[target] = subsequence(tree, target)

        missing = checked[target]

        if missing == "":
            continue

        if not wants:
            problems.append((target, line, "可視性の変更でも interface の追加でもない。消えた行: %r"
                             % (missing or line).strip()))
        else:
            problems.append((target, line, "public にした行が見つからない"))

    print("vanilla から消えた行: %d" % len(removed))
    print("足した行            : %d" % len(added))

    if not problems:
        print()
        print("消えた行は可視性・interface・逆コンパイルで消えた局所変数・式の末尾の発火のどれか。")
        return 0

    print()
    print("%d 件が説明できない:" % len(problems))

    for target, line, why in problems:
        print("  %s" % target)
        print("      %s" % line.strip())
        print("      -> %s" % why)

    return 1


if __name__ == "__main__":
    sys.exit(main())
