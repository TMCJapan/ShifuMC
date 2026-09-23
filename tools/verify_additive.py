"""当てたツリーが vanilla に対して決めた形の変更だけになっているかを確かめる。

条件 1(挙動が vanilla と完全に一致する)は、実行して測るより先に
**ソースの差分で確かめられる。** 基点のコミットからの差分を見れば、加えた変更が全部見える。

    python tools/verify_additive.py [<当てた木>]

木を渡さなければ SHIFU_TREE(無ければ tools/paths.py の既定)を見る。classic の AT を見るには
SHIFU_PAPER に Paper のクローンを渡す。規則はこのリポジトリの patches/ から読む
(SHIFU_NO_DECOMPILE があれば、closure と同じく patches/decompile と patches/expr は当てていないものとする)。
1 件でも外れれば exit 1。tools/closure.sh / closure.ps1 が、不動点まで回したあとの木に対して呼ぶ。

2026-09-21 までの版は消えた行しか見ず、局所変数を戻した行や式の末尾の発火を
「ファイルのどこかに足した行があるか」で照合していた。足した `this.x = 2;` も、
宣言を別の文の前に出して評価の順を変えた形も、`&& 発火(…) || 他の式` も通っていた。

## vanilla の行を書き換えてよい形

基点に、書き換えを許した規則だけを closure と同じ順で当て直して「期待する並び」を作り、
木と行ごとに比べる。期待と違う行は、足した行として下の形に収まらなければ全部落とす。
規則そのものの形も見る。

| 規則 | 許す形 |
|---|---|
| patches/access | widen_access.py が作る形(修飾子を public へ、`unfinal:` は final を外す)。`implements:` は落とす |
| patches/narrow | 式を `((T) 式)` で包む(cast を足す)、または修飾子を広げる |
| patches/decompile(exprs.rules 以外) | 局所変数を戻す(`X v = E;` を書き換える行の直前に出す)。型引数・参照型への cast・`this.new`・局所変数の名前の付け替えだけの違い |
| patches/expr | 式の末尾に ` && dev.shifu.event.…(…)` を 1 つ足す、または式を発火の第 1 引数に渡して包む |
| patches/decompile/exprs.rules | 逆コンパイラが変えた式を戻す書き換え(落ちた cast、逆になった条件、case の並び)。形は問わないが、規則に `method: <クラス> <名前><記述子>` があり、書き換えがそのメソッドの本体(中の無名クラスを含む)に収まること。**公式と同じ命令列になることはここでは見ない。** tools/postcompile.sh の SemDiff が、名指ししたメソッドを公式と比べて違えば組むのを止める |

## 足してよい行

差し込んだ塊を、置かれた位置の文脈で見る。

* 型の本体の中(shim・hand・anon): 宣言だけ。初期化ブロック(`{ … }` / `static { … }`)は落とす
* ファイルの先頭: import と注記だけ
* メソッドの本体の中(events): 次の形だけ
  * `if (<発火>) { … }`(括弧の無い本体も)と、続く `else if (<発火>) { … }`。中身は見ない
  * `if (<発火>) {` / `} else {` / 括弧の無い `if (<発火>)` で vanilla の文を囲み、別の塊で閉じる形。
    囲んだ if の else(取り消されたときの道)の中身は見ない
  * `if (<読むだけ>) { … }` は中身もこの形だけのとき。`else { … }` は登録が無くても走るので中身を見る
  * `if (this.<欄>) { … }`(括弧の無い本体も)。欄は STATE_FIELDS に挙げたもので、そのファイルに
    Shifu が宣言したものに限る。中身は見ない。vanilla の文を囲む形と否定は落とす
  * `dev.shifu.event.X.y(…);` の呼び出し 1 つ
  * `final T shifuX = <発火 か 読むだけの値>;` と、`x = dev.shifu.event.X(…, x, …);` のように
    発火に渡した値を受け直す代入
  * 局所変数の生存範囲を切る `{ … }`、発火の結果を回す `for (T v : shifuX) { … }`
  * <発火> は `&&` / `||` / `!` / 括弧で組んだ条件で、項は発火の呼び出し、発火の結果の局所変数、
    それらと読むだけの値の比較のどれか。`&&` の中では読むだけの項(`this.valid`)も発火と組めば通す。
    `listening` を名前に含む発火(登録が無ければ false)の後ろの `&&` と、`!listening(…)` の後ろの
    `||` は評価されないので見ない
* メソッドの本体の中(wire): patches/wire の規則の中身が、その規則のアンカーの隣にあるもの。
  文ごとに Bukkit 層(dev.shifu / org.bukkit / io.papermc / org.spigotmc / com.destroystokyo)か、
  Shifu が足したメンバーか、そのファイルの vanilla に出てこない名前(`.` の後ろの欄は、vanilla で
  `.` の後ろに出てこない名前)を含むこと

括弧の無い `if (…)` / `for (…)` / `while (…)` / `else` の直後(本体の前)への差し込みは、
差し込んだ文が本体になり vanilla の文が条件の外に出るので落とす。
差し込みで開いた `{` は差し込みの `}` で、vanilla の `{` は vanilla の `}` で閉じること。

見ていないもの: 発火の引数(登録が無くても評価される)、`if (<発火>) { … }` の中身が
登録の無いときに走らないこと(発火が「続けてよいか」を返す約束に頼っている)、
足したメンバーの初期値(構築子で走る)。

## 基点

木の `paper Imports`(最新のもの)が規則を当てる前の状態で、そこから上の形を見る。
mache(1.21.4 以降)は `Mache` と `paper ATs` の間にある Paper の access transformer も見る。
修飾子(public / protected / private / final)だけが広がる向きに変わっていること、
定数(`static final` の基本型か String で、初期値が字句)から final を外していないこと。
classic(1.21.3 以前)の `paper Imports` は AT を当てた jar を逆コンパイルしたものなので
ソースに AT の前が無い。paperweight の fixJar.jar(AT の前)と applyMergedAt.jar(後)の
クラスファイルの access flags を比べて同じことを見る(読んだ位置: <PW>/.gradle/caches/
paperweight/taskCache/decompileJar.log の入力が copyResources.jar、その元が applyMergedAt.jar)。
"""

import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import apply_events  # noqa: E402
import patch_adapter  # noqa: E402
import widen_access  # noqa: E402

SHIFU = os.path.dirname(HERE)
PATCHES = os.path.join(SHIFU, "patches")


# ---------------------------------------------------------------- 字句

TOKEN = re.compile(r'"""(?:.|\n)*?"""|"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\''
                   r"|//[^\n]*|/\*(?:.|\n)*?\*/|\s+"
                   r"|[A-Za-z_$][\w$]*|\d[\w.]*|&&|\|\||->|::|\+\+|--|==|!=|<=|>=|[^\s]")


def tokens(text):
    """(字句, 開始位置) の並び。空白と注記は落とす。"""
    out = []

    for found in TOKEN.finditer(text):
        word = found.group()

        if word[0].isspace() or word.startswith("//") or word.startswith("/*"):
            continue

        out.append((word, found.start()))

    return out


def words(text):
    return [word for word, _ in tokens(text)]


def closing(seq, at, open_="(", close=")"):
    """seq[at] の括弧に対応する閉じ括弧の位置。無ければ None。"""
    depth = 0

    for index in range(at, len(seq)):
        if seq[index] == open_:
            depth += 1
        elif seq[index] == close:
            depth -= 1

            if depth == 0:
                return index

    return None


# ---------------------------------------------------------------- 発火の条件

def is_fire_call(seq):
    """seq がちょうど `dev.shifu.event.<…>.<名前>(…)` 1 つか。"""
    if seq[:6] != ["dev", ".", "shifu", ".", "event", "."]:
        return False

    at = 6

    while at + 1 < len(seq) and re.fullmatch(r"[A-Za-z_$][\w$]*", seq[at]) and seq[at + 1] == ".":
        at += 2

    if at + 1 >= len(seq) or not re.fullmatch(r"[A-Za-z_$][\w$]*", seq[at]) or seq[at + 1] != "(":
        return False

    return closing(seq, at + 1) == len(seq) - 1


