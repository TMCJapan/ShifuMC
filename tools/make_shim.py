"""アダプタ層が要求するメンバーだけを vanilla に足す。

Shifu の NMS は vanilla のまま置く。ただし `org.bukkit.craftbukkit.*` は
`Entity.bukkitEntity` のような状態を NMS 側に置く前提で書かれているので、
その置き場所だけを足す必要がある。

**既存の行は 1 行も触らない。** 要求されているメンバーの宣言だけを抜き出して、
それが属する型の本体の末尾に足す。Paper のパッチは宣言と本体の書き換えを
同じ hunk に混ぜて出すので、hunk 単位で取ると挙動変更まで入ってしまう。

    python tools/make_shim.py <patches/sources> <src/minecraft/java> <required-members.txt>
"""

import importlib.util
import os
import re
import sys

spec = importlib.util.spec_from_file_location(
    "fs", os.path.join(os.path.dirname(os.path.abspath(__file__)), "filter_sources.py"))
fs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fs)

ENTRY = re.compile(r"^    (\w+)\s+([\w$]+)$")


def load_required(path):
    """要求されているメンバーの名前を集める。

    どのクラスのものかは見ない。エラーに出る型は呼び出し側の静的な型で、
    宣言している型とは限らない。`DedicatedServer.addLevel` は MinecraftServer の
    宣言だし、`Container.onOpen` は実装側の型に足す必要がある。
    名前で絞ると、その両方が落ちる。
    """
    names = set()

    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = ENTRY.match(line.rstrip("\n"))

            if match:
                names.add(match.group(2))

    return names


def declaration(line, names):
    """その行が要求されている名前を宣言していれば、その名前を返す。

    メソッドの中の局所変数もフィールドの形に見えるので、
    呼ぶ側が本体の外(波括弧の深さ 0)でだけ使うこと。
    """
    for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD):
        match = pattern.match(line)

        if not match or match.group(1) not in names:
            continue

        return match.group(1)

    return None


def depth_at(original, line):
    """元のファイルのその行の直前での波括弧の深さ。"""
    return sum(fs.line_deltas(original[:line]))


def members_in(original, bodies, hunk, names):
    """hunk が足している要素を (名前, 塊, 入る位置, @Override か) で返す。

    メソッドの中の局所変数は要素の宣言と同じ形をしている。
    見分けるには深さを見るしかないので、元のファイルでの深さから始めて、
    文脈行も含めて数えながら進む。型の直下にある追加行だけが要素の宣言。
    """
    pos = max(hunk.start - 1, 0)
    depth = depth_at(original, pos)
    seq = [(text.startswith("+"), text[1:])
           for text in hunk.lines if text[:1] in (" ", "+")]
    found = []
    index = 0

    while index < len(seq):
        is_added, text = seq[index]

        # 追加行はその時点の位置に入る。位置と深さの両方が合う型の直下に
        # あるものだけが要素の宣言。位置だけで見ると、要素の境目で
        # 隣の入れ子の型を拾ってしまう。
        at_member_level = any(b[0] <= pos <= b[1] and b[2] == depth for b in bodies)
        name = declaration(text, names) if is_added and at_member_level else None

        if name is None:
            depth += fs.structure_delta([text])[0]

            if not is_added:
                pos += 1

            index += 1
            continue

        # 宣言の直前に付いている注釈。塊には入らないので、ここで見ておく。
        # 祖先の宣言を上書きするものかの手掛かりになる(shadowing)。
        over = False
        back = index - 1

        while back >= 0 and seq[back][1].strip().startswith("@"):
            over = over or seq[back][1].strip().split("(")[0] == "@Override"
            back -= 1

        block = capture([t for _, t in seq[index:]], 0)
        span = seq[index:index + len(block)]
        index += len(block)
        depth += fs.structure_delta(block)[0]
        pos += sum(1 for added, _ in span if not added)

        # 途中に文脈行が混じる = 元からある行を巻き込む。足せない。
        if all(added for added, _ in span):
            found.append((name, block, pos, over))

    return found


