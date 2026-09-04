# Shifu (紙布)

Fabric の MOD と Bukkit / Paper のプラグインを同時に動かす Minecraft サーバー。

名前は「紙(Paper)」と「布(Fabric)」から。

Lithium や Carpet のような Fabric MOD と、WorldEdit や EssentialsX のような Bukkit
プラグインを、1 つのサーバーに入れて同時に使える。ゲームの中身は逆コンパイルした
vanilla そのままで、プラグインを入れていなければ処理順まで vanilla と同じになる。

## 状態

検証段階。**配布用の jar はまだ公開していない。** 動かすには自分でビルドする
([docs/DEVELOPING.md](docs/DEVELOPING.md))。

対応するのは Minecraft 26.2 だけ。26.1 で Mojang が難読化をやめ、Fabric が
Intermediary の更新を止めたことで、MOD とプラグインが同じ名前空間を見るようになった。
この作りが成り立つのはそれ以降のバージョンだけになる。


## 要るもの

* Java 25
* 空のフォルダ 1 つ

Minecraft のサーバー本体は入っていない。初回の起動時に Mojang の公式 server.jar を
取ってきて、手元で組み立てる。

## 動かし方

`sh tools/dist.sh --build` で `tools/build/dist/` に 2 つ出る。
`shifu.jar`(起動側、70 KB)と `shifu-server.jar`(サーバー本体、63 MB)。
この 2 つを空のフォルダに置いて、

```
java -jar shifu.jar nogui
```

初回は次の順で進む。数分かかる。

1. Mojang の公式 server.jar を落として、Shifu のサーバーを組み立てる
2. Fabric Loader とその依存を取ってくる
3. vanilla に寄せる設定を書く(`spigot.yml`、`config/paper-world-defaults.yml`)
4. サーバーを起動する

`Done (...)` が出たら `127.0.0.1:25565` で入れる。

初回に `versions/` `libraries/` `cache/` `.shifu/` `world/` `logs/` ができる。
`shifu-server.jar` を新しいものに入れ替えたら、起動側が中身のハッシュで見分けて
組み立て直す(`versions/<版>/paper-<版>.jar.from` に記録している)。消す必要はない。

`eula.txt` は自分で書く。Minecraft の EULA に同意したことになるので、中身を確かめてから。

## 設定

初回の起動で `shifu.properties` ができる。

```
# 対象の Minecraft バージョン
minecraft-version = 26.2

# Paper のビルド番号。latest でその時点の最新
paper-build = latest

# Shifu 自身のサーバー(paperclip 形式)。パスか URL
# 空にすると Paper 公式ビルドをそのまま組み立てる(イベント発火層は入らない)
server-paperclip = shifu-server.jar

# fabric-loader のバージョン
fabric-loader-version = 0.19.3

# Paper が vanilla から変えている挙動を、戻せる範囲で戻す
vanilla-parity = true

# サーバー JVM に渡す引数
jvm-args = -Xmx4G
```

`server-paperclip` を空にすると Paper の公式ビルドが組み上がる。この状態では
Shifu のイベント発火層が入らないので、プラグインは動くが MOD との同居は Shifu の
ものではなくなる。

`vanilla-parity` は、Paper がエンティティの活性化範囲や湧き上限を vanilla から
変えている分を設定で戻す。`server-paperclip` に Shifu 自身のサーバーを指定している
ときは、そもそも Paper のパッチが入っていないので効き目が無い。
`server-paperclip` を空にして Paper 公式ビルドを組み立てる使い方のための設定。
詳しくは [docs/VANILLA-PARITY.md](docs/VANILLA-PARITY.md)。

サーバーの Java は `shifu.jar` を動かした Java と同じものが使われる。

## MOD とプラグイン

MOD は `mods/`、プラグインは `plugins/` に置く。どちらも普通の Fabric サーバー・
Paper サーバーと同じ置き方でよい。

ブロックやバイオームを足す MOD を入れると、Fabric のレジストリ同期が素のクライアントを
弾く(「This server requires Fabric Loader and Fabric API installed on your client!」)。
入れるなら、遊ぶ側も Fabric のクライアントにする。最適化系の MOD
(Lithium、FerriteCore、Krypton、C2ME など)だけなら素のクライアントで入れる。


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
