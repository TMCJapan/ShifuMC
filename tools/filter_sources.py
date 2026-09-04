"""Paper の sources パッチをメソッド単位で選別して適用する。

Shifu は vanilla の挙動をそのまま保ちたいので、Paper のパッチのうち

  * 既存行を消さないもの(フィールド・アクセサの追加)  -> 当てる
  * イベントを発火するもの                              -> 当てる
  * 宣言が変わるもの(Bukkit 層が要求する引数の追加)    -> 当てる
  * 既存行を書き換えるもの(上のどれでもない)          -> 当てない

とする。4 番目に vanilla との挙動差が混ざっているため。

**判断の単位はメソッド。** hunk ごとに決めると、宣言を書き換える hunk と
その本体を書き換える hunk が別々に扱われ、片方だけ採用して重複定義や
構文エラーになる。メソッド全体を「vanilla のまま」か「Paper のもの」かの
二択にすれば、どちらを選んでも構文は閉じている。

パッチは `@@ -<start>,<count> +_,<count> @@` の形で、対象側の行番号が `_` になっている。
元ファイル側の行番号は全 hunk で同じ基準なので、一部だけ適用しても位置がずれない。

使い方:

    python tools/filter_sources.py <patches/sources> <src/minecraft/java> [--report <file>]

ツリーは事前に file patches 適用前のコミット(`paper Imports`)へ戻しておくこと。
"""

import argparse
import os
import re
import sys

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+.*?@@")
TARGET = re.compile(r"^\+\+\+ b/(.+)$")
EVENT = re.compile(r"(CraftEventFactory\.|\.callEvent\(\)|new\s+[A-Z]\w*Event\s*\()")
STRING = re.compile(r"\"(\\.|[^\"\\])*\"|'(\\.|[^'\\])*'")

# メソッドやコンストラクタの宣言。
#
# 呼び出しや制御構文を宣言と誤認すると Paper の挙動変更まで残ってしまうので、
# 修飾子で始まる行だけを宣言とみなす。取りこぼす側(パッケージプライベート)は
# コンパイルエラーとして露出するので、緩めるより厳しくする方が安全。
# 型に注釈が挟まる形がある。`org.bukkit.inventory.@Nullable InventoryHolder`。
# 型の文字集合に @ を入れないと、この宣言が丸ごと見えなくなる。
DECL = re.compile(
    r"^\s+(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|protected|private|static|final|abstract|native|synchronized|default|strictfp)\s+)+"
    r"(?:[\w$<>\[\],.?@\s]+\s+)?([\w$]+)\s*\("
)

# if / try を開く行と、else / catch / finally で続く行。
# 波括弧だけでは対にならない(``} else {`` は開閉が釣り合う)ので別に数える。
CHAIN_OPEN = re.compile(r"^\s*\}?\s*(?:else\s+)?(if|try)\b")
CHAIN_CONT = re.compile(r"^\s*\}?\s*(else|catch|finally)\b")

# 修飾子の無い宣言(インターフェースの抽象メソッド)。
# 呼び出しと紛れないよう、型と名前の 2 語で始まり、代入を含まない行に限る。
# 型に注釈が挟まる形は 3 語に見える。注釈を含む語を 1 つだけ前に許す。
IFACE = re.compile(
    r"^\s+(?!(?:return|new|throw|assert|case|else|for|if|while|switch|catch|do|yield)\b)"
    r"(?:[\w$<>\[\],.?]*@[\w$<>\[\],.?]+\s+)?"
    r"[\w$<>\[\],.?]+\s+([\w$]+)\s*\([^=]*?\)?\s*[;{]?\s*(?://.*)?$"
)

# 修飾子の無いフィールド。NMS にはパッケージプライベートなフィールドがある。
# メソッドの中の局所変数と同じ形なので、呼ぶ側が「型の直下の行」でだけ使うこと。
PLAIN_FIELD = re.compile(
    r"^\s+(?!(?:return|new|throw|assert|case|else|for|if|while|switch|catch|do|yield)\b)"
    r"(?:final\s+)?[\w$<>\[\],.?@]+\s+([\w$]+)\s*[;=]"
)