def parens(lines):
    """丸括弧の増減。宣言が複数行に折り返されているかの判定に使う。"""
    total = 0

    for line in lines:
        text = fs.STRING.sub('""', line).split("//")[0]
        total += text.count("(") - text.count(")")

    return total


def indent(line):
    """字下げから、その要素が属する型の深さを見る。"""
    return (len(line) - len(line.lstrip())) // 4


def sound(block):
    """切り出した塊がそれだけで閉じているか。

    Paper のパッチは 1 つの要素を複数の hunk に分けて出すことがある。
    途中で切れた塊をそのまま足すと構文が壊れるので、閉じていないものは捨てて
    自前で書く対象に回す。
    """
    if not block:
        return False

    braces, comments, _ = fs.structure_delta(block)

    # if / else の釣り合いは見ない。要素の中で完結していれば足りる。
    if braces or comments or parens(block):
        return False

    text = fs.STRING.sub('""', block[-1]).split("//")[0].rstrip()

    return text.endswith((";", "}"))


def capture(added, start):
    """宣言の行から、その要素の終わりまでを切り出す。

    フィールドは `;` で終わる。メソッドや型は波括弧が閉じるまで。
    宣言そのものが複数行に折り返されていることがあるので、
    丸括弧が閉じるまでは終わりとみなさない。
    """
    block = []
    depth = 0
    open_parens = 0

    for index in range(start, len(added)):
        line = added[index]
        block.append(line)
        depth += fs.structure_delta([line])[0]
        open_parens += parens([line])

        if open_parens > 0 or depth != 0:
            continue

        text = fs.STRING.sub('""', line).split("//")[0].rstrip()

        if text.endswith((";", "}")):
            break

    return block



def is_type(lines, body, keywords):
    """その本体を開いている型の種類を見る。

    ヘッダーが複数行に折り返されていると、括弧が開く行にキーワードが無い。
    宣言の続きである行だけ遡って見る。
    """
    head = body[0]

    while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
        head -= 1

    words = " ".join(lines[head:body[0] + 1]).replace("(", " ").split()

    return any(word in words for word in keywords)


# 初期化子の無い欄の final。修飾子と注釈の後、型の前に来る。
EMPTY_FINAL = re.compile(
    r"^(\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:public\s+|protected\s+|private\s+)?)"
    r"final\s+(.*)$")


def unfinal(block):
    """初期化子の無い final の欄なら final を外して返す。

    Paper は足した欄を構築子の中で代入するが、vanilla の構築子は触らないので
    代入する場所が無い。javac は構築子 1 つにつき 1 件しか報告しないので、
    残したままだと 1 回のコンパイルで 1 件ずつしか見えない。

    final を外しても vanilla の挙動は変わらない。その欄は vanilla に無いものなので、
    vanilla のコードは 1 行も読まない。
    """
    if len(block) != 1:
        return block

    text = fs.STRING.sub('""', block[0]).split("//")[0]

    if "=" in text or "(" in text or not text.rstrip().endswith(";"):
        return block

    match = EMPTY_FINAL.match(block[0])

    return [match.group(1) + match.group(2)] if match else block


ARGS = re.compile(r"\(([^()]*)\)")
FINAL = re.compile(r"\bfinal\s+")
ARG = re.compile(r"^(.*?[\w\]>])\s+\w+$")


ANNOTATION = re.compile(r"^\s*@[\w.]+(?:\([^()]*\))?\s*")


def head_of(block):
    """宣言の頭。折り返していれば繋いで、本体や初期化子の手前で切る。"""
    text = ""

    for line in block:
        clean = fs.STRING.sub('""', line).split("//")[0]
        text += " " + clean

        if "{" in clean or ";" in clean:
            break

    text = " ".join(text.split())
    cuts = [at for at in (text.find("{"), text.find(";")) if at >= 0]

    return text[:min(cuts)] if cuts else text