def split_top(seq, operators):
    """括弧の外の operators で切る。[(直前の演算子, 項)]。"""
    parts = []
    depth = 0
    start = 0
    joint = None

    for index, word in enumerate(seq):
        if word in "([{":
            depth += 1
        elif word in ")]}":
            depth -= 1
        elif depth == 0 and word in operators:
            parts.append((joint, seq[start:index]))
            joint = word
            start = index + 1

    parts.append((joint, seq[start:]))

    return parts


LITERALS = {"null", "true", "false"}


class Fired:
    """そのファイルで発火の結果を置いた局所変数の名前。

    guards は `listening(…)` の結果を置いたもの(登録が無ければ false)。
    fields は `if (this.<欄>)` の条件に使ってよい欄(STATE_FIELDS のうち、そのファイルで Shifu が宣言したもの)。
    """

    def __init__(self):
        self.names = set()
        self.guards = set()
        self.fields = set()


NOTHING = Fired()

# プラグインがイベントではなく API で立てる Bukkit 層の状態。登録の有無では決まらないので
# listening の見張りの後ろには置けない。プラグインが無ければ既定の false のままで、
# 足す仕事は Shifu が宣言した欄の読み 1 回(listening の見張りと同じ重さ)。
# `if (this.<欄>) …` の形で、欄がそのファイルに Shifu の shim / hand が宣言したもの
# (基点に宣言が無いもの)に限って、発火の条件と同じに扱う。
STATE_FIELDS = {
    "fixedPose",               # Entity.setPose(Paper の Expand Pose API。CraftEntity.setPose(pose, true) で立つ)
    "exact",                   # Ingredient.test(1.18.2 / 1.19.4。CraftRecipe.toIngredient の ExactChoice で立つ)
    "shifuClientWorldBorder",  # ServerPlayer.tick(CraftPlayer.setWorldBorder で仮想の境界を入れると立つ)
}


def state_guard(seq, fired):
    """seq がちょうど `this.<欄>` 1 つで、欄が fired.fields にあるか。

    否定(`!this.x`)は通さない。欄が既定の false の間に中身が走る。
    """
    seq = strip_parens(seq)

    return len(seq) == 3 and seq[:2] == ["this", "."] and seq[2] in fired.fields


def strip_parens(seq):
    while seq and seq[0] == "(" and closing(seq, 0) == len(seq) - 1:
        seq = seq[1:-1]

    return seq


def is_guard(seq, fired):
    """登録が無ければ false になる項か(`…listening…(…)` の発火か、その結果の局所変数)。"""
    seq = strip_parens(seq)

    if len(seq) == 1:
        return seq[0] in fired.guards

    # X.getHandlerList().getRegisteredListeners().length != 0 は EventGuard.listening(X) と同じ
    tail = [".", "getHandlerList", "(", ")", ".", "getRegisteredListeners", "(", ")", ".", "length"]

    for end in ([">", "0"], ["!=", "0"]):
        if len(seq) > len(tail) + 2 and seq[-len(tail) - 2:-2] == tail and seq[-2:] == end \
                and all(TYPEISH.fullmatch(word) for word in seq[:-len(tail) - 2]):
            return True

    return is_fire_call(seq) and "listening" in seq[seq.index("(") - 1].lower()


FIRED = "shifu$fired"


def fire_replaced(seq):
    """seq の中の発火の呼び出しを 1 つの名前(FIRED)に置き換えた並び。"""
    out = []
    at = 0

    while at < len(seq):
        if seq[at:at + 6] == ["dev", ".", "shifu", ".", "event", "."]:
            end = at + 6

            while end < len(seq) and seq[end] != "(":
                end += 1

            close = closing(seq, end) if end < len(seq) else None

            if close is not None and is_fire_call(seq[at:close + 1]):
                out.append(FIRED)
                at = close + 1
                continue

        out.append(seq[at])
        at += 1

    return out


CONSTANT_ROOT = {"dev", "org", "io", "com", "co"}


def is_literal(word):
    return word in LITERALS or bool(re.fullmatch(r"\d[\w.]*|\"(?:.|\n)*\"|'.*'", word))


def roots(seq):
    """. の前に来ない名前(式が読む局所変数)。Bukkit 層・発火層の定数(org.bukkit.… など)の頭は除く。"""
    out = []

    for index, word in enumerate(seq):
        if not IDENT.fullmatch(word) or (index > 0 and seq[index - 1] == "."):
            continue

        if word in CONSTANT_ROOT and seq[index + 1:index + 2] == ["."]:
            continue

        out.append(word)

    return out


def from_fired(seq, fired):
    """seq が発火の結果を置いた局所変数と字句だけでできた式か。"""
    names = roots(seq)

    return bool(names) and any(name in fired.names for name in names) \
        and all(name in fired.names or is_literal(name) for name in names)


def pure_read(seq):
    """局所変数・欄・字句を読んで比べるだけの式か(呼び出し・代入・生成が無い)。"""
    for index, word in enumerate(seq):
        if word in ("=", "++", "--", "new", "->", "::"):
            return False

        if word == "(" and index > 0 and IDENT.fullmatch(seq[index - 1]):
            return False

    return bool(seq)


def fire_condition(seq, fired=NOTHING):
    """発火だけで組んだ条件か。違えば理由の文、よければ None。

    発火の結果を置いた局所変数(`final … shifuX = <発火>;`)だけを見る項も発火と同じに扱う。
    登録が無ければ短絡して評価されない項は見ない。`&&` では登録が無ければ false の項
    (`listening(…)`)より後ろ、`||` では `!listening(…)` より後ろ。
    """
    seq = strip_parens(seq)

    if not seq:
        return "条件が空"

    for operator, skip in (("||", lambda part: part[:1] == ["!"] and is_guard(part[1:], fired)),
                           ("&&", lambda part: is_guard(part, fired))):
        parts = split_top(seq, (operator,))

        if len(parts) == 1:
            continue

        fire = False

        for _, part in parts:
            part = strip_parens(part)

            # && の中で読むだけの項(`this.valid`、`x != null`)は、発火と組んであれば通す。
            # 読むだけなので状態は変わらず、中身が走るかは発火の項が決める
            if operator == "&&" and pure_read(part) and fire_condition(part, fired) is not None:
                continue

            why = fire_condition(part, fired)

            if why:
                return why

            fire = True

            if skip(part):
                return None

        return None if fire else "発火の項が無い: " + " ".join(seq)

    leaf = seq

    while leaf[:1] == ["!"]:
        leaf = strip_parens(leaf[1:])

    if leaf is not seq and len(split_top(leaf, ("&&", "||"))) > 1:
        return fire_condition(leaf, fired)

    if is_fire_call(leaf) or from_fired(leaf, fired) or is_guard(leaf, fired):
        return None

    # 発火の結果と読むだけの値を比べる項(`shifuX != x`、`<発火>(…) > 0`)。登録が無ければ
    # 発火は渡した値を返すので、比べるのは発火の結果
    replaced = fire_replaced(leaf)

    if pure_read(replaced) and any(name == FIRED or name in fired.names for name in roots(replaced)):
        return None

    return "発火でない項: " + " ".join(seq)


# ---------------------------------------------------------------- 規則の形

MODIFIERS = {"public", "protected", "private", "final"}
RANK = {"private": 0, None: 1, "protected": 2, "public": 3}
CONSTANT_TYPE = {"byte", "short", "int", "long", "float", "double", "char", "boolean", "String"}


