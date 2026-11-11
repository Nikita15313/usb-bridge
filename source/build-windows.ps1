param([string]$OutputDirectory,[string]$Net35References)
$ErrorActionPreference='Stop'
$bridgeSourceDirectory=$PSScriptRoot
if(-not $OutputDirectory){$OutputDirectory=Join-Path $bridgeSourceDirectory 'windows-build'}
if(-not $Net35References){$Net35References=Join-Path $bridgeSourceDirectory '.build\net35\build\.NETFramework\v3.5'}

# Use the legacy reference assemblies without installing or downloading anything.
$bridgeReferenceNames=@('mscorlib.dll','System.dll','System.Drawing.dll','System.Windows.Forms.dll')
$bridgeMissingReferences=@($bridgeReferenceNames | Where-Object {-not(Test-Path -LiteralPath (Join-Path $Net35References $_) -PathType Leaf)})
if($bridgeMissingReferences.Count -gt 0){throw ("Missing .NET 3.5 reference assemblies: {0}. Pass -Net35References with the local .NETFramework\v3.5 reference-assembly folder." -f ($bridgeMissingReferences -join ', '))}
$Net35References=(Resolve-Path -LiteralPath $Net35References).ProviderPath
if(-not $env:WINDIR){throw 'Build on Windows with the .NET Framework C# compiler available.'}
$bridgeCompiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
if(-not(Test-Path -LiteralPath $bridgeCompiler -PathType Leaf)){throw "C# compiler not found: $bridgeCompiler. Build on a Windows computer with the .NET Framework 4 compiler already available."}

$bridgeWindowsDirectory=Join-Path $bridgeSourceDirectory 'windows'
$bridgeManifest=Join-Path $bridgeWindowsDirectory 'app.manifest'
$bridgeConfig=Join-Path $bridgeWindowsDirectory 'USBBridge.exe.config'
$bridgeBrandIcon=Join-Path $bridgeSourceDirectory 'branding\usbbridge.ico'
foreach($bridgeRequiredFile in @($bridgeManifest,$bridgeConfig,$bridgeBrandIcon)){if(-not(Test-Path -LiteralPath $bridgeRequiredFile -PathType Leaf)){throw "Required source file not found: $bridgeRequiredFile. Use a complete source checkout."}}
$bridgeSources=@(Get-ChildItem -LiteralPath $bridgeWindowsDirectory -Filter '*.cs' | Sort-Object Name | ForEach-Object FullName)
if($bridgeSources.Count -eq 0){throw "No C# sources found in $bridgeWindowsDirectory. Use a complete source checkout."}
$bridgeOutputDirectory=(New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$bridgeExe=Join-Path $bridgeOutputDirectory 'USBBridge.exe'
$bridgeReferenceArguments=@($bridgeReferenceNames | ForEach-Object {"/r:$(Join-Path $Net35References $_)"})
& $bridgeCompiler /nologo /noconfig /codepage:65001 /target:winexe /platform:x86 /optimize+ /nostdlib+ "/win32manifest:$bridgeManifest" "/win32icon:$bridgeBrandIcon" "/resource:$bridgeBrandIcon,UsbBridge.BrandIcon" "/out:$bridgeExe" $bridgeReferenceArguments $bridgeSources
if($LASTEXITCODE -ne 0){throw "Windows compilation failed (exit code $LASTEXITCODE). Review the compiler errors above."}
Copy-Item -LiteralPath $bridgeConfig -Destination (Join-Path $bridgeOutputDirectory 'USBBridge.exe.config') -Force
Write-Output "Windows build complete: $bridgeExe"