def key_of(name, block):
    """宣言の鍵。

    欄は名前だけ。**初期化子まで鍵にすると、逆コンパイラの方言の差
    (`1.0D` と `1.0`)で「Paper にしか無い」に化ける。**
    メソッドは名前と引数の型。引数の名前と `final` は版で変わるので落とす。
    """
    text = FINAL.sub("", head_of(block))

    while True:
        cut = ANNOTATION.match(text)

        if not cut:
            break

        text = text[cut.end():]

    equals = text.find("=")
    paren = text.find("(")

    # 初期化子の中の呼び出しをメソッドの引数と読まない
    if paren < 0 or (0 <= equals < paren):
        return name

    depth = 0

    for at in range(paren, len(text)):
        depth += (text[at] == "(") - (text[at] == ")")

        if depth == 0:
            break
    else:
        return name

    args = []

    for one in text[paren + 1:at].split(","):
        one = one.strip()

        if not one:
            continue

        hit = ARG.match(one)
        args.append((hit.group(1) if hit else one).replace(" ", ""))

    return f"{name}({','.join(args)})"



def signature(block):
    """既出を数える鍵。宣言の行そのものを正規化して使う。

    名前だけで数えると 1 つしか足せない形が 2 つある。

      * 状態と読み出しが同じ名前(`private boolean hasStopped` と
        `public boolean hasStopped()`)
      * 引数違いのオーバーロード(`knownCause()` と `knownCause(cause)`)

    Paper はどちらも普通に足してくるので、行の中身まで見る。
    """
    head = fs.STRING.sub('""', block[0]).split("//")[0]

    return " ".join(head.split())


# 祖先が宣言しているものを見る。
#
# 足した宣言が祖先の vanilla の宣言を上書き・隠蔽すると、プラグインが 0 個でも
# vanilla の呼び出しがこちらへ来る。2026-09-21 のレビューで 3 件出た。
#
#   * AbstractSkeleton.addAdditionalSaveData(CompoundTag) は Entity の同名を上書きし、
#     スケルトンの NBT に Paper.ShouldBurnInDay が増えた
#   * RegionFile.ChunkBuffer.write は ByteArrayOutputStream の同名を上書きし、
#     vanilla の NbtIo.write がそちらを通った
#   * PlayerList.getPlayerStats(ServerPlayer) は vanilla の getPlayerStats(Player) より
#     引数が特殊なので、ServerPlayer の構築子の呼び出しが結び付き直した
#
# 1 回の実行で木は 1 つなので、読んだファイルと解決の結果は控えて使い回す。
CACHE = {"lines": {}, "supers": {}, "extends": {}, "declared": {}, "where": {}, "patched": {}}

# 暗黙の親。extends に書かないので継承の並びには出ないが、足すと
# vanilla の側の文字列の連結や Set への出し入れの結果が変わる。
OBJECT = ("toString()", "hashCode()", "equals(Object)", "clone()", "finalize()")

# 基本型の広がり。vanilla が `f(long)` を持つところに `f(int)` を足すと、
# int の式を渡している vanilla の呼び出しはそちらへ結び付く。
WIDER = {
    "byte": ("short", "int", "long", "float", "double"),
    "short": ("int", "long", "float", "double"),
    "char": ("int", "long", "float", "double"),
    "int": ("long", "float", "double"),
    "long": ("float", "double"),
    "float": ("double",),
}

PRIMITIVE = ("boolean", "byte", "short", "char", "int", "long", "float", "double", "void")

# 型引数で始まるメソッド(`<T> void getEntitiesByClass(...)`)。
# filter_sources の形は「修飾子 + 型 + 名前」なので、型引数が前に付くと当たらない。
GENERIC = re.compile(r"^\s+(?:(?:public|protected|private|static|final|default|abstract)\s+)*"
                     r"<[^>]+>\s+[\w$<>\[\],.?]+\s+([\w$]+)\s*\(")

# 引数に付く注釈。鍵は空白を落とすので `@Nullable Entity` が `@NullableEntity` になる。
# vanilla の宣言とアダプタ層の宣言で付け方が違うので、突き合わせる前に落とす。
MARKER = re.compile(r"^@[\w.]*?(?:Nullable|NotNull|NonNull|Nonnull|Deprecated)")


def lines_of(tree, rel):
    """木の中のファイルの行。無ければ None。

    vanilla の木と Paper の木の両方を引くので、控えの鍵に木を入れる。
    """
    mark = (tree, rel)

    if mark not in CACHE["lines"]:
        path = os.path.join(tree, rel.replace("/", os.sep))
        CACHE["lines"][mark] = (
            open(path, encoding="utf-8", errors="replace").read().split("\n")
            if os.path.isfile(path) else None)

    return CACHE["lines"][mark]


