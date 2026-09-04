# 開発

Shifu のツリーを組み立てて、追加を書いて、確かめるまで。
設計は [ARCHITECTURE.md](ARCHITECTURE.md)、測った数字は [STATUS.md](STATUS.md)、
参加のしかたと守ることは [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 環境

前提は Windows + Git Bash (MSYS)、JDK 25、Python 3.12。
`tools/*.sh` は `cygpath` とセミコロン区切りのクラスパスを使う。

| | 既定 |
|---|---|
| リポジトリ | `tools/env.sh` が `$0` から求める |
| Paper のクローン | リポジトリの隣の `.pw`。`SHIFU_PAPER` で変えられる |
| vanilla の基点 | `tools/build/base-commit.txt`。無ければ `6d83d4b` "paper Imports" |
| `JAVA_HOME` | 環境のものを使う。JDK 25 未満なら止まる |
| NMS のソース | `$PW/paper-server/src/minecraft/java` |
| アダプタ層 | `$PW/paper-server/src/main/java` |
| サーバーの実行フォルダ | `$PW/run-shifu`(オフラインモード、port 25599) |

パスはどこにも直書きしない。シェルは `tools/env.sh` を読み、Python は
`tools/paths.py` を読む。新しいツールを足すときも同じにする。

自分の環境だけ変えたいときは `tools/env.local.sh`(git 管理外)に書く。
`env.sh` が `SHIFU` と `PW` を決めたあとに読むので、どちらも上書きできる。

```sh
# tools/env.local.sh
PW=/e/paper
```

`sh tools/setup.sh` が Paper のクローンと `applyAllPatches` を回し、
vanilla の基点のハッシュを `tools/build/base-commit.txt` に書き出す。
1 から作るときはこれ。Paper が更新されて基点が変わったら、`setup.sh` を回し直せば
ツールは新しい方を使う。

Paper のビルドはパスが長いと `applyResourcePatches` で失敗する。
データパックのファイル名が MAX_PATH(260)を超えるため。ドライブ直下から
2 段ほどの短いパスに置く。

`src/minecraft/java` と `src/minecraft/resources` はそれぞれ別の git リポジトリで、
`closure.sh` が前者を基点コミット、後者を `ba9af23` に戻す。
アダプタ層(`src/main/java`)は `git -C "$PW" checkout -- paper-server/src/main/java` で別に戻す。

## 回し方

```
sh tools/test-all.sh           # 生成器の単体テストとシェルの構文(数秒)。CI と同じもの
python tools/check_events.py   # 規則のアンカーが 1 箇所に定まるか(コンパイル無しで数秒)
sh tools/javac-event.sh        # 発火層だけを前回の jar に対して単体でコンパイル(1〜2 秒)
sh tools/once.sh               # ツリーに当ててコンパイル。追加を書くたびの確認はこちら
sh tools/closure.sh            # 不動点まで回して、shim と発火を当ててコンパイル
python tools/verify_additive.py  # vanilla の行に触っていないことを確かめる
sh tools/postcompile.sh        # コンパイル後・jar 前の後処理(番号合わせと公式バイトコードへの戻し)
sh tools/run-server.sh         # 組んで起動
```

`test-all.sh` は `tools/test_*.py` 5 本と、`tools/**/*.sh` の `sh -n` をまとめて回す。
Paper のクローンが要らないので、CI でも同じものが走る。

発火層(`src/event`)は Gradle にも登録してある。IDE で開くための登録が主で、
型チェックを走らせるときは明示する。

```
./gradlew :shifu-events:compileJava -PeventsCheck
```

見ているのは前回 `run-server.sh` で組んだ jar なので、そのあとに `patches/hand` で
足したメンバーはまだ無い。そのぶんはエラーに出る。速さが要るときは `javac-event.sh`。

ツールの出力(コンパイルエラーの全文、要求リスト、外した発火の理由、ビルドログ)は
`docs/backlog/` に書き出す。走らせるたびに上書きされるのでリポジトリには入っていない。
クローンした直後は要求リストが空なので、`once.sh` ではなく `closure.sh` から始める。

`closure.sh` は次の順で回る。

1. ツリーを基点コミット(パッチ適用前の vanilla)に戻す
2. `add_new_files.py` — Paper が丸ごと足すファイルを置く
3. `make_shim.py` — Paper のパッチから取れる宣言と import を足す
4. `widen_access.py` — `patches/access/*.rules` の可視性を広げる
5. `apply_shim_adds.py` — `patches/shim/**.add`(生成)と `patches/hand/**.add`(手書き)を足す。
   同じ名前があれば手書きを採り、`patches/access` で扱うものは足さない
6. `patch_adapter.py` — `patches/adapter/*.rules` でアダプタ層を直す
7. `apply_events.py` — `patches/anon/*.rules` を無名クラスへ差し込む
8. `apply_events.py` — `patches/wire/*.rules` の配線を差し込む
9. `apply_events.py` — `patches/events/*.rules` の発火を差し込む
10. コンパイルして、足りないものを要求リストに足して 1 に戻る

エラーが増えた回は要求リストを 1 つ前に戻して止まる。

要求は 1 回では閉じない。足したメンバー自身が別の追加メンバーを参照するので、
不動点まで回す。

javac は既定でエラー 100 件で打ち切る。`tools/maxerrs.gradle` で外してある。
上げないと「100 件で止まったまま」を「100 件しかない」と読み違える。

## 当たった結果を読む

`patches/` は当てるための入力で、読む対象ではない。当たったツリーがそのまま
git リポジトリになっているので、差分を読むならそちら。

**NMS** — `HEAD` は vanilla の基点コミット。作業ツリーが当たった状態。

```
cd "$PW/paper-server/src/minecraft/java"
git diff --shortstat            # 558 files changed, 10173 insertions(+), 82 deletions(-)
git diff net/minecraft/world/entity/Entity.java
git diff --stat | sort -k3 -rn | head -20      # 変更の大きい順
git show "$SHIFU_BASE:net/minecraft/world/entity/Entity.java"   # 素の vanilla
```

**アダプタ層** — `.pw` 本体のリポジトリの一部。

```
cd "$PW"
git diff -- paper-server/src/main/java         # 40 files changed, 215 insertions(+), 121 deletions(-)
```

`dev/shifu/**` は `src/event` からコピーされるので未追跡で出る。

消えた 82 行の中身は `python tools/verify_additive.py` が分類する。
可視性・interface・逆コンパイルで消えた局所変数・式の末尾の発火のどれかで、
それ以外の削除や書き換えが 1 行でもあれば落ちる。

**ここで直接編集したものは次の `once.sh` / `closure.sh` で消える。**
どちらも最初に `git reset --hard` でツリーを vanilla に戻す。直すなら `patches/` の側に書く。

## 変えてよい vanilla の行

Paper が vanilla の行そのものを書き換えている箇所のうち、コンパイル時の性質だけが
違うものが 3 種類ある。同じ名前で同じ引数のオーバーロードは足せないので、追加では届かない。
3 つとも実行される命令列は変わらない。

| 種類 | 扱い | 例 |
|---|---|---|
| 可視性を広げる | 許す(2026-09-01 に決定) | `ServerCommonPacketListenerImpl.connection` を protected から public へ |
| 返り値を void から値へ | 許さない | `ServerPlayer.nextContainerCounter()` が int を返す(17 件) |
| インスタンス定数を static へ | 許さない | `AbstractMountInventoryMenu.SLOT_SADDLE`(20 件) |
| 宣言に interface を足す | 一度許したが取り下げた。アダプタ層で包む | `CommandSourceStack implements PaperCommandSourceStack` |

interface を足す形は 2026-09-03 に一度許可して入れたが、同じ日に取り下げた。
`fabric-permission-api-v1` の mixin が `@ModifyReturnValue` の対象を
`/^with/ desc=/CommandSourceStack;$/` で選び、Paper API の `withExecutor`
(戻り値が API の型)まで拾って `InvalidInjectionException` でサーバーが起動しなくなる。
代わりにアダプタ層で包む形にした([ARCHITECTURE.md](ARCHITECTURE.md) の
「Paper の brigadier API」)。`patches/access/implements.rules` は無い。

`tools/widen_access.py` と `tools/verify_additive.py` は `implements:` を今も読める。
使っている規則は無い。

許さない 2 つは、アダプタ層の側で吸収する。アダプタ層は vanilla ではないので、
ここを書き換えても、プラグインが何もしないときに走る vanilla の命令列は変わらない。

## 追加の書き方

直し方は 5 つ。

| | 書く先 | 何をする |
|---|---|---|
| hand | `patches/hand/<パス>.add` | NMS の型の本体の末尾に宣言を足す |
| anon | `patches/anon/*.rules` | vanilla の中の無名クラスの本体に差し込む |
| wire | `patches/wire/*.rules` | Paper が構築子や起動時に行う代入を差し込む |
| access | `patches/access/*.rules` | 宣言の可視性だけを広げる |
| adapter | `patches/adapter/*.rules` | アダプタ層の行を書き換える |

どれもアンカー(その位置の行そのもの)で場所を指す。
アンカーが見つからない、または 2 つ以上あるときは失敗する。

### hand

書く先は `patches/hand/<パス>.add`。`patches/shim/` は `write_members.py` が
上書きするので、手で書いたものを置くと消える。

```java
// <所有クラス>.<名前>
    public void discard(final org.bukkit.event.entity.EntityRemoveEvent.Cause cause) {
        this.discard();
    }
```

`// クラス.名前` の目印で置き場所が決まり、`tools/apply_shim_adds.py` が
その型の本体の末尾に入れる。元の行は触らない。

同じ目印を 2 回書くと後の方だけが残るので、同じ名前のオーバーロードは
1 つの目印の下にまとめて書く。shim にある名前を hand に書くと shim の側は丸ごと消える
(`internalTeleport` の 3 つのオーバーロードで踏んだ)。

hand は生成器より優先される。同じ (ファイル, メンバー名) を Paper のパッチが持っていても、
`patches/hand` に書けばそちらが採られる。だから Paper がインターフェースに抽象で
足しているメソッドを `default` に書き換えて足せる。抽象のまま入れると
vanilla の中の無名クラスが軒並み落ちるので、これが要る。

書くものは 3 通り。

**引数が増えたメソッド** — 元の版へ渡すだけの追加を書く。増えた引数が何のためのもので、
なぜ渡さないのかをコメントに残す。理由が発火のためのもの(`SpawnReason` など)で、
発火層が読む欄があるなら、欄に置いてから渡す(`ServerLevel.addFreshEntity(entity, reason)`)。

**Paper が vanilla の本体を切り出した新しいメソッド** — vanilla の本体を読んで、
状態を変えない形で同じ式を書く。`LivingEntity.getExpReward` は vanilla の
`dropExperience` と同じ条件を読むだけで、経験値の生成はしない。

**フィールド** — Paper のパッチから型を読んで宣言だけ写す。本体は無い。

vanilla の行を書き換える形になったら、そこで止めて相談する。

進め方:

1. `python tools/worklist.py <vanilla-gap の出力>` で残りを見る
2. 直す対象を選び、コンパイルエラーの全文で呼び出し側が期待する形を読む
3. Paper のパッチ `$PW/paper-server/patches/sources/<パス>.patch` で宣言を確認する
   (推測で書かない)
4. vanilla 側の宣言も読む(`git -C "$TREE" show "$SHIFU_BASE:<パス>"`)。渡し先の引数と返り値を合わせる
5. `patches/hand/<パス>.add` に足す
6. 10 件ほど溜まったら `sh tools/once.sh` で数を見る

### anon

無名クラスは型の名前が無いので hand では届かない。イベント発火と同じアンカー方式で
本体に差し込む。

### access

変えるのは修飾子 1 語だけで、型も名前も引数も本体も触らない。
行そのものをアンカーにするので、Minecraft の更新で宣言が変わったら
`tools/widen_access.py` が失敗する。

```
# CraftPlayer と Paper の接続ラッパーが接続に直に触る
file: net/minecraft/server/network/ServerCommonPacketListenerImpl.java
line:
    protected final Connection connection;
```

### adapter

可視性と同じくアンカーで指定し、見つからない・数が合わないときは失敗する。
`replace` が 1 行のときは部分一致で、`with` も 1 行しか使われない。
複数行に差し替えるときは `replace` も 2 行以上にする。

```
# 鞍の位置は vanilla ではインスタンスの定数
file: org/bukkit/craftbukkit/inventory/CraftInventorySaddledMount.java
count: 8
replace:
    AbstractMountInventoryMenu.SLOT_SADDLE
with:
    net.minecraft.world.inventory.AbstractMountInventoryMenu.SHIFU_SLOT_SADDLE
```

NMS 側に別名の追加を足して、アダプタ層をそちらに向ける形が多い
(`patches/hand` と `patches/adapter` の 2 件で 1 組)。

## 発火の作り方

Paper のパッチ(`paper-server/patches/sources/**.java.patch`)は統一 diff で、
「足す文」と「その前後にある vanilla の行」が両方読める。後ろの vanilla の行を
そのままアンカーにすれば `patches/events` の規則になる。

```
python tools/scan_events.py <patches/sources>               # 発火位置の一覧
python tools/make_events.py <patches/sources> <木> <出力先>  # 規則を出す
python tools/make_block_events.py ...                       # setBlock を置き換えた発火
sh tools/settle_events.sh                                   # 通らないものを外して不動点まで
```

Paper の発火 824 箇所のうち、足すだけの形が 492、vanilla の行が消えている形が 332。
後者は大半を手で入れる。出せなかったものと理由は `make_events.py` が数え上げ、
コンパイルが通らなかった規則は `settle_events.sh` がコンパイラのエラーごと外して残す。
黙って減らさない。

手で入れるときの形は 3 つ。vanilla の行は変えない。

```java
// 途中で抜ける形
if (!dev.shifu.event.ShifuEvents.playerMove(this, startX, ...)) {
    return;
}

// vanilla の行を囲む形
if (dev.shifu.event.ShifuEvents.silentChat()
        || dev.shifu.event.ShifuEvents.playerChat(this.server, this.player, message)) {
this.server.getPlayerList().broadcastChatMessage(message, this.player, ChatType.bind(ChatType.CHAT, this.player));
}

// 式の末尾に足す形(patches/expr)
… && dev.shifu.event.ShifuEvents.turtleGoHome(…)
```

囲む形では、引数の式が必ず評価されてしまう。告知の文を組み立てるような重い式のときは
`silentChat()` を先に見て短絡させ、登録が無いときは vanilla と同じ命令列にする。

式の末尾に足す形は、Paper が式の途中で発火しているところに使う。足すのは常に式の最後で、
vanilla の判定が全て終わったあとにしか呼ばれない。登録が無ければ true を返す。

`final` な引数(`TeleportTransition transition`)を差し替えたいときは、
作り直したものでそのメソッドを呼び直す(`return this.teleport(shifuTransition);`)。
呼び直しの中では発火しない印を付ける。

取り消しが結果の状態を必要とするイベント(ブロック設置、移動、同じ世界の中のテレポート、
死亡)は、vanilla の処理を先に済ませてから発火し、取り消されたときだけ戻す。

理由(`SpawnReason` など)を発火層へ渡すときは欄に置く。呼ぶ側で 1 つの文が何度も呼ぶところ
(襲撃者の for、効果付きの矢)は、文の前で置いて文のあとで外す(`〜UntilDone` / `〜Done`)。
1 回で消える版だと最初の 1 体にしか付かない。

`apply_events.py` の `count:` は、同じ行が複数あって全部に差し込むときに使う
(TNT のレッドストーン判定が `onPlace` と `neighborChanged` に 1 行ずつある)。

## 差し込みで守ること

MOD の mixin は再コンパイルしたバイトコードを見る。差し込みが増やしたものは
mixin の狙いを外す。

**公式にもある型の局所変数を作らない。** MixinExtras の `@Local`(型だけで指す書き方)は
「その型がちょうど 1 つ」を要求する。公式の `ItemEntity.playerTouch` は int が
`orgCount` の 1 つだけで、Shifu が拾い上げイベントのために `canHold` と `remaining` を
足して 3 つになり、carpet-tis-addition が
`Found 3 candidate variables but exactly 1 is required` で起動しなかった。
値は欄(field)か発火層のメソッドの中に置く。

バイトコードでは隠せない。2 つ試して、どちらも効かなかった。

* frame から落とす(TOP を書く)— mixin の `Locals.java` は
  `if (localType == Opcodes.TOP)` で「Explicit TOP entries are pretty much always bogus」
  として読み飛ばす
* LVT の範囲を詰める — mixin は frame と STORE 命令を追って組み立て、LVT は名前を
  付けるのに使うだけ。一度 store された slot は CHOP frame まで残る。CHOP は末尾の slot
  しか落とせないので、差し込みの変数が公式の変数より若い番号だと消せない

守れていない場所は `python tools/check_extra_locals.py <クラスの置き場> <Mojang の jar> --list` で出る。

**ラムダを増やさない。** javac のラムダ名は `lambda$<メソッド>$<番号>` で、番号は
そのメソッドの中でソースに出てくる順。差し込みがラムダを 1 つ足すと、後ろにある
vanilla のラムダが繰り下がる。styled-chat は `PlayerAdvancements.lambda$award$0` を
名前で狙うので当たらなかった。`PlayerAdvancements` は if 文に書き換え、`ItemUtils` は
分岐の順を入れ替えて直した。確認は `python tools/check_lambdas.py <クラスの置き場> <Mojang の jar>`。

**局所変数の番号は後処理で公式に合わせる。** Mojang の jar は ProGuard を通っているので、
ソースをどう書いても同じ番号は出ない。`tools/lvtmatch` が 2 段で置き直す。

| | |
|---|---|
| 1 段目 `--slots` | slot ごと入れ替える。表に出ない一時変数も一緒に動くので衝突しない |
| 2 段目 `--vars` | 残りを変数ごとに置き直す。1 つの slot を寿命の違う変数が使い回していて行き先が食い違うものは、slot ごとでは直せない |

2 段目は、命令(xLOAD / xSTORE / IINC)を LVT の項目に結び付けてから動かす。
どの項目にも結び付かない使い方(javac の一時変数)は、その使用位置の範囲を塞いでいるものとして扱う。
互いの番号へ移りたい 2 つの変数は片方ずつでは動けないので、
いったん空いている高い番号へ寄せてから置く。書き換えたメソッドは BasicVerifier に通し、
通らなければ元に戻す。

`LambdaMatch` はラムダの形も合わせる。javac は `x -> this.foo(x)` の本体を instance の
合成メソッドにするが、Mojang の jar は static で受け手を第 1 引数に取る形になっている。
slot 0 の中身は同じなので本体の命令列は変えず、ACC_STATIC と署名、それを指す
invokedynamic の method handle だけを直す。参照を 1 つでも取りこぼすと
`NoSuchMethodError` になるので、直したあとに古い署名を指す参照が残っていないかを確かめる。

**逆コンパイラが消した局所変数は戻す**(`patches/decompile`)。Mojang の元のソースは
`BlockEntity blockEntity = level.getBlockEntity(pos);` と受けてから `instanceof` で見るが、
逆コンパイラはこれを 1 行にまとめて変数を消す。拡張 for も同じ。Ledger は
`CampfireBlock.useItemOn` の、Architectury API は `ServerExplosion.hurtEntities` の
この変数を `@Local` で捕まえるので、`Found 0 candidate variables` で起動しなかった。
規則は `python tools/make_decompile_rules.py <クラスの置き場> <Mojang の jar>` が作り直す。

**触っていないクラスは公式のバイトコードをそのまま置く**(`tools/keep_vanilla_classes.py`)。
差し替えないのは Shifu が手を入れたファイルと、Paper の AT が可視性を広げたファイル。
`run-server.sh` と `dist.sh` にコンパイル → 差し替え → jar の順で入れてある。

## 確かめ方

```
sh tools/player-events.sh     # bot を繋いでプレイヤー経路を通す
sh tools/real-plugins.sh      # 実際のプラグインを入れて bot にコマンドを叩かせる
sh tools/mods-and-plugins.sh  # MOD とプラグインを同時に入れて bot を繋ぐ
sh tools/compare-worldgen.sh  # vanilla を 2 回、Shifu を 1 回走らせて世界生成を突き合わせる
sh tools/compare-ticks.sh 1200  # 測定用 agent を付けて、N tick での世界を突き合わせる
sh tools/dist.sh              # 配布物を組んで、まっさらな場所で動かす
```

`player-events.sh` は検証プラグインと bot を組み、サーバーを起動して bot を繋ぎ、
プラグインが chat で bot に指示を出す。届いたイベントを `[probe]` の行に残す。
出力は `run-shifu/player-events.log`(サーバー)と `run-shifu/bot.log`(bot)。

bot(`tools/bot/src/dev/shifu/bot/Bot.java`)はサーバー jar のクラスだけで作ってある。
listener は動的 Proxy、受信の復号はレジストリが要る packet を捨てる codec で包む。

世界生成の比較は、同じシードで vanilla を 2 回・Shifu を 1 回走らせる。
同じものを 2 回走らせても違いは出る(起動から停止までのあいだ原点まわりが tick され、
乱数の入る処理がそのぶん進む)ので、「違いが 0 か」ではなく「同じもの同士のばらつきより
小さいか」で見る。3 つめの vanilla でばらつきを差し引くのが要点。

`compare-ticks.sh` は `tools/tickstop` の測定用 agent を両方に付ける。
agent は `RandomSupport.generateUniqueSeed()` を時刻を混ぜない決定的な列に置き換え、
`MinecraftServer.tickServer` を数えて N tick 目で `halt` する。

プラグインが動かないときは、本家 Paper を 1 回落として同じ jar を入れ、並べて見る。

```
curl -s https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest \
  | python -c "import sys,json;print(json.load(sys.stdin)['downloads']['server:default']['url'])"
```

MOD が動かないときは、素の Fabric サーバー(`$PW/run-vanilla-fabric`)で
同じことが起きるかを見る。そこで動くなら Shifu 側の問題。

プラグインが呼ぶ NMS のメンバーのうちサーバーに無いものは、動かす前に数えられる。

```
python tools/missing_members.py <サーバーの jar> <プラグインの jar>
```

class ファイルの定数プールを読んで継承を辿って照合する。`net.minecraft.*` だけでなく
Paper と Bukkit も見る。

## 手で動かす

`sh tools/dist.sh --build` が `tools/build/dist/` に `shifu.jar`(起動側)と
`shifu-server.jar`(paperclip 形式)を組む。この 2 つを空のフォルダへ置いて
`java -jar shifu.jar nogui` を走らせると、初回に Mojang の server.jar を取ってきて
Shifu のサーバーを組み立てる(数分)。`Done (...)` が出たら 127.0.0.1:25565 で入れる。

MOD は `mods/`、プラグインは `plugins/` に置く。サーバーの Java は起動に使った Java と
同じものになる(Java 25 が要る)。組み立ての設定は初回に出る `shifu.properties`。

```
minecraft-version   = 26.2
paper-build         = latest
server-paperclip    = shifu-server.jar   # パスか URL
fabric-loader-version = 0.19.3
vanilla-parity      = true
jvm-args            = -Xmx4G
```

初回に `versions/` `libraries/` `cache/` `.shifu/` `world/` `logs/` ができる。
組み直した jar で試すときは前の 4 つを消してから起動する。残っていると組み立て直さず、
古いサーバーがそのまま動く。

`eula.txt` は自分で書く(Minecraft の EULA に同意したことになる)。
`server.properties` を `online-mode=false` にしたまま外へ開けると、
誰でも名前を名乗って入れる。

## 道具

| ファイル | 役割 |
|---|---|
| `tools/setup.sh` | Paper のクローンと逆コンパイル。vanilla の基点のハッシュを確かめる |
| `tools/closure.sh` | 全部当ててコンパイル。不動点まで繰り返す |
| `tools/once.sh` | closure.sh の 1 回分。追加を書くたびの確認用 |
| `tools/postcompile.sh` | コンパイル後・jar 前の後処理。番号合わせと公式バイトコードへの戻し |
| `tools/run-server.sh` | 組んで起動する。ログは同期にして走らせる |
| `tools/dist.sh` | 配布物を組んで、まっさらな場所で動かす |
| `tools/maxerrs.gradle` | javac のエラー打ち切りを外す |
| `tools/add_new_files.py` | Paper が丸ごと足すファイルを置く |
| `tools/make_shim.py` | Paper のパッチから宣言と import を抜いて足す |
| `tools/write_members.py` | 取れる宣言を `patches/shim/**.add` に書き出す。上書きする |
| `tools/apply_shim_adds.py` | 自前で書いた宣言を足す |
| `tools/required_members.py` | コンパイルエラーから要求を列挙する |
| `tools/why_missing.py` | 取れない理由を分類する |
| `tools/worklist.py` | 残りのエラーを種類ごとにまとめる |
| `tools/widen_access.py` | 可視性だけを広げる。修飾子 1 語しか触らない |
| `tools/patch_adapter.py` | アダプタ層の行を書き換える。vanilla ではない |
| `tools/filter_sources.py` | Java の構造解析。上のほとんどが使う |
| `tools/scan_events.py` | Paper のパッチから発火位置を数え上げる |
| `tools/make_events.py` | 発火の規則を機械で出す(足すだけの塊) |
| `tools/make_block_events.py` | setBlock を置き換えた発火(ブロック変化 5 種)の規則を出す |
| `tools/classify_replace.py` | vanilla の行が消えている塊を、直せる形かで分ける |
| `tools/show_replace.py` | Paper のハンクと、対応する vanilla のメソッド全体を並べて出す |
| `tools/drop_bad_events.py` | 通らない規則を、コンパイラの言い分ごと外す |
| `tools/settle_events.sh` | 上の 2 つを不動点まで回す |
| `tools/check_events.py` | 規則のアンカーが 1 箇所に定まるか。hand と shim を足した状態でも見る |
| `tools/apply_events.py` | アンカーで差し込む。発火・配線・無名クラスの 3 つに使う |
| `tools/make_wires.py` | 誰も代入しない欄への配線を Paper のパッチから出す |
| `tools/find_constant_returns.py` | vanilla が定数を返すところを Paper が欄の読み出しに変えている箇所を探す |
| `tools/javac-event.sh` | 発火層だけを前回の jar に対して単体でコンパイルする |
| `tools/verify_additive.py` | vanilla の行に触っていないことを機械で確かめる |
| `tools/verify_fabric_rules.py` | Fabric の互換規則の前提を jar に当てて確かめる |
| `tools/lvtmatch` | 局所変数の番号とラムダの署名を公式に合わせる |
| `tools/compare_lvt.py` | 局所変数の並びが公式と違うメソッドを出す |
| `tools/check_lambdas.py` | ラムダの番号が公式とずれたクラスを出す |
| `tools/check_extra_locals.py` | 差し込みが公式にもある型の局所変数を作っている場所を出す |
| `tools/make_decompile_rules.py` | 逆コンパイルで消えた局所変数を戻す規則を作り直す |
| `tools/keep_vanilla_classes.py` | 触っていないクラスを公式のバイトコードに差し替える |
| `tools/missing_members.py` | プラグインが呼ぶメンバーのうち、サーバーに無いものを出す |
| `tools/compare_worlds.py` | 同じシードで作った世界を突き合わせる |
| `tools/compare-worldgen.sh` | vanilla を 2 回、Shifu を 1 回走らせて世界生成を突き合わせる |
| `tools/tickstop` + `tools/compare-ticks.sh` | 測定用 agent。乱数を決定的にして N tick で止め、世界を突き合わせる |
| `tools/exittrace` | JVM が静かに終わるときに全スレッドのスタックを出す agent |
| `tools/player-events.sh` | 検証プラグインと bot を組み、サーバーを起動して bot を繋ぐ |
| `tools/real-plugins.sh` | 実際のプラグインを入れ、bot にコマンドを叩かせる |
| `tools/mods-and-plugins.sh` | MOD とプラグインを同時に入れて bot を繋ぐ |
| `tools/fetch_addons.py` | Modrinth から MOD とプラグインを取ってくる |
| `tools/bot/` | ヘッドレスのクライアント。サーバー jar のクラスだけで動く |
| `tools/probe/` | プレイヤー経路の検証プラグイン。chat で bot に指示を出す |
| `tools/plugin-drive/` | 実プラグインの検証プラグイン。結果を `[drive]` の行に残す |
| `tools/log4j2-sync.xml` | 同期の log4j 設定。非同期だと落ちる直前の例外が消える |

## 踏んだ落とし穴

### 起動と実行

Paper の log4j 設定は非同期の appender を通す。落ちる直前のスタックトレースが
出力されないまま JVM が終わり、`exit 0` で何も出ない状態になる。
`tools/log4j2-sync.xml` に差し替えて走らせる(`run-server.sh` は既定でそうする)。
配布物のほうは `jvm-args` に `-Dlog4j.configurationFile=file:///C:/.../sync-log4j2.xml`
を渡す。パスは `file:` の URL で書く(`C:/...` のままだと "unknown protocol: c" になる)。

起動側は組み立て済みなら組み立てを飛ばす。`versions/` `libraries/` `cache/` があると
`Paper 26.2 is already assembled` で終わる。新しく組んだサーバーで測るときは、
その 3 つと `.shifu/` を消してから走らせる(古い jar で測って 40 分溶かした)。

`versions/<ver>/paper-<ver>.jar` の名前は版が変わっても同じなので、日付や大きさでは
組み立て済みかを判断できない。何から作ったかを `paper-<ver>.jar.from` に SHA-256 で
残して突き合わせる。直したのに効かないときは、ビルド成果物ではなく起動時に実際に
読まれるファイルを開く。

`createBundlerJar` は動いているサーバーが jar を掴んでいると差し替わらない。
先に `rm` して(掴まれていたら失敗するので気付ける)から組む。
`tasklist` で `paper-bundler` を持つ java を探して止める。

`tools/player-events.sh` はサーバーの jar を組まない。組むのは `tools/run-server.sh`
(`createBundlerJar`)。`once.sh` でコンパイルしただけで bot を繋ぐと、前の jar で走って
新しい発火が 1 つも届かない。bundler jar の時刻を見る。
bundler の中の jar は起動時に `run-shifu/versions/26.2/` へ展開し直される。

コンソールに `stop` を即座に流し込むと、ワールドを作る前に読まれる(vanilla も同じ順序)。
コマンドを流すときは `Done (...)` を待つ。

同じツリーで 2 つのビルドを同時に走らせない。数を取るときは 1 本だけ走らせる。

Java agent の ASM は Java 25 に対応した版が要る。ASM 9.7.1 は class file 69 を読めず
`Unsupported class file major version 69` になる(9.9.1 なら通る)。
`ClassFileTransformer` の中で出た例外は JVM が黙って捨てるので、自分で catch して出さないと
「変換されないまま動く」ように見える。agent のクラスは `Boot-Class-Path` にも載せる。
載せないと、差し込んだ呼び出しがサーバーのクラスローダから見つけられず
`NoClassDefFoundError` になる(Paper の bundler は system loader へ委譲しない)。

`tools/player-events.sh` は `tools/build` の中の自分の出力だけ消す。以前は
ディレクトリごと消していて、測定用 agent の jar まで消えていた
(次の `compare-ticks.sh` が `Error opening zip file or JAR manifest missing` で落ちる)。

### プラグインと MOD

コンパイルが通っても、プレイヤーを繋がないと出ないものがある。
`Entity.valid`(`Player.teleport` が false)、`LevelWriter.addFreshEntity(entity, reason)`
の既定(`World.spawn` が何もしない)、`cserver`(最初の packet で NPE)、
`CommandSourceStack` の checkcast(参加できない)。どれも例外が出ないか、出ても参加の途中。
`tools/player-events.sh` を回す。

`World.spawn` の失敗は黙る。返ってきたエンティティの `isValid()` が false で、
数 tick 後に `getEntitiesByClass` に居なければ入っていない。

Bukkit のコマンドには入口が 2 つある。`plugin.yml` の `commands:` は `CommandMap` を通り、
実行時に Brigadier へ登録するものは `LifecycleEvents.COMMANDS` の発火だけが入口。
cloud-paper の `PaperCommandManager` と Paper 自身の `JavaPlugin#registerCommand` は後者。
サーバーが行事を出さないとハンドラは呼ばれないまま終わり、登録の失敗ではないのでログも出ない。
同じ形の入口が registry と tag にもある(`LifecycleEvents.TAGS`、registry の add と compose)。

プラグインの `java.util.logging` は、根の JUL ロガーの handler を差し替えないと
標準エラーに出る。ランチャ越しに走らせると `launch.out` ではなく `launch.err` に入る。
`ForwardLogHandler` は `LogRecord.getLoggerName()` で log4j のロガーを引き直すので
`[プラグイン名]` が付く。JUL の既定書式の 1 行目は発生元のクラスであってロガー名ではない。

設定フェーズは netty のスレッド。`startConfiguration` や設定フェーズの packet 処理で
同期の Bukkit イベントを発火すると `may only be triggered synchronously` で接続が切れる
(`PlayerLinksSendEvent` で踏んだ)。`PlayerEvents.onServerThread` で待ち行列へ回す。

Fabric は MOD の jar を `loader.load()` の中でクラスパスへ足す
(fabric-loader@0.19.3 `FabricLoaderImpl.java:365-378`)。これは `unlockClassPath` より前
(`Knot.java:141-152`)なので、MOD 側の写しが先に見つかる。素の Fabric では game jar に
`net.minecraft.*` しか無いので起きないが、Shifu の game jar には Paper と Bukkit も入っている。
VeryManyPlayers が同梱する `io/papermc/paper/util/MCUtil` で CraftServer の起動が
`NoSuchMethodError` で落ちた。`ShifuGameProvider.initialize` の時点で `org.bukkit.`
`io.papermc.` `com.destroystokyo.` `org.spigotmc.` だけを先に出し、`net.minecraft.*` は
`unlockClassPath` まで出さない。

内容を足す MOD を入れると、素のクライアントは Fabric のレジストリ同期で弾かれる
(「This server requires Fabric Loader and Fabric API installed on your client!」)。

ログには色の制御文字が入るので、`grep` は `-a` を付けないと「バイナリ」と見て何も出さない。

### 生成器

判定を 2 つ以上まとめて変えない。コンパイルエラーの数だけを見て調整すると、
宣言の検出・切り出し・置き場所・重複の 4 つが干渉して原因が特定できなくなる。
`tools/test_make_shim.py` を先に通してから全体を回す。

クラス宣言が複数行にまたがるファイルがある。行単位で探す実装だと何も足せない。

入れ子の型が始まる行に足すと、その型の中に入る。本体を含むかどうかは
`開始 < 行 <= 終了` で見る。

Paper のパッチには `--- /dev/null` で始まるものがある(`tools/add_new_files.py` が別に置く)。

足した宣言は Paper の import に依存する。`make_shim.py` が同じパッチの `+import` 行も足す。

型に注釈が挟まる形がある。`org.bukkit.inventory.@Nullable InventoryHolder getOwner();`
は型を 1 語として書いた正規表現では宣言として見えない。

Paper は状態と読み出しに同じ名前を使う。既出は名前ではなく宣言の行そのもので数える。

重複判定に使う「宣言の行」から注釈を外す。`@Override` で始まる塊が全部落ちていたことがある。

可視性を広げた要素は、生成した shim から足させない。`apply_shim_adds.py` と
`make_shim.py` に `patches/access` を渡して外している。

vanilla のメソッドの特定はハンクの行番号で行う。同じ行がファイルに 2 つあると、
文字列の検索ではファイル全体に落ちて Paper の引数を見逃す。

別名をハンクを跨いで解決すると間違った値で出す。`MushroomBlock` の
`final BlockPos sourcePos = pos;` は「`pos` を書き換える前の控え」で、
ハンク単位で諦めるのが正しい。

`make_events.py` の `UNWIRED` は、Paper の挙動変更が設定を読む行(`Entity.push` の
`collisions` など)を規則から外すためのもの。設定が入ったあとも外したまま。

### 環境まわり

MSYS の bash は fork に失敗することがある(`dofork: child -1 - forked process died`)。
長いコマンドの連結や、Python から `git show` を呼ぶ生成器が途中で死ぬ。
生成器には 3 回の再試行を入れてある。連結が止まったら、残りを手で流す。

Python の非 raw 文字列に `\b` を書くとバックスペース (0x08) になる。
正規表現を書き換えたら `cat -A` で確認する。

PowerShell の `Set-Content -Encoding utf8` は BOM を付ける。
`[System.IO.File]::WriteAllText` を使う。

### 26.2 の仕様

ゲームルールの id は小文字の下線区切り(`block_drops`、`random_tick_speed`)。
コマンドは名前空間付き(`minecraft:block_drops`)でも通るが、`minecraft:do_fire_tick` の
ように 26.1 までの名前を名前空間に付けても通らない。id の一覧は
`net/minecraft/world/level/gamerules/GameRules.java`。

`/gamerule` はルール名の形が変わっている。`gamerule randomTickSpeed 4096` は
「Incorrect argument」で通らない。プラグインからは
`World.setGameRule(GameRule.RANDOM_TICK_SPEED, ...)` を使う。

moonrise の `TickThread` は Shifu では常に false を返す。`patches/adapter` で
`isTickThread()` に `isSameThread()` を足して直してある。

Paper のアダプタ層は Paper の設定が立ち上がっている前提で書いてある。
`patches/wire` で立ち上げる。

WorldEdit は `org.spigotmc.WatchdogThread` があると、長い操作の途中で `tick()` を呼ぶ
(`PaperweightAdapter$SpigotWatchdog`)。thread を起動していないと `instance` が null になり、
`//set` が 27 ブロック中 20 で止まる。Paper と同じ位置に doStart / tick / hasStarted / doStop を配線してある。

`stop` のあと JVM が残るのは Bukkit のスケジューラのスレッド。vanilla のサーバースレッドは
終わっているのに `Craft Async Scheduler Management Thread`(非デーモン)が JVM を生かす。
`jstack` で非デーモンのスレッドを見れば分かる。サーバースレッドから `System.exit` すると
vanilla の shutdown hook(`halt(true)`)と待ち合いになるので、Paper の位置にそのまま
`System.exit` を置くと止まらない。サーバースレッドが終わるのを別スレッドで待ってから exit する。
