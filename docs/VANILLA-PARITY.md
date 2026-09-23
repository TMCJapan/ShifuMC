# vanilla との差

`shifu.properties` の `vanilla-parity` が何をするか、いま vanilla と何が違うか。

## vanilla-parity が書くもの

`vanilla-parity = true`(既定)のとき、起動側が次の値を入れる
(`dev.shifu.launcher.VanillaParity`)。ファイルが無ければ作り、Paper が既に作っていれば
その中の鍵に書き込む。

`spigot.yml`

```yaml
world-settings:
  default:
    entity-activation-range:      # 0 で活性化範囲の判定そのものが外れる(下記)
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
    max-tnt-per-tick: 0           # vanilla に 1 tick あたりの上限は無い。0 で制限が外れる
```

`config/paper-world-defaults.yml`(1.19 以降)

```yaml
entities:
  spawning:
    per-player-mob-spawns: false  # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと
```

1.18.2 では同じ値を `paper.yml` の `world-settings.default.per-player-mob-spawns` に書く。
`config/paper-world-defaults.yml` が入ったのは 1.19 から。

### entity-activation-range が 0 なのはなぜか

2026-09-21 まで 512 を書き、「512 ブロック = 最大シミュレーション距離(32 チャンク)」と
説明していた。これは違う。活性の判定に使う箱は

```java
maxRange = Math.min( ( world.spigotConfig.simulationDistance << 4 ) - 8, maxRange );
```

で頭を押さえられる(`Paper-Server/src/main/java/org/spigotmc/ActivationRange.java:194`、
mache の 2 系統は `paper-server/patches/features/*-Entity-Activation-Range-2.0.patch` の同じ行)。
既定の simulation-distance 10 では 152 ブロック、上限の 32 でも 504 ブロックにしかならず、
512 と書いてもそこまでしか広がらない。

0 にすると `initializeEntityActivationState` がその分類の全エンティティで true を返し(同 :133)、
`Entity.defaultActivationState` が立って `checkIfActive` が先頭で true を返す(同 :388)。
`ServerLevel.tickNonPassenger` はその真偽で `entity.tick()` と `entity.inactiveTick()` を
選んでいるので、全部 `tick()` に入る = vanilla と同じになる。

### 書いた鍵と、書く前の値を覚えておく

書き込んだ鍵と、書く前にそこにあった値を
`.shifu/vanilla-parity-generated.properties` に 1 行ずつ残す。

```
spigot.yml|world-settings.default.entity-activation-range.animals=32
config/paper-world-defaults.yml|entities.spawning.per-player-mob-spawns=true
```

- 1 つの鍵に書き込むのは 1 度だけ。記録がある鍵は以降の起動で触らないので、
  利用者が後から直した値は残る。ファイル単位で持っていたときは、起動のたびに
  8 つの鍵を書き戻していた。
- `vanilla-parity = false` にすると、記録がある鍵を書く前の値へ戻す。
  Shifu が足した鍵(`(absent)` と記録されているもの)はその鍵ごと消す。
  いま入っている値が Shifu の書いた値と違うときは、利用者が変えたものとして触らない。
- ファイルの中身が Shifu の書いたままなら、ファイルごと消す。

戻せないのは、この記録を持つ前(2026-09-21 より前)のランチャが作ったディレクトリのうち、
設定ファイルから Shifu の印(先頭の注記)が落ちているもの。1.19 以降の
`config/paper-world-defaults.yml` は Configurate が書き直すときに注記を落とすので、
`per-player-mob-spawns: false` が Shifu の書いたものか利用者の設定かを見分けられない。
そのディレクトリでは、この鍵を手で消すと Paper の既定(true)に戻る。

バイトコード側では 1 件だけ書き換える(`dev.shifu.bootstrap.VanillaParityRules`)。

| クラス | 何をするか |
|---|---|
| `org.spigotmc.TrackingRange` | `getEntityTrackingRange(entity, defaultRange)` が第 2 引数をそのまま返すようにする。vanilla は `EntityType` ごとに `clientTrackingRange` を持つが、Spigot は 6 分類に丸めて上書きする |

## いまのビルドでは、この 3 つに読み手がいない