def plain(text):
    """型引数を落とす。`Predicate<? super Entity>` -> `Predicate`。"""
    out = []
    depth = 0

    for char in text:
        if char == "<":
            depth += 1
        elif char == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(char)

    return "".join(out)


def norm(key):
    """鍵を突き合わせる形にする。型引数とパッケージ名を落とす。

    vanilla とアダプタ層で同じ型の書き方が違う(`CompoundTag` と
    `net.minecraft.nbt.CompoundTag`)。そのままでは祖先の宣言と一致しない。
    """
    name, mark, args = key.partition("(")

    if not mark:
        return name

    return name + "(" + ",".join(
        MARKER.sub("", one.strip()).rsplit(".", 1)[-1]
        for one in plain(args.rstrip(")")).split(",") if one.strip()) + ")"


def kin(clause):
    """`extends A implements B, C` から親の名前。型引数は落とす。

    `ContainerSingleItem.BlockContainerSingleItem` のように入れ子の型を
    指していることがあるので、点は落とさずに返す。
    """
    text = plain(clause or "")

    for word in ("extends", "implements", "&"):
        text = text.replace(word, ",")

    return [one.strip() for one in text.split(",") if one.strip()]


def supers_of(tree, rel):
    """そのファイルの中の型の名前 -> 直接の親と実装先の単純名。"""
    mark = (tree, rel)

    if mark not in CACHE["supers"]:
        out = {}

        for line in lines_of(tree, rel) or ():
            # 型引数を先に落とす。`class EntityType<T extends Entity> implements ...` の
            # 「extends Entity」を親と読むと、無関係な型の宣言を祖先のものと見る
            match = fs.EXTENDS.match(plain(line))

            if match:
                out.setdefault(match.group(1), []).extend(kin(match.group(2)))

        CACHE["supers"][mark] = out

    return CACHE["supers"][mark]


def extends_of(tree, rel):
    """そのファイルの中の class の名前 -> `extends` に書いた親。

    interface は入れない。Paper は interface の側に adventure の親
    (`Component extends Iterable<Component>`)を足すので、そこを親として
    数えると「木の外の親を上書きしている」に見えてしまう。
    """
    mark = (tree, rel)

    if mark not in CACHE["extends"]:
        out = {}

        for line in lines_of(tree, rel) or ():
            text = plain(line)
            match = fs.EXTENDS.match(text)

            if not match or not re.search(r"\b(?:class|enum|record)\s+$", text[:match.start(1)]):
                continue

            clause = match.group(2) or ""
            cut = clause.find("implements")
            names = [one.strip() for one
                     in (clause[:cut] if cut >= 0 else clause).replace("extends", ",").split(",")
                     if one.strip()]

            if names:
                out[match.group(1)] = names[0]

        CACHE["extends"][mark] = out

    return CACHE["extends"][mark]


def outside_super(tree, rel, owner):
    """木の外にある親クラスの名前。無ければ None。

    `ChunkBuffer extends ByteArrayOutputStream` のように、実装を継いでいる
    相手が木の外にいるかを見る。
    """
    at = rel
    name = owner
    seen = set()

    while (at, name) not in seen:
        seen.add((at, name))
        up = extends_of(tree, at).get(name)

        if not up:
            return None

        where = where_of(tree, at, up)

        if not where:
            return up

        at = where
        name = up.rsplit(".", 1)[-1]

    return None