# 引数だけの行。宣言が複数行に折り返されているとき、引数の増減はこの形で現れる。
PARAM = re.compile(r"^\s*(?:final\s+)?(?:@\w+\s+)*[\w$<>\[\],.?]+(?:\s*\.\.\.)?\s+\w+,?\s*$")

# 宣言に前置される行。メソッドの範囲をここまで広げる。
LEAD = re.compile(r"^\s*(@|//|/\*|\*|$)")

# 型の宣言。この本体の直下にあるものを要素として扱う。
TYPE_DECL = re.compile(r"\b(class|interface|enum|record|@interface)\s+\w")

# フィールドの宣言。Paper はイベントに渡す状態をフィールドとして足す。
FIELD = re.compile(
    r"^\s+(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|protected|private|static|final|transient|volatile)\s+)+"
    r"[\w$<>\[\],.?@\s]+\s+([\w$]+)\s*[;=]"
)

# 型の宣言と、その親。継承の繋がりを辿るのに使う。
EXTENDS = re.compile(
    r"^\s*(?:\w+\s+)*(?:class|interface|enum|record)\s+(\w+)[^{]*?"
    r"((?:extends|implements)[^{]*)?\{"
)

# 入れ子の型の名前。例外リストで型そのものを指せるようにする。
TYPE_NAME = re.compile(r"^\s+(?:\w+\s+)*(?:class|interface|enum|record)\s+(\w+)")


def line_deltas(lines):
    """行ごとの波括弧の増減。文字列とコメントの中は数えない。"""
    in_comment = False
    result = []

    for line in lines:
        text = STRING.sub('""', line)
        index = 0
        delta = 0

        while index < len(text):
            if in_comment:
                end = text.find("*/", index)
                if end < 0:
                    break
                in_comment = False
                index = end + 2
                continue

            start = text.find("/*", index)
            comment = text.find("//", index)

            if comment >= 0 and (start < 0 or comment < start):
                segment = text[index:comment]
                delta += segment.count("{") - segment.count("}")
                break

            if start < 0:
                segment = text[index:]
                delta += segment.count("{") - segment.count("}")
                break

            segment = text[index:start]
            delta += segment.count("{") - segment.count("}")
            in_comment = True
            index = start + 2

        result.append(delta)

    return result


def structure_delta(lines):
    """波括弧・ブロックコメント・if 連鎖の増減。"""
    in_comment = False
    braces = 0
    comments = 0
    chain = 0

    for line in lines:
        if not in_comment:
            if CHAIN_OPEN.match(line):
                chain += 1
            if CHAIN_CONT.match(line):
                chain -= 1

        text = STRING.sub('""', line)
        index = 0

        while index < len(text):
            if in_comment:
                end = text.find("*/", index)
                if end < 0:
                    break
                in_comment = False
                comments -= 1
                index = end + 2
                continue

            start = text.find("/*", index)
            comment = text.find("//", index)

            if comment >= 0 and (start < 0 or comment < start):
                segment = text[index:comment]
                braces += segment.count("{") - segment.count("}")
                break

            if start < 0:
                segment = text[index:]
                braces += segment.count("{") - segment.count("}")
                break

            segment = text[index:start]
            braces += segment.count("{") - segment.count("}")
            in_comment = True
            comments += 1
            index = start + 2

    return braces, comments, chain


