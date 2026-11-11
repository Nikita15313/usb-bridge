param(
    [string]$Go = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../tools/go/bin/go.exe'))),
    [string]$Ndk = 'C:/Users/volod/AppData/Local/Android/Sdk/ndk/29.0.14206865'
)
$ErrorActionPreference = 'Stop'
$nativeBuild = Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Force -Path $nativeBuild | Out-Null
$goRoot = (& $Go env GOROOT).Trim()
$replacement = @{}
# Match the official Android wrapper's BOOTTIME behavior through suspend.
# Use an overlay: never edit the shared Go installation.
foreach ($relative in @('runtime/sys_linux_amd64.s','runtime/time_linux_amd64.s','runtime/sys_linux_arm64.s')) {
    $original = Join-Path (Join-Path $goRoot 'src') $relative
    $patched = Join-Path $nativeBuild ([IO.Path]::GetFileName($relative))
    $source = [IO.File]::ReadAllText($original)
    if ($source -notmatch 'CLOCK_MONOTONIC') { throw "Go runtime clock definition changed: $relative" }
    $source = $source.Replace('$1, DI // CLOCK_MONOTONIC', '$7, DI // CLOCK_BOOTTIME')
    $source = $source.Replace('#define CLOCK_MONOTONIC 1', '#define CLOCK_MONOTONIC 7 // Android CLOCK_BOOTTIME')
    [IO.File]::WriteAllText($patched,$source)
    $replacement[$original] = $patched
}
$overlay = Join-Path $nativeBuild 'go-overlay.json'
@{Replace=$replacement} | ConvertTo-Json | Set-Content -LiteralPath $overlay -Encoding utf8
$env:GOOS = 'android'
$env:CGO_ENABLED = '1'
$env:CGO_LDFLAGS = '-Wl,-soname=libwg-go.so -Wl,-z,max-page-size=16384'
$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
Push-Location (Join-Path $PSScriptRoot 'libwg-go')
try {
    foreach ($target in @(@{abi='x86_64';go='amd64';clang='x86_64-linux-android24-clang.cmd'},@{abi='arm64-v8a';go='arm64';clang='aarch64-linux-android24-clang.cmd'})) {
        $env:GOARCH = $target.go
        $env:CC = Join-Path $toolchain $target.clang
        $destination = Join-Path (Join-Path $PSScriptRoot '../app/src/main/jniLibs') ($target.abi+'/libwg-go.so')
        & $Go build -tags linux -overlay $overlay -trimpath -buildvcs=false -ldflags '-s -w -buildid=' -buildmode c-shared -o $destination .
        if ($LASTEXITCODE -ne 0) { throw "Native build failed: $($target.abi)" }
        Get-FileHash -Algorithm SHA256 -LiteralPath $destination | Select-Object Hash,Path
    }
} finally { Pop-Location }