def modifier_change(old, new):
    """old -> new が修飾子だけを広げる変更か。違えば理由の文、よければ None。"""
    old_words = words(old)
    new_words = words(new)

    if [w for w in old_words if w not in MODIFIERS] != [w for w in new_words if w not in MODIFIERS]:
        return "修飾子以外も変わっている"

    def access(seq):
        for word in seq:
            if word in RANK and word is not None:
                return word

        return None

    if RANK[access(new_words)] < RANK[access(old_words)]:
        return "可視性が狭まっている"

    if "final" in new_words and "final" not in old_words:
        return "final が増えている"

    if "final" in old_words and "final" not in new_words and "static" in old_words and "=" in old_words:
        at = old_words.index("=")
        kind = old_words[at - 2] if at >= 2 else ""
        value = old_words[at + 1:]

        if value and value[-1] == ";":
            value = value[:-1]

        if kind in CONSTANT_TYPE and len(value) <= 2 and value and re.fullmatch(
                r'-|\d[\w.]*|"(?:.|\n)*"|\'.*\'|true|false', value[-1]):
            return "定数から final を外している(javac が値を埋め込まなくなり、命令列が変わる)"

    return None


HOIST = re.compile(r"^(?P<indent>\s*)if \((?P<expr>.+?) instanceof (?P<type>[\w.$<>\[\], ]+?) "
                   r"(?P<name>\w+)(?P<tail>[)&|].*)$")
LOOP = re.compile(r"^(?P<indent>\s*)for \((?:final )?(?P<type>[\w.$<>\[\], ?]+?) (?P<name>\w+) : "
                  r"(?P<expr>.+)\) \{\s*$")


def hoisted(old, new, declaration):
    """new が old の式を直前の declaration(`X v = E;`)に出しただけか。"""
    for pattern, same in ((HOIST, ("type", "name", "tail")), (LOOP, ("type", "name"))):
        before = pattern.match(old)
        after = pattern.match(new)

        if not before or not after:
            continue

        if any(before.group(key) != after.group(key) for key in same):
            return False

        name = after.group("expr").strip()

        if not re.fullmatch(r"\w+", name):
            return False

        return re.fullmatch(r"(?:final\s+)?[\w.$<>\[\], ?]+\s+%s = %s;" % (re.escape(name),
                                                                         re.escape(before.group("expr").strip())),
                            declaration.strip()) is not None

    return False


IDENT = re.compile(r"[A-Za-z_$][\w$]*")
TYPEISH = re.compile(r"[A-Za-z_$][\w$]*|[.,?\[\]]|extends|super")
PRIMITIVE = {"byte", "short", "int", "long", "float", "double", "char", "boolean"}


def erased(seq):
    """型引数・参照型への cast・内部クラスの作り方の書き分けを落とした字句の並び。

    patches/decompile/generics.rules の形。逆コンパイラが型引数を落として javac が通さない
    ところに、witness や cast を書き足して通す。総称型は消去されるので命令列は変わらない。
    基本型への cast は値を変えるので落とさない。
    """
    out = list(seq)
    changed = True

    while changed:
        changed = False

        for at in range(1, len(out)):
            if out[at] != "<" or not (IDENT.fullmatch(out[at - 1]) or out[at - 1] == "."):
                continue

            end = at + 1

            while end < len(out) and TYPEISH.fullmatch(out[end]):
                end += 1

            if end < len(out) and out[end] == ">":
                del out[at:end + 1]
                changed = True
                break

    at = 0

    while at < len(out):
        # 参照型への cast: ( T ) の直後が式の頭
        if out[at] == "(":
            end = at + 1

            while end < len(out) and TYPEISH.fullmatch(out[end]):
                end += 1

            names = [word for word in out[at + 1:end] if IDENT.fullmatch(word)]

            if end < len(out) and out[end] == ")" and end + 1 < len(out) and names \
                    and names[-1] not in PRIMITIVE and names[-1][0].isupper() \
                    and (IDENT.fullmatch(out[end + 1]) or out[end + 1] in ("(", "\"")
                         or out[end + 1][0] in "\"'0123456789"):
                del out[at:end + 1]
                continue

        # this.new X(…) と new Outer.X(…) は同じもの(外側の this を渡す)
        if out[at] == "new":
            if at >= 2 and out[at - 2:at] == ["this", "."]:
                del out[at - 2:at]
                at -= 2

            end = at + 1

            while end + 2 < len(out) and IDENT.fullmatch(out[end]) and out[end + 1] == "." \
                    and IDENT.fullmatch(out[end + 2]):
                end += 2

            del out[at + 1:end]

        at += 1

    # cast を落として残った `(x)` の括弧(`((T) x).m()` の外側)
    at = 0

    while at + 2 < len(out):
        if out[at] == "(" and IDENT.fullmatch(out[at + 1]) and out[at + 2] == ")" \
                and not (at > 0 and (IDENT.fullmatch(out[at - 1]) or out[at - 1] in (")", "]", ">"))):
            del out[at + 2]
            del out[at]
            continue

        at += 1

    return out


def renamed(old, new):
    """new が old の局所変数の名前を付け替えただけか。

    逆コンパイラが同じ名前を 2 つの変数に使ったところ(`int i = this.index % i;`)を
    別の名前に分ける。付け替えた先の名前は old に無いものに限り、. の後ろ(欄やメソッド)と
    呼び出しの名前は付け替えない。
    """
    if len(old) != len(new):
        return False

    mapping = {}

    for index, (a, b) in enumerate(zip(old, new)):
        if a == b:
            continue

        if not IDENT.fullmatch(a) or not IDENT.fullmatch(b) or b in old \
                or (index > 0 and new[index - 1] == ".") \
                or (index + 1 < len(new) and new[index + 1] == "("):
            return False

        if mapping.setdefault(b, a) != a:
            return False

    return bool(mapping)


def uncast(seq):
    """`( ( T ) X )` を X に戻した字句の並び(patches/narrow)。"""
    out = list(seq)
    at = 0

    while at + 3 < len(out):
        if out[at] == "(" and out[at + 1] == "(":
            inner = closing(out, at + 1)
            outer = closing(out, at)

            if inner is not None and outer is not None and inner + 1 < outer \
                    and all(re.fullmatch(r"[\w$.<>?,\[\]]+", w) for w in out[at + 2:inner]):
                out = out[:at] + out[inner + 1:outer] + out[outer + 1:]
                continue

        at += 1

    return out


def tail_fired(old, new):
    """new が old の式の最後に ` && dev.shifu.event.…(…)` を 1 つ足しただけか。"""
    old_words = words(old)
    new_words = words(new)

    for end in ([";"], [")", "{"]):
        if old_words[-len(end):] != end or new_words[-len(end):] != end:
            continue

        head = old_words[:-len(end)]

        if new_words[:len(head)] != head or new_words[len(head):len(head) + 1] != ["&&"]:
            continue

        if is_fire_call(new_words[len(head) + 1:-len(end)]):
            return True

    return False


def wrapped(old, new):
    """new が old の式の 1 つを `dev.shifu.event.X(式, …)` に渡しただけか。"""
    new_words = words(new)
    old_words = words(old)

    for at in range(len(new_words) - 6):
        if new_words[at:at + 6] != ["dev", ".", "shifu", ".", "event", "."]:
            continue

        paren = at + 6

        while paren < len(new_words) and new_words[paren] != "(":
            paren += 1

        end = closing(new_words, paren)

        if end is None or not is_fire_call(new_words[at:end + 1]):
            continue

        first = split_top(new_words[paren + 1:end], (",",))[0][1]

        if new_words[:at] + first + new_words[end + 1:] == old_words:
            return True

    return False


EXPRS_KEY = re.compile(r"^[\w/$]+ (?P<name>[\w$<>]+)(?P<desc>\([^)]*\)\S+)$")


def is_exprs(rule):
    """patches/decompile/exprs.rules の規則か。"""
    return os.path.basename(rule.where.rsplit(":", 1)[0]) == "exprs.rules"


def descriptor_arity(desc):
    """メソッド記述子の引数の数。"""
    inner = desc[1:desc.index(")")]
    count = 0
    at = 0

    while at < len(inner):
        while inner[at] == "[":
            at += 1

        if inner[at] == "L":
            at = inner.index(";", at)

        at += 1
        count += 1

    return count