def where_of(tree, rel, name):
    """そのファイルから見た型の名前の相対パス。木の外のものは None。

    同じ単純名の型が木の中に何十個もある(`Entry`、`Builder`)。名前だけで
    引くと関係の無い型の宣言を祖先のものと読むので、そのファイルの中の宣言 ->
    import -> 同じパッケージ、の順で決める。
    """
    mark = (tree, rel, name)

    if mark in CACHE["where"]:
        return CACHE["where"][mark]

    # 入れ子の型(`ContainerSingleItem.BlockContainerSingleItem`)は外側の
    # ファイルにいる。パッケージ名が前に付いていることもあるので、
    # 大文字で始まる最初の語を外側の型として見る。
    parts = name.split(".")
    heads = [at for at, part in enumerate(parts) if part[:1].isupper()]
    outer = parts[heads[0]] if heads else name
    package = ".".join(parts[:heads[0]]) if heads else ""
    lines = lines_of(tree, rel) or []
    here = re.compile(r"\b(?:class|interface|enum|record)\s+" + re.escape(outer) + r"\b")
    imported = re.compile(r"^import\s+(?:static\s+)?([\w.]+)\." + re.escape(outer) + r";")

    if package:
        found = package.replace(".", "/") + "/" + outer + ".java"
    elif any(here.search(line) for line in lines):
        found = rel
    else:
        for line in lines:
            hit = imported.match(line.strip())

            if hit:
                found = hit.group(1).replace(".", "/") + "/" + outer + ".java"

                # 入れ子の型の import(`a.b.Outer.Inner`)。外側のファイルにいる
                if lines_of(tree, found) is None:
                    found = hit.group(1).replace(".", "/") + ".java"

                break
        else:
            near = os.path.dirname(rel)
            found = (near + "/" if near else "") + outer + ".java"

    if lines_of(tree, found) is None:
        found = None

    CACHE["where"][mark] = found

    return found


def owner_at(lines, start):
    """その本体を開いている型の名前。"""
    head = start

    while head > 0 and not lines[head - 1].rstrip().endswith((";", "{", "}")):
        head -= 1

    match = fs.TYPE_NAME.search(" ".join(lines[head:start + 1]))

    return match.group(1) if match else "(supertype)"


def declared_in(tree, rel):
    """そのファイルの型の直下にある宣言。(型の名前, 鍵) の集合。

    祖先が同じものを宣言しているかを見るだけなので、塊は取らない。
    """
    mark = (tree, rel)

    if mark not in CACHE["declared"]:
        lines = lines_of(tree, rel) or []
        out = set()

        for start, end, depth in fs.type_bodies(lines):
            owner = owner_at(lines, start)

            for number in range(start + 1, min(end + 1, len(lines))):
                line = lines[number]

                if not line.strip() or indent(line) != depth:
                    continue

                # 宣言が折り返していると 1 行では `(` や `;` まで届かない。
                # `;` か `{` が出るまで繋いでから見る(members と同じ)。
                joined = line.rstrip()

                for more in lines[number + 1:number + 8]:
                    if ";" in joined or "{" in joined:
                        break

                    joined += " " + more.strip()

                for probe in (line, joined):
                    match = next((hit for hit in (
                        pattern.match(probe) for pattern in
                        (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD, GENERIC)) if hit), None)

                    if match:
                        out.add((owner, norm(key_of(match.group(1), lines[number:number + 8]))))
                        break

        CACHE["declared"][mark] = out

    return CACHE["declared"][mark]


def ancestry(tree, rel, owner):
    """owner の上にある型。(相対パス, 名前) を近い順に返す。

    木の外の型(`ByteArrayOutputStream` など)は相対パスが None。
    そこに何が宣言されているかは読めないので、区別して返す。
    """
    out = []
    seen = {(rel, owner)}
    queue = [(rel, owner)]

    while queue:
        at, name = queue.pop(0)

        for parent in supers_of(tree, at).get(name, ()):
            up = (where_of(tree, at, parent), parent.rsplit(".", 1)[-1])

            if up in seen:
                continue

            seen.add(up)
            out.append(up)

            if up[0]:
                queue.append(up)

    return out


def patched_keys(root, rel):
    """`patches/sources` がそのファイルに足している宣言の鍵。

    mache は Paper の NMS がソースではなく差分なので、追加行から拾う。
    行の深さを見られないので局所変数も混ざるが、これは「祖先が既に持って
    いるから足してよい」と判断する側に使うので、混ざる分には害が無い。
    """
    mark = (root, rel)

    if mark not in CACHE["patched"]:
        path = os.path.join(root, rel.replace("/", os.sep) + ".patch")
        out = set()

        if os.path.isfile(path):
            added = [line[1:] for line in
                     open(path, encoding="utf-8", errors="replace").read().split("\n")
                     if line[:1] == "+" and not line.startswith("+++")]

            for number, line in enumerate(added):
                for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE, fs.PLAIN_FIELD, GENERIC):
                    match = pattern.match(line)

                    if match:
                        out.add(norm(key_of(match.group(1), added[number:number + 8])))
                        break

        CACHE["patched"][mark] = out

    return CACHE["patched"][mark]


