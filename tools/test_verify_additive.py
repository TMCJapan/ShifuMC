"""verify_additive の判定を確かめる。

    python tools/test_verify_additive.py

小さな git の木を作り、vanilla の 1 ファイルと当てたあとの形を並べて verify() に渡す。
2026-09-21 のレビューで通ってしまった形(足した行を見ていない、並びを見ていない、
式の末尾の後ろに || を足せる、AT が基点に埋もれている)は、どれも落ちることを確かめる。
"""

import importlib.util
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec = importlib.util.spec_from_file_location("va", os.path.join(HERE, "verify_additive.py"))
va = importlib.util.module_from_spec(spec)
spec.loader.exec_module(va)

FAILURES = []
TARGET = "net/minecraft/Sample.java"

VANILLA = """package net.minecraft;

import java.util.List;

public class Sample {
    private int count;
    private static final int LIMIT = 4;

    public boolean tick(Level level, BlockPos pos) {
        this.prepare();
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.open();
        }
        for (Entity entity : level.getEntities(pos)) {
            entity.push();
        }
        this.count++;
        return this.count > LIMIT;
    }

    public boolean canUse() {
        return this.count > 0;
    }
}
"""


def git(tree, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "core.autocrlf=false"] + list(args),
                   cwd=tree, check=True, capture_output=True)


