---
name: vanilla と挙動が違う
about: プラグインを入れていない状態で、vanilla と違う結果になる
labels: parity
---

## 何が違うか

<!-- vanilla でどうなり、Shifu でどうなるか。 -->

## 再現のしかた

<!-- シード、座標、手順。同じ結果が出る形で。 -->

## 測ったか

vanilla は同じものを 2 回走らせても結果が揺れる。同じもの同士のばらつきより
大きいかどうかで見る。

- [ ] vanilla を 2 回走らせて、ばらつきを取った
- [ ] そのばらつきより大きい差だった

ワールドの比較は `tools/compare_worlds.py`、tick 単位は `tools/compare-ticks.sh`。

## 原因の見当

<!-- 分かっていれば。どの差し込みか、どのアダプタ層か。 -->

## 環境

- Minecraft / Paper:
- Shifu のコミット:
