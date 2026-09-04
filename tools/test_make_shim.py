"""make_shim の判定を 1 つずつ確かめる。

コンパイルエラーの数だけを見て調整すると、判定どうしが干渉して
どれが効いたのか分からなくなる。入力と期待する出力をここに置き、
判定を変えるときは必ずここを通してから全体を回すこと。

    python tools/test_make_shim.py
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ms", os.path.join(HERE, "make_shim.py"))
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)
fs = ms.fs

FAILURES = []


def check(label, got, want):
    if got != want:
        FAILURES.append(f"{label}\n      got  {got!r}\n      want {want!r}")


# ---------------------------------------------------------------- 宣言の検出

NAMES = {"timeOffset", "interact", "isEmpty", "foo", "SavedPosition", "bukkitEntity",
         "getBukkitEntity", "dropResources", "processedDisconnect", "projectileSource"}


def test_declaration():
    cases = [
        ("    public long timeOffset = 0;", "timeOffset"),
        ("    public org.bukkit.craftbukkit.entity.CraftEntity bukkitEntity;", "bukkitEntity"),
        ("    boolean processedDisconnect;", "processedDisconnect"),
        # 型の途中に注釈が入る形。Paper はこれをよく使う
        ("    public org.bukkit.projectiles.@Nullable ProjectileSource projectileSource; // x",
         "projectileSource"),
        ("    InteractionResult interact(BlockState state, Level level);", "interact"),
        ("    public CraftEntity getBukkitEntity() {", "getBukkitEntity"),
        ("    public record SavedPosition(Optional<Vec3> position) {", "SavedPosition"),
        ("    public static void dropResources(", "dropResources"),
        # 宣言ではないもの。
        # 局所変数はここでは区別できない(形が同じ)。members_in が深さで弾く。
        ("        return foo(bar);", None),
        ("        this.foo(bar);", None),
        ("        if (foo) {", None),
        ("        for (Entity foo : list) {", None),
    ]

    for line, want in cases:
        check(f"declaration: {line.strip()[:50]}", ms.declaration(line, NAMES), want)


# ---------------------------------------------------------------- 切り出し

def test_capture():
    field = ["    public long timeOffset = 0;", "    public boolean relativeTime = true;"]
    check("capture: フィールドは 1 行", ms.capture(field, 0), field[:1])

    method = [
        "    public CraftEntity getBukkitEntity() {",
        "        if (this.bukkitEntity == null) {",
        "            this.bukkitEntity = CraftEntity.getEntity(this.server, this);",
        "        }",
        "        return this.bukkitEntity;",
        "    }",
        "    public int other;",
    ]
    check("capture: メソッドは閉じ括弧まで", ms.capture(method, 0), method[:6])

    wrapped = [
        "    public static void dropResources(",
        "        final BlockState state,",
        "        final Level level",
        "    ) {",
        "        doIt();",
        "    }",
    ]
    check("capture: 折り返した宣言も 1 つ", ms.capture(wrapped, 0), wrapped)

    check("sound: 閉じていれば真", ms.sound(method[:6]), True)
    check("sound: 途中で切れていれば偽", ms.sound(method[:3]), False)
    check("sound: 折り返しの途中も偽", ms.sound(wrapped[:2]), False)


# ---------------------------------------------------------------- 置き場所

OUTER = [
    "public class ServerPlayer extends Player {",           # 0
    "    public int level;",                                # 1
    "",                                                     # 2
    "    public void tick() {",                             # 3
    "        int level = 0;",                               # 4
    "    }",                                                # 5
    "",                                                     # 6
    "    public record SavedPosition(Optional<Vec3> pos) {",  # 7
    "        public static final MapCodec<SavedPosition> CODEC = null;",  # 8
    "    }",                                                # 9
    "",                                                     # 10
    "    public static class Helper {",                     # 11
    "        int count;",                                   # 12
    "    }",                                                # 13
    "}",                                                    # 14
]


def test_bodies():
    bodies = fs.type_bodies(OUTER)
    spans = sorted((b[0], b[1], b[2]) for b in bodies)
    check("type_bodies: 3 つの型", spans, [(0, 14, 1), (7, 9, 2), (11, 13, 2)])


# 宣言が複数行にまたがるクラス。Entity がこの形で、括弧が開く行に
# class のキーワードが無い。無名クラスと区別できること。
WRAPPED = [
    "public abstract class Entity implements",        # 0
    "    SlotProvider,",                              # 1
    "    TypedInstance<EntityType<?>> {",             # 2
    "    public boolean valid;",                      # 3
    "",                                               # 4
    "    public void tick() {",                       # 5
    "        Runnable task = new Runnable() {",       # 6
    "            public void run() {}",               # 7
    "        };",                                     # 8
    "    }",                                          # 9
    "}",                                              # 10
]


def test_bodies_wrapped_header():
    bodies = fs.type_bodies(WRAPPED)
    spans = sorted((b[0], b[1], b[2]) for b in bodies)
    check("type_bodies: 折り返した宣言も型として拾う", spans, [(2, 10, 1)])


def test_place():
    bodies = fs.type_bodies(OUTER)
    field = ["    public long timeOffset = 0;"]
    method = ["    public void extra() {", "    }"]

    # record の中を指していても、インスタンスのフィールドは外側のクラスへ
    check("place: record の中のフィールドは外へ",
          ms.place(OUTER, bodies, 8, field), (0, 14, 1))

    # 入れ子のクラスの中ならそこへ
    check("place: 入れ子のクラスはそのまま",
          ms.place(OUTER, bodies, 12, field), (11, 13, 2))

    # interface の定数は暗黙に static なのでそのまま置ける
    iface = [
        "public interface Container {",
        "    int getSize();",
        "}",
    ]
    check("place: interface に定数は置ける",
          ms.place(iface, fs.type_bodies(iface), 1, ["    int MAX_STACK = 99;"]),
          (0, 2, 1))

    # メソッドは record の中でも置ける
    check("place: record にメソッドは置ける",
          ms.place(OUTER, bodies, 8, method), (7, 9, 2))

    # 入れ子の型が始まる行を指しているとき、塊はその手前 = 外側のクラスに入る。
    # 内側に数えると interface の中にメソッドの本体を置いてしまう。
    check("place: 入れ子の型が始まる行は外側",
          ms.place(OUTER, bodies, 11, method), (0, 14, 1))


# ---------------------------------------------------------------- import

HEADER = [
    "package net.minecraft.world.level;",              # 0
    "",                                                # 1
    "import java.util.List;",                          # 2
    "import net.minecraft.core.BlockPos;",             # 3
    "",                                                # 4
    "public class Level {",                            # 5
    "}",                                               # 6
]


def test_import_spot():
    check("import_spot: 最後の import の次", ms.import_spot(HEADER), 4)
    check("import_spot: import が無ければ package の次",
          ms.import_spot(["package a.b;", "", "class C {}"]), 1)


def test_imports_in():
    hunk = fs.Hunk(1, 0)
    hunk.lines = [
        " package net.minecraft.world.level;",
        "+import java.util.Map;",
        "+import org.bukkit.craftbukkit.CraftWorld;",
        " import java.util.List;",
        "+    public Map<BlockPos, CraftWorld> captured;",
    ]
    check("imports_in: 足した import だけ取る", ms.imports_in([hunk]),
          ["import java.util.Map;", "import org.bukkit.craftbukkit.CraftWorld;"])


# ---------------------------------------------------------------- 空の final

def test_unfinal():
    # Paper は足した欄を構築子で代入するが、vanilla の構築子は触らないので
    # 代入する場所が無い。javac は構築子 1 つにつき 1 件しか報告しないので、
    # 残したままだと 1 回のコンパイルで 1 件ずつしか見えない。
    check("unfinal: 初期化子が無ければ外す",
          ms.unfinal(["    public final WorldLoader.DataLoadContext worldLoaderContext;"]),
          ["    public WorldLoader.DataLoadContext worldLoaderContext;"])
    check("unfinal: 注釈が挟まっていても外す",
          ms.unfinal(["    public final java.util.@Nullable Locale adventure$locale; // Paper"]),
          ["    public java.util.@Nullable Locale adventure$locale; // Paper"])

    # 初期化子があるものは代入できているので触らない
    keep = ["    public static final long IGNORE_WEATHERING_TICK = -2L;"]
    check("unfinal: 初期化子があれば残す", ms.unfinal(keep), keep)

    # メソッドの final は別のもの
    method = ["    public final int getX() {", "    }"]
    check("unfinal: メソッドは触らない", ms.unfinal(method), method)


# ---------------------------------------------------------------- 注釈

def test_leading_annotation():
    # Paper は `@Nullable public SocketAddress haProxyAddress;` の順で書くことがある。
    # 修飾子で始まる前提だと、この行は宣言として見えない。
    check("declaration: 前に付いた注釈",
          ms.declaration("    @Nullable public SocketAddress haProxyAddress; // Paper",
                         {"haProxyAddress"}), "haProxyAddress")
    check("declaration: 注釈だけの行は宣言ではない",
          ms.declaration("    @Override", {"Override"}), None)


# ---------------------------------------------------------------- 識別子

def test_dollar_name():
    # Paper は adventure 由来のフィールドを `adventure$locale` の名前で足す。
    # 名前を \w+ で取ると `adventure` で切れて、要求とも宣言とも一致しなくなる。
    line = "    public java.util.Locale adventure$locale = java.util.Locale.US;"
    check("declaration: $ を含む名前",
          ms.declaration(line, {"adventure$locale"}), "adventure$locale")
    check("declaration: 途中で切った名前では当たらない",
          ms.declaration(line, {"adventure"}), None)


# ---------------------------------------------------------------- 既出の鍵

def test_signature():
    # 状態と読み出しが同じ名前。両方足せること
    field = ms.signature(["    private boolean hasStopped = false;"])
    method = ms.signature(["    public final boolean hasStopped() {", "    }"])
    check("signature: フィールドとメソッドは別", field != method, True)

    # 引数違いのオーバーロード。両方足せること
    empty = ms.signature(["    public DamageCause knownCause() {", "    }"])
    with_arg = ms.signature(["    public DamageSource knownCause(final DamageCause cause) {", "    }"])
    check("signature: 引数違いは別", empty != with_arg, True)

    # 字下げとコメントの違いでは分かれないこと
    check("signature: コメントと空白は無視",
          ms.signature(["    public void tick() { // Paper"]),
          ms.signature(["        public  void  tick() {"]))


# ---------------------------------------------------------------- 重複

def test_already():
    bodies = fs.type_bodies(OUTER)
    outer = next(b for b in bodies if b[0] == 0)

    check("already: 直下にあれば真", ms.already(OUTER, outer, "level"), True)
    check("already: 無ければ偽", ms.already(OUTER, outer, "timeOffset"), False)

    # メソッドの中の局所変数 `level` を数えて入れ子のクラスを誤判定しないこと
    helper = next(b for b in bodies if b[0] == 11)
    check("already: 局所変数は数えない", ms.already(OUTER, helper, "level"), False)


# ---------------------------------------------------------------- 走査

def named(found):
    """位置を落として (名前, 塊) だけにする。"""
    return [(name, block) for name, block, _ in found]


class FakeHunk:
    def __init__(self, start, lines):
        self.start = start
        self.lines = lines


def test_members_in():
    bodies = fs.type_bodies(OUTER)

    # 型の直下に足すフィールド。OUTER の 6 行目(空行)に足す。
    at_member_level = FakeHunk(7, [
        " ",
        "+    public long timeOffset = 0;",
        " ",
    ])
    check("members_in: 型の直下は拾う",
          named(ms.members_in(OUTER, bodies, at_member_level, {"timeOffset"})),
          [("timeOffset", ["    public long timeOffset = 0;"])])

    # メソッドの中の局所変数。tick() の本体(4 行目)に足す。
    in_method = FakeHunk(5, [
        " ",
        "+        final boolean isEmpty = this.queue.isEmpty();",
        " ",
    ])
    check("members_in: メソッドの中は拾わない",
          named(ms.members_in(OUTER, bodies, in_method, {"isEmpty"})), [])

    # 起点がクラス宣言の途中(本体の開始より前)を指す形。Entity がこれ。
    wrapped_bodies = fs.type_bodies(WRAPPED)
    at_header = FakeHunk(1, [
        "     SlotProvider,",
        "     TypedInstance<EntityType<?>> {",
        "+    public boolean valid;",
    ])
    check("members_in: 宣言の途中が起点でも拾う",
          named(ms.members_in(WRAPPED, wrapped_bodies, at_header, {"valid"})),
          [("valid", ["    public boolean valid;"])])

    # 文脈行を巻き込む塊は足せない
    straddles = FakeHunk(7, [
        "+    public void extra() {",
        "         doIt();",
        "+    }",
    ])
    check("members_in: 文脈行を含む塊は足さない",
          named(ms.members_in(OUTER, bodies, straddles, {"extra"})), [])


def main():
    for name, test in sorted(globals().items()):
        if name.startswith("test_"):
            test()

    if not FAILURES:
        print("すべて通った")
        return 0

    print(f"{len(FAILURES)} 件が通らない\n")

    for failure in FAILURES:
        print(f"  {failure}\n")

    return 1


if __name__ == "__main__":
    sys.exit(main())