def write(root, path, text):
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)

    with open(full, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def case(applied, rules=None, vanilla=VANILLA, ats=None):
    """問題の並びを返す。ats を渡すと Mache -> paper ATs -> paper Imports と積む(mache)。"""
    root = tempfile.mkdtemp(prefix="shifu-test-verify-")

    try:
        tree = os.path.join(root, "tree")
        patches = os.path.join(root, "patches")
        os.makedirs(tree)
        os.makedirs(patches)
        git(tree, "init", "-q")
        write(tree, TARGET, vanilla)
        git(tree, "add", "-A")

        if ats is not None:
            git(tree, "commit", "-qm", "Mache")
            write(tree, TARGET, ats)
            git(tree, "commit", "-qam", "paper ATs")

        git(tree, "commit", "-q", "--allow-empty", "-m", "paper Imports")
        write(tree, TARGET, applied)

        for path, text in (rules or {}).items():
            write(patches, path, text)

        problems, _ = va.verify(tree, patches)

        return [why for _, _, why in problems]
    finally:
        shutil.rmtree(root, ignore_errors=True)


def passes(label, applied, rules=None, **more):
    got = case(applied, rules, **more)

    if got:
        FAILURES.append("%s: 通るはずが落ちた\n      %s" % (label, "\n      ".join(got)))


def fails(label, applied, rules=None, want=None, **more):
    got = case(applied, rules, **more)

    if not got:
        FAILURES.append("%s: 落ちるはずが通った" % label)
    elif want and not any(want in why for why in got):
        FAILURES.append("%s: 理由が違う(%r を含むはず)\n      %s" % (label, want, "\n      ".join(got)))


def insert(text, anchor, lines):
    """anchor の行の前に lines を入れる(字下げは anchor に合わせる)。"""
    out = []

    for line in text.split("\n"):
        if line.strip() == anchor:
            pad = line[:len(line) - len(line.lstrip())]
            out.extend(pad + one if one else "" for one in lines)

        out.append(line)

    return "\n".join(out)


def after(text, anchor, lines):
    out = []

    for line in text.split("\n"):
        out.append(line)

        if line.strip() == anchor:
            pad = line[:len(line) - len(line.lstrip())]
            out.extend(pad + one if one else "" for one in lines)

    return "\n".join(out)


# ---------------------------------------------------------------- 許す形

def test_untouched():
    passes("何もしていない", VANILLA)


def test_early_exit():
    passes("途中で抜ける形", insert(VANILLA, "this.prepare();", [
        "// Shifu - イベント発火",
        "if (!dev.shifu.event.ShifuEvents.tick(this, pos)) {",
        "    return false;",
        "}",
    ]))


def test_wrap():
    text = insert(VANILLA, "this.count++;", ["if (dev.shifu.event.ShifuEvents.count(this)) {"])
    passes("vanilla の文を囲む形", after(text, "this.count++;", ["}"]))


def test_bare_fire():
    passes("発火の呼び出し 1 つ", after(VANILLA, "this.prepare();", [
        "dev.shifu.event.ShifuEvents.prepared(this);",
    ]))


def test_guarded_reads():
    passes("listening の後ろは評価されない", insert(VANILLA, "this.prepare();", [
        "if (dev.shifu.event.EventGuard.listening(org.bukkit.event.Event.class) && this.count > level.size()) {",
        "    new org.bukkit.event.Event().callEvent();",
        "}",
    ]))


def test_fired_local():
    passes("発火の結果を局所変数に置いて見る", insert(VANILLA, "this.prepare();", [
        "final org.bukkit.event.Event shifuEvent = dev.shifu.event.ShifuEvents.tickEvent(this);",
        "if (shifuEvent != null && shifuEvent.isCancelled()) {",
        "    return false;",
        "}",
    ]))


def test_value_through_fire():
    passes("値を発火に通して受け直す", after(VANILLA, "this.prepare();", [
        "this.count = dev.shifu.event.ShifuEvents.count(this, this.count);",
    ]))


def test_member_and_import():
    text = after(VANILLA, "import java.util.List;", ["import org.bukkit.Bukkit; // Paper"])
    text = text.replace("        return this.count > 0;\n    }\n}",
                        "        return this.count > 0;\n    }\n\n    // Shifu - bukkit\n"
                        "    public Object bukkit;\n\n    public Object getBukkit() {\n"
                        "        return this.bukkit;\n    }\n}")
    passes("import とメンバーの追加", text)


def test_access():
    passes("patches/access で可視性を広げる",
           VANILLA.replace("    private int count;", "    public int count;"),
           {"access/a.rules": "file: %s\nline:\n    private int count;\n" % TARGET})


def test_decompile_local():
    rules = {"decompile/locals.rules": (
        "file: %s\nreplace:\n    if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {\nwith:\n"
        "    BlockEntity blockEntity = level.getBlockEntity(pos);\n"
        "    if (blockEntity instanceof ChestBlockEntity chest) {\n" % TARGET)}
    text = VANILLA.replace(
        "        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {",
        "        BlockEntity blockEntity = level.getBlockEntity(pos);\n"
        "        if (blockEntity instanceof ChestBlockEntity chest) {")
    passes("逆コンパイラが消した局所変数を戻す", text, rules)


def test_decompile_witness():
    rules = {"decompile/generics.rules": (
        "file: %s\nreplace:\n    for (Entity entity : level.getEntities(pos)) {\nwith:\n"
        "    for (Entity entity : level.<Entity>getEntities(pos)) {\n" % TARGET)}
    passes("型の witness を足す",
           VANILLA.replace("level.getEntities(pos)", "level.<Entity>getEntities(pos)"), rules)


def test_expr_tail():
    rules = {"expr/tail.rules": (
        "file: %s\nreplace:\n    return this.count > 0;\nwith:\n"
        "    return this.count > 0 && dev.shifu.event.ShifuEvents.canUse(this);\n" % TARGET)}
    passes("式の末尾に発火を足す",
           VANILLA.replace("return this.count > 0;",
                           "return this.count > 0 && dev.shifu.event.ShifuEvents.canUse(this);"), rules)


EXPRS_TICK = "net/minecraft/Sample tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
EXPRS_TEXT = VANILLA.replace("return this.count > LIMIT;", "return !(this.count <= LIMIT);")


def exprs_rule(method):
    key = "method: %s\n" % method if method else ""
    return {"decompile/exprs.rules": "file: %s\n%sreplace:\n    return this.count > LIMIT;\nwith:\n"
                                     "    return !(this.count <= LIMIT);\n" % (TARGET, key)}


def test_exprs_with_method():
    passes("exprs.rules: method: で名指ししたメソッドの中の書き換え", EXPRS_TEXT, exprs_rule(EXPRS_TICK))


def test_narrow_cast():
    rules = {"narrow/n.rules": "file: %s\nreplace:\n    chest.open();\nwith:\n    ((Chest) chest).open();\n" % TARGET}
    passes("patches/narrow で cast を足す", VANILLA.replace("chest.open();", "((Chest) chest).open();"), rules)


def test_wire():
    rules = {"wire/w.rules": ("file: %s\nanchor:\n    this.prepare();\ninsert-after:\n"
                              "    org.bukkit.Bukkit.getServer().tick();\n" % TARGET)}
    passes("wire の規則の中身がアンカーの隣にある",
           after(VANILLA, "this.prepare();", ["org.bukkit.Bukkit.getServer().tick();"]), rules)


def member(text, name, declaration="public boolean %s;"):
    """型の本体の終わりに Shifu の欄を足す(shim / hand の形)。"""
    return text.replace("        return this.count > 0;\n    }\n}",
                        "        return this.count > 0;\n    }\n\n    // Shifu - %s\n    %s\n}"
                        % (name, declaration % name))


def test_state_field():
    passes("Shifu が宣言した STATE_FIELDS の欄を読む if", member(insert(VANILLA, "this.prepare();", [
        "if (this.shifuClientWorldBorder) {",
        "    ((org.bukkit.craftbukkit.CraftWorldBorder) this.getBukkitEntity().getWorldBorder()).getHandle().tick();",
        "}",
    ]), "shifuClientWorldBorder"))
    passes("STATE_FIELDS の欄を読む括弧の無い if", member(insert(VANILLA, "this.prepare();", [
        "if (this.fixedPose) return false;",
    ]), "fixedPose"))


def test_wire_bukkit_field():
    # main の ServerLevel.setMapData の `data.id = id;`。欄の名前が vanilla の引数の名前と同じ
    rules = {"wire/w.rules": ("file: %s\nanchor:\n    this.prepare();\ninsert-after:\n"
                              "    level.pos = pos;\n" % TARGET)}
    passes("wire が vanilla の引数と同じ名前の Bukkit 層の欄に入れる",
           after(VANILLA, "this.prepare();", ["level.pos = pos;"]), rules)


def test_mache_ats():
    ats = VANILLA.replace("    private int count;", "    public int count;")
    passes("Paper の AT が可視性を広げる(mache)", ats, ats=ats)


# ---------------------------------------------------------------- 落とす形(2026-09-21 の穴)

def test_exprs_without_method():
    fails("exprs.rules: method: が無い", EXPRS_TEXT, exprs_rule(None), want="method: が無い")


def test_exprs_outside_method():
    fails("exprs.rules: 名指ししたメソッドの外の書き換え", EXPRS_TEXT,
          exprs_rule("net/minecraft/Sample canUse()Z"), want="本体の外")


def test_added_statement():
    fails("足した行 this.x = 2; を見ていなかった", after(VANILLA, "this.prepare();", ["this.count = 2;"]),
          want="発火の形でない文")


def test_added_random():
    fails("発火の条件に || this.random… を足す", insert(VANILLA, "this.prepare();", [
        "if (!dev.shifu.event.ShifuEvents.tick(this, pos) || this.random.nextInt(2) == 0) {",
        "    return false;",
        "}",
    ]), want="発火でない項")


def test_added_to_vanilla_condition():
    fails("vanilla の条件の途中に || の行を足す",
          VANILLA.replace("        return this.count > LIMIT;",
                          "        return this.count > LIMIT\n            || this.random.nextInt(2) == 0;"),
          want="vanilla の行が変わった")


def test_hoist_order_in_rule():
    # 宣言を 1 つ前の文より前に出すと、level.getBlockEntity(pos) が this.prepare() より先に走る
    rules = {"decompile/locals.rules": (
        "file: %s\nreplace:\n    this.prepare();\n"
        "    if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {\nwith:\n"
        "    BlockEntity blockEntity = level.getBlockEntity(pos);\n    this.prepare();\n"
        "    if (blockEntity instanceof ChestBlockEntity chest) {\n" % TARGET)}
    text = VANILLA.replace(
        "        this.prepare();\n        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {",
        "        BlockEntity blockEntity = level.getBlockEntity(pos);\n        this.prepare();\n"
        "        if (blockEntity instanceof ChestBlockEntity chest) {")
    fails("局所変数を戻す規則が評価の順を変える", text, rules, want="局所変数を直前に出す形でもない")


def test_hoist_order_in_tree():
    # 規則は正しいが、木では宣言が別の文の前にある(ファイルのどこかにあれば通っていた)
    rules = {"decompile/locals.rules": (
        "file: %s\nreplace:\n    if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {\nwith:\n"
        "    BlockEntity blockEntity = level.getBlockEntity(pos);\n"
        "    if (blockEntity instanceof ChestBlockEntity chest) {\n" % TARGET)}
    text = VANILLA.replace(
        "        this.prepare();\n        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {",
        "        BlockEntity blockEntity = level.getBlockEntity(pos);\n        this.prepare();\n"
        "        if (blockEntity instanceof ChestBlockEntity chest) {")
    fails("戻した局所変数が規則の位置に無い", text, rules, want="vanilla の行が変わった")


def test_tail_with_or():
    rules = {"expr/tail.rules": (
        "file: %s\nreplace:\n    return this.count > 0;\nwith:\n"
        "    return this.count > 0 && dev.shifu.event.ShifuEvents.canUse(this) || this.other();\n" % TARGET)}
    fails("式の末尾の発火の後ろに || を足す",
          VANILLA.replace("return this.count > 0;",
                          "return this.count > 0 && dev.shifu.event.ShifuEvents.canUse(this) || this.other();"),
          rules, want="式の末尾に発火を 1 つ足す形でも")


def test_mache_at_statement():
    ats = VANILLA.replace("this.count++;", "this.count += 2;")
    fails("Paper の AT のコミットに修飾子以外の変更がある(基点が AT の後ろだと見えなかった)", ats, ats=ats,
          want="Paper の AT")


def test_mache_at_constant():
    ats = VANILLA.replace("private static final int LIMIT = 4;", "public static int LIMIT = 4;")
    fails("Paper の AT が定数から final を外す", ats, ats=ats, want="定数から final")


def test_braceless_if():
    vanilla = VANILLA.replace("        this.count++;", "        if (level.isDay())\n            this.count++;")
    fails("括弧の無い if の本体の前に差し込む",
          insert(vanilla, "this.count++;", ["dev.shifu.event.ShifuEvents.counted(this);"]),
          vanilla=vanilla, want="括弧の無い")


def test_wrap_mismatch():
    # 差し込んだ { を vanilla の } が閉じる(for の本体の途中で囲みを開いたまま抜ける)
    text = insert(VANILLA, "entity.push();", ["if (dev.shifu.event.ShifuEvents.push(entity)) {"])
    text = after(text, "this.count++;", ["}"])
    fails("囲む形の { と } が vanilla の括弧をまたぐ", text, want="で閉じている")


def test_initializer_block():
    text = VANILLA.replace("    private int count;", "    private int count;\n\n    {\n        this.count = 2;\n    }")
    fails("型の本体に初期化ブロックを足す", text, want="初期化ブロック")


def test_else_runs():
    fails("else の中身は登録が無くても走る", insert(VANILLA, "this.prepare();", [
        "if (!dev.shifu.event.ShifuEvents.tick(this, pos)) {",
        "    return false;",
        "} else {",
        "    this.count = 0;",
        "}",
    ]), want="else の中身")


def test_wire_vanilla_statement():
    rules = {"wire/w.rules": ("file: %s\nanchor:\n    this.prepare();\ninsert-after:\n"
                              "    this.count = 2;\n" % TARGET)}
    fails("wire の中身が vanilla の状態だけを書き換える",
          after(VANILLA, "this.prepare();", ["this.count = 2;"]), rules, want="Bukkit 層にも")


def test_wire_elsewhere():
    rules = {"wire/w.rules": ("file: %s\nanchor:\n    this.prepare();\ninsert-after:\n"
                              "    this.count = 2;\n" % TARGET)}
    fails("wire の中身がアンカーから離れた位置にある",
          after(VANILLA, "entity.push();", ["this.count = 2;"]), rules, want="発火の形でない文")


def test_state_field_unlisted():
    fails("STATE_FIELDS に無い欄を読む if", member(insert(VANILLA, "this.prepare();", [
        "if (this.shifuOther) {",
        "    this.count = 0;",
        "}",
    ]), "shifuOther"), want="if (this . shifuOther) の中身")


def test_state_field_vanilla():
    vanilla = VANILLA.replace("    private int count;", "    private int count;\n    private boolean exact;")
    fails("STATE_FIELDS の名前でも vanilla が宣言した欄", insert(vanilla, "this.prepare();", [
        "if (this.exact) {",
        "    this.count = 0;",
        "}",
    ]), vanilla=vanilla, want="if (this . exact) の中身")


def test_state_field_call():
    fails("欄でなく呼び出しを見る if", member(insert(VANILLA, "this.prepare();", [
        "if (this.getBukkitEntity().hasClientWorldBorder()) {",
        "    this.count = 0;",
        "}",
    ]), "shifuClientWorldBorder"), want="発火でない項")


def test_state_field_negated():
    fails("STATE_FIELDS の欄の否定(既定の false で中身が走る)", member(insert(VANILLA, "this.prepare();", [
        "if (!this.shifuClientWorldBorder) {",
        "    this.count = 0;",
        "}",
    ]), "shifuClientWorldBorder"))


def test_state_field_wrap():
    text = insert(VANILLA, "this.count++;", ["if (this.fixedPose) {"])
    fails("STATE_FIELDS の欄で vanilla の文を囲む(既定の false で vanilla の文が飛ぶ)",
          member(after(text, "this.count++;", ["}"]), "fixedPose"), want="囲んでいる")


def test_access_without_rule():
    fails("規則の無い可視性の変更", VANILLA.replace("    private int count;", "    public int count;"),
          want="vanilla の行が変わった")


def test_narrow_static():
    rules = {"narrow/n.rules": ("file: %s\nreplace:\n    public boolean canUse() {\nwith:\n"
                                "    public static boolean canUse() {\n" % TARGET)}
    fails("patches/narrow でメソッドを static にする",
          VANILLA.replace("public boolean canUse() {", "public static boolean canUse() {"), rules,
          want="cast を足す形でも")


# ---------------------------------------------------------------- classic の AT(jar の flags)

def class_file(access, fields):
    """最小のクラスファイル。fields は (flags, 名前, 記述子, ConstantValue の有無)。"""
    pool = []

    def utf8(text):
        pool.append(b"\x01" + struct.pack(">H", len(text)) + text.encode())
        return len(pool)

    this_name = utf8("Sample")
    pool.append(b"\x07" + struct.pack(">H", this_name))
    this_class = len(pool)
    super_name = utf8("java/lang/Object")
    pool.append(b"\x07" + struct.pack(">H", super_name))
    super_class = len(pool)
    constant = utf8("ConstantValue")
    body = b""

    for flags, name, desc, has_constant in fields:
        body += struct.pack(">HHH", flags, utf8(name), utf8(desc))

        if has_constant:
            pool.append(b"\x03" + struct.pack(">i", 4))
            body += struct.pack(">HHIH", 1, constant, 2, len(pool))
        else:
            body += struct.pack(">H", 0)

    head = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(pool) + 1) + b"".join(pool)

    return head + struct.pack(">HHHH", access, this_class, super_class, 0) \
        + struct.pack(">H", len(fields)) + body + struct.pack(">HH", 0, 0)


