# closure.sh の PowerShell 版(classic の並べ方だけ)。
#
#     powershell -File tools\closure.ps1 -Paper D:\.pw194 -JavaHome "C:\Program Files\Java\jdk-17" [-Rounds 10] [-Report]
#
# -Report は当たらなかった規則を並べて先へ進む。調べるときだけ使う。
# 付けたままビルドすると、当たらない規則を抱えたまま jar ができる。
#
# MSYS の bash は fork が枯れると途中で止まる(sh が子を持たないまま残る)。
# closure.sh は python と git の呼び出しが 1 周に数十回あって、そのたびに fork するので、
# 止まりやすい。ここでは PowerShell から直に呼ぶ。やることは closure.sh と同じ順。
# mache(1.21.4 以降)は closure.sh を使う。
# このファイルは BOM 付き UTF-8 で保存する。PowerShell 5.1 は BOM 無しを ANSI として読み、
# 日本語の注記の途中で引用符が壊れて parse error になる。
param(
    [Parameter(Mandatory = $true)][string] $Paper,
    [Parameter(Mandatory = $true)][string] $JavaHome,
    [int] $Rounds = 10,
    [switch] $Report,
    # Shifu が触ったファイルだけを vanilla の木から写す。Paper が持つだけのファイルは Paper のまま組む。
    # 1.19.4 の decompileJar の出力は、そのままでは通らないファイルが多い(変数名の重複、総称型の推論)。
    # 触っていないクラスは postcompile で公式のバイトコードに戻るので、組む元が Paper の版でも
    # 配布物には入らない。
    [switch] $TouchedOnly
)

$ErrorActionPreference = "Continue"
$Shifu = Split-Path -Parent $PSScriptRoot
$Tree = Join-Path $Paper "vanilla-src\minecraft\java"
$Sources = Join-Path $Paper "sources"
$PaperServer = Join-Path $Paper "Paper-Server"
$Adapter = Join-Path $PaperServer "src\main\java"
$Req = Join-Path $Shifu "docs\backlog\required-members.txt"
$Gap = Join-Path $Shifu "docs\backlog\vanilla-gap.txt"

$env:SHIFU_PAPER = $Paper
$env:SHIFU_TREE = $Tree
$env:SHIFU_SOURCES = $Sources
$env:JAVA_HOME = $JavaHome
$env:PYTHONIOENCODING = "utf-8"
$flags = @()
if ($Report) { $flags = @("--report") }

function Base($repo, $subject) {
    foreach ($line in (git -C $repo log --format='%H %s')) {
        if ($line.EndsWith(" $subject")) { return $line.Split(" ")[0] }
    }
    throw "$repo に $subject が無い"
}

$base = Base $Tree "paper Imports"
$basePaper = Base $PaperServer "Initial"
New-Item -ItemType Directory -Force (Join-Path $Shifu "docs\backlog") | Out-Null
if (-not (Test-Path $Req)) { New-Item -ItemType File $Req | Out-Null }

# python が SystemExit で止まっても、呼び出し側は次の対象へ進んでしまう。
# 1.18.2 の patches/decompile は 3 番目の対象で止まり、残り 40 件が当たらないまま
# コンパイルまで進んでいた。終了コードを見て、その場で止める。
function Py {
    param([string[]] $a)

    & python @a 2>&1 | ForEach-Object { "$_" }

    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $a[0]) $(Split-Path -Leaf $a[1]) が失敗した(exit $LASTEXITCODE)"
    }
}

$before = ""

