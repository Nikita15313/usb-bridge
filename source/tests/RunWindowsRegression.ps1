param([string]$Net35References,[ValidateSet('Auto','Clr2')][string]$Runtime='Auto',[string]$OutputDirectory,[string[]]$SuiteNames)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(-not $Net35References){$Net35References=Join-Path $root '.build\net35\build\.NETFramework\v3.5'}
$output=if($OutputDirectory){$OutputDirectory}else{Join-Path $root 'artifacts\windows-regression'}
New-Item -ItemType Directory -Path $output -Force | Out-Null
$compiler=Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
$suites=@{
 'NetworkPolicyTests'=@('NetworkCatalog.cs','UsbNetwork.cs','DnsTransport.cs','Wire.cs')
 'DnsTransportTests'=@('DnsTransport.cs','Wire.cs')
 'RelayUdpTests'=@('Relay.cs','DnsTransport.cs','Wire.cs')
 'RelayUdpBurstTests'=@('Relay.cs','DnsTransport.cs','Wire.cs')
 'RelayUdpIsolationTests'=@('Relay.cs','DnsTransport.cs','Wire.cs')
 'RelayDnsTests'=@('Relay.cs','DnsTransport.cs','Wire.cs')
 'ProtocolTests'=@('Wire.cs','Midi.cs')
 'AdbHelperTests'=@('AdbHelper.cs','TcpAdbHelper.cs')
 'TcpAdbHelperTests'=@('AdbHelper.cs','TcpAdbHelper.cs')
 'UsbNetworkTests'=@('UsbNetwork.cs','NetworkCatalog.cs','DnsTransport.cs','Wire.cs')
 'MuxPressureTests'=@('Wire.cs')
 'MuxCapacityTests'=@('Wire.cs')
 'TransferRateTests'=@('TransferRateSampler.cs')
 'ChannelReadTests'=@('Wire.cs')
 'UpdateTests'=@('UpdateManifest.cs','UpdateInstaller.cs','UpdateHttp.cs','UpdateClient.cs','DiagnosticLog.cs','AssemblyInfo.cs')
 'DesktopLifecycleTests'=@(Get-ChildItem -LiteralPath (Join-Path $root 'windows') -Filter '*.cs' | ForEach-Object Name)
}
foreach($suite in $suites.GetEnumerator()){
 if($SuiteNames -and $SuiteNames -notcontains $suite.Key){continue}
 $target=Join-Path $output ($suite.Key+'.exe')
 $sources=@(Join-Path $PSScriptRoot ($suite.Key+'.cs'))+@($suite.Value | ForEach-Object {Join-Path $root ('windows\'+$_)})
 $extra=@();if($suite.Key -eq 'DesktopLifecycleTests'){$extra=@('/main:DesktopLifecycleTests',"/r:$Net35References\System.Drawing.dll","/r:$Net35References\System.Windows.Forms.dll")}
 & $compiler /nologo /noconfig /codepage:65001 /target:exe /platform:x86 /optimize+ /nostdlib+ "/out:$target" "/r:$Net35References\mscorlib.dll" "/r:$Net35References\System.dll" $extra $sources
 if($LASTEXITCODE -ne 0){throw "$($suite.Key) compilation failed"}
 if($Runtime -eq 'Clr2'){
  [IO.File]::WriteAllText(($target+'.config'),'<configuration><startup><supportedRuntime version="v2.0.50727"/></startup></configuration>')
 }else{Copy-Item -LiteralPath (Join-Path $root 'windows\USBBridge.exe.config') -Destination ($target+'.config') -Force}
 if($suite.Key -eq 'UsbNetworkTests'){
  # Public test-only self-signed key. It is loaded locally and never trusted/installed.
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'untrusted-probe-fixture.pfx') -Destination (Join-Path $output 'untrusted-probe-fixture.pfx') -Force
 }
 if($suite.Key -eq 'UpdateTests'){
  & $target $target | Tee-Object -FilePath (Join-Path $output ($suite.Key+'.log'))
 }else{
  & $target | Tee-Object -FilePath (Join-Path $output ($suite.Key+'.log'))
 }
 if($LASTEXITCODE -ne 0){throw "$($suite.Key) failed"}
}