def classic(before, after_, entry="net/minecraft/Sample.class"):
    root = tempfile.mkdtemp(prefix="shifu-test-verify-")

    try:
        cache = os.path.join(root, ".gradle", "caches", "paperweight", "taskCache")
        os.makedirs(cache)

        for name, data in (("fixJar.jar", before), ("applyMergedAt.jar", after_)):
            with zipfile.ZipFile(os.path.join(cache, name), "w") as jar:
                jar.writestr(entry, data)

        problems = []
        count = va.classic_ats(root, problems)

        return count, [why for _, _, why in problems]
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_classic_at_widen():
    count, problems = classic(class_file(0x21, [(0x2, "count", "I", False)]),
                             class_file(0x21, [(0x1, "count", "I", False)]))

    if count != 1 or problems:
        FAILURES.append("classic の AT: 広げるだけなら 1 か所で通る(%d, %r)" % (count, problems))


def test_classic_at_constant():
    _, problems = classic(class_file(0x21, [(0x1A, "LIMIT", "I", True)]),
                          class_file(0x21, [(0x09, "LIMIT", "I", True)]))

    if not any("定数" in why for why in problems):
        FAILURES.append("classic の AT: 定数から final を外すと落ちる(%r)" % problems)


def test_classic_at_narrow():
    _, problems = classic(class_file(0x21, [(0x10, "components", "I", False)]),
                          class_file(0x21, [(0x2, "components", "I", False)]))

    if not problems:
        FAILURES.append("classic の AT: 可視性を狭めると落ちる")


def test_classic_at_narrowing_allowed():
    desc = "Lnet/minecraft/core/component/PatchedDataComponentMap;"
    item = "net/minecraft/world/item/ItemStack.class"
    _, problems = classic(class_file(0x21, [(0x10, "components", desc, False)]),
                          class_file(0x21, [(0x2, "components", desc, False)]), item)

    if problems:
        FAILURES.append("classic の AT: 確かめた ItemStack.components は通る(%r)" % problems)

    # 同じ欄でも、確かめた形と違う狭め方は落ちる
    _, problems = classic(class_file(0x21, [(0x1, "components", desc, False)]),
                          class_file(0x21, [(0x2, "components", desc, False)]), item)

    if not problems:
        FAILURES.append("classic の AT: ItemStack.components でも確かめた形以外は落ちる")


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
