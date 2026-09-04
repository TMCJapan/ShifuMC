# 土台の選択肢

「vanilla 挙動と一致」と「全プラグインが動く」を両立させるための土台の比較。
数字は Paper のパッチセット(1.21.10)と Paper 26.2 のバイトコードを実測したもの。

ここで案3(feature パッチを外した Paper)を採ったが、その後
`sources` の側も選別しようとして行き詰まり、方針を切り替えた(下の「案4」)。
いまの構成は [ARCHITECTURE.md](ARCHITECTURE.md)。この文書は判断の材料として残す。

## Paper のパッチの構造

| ディレクトリ | ファイル | hunk | 中身 |
|---|---|---|---|
| `patches/sources/` | 904 | 3032 | CraftBukkit / Paper の API 配線 |
| `patches/features/` | 31 | 約1000 | 名前の付いた挙動・最適化パッチ |

`sources/` 3032 hunk の内訳:

| 分類 | hunk | 割合 |
|---|---|---|
| 純粋な追加(既存行を消さない) | 1188 | 39% |
| 既存行を書き換える | 1844 | 60% |
| うちシグネチャ変更 | 592 | — |
| イベントを発火するもの | 588 | 19% |

登場する Bukkit イベントは 283 種類。

## 案1: vanilla に自前でフックを差す(イベントのルーティング)

Paper がどこにイベントを差しているかを把握し、vanilla に Mixin で同じ位置にフックを入れる。

イベントの差し込みは 3032 hunk のうち 588 件(19%)にすぎない。
残り 2444 hunk は構造的な配線で、イベントのルーティングでは覆えない:

- `Entity.bukkitEntity` / `getBukkitEntity()` のようなフィールドとアクセサの追加
- `ServerLevel` から `org.bukkit.World` への到達経路
- `spigotConfig` / `paperConfig` の引き回し
- メソッドのシグネチャに Bukkit の文脈(イベント原因など)を通す変更(592 hunk)
- ブロック変更の捕捉とロールバック

しかも 1844 hunk は既存行の書き換えなので、Mixin の注入点を 1 件ずつ決めることになる。
これは Cardboard(Mixin 246 個)や Taiyitist(Mixin 567 個)がやっていることと同じで、
規模も同じ桁。両者とも本家とのズレがプラグインごとのバグとして噴き出している。

vanilla 挙動は構造的に保証できるが、コストは 3032 hunk の再実装。

## 案2: Paper をそのまま使う

プラグイン互換は構造的に保証される。vanilla 挙動は `features/` 31 件を 1 件ずつ監査して
近づけるしかなく、`0001-Moonrise-optimisation-patches`(チャンクシステム)は戻せない。

## 案3: feature パッチを選んで外した Paper を作る

`sources/`(API 配線)は全部当て、`features/`(挙動・最適化)から挙動を変えるものだけ
外して Paper をビルドする。

feature パッチのサイズ:

| KB | hunk | ファイル | パッチ | 判断 |
|---|---|---|---|---|
| 2059 | 345 | 343 | `0002-Rewrite-dataconverter-system` | 残す(インフラ。挙動ではない) |
| 1652 | 448 | 184 | `0001-Moonrise-optimisation-patches` | 外す(チャンクシステム) |
| 91 | 17 | 13 | `0016-Alternate-Current-redstone` | 外す(既定で無効だが不要) |
| 57 | 8 | 7 | `0022-Optimise-general-POI-access` | 要判断 |
| 55 | 6 | 3 | `0015-Eigencraft-redstone` | 外す |
| 39 | 29 | 17 | `0005-Entity-Activation-Range-2.0` | 外す(設定で消すより確実) |
| 35 | 15 | 6 | `0029-Optimize-Hoppers` | 外す |
| 14 | 12 | 4 | `0027-Optional-per-player-mob-spawns` | 外す |
| 10 | 8 | 1 | `0024-Optimise-collision-checking` | 要判断 |
| 他 22 件 | 計約 150 | | 小さいもの | 個別に判断 |

判断対象は 31 件で、外すのは「当てない」だけなので実装コストがゼロ。
案1 の 3032 hunk 再実装と比べて桁が違う。

### 試験ビルドの結果(2026-09-01)

Paper `main`(26.2)を clone し、feature パッチを 1 つも当てずにビルドして動かした。

    ./gradlew applyPatches            # sources 934 + features 34 が適用される
    cd paper-server/src/minecraft/java
    git reset --hard <paper File Patches のコミット>   # feature 34件を全て落とす
    ./gradlew :paper-server:createBundlerJar

パッチ適用後のソースは git 履歴になっており、`sources` 934 件は 1 個のコミット、
`features` 34 件はその上の個別コミット。feature を外すのは commit を落とすだけで済む。
`applyFilePatches` と `applyFeaturePatches` が別タスクになっているのも同じ理由。

