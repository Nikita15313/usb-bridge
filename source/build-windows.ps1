param([string]$OutputDirectory,[string]$Net35References)
$ErrorActionPreference='Stop'
$bridgeRoot=$PSScriptRoot
if(-not $OutputDirectory){$OutputDirectory=Join-Path $bridgeRoot 'artifacts\windows-only'}
if(-not $Net35References){$Net35References=Join-Path $bridgeRoot '.build\net35\build\.NETFramework\v3.5'}
if(-not(Test-Path -LiteralPath (Join-Path $Net35References 'mscorlib.dll'))){throw 'The .NET 3.5 reference assemblies are required on the build computer.'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$bridgeCompiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
$bridgeSources=Get-ChildItem -LiteralPath (Join-Path $bridgeRoot 'windows') -Filter '*.cs' | ForEach-Object FullName
$bridgeExe=Join-Path $OutputDirectory 'USBBridge.exe'
& $bridgeCompiler /nologo /noconfig /codepage:65001 /target:winexe /platform:x86 /optimize+ /nostdlib+ "/win32manifest:$bridgeRoot\windows\app.manifest" "/out:$bridgeExe" "/r:$Net35References\mscorlib.dll" "/r:$Net35References\System.dll" "/r:$Net35References\System.Drawing.dll" "/r:$Net35References\System.Windows.Forms.dll" $bridgeSources
if($LASTEXITCODE -ne 0){throw 'Windows build failed'}
Copy-Item -LiteralPath (Join-Path $bridgeRoot 'windows\USBBridge.exe.config') -Destination $OutputDirectory -Force
Write-Output "Windows build complete: $bridgeExe"