for ($round = 1; $round -le $Rounds; $round++) {
    Set-Location $Tree
    # git の起動が「アクセスが拒否されました」で落ちることがある。戻せていない木に当て直すと
    # 追加が二重になって @Override が 4000 件重なるので、戻せたことを確かめてから進む。
    git reset --hard $base -q
    if ($LASTEXITCODE -ne 0) { throw "git reset が失敗した(round $round)" }
    git clean -fdq
    if (@(git status --porcelain).Count -ne 0) { throw "木が素に戻っていない(round $round)" }

    # Paper 側を素の状態に戻す(env.sh の shifu_reset_paper と同じ)
    git -C $PaperServer clean -qfd -- src/main/java
    git -C $PaperServer checkout -q -- src/main/java
    if ($LASTEXITCODE -ne 0) { throw "Paper-Server を戻せなかった(round $round)" }
    if ((git -C $PaperServer ls-tree -d $basePaper -- src/main/resources/data/minecraft/worldgen) -ne $null) {
        git -C $PaperServer checkout -q $basePaper -- src/main/resources/data/minecraft/worldgen
    }
    Remove-Item -Recurse -Force (Join-Path $Adapter "alternate") -ErrorAction SilentlyContinue

    Py @("$Shifu\tools\add_new_files.py", $Sources, ".") | Out-Null
    Remove-Item -Recurse -Force "$Shifu\tools\build\classic-shim" -ErrorAction SilentlyContinue
    Py @("$Shifu\tools\make_classic_shim.py", $Adapter, ".", "$Shifu\tools\build\classic-shim", $Req, "--write") | Select-Object -Last 2
    Py @("$Shifu\tools\apply_shim_adds.py", "$Shifu\tools\build\classic-shim", ".", "$Shifu\patches\hand", "$Shifu\patches\access") | Select-Object -Last 1
    Py (@("$Shifu\tools\widen_access.py", "$Shifu\patches\access", ".") + $flags)
    Py (@("$Shifu\tools\patch_adapter.py", "$Shifu\patches\narrow", ".") + $flags) | Select-Object -Last 1
    Py @("$Shifu\tools\apply_shim_adds.py", "$Shifu\patches\shim", ".", "$Shifu\patches\hand", "$Shifu\patches\access") | Select-Object -Last 1
    Py @("$Shifu\tools\apply_shim_adds.py", "$Shifu\patches\hand", ".") | Select-Object -Last 1
    Py (@("$Shifu\tools\patch_adapter.py", "$Shifu\patches\adapter", $Adapter) + $flags)

    New-Item -ItemType Directory -Force (Join-Path $Adapter "dev\shifu\event") | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $Adapter "dev\shifu\command") | Out-Null
    Copy-Item "$Shifu\src\event\java\dev\shifu\event\*.java" (Join-Path $Adapter "dev\shifu\event") -Force
    Copy-Item "$Shifu\src\event\java\dev\shifu\command\*.java" (Join-Path $Adapter "dev\shifu\command") -Force

    Py (@("$Shifu\tools\apply_events.py", "$Shifu\patches\anon", ".", "anon") + $flags)
    Py (@("$Shifu\tools\apply_events.py", "$Shifu\patches\wire", ".", "wire") + $flags)
    Py (@("$Shifu\tools\apply_events.py", "$Shifu\patches\events", ".") + $flags)
    Py (@("$Shifu\tools\patch_adapter.py", "$Shifu\patches\decompile", ".") + $flags) | Select-Object -Last 1
    Py (@("$Shifu\tools\patch_adapter.py", "$Shifu\patches\expr", ".") + $flags) | Select-Object -Last 1

    # 当て終わった木を、コンパイルするソースセットに写す(env.sh の shifu_stage_tree と同じ)
    $rels = @()
    if (-not $TouchedOnly) {
        $rels += @(git -C $PaperServer ls-files -- src/main/java/net src/main/java/com src/main/java/ca | ForEach-Object { $_.Substring("src/main/java/".Length) })
    }
    $rels += @(git -C $Tree status --porcelain | ForEach-Object { $_.Substring(3).Trim() })
    # Paper が書き換えているが vanilla のまま使うファイル。規則が触らなくても vanilla の木から写す
    $vanillaList = Join-Path $Shifu "patches\vanilla-files.txt"
    if (Test-Path $vanillaList) {
        $rels += @(Get-Content $vanillaList -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") })
    }
    $staged = 0
    foreach ($rel in ($rels | Sort-Object -Unique)) {
        $from = Join-Path $Tree $rel
        if (Test-Path $from -PathType Leaf) {
            $to = Join-Path $Adapter $rel
            New-Item -ItemType Directory -Force (Split-Path -Parent $to) | Out-Null
            Copy-Item $from $to -Force
            $staged++
        }
    }
    "staged $staged files"

    Set-Location $Paper
    $init = Join-Path $Shifu "tools\maxerrs.gradle"
    $prefix = ($PaperServer + "\src\")
    # PowerShell 5.1 の Out-File -Encoding utf8 は BOM を付ける。python が読む文書は BOM 無しで書く
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $lines = & .\gradlew.bat --no-daemon -I $init ":paper-server:compileJava" "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US" 2>&1 |
        ForEach-Object { "$_".Replace($prefix, "").Replace($prefix.Replace("\", "/"), "") }
    [System.IO.File]::WriteAllText($Gap, ($lines -join "`n") + "`n", $utf8)

    $count = @(Select-String -Path $Gap -Pattern "error:").Count
    "round ${round}: $count errors"

    # javac まで届かずに gradle が落ちると error: が 0 件になる。それは通ったのではない
    $ran = Select-String -Path $Gap -Pattern "Task :paper-server:compileJava" -Quiet
    $ok = Select-String -Path $Gap -Pattern "BUILD SUCCESSFUL" -Quiet
    if (-not $ran -or (-not $ok -and $count -eq 0)) {
        "GRADLE FAILED (javac は走っていない)"
        Select-String -Path $Gap -Pattern "What went wrong" -Context 0,2 | ForEach-Object { $_.Context.PostContext }
        break
    }

    if ($count -eq 0) { "COMPILED"; break }

    # 数ではなく中身で見る。宣言レベルの誤り(cannot find symbol: class)が 1 つあると javac は
    # 後ろの検査をしないので、それを直した次の周は数が跳ね上がる。同じ誤りの集合が 2 周続いたら止める
    $errorSet = (Select-String -Path $Gap -Pattern "error:" | ForEach-Object { $_.Line } | Sort-Object -Unique) -join "`n"
    if ($errorSet -eq $before) {
        if (Test-Path "$Req.bak") { Move-Item "$Req.bak" $Req -Force; "要求を 1 つ前に戻した" }
        "STALLED"
        break
    }

    $before = $errorSet
    Set-Location $Shifu
    Copy-Item $Req "$Req.bak" -Force
    $more = Py @("$Shifu\tools\required_members.py", $Gap, $Tree, $Adapter)
    [System.IO.File]::AppendAllText($Req, ($more -join "`n") + "`n", $utf8)
    $now = @(Select-String -Path $Req -Pattern "^    (method|variable|class|access|abstract) ").Count
    "round ${round}: required now $now"
}

# vanilla の行を決めた形でしか変えていないことを、不動点まで回したあとの木で確かめる
# (tools/verify_additive.py)。外れた行が 1 つでもあれば Py が throw して止まる。
# 2026-09-21 まではどこからも呼んでおらず、説明の付かない差分が 7〜56 件ある木のまま組んでいた。
Py @("$Shifu\tools\verify_additive.py", $Tree)