| 確認項目 | 結果 |
|---|---|
| feature ゼロで `paper-server:compileJava` | 成功(9435 クラス。CraftBukkit 944、moonrise グルー 57) |
| 単体起動 | 成功 `Done (8.973s)!` |
| プラグイン 3 件(WorldEdit / LuckPerms / PlaceholderAPI) | 成功 `Done (9.248s)!`。WorldEdit の paperweight NMS アダプタも動く |
| Shifu(Fabric Loader + Fabric API 41 モジュール)経由 | 成功 `Done (19.141s)!` |

生成された jar のクラスの有無:

| クラス | 状態 |
|---|---|
| `ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkTaskScheduler` | ABSENT — チャンクシステムが vanilla |
| `io/papermc/paper/entity/activation/ActivationRange` | ABSENT |
| `alternate/current/wire/WireHandler` | ABSENT |
| `org/bukkit/craftbukkit/CraftServer` ほか 992 クラス | PRESENT |
| `org/spigotmc/TrackingRange` | PRESENT(`sources` 側にあるので残る) |

`BaseChunkSystemHooks` が `0001` の追加フィールドを参照しているため
「そのままではコンパイルが通らない」と見ていたが、26.2 では `sources` 側だけで通った。
`sources/` の 904 件のうち 10 件が moonrise を参照しているものの、深いチャンクシステムの
書き換えは `0001` 側にあり、`sources/` 側は薄い呼び出しだけだった。

`TrackingRange` は `features` ではなく `sources` にあるため残るので、
バイトコードの書き換えで戻すことになる。

### コスト(実測)

- Paper を公式ビルドのダウンロードではなくソースからビルドすることになる。
  clone → `applyPatches`(vanilla の逆コンパイルを含む)→ ビルドで、
  初回 約 6 分、2 回目以降はキャッシュが効く
- 配布形態が変わる。ユーザーの環境でビルドさせるか、ビルド済み jar を配るか。
  後者だと Paper 由来のコードを再配布することになり、
  「配布物に Paper のコードが 1 行も入らない」性質を失う(GPLv3 なので合法ではある)
- Paper の更新ごとに、外す feature パッチの選定をやり直す
  (件数が変わる。1.21.10 は 31 件、26.2 は 34 件だった)

### Windows での注意

Paper のビルドはパスが長いと `applyResourcePatches` で失敗する。

    error: unable to create file data/minecraft/datapacks/trade_rebalance/data/minecraft/
    villager_trade/armorer/4/emerald_enchanted_iron_leggings_savanna.json: Filename too long

データパックのファイル名が長く MAX_PATH(260)を超える。
ドライブ直下から 2 段ほどの短いパスに置けば通る。

## 2026-09-01 の決定

案3 を採る。feature パッチは 34 件すべて外す
(どれが影響しないか判断できないので、残す理由が立証できたものだけ後から戻す)。
Paper のビルドはランチャが行う(Fabric や Paper のインストーラと同じ形)。

## feature を外すと vanilla と完全一致するのか → しない

feature を外すと消えるのは Paper の逸脱(moonrise、Entity Activation Range、
別実装レッドストーン、各種最適化)。CraftBukkit の逸脱は `sources` 側にあるので残る。

26.2 の `sources` 3124 hunk の内訳:

| 分類 | hunk | 挙動への影響 |
|---|---|---|
| 純粋な追加(既存行を消さない) | 1194 | 無い。フィールドやアクセサを足すだけ |
| 既存行を書き換え + イベント発火 | 409 | プラグインがキャンセルしなければ vanilla と同じ |
| 既存行を書き換え、イベント発火なし | 1521 | ここに実際の挙動変更が混ざる(うち 98 件は設定を読む) |

1521 件から無作為に抜き出して中身を確認したところ、内訳はおおむね 3 種類だった。

実際に vanilla と挙動が変わるもの:

```java
// EnderDragon: CraftBukkit が条件を追加している
- if (targetLocation != null) {
+ if (targetLocation != null && currentPhase.getPhase() != EnderDragonPhase.HOVERING) {

// ServerPlayer: Paper がテレポート時の強制降車を入れている
- public void removeVehicle() {
+ public void removeVehicle(final boolean suppressCancellation) {

// MinecraftServer: Paper がリスポーン地点をワールドごとに持たせている
- LevelData.RespawnData respawnData = this.getWorldData().overworldData().getRespawnData();
+ ResourceKey<Level> respawnDimension = ((PrimaryLevelData) this.getWorldData()...
```

プラグインが動かなければ vanilla と同じもの:

```java
// SetSpawnCommand: PlayerSetSpawnEvent で絞った結果を使う
- return targets.size();
+ return actualTargets.size();
```

挙動に影響しないもの: 引数に Bukkit のイベント原因を足すだけの変更、コメントの追加。

feature 除去は「Paper の逸脱をゼロにする」であって「vanilla と一致させる」ではない。
残るのは CraftBukkit 由来の古くからある差分で、Paper の最適化群に比べれば桁は小さいが、
確実に存在する。

この 1521 hunk を分類して戻そうとしたのが次の段階になる。

