# -*- coding: utf-8 -*-
"""ツールが使うパス。tools/env.sh と同じ既定値を返す。

環境変数で上書きできる。env.sh を通して呼べば、シェル側の設定がそのまま届く。

    SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
    SHIFU_BASE   vanilla の基点コミット  既定: 6d83d4b
"""

import os

SHIFU = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAPER = os.environ.get("SHIFU_PAPER") or os.path.join(os.path.dirname(SHIFU), ".pw")

TREE = os.path.join(PAPER, "paper-server", "src", "minecraft", "java")
SOURCES = os.path.join(PAPER, "paper-server", "patches", "sources")
RUN = os.path.join(PAPER, "run-shifu")

BASE = os.environ.get("SHIFU_BASE", "6d83d4b")
