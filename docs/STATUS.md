# 現状(ver/1.20.6)

このブランチは Minecraft 1.20.6。節ごとにどの版で測ったかを頭に書いてある。
26.2 の分は `main`、1.21.11 の分は `ver/1.21.11` の同じファイル。
構成は [ARCHITECTURE.md](ARCHITECTURE.md)、開発の手順は [DEVELOPING.md](DEVELOPING.md)。

## 1.20.6 で通っているところ(2026-09-09)

| | |
|---|---|
| コンパイル | エラー 0(`once.sh`) |
| 落ちた規則 | 0 |
| 起動(MOD 無し) | プラグイン 24 個が有効化まで進む |
| 起動(MOD 込み) | MOD 102(直接入れたのは 16)・プラグイン 24 で `Done (1.772s)`。`java -jar shifu.jar` だけで通る |
| vanilla 一致(世界) | 起動して 20 秒・1200 tick のどちらも、vanilla が再現するチャンクで違い 0(詳細は [VANILLA-PARITY.md](VANILLA-PARITY.md)) |
| プレイヤー | bot が参加 → 登録 → テレポート → コマンド → WorldEdit の `//set` `//undo` → 落ちた物の拾い上げまで通る(`tools/mods-and-plugins.sh`) |

### Bukkit 層で空だったものを埋めた(2026-09-09)

bot を繋いで初めて分かった分。起動だけを見ていたときは出なかった。

| 何が起きたか | 何が原因だったか | 直し方 |
|---|---|---|
| ログに例外が 17675 行(その 4307 行が `CraftWorld.getUID() is null`) | Paper が `ServerLevel` の構築子で入れる `convertable` `uuid` と、`bukkitName`、`CraftServer.addWorld` が丸ごと落ちていた。`Bukkit.getWorlds()` が空で、世界の名前も UUID も null | 構築子の末尾で配線する。UUID は次元ごとのフォルダ(overworld は世界の直下、他は `DIM-1` / `DIM1`)の `uid.dat` から作る。addWorld が UUID の同じ世界を重複として弾くので、次元ごとに違う必要がある。例外は **14 行**になった |
| `ServerLevel.getTypeKey()` が null を返す | Paper は `LevelStorageAccess.dimensionType` を返すが、そこに次元を入れるのは `CraftServer.createWorld` だけ。vanilla の `Main` は次元を渡さない版を呼ぶ | 入っていなければ `dimension().location()` から `ResourceKey<LevelStem>` を作る(`patches/hand`) |
| 非同期のチャットと別スレッドからの kick が返ってこない | `MinecraftServer.processQueue` を誰も流していなかった。積んだ側は `Waitable` で待つ | CraftBukkit と同じく `popPush("levels")` の直後で流す |
| プラグインが `PlayerJoinEvent` `PlayerQuitEvent` を受け取らない | 発火の規則も発火層の `playerJoin` / `playerQuit` も落ちていた | `PlayerList` と `ServerGamePacketListenerImpl` の告知の文を囲む(`main` と同じ形) |
| WorldEdit の `//set` が `NoSuchMethodError` | `LevelChunk.setBlockState(BlockPos, BlockState, boolean, boolean)` は CraftBukkit の追加で、Shifu には 3 引数の vanilla の版しか無い | 4 引数の版を足す。`doPlace` が true のときは vanilla の版へ渡し、false のときだけ `onPlace` を抜いた写しを通る |
| プラグインがエンティティを 1 つも出せない(`World.dropItem` / `spawn` / `spawnEntity` が NPE) | 生成した `ServerLevel.addEntity(Entity, SpawnReason)` が Paper のチャンク系(`entityLookup`)へ渡していた。Shifu はそこを繋いでいない | イベントを出すところまでは Paper と同じで、最後の 1 行だけ vanilla の `addEntity(Entity)` へ渡す。`tryAddFreshEntityWithPassengers` と `addWorldGenChunkEntities` も同じ形で `entityManager` へ向けた |
| 拾い上げのイベントが 1 つも出ない | `ItemEntity` の規則ごと落ちていた | `main` の規則を 1.20.6 の行に当て直す(`orgCount` → `i`)。bot で `PlayerAttemptPickupItemEvent` と `EntityPickupItemEvent` の発火を確かめた |
| kick したときに NPE | `ServerCommonPacketListenerImpl.cserver` が空 | 構築子で入れる |
| 誰が繋いでも `Player.locale()` が en_US | `adventure$locale` が既定値のまま | `clientOptions.language()` を読んだ直後に入れる |
| `setSleepingIgnored` が効かない / `PlayerNaturallySpawnCreaturesEvent` が出ない | 数え上げ側の規則と発火そのものが落ちていた | `SleepStatus.areEnoughDeepSleeping` と `ServerChunkCache.tickChunks` に足す |