def shape(key):
    """鍵の名前と引数の数。

    型引数を入れ替えた上書き(`Property.getIdFor(T)` と
    `BooleanProperty.getIdFor(Boolean)`)を同じものとして数えるのに使う。
    """
    name, mark, args = key.partition("(")

    if not mark:
        return (name, None)

    return (name, len([one for one in args.rstrip(")").split(",") if one.strip()]))


def annotated(block, mark="@Override"):
    """宣言に前置された注釈にそれがあるか。本体の中の注釈は見ない。"""
    for line in block:
        text = line.strip()

        if text.startswith("@"):
            if text.split("(")[0] == mark:
                return True

            continue

        if text.startswith("//") or not text:
            continue

        return False

    return False


def shadowing(tree, rel, owner, key, override, paper=None):
    """祖先の vanilla の宣言を上書き・隠蔽する宣言か。理由を返す。

    上書きすると、vanilla の呼び出しがこちらへ入る。`override` は Paper が
    その宣言に `@Override` を付けていたか。`paper` は木の中の型の相対パスから
    「Paper がそこに宣言しているものの鍵」を返す関数。
    """
    key = norm(key)
    chain = ancestry(tree, rel, owner)

    if key in OBJECT:
        return f"Object.{key} を上書きする"

    for where, name in chain:
        if where and (name, key) in declared_in(tree, where):
            return f"{name}.{key} を上書きする"

    # 木の外の親クラス(java.io.ByteArrayOutputStream など)が宣言している
    # ものは読めない。Paper が @Override を付けていて、木の中の祖先のどれも
    # その鍵を宣言していなければ、上書きの相手はその外の親。vanilla は
    # その実装をそのまま使っている。
    #
    # 逆に、木の中の祖先に Paper が同じものを宣言しているなら、それは shim が
    # 一緒に足す宣言(`Container.onOpen` のような Paper の追加)の実装なので足す。
    outside = outside_super(tree, rel, owner) if override and paper else None

    if outside and not any(shape(key) in {shape(one) for one in paper(where)}
                           for where, _ in chain if where):
        return f"木の外の親 {outside} のメソッドを上書きする"

    return None


def narrower(tree, rel, sub, sup):
    """sub が sup より特殊な型か。同じなら偽。"""
    sub = norm(sub)
    sup = norm(sup)

    if sub == sup:
        return False

    if sub in WIDER:
        return sup in WIDER[sub]

    if sub.endswith("...") or sup.endswith("..."):
        return False

    if sup == "Object":
        return True

    if sub.endswith("[]") or sup in PRIMITIVE:
        return False

    where = where_of(tree, rel, sub)

    return bool(where) and any(name == sup for _, name in ancestry(tree, where, sub))


def rebinding(tree, rel, owner, key):
    """vanilla の同名のメソッドより引数が特殊な多重定義か。理由を返す。

    Java は一番特殊な方を選ぶので、足すと vanilla の呼び出しの行き先が変わる。
    """
    name, mark, args = norm(key).partition("(")

    if not mark:
        return None

    args = [one for one in args.rstrip(")").split(",") if one]

    for where, who in [(rel, owner)] + ancestry(tree, rel, owner):
        if not where:
            continue

        for other, that in declared_in(tree, where):
            if other != who:
                continue

            name2, mark2, args2 = that.partition("(")

            if not mark2 or name2 != name:
                continue

            args2 = [one for one in args2.rstrip(")").split(",") if one]

            if len(args2) != len(args) or not args:
                continue

            if all(mine == theirs or narrower(tree, rel, mine, theirs)
                   for mine, theirs in zip(args, args2)):
                return f"{who}.{that} に結び付いている vanilla の呼び出しがこちらへ来る"

    return None