上の設定は、Paper のサーバーを土台にしていた頃に書いたもの。いまは Paper の
`patches/sources` を 1 つも当てないので、これらの設定を読む行が NMS に入っていない。

組んだ `paper-26.2.jar`(NMS 7442 クラス)を調べた結果:

| 探したもの | 結果 |
|---|---|
| `io.papermc.paper.entity.activation.ActivationRange` | jar に入っていない。`entity-activation-range` を読むクラスが存在しない |
| `maxTntTicksPerTick`(`SpigotWorldConfig` の欄) | 参照する NMS クラスが 0 |
| `perPlayerMobSpawns`(`WorldConfiguration` の欄) | 参照する NMS クラスが 0 |
| `org.spigotmc.TrackingRange` の呼び出し | NMS からは 0。`ca.spottedleaf.moonrise.paper.PaperHooks.modifyEntityTrackingRange` からだけ呼ばれ、そこを呼ぶ NMS の行も無い |

つまり `vanilla-parity` を false にしても、いまの構成では挙動は変わらない。
値が効いていたのは Paper のパッチが当たっていた頃の話で、そのパッチを外した時点で
補正する対象そのものが消えている。

`org/spigotmc/SpigotWorldConfig` と `io/papermc/paper/configuration/WorldConfiguration`
はアダプタ層として jar に残っているので、`spigot.yml` と `paper-world-defaults.yml` は
読み込まれるし、プラグインからも見える。値が NMS の処理に届かないだけ。

設定と規則を消していないのは、`server-paperclip` を空にして Paper 公式ビルドを
組み立てる使い方が残っているため。そちらでは Paper のパッチが当たっているので、
3 つとも効く。

## Paper 由来の挙動差は、いま何が残っているか

NMS に Paper のパッチを当てていないので、Paper と CraftBukkit の挙動差は入っていない。
`patches/sources` の 3124 hunk のうち「既存行を書き換え、イベント発火なし」だった
1521 hunk が、まるごと入らない。

代わりに、Shifu 自身が vanilla の行に触っている箇所がある。4 種類しかない。

| | 件数 | 命令列への影響 |
|---|---|---|
| `patches/access` — 宣言の可視性を広げる | 21 件 / 16 ファイル | 無し(修飾子 1 語) |
| `patches/decompile` — 逆コンパイラが消した局所変数を戻す | 60 件 / 43 ファイル | 無し(評価の回数も順序も同じ) |
| `patches/expr` — 式の末尾に発火を足す | 1 件 | 登録が無ければ true を返す static 呼び出しが 1 つ増える |
| `patches/decompile/exprs.rules` — 逆コンパイラが意味を変えた式を戻す | 2 件 | 公式と同じ命令列に戻す。`postcompile.sh` の SemDiff が名指ししたメソッドを公式と比べ、違えば組むのを止める |

`python tools/verify_additive.py` が、変更がこの 4 つの形に収まっているかを確かめる。

イベント発火の差し込みは 866 箇所あるが、`HandlerList` に登録が無ければ発火しない。
発火しないとき、実行される命令列は vanilla と同一になる。

## 測って確かめた範囲

| 比べたもの | 違うチャンク |
|---|---|
| vanilla ↔ vanilla(同じシードで 2 回) | 22〜23 |
| vanilla ↔ Shifu(上のばらつきを除いたもの) | 1 |
| 1200 tick 走らせたあと(vanilla 同士 17 を除く) | 2 |

同じものを 2 回走らせても違いは出るので、「違いが 0 か」ではなく「同じもの同士の
ばらつきより小さいか」で見る。この測り方では vanilla と Shifu を区別できない。

プレイヤーが遊ぶ範囲の処理順はまだ突き合わせていない。

## 分かっている差

乗り物に乗っている間の `PlayerMoveEvent` は届くが、イベントの中で
`player.getLocation()` を読むと動く前の位置になる。Paper はプレイヤーを乗り物の位置へ
先に動かしてから発火するが、それは vanilla に無い動きなので入れていない。

Paper のブロックキャプチャ(`Level.captureBlockStates`)は入れていない。更新順そのものが
変わるため。これを前提にしている `BlockFertilizeEvent` と `StructureGrowEvent` は
発火させていない。

