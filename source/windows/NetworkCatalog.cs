using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Diagnostics;

namespace UsbBridge {
public delegate bool CableAlive();
public delegate bool UplinkVerifier(Adapter adapter,int budgetMs,CableAlive alive,out string reason);
public sealed class Adapter {
 public IPAddress Address,Dns,Gateway,Mask,DhcpServer;public int Index;public long DefaultMetric=-1;public string Id,Name,Description,DeviceEvidence;public bool UsbPhone,UsbCandidate,Preferred,Physical,Wired;
 public readonly List<IPAddress> DnsServers=new List<IPAddress>();
 // Set only on a selected snapshot after a successful bound DNS + HTTPS probe.
 public bool UplinkVerified;
 // Legacy CLR rejected TLS 1.2 before authentication. The Android HTTPS probe
 // must still succeed over this exact source before internet can be enabled.
 public bool UplinkCheckDeferred;
 public override string ToString(){if(String.IsNullOrEmpty(Description)||String.Equals(Name,Description,StringComparison.OrdinalIgnoreCase))return Name;return Name+"  ·  "+Description;}
}
public static class NetworkCatalog {
 public sealed class DefaultRoute {public IPAddress Gateway;public long Metric;}
 [DllImport("iphlpapi.dll")]static extern uint GetIpForwardTable(IntPtr table,ref int size,bool sorted);
 public static Dictionary<int,DefaultRoute> ParseDefaultRoutes(byte[] data){Dictionary<int,DefaultRoute> result=new Dictionary<int,DefaultRoute>();if(data==null||data.Length<4)return result;uint count=BitConverter.ToUInt32(data,0);if(count>(data.Length-4)/56)return result;for(int n=0;n<count;n++){int p=4+n*56;if(BitConverter.ToUInt32(data,p)!=0||BitConverter.ToUInt32(data,p+4)!=0||BitConverter.ToUInt32(data,p+20)!=4)continue;int index=BitConverter.ToInt32(data,p+16);long metric=BitConverter.ToUInt32(data,p+36);IPAddress gateway=new IPAddress(new byte[]{data[p+12],data[p+13],data[p+14],data[p+15]});DefaultRoute existing;if(!gateway.Equals(IPAddress.Any)&&(!result.TryGetValue(index,out existing)||metric<existing.Metric))result[index]=new DefaultRoute{Gateway=gateway,Metric=metric};}return result;}
 static Dictionary<int,DefaultRoute> DefaultRoutes(){IntPtr buffer=IntPtr.Zero;try{int size=0;if(GetIpForwardTable(IntPtr.Zero,ref size,false)!=122||size<4||size>1048576)return new Dictionary<int,DefaultRoute>();buffer=Marshal.AllocHGlobal(size);int allocated=size;if(GetIpForwardTable(buffer,ref size,false)!=0||size>allocated)return new Dictionary<int,DefaultRoute>();byte[] data=new byte[size];Marshal.Copy(buffer,data,0,size);return ParseDefaultRoutes(data);}catch{return new Dictionary<int,DefaultRoute>();}finally{if(buffer!=IntPtr.Zero)Marshal.FreeHGlobal(buffer);}}
 static readonly object knownGate=new object();static readonly Dictionary<string,bool> knownPhones=new Dictionary<string,bool>(StringComparer.OrdinalIgnoreCase);
 const string NetClass="{4d36e972-e325-11ce-bfc1-08002be10318}";
 // Read-only registry evidence survives translated/generic device labels. Failure
 // to read it is harmless: descriptions remain a fallback, never a route change.
 static string Evidence(string id){try{string pnp="";using(RegistryKey connection=Registry.LocalMachine.OpenSubKey("SYSTEM\\CurrentControlSet\\Control\\Network\\"+NetClass+"\\"+id+"\\Connection")){if(connection!=null)pnp=Convert.ToString(connection.GetValue("PnpInstanceID",""));}if(pnp.Length==0)return "";string evidence=pnp;using(RegistryKey device=Registry.LocalMachine.OpenSubKey("SYSTEM\\CurrentControlSet\\Enum\\"+pnp)){if(device!=null){evidence+=" "+Convert.ToString(device.GetValue("Service",""));string[] ids=device.GetValue("CompatibleIDs",new string[0]) as string[];if(ids!=null)evidence+=" "+String.Join(" ",ids);}}return evidence;}catch{return "";}}
 public static bool IsPhone(string identity,string evidence){string value=((identity??"")+" "+(evidence??"")).ToLowerInvariant();return value.Contains("rndis")||value.Contains("remote ndis")||value.Contains("usb8023")||value.Contains("usbncm")||System.Text.RegularExpressions.Regex.IsMatch(value,"(?:^|[^a-z])ncm(?:[^a-z]|$)")||value.Contains("oneplus")||value.Contains("android")||value.Contains("usb\\class_02&subclass_0d")||value.Contains("usb\\class_e0&subclass_01&prot_03");}
 public static bool IsUsbCandidate(string identity,string evidence,bool phone){string value=((identity??"")+" "+(evidence??"")).ToLowerInvariant();return phone||value.Contains("usb\\")||(value.Contains("usb")&&(value.Contains("ethernet")||value.Contains("network")||value.Contains("сетев")));}
 public static void RememberPhone(Adapter adapter){adapter.UsbPhone=true;if(!String.IsNullOrEmpty(adapter.Id))lock(knownGate)knownPhones[adapter.Id]=true;}
 [ComImport,Guid("DCB00000-570F-4A9B-8D69-199FDBA5723B"),InterfaceType(ComInterfaceType.InterfaceIsDual)]
 interface Manager { Networks GetNetworks(int flags);Network GetNetwork(Guid id);Connections GetNetworkConnections(); }
 [ComImport,Guid("DCB00003-570F-4A9B-8D69-199FDBA5723B"),InterfaceType(ComInterfaceType.InterfaceIsDual)]interface Networks {}
 [ComImport,Guid("DCB00002-570F-4A9B-8D69-199FDBA5723B"),InterfaceType(ComInterfaceType.InterfaceIsDual)]
 interface Network { [return:MarshalAs(UnmanagedType.BStr)]string GetName(); }
 [ComImport,Guid("DCB00005-570F-4A9B-8D69-199FDBA5723B"),InterfaceType(ComInterfaceType.InterfaceIsDual)]
 interface Connection { Network GetNetwork();[return:MarshalAs(UnmanagedType.VariantBool)]bool IsConnectedToInternet();[return:MarshalAs(UnmanagedType.VariantBool)]bool IsConnected();int GetConnectivity();Guid GetConnectionId();Guid GetAdapterId(); }
 [ComImport,Guid("DCB00006-570F-4A9B-8D69-199FDBA5723B"),InterfaceType(ComInterfaceType.InterfaceIsDual)]
 interface Connections { [return:MarshalAs(UnmanagedType.IUnknown)]object NewEnum();[PreserveSig]int Next(uint count,[MarshalAs(UnmanagedType.Interface)]out Connection item,out uint fetched); }
 static Dictionary<string,string> Names(){
  Dictionary<string,string> result=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);Manager manager=null;Connections list=null;
  try{manager=(Manager)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B")));list=manager.GetNetworkConnections();Connection item;uint count;
   while(list.Next(1,out item,out count)==0&&count==1){Network network=null;try{network=item.GetNetwork();result[item.GetAdapterId().ToString("B")]=network.GetName();}finally{if(network!=null)Marshal.ReleaseComObject(network);Marshal.ReleaseComObject(item);}}
  }catch{}finally{if(list!=null)Marshal.ReleaseComObject(list);if(manager!=null)Marshal.ReleaseComObject(manager);}return result;
 }
 static IPAddress DefaultSource(){try{using(Socket socket=new Socket(AddressFamily.InterNetwork,SocketType.Dgram,ProtocolType.Udp)){socket.Connect(new IPEndPoint(IPAddress.Parse("1.1.1.1"),53));return ((IPEndPoint)socket.LocalEndPoint).Address;}}catch{return null;}}
 public static List<Adapter> All(){
  List<Adapter> result=new List<Adapter>();Dictionary<string,string> names=Names();Dictionary<int,DefaultRoute> routes=DefaultRoutes();IPAddress preferred=DefaultSource();
  foreach(NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())try{
   if(ni.OperationalStatus!=OperationalStatus.Up||ni.NetworkInterfaceType==NetworkInterfaceType.Loopback)continue;IPInterfaceProperties p=ni.GetIPProperties();
   string identity=(ni.Description+" "+ni.Name).ToLowerInvariant(),evidence=Evidence(ni.Id);bool phone=IsPhone(identity,evidence);lock(knownGate)phone=phone||knownPhones.ContainsKey(ni.Id);
   string profile;names.TryGetValue(ni.Id,out profile);string label=String.IsNullOrEmpty(profile)?ni.Name:String.Equals(profile,ni.Name,StringComparison.OrdinalIgnoreCase)?profile:profile+" — "+ni.Name;
   foreach(UnicastIPAddressInformation ip in p.UnicastAddresses)if(ip.Address.AddressFamily==AddressFamily.InterNetwork&&!ip.Address.ToString().StartsWith("169.254.")){
    Adapter a=new Adapter();a.Id=ni.Id;a.Name=label;a.Address=ip.Address;a.Mask=ip.IPv4Mask;a.Index=p.GetIPv4Properties().Index;a.Description=ni.Description;a.DeviceEvidence=evidence;a.UsbPhone=phone;a.UsbCandidate=IsUsbCandidate(identity,evidence,phone);a.Preferred=a.Address.Equals(preferred);a.Physical=(ni.NetworkInterfaceType==NetworkInterfaceType.Ethernet||ni.NetworkInterfaceType==NetworkInterfaceType.Wireless80211)&&!identity.Contains("virtual")&&!identity.Contains("hyper-v")&&!identity.Contains("tap-windows")&&!identity.Contains("sing-tun")&&!identity.Contains("wintun")&&!identity.Contains("wireguard")&&!identity.Contains("vpn")&&!identity.Contains("hamachi");a.Wired=a.Physical&&ni.NetworkInterfaceType==NetworkInterfaceType.Ethernet;
    foreach(IPAddress dns in p.DnsAddresses)if(dns.AddressFamily==AddressFamily.InterNetwork&&!dns.Equals(IPAddress.Any)&&!a.DnsServers.Contains(dns)){a.DnsServers.Add(dns);if(a.Dns==null)a.Dns=dns;}
    foreach(GatewayIPAddressInformation gw in p.GatewayAddresses)if(gw.Address.AddressFamily==AddressFamily.InterNetwork&&!gw.Address.Equals(IPAddress.Any)){a.Gateway=gw.Address;break;}DefaultRoute route;if(routes.TryGetValue(a.Index,out route)){a.DefaultMetric=route.Metric;if(a.Gateway==null)a.Gateway=route.Gateway;}foreach(IPAddress server in p.DhcpServerAddresses)if(server.AddressFamily==AddressFamily.InterNetwork&&!server.Equals(IPAddress.Any)){a.DhcpServer=server;break;}result.Add(a);
   }
  }catch{}return result;
 }
 public static Adapter Choose(List<Adapter> choices){foreach(Adapter a in choices)if(!a.UsbPhone&&a.Preferred)return a;Adapter physical=ChoosePhysical(choices);if(physical!=null)return physical;foreach(Adapter a in choices)if(!a.UsbPhone&&a.Gateway!=null)return a;return null;}
 public static Adapter ChoosePhysical(List<Adapter> choices){foreach(Adapter a in choices)if(!a.UsbPhone&&a.Physical&&a.Preferred&&a.Gateway!=null)return a;foreach(Adapter a in choices)if(!a.UsbPhone&&a.Wired&&a.Gateway!=null)return a;foreach(Adapter a in choices)if(!a.UsbPhone&&a.Physical&&a.Gateway!=null)return a;return null;}
 public static bool OnLink(Adapter adapter,IPAddress peer){if(peer==null||adapter.Address==null||peer.AddressFamily!=AddressFamily.InterNetwork||peer.Equals(adapter.Address)||peer.Equals(IPAddress.Any)||peer.Equals(IPAddress.Broadcast)||IPAddress.IsLoopback(peer))return false;byte[] address=adapter.Address.GetAddressBytes(),target=peer.GetAddressBytes();if(target[0]>=224)return false;if(adapter.Mask==null)return peer.Equals(adapter.Gateway)||peer.Equals(adapter.DhcpServer);byte[] mask=adapter.Mask.GetAddressBytes();if(mask.Length!=4)return false;bool anyMask=false,allHost=true,zeroHost=true;for(int i=0;i<4;i++){anyMask|=mask[i]!=0;if((address[i]&mask[i])!=(target[i]&mask[i]))return false;int host=target[i]&(~mask[i]&255);allHost&=host==(~mask[i]&255);zeroHost&=host==0;}return anyMask&&!allHost&&!zeroHost;}
 public static List<IPAddress> Peers(Adapter adapter){List<IPAddress> peers=new List<IPAddress>();foreach(IPAddress address in new IPAddress[]{adapter.Gateway,adapter.DhcpServer,adapter.Dns})if(OnLink(adapter,address)&&!peers.Contains(address))peers.Add(address);return peers;}
 public static List<Adapter> UsbNetworks(List<Adapter> choices){List<Adapter> result=new List<Adapter>();foreach(Adapter adapter in choices)if(adapter.UsbPhone&&Peers(adapter).Count>0)result.Add(adapter);foreach(Adapter adapter in choices)if(!adapter.UsbPhone&&adapter.UsbCandidate&&Peers(adapter).Count>0)result.Add(adapter);return result;}
 public static Adapter Phone(List<Adapter> choices){foreach(Adapter a in choices)if(a.UsbPhone&&Peers(a).Count>0)return a;return null;}
 public static Adapter Find(List<Adapter> choices,IPAddress address){foreach(Adapter a in choices)if(a.Address.Equals(address))return a;return null;}
 public static bool SameInterface(Adapter a,Adapter b){if(a==null||b==null)return false;if(Object.ReferenceEquals(a,b))return true;if(!String.IsNullOrEmpty(a.Id)&&!String.IsNullOrEmpty(b.Id))return String.Equals(a.Id,b.Id,StringComparison.OrdinalIgnoreCase);return a.Index>0&&b.Index>0&&a.Index==b.Index;}
 public static List<IPAddress> ConfiguredDns(Adapter adapter){List<IPAddress> result=new List<IPAddress>();if(adapter==null)return result;if(adapter.Dns!=null)result.Add(adapter.Dns);foreach(IPAddress dns in adapter.DnsServers)if(!result.Contains(dns))result.Add(dns);result.RemoveAll(delegate(IPAddress dns){return dns.AddressFamily!=AddressFamily.InterNetwork||dns.Equals(IPAddress.Any)||dns.GetAddressBytes()[0]>=224;});return result;}
 public static bool IsUsableUplink(Adapter adapter,List<Adapter> choices,Adapter cable){
  if(adapter==null||adapter.UsbPhone||adapter.Address==null||adapter.Address.AddressFamily!=AddressFamily.InterNetwork||adapter.Address.Equals(IPAddress.Any)||IPAddress.IsLoopback(adapter.Address)||SameInterface(adapter,cable))return false;
  foreach(Adapter other in choices)if(other.UsbPhone&&SameInterface(adapter,other))return false;
  return (adapter.Preferred||adapter.Gateway!=null||adapter.DefaultMetric>=0)&&ConfiguredDns(adapter).Count>0;
 }
 public static List<Adapter> UplinkCandidates(List<Adapter> choices,IPAddress requestedAddress,Adapter cable){
  List<Adapter> result=new List<Adapter>();foreach(Adapter adapter in choices)if((requestedAddress==null||adapter.Address.Equals(requestedAddress))&&IsUsableUplink(adapter,choices,cable))result.Add(adapter);
  // An IP alone cannot distinguish interfaces with overlapping address ranges.
  // Do not silently reinterpret a manual source as a different interface.
  if(requestedAddress!=null){for(int i=1;i<result.Count;i++)if(!SameInterface(result[0],result[i]))return new List<Adapter>();return result;}
  result.Sort(delegate(Adapter a,Adapter b){int order=b.Preferred.CompareTo(a.Preferred);if(order!=0)return order;order=b.Physical.CompareTo(a.Physical);if(order!=0)return order;long am=a.DefaultMetric<0?Int64.MaxValue:a.DefaultMetric,bm=b.DefaultMetric<0?Int64.MaxValue:b.DefaultMetric;order=am.CompareTo(bm);if(order!=0)return order;order=b.Wired.CompareTo(a.Wired);if(order!=0)return order;return String.Compare(a.Name,b.Name,StringComparison.OrdinalIgnoreCase);});return result;
 }
 public static bool RequiresUplinkVerification(Adapter adapter,List<Adapter> choices){return adapter==null||PhoneOwnsDefault(choices)||!adapter.Preferred||adapter.UsbCandidate;}
 static Adapter Snapshot(Adapter a,IPAddress dns){Adapter copy=new Adapter{Address=a.Address,Dns=dns,Gateway=a.Gateway,Mask=a.Mask,DhcpServer=a.DhcpServer,Index=a.Index,DefaultMetric=a.DefaultMetric,Id=a.Id,Name=a.Name,Description=a.Description,DeviceEvidence=a.DeviceEvidence,UsbPhone=a.UsbPhone,UsbCandidate=a.UsbCandidate,Preferred=a.Preferred,Physical=a.Physical,Wired=a.Wired};copy.DnsServers.AddRange(a.DnsServers);return copy;}
 public static bool StillAvailable(Adapter pinned,List<Adapter> choices,Adapter cable){if(pinned==null)return false;foreach(Adapter adapter in choices)if(adapter.Address.Equals(pinned.Address)&&SameInterface(adapter,pinned)&&IsUsableUplink(adapter,choices,cable)&&ConfiguredDns(adapter).Contains(pinned.Dns))return true;return false;}
 public static Adapter SelectVerifiedUplink(List<Adapter> choices,IPAddress requestedAddress,Adapter cable,int budgetMs,CableAlive alive,UplinkVerifier verifier,out string reason){
  reason="У компьютера нет независимого подключения с настроенным DNS";List<Adapter> candidates=UplinkCandidates(choices,requestedAddress,cable);List<Adapter> attempts=new List<Adapter>();foreach(Adapter adapter in candidates){int dnsCount=0;foreach(IPAddress dns in ConfiguredDns(adapter)){// Only configured DNS from this interface; never borrow the USB phone resolver.
    bool phoneOnly=false;foreach(Adapter other in choices)if(other.UsbPhone&&!SameInterface(adapter,other)&&!OnLink(adapter,dns)&&(dns.Equals(other.Gateway)||dns.Equals(other.Address)))phoneOnly=true;if(!phoneOnly&&dnsCount++<2)attempts.Add(Snapshot(adapter,dns));}}
  if(!alive()){reason="Подключение отменено";return null;}
  // The ordinary Windows source needs no startup probe. A USB takeover,
  // uncertain USB adapter, alternate interface or alternate DNS does.
  if(attempts.Count>0&&candidates.Count>0&&SameInterface(attempts[0],candidates[0])&&attempts[0].Address.Equals(candidates[0].Address)&&attempts[0].Dns.Equals(candidates[0].Dns)&&!RequiresUplinkVerification(candidates[0],choices)){reason="Источник Windows: "+attempts[0].Name;return attempts[0];}
  Stopwatch elapsed=Stopwatch.StartNew();int limit=Math.Max(1,Math.Min(8000,budgetMs));for(int i=0;i<attempts.Count;i++){
   if(!alive()){reason="Подключение отменено";return null;}int remaining=limit-(int)elapsed.ElapsedMilliseconds;if(remaining<=0){reason="Проверка подключения ПК не завершилась вовремя";return null;}
   int allowance=Math.Min(remaining,Math.Max(800,remaining/(attempts.Count-i)));string failure="";bool verified=false,deferred=false;try{verified=verifier(attempts[i],allowance,alive,out failure);}catch(Tls12UnavailableException e){deferred=true;failure=e.Message;}catch(Exception e){failure=e.Message;}
   if(!alive()){reason="Подключение отменено";return null;}if(verified&&elapsed.ElapsedMilliseconds<=limit){attempts[i].UplinkVerified=true;reason="Проверено через "+attempts[i].Name;return attempts[i];}
   if(deferred&&elapsed.ElapsedMilliseconds<=limit){attempts[i].UplinkCheckDeferred=true;reason="Источник закреплён: "+attempts[i].Name+". "+failure;return attempts[i];}reason=String.IsNullOrEmpty(failure)?"Подключение ПК не прошло проверку":failure;
  }return null;
 }
 public static bool PhoneOwnsDefault(List<Adapter> choices){long independent=Int64.MaxValue,phone=Int64.MaxValue;foreach(Adapter a in choices){if(a.UsbPhone&&a.Preferred)return true;if(a.DefaultMetric<0)continue;if(a.UsbPhone)phone=Math.Min(phone,a.DefaultMetric);else if(a.Physical)independent=Math.Min(independent,a.DefaultMetric);}return phone!=Int64.MaxValue&&phone<=independent;}
 // Auto probes an already available USB network before MIDI. It never enables
 // tethering, installs drivers, or rewrites Windows routes/DNS on the user's behalf.
 public static bool CanUseUsbNetwork(int mode,List<Adapter> choices){return mode!=1&&UsbNetworks(choices).Count>0;}
 public static string Describe(List<Adapter> choices){System.Text.StringBuilder text=new System.Text.StringBuilder();foreach(Adapter a in choices)text.AppendLine(a.Name+" | address="+a.Address+" | mask="+a.Mask+" | gateway="+a.Gateway+" | dhcp="+a.DhcpServer+" | dns="+a.Dns+" | index="+a.Index+" | usb="+a.UsbPhone+" | usbCandidate="+a.UsbCandidate+" | default="+a.Preferred+" | defaultMetric="+a.DefaultMetric+" | device="+a.DeviceEvidence);return text.ToString();}
}
public static class UsbPair {
 // Only used on MIDI, a detected USB network, or an explicit loopback test.
 // Physical access to the USB host is the trust boundary, not a four-digit PIN.
 public static string Connect(System.IO.Stream stream){byte[] hello=System.Text.Encoding.ASCII.GetBytes("USBBR05!");stream.Write(hello,0,hello.Length);stream.Flush();return BitConverter.ToString(Wire.Read(stream,32)).Replace("-","");}
}
}
