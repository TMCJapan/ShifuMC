# run-server.sh の PowerShell 版(classic の並べ方だけ)。組んで、プラグイン無しで起動し、
# Done まで待って止める。closure.ps1 と同じく MSYS を通らない。
#
#     powershell -File tools\run-server.ps1 -Paper D:\.pw194 -JavaHome "C:\Program Files\Java\jdk-17" [-Seconds 60]
#
# このファイルは BOM 付き UTF-8 で保存する(PowerShell 5.1 は BOM 無しを ANSI として読む)。
param(
    [Parameter(Mandatory = $true)][string] $Paper,
    [Parameter(Mandatory = $true)][string] $JavaHome,
    [int] $Seconds = 60,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Continue"
$Shifu = Split-Path -Parent $PSScriptRoot
$Run = Join-Path $Paper "run-shifu"
$env:JAVA_HOME = $JavaHome
# 区切りが \ のままだと MSYS の sh が escape として読む
$env:SHIFU_PAPER = $Paper -replace '\\', '/'
$env:SHIFU_TREE = (Join-Path $Paper "vanilla-src\minecraft\java") -replace '\\', '/'
$env:PYTHONIOENCODING = "utf-8"
$java = Join-Path $JavaHome "bin\java.exe"

if (-not $SkipBuild) {
    Set-Location $Paper
    & .\gradlew.bat --no-daemon ":paper-server:compileJava" "-Dorg.gradle.jvmargs=-Xmx6G" 2>&1 | Select-String "BUILD|error:" | ForEach-Object { $_.Line }
    if ($LASTEXITCODE -ne 0) { throw "compileJava が失敗した(exit $LASTEXITCODE)" }

    # 後処理は tools/postcompile.sh の 1 か所だけに置く。ここに写しがあったころは、
    # ps1 で組んだ jar にだけ FrameFix と LvtMatch --vars が入らず、sh で組んだものと
    # 中身が違った。MSYS の sh は PATH に無いので、git の入っている場所から引く
    $sh = Join-Path (Split-Path -Parent (Split-Path -Parent (Get-Command git.exe).Source)) "bin\sh.exe"
    if (-not (Test-Path $sh)) { throw "MSYS の sh が見つからない: $sh" }
    & $sh (($Shifu -replace '\\', '/') + "/tools/postcompile.sh")
    if ($LASTEXITCODE -ne 0) { throw "postcompile.sh が失敗した(exit $LASTEXITCODE)" }

    Set-Location $Paper
    & .\gradlew.bat --no-daemon -I "$Shifu\tools\keepfields.gradle" ":createMojmapBundlerJar" -x ":paper-server:compileJava" "-Dorg.gradle.jvmargs=-Xmx6G -Duser.language=en -Duser.country=US" 2>&1 | Select-String "BUILD|error" | ForEach-Object { $_.Line }
    if ($LASTEXITCODE -ne 0) { throw "createMojmapBundlerJar が失敗した(exit $LASTEXITCODE)" }
}

$jar = Get-ChildItem (Join-Path $Paper "build\libs") -Filter "*bundler*.jar" | Where-Object { $_.Name -notmatch "reobf" } | Select-Object -First 1
if (-not $jar) { throw "bundler の jar が無い" }
"jar: $($jar.FullName)"

New-Item -ItemType Directory -Force $Run | Out-Null
Set-Location $Run
if (-not (Test-Path eula.txt)) { [System.IO.File]::WriteAllText("$Run\eula.txt", "eula=true`n") }
Remove-Item shifu.log -ErrorAction SilentlyContinue
$log4j = "file:///" + ((Join-Path $Shifu "tools\log4j2-sync.xml") -replace '\\', '/')
$server = Start-Process -FilePath $java -ArgumentList "-Xmx4G", "-Duser.language=en", "-Duser.country=US", "-Dlog4j.configurationFile=$log4j", "-jar", $jar.FullName, "--nogui" -WorkingDirectory $Run -RedirectStandardOutput "$Run\shifu.log" -RedirectStandardError "$Run\shifu.err" -PassThru -WindowStyle Hidden

$up = $false
for ($i = 0; $i -lt $Seconds; $i++) {
    if ((Test-Path "$Run\shifu.log") -and (Select-String -Path "$Run\shifu.log" -Pattern "Done \(" -Quiet)) { $up = $true; break }
    if ($server.HasExited) { break }
    Start-Sleep 1
}

"started: $up (after ${i}s)"
Start-Sleep 5
Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
"exceptions: " + @(Select-String -Path "$Run\shifu.log" -Pattern "Exception|ERROR" | Where-Object { $_.Line -notmatch "FabricLoader/Mixin" }).Count
Select-String -Path "$Run\shifu.log" -Pattern "Exception|ERROR|Done \(" | Select-Object -First 12 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) }
Get-Content "$Run\shifu.err" -Tail 5 -ErrorAction SilentlyContinue
