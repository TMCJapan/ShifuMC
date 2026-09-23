# 現状(ver/1.21.11)

2026-09-12 時点。このブランチは Minecraft 1.21.11。26.2 の分は `main` の同じファイル。
構成は [ARCHITECTURE.md](ARCHITECTURE.md)、開発の手順は [DEVELOPING.md](DEVELOPING.md)。

## 1.21.11 で通っているところ

| | |
|---|---|
| コンパイル | エラー 0(`once.sh`) |
| 差し込み | 発火 828 箇所 / 337 ファイル、配線 75 箇所 / 38 ファイル、落ちた規則 0 |
| 追加だけであること | closure の最後に `verify_additive.py` が走る。2026-09-23 に当てた木では 24 件が決めた形に収まらず、closure はそこで止まる |
| 起動 | `Done (0.440s)!`。例外 0 |
| Bukkit の世界 | 3 つ(`world` / `world_nether` / `world_the_end`)。環境と UUID は別々 |
| スケジューラ | 毎 tick 走る。`MinecraftServer.currentTick` も進む |
| 停止 | `Bukkit.shutdown()` で JVM まで落ちる |
| 起動(MOD 込み、2026-09-12) | MOD 125・プラグイン 24 で `Done (1.499s)`。mixin エラー 0、例外 3(starlight の class 探し 2、AuthMe の GeoLite 取得 1。どれも Shifu の外) |
| プレイヤー(2026-09-12) | bot が参加 → AuthMe 登録 → sethome / home → gamemode → WorldEdit `//set`(27 / 27) `//undo` → 落ちた物の拾い上げ(2 件)→ ブロック破壊(BlockBreakEvent)→ TNT(EntityExplodeEvent、blocks=14)→ インベントリ操作(InventoryClickEvent)→ ドア設置(BlockPlaceEvent)まで通る |

まだ見ていないもの: vanilla との tick 一致(`compare-ticks.sh` / `compare-worldgen.sh`)。
出ていないイベント: BlockMultiPlaceEvent(ドア)、BlockDropItemEvent(松明)。
どちらも 1.20.6 で足した規則(`7f493a8`)がこの枝には無い。

### 1.20.6 のツールを持ち込んで直したもの(2026-09-12)

| 何が起きたか | 原因 | 直し方 |
|---|---|---|
| fabric-entity-events の `isSleepingInBed` のラムダを狙う mixin が当たらない | `LivingEntity.removeAllEffects` の差し込みが `forEach` のラムダを 2 つ足していて、合成メソッドの番号がずれる | for 文で書く(1.20.6 と同じ) |
| fabric-tag-api の `@Accessor` が `MappedRegistry$TagSet$2` の `field_53694` を見つけない | 逆コンパイラが局所変数を `map` と付け、合成の欄が `val$map` になる。公式は `val$tags` | `patches/decompile/locals.rules` で名前だけ直す |
| fabric-game-rule-api の `@Shadow` が `GameRuleCommand$1` の `field_64600` を見つけない | 同じく `val$literalArgumentBuilder`。公式は `val$base` | 同上 |
| styledchat の `@ModifyArg` が `PlayerAdvancements` の告知のラムダを見つけない | PlayerAdvancementDoneEvent の差し込みが `flatMap` のラムダを足していた | Optional を取り出して分岐で書く |
| bot が参加 2 秒後に切れる(サーバー側は `Disconnected` だけ) | codebook が名前を戻した公式 jar の `BundlerInfo$1$1` が JVM に読めない(`ClassFormatError: Illegal field name`)。`keep_vanilla_classes.py` がそれを写していた。サーバーは使わないクラスなので起動は通る | 公式へ戻したクラスを JVM に読ませ(`tools/lvtmatch` の `ClassCheck`)、落ちたら同じソースのクラスをまとめて Shifu のものに戻す(4 件) |
| bot が 1.21.11 の packet で組めない | `tools/bot` は版ごとに packet の署名が違う | この枝の bot に 1.20.6 の追加(break / slot、`OUT`、カンマ区切り)だけを移した |
| bot の切れた理由が出ない | `Connection.tick()` を回していないと `onDisconnect` が届かない | 1 秒ごとに `tick()` する |

