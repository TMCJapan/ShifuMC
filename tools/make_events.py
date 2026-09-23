# -*- coding: utf-8 -*-
"""Paper のパッチから発火の規則を作る。

`paper-server/patches/sources/**.java.patch` は統一 diff なので、
足す文と、その前後にある vanilla の行が両方読める。後ろの vanilla の行を
アンカーにすれば、そのまま `patches/events` の規則になる。

    python tools/make_events.py <patches/sources> <vanilla の木> <出力先> \
        [--only 名前,名前] [--skip-file 名前,名前] \
        [--craft <CraftEventFactory.java>] [--api <paper-api の src か jar>]

出せるのは、vanilla の行を 1 行も消していない塊だけ。中括弧が釣り合わない
(vanilla の行を囲む)ものは `insert` と `insert-after` に分ける。
vanilla の行が消えている塊は出さない。手で見るしかない。

アンカーは後ろの文脈を 1 行ずつ伸ばして、vanilla の中で 1 箇所に定まるまで
広げる。8 行伸ばしても定まらないものは出さない(`apply_events.py` が
2 箇所以上を失敗にするので、黙って別の場所に入ることはない)。

足した文はそのままでは出さない。`dev.shifu.event.EventGuard.listening` で
囲んでから出す。Paper の発火行は登録の有無を見ないので、写したままだと
プラグイン 0 個でもイベントを組み立てる(`MobEffectInstance` の tick ごとに
`EntityEffectTickEvent`、姿勢が変わるたびに `Entity.getBukkitEntity()`)。
MOD が登録したエンティティ型では `CraftEntityType.minecraftToBukkit` が
`IllegalArgumentException` を投げ、`guardEntityTick` がサーバーを落とす。

どのイベントを見るかは、足した文の `new なんとかEvent(` と、
`CraftEventFactory` のメソッドが作るイベントから決める。後者を読むために
`--craft` と `--api` が要る(渡さなければ `<patches/sources>` の位置から探す)。
イベントが決まらない塊と、文として囲めない形(式の途中、波括弧なしの if、
メソッドを割るもの)は出さない。数え上げには理由ごとに出る。
"""
import glob
import io
import os
import re
import sys
import zipfile
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_events

STRINGS = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//.*$')
MAX_ANCHOR = 8


def balance(lines):
    """中括弧の釣り合い。文字列と行末注釈の中は数えない。"""
    n = 0
    for line in lines:
        bare = STRINGS.sub("", line)
        n += bare.count("{") - bare.count("}")
    return n


def vanilla(tree, rel, cache):
    """アンカーを探す相手。shim を当てたあとの木をそのまま見る。

    `apply_events.py` が見るのもこの木なので、ここで 1 箇所に定まれば
    当てるときも定まる。vanilla の commit の方を見ないのは、shim が足した行と
    ぶつかる可能性をここで拾うため。
    """
    if rel not in cache:
        path = os.path.join(tree, rel.replace("/", os.sep))
        cache[rel] = (None if not os.path.exists(path)
                      else [l.strip() for l in
                            io.open(path, encoding="utf-8").read()
                            .replace("\r\n", "\n").split("\n")])

    return cache[rel]


def unique_anchor(body, after):
    """1 箇所に定まる最短のアンカー。定まらなければ None。"""
    want = [l for l in after if l.strip()]

    for size in range(1, min(len(want), MAX_ANCHOR) + 1):
        probe = [l.strip() for l in want[:size]]
        hits = sum(1 for i in range(len(body) - size + 1)
                   if body[i:i + size] == probe)

        if hits == 1:
            return want[:size]

        if hits == 0:
            return None

    return None


def dedent(lines):
    """一番浅い行を 0 にそろえる。"""
    pads = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    cut = min(pads) if pads else 0

    return [l[cut:] if l.strip() else "" for l in lines]


def hunk_runs(hunk):
    """ハンクを、変更のひと続きと、その間の vanilla の行に切り分ける。"""
    out = []
    i = 0

    while i < len(hunk):
        if hunk[i][:1] in "+-":
            j = i

            while j < len(hunk) and hunk[j][:1] in "+-":
                j += 1

            out.append(("chg",
                        [l[1:] for l in hunk[i:j] if l[:1] == "-"],
                        [l[1:] for l in hunk[i:j] if l[:1] == "+"]))
            i = j
        else:
            k = i

            while k < len(hunk) and hunk[k][:1] not in "+-":
                k += 1

            out.append(("ctx", [], [l[1:] for l in hunk[i:k]]))
            i = k

    return out


