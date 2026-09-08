# Shifu の構成

Shifu は Fabric の MOD と Bukkit / Paper のプラグインを同時に動かすサーバーソフトウェアである。
条件は 2 つで、どちらも譲れない。

1. 挙動が vanilla と完全に一致する(表面上ではなく、処理順とアルゴリズムまで)
2. プラグインが動く

## 先行実装と、取らなかった案

Cardboard(Mixin 246 個)と Taiyitist(Mixin 567 個)は、Paper のサーバー実装を手で移植し、
CraftBukkit の nms-patches を自作 Mixin で再現している。移植である以上、本家が変わるたびに
ズレが増える。両者ともそのズレがプラグインごとのバグとして出ている。

Paper をそのまま土台にする案は測定して捨てた。Paper のパッチには vanilla との挙動差が
混ざっており、イベント発火だけを取り出そうとしても本体の書き換えが付いてくる。
`patches/sources` をメソッド単位で選別しても、採用した 2530 のうち 733 が
「イベントでもシグネチャでもない書き換え」として残った。経緯と数字は
[FOUNDATION-OPTIONS.md](FOUNDATION-OPTIONS.md)。

Shifu は逆コンパイルした vanilla の NMS をそのまま置き、Paper のアダプタ層
(`org.bukkit.craftbukkit.*`)をその上に載せる。アダプタ層が要求するメンバーは
宣言だけを足し、イベントの発火は自前で差し込む。移植しないので、本家とのズレが
そもそも発生しない。

26.1 で Mojang が難読化を廃止し、Fabric が Intermediary の更新を止めたので、
26.1 以降は Fabric MOD と Paper プラグインが同じ名前空間(mojmap)を見る。
リマッパ層が要らない。

それより前でも作り自体は変わらない。Paper の開発ツリーは難読化バージョンでも
mojang マッピングなので、NMS もアダプタ層も mojmap で揃う。ズレるのは MOD だけで、
intermediary から mojmap への変換は起動側が用意する(`dev.shifu.launcher.Namespace`)。
要るのは 3 つ。intermediary と Mojang の `server.txt` を合成したマッピング、
fabric-loader の名前空間のシステムプロパティ、そして入力側の名前空間にした
リマップ用クラスパス。1.20.6 で MOD 70・プラグイン 24 を同時に起動できている。
1.20.5 より前は Paper 自身が spigot 名前空間で動くので、
プラグイン側の変換も抱えることになる。

バージョンごとの実際の費用は名前空間ではなく、差し込みのアンカーの付け直しになる。
版ごとのブランチの分け方は [DEVELOPING.md](DEVELOPING.md)。

## 層

| 層 | 中身 | 出どころ | 挙動への影響 |
|---|---|---|---|
| NMS | 逆コンパイルした vanilla | Mojang | 無し(定義上 vanilla) |
| shim | アダプタ層が要求するメンバーの追加 | 生成 | 無し(既存行を触らない) |
| wire | Paper が構築子や起動時に行う代入・呼び出し | 生成 + 手書き | Bukkit 層の起動だけ |
| event | イベント発火の挿入 | 自前 | ハンドラ 0 なら発火しない |
| アダプタ | `org.bukkit.craftbukkit.*` / `io.papermc.paper.*` | Paper (GPLv3) | NMS を触らない |
| API | Bukkit / Paper API | Paper (GPLv3) | 無し |
| MOD | Fabric Loader と MOD | Fabric | 無し |

NMS に当てるのは shim・wire・event の 3 つで、どれも既存の行を消さない。
Paper の `patches/sources`(3124 hunk)は 1 つも当てない。

## 満たすべき条件

ハンドラが登録されていないイベントは発火しない。発火しないとき、実行される命令列は
vanilla と同一。

これが成り立てば、プラグインを入れていないサーバーは vanilla と完全に一致する。
発火の判定は Bukkit の `HandlerList` を見るだけで、イベントオブジェクトの生成も起きない。

返り値は「vanilla の処理を続けてよいか」で統一する
(Bukkit の `Event.callEvent()` と同じ向き)。差し込みは 3 つの形しか使わない。
vanilla の行そのものは 1 文字も変えない。途中で抜ける形、`if (...) {` と `}` で
vanilla の文を囲む形、式の末尾に `&& …` を足す形。

取り消しが結果の状態を必要とするイベントは、vanilla の処理を先に済ませてから発火し、
取り消されたときだけ元に戻す。成功する経路は vanilla のままになる。
Paper が使っているブロックキャプチャ(`Level.captureBlockStates`)は入れない。
あれは更新順そのものを変えるので、条件を満たせない。

## vanilla の行に触っている 3 か所