## 案3の続き: `sources` もメソッド単位で選ぶ(2026-09-01〜09-02、捨てた)

`sources` を全部当てると CraftBukkit 由来の挙動差が残るので、そちらも選別できないかを試した。
この方針で使っていたツール(`keep_from_errors.py`、`measure_kept.py`、
`tools/keep-methods.txt`)は残していない。

判断の単位はメソッドにした。hunk 単位で決めると宣言を変える hunk と本体を変える hunk が
割れて、多重定義や構文の破れになる。メソッド全体を「vanilla のまま」か「Paper のもの」かの
二択にすれば、どちらを選んでも構文は閉じている。

採る基準は 4 つ。

| 基準 | 理由 |
|---|---|
| イベントを発火する | プラグインを動かす本体 |
| 既存行を消さない(追加のみ) | Bukkit 層がリンクするのに必要。挙動に影響しない |
| 宣言が変わる | イベントの原因やワールドの文脈を渡すための引数追加。配線であって挙動ではない |
| 個別に指定したもの | 上の 3 つでは落ちるが、落とすと Bukkit 層が繋がらないもの |

引数が変わったメソッドは、上書きしている側も同じ形にしないと繋がらないので、
継承で繋がっている型(祖先と子孫)に限って同じ名前を揃えた。名前だけで全体に広げると
`tick` や `remove` のような名前が Paper の実装をツリー全体に引き込む。

Paper 26.2 での測定:

```
kept            : 2530
  うち書き換え  : 733   <- vanilla との差はここに出る
  うち配線      : 674
dropped         : 594
```

「落とした数」は指標にならない。引数を足して渡すだけの hunk は採用しても挙動は変わらない。
見るべきは採用したもののうち、イベントでもシグネチャでもない書き換えで、これが 733 件あった。
235 ファイルに散っていて、上位 8 ファイルで約 3 割を占める。

| 件数 | ファイル |
|---|---|
| 37 | `net/minecraft/server/level/ServerPlayer.java` |
| 37 | `net/minecraft/world/entity/LivingEntity.java` |
| 34 | `net/minecraft/world/entity/Entity.java` |
| 31 | `net/minecraft/server/MinecraftServer.java` |
| 31 | `net/minecraft/server/network/ServerGamePacketListenerImpl.java` |
| 22 | `net/minecraft/server/level/ServerLevel.java` |
| 15 | `net/minecraft/world/entity/player/Player.java` |
| 14 | `net/minecraft/server/players/PlayerList.java` |

多くは「イベントを発火するメソッドの本体が、発火と一緒に書き換えも含んでいる」もの。
発火だけを残して書き換えを戻すには、1 件ずつ本体を読んで判断することになる。

捨てた理由は 2 つ。

ブロックキャプチャが残る。`Level.captureBlockStates` / `capturedBlockStates` /
`captureTreeGeneration` は採用されたままで、樹木の生長・プレイヤーのブロック設置・
ディスペンサーで更新順が vanilla と変わる。`BlockPlaceEvent` 系がイベント発火前の状態を
必要とするので、キャプチャを外すとイベントが成立しない。

コンパイルも通らなかった。残り 103 件で、総称型や関数型インターフェースの引数追加など、
エラー本文から機械的に名前を採れないものが中心だった。

733 件を 1 件ずつ判断する作業量と、それを Minecraft の更新ごとに繰り返すことを考えると、
「Paper の hunk を当てない。足りないものだけを追加で埋める」ほうが総量が小さい。
発火を後にして取り消す形にすれば、ブロックキャプチャ無しで成功経路を vanilla と
同じ順序にできることも分かった。

## 案4: `sources` を 1 つも当てず、アダプタ層の要求だけを埋める(採用)

最終的にこの形になった。詳しくは [ARCHITECTURE.md](ARCHITECTURE.md)。

## 比較

| | 案1(vanilla + 自前フック) | 案2(Paper そのまま) | 案3(feature を選んで外す) |
|---|---|---|---|
| vanilla 挙動 | 構造的に保証 | 個別に戻す。moonrise は戻せない | moonrise ごと外せる |
| プラグイン互換 | 3032 hunk の再現度次第。ズレたら個別対応 | 構造的に保証 | 構造的に保証 |
| 実装コスト | 3032 hunk(うち 1844 は注入点の決定が必要) | ほぼゼロ | 31 件の判断 + ビルド構成 |
| 未知数 | 大(先行実装 2 つが失敗している) | 小 | `0001` を外して通るか |
| 配布 | Paper のコードを含まない | Paper のコードを含まない | ビルド方法次第 |

いまの構成は案1 に近いが、Mixin で 3032 hunk を再実装するのではなく、
Paper のアダプタ層(`org.bukkit.craftbukkit.*`)をソースのまま載せ、
そこが要求するメンバーの宣言だけを vanilla に足す形になっている。
「3032 hunk の再現度次第」という未知数は、アダプタ層を Paper から持ってくることで消えた。
