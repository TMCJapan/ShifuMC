# -*- coding: utf-8 -*-
"""Paper が setBlock を発火つきの版に置き換えた箇所から、Shifu の規則を作る。

Paper の `handleBlockGrowEvent` / `handleBlockSpreadEvent` / `handleBlockFormEvent` /
`handleMoistureChangeEvent` / `handleCauldronLevelChangeEvent` は、どれも

    -    level.setBlock(pos, state, flags);
    +    CraftEventFactory.handleBlockGrowEvent(level, pos, state, flags);

の形で vanilla の setBlock を置き換えている(発火して、通ったら置く)。
Shifu は vanilla の行を残し、**置く前に控えを取り、置いたあとに発火して、
取り消されたら控えに戻す**。通る経路は vanilla のまま。

    python tools/make_block_events.py <patches/sources> <当てたあとの木> <出力先の .rules> [--report]

1 つのハンクの中で、消えた setBlock の行と上の呼び出しを順に対にする
(数が違えば出さない。`if (!handle...) { return; }` を前に足して setBlock を後ろで消す形も、
これで拾える)。対の位置の引数が合わないものは出さない。
setBlock の行が木の中で 1 箇所に定まらないときは、ハンクの中でその直前にある
vanilla の行を足して定まるまで広げる(`apply_events.py` は `insert-after` を
アンカーの最後の行の後ろに置くので、後ろへは広げない)。
発火の引数に Paper が足した変数(木に無い名前)が入っているもの、ラムダの中の setBlock、
それでも定まらないものは出さずに数える。
"""
import io
import os
import re
import subprocess
import sys

import paths

HANDLERS = {
    "handleBlockGrowEvent": "grow",
    "handleBlockSpreadEvent": "spread",
    "handleBlockFormEvent": "form",
    "handleMoistureChangeEvent": "moisture",
    "handleCauldronLevelChangeEvent": "cauldron",
    "callEntityChangeBlockEvent": "entity",
}
SET_BLOCK = re.compile(r"^\s*(?P<level>[\w.()]+)\.(?P<call>setBlock|setBlockAndUpdate)\((?P<args>.*)\);\s*$")
CALL_HEAD = re.compile(r"CraftEventFactory\.(?P<name>(?:handle|call)\w+Event)\(")
# Paper が足した局所変数 `final BlockPos sourcePos = pos;` の形。発火の引数に使われていたら中身に置き換える
ALIAS = re.compile(r"^\s*(?:final\s+)?BlockPos\s+(\w+)\s*=\s*([^;]+);\s*(?://.*)?$")
IDENT = re.compile(r"[A-Za-z_]\w*")
UPDATE_ALL = "net.minecraft.world.level.block.Block.UPDATE_ALL"
MAX_CONTEXT = 6
BASE = paths.BASE


def split_args(text):
    """括弧の深さを見て , で割る。"""
    out = []
    depth = 0
    current = []

    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1

        if ch == "," and depth == 0:
            out.append("".join(current).strip())
            current = []
        else:
            current.append(ch)

    if "".join(current).strip():
        out.append("".join(current).strip())

    return out


def call_args(line, start):
    """`(` の位置から、対になる `)` までの中身。"""
    depth = 0

    for index in range(start, len(line)):
        ch = line[index]

        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1

            if depth == 0:
                return line[start + 1:index]

    return None


def find_calls(lines):
    """足した行の中の発火の呼び出し (名前, 引数の並び)。"""
    out = []

    for line in lines:
        for match in CALL_HEAD.finditer(line):
            if match.group("name") not in HANDLERS:
                continue

            inner = call_args(line, match.end() - 1)

            if inner is not None:
                out.append((match.group("name"), split_args(inner)))

    return out


HUNK_HEAD = re.compile(r"^@@ -(\d+)")


def hunks(text):
    """統一 diff のハンクごとに、(vanilla 側の開始行番号, その行の並び)を返す。"""
    current = None
    start = 0

    for line in text.split(chr(10)):
        match = HUNK_HEAD.match(line)

        if match:
            if current:
                yield start, current
            current = []
            start = int(match.group(1))
        elif current is not None and line[:1] in " +-":
            current.append(line)

    if current:
        yield start, current


def vanilla_line_number(hunk, index, start):
    """ハンクの index 行が vanilla の何行目か(1 始まり)。足した行は数えない。"""
    return start + sum(1 for l in hunk[:index] if l[:1] in " -")


def unique_anchor(hunk, index, tree_lines):
    """ハンクの index 行(消えた setBlock)を最後にして、木の中で 1 箇所に定まるアンカー。"""
    anchor = [hunk[index][1:]]
    at = index

    for _ in range(MAX_CONTEXT + 1):
        want = [l.strip() for l in anchor if l.strip()]
        hits = sum(1 for i in range(len(tree_lines) - len(want) + 1)
                   if tree_lines[i:i + len(want)] == want)

        if hits == 1:
            return anchor

        if hits == 0:
            return None

        # 直前の vanilla の行(文脈)を 1 つ足す。足した行の上に消えた行があれば諦める
        at -= 1

        while at >= 0 and not hunk[at].strip():
            at -= 1

        if at < 0 or hunk[at][:1] != " ":
            return None

        anchor.insert(0, hunk[at][1:])

    return None