def blocks(text):
    """{ … } の塊ごとに (見出しの字句, 開き位置, 閉じ位置)。見出しは直前の ; / { / } から { までの字句。"""
    out = []
    stack = []
    head = []

    for word, at in tokens(text):
        if word == "{":
            stack.append((head, at))
            head = []
        elif word == "}":
            if stack:
                opened, start = stack.pop()
                out.append((opened, start, at))

            head = []
        elif word == ";":
            head = []
        else:
            head.append(word)

    return out


def declares(head, name, arity):
    """見出しが name という名前で引数 arity 個のメソッド(か構築子)の宣言か。"""
    for at, word in enumerate(head):
        if word != name or at + 1 >= len(head) or head[at + 1] != "(":
            continue

        if at > 0 and head[at - 1] in (".", "new"):
            continue

        end = closing(head, at + 1)

        if end is None or (end + 1 < len(head) and head[end + 1] != "throws"):
            continue

        params = head[at + 2:end]
        depth = 0
        count = 1 if params else 0

        for token in params:
            if token in ("(", "<", "["):
                depth += 1
            elif token in (")", ">", "]"):
                depth -= 1
            elif token == "," and depth == 0:
                count += 1

        if count == arity:
            return True

    return False


def check_exprs(lines, hits, rule):
    """exprs.rules の規則が method: を持ち、書き換えがそのメソッドの本体に収まっているか。違えば理由の文。

    書き換えの形は問わない。公式と同じ命令列になることは tools/lvtmatch の SemDiff が
    コンパイルしたあとに確かめる(tools/postcompile.sh)。ここで見るのは、証明の範囲
    (名指ししたメソッド)の外に書き換えが漏れていないことだけ。
    """
    if not rule.methods:
        return "exprs.rules の規則に method: が無い(SemDiff が公式と比べるメソッドを名指しする)"

    names = []

    for key in rule.methods:
        found = EXPRS_KEY.match(key)

        if not found:
            return "method: の形が読めない(<クラス> <名前><記述子>): %s" % key

        name = found.group("name")

        # 構築子はソースではクラスの単純名
        if name == "<init>":
            name = re.split(r"[/$]", key.split(" ", 1)[0])[-1]

        names.append((name, descriptor_arity(found.group("desc"))))

    text = "\n".join(lines)
    starts = [0]

    for line in lines:
        starts.append(starts[-1] + len(line) + 1)

    candidates = [(start, end) for head, start, end in blocks(text)
                  if any(declares(head, name, arity) for name, arity in names)]

    for hit in hits:
        first = starts[hit]
        last = starts[hit + len(rule.old) - 1] + len(lines[hit + len(rule.old) - 1])

        if not any(start < first and last < end for start, end in candidates):
            return "書き換えが method: で名指ししたメソッドの本体の外にある: %s" % ", ".join(rule.methods)

    return None


def line_pairs(rule):
    """同じ行数の置き換えで、違う行の組だけを返す。行数が違えば None。"""
    if len(rule.old) != len(rule.new):
        return None

    return [(a, b) for a, b in zip(rule.old, rule.new) if a.strip() != b.strip()]


def check_replace(kind, rule):
    """patch_adapter 形式の規則が kind の形に収まっているか。違えば理由の文。"""
    if kind == "decompile":
        if len(rule.old) == len(rule.new):
            old = erased(words("\n".join(rule.old)))
            new = erased(words("\n".join(rule.new)))

            if old == new or renamed(old, new):
                return None

        old = [line.strip() for line in rule.old]
        new = [line.strip() for line in rule.new]

        if len(new) == len(old) + 1 and old[:-1] == new[:-2] and hoisted(old[-1], new[-1], new[-2]):
            return None

        return "型引数・cast・局所変数の名前だけの違いでも、局所変数を直前に出す形でもない"

    if kind == "expr":
        pairs = line_pairs(rule)

        if pairs is not None and len(pairs) == 1:
            old, new = pairs[0]

            if tail_fired(old, new) or wrapped(old, new):
                return None

        return "式の末尾に発火を 1 つ足す形でも、式を発火の第 1 引数に包む形でもない"

    if kind == "narrow":
        pairs = line_pairs(rule)

        # 型を狭める cast を足す形と、可視性を広げる形(上書きしている 2 つが同じ行で
        # patches/access の 1 行のアンカーでは 1 つに定まらないもの)
        if pairs is not None and pairs and all(uncast(words(b)) == words(a) or modifier_change(a, b) is None
                                               for a, b in pairs):
            return None

        return "cast を足す形でも、可視性を広げる形でもない"

    return "知らない種類"


# ---------------------------------------------------------------- 規則を読む

def read_rules(folder, parse):
    rules = []

    if not os.path.isdir(folder):
        return rules

    for base, _, names in sorted(os.walk(folder)):
        for name in sorted(names):
            if name.endswith(".rules"):
                with open(os.path.join(base, name), encoding="utf-8") as handle:
                    where = os.path.relpath(os.path.join(base, name), os.path.dirname(os.path.dirname(folder)))
                    rules.extend(parse(handle.read(), where.replace(os.sep, "/")))

    return rules


def load_rules(patches, decompile=True):
    """{種類: {ファイル: [規則]}}。"""
    kinds = {"access": read_rules(os.path.join(patches, "access"), widen_access.parse),
             "narrow": read_rules(os.path.join(patches, "narrow"), patch_adapter.parse),
             "wire": read_rules(os.path.join(patches, "wire"), apply_events.parse)}

    if decompile:
        kinds["decompile"] = read_rules(os.path.join(patches, "decompile"), patch_adapter.parse)
        kinds["expr"] = read_rules(os.path.join(patches, "expr"), patch_adapter.parse)

    out = {}

    for kind, rules in kinds.items():
        for rule in rules:
            out.setdefault(kind, {}).setdefault(rule.target, []).append(rule)

    return out


def expected(lines, target, rules, problems):
    """基点の行に、vanilla の行を書き換えてよい規則(access / narrow / decompile / expr)を当てる。

    closure と同じ順に当てる。発火の差し込みの中に同じ文字列があると木では数が増えるので、
    基点で見つかった数が count 以下なら、見つかった分だけ当てる。
    """
    for kind in ("access", "narrow", "decompile", "expr"):
        for rule in rules.get(kind, {}).get(target, []):
            if kind == "access":
                try:
                    before = list(lines)
                    lines = widen_access.widen(lines, rule)
                except SystemExit:
                    continue

                if rule.interface:
                    problems.append((target, 0, "%s: 宣言に interface を足している(2026-09-03 に取り下げた形)"
                                     % rule.where))
                    continue

                for old, new in zip(before, lines):
                    if old != new:
                        why = modifier_change(old, new)

                        if why:
                            problems.append((target, 0, "%s: %s" % (rule.where, why)))

                continue

            hits = patch_adapter.find(lines, rule.old)

            if not hits or len(hits) > rule.count:
                continue

            if kind == "decompile" and is_exprs(rule):
                why = check_exprs(lines, hits, rule)
            else:
                why = check_replace(kind, rule)

            if why:
                problems.append((target, 0, "%s: %s\n        %s\n     -> %s" % (
                    rule.where, why, " / ".join(l.strip() for l in rule.old),
                    " / ".join(l.strip() for l in rule.new))))

            count = rule.count
            rule.count = len(hits)
            lines = patch_adapter.apply(lines, rule)
            rule.count = count

    return lines


# ---------------------------------------------------------------- 文脈

TYPE_WORDS = {"class", "interface", "enum", "record"}


class Frame:
    def __init__(self, kind, inserted, line, enum=False):
        self.kind = kind          # "type" / "code"
        self.inserted = inserted
        self.line = line
        self.enum = enum          # enum の定数の並びの途中(最初の ; まで)


