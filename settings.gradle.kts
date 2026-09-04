rootProject.name = "shifu"

// ディレクトリ名は shifu-bootstrap のままだが、GameProvider とランチャの両方が入っている。
// 成果物は shifu-<version>.jar。
include("shifu-bootstrap")

// 発火層(src/event)の型チェックと IDE 用。組んだサーバーの jar が無ければ飛ばす。
// 本番のコンパイルは once.sh が Paper のツリーへ写して行う。
include("shifu-events")