### 命令列の差 1705 件の内訳(2026-09-09)

`tools/build/code-differs.txt`(`tools/postcompile.sh` の 4 段目)を、
差が出た最初の命令の形と、そのファイルに Shifu の規則が当たるかで分けた。

| | 規則の当たるファイル | 当たらないファイル |
|---|---|---|
| 分岐の向きが逆(`IFEQ`↔`IFNE` など) | 462 | 171 |
| `goto` で 1 つの `return` にまとめるか、その場で返すか | 276 | 119 |
| `dev.shifu` / `org.bukkit` / `io.papermc` の記号が出る | 136 | 19 |
| その他 | 272 | 251 |
| 計 | 1146 | 560 |

**6 割(1028 件)は逆コンパイラの制御構造の形。** 同じ形が、Shifu が 1 行も触っていない
ファイルにも 290 件出ている。差し込みが原因と分かるのは 155 件(9%)。
残る 523 件は 1 件ずつ読まないと分からない。

CodeDiff はメソッドごとに**最初の 1 件**しか出さないので、
「差し込みの記号」の 155 件は下限になる。

### 1.20.6 に足りていないもの(2026-09-09 に測った)

`main`(26.2)と比べた数。1.20.6 への移植で落ちたまま。

| | 1.20.6 | main |
|---|---|---|
| 発火の規則 | 339 | 866 |
| 規則と発火層に出てくるイベントの型 | 253 | 431 |
| `patches/events/generated` の規則ファイル | 62 | 197(うち 118 は同じパスのファイルが 1.20.6 にもある) |

`main` の規則のうち、1.20.6 の行にそのまま当たるのは 31 件(近い行が無い 147、
ファイルごと無い 52)。写した 31 件のうちコンパイルが通ったのは 17 件で、
落ちた分は差し込む本体が 26.2 だけの API か、局所変数の名前違い。
名前違いのうち 3 件(`Creeper` の `lightning`、`HoneyBlock` の `world`、
`ItemFrame` の `CraftItemStack`)は手で直して戻した。

落ちている例: 拾い上げ(`PlayerAttemptPickupItemEvent` / `PlayerPickupItemEvent` /
`EntityPickupItemEvent`)、ディスペンサー、`ServerPlayerGameMode`、`Entity`、`Raid`、
`AnvilMenu`。`tools/check_wires.py main` は配線 31 件を挙げるが、読むと全部
26.2 だけのものか名前の違いだった。

足したが誰も代入しない欄が 36 件ある。大半は Paper のチャンク系(`chunkTaskScheduler`、
`newChunkHolder`、`entityLookup`)で、Shifu は繋いでいないので読むと NPE になる。
残る例外 14〜24 行はほぼこれと NBTAPI の自己診断。

### 名前空間の橋(1.20.6)

1.20.6 の Paper は mojmap で動くが、Fabric の MOD は intermediary のまま。
起動側(`dev.shifu.launcher.Namespace`)が 3 つを用意して fabric-loader に渡す。

1. intermediary と Mojang の `server.txt` を合わせた tiny v2
2. `fabric.mappingPath` ほか 5 つのシステムプロパティ
3. サーバー jar を intermediary に写したリマップ用クラスパス

