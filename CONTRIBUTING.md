# 開発に参加する

Shifu は「vanilla と完全に同じ挙動」と「プラグインが動く」を両方満たすことを条件にしている。
この 2 つを壊さないための決まりが多いので、手を動かす前にここを読んでほしい。

作りの説明は [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、
道具と手順は [docs/DEVELOPING.md](docs/DEVELOPING.md)。

## 前提の環境

| | |
|---|---|
| OS | Windows + Git Bash (MSYS) |
| JDK | 25 |
| Python | 3.12 |
| ディスク | Paper のクローンと逆コンパイルで 20 GB ほど |

`tools/*.sh` は `cygpath` とセミコロン区切りのクラスパスを使うので、いまのところ
Windows + MSYS でしか動かない。Linux や macOS で動かせるようにするのは未着手。
`shifu-bootstrap`(起動側)だけは素の Gradle で組めるので、そこは OS を選ばない。

## 最初の 1 回

```
git clone <このリポジトリ>
cd Shifu
export JAVA_HOME=/c/Program\ Files/Eclipse\ Adoptium/jdk-25.0.3.9-hotspot

sh tools/setup.sh     # Paper のクローンと逆コンパイル(10〜30 分)
sh tools/closure.sh   # 不動点まで回してコンパイル
sh tools/run-server.sh
```

当たった結果は `$PW/paper-server/src/minecraft/java` に出る。ここは vanilla の基点
コミットを `HEAD` に持つ git リポジトリなので、`git diff` で Shifu の追加が全部読める
(読み方は [docs/DEVELOPING.md](docs/DEVELOPING.md) の「当たった結果を読む」)。

`tools/setup.sh` は Paper をリポジトリの隣(`../.pw`)に clone する。別の場所に置きたい
ときは `SHIFU_PAPER` を設定するか、`tools/env.local.sh`(git 管理外)に書く。

```sh
# tools/env.local.sh
PW=/e/paper
```

パスを直書きしない。`tools/*.sh` は `tools/env.sh` を読み、Python 側は
`tools/paths.py` を読む。新しいツールを足すときも同じにする。

Windows の git は既定で `core.filemode = false` なので、新しく足したスクリプトの
実行ビットが記録されない。`./gradlew` を Linux の CI で走らせたときに
`exit code 126` で落ちて分かった。`.sh` を足したら手で立てる。

```
git update-index --chmod=+x tools/<名前>.sh
```

## 譲れない 2 条件

1. **挙動が vanilla と完全に一致する。** 表面上ではなく、処理順とアルゴリズムまで
2. **プラグインが動く**

1 のために、vanilla の行を書き換えない。触ってよいのは 3 種類だけで、
どれも実行される命令列を変えない([docs/VANILLA-PARITY.md](docs/VANILLA-PARITY.md))。

| | 何をしてよいか |
|---|---|
| `patches/access` | 宣言の可視性を広げる。修飾子 1 語だけ |
| `patches/decompile` | 逆コンパイラが消した局所変数を戻す |
| `patches/expr` | 式の末尾に発火を足す |

これ以外の形で vanilla の行に触る必要が出たら、書く前に issue を立てて相談する。
`python tools/verify_additive.py` が形を機械で確かめる。

## 守ること

**イベントは登録が無ければ発火させない。** 判定は `HandlerList` の長さを見るだけにする。
イベントオブジェクトを先に作らない。囲む形で重い式が引数に入るときは、
`silentXxx()` を先に見て短絡させる。

**差し込みで、公式にもある型の局所変数を作らない。** MixinExtras の `@Local` は
「その型がちょうど 1 つ」を要求する。増やすと MOD の mixin が外れる。値は欄か
発火層のメソッドの中に置く。確認は `python tools/check_extra_locals.py`。

**差し込みでラムダを増やさない。** `lambda$<メソッド>$<番号>` の番号が繰り下がって、
名前で狙う mixin が外れる。確認は `python tools/check_lambdas.py`。

**手書きは `patches/hand` に置く。** `patches/shim` は `write_members.py` が上書きする。

**アンカーは 1 か所に定まること。** 見つからない・2 つ以上あるときは失敗させる。
確認は `python tools/check_events.py`。

**通らなかった規則を黙って消さない。** `settle_events.sh` がコンパイラの言い分ごと
`docs/backlog/events-dropped.txt` に残す。

**他の MOD やライブラリの仕様を推測で書かない。** 依存しているバージョンのタグを開いて
該当箇所を読み、読んだ位置(`repo@tag path/To/File.java`)をコメントか PR に残す。

**MOD やプラグインごとの個別対応をしない。** 動かないものが出たら、その 1 つを直すのではなく
仕組みで塞ぐ。素の Fabric サーバーで動くものは Shifu でも動くべき、を基準にする。

## PR を出す前に

```
sh tools/test-all.sh          # 生成器の単体テストとシェルの構文(数秒)
python tools/check_events.py  # 規則のアンカーが 1 か所に定まるか(数秒)
sh tools/once.sh              # ツリーに当ててコンパイル(6 分)
python tools/verify_additive.py  # vanilla の行に触っていないか
```

発火や配線を足したときは、bot を繋いで届くことまで見る。

```
sh tools/run-server.sh
sh tools/player-events.sh
```

`tools/test-all.sh` は CI でも走る。Paper のクローンが要らないものだけを入れてある。

## リポジトリの構成

| | ファイル | 出どころ |
|---|---|---|
| `patches/hand` | 88 | 手書き。Paper のパッチから取れなかった宣言 |
| `patches/events/*.rules` | 12 | 手書き。vanilla の行が消えている発火 |
| `patches/events/generated` | 197 | 生成 `make_events.py` |
| `patches/shim` | 104 | 生成 `write_members.py`(上書きされる) |
| `patches/adapter` | 39 | 手書き。アダプタ層の書き換え |
| `patches/wire` | 21 | 手書き。`null-fields.rules` だけ `make_wires.py` の生成 |
| `patches/anon` | 5 | 手書き。無名クラスへの差し込み |
| `patches/access` | 1 | 手書き。可視性 |
| `patches/decompile` | 1 | 生成 `make_decompile_rules.py` |
| `patches/expr` | 1 | 手書き |
| `src/event` | 11 | 手書き。イベント発火層 |
| `shifu-bootstrap` | 18 | 手書き。起動側と Fabric の GameProvider |
| `tools` | 65 | 手書き。生成器・検証・測定 |
| `docs` | 5 | 手書き |

生成物もリポジトリに入れてある。作り直すには Paper のクローンと、
`settle_events.sh` で 8 ラウンドぶんのコンパイルが要るため。

`docs/backlog/` はツールが毎回上書きする出力なので入っていない。
`test/` は手で動かすためのサーバーの置き場で、これも入っていない。

## ブランチとコミット

`main` に直接 push しない。ブランチを切って PR を出す。

コミットは論点ごとに分ける。生成物の更新(`patches/shim`、`patches/events/generated`)は、
手で書いた変更と分けると差分が読める。

## 相談したほうがよいもの

- vanilla の行を、上の 3 種類以外の形で触る必要が出たとき
- Paper のブロックキャプチャ(`Level.captureBlockStates`)を入れたくなったとき。
  更新順が変わるので、いまは入れないことにしてある
- MOD やプラグイン 1 つのために例外を作りたくなったとき
