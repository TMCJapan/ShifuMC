# Shifu (紙布)

Fabric の MOD と Bukkit / Paper のプラグインを同時に動かす Minecraft サーバー。

名前は「紙(Paper)」と「布(Fabric)」から。

Lithium や Carpet のような Fabric MOD と、WorldEdit や EssentialsX のような Bukkit
プラグインを、1 つのサーバーに入れて同時に使える。ゲームの中身は逆コンパイルした
vanilla そのままで、プラグインを入れていなければ処理順まで vanilla と同じになる。

## 状態

検証段階。配布物は [Releases](https://github.com/TMCJapan/ShifuMC/releases) に
Minecraft の版ごとに置く。

| Minecraft | ブランチ | Java | MOD の名前空間 |
|---|---|---|---|
| 26.2 | `main` | 25 | mojmap(26.1 で難読化が無くなった) |
| 1.21.11 | `ver/1.21.11` | 21 以上 | intermediary |
| 1.20.6 | `ver/1.20.6` | 21 以上 | intermediary |
| 1.19.4 | `ver/1.19.4` | 21 以上 | intermediary |
| 1.18.2 | `ver/1.18.2` | 21 以上 | intermediary |

起動側(`shifu.jar`)は Java 21 向けに組んでいる。サーバーは `shifu.jar` を動かした Java で
動くので、1.18.2 / 1.19.4 も Java 21 以上で動かす。確かめた組み合わせは、1.18.2 と 1.19.4 が
Java 21、1.20.6 が Java 22、1.21.11 と 26.2 が Java 25。

各版で、プラグイン無しの起動、MOD とプラグインを同時に入れた起動(MOD 95〜126 個)、
bot を参加させてプラグインの操作(AuthMe、HuskHomes、EssentialsX、WorldEdit など)を
通す検査を回している。版ごとの数字と、動かないプラグインは [docs/STATUS.md](docs/STATUS.md)
(各ブランチのもの)にある。

## 要るもの

* Java(上の表)
* 空のフォルダ 1 つ

Minecraft のサーバー本体は入っていない。初回の起動時に Mojang の公式 server.jar を
取ってきて、手元で組み立てる。

## 動かし方

Releases から、使う版の `shifu.jar`(起動側、約 100 KB)と `shifu-server.jar`
(サーバー本体、35〜55 MB)を落として、空のフォルダに置く。
同じフォルダに `shifu.properties` を作り、次の 1 行を書く。

```
server-paperclip = shifu-server.jar
```

この行が無いと、起動側は Paper の公式ビルドを落として組み立てる。その場合は Shifu の
イベント発火層が入らない(下の「設定」)。

`eula.txt` も自分で書く。Minecraft の EULA に同意したことになるので、中身を確かめてから。

```
java -jar shifu.jar nogui
```

初回は次の順で進む。数分かかる。

1. Mojang の公式 server.jar を落として、Shifu のサーバーを組み立てる
2. Fabric Loader とその依存を取ってくる
3. 1.21.11 以前は、MOD の名前(intermediary)とサーバーの名前(mojmap)を繋ぐ。
   Mojang のマッピングと Fabric の intermediary を落とし、サーバーの jar を intermediary に
   写したものを作る(1 分ほど)
4. vanilla に寄せる設定を書く(`spigot.yml` と、1.19 以降は `config/paper-world-defaults.yml`、
   1.18.2 は `paper.yml`)
5. サーバーを起動する

`Done (...)` が出たら `127.0.0.1:25565` で入れる。

初回に `versions/` `libraries/` `cache/` `.shifu/` `world/` `logs/` ができる。
`shifu-server.jar` を新しいものに入れ替えたら、起動側が中身のハッシュで見分けて
組み立て直す(`versions/<版>/paper-<版>.jar.from` に記録している)。消す必要はない。
`shifu-server.jar` の版と `minecraft-version` が違うと、組み立てる前に止まる。

## 設定

`shifu.properties` が無ければ、初回の起動で次の内容で作る。書いていない鍵はこの既定値になる。

```
# 対象の Minecraft バージョン(既定は shifu.jar の版)
minecraft-version = 26.2

# Paper のビルド番号。latest でその時点の最新
paper-build = latest

# Shifu 自身のサーバー(paperclip 形式)。パスか URL
# 空にすると Paper 公式ビルドをそのまま組み立てる(イベント発火層は入らない)
server-paperclip =

# fabric-loader のバージョン
fabric-loader-version = 0.19.5

# Paper が vanilla から変えている挙動を、戻せる範囲で戻す
vanilla-parity = true

# サーバー JVM に渡す引数。空白を含む引数は引用符で囲める
jvm-args = -Xmx2G
```

Windows のパスはバックスラッシュのまま書ける(`server-paperclip = C:\server\shifu-server.jar`)。

`server-paperclip` を空にすると Paper の公式ビルドが組み上がる。この状態では
Shifu のイベント発火層が入らないので、プラグインは動くが MOD との同居は Shifu の
ものではなくなる。

`vanilla-parity` は、Paper がエンティティの活性化範囲や湧き上限を vanilla から
変えている分を設定で戻す。`server-paperclip` に Shifu 自身のサーバーを指定している
ときは、その設定を読む処理がサーバーに入っていないので効き目が無い。
`server-paperclip` を空にして Paper 公式ビルドを組み立てる使い方のための設定。
詳しくは [docs/VANILLA-PARITY.md](docs/VANILLA-PARITY.md)。

## MOD とプラグイン

MOD は `mods/`、プラグインは `plugins/` に置く。どちらも普通の Fabric サーバー・
Paper サーバーと同じ置き方でよい。MOD はその版の Fabric 向けのものをそのまま使う。

ブロックやバイオームを足す MOD を入れると、Fabric のレジストリ同期が素のクライアントを
弾く(「This server requires Fabric Loader and Fabric API installed on your client!」)。
入れるなら、遊ぶ側も Fabric のクライアントにする。最適化系の MOD
(Lithium、FerriteCore、Krypton、C2ME など)だけなら素のクライアントで入れる。

Spigot 向けに書かれたプラグイン(NMS を Spigot の名前で呼ぶもの)は、1.20.6 以降は Paper が、
1.19.4 と 1.18.2 は Shifu が、読み込むときに名前を写す。

独自の packet を使う MOD は、Fabric のクライアントを繋いだ確認をしていない。

## 自分でビルドする

`sh tools/dist.sh --build` で `tools/build/dist/` に `shifu.jar` と `shifu-server.jar` が出る。
環境の用意は [CONTRIBUTING.md](CONTRIBUTING.md) と [docs/DEVELOPING.md](docs/DEVELOPING.md)。

## 仕組みと開発

| | |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | 開発に参加する。環境、守ること、PR の前に通すもの |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | なぜこの作りなのか、層の分け方 |
| [docs/DEVELOPING.md](docs/DEVELOPING.md) | ビルドの回し方、追加の書き方、道具 |
| [docs/STATUS.md](docs/STATUS.md) | 測った数字 |
| [docs/VANILLA-PARITY.md](docs/VANILLA-PARITY.md) | vanilla との差と、戻している設定 |
| [docs/FOUNDATION-OPTIONS.md](docs/FOUNDATION-OPTIONS.md) | 土台の選択肢と、捨てた案の記録 |

## ライセンス

GPLv3。Paper (Bukkit / CraftBukkit / Spigot 由来) のライセンスを継承する。

Minecraft のバイナリは配布物に含まない。実行時に Mojang の公式 server.jar を取得して、
ユーザーのマシン上で合成する。Paper 由来のコードはサーバーの jar に入る(GPLv3)。