def place(lines, bodies, line, block):
    """その要素を置く型の本体を選ぶ。

    基本は一番内側。ただし record の本体にインスタンスのフィールドは
    置けないので、その場合だけ 1 つ外側に移す。
    Paper のパッチの位置が入れ子の型の中を指していることがあるため。
    """
    is_field = (not any("{" in text for text in block)
                and block[-1].rstrip().endswith(";")
                and "static" not in block[0])

    # 塊は line の行の手前に入る。本体がその行から始まる型は、
    # 括弧が開く行の手前 = 型の外なので含めない。
    inside = sorted((b for b in bodies if b[0] < line <= b[1]),
                    key=lambda b: b[0], reverse=True)

    for body in inside:
        # interface のフィールドは暗黙に static なので置ける。
        # record は本体にインスタンスのフィールドを持てない。
        if is_field and is_type(lines, body, ("record",)):
            continue

        return body

    return None


def already(lines, body, name):
    """その名前がその型の直下に既にあるか。

    Paper は既にあるフィールドの可視性を広げるだけの変更も出す。
    それを追加として取ると同名が 2 つになる。
    メソッドの中の局所変数を数えないよう、字下げが直下のものだけを見る。
    """
    start, end, depth = body

    for line in lines[start:end + 1]:
        if indent(line) != depth:
            continue

        for pattern in (fs.DECL, fs.FIELD, fs.TYPE_NAME, fs.IFACE):
            match = pattern.match(line)

            if match and match.group(1) == name:
                return True

    return False


IMPORT = re.compile(r"^import\s+(?:static\s+)?[\w.]+(?:\.\*)?;\s*(?://.*)?$")


def imports_in(hunks):
    """パッチが足している import。

    足した宣言は Paper が書いたままなので、Paper が同じパッチで足している
    import が要る。import を足しても、既にある行の意味は変わらない。
    同じ名前が二重に入れば javac がエラーにするので、黙って挙動が変わることはない。
    """
    found = []

    for hunk in sorted(hunks, key=lambda h: h.start):
        for line in hunk.added():
            text = line.strip()

            if IMPORT.match(text) and text not in found:
                found.append(text)

    return found


def import_spot(lines):
    """import を足す行。最後の import の次。import が無ければ package の次。"""
    spot = None

    for number, line in enumerate(lines):
        text = line.strip()

        if IMPORT.match(text) or text.startswith("package "):
            spot = number

    return spot + 1 if spot is not None else 0


ACCESS_NAME = re.compile(r"[\w$]+(?=\s*[;=(])")
HAND_MARK = re.compile(r"^// \S+\.([\w$]+)$", re.M)


def hand_written(root):
    """`patches/hand` で自前で書いてある要素を (対象, 名前) で集める。

    Paper の宣言をそのまま入れると閉じないもの(インターフェースの抽象メソッドが
    vanilla の無名クラスを壊す、など)は手で書き直す。同じ名前を両方から足すと
    宣言が 2 つになるので、手で書いた方を採る。
    """
    found = set()

    if not root or not os.path.isdir(root):
        return found

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".add"):
                continue

            path = os.path.join(base, name)
            target = os.path.relpath(path, root)[:-4].replace(os.sep, "/")

            with open(path, encoding="utf-8") as handle:
                for member in HAND_MARK.findall(handle.read()):
                    found.add((target, member))

    return found


def access_widened(root):
    """`patches/access` で可視性を広げる要素を (対象, 名前) で集める。

    そこに出てくるのは vanilla に既にある宣言なので、Paper のパッチから
    同じ名前を足すと宣言が 2 つになる。
    """
    found = set()

    if not root or not os.path.isdir(root):
        return found

    for base, _, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".rules"):
                continue

            target = None
            pending = False

            with open(os.path.join(base, name), encoding="utf-8") as handle:
                for raw in handle:
                    text = raw.strip()

                    if pending:
                        if not text:
                            continue

                        hit = ACCESS_NAME.search(text)

                        if hit:
                            found.add((target, hit.group(0)))

                        pending = False
                        continue

                    if text.startswith("file:"):
                        target = text[len("file:"):].strip()
                    elif text == "line:":
                        pending = True

    return found


