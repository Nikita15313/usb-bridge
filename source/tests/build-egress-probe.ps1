param(
 [string]$BuildDirectory='D:\Codex\Builds\USBBridgeEgressProbe015',
 [string]$OutputDirectory=(Join-Path $PSScriptRoot '../../v015-tests')
)
$ErrorActionPreference='Stop'
$taskSource=Join-Path $PSScriptRoot '../android'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\volod\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$taskGradle='C:\Users\volod\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat'
New-Item -ItemType Directory -Force -Path $BuildDirectory,$OutputDirectory,(Join-Path $BuildDirectory 'probe') | Out-Null
foreach($name in @('build.gradle','gradle.properties')){Copy-Item -LiteralPath (Join-Path $taskSource $name) -Destination (Join-Path $BuildDirectory $name) -Force}
@'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = 'Bridge Egress Probe'
include ':probe'
'@ | Set-Content -LiteralPath (Join-Path $BuildDirectory 'settings.gradle') -Encoding UTF8
Copy-Item -LiteralPath (Join-Path $taskSource 'probe/build.gradle') -Destination (Join-Path $BuildDirectory 'probe/build.gradle') -Force
Copy-Item -LiteralPath (Join-Path $taskSource 'probe/src') -Destination (Join-Path $BuildDirectory 'probe') -Recurse -Force
& $taskGradle -p $BuildDirectory :probe:assembleDebug '-PprobeApplicationId=local.usbbridge.egressprobe' '-PprobeLabel=Bridge Egress Test' --console=plain *> (Join-Path $OutputDirectory 'egress-probe-build.log')
if($LASTEXITCODE -ne 0){Get-Content -LiteralPath (Join-Path $OutputDirectory 'egress-probe-build.log') -Tail 60;throw 'Probe build failed'}
Copy-Item -LiteralPath (Join-Path $BuildDirectory 'probe/build/outputs/apk/debug/probe-debug.apk') -Destination (Join-Path $OutputDirectory 'egress-probe.apk') -Force
Get-FileHash -LiteralPath (Join-Path $OutputDirectory 'egress-probe.apk') -Algorithm SHA256