| | 件数 | 中身 |
|---|---|---|
| `patches/access` | 21 件 / 16 ファイル | 宣言の可視性を広げる。修飾子 1 語だけ |
| `patches/decompile` | 60 件 / 43 ファイル | 逆コンパイラが 1 行にまとめた局所変数を戻す |
| `patches/expr` | 1 件 | 式の末尾に発火を足す |

`patches/decompile` は `X v = E; if (v instanceof T p)` を `if (E instanceof T p)` に
まとめられたものを戻す。評価の回数も順序も変わらない。MOD の mixin が `@Local` で
その変数を捕まえるので必要になる。

`patches/expr` は式の最後に足すので、vanilla の判定が全て通ったあとにしか呼ばれない。
登録が無ければ true を返す。

`python tools/verify_additive.py` が、変更がこの 3 つの形に収まっているかを確かめる。

## アダプタ層が NMS に要求するもの

`org.bukkit.craftbukkit.*` は `Entity.bukkitEntity` のような状態を NMS 側に置く前提で
書かれている。素の vanilla に対してコンパイルすると、足りないものが全部エラーになる。

Paper 26.2 での実測:

* エラー 1703 件 / 全て `src/main/java`(vanilla の NMS 自体は素で通る)
* 要求されるメンバー 569 個 / 205 クラス
* 内訳は `Entity` 29、`ServerPlayer` 32、`ServerLevel` 24、`MinecraftServer` 21 など

中身はほぼ全部が状態の追加とアクセサで、vanilla のロジックを書き換えるものは無い。
`Entity` なら `bukkitEntity` / `spawnReason` / `origin` / `persist` といったフィールドと、
`getBukkitEntity()` のようなアクセサ。

宣言だけを Paper のパッチから抜き出して足す形(shim)で 569 個のうち大半が埋まる。
残りは `patches/hand` に手で書く(265 メンバー / 88 ファイル)。

## Paper の brigadier API

Paper は NMS の `CommandSourceStack` に API の `PaperCommandSourceStack` を実装させて
「同じオブジェクト」で通している。プラグインが組んだノード(述語・実行・補完・リダイレクト)は
API の型で書かれているのに、vanilla の dispatcher からは NMS の source が渡るからである。

Shifu はこの前提を取らない。vanilla のクラス宣言に interface を足すと、
`fabric-permission-api-v1` の mixin が Paper API の `withExecutor` を拾って
サーバーが起動しなくなる。

代わりに `dev.shifu.command.ApiSource`(アダプタ層に置く record)で包む。

| いつ | 何をする |
|---|---|
| API のノードを vanilla の dispatcher に入れるとき | 述語・実行・補完・リダイレクトを、NMS の source を受ける形に組み直す |
| API の引数の型を呼ぶとき | source を包んで渡す |
| API の引数の型が NMS を要求するとき | 包みを外す |
| 包んだもの | NMS の側に覚える(`shifuApiSource`)。同じ source は同じオブジェクトになる |

文脈は `CommandContext.copyFor` で source だけ差し替える。
書き換えたのはアダプタ層の 5 ファイル(`ApiMirrorRootNode`、`WrappedArgumentCommandNode`、
`VanillaArgumentProviderImpl`、`PaperBrigadier`、`BukkitCommandNode`)で、
vanilla の行は触っていない。

## バイトコードを公式に合わせる

MOD の mixin は、再コンパイルしたバイトコードを見る。逆コンパイル → 再コンパイルを
経ると、局所変数の番号もラムダの署名も公式と同じにはならない(Mojang の jar は
ProGuard を通っている)。ソースの書き方では合わせられないので、コンパイル後に直す。

| | |
|---|---|
| `tools/keep_vanilla_classes.py` | 触っていないクラスを Mojang 公式のバイトコードに差し替える |
| `tools/lvtmatch` | 局所変数の番号を公式に合わせ、ラムダを static 形へ直す |
| `tools/check_extra_locals.py` | 差し込みが公式にもある型の局所変数を作っている場所を出す |

差し込みそのものにも制約が付く。公式にもある型の局所変数を作らない、ラムダを増やさない。
どちらも `@Local` や `lambda$…$0` を名前で狙う mixin を外すため。
詳しくは [DEVELOPING.md](DEVELOPING.md) の「差し込みで守ること」。

## 生成の流れ

`tools/closure.sh` が、vanilla のツリーを基点に戻してから追加を当て、コンパイルし、
足りないものを要求リストへ足して不動点まで繰り返す。要求が 1 回で閉じないのは、
足したメンバー自身が別の追加メンバーを参照するため。

各段の道具と、手で書くときの手順は [DEVELOPING.md](DEVELOPING.md)。

`tools/filter_sources.py` は元々 Paper のパッチを選別するためのものだったが、
その方針は取りやめたため、現在は Java の構造解析(型の本体、宣言の判定)だけを使っている。