合成とリマップは fabric-loader が同梱している mapping-io と tiny-remapper を
子 JVM で使う(`dev.shifu.launcher.Bridge`)。26.1 以降は intermediary が
公開されていないので、404 を見た時点で橋を作らずに進む。

### 名前空間を繋いだあとに直したもの(1.20.6)

MOD 側が悪いように見えて、全部 Shifu 側だった。公式の jar と 1 命令ずつ突き合わせて直した。

| 何が起きたか | 何が原因だったか | 直し方 |
|---|---|---|
| fabric-entity-events が `LivingEntity.checkBedExists` のラムダに当たらない | 差し込みがラムダを 1 つ足して、後ろの番号が繰り下がっていた | for 文に書き換えた(`patches/events/entity-core.rules`) |
| carpet の `@Shadow field_12858` が `LevelChunk` に見つからない | CraftBukkit に倣って `LevelChunk.level` を `ServerLevel` に狭めていた。`@Shadow` は署名で照合する | 狭めるのをやめ、Paper から写した本体の側で絞る |
| fabric-api が `ServerPlayerGameMode.destroyBlock` の局所変数に当たらない | ProGuard は読み終わった変数の slot を次の変数に使い回すが、javac は scope の終わりまで空けない | `LvtMatch` でも使い回す(公式とずれる変数 42 → 19) |
| C2ME が `ChunkMap.scheduleChunkGeneration` のラムダに当たらない | 逆コンパイルで式の順が変わり、ラムダが捕まえた引数の並びが公式と入れ替わっていた | `LambdaMatch` で引数と積む順を並べ替える |
| ServerCore の `@Redirect` が `Cat.tickCount` を見つけられない | paperweight の `fixJarForReobf` が field の所有クラスを宣言クラスへ書き換える。reobf には要るが mojmap の出力にも入っていた | mojmap 側は書き換える前の jar を使う(`tools/keepfields.gradle`) |
| Ledger の `@Local BlockEntity` が `CampfireBlock` で候補 0 件 | 逆コンパイラが `instanceof` の前の変数を畳んでいた | `patches/decompile/locals.rules` に 4 件足した |
| Ledger が `CauldronInteraction.lambda$bootStrap$5` に当たらない | 逆コンパイラが `static` 初期化子を別の位置に置くので、ラムダの番号の割り当てが入れ替わる | `LambdaMatch` で番号を付け直す(5 クラス 30 個) |
| Alternate Current が `WireHandler` の構築子で落ちる | Paper 1.20.6 が同名パッケージの Alternate Current を同梱していて、サーバー側のクラスが MOD を隠していた | Paper の同梱分を外す。`RedStoneWireBlock` は `redstoneImplementation == VANILLA` で分岐するだけで `alternate.current` を呼ばず、`WireHandler` を作る Paper の `ServerLevel` の配線も Shifu は取っていない。`ALTERNATE_CURRENT` の設定値は外す前から何もしていない |
| MOD を入れると NMS 側のログが 1 行も出ない | Paper の `SpigotLibraryLoader` が `libraries/` へ落としたプラグインの slf4j-api 1.7.36 が、Paper の 2.0.9 より先にクラスパスへ載って SLF4J が NOP になっていた | bundler の `META-INF/libraries.list` に載っている分だけを載せる |

### 残っている食い違い(1.20.6)

`tools/check_lambdas.py` が 4 クラス、`tools/compare_lvt.py` が 15 変数を挙げる
(差し込みが原因の 4 件は 2026-09-09 に直した)。
`ServerLevel` と `ItemStack` は Paper が足したメソッドの分だけラムダが増えるので、
番号の付け直しでは合わせられない。

残る 15 のうち 11 はラムダで、捕まえた引数の並びが公式と違う。`LambdaMatch` は
「indy の直前に並んだ命令が xLOAD だけ」のときしか並べ替えない。
`Block.popResource` のように式の途中に副作用があると並べ替えられないため。
残り 4 は逆コンパイラの側(`HangingEntity.offs` と `ChunkMap.getDependencyStatus` は
static と instance の食い違い、`Strider.tick` と `LootTable.shuffleAndSplitItems` は
slot の使い回し)。