def opens_type(header):
    """`{` の直前までの字句から、型の本体を開くかを見る。"""
    for index, word in enumerate(header):
        if word in TYPE_WORDS and (index == 0 or header[index - 1] not in (".", "::")) \
                and index + 1 < len(header) and re.fullmatch(r"[A-Za-z_$][\w$]*", header[index + 1]):
            return "enum" if word == "enum" else "type"

    # 無名クラス: new T<…>(…) {
    if header and header[-1] == ")":
        depth = 0

        for index in range(len(header) - 1, -1, -1):
            if header[index] == ")":
                depth += 1
            elif header[index] == "(":
                depth -= 1

                if depth == 0:
                    at = index - 1

                    while at >= 0 and re.fullmatch(r"[\w$.<>?,\[\]]+|extends|super", header[at]):
                        if header[at] == "new":
                            return "type"

                        at -= 1

                    return None

    return None


def scan(lines, inserted):
    """行ごとの文脈("top" / "type" / "code")と、括弧の対の食い違いを返す。

    文脈は行の頭の時点のもの。差し込んだ行と vanilla の行で開閉が食い違えば、
    (行番号, 理由) を返す。inserted は書き換えることがある(続いた `}` の入れ替え)。
    """
    text = "\n".join(lines)
    starts = [0]

    for line in lines:
        starts.append(starts[-1] + len(line) + 1)

    context = ["top"] * len(lines)
    stack = []
    header = []
    problems = []
    line_at = 0
    last_significant = [None] * len(lines)   # 行の頭の直前にある字句(括弧の無い本体の検出に使う)
    previous = []

    def top():
        return stack[-1].kind if stack else "top"

    for word, position in tokens(text):
        while line_at + 1 < len(lines) and starts[line_at + 1] <= position:
            line_at += 1
            context[line_at] = top()
            last_significant[line_at] = list(previous[-40:])

        here = line_at
        mine = inserted[here]

        if word == "{":
            kind = opens_type(header)

            if kind is None and stack and stack[-1].kind == "type" and stack[-1].enum:
                kind = "type"

            stack.append(Frame("type" if kind else "code", mine, here, enum=(kind == "enum")))
            header = []
        elif word == "}":
            if not stack:
                problems.append((here, "閉じる { が無い }"))
            else:
                frame = stack.pop()
                following = here + 1 < len(lines) and lines[here + 1].strip() == "}"

                if frame.inserted != mine and lines[here].strip() == "}" and following \
                        and inserted[here + 1] == frame.inserted:
                    # `}` だけの行が 2 つ続くと、差分はどちらを足した行にしても同じ並びになる。
                    # 開いた側に合わせて入れ替える(行が同じなので意味は変わらない)
                    inserted[here], inserted[here + 1] = inserted[here + 1], inserted[here]
                    mine = inserted[here]

                if frame.inserted != mine:
                    problems.append((here, "%d 行目の %s の { を %s の } で閉じている" % (
                        frame.line + 1, "差し込み" if frame.inserted else "vanilla",
                        "差し込み" if mine else "vanilla")))

            header = []
        elif word == ";":
            if stack and stack[-1].enum:
                stack[-1].enum = False

            header = []
        else:
            header.append(word)

        previous.append(word)

        if len(previous) > 64:
            del previous[:32]

    for line in range(line_at + 1, len(lines)):
        context[line] = top()
        last_significant[line] = list(previous[-40:])

    return context, last_significant, problems


def braceless_body(before):
    """before(直前の字句)で終わる位置が、括弧の無い if / for / while / else の本体の前か。"""
    if not before:
        return False

    if before[-1] in ("else", "do"):
        return True

    if before[-1] != ")":
        return False

    depth = 0

    for index in range(len(before) - 1, -1, -1):
        if before[index] == ")":
            depth += 1
        elif before[index] == "(":
            depth -= 1

            if depth == 0:
                return index > 0 and before[index - 1] in ("if", "for", "while")

    # 40 字句では閉じない長い条件。if / for / while が見えていれば括弧の無い本体として扱う
    return "if" in before or "while" in before or "for" in before


# ---------------------------------------------------------------- 差し込んだ塊

BUKKIT = re.compile(r"\b(?:dev\.shifu|org\.bukkit|io\.papermc|org\.spigotmc|com\.destroystokyo|co\.aikar)\.")


def statement_end(seq, at):
    """at から始まる文の ; の位置(括弧の外)。無ければ None。"""
    depth = 0

    for index in range(at, len(seq)):
        if seq[index] in ("(", "[", "{"):
            depth += 1
        elif seq[index] in (")", "]", "}"):
            depth -= 1
        elif seq[index] == ";" and depth == 0:
            return index

    return None


def fired_value(seq, fired):
    """発火の結果とみなせる式か(発火の条件、発火の結果の局所変数、字句)。"""
    if len(seq) == 1 and is_literal(seq[0]):
        return True

    return bool(seq) and fire_condition(seq, fired) is None


def simple_ok(statement, fired):
    """; で終わる 1 文。違えば理由の文。"""
    if is_fire_call(statement):
        return None

    parts = split_top(statement, ("=",))
    left = parts[0][1]
    name = left[-1] if left else ""
    declared = len(left) >= 2 and IDENT.fullmatch(name) and (IDENT.fullmatch(left[-2]) or left[-2] in (">", "]")) \
        and all(TYPEISH.fullmatch(word) or word in ("final", "<", ">") for word in left[:-1])

    # final T shifuX; / final T shifuX = <発火 か 字句 か 読むだけ>;
    # 発火の結果を置いておき、あとの if で見る。読むだけのものは発火に渡す控え
    if declared and name.startswith("shifu"):
        if len(parts) == 1:
            fired.names.add(name)
            return None

        right = parts[1][1]
        question = split_top(right, ("?",))

        if len(question) == 2:
            choice = split_top(question[1][1], (":",))
            test = question[0][1]
            # 登録が無ければ false の条件なら、? の後ろは評価されない
            good = len(choice) == 2 and fire_condition(test, fired) is None \
                and (is_guard(test, fired) or fired_value(choice[0][1], fired)) \
                and fired_value(choice[1][1], fired)
        else:
            good = fired_value(right, fired) or pure_read(right)

        if good:
            fired.names.add(name)

            if is_guard(right, fired):
                fired.guards.add(name)

            return None

        return "発火の結果でない値の局所変数: " + " ".join(statement) + " ;"

    if len(parts) == 2 and not declared:
        right = parts[1][1]

        # shifuX = <発火>;
        if len(left) == 1 and name in fired.names and fired_value(right, fired):
            return None

        # x = <発火の結果の局所変数>; / x = dev.shifu.event.X(…, x, …);
        # 登録が無ければ渡した x をそのまま返す(Paper が値を差し替えるところ)
        if from_fired(right, fired):
            return None

        # x = x && <発火>; / x = x || <発火の結果>;  登録が無ければ x は変わらない
        if right[:len(left)] == left and right[len(left):len(left) + 1] in (["&&"], ["||"]) \
                and fired_value(right[len(left) + 1:], fired):
            return None

        if is_fire_call(right):
            paren = right.index("(")

            if any(argument == left for _, argument in split_top(right[paren + 1:-1], (",",))):
                # 以後この変数を見る条件は、発火の結果を見ている
                if len(left) == 1:
                    fired.names.add(name)

                return None

    return "発火の形でない文: " + " ".join(statement) + " ;"


def else_part(seq, at, fired, check=True):
    """else の後ろを読む。(次の位置, 理由)。

    check が偽のときは、vanilla の文を囲んだ if の else(取り消されたときの道)なので
    中身を見ない。続く else if の中身も同じ。
    """
    if at >= len(seq):
        # 括弧の無い else が塊の終わりにある。続く vanilla の文を囲む形
        return at, None

    if seq[at] == "if":
        if check or at + 1 >= len(seq) or seq[at + 1] != "(":
            return at, None

        end = closing(seq, at + 1)

        if end is None:
            return len(seq), "if の条件が閉じていない"

        why = fire_condition(seq[at + 2:end], fired)

        if why:
            return len(seq), "else if (" + " ".join(seq[at + 2:end]) + "): " + why

        at = end + 1

        if at < len(seq) and seq[at] == "{":
            body = closing(seq, at, "{", "}")

            if body is None:
                return len(seq), None

            at = body + 1
        else:
            body = statement_end(seq, at)
            at = len(seq) if body is None else body + 1

        if at < len(seq) and seq[at] == "else":
            return else_part(seq, at + 1, fired, check)

        return at, None

    if seq[at] == "{":
        end = closing(seq, at, "{", "}")

        if end is None:
            # 開いたまま塊が終わる。vanilla の文を else 側で囲む形
            return len(seq), None

        why = statements_ok(seq[at + 1:end], fired) if check else None

        return end + 1, ("else の中身は登録が無くても走る: " + why) if why else None

    end = statement_end(seq, at)

    if end is None:
        return len(seq), "else の後ろが読めない"

    why = simple_ok(seq[at:end], fired) if check else None

    return end + 1, ("else の中身は登録が無くても走る: " + why) if why else None


