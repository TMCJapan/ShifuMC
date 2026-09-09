# vanilla との差

`shifu.properties` の `vanilla-parity` が何をするか、いま vanilla と何が違うか。

## vanilla-parity が書くもの

`vanilla-parity = true`(既定)のとき、起動側が 2 つの設定ファイルを書く
(`dev.shifu.launcher.VanillaParity`)。すでにファイルがある場合は上書きせず、
戻すべき値を警告に出すだけ。

`spigot.yml`

```yaml
world-settings:
  default:
    entity-activation-range:      # vanilla はシミュレーション距離内の全エンティティを tick する
      animals: 512                # 512 ブロック = 最大シミュレーション距離(32 チャンク)
      monsters: 512
      raiders: 512
      misc: 512
      water: 512
      villagers: 512
      flying-monsters: 512
    max-tnt-per-tick: 0           # vanilla に 1 tick あたりの上限は無い。0 で制限が外れる
```

`config/paper-world-defaults.yml`

```yaml
entities:
  spawning:
    per-player-mob-spawns: false  # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと
```

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

代わりに、Shifu 自身が vanilla の行に触っている箇所がある。3 種類しかない。

| | 件数 | 命令列への影響 |
|---|---|---|
| `patches/access` — 宣言の可視性を広げる | 21 件 / 16 ファイル | 無し(修飾子 1 語) |
| `patches/decompile` — 逆コンパイラが消した局所変数を戻す | 66 件 / 43 ファイル | 無し(javac は `instanceof` の受け側を同じ命令で store する。変わるのは局所変数表だけ) |
| `patches/expr` — 式の末尾に発火を足す | 1 件 | 登録が無ければ true を返す static 呼び出しが 1 つ増える |

`python tools/verify_additive.py` が、変更がこの 3 つの形に収まっているかを確かめる。

イベント発火の差し込みは 866 箇所あるが、`HandlerList` に登録が無ければ発火しない。
発火しないとき、実行される命令列は vanilla と同一になる。

## 命令列で確かめる(1.20.6、2026-09-09)

ソースの行を見る `verify_additive.py` は、ビルドの後段で入る書き換えを見つけられない。
組み上がったバイトコードを公式と直接突き合わせる。

    java -cp tools/build/lvtmatch<;asm> dev.shifu.lvtmatch.CodeDiff <class の置き場か jar> <公式の jar>

公式にもこちらにもあるメソッドについて、命令の並びを比べる(ラベル・行番号・frame と
局所変数の番号は落とす。field と method は所有クラスまで見る)。

| | 命令列が同じ | 命令列が違う |
|---|---|---|
| コンパイル後の class | 20370 | 1703 |
| 組み上がった jar | 54227 | 1703 |

**この 2 つの「違う」が同じ数であることが、ビルドの後段がバイトコードを触っていない証拠。**
`tools/keepfields.gradle` を入れる前は jar 側が 3547 で、paperweight の
`fixJarForReobf` が field の所有クラスを宣言クラスへ書き換えていた分 1844 件が
上乗せされていた。

残る 1703 は差し込みと、逆コンパイラが分岐の形を変えた分
(`if (x instanceof T) { ... }` と `if (!(x instanceof T)) return;` など)。
意味は同じで、どちらも Shifu が新しく作ったものではない。

## 世界を突き合わせる(1.20.6、2026-09-09)

同じシードで vanilla を 4 回、Shifu を 1 回走らせ、region ファイルのチャンクを
展開して比べる(`tools/compare-worldgen.sh` / `tools/compare-ticks.sh`)。

| 比べたもの | ばらつき(vanilla 4 回) | 比べたチャンク | 違うチャンク |
|---|---|---|---|
| 起動して 20 秒 | 105 | 739 | **0** |
| 1200 tick(種と tick 数を固定) | 107 | 736 | **0** |

**vanilla が再現できるチャンクでは、Shifu との違いが 1 つも無い。**

vanilla を 2 回でなく 4 回走らせるのは、2 回だと「たまたま揃ったチャンク」が
ばらつきに数えられず、実差のように見えるため。1.20.6 では 2 回のとき 5 件、
3 回で 3 件、4 回で 0 件になった。

### 測るときに揃えるもの

これを外すと、Shifu 側の問題に見える差が出る。

| | なぜ |
|---|---|
| Shifu 側に vanilla 一致の設定を置く | `entity-activation-range` と `per-player-mob-spawns` が Paper の既定のままだと動物の tick と湧きが変わり、羊が草を食べるかどうかでブロックが変わる。**1.20.6 で 2 チャンクの実差に見えた原因はこれ**(`tools/env.sh` の `shifu_parity_dirs` が置く) |
| `LastUpdate` を比べない | 保存した時点のワールド tick。走らせるたびに数 tick ずれるので、見ると**全チャンクが「違う」**になり、比べられるチャンクが 0 件になる |
| `-Dmax.bg.threads=1` | チャンク生成と光の計算がどこまで進むかがスレッドの巡り合わせで変わる。vanilla と Shifu の両方に同じだけ効かせる |
| 難読化バージョンでは mojmap に写した公式 jar で vanilla を走らせる | `tools/tickstop` の agent が `MinecraftServer.tickServer` を名前で探すため。バイトコードは公式のまま(`run-vanilla/cp.txt`) |

残るばらつき(約 100 チャンク)は vanilla 自身のもの。チャンク生成は
`Util.getNanos()` の締め切りで 1 tick あたりの進み具合を決めるので、
同じ tick 数でもどこまで生成が進むかが実行のたびに変わる。

プレイヤーが遊ぶ範囲の処理順はまだ突き合わせていない。

### 26.2 で測ったもの(参考)

| 比べたもの | 違うチャンク |
|---|---|
| vanilla ↔ vanilla(同じシードで 2 回) | 22〜23 |
| vanilla ↔ Shifu(上のばらつきを除いたもの) | 1 |
| 1200 tick 走らせたあと(vanilla 同士 17 を除く) | 2 |

vanilla 2 回ぶんしか引いていないので、残った 1〜2 件はばらつきの取りこぼしの可能性がある。

## 分かっている差

乗り物に乗っている間の `PlayerMoveEvent` は届くが、イベントの中で
`player.getLocation()` を読むと動く前の位置になる。Paper はプレイヤーを乗り物の位置へ
先に動かしてから発火するが、それは vanilla に無い動きなので入れていない。

Paper のブロックキャプチャ(`Level.captureBlockStates`)は入れていない。更新順そのものが
変わるため。これを前提にしている `BlockFertilizeEvent` と `StructureGrowEvent` は
発火させていない。

## 項目を足すときの基準

`VanillaParity` に設定を足してよいのは、Paper 26.2 のバイトコードで
「その設定が実際に読まれていること」と「どの値が vanilla 相当か」の両方を確認した項目だけ。
確認していない設定を書くと、直すつもりで別の挙動を壊す。

`VanillaParityRules` にバイトコードの規則を足してよいのは、「その分岐が実際に vanilla の
値を捨てている」ことを確認した項目だけ。`TrackingRange` の場合は、
`getEntityTrackingRange` が引数を 0 のときしか返さないこと
(`0: iload_1 / 1: ifne 6 / 4: iload_1 / 5: ireturn`)を読んでから入れてある。
