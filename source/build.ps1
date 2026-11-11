param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Net35References,
    [string]$AndroidBuildDirectory,
    [switch]$RebuildNative
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set -JavaHome to JDK 17 or newer.' }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar'))) { throw 'Android SDK platform 36 is required for building.' }

if ($RebuildNative) {
    $ndk = Join-Path $AndroidSdk 'ndk\29.0.14206865\ndk-build.cmd'
    if (-not (Test-Path -LiteralPath $ndk)) { throw 'Install Android NDK 29.0.14206865 through the SDK manager.' }
    Push-Location (Join-Path $root 'third-party\hev')
    try {
        & $ndk NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk 'APP_ABI=arm64-v8a x86_64' -j4
        if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
        foreach ($abi in @('arm64-v8a','x86_64')) {
            $target = Join-Path $root "android\app\src\main\jniLibs\$abi"
            New-Item -ItemType Directory -Force -Path $target | Out-Null
            Copy-Item -LiteralPath "libs\$abi\libhev-socks5-tunnel.so" -Destination $target -Force
        }
    } finally { Pop-Location }
}

$androidProject = Join-Path $root 'android'
if (-not $AndroidBuildDirectory -and $androidProject -match '[^\x00-\x7f]') {
    $AndroidBuildDirectory = Join-Path $env:LOCALAPPDATA 'USBBridgeBuild\android-0.15.5'
}
if ($AndroidBuildDirectory) {
    if ($AndroidBuildDirectory -match '[^\x00-\x7f]') { throw 'Set -AndroidBuildDirectory to a path with ASCII characters; Android AIDL cannot build from a Cyrillic path on Windows.' }
    New-Item -ItemType Directory -Force -Path $AndroidBuildDirectory | Out-Null
    & robocopy $androidProject $AndroidBuildDirectory /E /XD .gradle build /NJH /NJS /NFL /NDL /NP
    if ($LASTEXITCODE -gt 7) { throw 'Could not stage Android sources' }
    $androidProject = $AndroidBuildDirectory
}
& (Join-Path $androidProject 'gradlew.bat') -p $androidProject :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }

$cache = Join-Path $root '.build'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
if (-not $Net35References) {
    $Net35References = Join-Path $cache 'net35\build\.NETFramework\v3.5'
    if (-not (Test-Path -LiteralPath (Join-Path $Net35References 'mscorlib.dll'))) {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $archive = Join-Path $cache 'net35.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://api.nuget.org/v3-flatcontainer/microsoft.netframework.referenceassemblies.net35/1.0.3/microsoft.netframework.referenceassemblies.net35.1.0.3.nupkg' -OutFile $archive
        Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $cache 'net35') -Force
    }
}
$output = Join-Path $root 'artifacts'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw 'The build PC needs the .NET Framework 4 C# compiler.' }
$sources = Get-ChildItem -LiteralPath (Join-Path $root 'windows') -Filter '*.cs' | ForEach-Object FullName
& $csc /nologo /noconfig /codepage:65001 /target:winexe /platform:x86 /optimize+ /nostdlib+ "/win32manifest:$root\windows\app.manifest" "/out:$output\USBBridge.exe" "/r:$Net35References\mscorlib.dll" "/r:$Net35References\System.dll" "/r:$Net35References\System.Drawing.dll" "/r:$Net35References\System.Windows.Forms.dll" $sources
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
Copy-Item -LiteralPath (Join-Path $root 'windows\USBBridge.exe.config') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $androidProject 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $output 'USBBridge-0.15.5.apk') -Force
Write-Output "Build complete: $output"