def statements_ok(seq, fired):
    """メソッドの本体に差し込んだ字句の並びが、発火の形だけでできているか。違えば理由の文。

    fired はそのファイルで発火の結果を置いた局所変数。見つけたら足していく。
    """
    at = 0

    if seq[:1] in (["&&"], ["||"]):
        return "vanilla の式の途中に項を足している: " + " ".join(seq)

    while at < len(seq):
        word = seq[at]

        if word == ";":
            at += 1
            continue

        if word == "}":
            # 別の塊で開いた { を閉じる。対になっているかは scan() が見る
            at += 1

            if at < len(seq) and seq[at] == "else":
                at, why = else_part(seq, at + 1, fired, check=False)

                if why:
                    return why

            continue

        if word == "{":
            # 局所変数の生存範囲を切るための塊(公式と同じ slot に戻すため)
            end = closing(seq, at, "{", "}")

            if end is None:
                return "差し込みの { が閉じていない"

            why = statements_ok(seq[at + 1:end], fired)

            if why:
                return why

            at = end + 1
            continue

        if word in ("if", "for"):
            if at + 1 >= len(seq) or seq[at + 1] != "(":
                return word + " の条件が読めない"

            end = closing(seq, at + 1)

            if end is None:
                return word + " の条件が閉じていない"

            head = seq[at + 2:end]

            if word == "if" and state_guard(head, fired):
                # `if (this.<欄>) …`(STATE_FIELDS)。中身は欄が立ったときだけ走る。
                # vanilla の文を囲む形は、欄が既定の false の間 vanilla の文が飛ぶので落とす
                at = end + 1
                body = None

                if at < len(seq) and seq[at] == "{":
                    body = closing(seq, at, "{", "}")
                elif at < len(seq):
                    body = statement_end(seq, at)

                if body is None:
                    return "if (" + " ".join(head) + ") が vanilla の文を囲んでいる(欄が false の間は vanilla の文が飛ぶ)"

                at = body + 1

                if at < len(seq) and seq[at] == "else":
                    at, why = else_part(seq, at + 1, fired)

                    if why:
                        return why

                continue

            if word == "if":
                why = fire_condition(head, fired)

                if why and pure_read(head) and end + 1 < len(seq) and seq[end + 1] == "{" \
                        and closing(seq, end + 1, "{", "}") is not None:
                    # 読むだけの条件で、中身も発火の形だけなら通す(中身は登録が無いときも走るので見る)
                    body = closing(seq, end + 1, "{", "}")
                    inner = statements_ok(seq[end + 2:body], fired)

                    if inner:
                        return "if (" + " ".join(head) + ") の中身: " + inner

                    at = body + 1

                    if at < len(seq) and seq[at] == "else":
                        at, why = else_part(seq, at + 1, fired)

                        if why:
                            return why

                    continue

                if why:
                    return "if (" + " ".join(head) + "): " + why
            else:
                # for (T x : <発火の結果>) { … }  中身は発火の結果の数だけ走る
                colon = split_top(head, (":",))

                if len(colon) != 2 or not from_fired(colon[1][1], fired):
                    return "発火の結果を回す形でない for: " + " ".join(head)

            at = end + 1

            if at >= len(seq):
                # 括弧の無い if が塊の終わりにある。続く vanilla の文を囲む形
                if word == "if":
                    return None

                return "for の本体が無い"

            if seq[at] == "{":
                body = closing(seq, at, "{", "}")

                if body is None:
                    # 開いたまま塊が終わる。vanilla の文を囲む形(閉じるのは別の塊の })
                    return None if word == "if" else "for が vanilla の文を囲んでいる"

                at = body + 1
            else:
                # 括弧の無い本体(発火が止めるときだけ走る)
                end = statement_end(seq, at)

                if end is None:
                    return word + " の本体が閉じていない"

                at = end + 1

            if word == "if" and at < len(seq) and seq[at] == "else":
                at, why = else_part(seq, at + 1, fired)

                if why:
                    return why

            continue

        end = statement_end(seq, at)

        if end is None:
            return "文が閉じていない: " + " ".join(seq[at:])

        why = simple_ok(seq[at:end], fired)

        if why:
            return why

        at = end + 1

    return None


def wire_ok(block, members, vocabulary, fields):
    """wire の中身の文が、どれも Bukkit 層か vanilla に無い名前に触れているか。

    vanilla に無い名前は、Shifu が足したメンバー(members)か、そのファイルの vanilla に
    出てこない名前(vocabulary に無いもの)。closure の 1 周目は shim がまだ揃っていないので、
    足したメンバーだけでは見られない。
    `.` の後ろの欄の読み書き(呼び出しでないもの)は、vanilla で `.` の後ろに出てこない名前(fields に
    無いもの)なら vanilla に無い欄とみなす。main の ServerLevel.setMapData の `data.id = id;` は
    Paper の MapItemSavedData.id を入れる文だが、`id` が vanilla の引数の名前と同じで落ちていた。
    """
    seq = words("\n".join(block))
    at = 0

    while at < len(seq):
        end = at
        depth = 0

        while end < len(seq):
            if seq[end] in ("(", "{", "["):
                depth += 1
            elif seq[end] in (")", "}", "]"):
                depth -= 1

                if depth == 0 and seq[end] == "}" and (end + 1 >= len(seq) or seq[end + 1] != "else"):
                    break
            elif seq[end] == ";" and depth == 0:
                break

            end += 1

        statement = seq[at:end + 1]
        text = "".join(statement)

        if all(word in ("}", "{", "else") for word in statement):
            # 囲む形の開け閉め(`} else {`、`}`)
            at = end + 1
            continue

        foreign = any(IDENT.fullmatch(word) and index > 0 and statement[index - 1] == "."
                      and statement[index + 1:index + 2] != ["("] and word not in fields
                      for index, word in enumerate(statement))

        if not BUKKIT.search(text) and not any(word in members for word in statement) and not foreign \
                and not any(IDENT.fullmatch(word) and word not in vocabulary for word in statement):
            return "Bukkit 層にも vanilla に無い名前にも触れない文: " + " ".join(statement)

        at = end + 1

    return None


