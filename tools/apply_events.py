"""イベントの発火を vanilla のソースに差し込む。

行番号ではなく、差し込む位置にある行(アンカー)で指定する。
shim を当てたあとの行番号は動くし、Minecraft の更新でも動く。
**アンカーが見つからない、または 2 つ以上あるときは失敗させる。**
黙って読み飛ばすと、発火が消えたことに気付けない。

**vanilla の行は 1 文字も変えない。** 取り消しでその文を飛ばしたいときは、
`insert` と `insert-after` で前後を囲む。文が複数行なら、アンカーも複数行書く。

規則の書き方 (`patches/events/*.rules`):

    # ブロックの破壊
    file: net/minecraft/server/level/ServerPlayerGameMode.java
    anchor:
        BlockState adjustedState = block.playerWillDestroy(this.level, pos, state, this.player);
    insert:
        if (!dev.shifu.event.ShifuEvents.blockBreak(this.player, pos)) {
            return false;
        }

    # 退出。3 行にまたがる文を囲む
    file: net/minecraft/server/network/ServerGamePacketListenerImpl.java
    anchor:
        this.server
            .getPlayerList()
            .broadcastSystemMessage(Component.translatable("multiplayer.player.left", ...), false);
    insert:
        if (dev.shifu.event.ShifuEvents.playerQuit(this.player)) {
    insert-after:
        }

`insert` の行はアンカーの字下げに合わせて差し込まれる。

    python tools/apply_events.py <patches/events> <src/minecraft/java> [<差し込みの名前>]

3 つめを渡すと、印と数え上げの言葉がそれになる。vanilla の中の無名クラスへ
本体を足すのにも同じ仕組みを使う(`patches/anon`)。型の名前が無いので
`patches/hand` では届かないため。
"""

import os
import re
import sys


class Rule:
    """1 箇所への差し込み。"""

    def __init__(self, target, anchor, where):
        self.target = target
        self.anchor = anchor
        self.where = where
        self.before = []
        self.after = []
        # 同じ行が N 箇所にあって、その全部に同じものを差し込むときだけ書く。既定は 1
        self.count = 1

    def __repr__(self):
        return f"Rule({self.target}, {self.anchor[0]!r})"


def parse(text, source="<rules>"):
    """規則の並びを読む。"""
    rules = []
    target = None
    rule = None
    body = None
    number = 0

    for number, raw in enumerate(text.split("\n"), 1):
        line = raw.rstrip()

        if body is not None and (line.startswith("    ") or not line):
            body.append(line[4:] if line else "")
            continue

        body = None
        stripped = line.strip()

        if not stripped or stripped.startswith("#"):
            continue

        key, _, value = stripped.partition(":")

        if key == "file":
            target = value.strip()
        elif key == "anchor":
            if target is None:
                raise ValueError(f"{source}:{number}: file が先に要る")

            rule = Rule(target, [], f"{source}:{number}")
            rules.append(rule)
            body = rule.anchor
        elif key == "insert":
            body = need(rule, source, number).before
        elif key == "insert-after":
            body = need(rule, source, number).after
        elif key == "count":
            need(rule, source, number).count = int(value.strip())
        else:
            raise ValueError(f"{source}:{number}: 知らない見出し {key!r}")

    for one in rules:
        trim(one.anchor)
        trim(one.before)
        trim(one.after)

        if not one.anchor:
            raise ValueError(f"{one.where}: anchor が空")

    return rules


def need(rule, source, number):
    if rule is None:
        raise ValueError(f"{source}:{number}: anchor が先に要る")

    return rule


def trim(lines):
    """前後の空行を落とす。"""
    while lines and not lines[0]:
        lines.pop(0)

    while lines and not lines[-1]:
        lines.pop()

    return lines


def find(lines, anchor, count=1):
    """アンカーの (開始, 終了) 行番号。count 個に定まらなければ例外。"""
    return find_all(lines, anchor, count)[0]


def find_all(lines, anchor, count=1):
    """アンカーの (開始, 終了) の並び。count 個(既定 1)に定まらなければ例外。"""
    want = [text.strip() for text in anchor]
    hits = []

    for start in range(len(lines) - len(want) + 1):
        if [text.strip() for text in lines[start:start + len(want)]] == want:
            hits.append(start)

    if not hits:
        raise LookupError("見つからない")

    if len(hits) != count:
        raise LookupError(f"{len(hits)} 箇所にある" + (f"({count} 箇所のはず)" if count != 1 else ""))

    return [(start, start + len(want) - 1) for start in hits]