def members(lines):
    """型の本体の直下にある要素ごとの行範囲を返す。

    ラムダや無名クラスのブロックは要素として扱わない。扱うと、メソッドの
    途中で範囲が切れて採否が食い違い、``} else {`` のような続きが宙に浮く。

    入れ子の型の中もそのまま範囲として出すので、範囲は重なる。
    hunk を割り当てるときは、含んでいるもののうち一番狭いものを選ぶ。
    """
    deltas = line_deltas(lines)
    depth = []
    current = 0

    for delta in deltas:
        depth.append((current, current + delta))
        current += delta

    def span(index):
        """その行が開いたブロックの終わり。"""
        before = depth[index][0]
        end = index

        while end < len(lines) and depth[end][1] > before:
            end += 1

        return min(end, len(lines) - 1)

    # 型の本体が始まる深さ。ここに直に置かれているものだけが要素。
    bodies = [(0, len(lines) - 1, 1)]

    for index in range(len(lines)):
        before, after = depth[index]

        if after > before and TYPE_DECL.search(lines[index]):
            bodies.append((index, span(index), before + 1))

    regions = []

    for index in range(len(lines)):
        before, after = depth[index]

        if after <= before:
            continue

        # 一番内側の型の本体を見て、その直下かどうかを判断する。
        inner = None

        for start, end, member_depth in bodies:
            if start <= index <= end and (inner is None or start > inner[0]):
                inner = (start, end, member_depth)

        if inner is None or before != inner[2]:
            continue

        head = index

        # 宣言が複数行に折り返されている場合、引数の行までさかのぼる。
        # ここを含めないと、引数を足す hunk がどの要素にも属さなくなる。
        while head > 0 and (LEAD.match(lines[head - 1])
                            or not lines[head - 1].rstrip().endswith((";", "{", "}"))):
            head -= 1

        regions.append((head, span(index)))

    return regions