### プラグインが無いときの扱いを決めたもの

Bukkit が自分で登録するコマンド。`SimpleCommandMap` は構築子と、`CraftServer.enablePlugins(POSTWORLD)` が呼ぶ
`setFallbackCommands()` で、プラグインの数に関係なく `help` と `timings` を `<名前>` と `bukkit:<名前>` の両方で登録する。
vanilla にもある名前(`/help`)は、プラグインを 1 つも入れていなくても Bukkit 版に置き換わっていた。
`patches/adapter/org-bukkit-craftbukkit-command-CraftCommandMap.rules` で `bukkit:` の付いた名前だけにしている。
Paper は `minecraft:<名前>` を残す別名の登録(modern alias registration)を入れているが、
`Commands` の構築子の書き換えなので入れていない。

データパックの読み直し。Paper は読み直しのあとで `CraftServer.syncCommands` を呼び、全員へコマンドの木を
送り直す。vanilla は読み直しで送り直さない(`sendCommands` の呼び手は参加時と op の変更だけ)ので、
プラグインが 1 つも無いときは送らない(`patches/adapter/org-bukkit-craftbukkit-CraftServer.rules`)。

イベントではなく API で立つ欄は `listening()` で包めないので、vanilla の処理の中で欄を読む分岐が残る。
プラグインが無ければ欄は false のままで、増えるのは欄の読み 1 回と分岐 1 つ。

| 欄 | 読む場所 | 立てるもの |
|---|---|---|
| `Entity.fixedPose` | `Entity.setPose` の先頭 | `Entity#setPose(pose, true)`(`CraftEntity`) |
| `ServerPlayer.shifuClientWorldBorder` | `ServerPlayer.doTick` | `Player.setWorldBorder`(`CraftPlayer`) |

### MOD から見た開発環境の判定

MOD を intermediary から mojmap へ写す経路が fabric-loader では開発環境の判定の中にしか無いので、
起動側は `-Dfabric.development=true` を付ける(`dev.shifu.launcher.Namespace`)。そのままだと
`FabricLoader.isDevelopmentEnvironment()` が MOD にも true を返し、プラグインも MOD の操作も無いのに
Fabric API が vanilla の開発用コマンド `/debugconfig` を登録し、C2ME は `/c2me debug` を出し、
Fabric API の object-builder は遅い登録を例外にする(0.141.6+1.21.11 で確認。1.20.6 の 0.100.8 には無い)。

起動側は fabric-loader の jar を書き換え、このメソッドが凍結(`FabricLoaderImpl.freeze()`)の後は
false を返すようにして使う(`.shifu/libraries/.../fabric-loader-<版>-shifu.jar`)。
fabric-loader の中でこのメソッドを true のまま必要とするのは `setup()` の 2 か所(MOD を写すかどうかと、
MOD 順のシャッフル)だけで、どちらも凍結の前に走る。写しの名前空間や Mixin の設定は
`FabricLauncherBase.isDevelopment()` を直接読むので、書き換えの影響を受けない。
0.19.3 と 0.19.5 の jar の全クラスで呼び出し元を確かめてあり、それ以外の場所から呼ぶ版では
書き換えずに起動する。書き換えたクラスは署名と合わなくなるので、書き換えた jar は署名を外してある。

書き換えられなかったときは、起動時に
`could not rewrite fabric-loader-<版>.jar (...) - mods will see isDevelopmentEnvironment() = true`
と出て、上の差がそのまま残る。

26.1 以降(`main`)は MOD もサーバーも mojmap なので、この値を付けていない。

## 項目を足すときの基準

`VanillaParity` に設定を足してよいのは、Paper 26.2 のバイトコードで
「その設定が実際に読まれていること」と「どの値が vanilla 相当か」の両方を確認した項目だけ。
確認していない設定を書くと、直すつもりで別の挙動を壊す。

`VanillaParityRules` にバイトコードの規則を足してよいのは、「その分岐が実際に vanilla の
値を捨てている」ことを確認した項目だけ。`TrackingRange` の場合は、
`getEntityTrackingRange` が引数を 0 のときしか返さないこと
(`0: iload_1 / 1: ifne 6 / 4: iload_1 / 5: ireturn`)を読んでから入れてある。