STRING = re.compile(r'"(?:[^"\\]|\\.)*"' + r"|'(?:[^'\\]|\\.)*'")


def brace_delta(lines):
    """差し込む行の中括弧の差。

    vanilla の文を囲む形は `insert` に `{`、`insert-after` に `}` を置く。
    片方だけが当たると釣り合いが崩れ、ファイルが構文として壊れる
    (--report は規則を 1 件ずつ落とすので、対の片方だけが残ることがある)。
    ファイル単位で足せば 0 になるはずなので、それで気付ける。
    """
    total = 0

    for line in lines:
        text = STRING.sub('""', line).split("//")[0]
        total += text.count("{") - text.count("}")

    return total


def pad_of(line):
    return " " * (len(line) - len(line.lstrip()))


def apply(lines, rules, label="イベント発火"):
    """規則を当てた行の並びを返す。"""
    before = {}
    after = {}

    for rule in rules:
        for start, end in find_all(lines, rule.anchor, rule.count):
            pad = pad_of(lines[start])

            if rule.before:
                before.setdefault(start, []).extend(
                    [pad + f"// Shifu - {label}"]
                    + [pad + text if text else "" for text in rule.before])

            if rule.after:
                after.setdefault(end, []).extend(
                    pad + text if text else "" for text in rule.after)

    out = []

    for number, line in enumerate(lines):
        out.extend(before.get(number, []))
        out.append(line)
        out.extend(after.get(number, []))

    return out


def main():
    rules_root, tree = sys.argv[1:3]
    rest = [arg for arg in sys.argv[3:] if arg != "--report"]
    report = "--report" in sys.argv
    label = rest[0] if rest else "イベント発火"
    by_file = {}

    for base, _, files in os.walk(rules_root):
        for name in sorted(files):
            if not name.endswith(".rules"):
                continue

            with open(os.path.join(base, name), encoding="utf-8") as handle:
                for rule in parse(handle.read(), name):
                    by_file.setdefault(rule.target, []).append(rule)

    applied = 0
    failed = []

    for target, rules in sorted(by_file.items()):
        path = os.path.join(tree, target.replace("/", os.sep))

        # Minecraft の更新でファイルごと消えることがある。--report は
        # 当たらないものを数え上げるためのモードなので、ここも数えて先へ進む。
        if not os.path.isfile(path):
            if not report:
                print(f"{target}: このファイルが無い", file=sys.stderr)
                return 1

            for rule in rules:
                failed.append(f"{target}: {rule.where}: このファイルが無い")

            continue

        with open(path, encoding="utf-8") as handle:
            lines = handle.read().split("\n")

        good = rules

        if report:
            # 当たらない規則を全部数え上げてから進む。make_events.py が出した
            # 規則を見直すときだけ使う。既定では最初の 1 件で止める。
            good = []

            for rule in rules:
                try:
                    find_all(lines, rule.anchor, rule.count)
                except LookupError as problem:
                    failed.append(f"{target}: {rule.where}: アンカーが{problem}"
                                  f"\n      {rule.anchor[0].strip()}")
                    continue

                good.append(rule)

        try:
            result = apply(lines, good, label)
        except LookupError as problem:
            print(f"{target}: アンカーが{problem}", file=sys.stderr)
            return 1

        delta = sum(brace_delta(rule.before) + brace_delta(rule.after) for rule in good)

        if delta != 0:
            # 囲む形の片方だけが当たった。このまま書くとファイルが壊れる。
            failed.append(f"{target}: 中括弧が {delta:+d} 釣り合わない。"
                          "囲む形の片方だけが当たっている(対の相手を直すこと)")

            if not report:
                print(f"{target}: 中括弧が {delta:+d} 釣り合わない", file=sys.stderr)
                return 1

            continue

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(result))

        applied += len(good)

    print(f"差し込んだ{label}: {applied} 箇所 / {len(by_file)} ファイル")

    if failed:
        print(f"当たらなかった規則 {len(failed)} 件:", file=sys.stderr)

        for line in failed:
            print("  " + line, file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