def method_window(text, line, number=None):
    """vanilla の中で、その行を含むメソッドの本体(字下げ 4 の宣言から次の字下げ 4 の行まで)。

    number はハンクから求めた行番号(1 始まり)。その行が本当に line なら、同じ行が
    ファイルに何度あっても定まる。無い・合わないときは行の文字列で探し、1 箇所に
    定まらなければファイル全体を返す。"""
    lines = text.split(chr(10))
    want = line.strip()

    if number is not None and 0 < number <= len(lines) and lines[number - 1].strip() == want:
        hits = [number - 1]
    else:
        hits = [i for i, l in enumerate(lines) if l.strip() == want]

    if len(hits) != 1:
        return text

    start = hits[0]

    while start > 0 and not (lines[start].startswith("    ") and not lines[start].startswith("     ") and lines[start].strip()):
        start -= 1

    end = hits[0] + 1

    while end < len(lines) and not (lines[end].startswith("    ") and not lines[end].startswith("     ") and lines[end].strip()):
        end += 1

    return chr(10).join(lines[start:end + 1])


def known_names(expr, tree_text):
    """式に出てくる名前が、そのメソッド(vanilla)の中にあるか。Paper が足した局所変数を弾く。"""
    for name in IDENT.findall(expr):
        if name in ("this", "null", "true", "false"):
            continue

        if not re.search(r"\b%s\b" % re.escape(name), tree_text):
            return False

    return True