Chunky と ChunkyBorder は、Chunky の Fabric MOD と Bukkit プラグインが
同じパッケージ名(`org.popcraft.chunky`)を持つため衝突する。MOD 側が先に載る。

## 1.21.11 で通っているところ

| | |
|---|---|
| コンパイル | エラー 0(`once.sh`) |
| 差し込み | 発火 828 箇所 / 337 ファイル、配線 75 箇所 / 38 ファイル、落ちた規則 0 |
| 追加だけであること | `verify_additive.py` が通る(消えた 23 行は可視性のみ) |
| 起動 | `Done (0.440s)!`。例外 0 |
| Bukkit の世界 | 3 つ(`world` / `world_nether` / `world_the_end`)。環境と UUID は別々 |
| スケジューラ | 毎 tick 走る。`MinecraftServer.currentTick` も進む |
| 停止 | `Bukkit.shutdown()` で JVM まで落ちる |

まだ見ていないもの: プレイヤーを繋いだ経路、実際のプラグイン、Fabric MOD、
vanilla との tick 一致(`compare-ticks.sh` / `compare-worldgen.sh`)。

### 数(1.21.11)

| | 件数 |
|---|---|
| 生成した宣言(`patches/shim`) | 126 メンバー / 98 ファイル |
| 自前で書いた追加(`patches/hand`) | 182 メンバー / 91 ファイル |
| 可視性を広げた宣言(`patches/access`) | 22 件 / 18 ファイル |
| Bukkit 層の配線(`patches/wire`) | 75 箇所 / 38 ファイル |
| 無名クラスへの差し込み(`patches/anon`) | 7 箇所 / 5 ファイル |
| 逆コンパイルの取りこぼしを戻した行(`patches/decompile`) | 45 件 |
| 式の末尾に足した発火(`patches/expr`) | 1 件 |
| アダプタ層の書き換え(`patches/adapter`) | 61 件 / 37 ファイル |
| 差し込んだ発火 | 828 箇所 / 337 ファイル(規則 828 件。うち 293 件は生成) |
| 発火層(`src/event`) | 10 ファイル 8895 行 |

---

以下は `main`(26.2)で測ったもの。1.21.11 では測り直していない。

## できていること

vanilla の NMS は素でコンパイルが通る。Paper の `patches/sources` を 1 つも当てない状態で
`paper-server/src/minecraft/java` はエラー 0。足りないものを要求するのはアダプタ層だけ。

shim(追加だけのパッチ)が生成できる。アダプタ層が要求するメンバーの宣言だけを
Paper のパッチから抜き出して、型の本体の末尾に足す。既存の行は触らない。

イベント発火層が動く。差し込みは行番号ではなくアンカー(その位置の行そのもの)で指定し、
見つからない・複数あるときは失敗させる。

MOD とプラグインが同じサーバーで同時に動き、プレイヤーの経路も通る。

## 数字

追加の数はリポジトリの規則ファイルを数えたもの。それ以外は測った日付を付けた。

| | 件数 |
|---|---|
| 素の vanilla に対するコンパイルエラー | 1703 → 0 |
| 起動 | `Done (0.639s)!`(エラー・例外 0) |
| 生成した宣言(`patches/shim`) | 238 メンバー / 104 ファイル |
| 自前で書いた追加(`patches/hand`) | 265 メンバー / 88 ファイル |
| 可視性を広げた宣言(`patches/access`) | 21 件 / 16 ファイル |
| Bukkit 層の配線(`patches/wire`) | 70 箇所 / 37 ファイル |
| 無名クラスへの差し込み(`patches/anon`) | 7 箇所 / 5 ファイル |
| 逆コンパイルの取りこぼしを戻した行(`patches/decompile`) | 60 件 / 43 ファイル |
| 式の末尾に足した発火(`patches/expr`) | 1 件 |
| アダプタ層の書き換え(`patches/adapter`) | 61 件 / 40 ファイル |
| 差し込んだ発火 | 866 箇所 / 354 ファイル(規則 559 件。うち 53 件は生成) |
| 発火層(`src/event`) | 10 ファイル 9012 行 |
| 発火しても一部効かないもの | 97 種 |
| 入れられなかった発火 | 2 件(どちらもブロックキャプチャが前提) |
| 世界生成の一致 | vanilla 同士のばらつき(22〜23 チャンク)の中に収まる |
| 1200 tick 走らせた世界の一致 | vanilla 同士 17 チャンク、それを引いた Shifu との差は 2 |
| 公式の局所変数が公式の番号に座っていないメソッド | 182 → 21 |

