param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputDirectory = (Join-Path $env:TEMP 'USBBridge-Gateway-Tests'),
    [string]$LogPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
$compiler = Join-Path $JavaHome 'bin\javac.exe'
$runtime = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Set JavaHome to a JDK containing javac.exe.' }
$sourceRoot = Split-Path -Parent $PSScriptRoot
$android = Join-Path $sourceRoot 'android\app\src\main\java\local\usbbridge'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $compiler -encoding UTF-8 -d $OutputDirectory (Join-Path $android 'Wire.java') (Join-Path $android 'NetworkSocksGateway.java') (Join-Path $PSScriptRoot 'NetworkSocksGatewayTest.java')
if ($LASTEXITCODE -ne 0) { throw "Gateway test compilation failed ($LASTEXITCODE)." }
$result = & $runtime -cp $OutputDirectory local.usbbridge.NetworkSocksGatewayTest 2>&1
$testExit = $LASTEXITCODE
if ($LogPath) { $result | Set-Content -LiteralPath $LogPath -Encoding UTF8 }
$result | Write-Output
if ($testExit -ne 0) { throw "Gateway regression failed ($testExit)." }