### 2026-09-16 に直したもの(1.21.11)

| | |
|---|---|
| 新しいプレイヤーが nether に参加する | vanilla の nether / end の `serverLevelData` は `DerivedLevelData` で、`CraftWorld.setSpawnLocation` の `setSpawn` が主世界の `PrimaryLevelData` へ通る。1.21.9 以降の `RespawnData` は次元を持つので、Multiverse が nether の spawn を置いた時点でサーバーの `effectiveRespawnData` が nether になり、新しいプレイヤーが `world_nether` の (0.5, 1.0, 0.5) に参加していた。主世界以外は `ServerLevel.shifuSpawn`(hand)に置いて `getSpawnLocation` もそこから読む。drive で nether を (10, 64, 10) にしても主世界は (0, 91, 0) のまま、参加は `world` |
| MOD 無しで参加の直後に落ちる | `VerifyError: Inconsistent stackmap frames`(`ServerGamePacketListenerImpl.handleUseItem`、次に `DimensionDataStorage.readTagFromDisk`)。後処理の `LvtMatch` が slot を入れ替えるが frame を並べ替えない古い版だった(`main` / `ver/1.19.4` の版へ揃えた)のと、`--vars` が try-with-resources の一時変数(frame では top)を生きている slot に重ねるため。MOD 入りだと Mixin が frame を計算し直すので見えず、9-14 の検証(MOD 125 個)では通っていた。`--vars` を外すと MOD 入りの起動が `Environment` の直後で黙って止まる(戻した)。代わりに `tools/lvtmatch` の `FrameFix` を後処理に足し、全クラスの frame を `ClassWriter.COMPUTE_FRAMES` で計算し直す(`ClassWriter(reader, ...)` だと触っていないメソッドは元の bytes を写すので frame が残る。reader を渡さない)。2026-09-23 から、FrameFix は LvtMatch が書き換えたメソッドと LambdaMatch が並べ替えたクラスだけに絞った(全クラスでは javac が top と書いた死んだ slot にも型が入り、Mixin から見える局所変数が増えていた)。MOD 無し・MOD 125 個の両方で drive の一連が通る |
| 別の世界へのテレポート | drive の the_end への teleport → true、`Bukkit.getPlayer(uuid) == bot`、戻りも通る(1.21.11 の respawn は vanilla が新しい ServerPlayer を作るので、1.20.6 以前の restoreFrom の直しは要らない) |
| adventure の boss bar | Paper は `BossEvent` に欄 `adventure` を足して getter で先に見る。Shifu の getter は vanilla なので、`BossBarImplementationImpl` が送る packet は作ったときの名前・色、割合 1.0 のままになる形だった。listener で変わった値を `ServerBossEvent` の setter に写す(`io-papermc-paper-adventure-BossBarImplementationImpl.rules`)。drive の boss bar(0.25 BLUE → 0.75 / 名前 / RED)が bot に add → progress → name → style → remove の順で届く(MOD 125 個 + プラグイン 26 個の mix で確認) |
| nether / end の `serverLevelData` | vanilla では `DerivedLevelData` なので、CraftWorld の `PrimaryLevelData` への cast(worldGenOptions、settings.hardcore)は nether / end で落ちる形だった。`PrimaryLevelData` でなければサーバーの `WorldData` を読む(他の版と同じ) |

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

vanilla の行を書き換えてよいのは 4 種類で、`tools/verify_additive.py` が closure の最後に形を確かめる(2026-09-23 は形に収まらないものが 24 件残っていて、closure はそこで止まる)。
内訳は [ARCHITECTURE.md](ARCHITECTURE.md) の「vanilla の行に触っている 4 か所」。

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
入れていない。Bukkit が自分で登録するコマンド(`SimpleCommandMap` の構築子と `setFallbackCommands` が
登録するもの)は、`patches/adapter/org-bukkit-craftbukkit-command-CraftCommandMap.rules` で
`bukkit:` の付いた名前(`/bukkit:help` など)だけにしている。`/help` と `/reload` は vanilla のもの。

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
