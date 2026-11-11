param([string]$OutputDirectory='D:\Codex\usbbridge-router-vpn-probe')
$ErrorActionPreference='Stop'
$taskSdk='C:\Users\volod\AppData\Local\Android\Sdk'
$taskJdk='C:\Program Files\Android\Android Studio\jbr'
$classes=Join-Path $OutputDirectory 'classes'
$dex=Join-Path $OutputDirectory 'dex'
New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
& "$taskJdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$taskSdk\platforms\android-36\android.jar" -d $classes (Join-Path $PSScriptRoot 'RouterVpnProbe.java') (Join-Path $PSScriptRoot 'TestNetworkEgressProbe.java') (Join-Path $PSScriptRoot '../android/probe/src/main/java/local/usbbridge/probe/ProbeBody.java')
if($LASTEXITCODE -ne 0){throw 'javac failed'}
$taskClasses=@(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$taskJdk\bin\java.exe" -cp "$taskSdk\build-tools\36.1.0\lib\d8.jar" com.android.tools.r8.D8 --lib "$taskSdk\platforms\android-36\android.jar" --min-api 33 --output $dex @taskClasses
if($LASTEXITCODE -ne 0){throw 'd8 failed'}
$jar=Join-Path $OutputDirectory 'router-vpn-probe.jar'
& "$taskJdk\bin\jar.exe" --create --file $jar -C $dex classes.dex
if($LASTEXITCODE -ne 0){throw 'jar failed'}
Write-Output $jar