def hierarchy(tree):
    """クラス名から親と子を引ける表を作る。

    宣言が変わったメソッドは、上書きしている側も揃えないと繋がらない。
    ただし名前が同じというだけで全体に広げると、``tick`` のような
    ありふれた名前が Paper の実装をツリー全体に引き込んでしまう。
    継承の繋がりがあるものだけに限る。
    """
    parents = {}

    for base, _, files in os.walk(tree):
        for name in files:
            if not name.endswith(".java"):
                continue

            with open(os.path.join(base, name), encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    match = EXTENDS.match(line)

                    if match:
                        kin = re.findall(r"\b([A-Z]\w*)", match.group(2) or "")
                        parents.setdefault(match.group(1), set()).update(kin)

    children = {}

    for child, kin in parents.items():
        for parent in kin:
            children.setdefault(parent, set()).add(child)

    return parents, children


def family(name, parents, children):
    """その型の祖先と子孫。

    両方向を一度に辿ると、``Entity`` や ``Block`` を経由して全体が
    ひとつながりになってしまう。上へは親だけ、下へは子だけを辿る。
    """
    seen = {name}

    for table in (parents, children):
        stack = [name]

        while stack:
            current = stack.pop()

            for other in table.get(current, set()):
                if other not in seen:
                    seen.add(other)
                    stack.append(other)

    return seen


def type_bodies(lines):
    """型の本体の (開始行, 終了行, 直下の深さ) を返す。

    追加した要素をどこに置くかを決めるのに使う。入れ子の型に属するものを
    外側のクラスの末尾に置くと別の型の要素になってしまう。
    """
    deltas = line_deltas(lines)
    depth = []
    current = 0

    for delta in deltas:
        depth.append((current, current + delta))
        current += delta

    bodies = []

    for index in range(len(lines)):
        before, after = depth[index]

        if after <= before:
            continue

        # 宣言が複数行にまたがると、括弧が開く行に class や record が無い。
        # 宣言の続きである行だけを遡って見る。無名クラス
        # (``... = new Runnable() {``)は直前の行が文で終わるので混ざらない。
        head = index

        while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
            head -= 1

        if not TYPE_DECL.search(" ".join(lines[head:index + 1])):
            continue

        end = index

        while end < len(lines) and depth[end][1] > before:
            end += 1

        bodies.append((index, min(end, len(lines) - 1), before + 1))

    return bodies


def innermost_body(bodies, line):
    """その行を含む一番内側の型の本体。"""
    best = None

    for start, end, depth in bodies:
        if start <= line <= end and (best is None or start > best[0]):
            best = (start, end, depth)

    return best


def enclosing(regions, line):
    """その行を含む一番狭い範囲。無ければ None。"""
    best = None

    for start, end in regions:
        if start <= line <= end and (best is None or end - start < best[1] - best[0]):
            best = (start, end)

    return best


def following(regions, line):
    """その行より後で最初に始まる範囲。無ければ None。

    要素と要素の間に入る hunk は、直後の要素に付ける。Paper が引数を足すときは
    元の名前の委譲メソッドを直前に挿入するので、両方を同じ扱いにしないと
    片方だけ残って多重定義になる。
    """
    best = None

    for start, end in regions:
        if start >= line and (best is None or start < best[0]):
            best = (start, end)

    return best


class Hunk:
    def __init__(self, start, count):
        self.start = start
        self.count = count
        self.lines = []
        self.keep = False

    def added(self):
        return [l[1:] for l in self.lines if l.startswith("+")]

    def removed(self):
        return [l[1:] for l in self.lines if l.startswith("-")]

    def fires_event(self):
        return bool(EVENT.search("\n".join(self.added())))

    def declares(self, names):
        """すでにある名前のオーバーロードを足しているか。

        Paper が引数を足すときは、元の名前で受けて新しい方へ渡すだけの
        メソッドを直前に挿入する。これは単体では追加だが、渡し先の変更を
        落とすと多重定義になるので、渡し先と同じ扱いにする必要がある。
        """
        added = {m.group(1) for m in (DECL.match(l) for l in self.added()) if m}

        return bool(added & names)

    def changes_params(self):
        """宣言の引数そのものが変わっているか。

        可視性や final を広げただけなら、上書きしている側は直さなくてよい。
        引数が変わったときだけ、同じ名前を全体に広げる必要がある。
        """
        if any(PARAM.match(l) for l in self.added() + self.removed()):
            return True

        def params(lines):
            found = {}

            for line in lines:
                match = DECL.match(line) or IFACE.match(line)

                if match:
                    found[match.group(1)] = line[line.index("(") + 1:]

            return found

        before = params(self.removed())
        after = params(self.added())

        return any(before[name] != after[name] for name in before.keys() & after.keys())

    def changes_signature(self):
        """同じ名前の宣言が ``-`` と ``+`` の両方に現れるか。

        CraftBukkit はイベントの原因やワールドの文脈を渡すために引数を足す。
        これは挙動ではなく配線なので、落とすと Bukkit 層がリンクしなくなる。
        """
        removed = {m.group(1) for m in (DECL.match(l) or IFACE.match(l) for l in self.removed()) if m}
        added = {m.group(1) for m in (DECL.match(l) or IFACE.match(l) for l in self.added()) if m}

        if removed & added:
            return True

        # フィールドの修飾子(可視性や final)を変えるものも、
        # 落とすと Bukkit 層から触れなくなるので配線として扱う。
        removed_fields = {m.group(1) for m in (FIELD.match(l) for l in self.removed()) if m}
        added_fields = {m.group(1) for m in (FIELD.match(l) for l in self.added()) if m}

        if removed_fields & added_fields:
            return True

        # 宣言が折り返されている場合は、引数の行だけが増減する。
        return any(PARAM.match(l) for l in self.added() + self.removed())


def parse(path):
    target = None
    hunks = []
    current = None

    with open(path, encoding="utf-8", errors="strict") as handle:
        for raw in handle:
            line = raw.rstrip("\n")

            if line.startswith("+++ "):
                match = TARGET.match(line)
                if match:
                    target = match.group(1).strip()
                continue

            if line.startswith("--- "):
                continue

            match = HUNK.match(line)

            if match:
                current = Hunk(int(match.group(1)), int(match.group(2) or 1))
                hunks.append(current)
                continue

            if current is not None:
                current.lines.append(line)

    return target, hunks


def group(original, hunks):
    """同じメソッドに当たる hunk をまとめる。"""
    regions = members(original)
    buckets = {}
    names = {}
    order = []

    existing = {m.group(1) for m in (DECL.match(l) for l in original) if m}

    for hunk in sorted(hunks, key=lambda h: h.start):
        line = max(hunk.start - 1, 0)
        region = enclosing(regions, line)

        # 要素の末尾にかかっているだけの hunk は、次の要素のものとして扱う。
        # Paper は引数を足すとき、元の名前の委譲メソッドを直前に差し込むので、
        # 開始行で見ると前の要素に入ってしまう。
        straddles = region is not None and region[1] < line + hunk.count - 1

        if (region is None or straddles) and hunk.declares(existing):
            region = following(regions, line) or region

        key = region if region else ("outside", hunk.start)

        if key not in buckets:
            buckets[key] = []
            names[key] = declared(original, region) if region else set()
            order.append(key)

        buckets[key].append(hunk)

    merged = []

    for chunk in close(buckets[key] for key in order):
        found = set()

        for key in order:
            if any(h in buckets[key] for h in chunk):
                found |= names[key]

        # Paper が足した要素は元のファイルに無いので、追加行の側からも名前を取る。
        for hunk in chunk:
            found |= declared(hunk.added(), (0, len(hunk.added())))

        merged.append((chunk, found))

    return merged


def declared(lines, region):
    """その範囲が宣言している名前。例外リストとの突き合わせに使う。

    入れ子の型も名前で指せるようにする。Paper はイベントが扱う値の型を
    入れ子の enum やレコードとして足すことがあり、それも指定の対象になる。
    """
    start, end = region
    names = set()

    for line in lines[start:end + 1]:
        match = (DECL.match(line) or TYPE_NAME.match(line)
                 or FIELD.match(line) or IFACE.match(line))

        if match:
            names.add(match.group(1))

    return names


def close(groups):
    """構造が閉じないグループを次と結合する。

    メソッドの範囲は hunk の開始行で決めるので、閉じ括弧をまたぐ hunk が
    隣のメソッドに食い込むことがある。Paper が ``/*`` で範囲ごと
    コメントアウトしている箇所も、開きと閉じが別のメソッドに分かれる。
    どちらも採否が食い違うと構文が壊れるので、釣り合うまで束ねる。
    """
    result = []
    pending = []
    delta = [0, 0, 0]

    for chunk in groups:
        pending.extend(chunk)

        for hunk in chunk:
            add = structure_delta(hunk.added())
            remove = structure_delta(hunk.removed())
            delta = [d + a - r for d, a, r in zip(delta, add, remove)]

        if not any(delta):
            result.append(pending)
            pending = []

    if pending:
        result.append(pending)

    return result


def reason(chunk, names, forced):
    """このグループで Paper 側を採る理由。採らないなら None。"""
    if names & forced:
        return "forced"

    if any(h.fires_event() for h in chunk):
        return "event"

    if any(h.changes_signature() for h in chunk):
        return "signature"

    if not any(h.removed() for h in chunk):
        return "additive"

    return None


def rebuild(original, hunks, stats, report, target, forced, collect=None, owner=None):
    # 採否はメソッド単位で決め、出力は行番号順に行う。
    # 同じメソッドの hunk は連続しているとは限らない(中のラムダや無名クラスが
    # 内側の範囲として割り込む)ので、グループの順に出すと位置が前後して重複する。
    for chunk, names in group(original, hunks):
        why = reason(chunk, names, forced)

        stats[why if why else "dropped"] += len(chunk)

        # 宣言が変わったものは、上書きしている側も同じ形にしないと繋がらない。
        # 継承関係はここでは分からないので、同じ名前を全体に広げる。
        if collect is not None and why in ("signature", "forced"):
            if any(h.changes_params() for h in chunk):
                for name in names:
                    collect.setdefault(name, set()).add(owner)

        if why == "signature" and report is not None:
            # 挙動ではなく配線として採ったもの。本体の変更も一緒に入るので、
            # 後で個別に見直す対象として書き出す。
            report.append(f"{target}:{chunk[0].start}")

        for hunk in chunk:
            hunk.keep = why is not None

    out = []
    pos = 0

    for hunk in sorted(hunks, key=lambda h: h.start):
        start = max(hunk.start - 1, 0)

        if start > pos:
            out.extend(original[pos:start])

        wanted = (" ", "+") if hunk.keep else (" ", "-")

        for line in hunk.lines:
            if not line:
                out.append("")
            elif line[0] in wanted:
                out.append(line[1:])

        pos = max(pos, start + hunk.count)

    out.extend(original[pos:])

    return out


def load_keep(path):
    """例外リストを読む。

    ``<パス> <名前>`` の行で「この要素は Paper のものを採る」と指定する。
    落としたい変更だが Bukkit 層がその形を要求していてコンパイルが通らない、
    というものをここに書く。挙動差が残るので、理由を必ず添える。
    """
    result = {}

    if not os.path.exists(path):
        return result

    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split("#", 1)[0].strip()

            if not line:
                continue

            target, name = line.split()
            result.setdefault(target, set()).add(name)  # target が "*" なら全ファイル

    return result


def patches(root):
    """パッチファイルを (対象, hunk) の形で列挙する。"""
    for base, _, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".patch"):
                continue

            target, hunks = parse(os.path.join(base, name))

            if target is None:
                print("no target in", name, file=sys.stderr)
                continue

            yield target, hunks


