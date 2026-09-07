# -*- coding: utf-8 -*-
"""ツールが使うパス。tools/env.sh と同じ既定値を返す。

環境変数で上書きできる。env.sh を通して呼べば、シェル側の設定がそのまま届く。

    SHIFU_PAPER  Paper のクローン        既定: リポジトリの親ディレクトリの .pw
    SHIFU_BASE   vanilla の基点コミット  既定: クローンの履歴から探す
"""

import os
import subprocess

SHIFU = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAPER = os.environ.get("SHIFU_PAPER") or os.path.join(os.path.dirname(SHIFU), ".pw")

TREE = os.path.join(PAPER, "paper-server", "src", "minecraft", "java")
SOURCES = os.path.join(PAPER, "paper-server", "patches", "sources")
RUN = os.path.join(PAPER, "run-shifu")


def _base():
    """vanilla のインポートのコミット。

    paperweight は「デコンパイルした vanilla を入れる」コミットと
    「Paper のパッチを当てる」コミットを分けて積む。前者が基点。
    バージョンによってハッシュが変わるので、履歴から題名で探す。
    """
    if os.environ.get("SHIFU_BASE"):
        return os.environ["SHIFU_BASE"]

    try:
        log = subprocess.run(["git", "-C", TREE, "log", "--format=%H %s"],
                             capture_output=True, text=True, encoding="utf-8").stdout
    except OSError:
        return ""

    for line in log.split("\n"):
        if line.endswith("paper Imports"):
            return line.split(" ", 1)[0]

    return ""


BASE = _base()