# 宣言と見分けが付かない行。ここだけは名指しで外す。
# PacketProcessor の `final boolean isEmpty = ...` はメソッドの中の局所変数だが、
# 開き括弧が文脈行なので追加行だけを見ると要素の宣言と区別できない。
EXCLUDE = {
    ("net/minecraft/network/PacketProcessor.java", "isEmpty"),
}



def main():
    patch_root, tree, required = sys.argv[1:4]
    patches = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "patches")
    access_root = sys.argv[4] if len(sys.argv) > 4 else os.path.join(patches, "access")
    hand_root = sys.argv[5] if len(sys.argv) > 5 else os.path.join(patches, "hand")
    access = access_widened(access_root) | hand_written(hand_root)
    names = load_required(required)
    print(f"要求されている名前: {len(names)}")

    stats = {"files": 0, "members": 0, "missed": 0, "imports": 0}
    missed = []
    dropped = []

    def paper_side(at):
        """木の中の祖先で Paper が宣言しているものの鍵。"""
        return patched_keys(patch_root, at)

    for target, hunks in fs.patches(patch_root):
        path, original = fs.source(tree, target)

        if not original:
            continue

        bodies = fs.type_bodies(original)
        taken = {}
        seen = set()
        added = set()
        rejected = {}

        for hunk in sorted(hunks, key=lambda h: h.start):
            for name, block, pos, over in members_in(original, bodies, hunk, names):
                mark = signature(block)

                if mark in seen:
                    continue

                body = place(original, bodies, pos, block)

                # access は可視性で扱うもの、hand は手で書いたもの。どちらも
                # ここから足すと同じ名前が 2 つになる。
                if ((target, name) in EXCLUDE or (target, name) in access
                        or not sound(block) or body is None
                        or already(original, body, name)):
                    # ここで既出にしない。同じ名前が別の hunk にも出ることがあり、
                    # そちらは取り出せる形をしているかもしれない。
                    rejected.setdefault(target, set()).add(name)
                    continue

                # 祖先の vanilla の宣言を上書きするもの、vanilla の呼び出しが
                # 結び付き直す多重定義は足さない(shadowing の見出しに 3 件)。
                owner = owner_at(original, body[0])
                key = key_of(name, block)
                why = (shadowing(tree, target, owner, key, over, paper_side)
                       or rebinding(tree, target, owner, key))

                if why:
                    dropped.append(f"{target} {owner}.{key}: {why}")
                    continue

                seen.add(mark)
                added.add(name)
                taken.setdefault(body[1], []).extend(unfinal(block))
                stats["members"] += 1

        for name in sorted(rejected.get(target, set()) - added):
            missed.append(f"{target} {name}")
            stats["missed"] += 1

        if not taken:
            continue

        here = {line.strip() for line in original}
        extra = [text for text in imports_in(hunks) if text not in here]

        if extra:
            taken.setdefault(import_spot(original), []).extend(extra)
            stats["imports"] += len(extra)

        stats["files"] += 1
        out = []

        for number, line in enumerate(original):
            if number in taken:
                out.append("    // Shifu - アダプタ層が要求する追加")
                out.extend(taken[number])

            out.append(line)

        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(out))

    # 何を外したかは木にも出力にも残らないので、その場で書き出す。
    # 外れたものをアダプタ層が要るなら patches/hand に自前で書く。
    for line in dropped:
        print(f"  上書きになるので置かない: {line}", file=sys.stderr)

    print(f"足したファイル  : {stats['files']}")
    print(f"足したメンバー  : {stats['members']}")
    print(f"足した import   : {stats['imports']}")
    print(f"置き場所が不明  : {stats['missed']}")
    print(f"上書きになるもの: {len(dropped)}")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "docs", "backlog", "shim-todo.txt")

    with open(out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# Paper のパッチからそのまま取れなかった要素。自前で書く対象。\n")
        handle.write(f"# {len(missed)} 件\n\n")
        handle.write("\n".join(sorted(set(missed))) + "\n")

    print(f"-> {os.path.normpath(out)}")


if __name__ == "__main__":
    main()