触った vanilla の行は 3 種類だけで、`python tools/verify_additive.py` が形を確かめる。
内訳は [ARCHITECTURE.md](ARCHITECTURE.md) の「vanilla の行に触っている 3 か所」。

## プレイヤー経路(実測、2026-09-03)

`sh tools/player-events.sh` で bot を繋いで通した。届いたもの:

| イベント | 確かめたこと |
|---|---|
| `PlayerJoinEvent` / `PlayerQuitEvent` | 届く |
| `PlayerTeleportEvent` | `PLUGIN`(`Player.teleport`)と `COMMAND`(`/tp`)で届く |
| `PlayerMoveEvent` | 届く。取り消すと bot に戻すテレポートが届く |
| `AsyncChatEvent` | 届く(同期)。`message()` の差し替えが送信に反映される |
| `PlayerDropItemEvent` | 届く |
| `InventoryClickEvent` | 届く(`LEFT` / `PICKUP_ALL`、raw slot 36) |
| `BlockExplodeEvent` | `World.createExplosion` で届く |
| `EntityExplodeEvent` | 発生源がプレイヤーのもの、TNT のもの(`ExplosionPrimeEvent` も)で届く |
| `PlayerDeathEvent` | 届く。`deathMessage()` の差し替えが死亡画面と告知に反映される |
| `PlayerRespawnEvent` | 届く。`setRespawnLocation` が反映される |
| リスポーン後の `Player` | 死ぬ前と同じオブジェクト(`Bukkit.getPlayer(uuid) == 前の参照`) |
| `AsyncPlayerPreLoginEvent` / `PlayerPreLoginEvent` / `PlayerLoginEvent` | 届く(async は別スレッド、他はサーバースレッド) |
| `PlayerInteractEvent` | ブロックの右クリック(`RIGHT_CLICK_BLOCK`)と左クリックで届く |
| `BlockBreakEvent` | クリエイティブの即時破壊で届く |
| `BlockGrowEvent` | 蔓と小麦の生長で届く(1 回の走行で 6934 件)。取り消すと 1 tick 後に元の状態に戻る |
| `BlockSpreadEvent` | 届く(2876 件) |
| `EntityChangeBlockEvent` | 届く(75 件) |
| `PlayerToggleFlightEvent` | 届く。取り消すと `flying` は false のまま。2 回目(許可)で true |
| `PlayerGameModeChangeEvent` | `/gamemode` で `COMMAND`、`Player.setGameMode` で `PLUGIN` |
| `TNTPrimeEvent` | レッドストーンブロックで点火して `REDSTONE` |
| `PlayerItemConsumeEvent` | パンを食べ終えて届く(`hand=HAND`) |
| プラグインメッセージ | クライアントの名乗り(`minecraft:brand`)が `Player.getClientBrandName()` に入る |
| `FoodLevelChangeEvent` | 食べ終えて届く(`getItem()` は `BREAD`) |
| `WorldBorderBoundsChangeEvent` | `WorldBorder.setSize` で届く。取り消すと大きさが変わらない |
| `WorldGameRuleChangeEvent` | API と `/gamerule` の両方で届く |
| `TimeSkipEvent` | `/time set` で届く(`COMMAND`) |

サーバー側のエラー・例外は 0。`stop` のあと JVM は保存の直後に exit 0 で終わる。

## 実際のプラグイン(実測、2026-09-03)

`sh tools/real-plugins.sh` で 5 つのプラグイン(WorldEdit 7.4.5、EssentialsX 2.22.0、
LuckPerms 5.5.71、Vault 1.7.3、ProtocolLib 5.5.0)を入れ、bot を繋いでコマンドを叩かせた。