DECL = re.compile(r"^\s*(?:public|protected|private|abstract|synchronized"
                  r"|native|static|class|interface|enum|record)\b")


def declares_member(lines):
    """型の本体に新しいメンバーを足しているか。

    Paper が足したメソッドの中に発火があると、diff の上では追加の塊に見える。
    メンバーの宣言は `make_shim.py` と `patches/hand` の担当なので、
    ここで出すと同じものが 2 つになる。

    塊の途中から始まることもある。Paper がメソッドを 2 つに割ると、
    追加の塊は「元のメソッドを閉じる `}`」から始まり、そのあとに新しい
    メソッドの宣言が来る。頭の行だけ見ると気付けないので全部見る。
    無名クラスを式の中に書いている追加もまとめて落ちるが、
    入れ損ねる方が、二重に宣言して壊すより安い。
    """
    return any(DECL.match(line) for line in lines)


def commented_out(lines):
    """`/*` で vanilla の行を囲って殺しているか。

    Paper は行を消す代わりに注釈で囲うことがある。diff の上では追加だけに
    見えるが、やっていることは書き換え。そのまま入れると注釈が閉じない。
    """
    text = "\n".join(lines)

    return text.count("/*") != text.count("*/")


# Shifu が立ち上げていない Paper の下部機構。触ると実行時に落ちる。
# ここに挙げたものは、当てたツリーで代入・起動する場所が無いことを確かめてある。
#
#   io.papermc.paper.configuration  GlobalConfiguration.get() が null を返す。
#                                   MinecraftServer.paperConfigurations に
#                                   代入する場所が無い。起動時に
#                                   SparksFly.enable が NPE で落ちた。
#   SparksFly / .spark              spark 自体は CraftServer が作るが、
#                                   enable() が上の設定を読む。
#   org.spigotmc.WatchdogThread     doStart() を呼ぶ場所が無い。動いていない。
#   paperConfig() / spigotConfig    Level の欄。shim が宣言だけ足していて
#                                   代入する場所が無い。Entity.push に入った
#                                   `level.paperConfig().collisions` が
#                                   エンティティを tick した瞬間に NPE で
#                                   落とした。
#
# org.spigotmc.SpigotConfig(静的な方)は CraftServer.loadPlugins が init する。
# 別物なので、ここには入れない。
UNWIRED = re.compile(r"io\.papermc\.paper\.configuration\."
                     r"|io\.papermc\.paper\.SparksFly"
                     r"|\.spark\."
                     r"|org\.spigotmc\.WatchdogThread"
                     r"|paperConfig\(\)"
                     r"|\.spigotConfig\b")


def drop_unwired(lines):
    """立ち上げていない機構を触る行を落とす。落とせなければ None。

    落としてよいのは、それだけで完結している文(括弧が釣り合っていて、
    その行で終わっているもの)に限る。条件式や途中の行だったら、
    落とすと形が壊れるので規則ごと捨てる。
    """
    out = []

    for line in lines:
        if not UNWIRED.search(line):
            out.append(line)
            continue

        bare = STRINGS.sub("", line).strip()

        if balance([line]) != 0 or not bare.endswith(";"):
            return None

    return out


GUARD_CALL = "dev.shifu.event.EventGuard.listening(%s.class)"
GUARD_NOTE = "// Shifu - 登録が無ければ組み立てない"
NEW_EVENT = re.compile(r"new\s+([\w.]*[A-Z]\w*Event)\s*\(")
CEF_CALL = re.compile(r"CraftEventFactory\.(\w+)\s*\(")
IF_OPEN = re.compile(r"^(\s*)if\s*\((.*)\)\s*\{\s*(//.*)?$")
# 波括弧を付けずに次の vanilla の行を条件にしているもの。囲むと形が変わる。
DANGLING = re.compile(r"^(?:\}\s*else\s*)?(?:if|for|while)\s*\(.*\)\s*$")
# 文ではなく式の途中(条件の続き、Stream の連鎖)。
FRAGMENT = (".", "&&", "||", ",", "+", "?", ":", ")")
MEMBER = re.compile(r"^    (?:public|private|protected|static|final|\s)"
                    r"*[\w.<>\[\],?@ ]+\s+(\w+)\s*\(")
IMPORT = re.compile(r"^import\s+(?:static\s+)?([\w.]+);")