DECLARED = re.compile(r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*[\w.$<>\[\], ?@]*[\w>\]]\s+([A-Za-z_$][\w$]*)\s*[=;(]")
STATEMENT_WORDS = {"return", "throw", "new", "else", "case", "yield", "assert"}


def members_added(block):
    """足した行から、宣言した名前を拾う(`// Shifu - <名前>` の目印と、`型 名前 =/;/(` の行)。"""
    names = set()

    for line in block:
        found = re.match(r"\s*// Shifu - (\w+)\s*$", line)

        if found:
            names.add(found.group(1))
            continue

        found = DECLARED.match(line)

        if found and line.split()[0] not in STATEMENT_WORDS:
            names.add(found.group(1))

    return names


def type_block_ok(block):
    """型の本体に足した塊が、宣言だけでできているか。"""
    seq = words("\n".join(block))
    depth = 0
    header = []

    for word in seq:
        if word == "{":
            if depth == 0 and (not header or header == ["static"]):
                return "初期化ブロック(構築のたびに走る)"

            depth += 1
            header = []
        elif word == "}":
            depth -= 1

            if depth < 0:
                return "型の本体を閉じている"

            header = []
        elif word == ";":
            header = []
        elif depth == 0:
            header.append(word)

    return None if depth == 0 else "中括弧が釣り合わない"


IMPORT = re.compile(r"import (?:static )?[\w.$]+(?:\.\*)?;(?:\s*//.*)?")


def top_block_ok(block):
    for line in block:
        text = line.strip()

        if text and not text.startswith("//") and not IMPORT.fullmatch(text):
            return "ファイルの先頭に import 以外を足している: " + text

    return None


# ---------------------------------------------------------------- 差分

HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def run(args, cwd, ok=(0,)):
    done = subprocess.run(args, cwd=cwd, capture_output=True)

    if done.returncode not in ok:
        raise SystemExit("%s が失敗した: %s" % (" ".join(args[:3]),
                                             done.stderr.decode("utf-8", "replace").strip()[:300]))

    return done.stdout.decode("utf-8", "replace")


def read_lines(data):
    return data.replace("\r\n", "\n").split("\n")


def hunks(old, new):
    """[(old_start, old_lines, new_start, new_lines)](0 始まり)。git の histogram で揃える。"""
    folder = tempfile.mkdtemp(prefix="shifu-verify-")

    try:
        for name, lines in (("a", old), ("b", new)):
            with open(os.path.join(folder, name), "w", encoding="utf-8", newline="\n") as handle:
                handle.write("\n".join(lines))

        out = run(["git", "diff", "--no-index", "--no-color", "--text", "-U0", "--histogram", "a", "b"],
                  folder, ok=(0, 1))
    finally:
        shutil.rmtree(folder, ignore_errors=True)

    found = []

    for line in out.split("\n"):
        match = HUNK.match(line)

        if match:
            a, b, c, d = match.groups()
            a, c = int(a), int(c)
            b = 1 if b is None else int(b)
            d = 1 if d is None else int(d)
            found.append((a - 1 if b else a, b, c - 1 if d else c, d))

    return found


def check_file(target, old, new, rules, members, problems):
    """1 ファイル分。old は規則を当てた期待の並び、new は木。"""
    inserted = [False] * len(new)
    runs = []

    for a, b, c, d in hunks(old, new):
        removed = old[a:a + b]
        added = list(range(c, c + d))

        if removed:
            # 差分の表示のあや(差し込みの中の空行や } に vanilla の行が寄った)なら、
            # 消えた行は足した行の中に順どおり全部ある
            keep = set()
            at = 0

            for line in removed:
                while at < len(added) and new[added[at]] != line:
                    at += 1

                if at == len(added):
                    break

                keep.add(added[at])
                at += 1
            else:
                added = [n for n in added if n not in keep]
                removed = []

        if removed:
            for offset, line in enumerate(removed):
                problems.append((target, a + offset + 1, "vanilla の行が変わった: %s" % line.strip()))

            for n in added:
                problems.append((target, n + 1, "  -> %s" % new[n].strip()))

            continue

        for n in added:
            inserted[n] = True

    context, before, pairing = scan(new, inserted)
    fired = Fired()
    declared = members_added([line for line, mine in zip(new, inserted) if mine])
    fired.fields = {name for name in STATE_FIELDS if name in declared} - members_added(old)
    known = []

    def vocabulary():
        if not known:
            seq = words("\n".join(old))
            known.append((set(seq), {word for index, word in enumerate(seq) if index > 0 and seq[index - 1] == "."}))

        return known[0]

    for line, why in pairing:
        problems.append((target, line + 1, why))

    # 連続した差し込みの塊に分ける
    start = None

    for n in range(len(new) + 1):
        if n < len(new) and inserted[n]:
            if start is None:
                start = n
        elif start is not None:
            runs.append((start, n))
            start = None

    for start, end in runs:
        block = new[start:end]

        if not "".join(block).strip():
            continue

        kind = context[start]

        if kind == "top":
            why = top_block_ok(block)
        elif kind == "type":
            why = type_block_ok(block)
        else:
            why = code_block_ok(target, new, start, end, rules, members, before[start], fired, vocabulary)

        if why:
            problems.append((target, start + 1, why))


def code_block_ok(target, new, start, end, rules, members, previous, fired, vocabulary):
    """メソッドの本体に差し込んだ塊を見る。"""
    if braceless_body(previous):
        return "括弧の無い if / for / while / else の本体の前に差し込んでいる"

    block = new[start:end]
    wired = [False] * len(block)
    prev_line = new[start - 1].strip() if start > 0 else ""
    next_line = new[end].strip() if end < len(new) else ""
    stripped = [line.strip() for line in block]

    for rule in rules.get("wire", {}).get(target, []):
        for body, where in ((rule.before, "before"), (rule.after, "after")):
            want = [line.strip() for line in body]

            if not want:
                continue

            if where == "before" and next_line != rule.anchor[0].strip():
                continue

            if where == "after" and prev_line != rule.anchor[-1].strip():
                continue

            for at in range(len(block) - len(want) + 1):
                if stripped[at:at + len(want)] == want and not any(wired[at:at + len(want)]):
                    why = wire_ok(body, members, *vocabulary())

                    if why:
                        return "%s: %s" % (rule.where, why)

                    for index in range(at, at + len(want)):
                        wired[index] = True

                    break

    # wire で説明できない行を、発火の形として読む。wire の塊で切れた部分ごとに見る
    part = []

    for index, line in enumerate(block + [None]):
        if line is not None and not wired[index]:
            part.append(line)
            continue

        if part:
            why = statements_ok(words("\n".join(part)), fired)

            if why:
                return why

            part = []

    return None


# ---------------------------------------------------------------- Paper の AT

def mache_ats(tree, vanilla, with_ats, problems):
    """mache: Mache..paper ATs の差分が修飾子を広げるだけか。変わった行数を返す。"""
    out = run(["git", "-c", "core.quotepath=false", "diff", "--no-color", "-U0", vanilla, with_ats], tree)
    target = None
    old = []
    new = []
    changed = 0

    def flush():
        if len(old) != len(new):
            problems.append((target, 0, "Paper の AT が行の数を変えている"))
        else:
            for a, b in zip(old, new):
                why = modifier_change(a, b)

                if why:
                    problems.append((target, 0, "Paper の AT: %s: %s" % (why, a.strip())))

        del old[:]
        del new[:]

    for line in out.split("\n"):
        if line.startswith("+++ "):
            target = line[len("+++ b/"):]
        elif line.startswith("@@"):
            flush()
        elif line.startswith("-") and not line.startswith("---"):
            old.append(line[1:])
            changed += 1
        elif line.startswith("+") and not line.startswith("+++"):
            new.append(line[1:])

    flush()

    return changed


ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_STATIC, ACC_FINAL = 0x1, 0x2, 0x4, 0x8, 0x10


def class_flags(data):
    """クラスファイルの (クラスの flags, {(名前, 記述子): (flags, ConstantValue の有無)})。"""
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    at = 10
    index = 1

    while index < count:
        tag = data[at]

        if tag == 1:
            size = struct.unpack_from(">H", data, at + 1)[0]
            pool[index] = data[at + 3:at + 3 + size].decode("utf-8", "replace")
            at += 3 + size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            at += 5
        elif tag in (5, 6):
            at += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            at += 3
        elif tag == 15:
            at += 4
        else:
            raise ValueError("知らない定数の種類 %d" % tag)

        index += 1

    access = struct.unpack_from(">H", data, at)[0]
    interfaces = struct.unpack_from(">H", data, at + 6)[0]
    at += 8 + 2 * interfaces
    members = {}

    for _ in range(2):
        total = struct.unpack_from(">H", data, at)[0]
        at += 2

        for _ in range(total):
            flags, name, desc, attributes = struct.unpack_from(">HHHH", data, at)
            at += 8
            constant = False

            for _ in range(attributes):
                attr, size = struct.unpack_from(">HI", data, at)
                constant = constant or pool[attr] == "ConstantValue"
                at += 6 + size

            members[(pool[name], pool[desc])] = (flags, constant)

    return access, members


def widened_flags(old, new):
    """access flags が広がる向きにだけ変わったか。"""
    def rank(flags):
        if flags & ACC_PUBLIC:
            return 3
        if flags & ACC_PROTECTED:
            return 2
        if flags & ACC_PRIVATE:
            return 0
        return 1

    rest = ~(ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_FINAL) & 0xFFFF

    return (old & rest) == (new & rest) and rank(new) >= rank(old) and not (new & ACC_FINAL and not old & ACC_FINAL)


# Paper の AT が狭めているが、害が無いと確かめたもの。(クラス, 名前, 記述子, 前の flags, 後の flags)。
# ItemStack.components(1.20.6): vanilla は修飾子無しの final、Paper の AT(private-f)は private で final を外す。
# classic の木は AT を当てた jar を逆コンパイルしたものなので、Shifu の ItemStack もこの形で組まれる。
# 公式の jar でこの欄を読むクラスは ItemStack と ItemStack$1(同じ nest)だけで、Shifu の木で
# 代入しているのは構築子の 2 か所だけ(読んだ位置: <PW>/.gradle/caches/paperweight/taskCache/minecraft.jar の
# 全クラスの Fieldref、Shifu の木の net/minecraft/world/item/ItemStack.java)。
# Mixin は @Shadow と相手の final の食い違いを verbose のときに警告するだけ
# (読んだ位置: sponge-mixin 0.17.4+mixin.0.8.7 の MixinPreProcessorStandard.java:694-701)。
NARROWING_ATS = {
    ("net/minecraft/world/item/ItemStack", "components", "Lnet/minecraft/core/component/PatchedDataComponentMap;",
     ACC_FINAL, ACC_PRIVATE),
}


def classic_ats(paper, problems):
    """classic: fixJar.jar(AT の前)と applyMergedAt.jar(後)の flags を比べる。変わった数を返す。"""
    cache = os.path.join(paper, ".gradle", "caches", "paperweight", "taskCache")
    before = os.path.join(cache, "fixJar.jar")
    after = os.path.join(cache, "applyMergedAt.jar")

    if not os.path.isfile(before) or not os.path.isfile(after):
        problems.append(("(Paper の AT)", 0, "AT の前後の jar が無い: %s" % cache))
        return 0

    changed = 0

    with zipfile.ZipFile(before) as old_jar, zipfile.ZipFile(after) as new_jar:
        names = set(old_jar.namelist())

        for name in new_jar.namelist():
            if not name.endswith(".class") or name not in names:
                continue

            old_data = old_jar.read(name)
            new_data = new_jar.read(name)

            if old_data == new_data:
                continue

            old_class, old_members = class_flags(old_data)
            new_class, new_members = class_flags(new_data)
            target = name[:-len(".class")]

            if old_class != new_class:
                changed += 1

                if not widened_flags(old_class, new_class):
                    problems.append((target, 0, "Paper の AT: クラスの flags %#x -> %#x" % (old_class, new_class)))

            for key, (flags, constant) in new_members.items():
                old_flags = old_members.get(key, (flags, constant))[0]

                if old_flags == flags:
                    continue

                changed += 1

                if (target,) + key + (old_flags, flags) in NARROWING_ATS:
                    continue

                if not widened_flags(old_flags, flags):
                    problems.append((target, 0, "Paper の AT: %s%s の flags %#x -> %#x" % (key + (old_flags, flags))))
                elif constant and old_flags & ACC_STATIC and old_flags & ACC_FINAL and not flags & ACC_FINAL:
                    problems.append((target, 0, "Paper の AT: 定数 %s から final を外している" % key[0]))

    return changed


# ---------------------------------------------------------------- 本体

def commit(tree, subject):
    """その題名の一番新しいコミット。無ければ None。"""
    for line in run(["git", "log", "--format=%H %s"], tree).split("\n"):
        if line.endswith(" " + subject):
            return line.split(" ", 1)[0]

    return None


def blobs(tree, names):
    """`<コミット>:<パス>` の中身を行の並びで返す。

    git show に渡すと、長いパスで「Filename too long」になる(Windows)。
    cat-file --batch は引数に載せないので通る。1 回の起動で全部読める。
    """
    done = subprocess.run(["git", "cat-file", "--batch"], cwd=tree, capture_output=True,
                          input="".join(name + "\n" for name in names).encode("utf-8"))

    if done.returncode != 0:
        raise SystemExit("git cat-file が失敗した: %s" % done.stderr.decode("utf-8", "replace")[:300])

    data = done.stdout
    out = []
    at = 0

    for name in names:
        end = data.index(b"\n", at)
        head = data[at:end].split(b" ")

        if len(head) != 3:
            raise SystemExit("基点に無い: %s" % name)

        size = int(head[2])
        out.append(read_lines(data[end + 1:end + 1 + size].decode("utf-8", "replace")))
        at = end + 1 + size + 1

    return out


def verify(tree, patches=PATCHES, paper=None, decompile=True):
    """(問題の並び, 数え上げ) を返す。"""
    problems = []
    base = commit(tree, "paper Imports")

    if base is None:
        raise SystemExit("%s に paper Imports のコミットが無い" % tree)

    vanilla = commit(tree, "Mache")
    with_ats = commit(tree, "paper ATs")

    if vanilla and with_ats:
        ats = mache_ats(tree, vanilla, with_ats, problems)
    elif paper:
        ats = classic_ats(paper, problems)
    else:
        ats = None

    rules = load_rules(patches, decompile)
    status = run(["git", "-c", "core.quotepath=false", "diff", "--name-status", "--no-renames", base], tree)
    status += "".join("A\t%s\n" % line for line in run(
        ["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"], tree).split("\n") if line)
    changed = []
    added_files = 0

    for line in status.split("\n"):
        if not line.strip():
            continue

        mark, path = line.split("\t", 1)

        if mark == "A":
            added_files += 1
        elif mark == "D":
            problems.append((path, 0, "vanilla のファイルが消えた"))
        elif path.endswith(".java"):
            changed.append(path)

    old_of = dict(zip(changed, blobs(tree, ["%s:%s" % (base, path) for path in changed])))
    new_of = {}

    for path in changed:
        with open(os.path.join(tree, path), encoding="utf-8") as handle:
            new_of[path] = read_lines(handle.read())

    # wire の文が触れてよい名前は、どのファイルで足したメンバーでもよい(親で足したもの)
    members = set()

    for path in changed:
        old_set = set(old_of[path])
        members |= members_added([line for line in new_of[path] if line not in old_set])

    for path in changed:
        check_file(path, expected(old_of[path], path, rules, problems), new_of[path],
                   rules, members, problems)

    return problems, {"files": len(changed), "added_files": added_files, "ats": ats}


def main():
    args = [arg for arg in sys.argv[1:] if not arg.startswith("--")]
    tree = args[0] if args else os.environ.get("SHIFU_TREE")

    if not tree:
        import paths
        tree = paths.TREE

    paper = os.environ.get("SHIFU_PAPER")
    decompile = not os.environ.get("SHIFU_NO_DECOMPILE")
    problems, counts = verify(tree, PATCHES, paper, decompile)

    print("見たファイル: %d(vanilla に無いファイル %d は数えるだけ)" % (counts["files"], counts["added_files"]))

    if counts["ats"] is None:
        print("Paper の AT: AT の前が手元に無いので見ていない(SHIFU_PAPER を渡す)")
    else:
        print("Paper の AT: %d か所(修飾子を広げる向きだけか、定数から final を外していないかを見た)"
              % counts["ats"])

    if not problems:
        print("vanilla の行の変更は決めた形だけで、足した行も決めた形だけ。")
        return 0

    print()
    print("%d 件が決めた形に収まらない:" % len(problems))
    last = None

    for target, line, why in problems:
        if target != last:
            print("  %s" % target)
            last = target

        print("    %s%s" % ("%d: " % line if line else "", why))

    return 1


if __name__ == "__main__":
    sys.exit(main())