| 確かめたこと | 結果 |
|---|---|
| 読み込みと有効化 | 5 つとも通る。EssentialsX は設定ファイルを一式作る |
| LuckPerms と Vault | 起動し、Vault が LuckPerms を権限の提供元として掴む。`lp info` が応答する |
| ProtocolLib | 有効化され、プレイヤーが繋がった状態でも例外を出さずに終わる(netty のパイプラインに割り込む) |
| `//pos1` `//pos2` `//set stone` | 3×3×3 の 27 ブロックすべてが石になる |
| `//undo` | 27 ブロックすべてが空気に戻る |
| `/sethome` と `/home` | 離れた場所から家へ戻る |
| `/speed 3` | 飛行速度が 0.1 → 0.3 |
| `/gamemode survival`(EssentialsX 版) | 変わる |
| サーバー側の例外 | 0 |

EssentialsX は起動時に「You are running an unsupported server version!」と出す。
EssentialsX が知っているバージョンの一覧に 26.2 が無いため。

`plugins` と `version` は通らない。この 2 つは Bukkit ではなく Paper 独自のコマンド
(`io.papermc.paper.command.PaperCommands.registerCommands`)で、vanilla に無いので
入れていない。Bukkit の `SimpleCommandMap.setFallbackCommands` が登録するのは
`/bukkit:help` だけ。

## MOD とプラグインを同時に(実測、2026-09-04)

`sh tools/mods-and-plugins.sh` で MOD 17 個・プラグイン 29 個を同時に入れ、
bot をプレイヤーとして繋いだ。参加・WorldEdit の `//set` と `//undo`・
EssentialsX の `/home` まで通る(例外 0)。

MOD は Fabric API、Lithium、FerriteCore、Krypton、C2ME、Carpet、Ledger、styled-chat、
spark、ServerCore、Alternate Current、VeryManyPlayers、Chunky、No Chat Reports ほか。
プラグインは WorldEdit、WorldGuard、EssentialsX、LuckPerms、Vault、Multiverse、Via、
Chunky、TAB、TabTPS、VeinMiner、PlaceholderAPI、DecentHolograms、FancyHolograms、
FancyNpcs、NBT-API、MiniMOTD、InvSee++、Skript、GriefPrevention、HuskHomes、
QuickShop-Hikari、BlueMap、ProtocolLib ほか。

内容を足す MOD も含めた 139 mod でも起動は通る(Done 5.5 秒、例外 0)。
Fabric のレジストリ同期が素のクライアントを弾くので、遊ぶには Fabric のクライアントが要る。

触っていないクラスは Mojang 公式のバイトコードをそのまま置くようにした。
7442 クラス中 6028 が公式のままになり、そこに mixin を当てる MOD は vanilla と同じものを見る。

動かないもの:

| | 誰の問題か |
|---|---|
| Architectury API 21.0.7 | Shifu。手を入れたクラスに `@Local` で局所変数を捕まえるので当たらない |
| FastAsyncWorldEdit 2.15.4 | Shifu の設計。Paper の chunk システムの内部が要る |
| Krypton + GrimAC | 両者の衝突。GrimAC だけなら動く。どちらも netty のパイプラインを組み替える |
| PacketEvents 2.13.0 | プラグイン側。26.2 の packet id を持っておらず、ログインで接続が切れる |
| CoreProtect CE 24.0 | プラグイン側。`VersionCheckService` が 26.1.2 を上限として自分で弾く。本家 Paper 26.2 でも同じ |

`LifecycleEvents` の registry と tag の入口(Paper は 6 ファイルで発火する)は
まだ通していない。

プラグインが要る NMS のメンバーは、動かす前に `python tools/missing_members.py` で
数えられる。ProtocolLib・NBT-API・TabTPS・PlaceholderAPI は 0 件だった。

## 世界生成の一致(実測)

同じシード(1234567890)で vanilla と Shifu を走らせ、region ファイルのチャンクを
展開して突き合わせた。プラグインは入れない。