def craft_map(path):
    """CraftEventFactory の各メソッドが発火するイベントの完全限定名。

    メソッドの本体にある `new なんとかEvent(` を集めて、同じクラスの
    別のメソッドを呼んでいるぶんもたどる。単純名は import で解く。
    """
    lines = io.open(path, encoding="utf-8").read() \
        .replace("\r\n", "\n").split("\n")
    imports = {}

    for line in lines:
        hit = IMPORT.match(line)

        if hit:
            imports[hit.group(1).rsplit(".", 1)[-1]] = hit.group(1)

    bodies = collections.defaultdict(list)
    i = 0

    while i < len(lines):
        hit = MEMBER.match(lines[i])

        if hit and re.sub(r"//.*$", "", lines[i]).rstrip().endswith("{"):
            depth = lines[i].count("{") - lines[i].count("}")
            body = []
            j = i + 1

            while j < len(lines) and depth > 0:
                depth += lines[j].count("{") - lines[j].count("}")

                if depth > 0:
                    body.append(lines[j])

                j += 1

            bodies[hit.group(1)].append("\n".join(body))
            i = j
            continue

        i += 1

    direct = {}
    calls = {}

    for name, texts in bodies.items():
        made = set()
        called = set()

        for text in texts:
            for found in NEW_EVENT.findall(text):
                made.add(found if "." in found else imports.get(found, found))

            called |= set(re.findall(r"\b(\w+)\s*\(", text))

        direct[name] = made
        calls[name] = called & set(bodies)

    out = {}

    for name in bodies:
        seen = set()
        stack = [name]
        made = set()

        while stack:
            one = stack.pop()

            if one in seen:
                continue

            seen.add(one)
            made |= direct.get(one, set())
            stack.extend(calls.get(one, ()))

        out[name] = sorted(made)

    return out


def api_map(path):
    """イベントの単純名 -> 完全限定名。jar でも src の木でも読む。"""
    out = collections.defaultdict(set)

    if path.endswith(".jar"):
        for name in zipfile.ZipFile(path).namelist():
            if name.endswith("Event.class") and "$" not in name:
                fq = name[:-len(".class")].replace("/", ".")
                out[fq.rsplit(".", 1)[-1]].add(fq)
    else:
        for base, _, files in os.walk(path):
            for name in files:
                if not name.endswith("Event.java"):
                    continue

                rel = os.path.relpath(os.path.join(base, name), path)
                fq = rel.replace("\\", "/")[:-len(".java")].replace("/", ".")
                out[fq.rsplit(".", 1)[-1]].add(fq)

    return {k: sorted(v) for k, v in out.items()}


def find_sources(sources):
    """`<patches/sources>` から CraftEventFactory と paper-api を探す。"""
    root = os.path.abspath(sources)

    for _ in range(4):
        root = os.path.dirname(root)

        for server, api in (("paper-server", "paper-api"),
                            ("Paper-Server", "Paper-API")):
            craft = os.path.join(root, server, "src", "main", "java",
                                 "org", "bukkit", "craftbukkit", "event",
                                 "CraftEventFactory.java")
            src = os.path.join(root, api, "src", "main", "java")
            jars = sorted(glob.glob(os.path.join(root, api, "build", "libs",
                                                 "paper-api-*.jar")))

            if os.path.isfile(craft) and (os.path.isdir(src) or jars):
                return craft, (src if os.path.isdir(src) else jars[-1])

    return None, None


def guard_events(lines, craft, api):
    """この塊が発火するイベントの完全限定名。決まらなければ None。"""
    text = "\n".join(lines)
    out = []

    for found in NEW_EVENT.findall(text):
        if "." in found:
            out.append(found)
        elif len(api.get(found, ())) == 1:
            out.append(api[found][0])
        else:
            return None

    for name in CEF_CALL.findall(text):
        if not craft.get(name):
            return None

        out.extend(craft[name])

    if not out:
        return None

    seen = []

    for one in out:
        if one not in seen:
            seen.append(one)

    return seen


def guard_expr(events):
    calls = [GUARD_CALL % one for one in events]

    return calls[0] if len(calls) == 1 else "(" + " || ".join(calls) + ")"