class Generator:
    def __init__(self, tree):
        self.tree = tree
        self.rules = []
        self.skipped = {}
        self.number = 0
        self.cache = {}

    def skip(self, why, target, line):
        self.skipped.setdefault(why, []).append((target, line.strip()))

    def tree_of(self, target):
        path = os.path.join(self.tree, target.replace("/", os.sep))

        if path not in self.cache:
            self.cache[path] = io.open(path, encoding="utf-8").read() if os.path.exists(path) else None

        return self.cache[path]

    def vanilla_of(self, target):
        """パッチ適用前の vanilla の中身。名前の有無はこちらで見る(当てた木には自分が差し込んだ行が入っている)。"""
        key = ("vanilla", target)

        if key not in self.cache:
            text = None

            # MSYS の bash が fork に失敗することがある(dofork: child -1)。3 回まで試す
            for _ in range(3):
                try:
                    out = subprocess.run(["git", "show", "%s:%s" % (BASE, target)], cwd=self.tree, capture_output=True)
                except OSError:
                    continue

                if out.returncode == 0:
                    text = out.stdout.decode("utf-8", "replace")
                    break

            self.cache[key] = text

        return self.cache[key]

    def emit(self, target, hunk, index, name, args, aliases, start):
        """消えた setBlock 1 行と発火 1 つの対から、規則を 1 つ出す。"""
        vanilla = hunk[index][1:]
        # Paper が足した別名を中身に戻し、Paper の getMinecraftWorld() は外す
        args = [aliases.get(a, a) for a in args]
        args = [re.sub(r"\.getMinecraftWorld\(\)$", "", a) for a in args]

        if "->" in vanilla:
            self.skip("ラムダの中の setBlock", target, vanilla)
            return

        kind = HANDLERS[name]
        set_match = SET_BLOCK.match(vanilla)
        set_args = split_args(set_match.group("args"))
        flags = set_args[2] if set_match.group("call") == "setBlock" and len(set_args) > 2 else UPDATE_ALL
        entity = "null"
        source = None
        reason = None

        if kind == "spread":
            # handleBlockSpreadEvent(level, source, target, state, flags[, checkSetResult])
            if len(args) < 5:
                self.skip("spread の引数が読めない", target, vanilla)
                return
            level, source, pos = args[0], args[1], args[2]
        elif kind == "cauldron":
            # handleCauldronLevelChangeEvent(level, pos, state, entity, reason)
            if len(args) < 5:
                self.skip("cauldron の引数が読めない", target, vanilla)
                return
            level, pos, entity, reason = args[0], args[1], args[3], args[4]
        elif kind == "entity":
            # callEntityChangeBlockEvent(entity, pos, state[, cancelled])。level は setBlock の受け手
            if len(args) < 3:
                self.skip("entity の引数が読めない", target, vanilla)
                return
            entity, pos = args[0], args[1]
            level = set_match.group("level")
        else:
            # handleBlockXEvent(level, pos, state, flags[, entity[, checkSetResult]])
            if len(args) < 4:
                self.skip("引数が読めない", target, vanilla)
                return
            level, pos = args[0], args[1]

            if kind == "form" and len(args) > 4:
                entity = args[4]

        if pos != set_args[0]:
            self.skip("発火の位置と setBlock の位置が違う", target, vanilla)
            return

        text = self.tree_of(target)

        if text is None:
            self.skip("木に無い", target, vanilla)
            return

        vanilla_text = method_window(self.vanilla_of(target) or text, vanilla, vanilla_line_number(hunk, index, start))

        # reason が API の定数(ChangeReason.X)なら見ない。裸の名前は Paper が足した引数なので見る
        names = [level, pos, source or "", entity]

        if reason and "ChangeReason." not in reason:
            names.append(reason)

        for expr in names:
            if not known_names(expr, vanilla_text):
                self.skip("発火の引数に木に無い名前がある(Paper が足した変数)", target, vanilla)
                return

        tree_lines = [l.strip() for l in text.split("\n")]
        anchor = unique_anchor(hunk, index, tree_lines)

        if anchor is None:
            self.skip("setBlock の行が木の中で 1 箇所に定まらない", target, vanilla)
            return

        self.number += 1
        var = "shifuBlock%d" % self.number

        if kind == "grow":
            handlers = "org.bukkit.event.block.BlockGrowEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.blockGrow(%s, %s, %s, %s);" % (level, pos, var, flags)
        elif kind == "spread":
            handlers = "org.bukkit.event.block.BlockSpreadEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.blockSpread(%s, %s, %s, %s, %s);" % (level, source, pos, var, flags)
        elif kind == "form":
            handlers = "org.bukkit.event.block.BlockFormEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.blockForm(%s, %s, %s, %s, %s);" % (level, pos, var, flags, entity)
        elif kind == "moisture":
            handlers = "org.bukkit.event.block.MoistureChangeEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.moistureChange(%s, %s, %s, %s);" % (level, pos, var, flags)
        elif kind == "entity":
            handlers = "org.bukkit.event.entity.EntityChangeBlockEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.entityChangeBlock(%s, %s, %s, %s, %s);" % (level, pos, var, flags, entity)
        else:
            handlers = "org.bukkit.event.block.CauldronLevelChangeEvent.getHandlerList()"
            after = "dev.shifu.event.ShifuEvents.cauldronLevelChange(%s, %s, %s, %s, %s);" % (level, pos, var, entity, reason)

        before = "dev.shifu.event.ShifuEvents.blockChangeBefore(%s, %s, %s)" % (level, pos, handlers)
        self.rules.append((target, anchor, var, before, after, name))

    def scan(self, root):
        for base, _, names in sorted(os.walk(root)):
            for name in sorted(names):
                if not name.endswith(".java.patch"):
                    continue

                path = os.path.join(base, name)
                target = os.path.relpath(path, root).replace(os.sep, "/")[:-len(".patch")]
                text = io.open(path, encoding="utf-8").read()

                for start, hunk in hunks(text):
                    added = [l[1:] for l in hunk if l[:1] == "+"]
                    calls = find_calls(added)

                    if not calls:
                        continue

                    aliases = {}

                    for line in added:
                        match = ALIAS.match(line)

                        if match:
                            aliases[match.group(1)] = match.group(2).strip()

                    removed = [i for i, l in enumerate(hunk) if l[:1] == "-" and SET_BLOCK.match(l[1:])]

                    if not removed or len(calls) != len(removed):
                        self.skip("消えた setBlock %d 件、発火 %d 件で対にならない" % (len(removed), len(calls)),
                                  target, hunk[removed[0]][1:] if removed else added[0])
                        continue

                    for index, (name, args) in zip(removed, calls):
                        self.emit(target, hunk, index, name, args, aliases, start)


def main():
    root, tree, out_path = sys.argv[1:4]
    report = "--report" in sys.argv
    generator = Generator(tree)
    generator.scan(root)

    with io.open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# Paper が setBlock を発火つきの版に置き換えた箇所。tools/make_block_events.py が出したもの。\n")
        handle.write("#\n#     python tools/make_block_events.py <patches/sources> <木> patches/events/block-change.rules\n")
        handle.write("#\n# vanilla の setBlock の行は残す。置く前に控えを取り(登録が無ければ null)、\n")
        handle.write("# 置いたあとに発火して、取り消されたら控えに戻す。通る経路は vanilla のまま。\n")
        handle.write("# アンカーが 2 行以上のものは、setBlock の行だけでは定まらないので直前の行を足してある。\n")

        for target, anchor, var, before, after, name in generator.rules:
            handle.write("\n# %s\n" % name)
            handle.write("file: %s\n" % target)
            handle.write("anchor:\n")

            for line in anchor:
                handle.write("    %s\n" % line.strip())

            handle.write("insert:\n    final org.bukkit.craftbukkit.block.CraftBlockState %s = %s;\n" % (var, before))
            handle.write("insert-after:\n    %s\n" % after)

    print("出した規則: %d 件 -> %s" % (len(generator.rules), out_path))

    for why, items in sorted(generator.skipped.items()):
        print("出せなかった(%s): %d 件" % (why, len(items)))

        if report:
            for target, line in items:
                print("    %s: %s" % (target, line))

    return 0


if __name__ == "__main__":
    sys.exit(main())