同じものを 2 回走らせても違いは出る。起動から停止までのあいだ原点まわりが tick され、
乱数の入る処理がそのぶん進むため。「違いが 0 か」ではなく「同じもの同士のばらつきより
小さいか」で見る。

| 比べたもの | 違うチャンク |
|---|---|
| vanilla ↔ vanilla | 23 |
| Shifu ↔ Shifu | 24 |
| vanilla ↔ Shifu(上の 23 を除いたもの) | 1 |

ばらつきの下限が 22〜24 チャンクで、vanilla と Shifu の差はその中の 1。
この測り方では両者を区別できない。発火 318 箇所、339 箇所、382 箇所の 3 つの時点で
同じ数字。残る 1 の位置は走るたびに変わる。412 箇所にしたあとは測っていない。

## 処理順(実測)

`tools/compare-ticks.sh` で 1200 tick まで測った。測定用の Java agent(`tools/tickstop`)で
乱数の種を決定的にし、ちょうど N tick で止める。vanilla 同士で 17 チャンクずれ、
それを引いた Shifu との差は 2 チャンク(走らせるたびに 2〜4 で揺れる)。

## 配布物(実測、2026-09-03)

`sh tools/dist.sh` で 2 つを組んで、まっさらなフォルダで動かした。

| 出るもの | 大きさ | 中身 |
|---|---|---|
| `shifu.jar` | 69 KB | 起動側。Minecraft も Paper も Shifu のサーバーも入っていない |
| `shifu-server.jar` | 63 MB | Shifu のサーバー(paperclip 形式)。Minecraft のクラスは入っていない |

まっさらなフォルダに `shifu.jar` を置いて `java -jar shifu.jar` を走らせると、この順で進む。

1. `shifu-server.jar`(パスか URL は `shifu.properties` の `server-paperclip`)を
   paperclip として走らせ、Mojang の公式 server.jar を落として Shifu のサーバーを組み立てる
2. Fabric Loader とその依存を取る
3. vanilla に寄せる設定(`spigot.yml`、`paper-world-defaults.yml`)を書く
4. Shifu の GameProvider を通してサーバーを起動する

確かめたこと: `Done (1.403s)`、Fabric の MOD が初期化されてレジストリを読む、
Bukkit のプラグイン(WorldEdit)が有効化される、例外 0。

Minecraft のバイナリは配布物に入らない。Paper 由来のコードは `shifu-server.jar` に入るが、
Shifu も Paper も GPLv3 で、ソースを出していれば配れる。

## まだ確かめていないこと

プレイヤーが遊ぶ範囲の処理順。`tools/compare-ticks.sh` は無人のサーバーを N tick
走らせるだけで、bot を決まった手順で動かした比較はしていない。
残っている 2〜4 チャンクの差が何のずれかも特定していない。

乗り物に乗っている間の `PlayerMoveEvent` は出るが、イベントの中で
`player.getLocation()` を読むと動く前の位置になる。Paper はプレイヤーを乗り物の位置へ
先に動かすが、それは vanilla に無い動き。

プラグインメッセージの本文の受信。実装は入れたが、vanilla の codec は知らないチャンネルの
本文を書かないので bot からは送れない。実クライアントかプロキシで見る必要がある。

`ServerCommonPacketListenerImpl.clientBrand` と `pluginMessagerChannels` は、
vanilla の `CommonListenerCookie` に成分が無いので配線できない。
足したが誰も代入しない欄はこの 2 つだけ残っている。

公式の局所変数が公式の番号に座っていないメソッド 21 と、ラムダの署名が公式と違う 6 クラス。
残っているのは、ラムダの引数の順が違う(引数は位置で決まるので動かせない)、
同じ名前が複数の slot にある、一時変数や寿命の重なりで空けられない、
公式にしかないラムダ(逆コンパイラが式に展開してしまい、合成メソッドが無い)のいずれか。
ここから先は、差し込みをソースではなく公式のバイトコードに対して行うことになる。

独自 packet を使う MOD とプラグイン、経済系のプラグイン。