def guarded(insert, close, expr):
    """登録を見てから通る形にした insert。できなければ (None, 理由)。"""
    if close:
        # 囲む形。開いている if の条件の頭で短絡させれば、登録が無いときに
        # 通る命令列は vanilla と同じになる。
        depth = 0
        at = None

        for i, line in enumerate(insert):
            before = depth
            depth += balance([line])

            if before == 0 and depth == 1:
                at = i

        if at is None:
            return None, "囲む if が見つからない"

        hit = IF_OPEN.match(insert[at])

        if not hit:
            return None, "囲んでいるのが素の if ではない"

        out = list(insert)
        out[at] = "%sif (!%s || (%s)) {%s" % (
            hit.group(1), expr, hit.group(2).rstrip(),
            (" " + hit.group(3)) if hit.group(3) else " " + GUARD_NOTE)

        return out, None

    head = next((l.strip() for l in insert
                 if l.strip() and not l.strip().startswith("//")), "")

    if head.startswith(FRAGMENT):
        return None, "式の途中なので文として囲めない"

    depth = 0

    for line in insert:
        depth += balance([line])

        if depth < 0:
            return None, "メソッドを割っているので文として囲めない"

    for line in insert:
        if DANGLING.match(STRINGS.sub("", line).strip()):
            return None, "波括弧なしの if なので囲めない"

    return (["if (%s) { %s" % (expr, GUARD_NOTE)]
            + ["    " + l if l.strip() else "" for l in insert]
            + ["}"]), None


def contiguous(removed, added):
    """消えた行が、足した行の中にひと続きでそのまま入っている位置。

    Paper が vanilla の行を条件の中に入れ直しただけなら、diff の上では
    「消して足し直した」に見える。中身が 1 文字も変わっていないなら、
    前後を囲む形で同じことが書ける。**vanilla の行はそのまま残る。**
    """
    want = [l.strip() for l in removed if l.strip()]

    if not want:
        return None

    keep = [(i, l.strip()) for i, l in enumerate(added) if l.strip()]

    for start in range(len(keep) - len(want) + 1):
        if [l for _, l in keep[start:start + len(want)]] == want:
            return keep[start][0], keep[start + len(want) - 1][0]

    return None


def build(at, parts):
    """parts[at] の塊を規則の形にする。出せなければ (None, 理由)。"""
    _, removed, raw = parts[at]
    added = drop_unwired(raw)

    if added is None:
        return None, "立ち上げていない機構を条件や途中の行で触る"

    if not any(scan_events.FIRE.search(l) for l in added):
        return None, "発火の行が立ち上げていない機構を触る"

    if removed:
        if commented_out(added) or declares_member(added):
            return None, "vanilla の行が消えている"

        span = contiguous(removed, added)

        if span is None:
            return None, "vanilla の行が消えている"

        head, tail = added[:span[0]], added[span[1] + 1:]

        if balance(head) + balance(tail) != 0:
            return None, "囲みの括弧が釣り合わない"

        return {"insert": head, "wrapped": removed, "close": tail,
                "after_ctx": [], "anchor_is_removed": True}, None

    if commented_out(added):
        return None, "vanilla の行を注釈で囲っている"

    if declares_member(added):
        return None, "メンバーの宣言(shim の担当)"

    delta = balance(added)

    if delta == 0:
        after = (parts[at + 1][2]
                 if at + 1 < len(parts) and parts[at + 1][0] == "ctx" else [])

        return {"insert": added, "wrapped": [], "close": [],
                "after_ctx": after}, None

    if delta < 0:
        return None, "閉じ括弧が先にある"

    # 囲む形。同じハンクの後ろで釣り合いが戻るところを探す。
    wrapped = []
    depth = delta
    k = at + 1

    while k < len(parts):
        kind, rem, lines = parts[k]

        if kind == "ctx":
            wrapped.extend(lines)
            k += 1
            continue

        if rem:
            return None, "囲む途中で vanilla の行が消えている"

        depth += balance(lines)

        if depth == 0:
            close = drop_unwired(lines)

            if close is None:
                return None, "立ち上げていない機構を条件や途中の行で触る"

            after = (parts[k + 1][2]
                     if k + 1 < len(parts) and parts[k + 1][0] == "ctx" else [])

            return {"insert": added, "wrapped": wrapped, "close": close,
                    "after_ctx": after}, None

        wrapped.extend(lines)
        k += 1

    return None, "閉じが同じハンクに無い"


