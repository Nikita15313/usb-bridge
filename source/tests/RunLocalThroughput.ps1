$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$ref=Join-Path $root '.build/net35/build/.NETFramework/v3.5'
$output=Join-Path $root 'artifacts/windows-regression/LocalThroughput.exe'
$sources=@((Join-Path $PSScriptRoot 'LocalThroughput.cs'),(Join-Path $root 'windows/Wire.cs'),(Join-Path $root 'windows/Relay.cs'),(Join-Path $root 'windows/DnsTransport.cs'))
& "$env:WINDIR/Microsoft.NET/Framework/v4.0.30319/csc.exe" /nologo /noconfig /codepage:65001 /target:exe /platform:x86 /optimize+ /nostdlib+ "/out:$output" "/r:$ref\mscorlib.dll" "/r:$ref\System.dll" $sources
if($LASTEXITCODE -ne 0){throw 'Local benchmark compilation failed'}
Copy-Item -LiteralPath (Join-Path $root 'windows/USBBridge.exe.config') -Destination ($output+'.config') -Force
& $output | Tee-Object -FilePath ($output+'.log')
if($LASTEXITCODE -ne 0){throw 'Local benchmark failed'}
