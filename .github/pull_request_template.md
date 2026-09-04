## 何を変えたか

<!-- 1〜3 行。どこに何を足したか。 -->

## なぜ

<!-- 直したい挙動、または動かなかった MOD / プラグイン。 -->

## vanilla の行

- [ ] 触っていない
- [ ] 触った(`patches/access` / `patches/decompile` / `patches/expr` のどれか)
- [ ] 触った(上の 3 つ以外。理由を下に書く)

<!-- 3 つ以外の場合、なぜ追加では届かないかを書く。 -->

## 通したもの

- [ ] `sh tools/test-all.sh`
- [ ] `python tools/check_events.py`
- [ ] `sh tools/once.sh`(エラー 0)
- [ ] `python tools/verify_additive.py`
- [ ] 発火を足した場合: `sh tools/player-events.sh` で届くことを確認
- [ ] 局所変数やラムダを増やしていない(`check_extra_locals.py` / `check_lambdas.py`)

## 他の実装を読んだ場合

<!-- 読んだ位置を `repo@tag path/To/File.java:行` の形で残す。推測で書かない。 -->