def main():
    sources, tree, dest = sys.argv[1:4]
    only = set()
    skip_files = set()
    args = sys.argv[4:]

    for i, a in enumerate(args):
        if a == "--only":
            only = set(args[i + 1].split(","))

        if a == "--skip-file":
            skip_files = set(args[i + 1].split(","))

    craft_path, api_path = find_sources(sources)

    for i, a in enumerate(args):
        if a == "--craft":
            craft_path = args[i + 1]

        if a == "--api":
            api_path = args[i + 1]

    if not craft_path or not api_path:
        print("CraftEventFactory.java か paper-api が見つからない。"
              "--craft と --api で渡す", file=sys.stderr)
        return 1

    craft = craft_map(craft_path)
    api = api_map(api_path)
    cache = {}
    made = collections.defaultdict(list)
    dropped = collections.Counter()
    kept = collections.Counter()

    for root, _, files in os.walk(sources):
        for f in sorted(files):
            if not f.endswith(".java.patch"):
                continue

            rel = os.path.relpath(os.path.join(root, f),
                                  sources).replace("\\", "/")[:-len(".patch")]

            if rel in skip_files:
                continue

            body = vanilla(tree, rel, cache)

            if body is None:
                dropped["vanilla に無いファイル"] += 1
                continue

            for hunk in scan_events.parse(os.path.join(root, f)):
                parts = hunk_runs(hunk)

                for at, (kind, removed, added) in enumerate(parts):
                    if kind != "chg":
                        continue

                    fired = [l for l in added if scan_events.FIRE.search(l)]

                    if not fired:
                        continue

                    names = scan_events.events_in(fired)

                    if only and not (set(names) & only):
                        continue

                    shape, why = build(at, parts)

                    if shape is None:
                        dropped[why] += 1
                        continue

                    anchor_src = shape["wrapped"] or shape["after_ctx"]

                    if not [l for l in anchor_src if l.strip()]:
                        dropped["後ろに vanilla の行が無い"] += 1
                        continue

                    if shape.get("anchor_is_removed"):
                        # 囲む相手は消えた行そのもの。文脈で伸ばす余地は無いので
                        # 全部そろって 1 箇所に定まらないと入れられない。
                        anchor = [l for l in anchor_src if l.strip()]
                        probe = [l.strip() for l in anchor]
                        hits = sum(1 for i in range(len(body) - len(probe) + 1)
                                   if body[i:i + len(probe)] == probe)

                        if hits != 1:
                            dropped["アンカーが定まらない"] += 1
                            continue
                    else:
                        anchor = unique_anchor(body, anchor_src)

                        if anchor is None:
                            dropped["アンカーが定まらない"] += 1
                            continue

                        if shape["close"] and len(anchor) < len(
                                [l for l in shape["wrapped"] if l.strip()]):
                            dropped["囲む範囲がアンカーに収まらない"] += 1
                            continue

                    fired = guard_events(shape["insert"] + shape["close"],
                                         craft, api)

                    if fired is None:
                        dropped["発火するイベントが決まらない"] += 1
                        continue

                    insert, why = guarded(dedent(shape["insert"]),
                                          dedent(shape["close"]),
                                          guard_expr(fired))

                    if insert is None:
                        dropped[why] += 1
                        continue

                    made[rel].append({
                        "events": names,
                        "anchor": dedent(anchor),
                        "insert": insert,
                        "after": dedent(shape["close"]),
                    })

                    for n in names:
                        kept[n] += 1

    os.makedirs(dest, exist_ok=True)
    total = 0

    for rel, rules in sorted(made.items()):
        name = rel.replace("/", "-")[:-len(".java")] + ".rules"
        out = ["# Paper のパッチから作った発火。tools/make_events.py が出したもの。", ""]

        for r in rules:
            out.append("# %s" % ", ".join(r["events"]))
            out.append("file: %s" % rel)
            out.append("anchor:")
            out.extend("    " + l if l.strip() else "" for l in r["anchor"])
            out.append("insert:")
            out.extend("    " + l if l.strip() else "" for l in r["insert"])

            if r["after"]:
                out.append("insert-after:")
                out.extend("    " + l if l.strip() else "" for l in r["after"])

            out.append("")
            total += 1

        io.open(os.path.join(dest, name), "w", encoding="utf-8",
                newline="\n").write("\n".join(out))

    print("出した規則 %d / %d ファイル" % (total, len(made)))
    print()
    print("出せなかった塊:")

    for why, n in dropped.most_common():
        print("  %-36s %d" % (why, n))

    print()
    print("出したイベント %d 種、多い順:" % len(kept))

    for name, n in kept.most_common(12):
        print("  %-42s %d" % (name, n))


if __name__ == "__main__":
    sys.exit(main())