def source(tree, target):
    path = os.path.join(tree, target.replace("/", os.sep))

    if not os.path.exists(path):
        return path, []

    with open(path, encoding="utf-8", errors="strict") as handle:
        return path, handle.read().split("\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("patches")
    parser.add_argument("tree")
    parser.add_argument("--report")
    parser.add_argument("--keep", default=os.path.join(os.path.dirname(__file__), "keep-methods.txt"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    keep = load_keep(args.keep)

    # 1 巡目: 宣言が変わる要素の名前を集める。
    # 上書きしている側を vanilla のままにすると型が合わなくなるので、
    # 名前が一致するものはツリー全体で Paper 側に揃える。
    always = set(keep.pop("*", set()))
    parents, children = hierarchy(args.tree)
    changes = {}
    scratch = {k: 0 for k in ("files", "new", "event", "signature", "additive", "forced", "dropped")}

    for target, hunks in patches(args.patches):
        _, original = source(args.tree, target)
        rebuild(original, hunks, scratch, None, target, keep.get(target, set()),
                collect=changes, owner=os.path.basename(target)[:-5])

    # 宣言が変わった名前を、継承で繋がっている型にだけ広げる。
    spread = {}

    for name, owners in changes.items():
        reach = set()

        for one in owners:
            reach |= family(one, parents, children)

        spread[name] = reach

    stats = {k: 0 for k in scratch}
    report = [] if args.report else None

    for target, hunks in patches(args.patches):
        stats["files"] += 1
        path, original = source(args.tree, target)

        if not original:
            stats["new"] += 1

        here = os.path.basename(target)[:-5]
        inherited = {name for name, reach in spread.items() if here in reach}

        result = rebuild(original, hunks, stats, report, target,
                         keep.get(target, set()) | always | inherited)

        if args.dry_run:
            continue

        os.makedirs(os.path.dirname(path), exist_ok=True)

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(result))

    kept = stats["event"] + stats["signature"] + stats["additive"] + stats["forced"]

    print(f"patch files : {stats['files']} (new files {stats['new']})")
    print(f"kept        : {kept}"
          f" (event {stats['event']}, signature {stats['signature']},"
          f" additive {stats['additive']}, forced {stats['forced']})")
    print(f"dropped     : {stats['dropped']}")
    print(f"spread names: {len(spread)}")

    if report is not None:
        with open(args.report, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(report) + "\n")

        print(f"report      : {args.report} ({len(report)} methods)")


if __name__ == "__main__":
    main()
