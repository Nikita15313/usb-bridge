using System;
using System.Collections.Generic;
using System.Net;
using System.Threading;
using UsbBridge;

class NetworkPolicyTests {
 static int count;
 static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);count++;}
 static Adapter Adapter(string name,string ip,bool phone,bool preferred,bool physical,bool wired,string gateway){return new Adapter{Name=name,Address=IPAddress.Parse(ip),UsbPhone=phone,Preferred=preferred,Physical=physical,Wired=wired,Gateway=gateway==null?null:IPAddress.Parse(gateway)};}
 static Adapter Uplink(string name,string ip,int index,bool preferred,string gateway,string dns){Adapter a=Adapter(name,ip,false,preferred,true,true,gateway);a.Id=name;a.Index=index;a.Mask=IPAddress.Parse("255.255.255.0");a.Dns=dns==null?null:IPAddress.Parse(dns);return a;}
 static void DeferredUplinkPolicy(){
  Adapter ethernet=Uplink("Legacy Ethernet","192.168.0.2",21,false,"192.168.0.1","192.168.0.1");
  Adapter phone=Uplink("Phone","192.168.42.2",37,true,"192.168.42.129","192.168.42.129");phone.UsbPhone=true;
  Adapter wifi=Uplink("Wi-Fi","10.0.0.2",5,false,"10.0.0.1","10.0.0.1");wifi.Wired=false;
  List<Adapter> choices=new List<Adapter>(new Adapter[]{ethernet,phone,wifi});int calls=0;string reason;
  UplinkVerifier legacy=delegate(Adapter a,int ms,CableAlive running,out string why){calls++;why="";throw UsbNetwork.ExplainTlsFailure(new ArgumentException("Unsupported value","sslProtocolType"));};
  Adapter selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},legacy,out reason);
  Check(selected!=null&&selected.UplinkCheckDeferred&&!selected.UplinkVerified&&calls==1&&reason.Contains("телефон")&&reason.Contains("не подтверждён"),"explicit legacy TLS rejection defers to phone without reporting verified internet");
  Check(selected.Id==ethernet.Id&&selected.Index==21&&selected.Address.Equals(ethernet.Address)&&selected.Dns.Equals(ethernet.Dns)&&!Object.ReferenceEquals(selected,ethernet),"deferred check keeps the exact requested interface IP and configured DNS snapshot");
  Check(!ethernet.UplinkCheckDeferred&&!ethernet.UplinkVerified&&NetworkCatalog.StillAvailable(selected,choices,phone),"deferred outcome does not mutate live adapter and still requires availability");
  ethernet.Dns=IPAddress.Parse("192.168.0.53");Check(!NetworkCatalog.StillAvailable(selected,choices,phone),"DNS change invalidates a deferred source just like a verified source");ethernet.Dns=IPAddress.Parse("192.168.0.1");
  Adapter noDns=Uplink("DNS absent","172.20.0.2",51,false,"172.20.0.1",null);calls=0;
  Check(NetworkCatalog.SelectVerifiedUplink(new List<Adapter>(new Adapter[]{noDns}),noDns.Address,null,8000,delegate{return true;},legacy,out reason)==null&&calls==0,"legacy TLS handling never supplies a public or borrowed DNS resolver");
  foreach(Exception failure in new Exception[]{new System.Security.Authentication.AuthenticationException("Untrusted certificate"),new System.IO.IOException("Timeout"),new NotSupportedException("Something unavailable"),new ArgumentException("Unsupported","targetHost")}){
   calls=0;Adapter failed=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},delegate(Adapter a,int ms,CableAlive running,out string why){calls++;why="";throw failure;},out reason);
   Check(failed==null&&calls==1,"real probe failure stays closed: "+failure.GetType().Name);
  }
  bool active=true;selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return active;},delegate(Adapter a,int ms,CableAlive running,out string why){active=false;why="";throw UsbNetwork.ExplainTlsFailure(new ArgumentException("Unsupported","enabledSslProtocols"));},out reason);
  Check(selected==null,"stop during unsupported TLS handling cannot publish deferred source");
  selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,20,delegate{return true;},delegate(Adapter a,int ms,CableAlive running,out string why){Thread.Sleep(50);why="";throw UsbNetwork.ExplainTlsFailure(new ArgumentException("Unsupported","sslProtocolType"));},out reason);
  Check(selected==null,"deferred TLS outcome cannot exceed verification deadline");
  Adapter unknownUsb=Uplink("Unknown USB","192.168.77.2",77,true,"192.168.77.1","192.168.77.1");unknownUsb.UsbCandidate=true;
  List<Adapter> hidden=new List<Adapter>(new Adapter[]{unknownUsb,ethernet});selected=NetworkCatalog.SelectVerifiedUplink(hidden,unknownUsb.Address,null,8000,delegate{return true;},legacy,out reason);
  Check(selected!=null&&selected.UplinkCheckDeferred,"generic USB may await Android proof without claiming TLS success");NetworkCatalog.RememberPhone(unknownUsb);
  Check(!NetworkCatalog.StillAvailable(selected,hidden,unknownUsb),"identifying generic USB as phone invalidates even a deferred source");
  calls=0;Check(NetworkCatalog.SelectVerifiedUplink(hidden,unknownUsb.Address,unknownUsb,8000,delegate{return true;},legacy,out reason)==null&&calls==0,"deferred flag cannot bypass authenticated phone exclusion");
 }
 static void UplinkPolicy(){
  Adapter ethernet=Uplink("Ethernet","192.168.0.2",21,true,"192.168.0.1","192.168.0.1");Adapter wifi=Uplink("Wi-Fi","10.0.0.2",5,false,"10.0.0.1","10.0.0.1");wifi.Wired=false;ethernet.DefaultMetric=25;wifi.DefaultMetric=30;
  Adapter phone=Uplink("Phone","192.168.42.2",37,false,"192.168.42.129","192.168.42.129");phone.UsbPhone=true;phone.UsbCandidate=true;phone.DefaultMetric=50;
  List<Adapter> choices=new List<Adapter>(new Adapter[]{ethernet,wifi,phone});int calls=0;string reason;bool alive=true;
  UplinkVerifier success=delegate(Adapter a,int ms,CableAlive running,out string why){calls++;if(ms<1||ms>8000)throw new Exception("Unbounded verification");why="verified";return true;};
  Adapter selected=NetworkCatalog.SelectVerifiedUplink(choices,null,null,8000,delegate{return alive;},success,out reason);
  Check(selected!=null&&selected.Id==ethernet.Id&&calls==0&&!selected.UplinkVerified,"normal preferred PC source takes fast path without claiming a network probe");
  Check(!Object.ReferenceEquals(selected,ethernet)&&selected.Index==21&&selected.Dns.Equals(ethernet.Dns),"selected source pins a snapshot of interface address and configured DNS");
  alive=false;Check(NetworkCatalog.SelectVerifiedUplink(choices,null,null,8000,delegate{return alive;},success,out reason)==null&&calls==0,"canceled fast-path selection cannot publish an uplink");alive=true;
  ethernet.Preferred=false;phone.Preferred=true;phone.DefaultMetric=10;
  selected=NetworkCatalog.SelectVerifiedUplink(choices,null,phone,8000,delegate{return true;},success,out reason);
  Check(selected!=null&&selected.Id==ethernet.Id&&calls==1&&selected.UplinkVerified&&NetworkCatalog.PhoneOwnsDefault(choices),"phone default route permits independently verified Ethernet without route mutation");
  Check(selected.Index==21&&selected.Address.Equals(ethernet.Address)&&selected.Dns.Equals(ethernet.Dns),"verification result retains the exact PC interface IP and DNS");
  Check(NetworkCatalog.StillAvailable(selected,choices,phone),"phone keeping its default route does not invalidate a pinned independent source");
  Adapter alias=Uplink("Other label","192.168.42.3",37,true,"192.168.42.129","192.168.42.129");alias.Id=phone.Id;choices.Add(alias);
  Check(!NetworkCatalog.IsUsableUplink(alias,choices,null)&&!NetworkCatalog.IsUsableUplink(phone,choices,null),"all IP aliases of a known phone interface are excluded from uplink");choices.Remove(alias);
  Check(!NetworkCatalog.IsUsableUplink(ethernet,choices,ethernet),"authenticated cable interface cannot also be the internet source");
  List<string> seen=new List<string>();UplinkVerifier fallback=delegate(Adapter a,int ms,CableAlive running,out string why){seen.Add(a.Id);why="not reachable";return a.Id==wifi.Id;};
  selected=NetworkCatalog.SelectVerifiedUplink(choices,null,phone,8000,delegate{return true;},fallback,out reason);
  Check(selected!=null&&selected.Id==wifi.Id&&seen.Count==2&&seen[0]==ethernet.Id,"automatic source falls back only after independent Ethernet probe fails");
  seen.Clear();selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},fallback,out reason);
  Check(selected==null&&seen.Count==1&&seen[0]==ethernet.Id,"explicit Ethernet failure never silently switches to Wi-Fi");
  seen.Clear();Check(NetworkCatalog.SelectVerifiedUplink(choices,IPAddress.Parse("192.168.0.99"),phone,8000,delegate{return true;},fallback,out reason)==null&&seen.Count==0,"missing manual address never probes another source");
  Adapter overlap=Uplink("Overlap",ethernet.Address.ToString(),50,false,"192.168.0.1","192.168.0.1");choices.Add(overlap);
  Check(NetworkCatalog.UplinkCandidates(choices,ethernet.Address,phone).Count==0,"ambiguous manual address on two interfaces is rejected");choices.Remove(overlap);
  IPAddress secondary=IPAddress.Parse("192.168.0.53");ethernet.DnsServers.Add(secondary);List<string> resolvers=new List<string>();
  UplinkVerifier secondaryDns=delegate(Adapter a,int ms,CableAlive running,out string why){resolvers.Add(a.Dns.ToString());why="primary DNS failed";return a.Dns.Equals(secondary);};
  selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},secondaryDns,out reason);
  Check(selected!=null&&selected.Dns.Equals(secondary)&&resolvers.Count==2&&ethernet.Dns.Equals(IPAddress.Parse("192.168.0.1")),"secondary configured DNS is pinned without rewriting adapter settings");
  Check(NetworkCatalog.StillAvailable(selected,choices,phone),"selected secondary DNS remains valid while configured on the same interface");ethernet.DnsServers.Clear();
  Check(!NetworkCatalog.StillAvailable(selected,choices,phone),"removed selected DNS invalidates the pinned source");
  Adapter dnsless=Uplink("DNS absent","172.20.0.2",51,true,"172.20.0.1",null);calls=0;
  Check(NetworkCatalog.SelectVerifiedUplink(new List<Adapter>(new Adapter[]{dnsless}),null,null,8000,delegate{return true;},success,out reason)==null&&calls==0,"missing configured DNS is not replaced with a public resolver");
  ethernet.Preferred=true;phone.Preferred=false;phone.DefaultMetric=50;ethernet.Dns=phone.Gateway;ethernet.DnsServers.Add(secondary);resolvers.Clear();
  selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},secondaryDns,out reason);
  Check(selected!=null&&selected.Dns.Equals(secondary)&&resolvers.Count==1,"off-link phone DNS is skipped and alternate PC DNS must be verified");
  ethernet.DnsServers.Clear();calls=0;Check(NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return true;},success,out reason)==null&&calls==0,"phone-only DNS is rejected instead of leaking a probe to USB");
  ethernet.Dns=IPAddress.Parse("192.168.0.1");ethernet.Preferred=false;calls=0;
  selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,8000,delegate{return alive;},delegate(Adapter a,int ms,CableAlive running,out string why){calls++;alive=false;why="late success";return true;},out reason);
  Check(selected==null&&calls==1,"stop during verification discards even a successful result");alive=true;calls=0;
  selected=NetworkCatalog.SelectVerifiedUplink(choices,ethernet.Address,phone,30,delegate{return true;},delegate(Adapter a,int ms,CableAlive running,out string why){calls++;Thread.Sleep(ms+30);why="too late";return true;},out reason);
  Check(selected==null&&calls==1,"late probe success cannot exceed the total verification deadline");
  Adapter unknownUsb=Uplink("USB Ethernet","10.7.0.2",71,true,"10.7.0.1","10.7.0.1");unknownUsb.UsbCandidate=true;calls=0;
  selected=NetworkCatalog.SelectVerifiedUplink(new List<Adapter>(new Adapter[]{unknownUsb}),null,null,8000,delegate{return true;},success,out reason);
  Check(selected!=null&&calls==1,"generic USB Ethernet requires proof before it is used as an uplink");
  Adapter local=Uplink("Local-only","172.16.0.1",72,false,null,"172.16.0.53");Check(!NetworkCatalog.IsUsableUplink(local,new List<Adapter>(new Adapter[]{local}),null),"local-only adapter does not become an internet source merely because it has DNS");
  Adapter hiddenPhone=Uplink("Unknown USB","192.168.77.2",77,false,"192.168.77.1","192.168.77.1");hiddenPhone.UsbCandidate=true;hiddenPhone.DefaultMetric=5;
  Adapter vpn=Uplink("VPN","198.18.255.1",63,true,null,"198.18.255.2");vpn.Physical=vpn.Wired=false;
  List<Adapter> hiddenChoices=new List<Adapter>(new Adapter[]{vpn,ethernet,hiddenPhone});calls=0;
  selected=NetworkCatalog.SelectVerifiedUplink(hiddenChoices,null,null,8000,delegate{return true;},success,out reason);
  Check(selected!=null&&selected.Id==vpn.Id&&!selected.UplinkVerified&&calls==0,"preferred VPN initially follows ordinary fast path before generic phone identification");
  NetworkCatalog.RememberPhone(hiddenPhone);
  Check(NetworkCatalog.StillAvailable(selected,hiddenChoices,hiddenPhone)&&NetworkCatalog.RequiresUplinkVerification(selected,hiddenChoices)&&!selected.UplinkVerified,"identifying a hidden USB default route marks a previously selected VPN for verification");
  seen.Clear();selected=NetworkCatalog.SelectVerifiedUplink(hiddenChoices,null,hiddenPhone,8000,delegate{return true;},delegate(Adapter a,int ms,CableAlive running,out string why){seen.Add(a.Id);why="VPN cannot reach upstream";return a.Id==ethernet.Id;},out reason);
  Check(selected!=null&&selected.Id==ethernet.Id&&selected.UplinkVerified&&seen.Count==2&&seen[0]==vpn.Id,"failed preferred VPN can fall back to verified Ethernet while excluding identified phone");
 }
 static int Main(){try{
  Adapter phone=Adapter("Android RNDIS","192.168.42.2",true,true,true,true,"192.168.42.129");
  Adapter ethernet=Adapter("Ethernet","192.168.0.2",false,false,true,true,"192.168.0.1");
  Adapter wifi=Adapter("Wi-Fi","10.0.0.2",false,true,true,false,"10.0.0.1");
  Adapter vpn=Adapter("VPN","198.18.255.1",false,true,false,false,null);
  Adapter local=Adapter("Virtual switch","172.16.0.1",false,false,false,false,null);
  List<Adapter> choices=new List<Adapter>(new Adapter[]{phone,ethernet});
  Check(NetworkCatalog.PhoneOwnsDefault(choices),"detect phone taking over the PC default route");
  Check(NetworkCatalog.Choose(choices)==ethernet,"phone is never an internet uplink");
  Check(NetworkCatalog.CanUseUsbNetwork(0,choices),"automatic USB remains discoverable when phone advertises a default route");
  Check(NetworkCatalog.CanUseUsbNetwork(2,choices),"manual USB is not blanket-blocked by phone default route");
  phone.Preferred=false;
  Check(NetworkCatalog.CanUseUsbNetwork(2,choices),"manual USB remains available with independent PC route");
  Check(NetworkCatalog.CanUseUsbNetwork(0,choices),"automatic mode probes safe USB network before MIDI");
  Check(!NetworkCatalog.CanUseUsbNetwork(1,choices),"MIDI-only mode never probes USB network");
  Check(NetworkCatalog.ChoosePhysical(new List<Adapter>(new Adapter[]{ethernet,wifi}))==wifi,"preferred Wi-Fi beats unrelated Ethernet gateway");
  Check(NetworkCatalog.Choose(new List<Adapter>(new Adapter[]{ethernet,vpn}))==vpn,"selected Windows VPN is honored even without a gateway");
  Check(NetworkCatalog.Choose(new List<Adapter>(new Adapter[]{local}))==null,"local-only virtual adapter is not advertised as internet");
  Check(NetworkCatalog.Find(choices,IPAddress.Parse("192.168.0.2"))==ethernet,"manual source remains pinned by address");
  Check(NetworkCatalog.Find(choices,IPAddress.Parse("192.168.0.99"))==null,"missing manual source is not silently substituted");
  Check(NetworkCatalog.IsPhone("Сетевое устройство",@"USB\VID_1234&PID_5678 usbncm USB\Class_02&SubClass_0D&Prot_00"),"NCM registry service is recognized despite localized generic name");
  Check(NetworkCatalog.IsPhone("Ethernet",@"USB\VID_1234&PID_5678 rndismp"),"RNDIS registry service is recognized despite generic name");
  Check(!NetworkCatalog.IsPhone("ASIX USB Ethernet",@"USB\VID_0B95&PID_1790"),"ordinary USB Ethernet dock is not assumed to be a phone");
  Check(NetworkCatalog.IsUsbCandidate("ASIX USB Ethernet",@"USB\VID_0B95&PID_1790",false),"generic USB Ethernet can be probed for Bridge protocol");
  Check(!NetworkCatalog.IsUsbCandidate("Intel Ethernet",@"PCI\VEN_8086",false),"ordinary PCI Ethernet is never scanned as a phone");
  Adapter dhcp=Adapter("USB NCM","192.168.47.2",true,false,true,true,null);dhcp.Mask=IPAddress.Parse("255.255.255.0");dhcp.DhcpServer=IPAddress.Parse("192.168.47.1");dhcp.Dns=IPAddress.Parse("192.168.47.1");
  Check(NetworkCatalog.Phone(new List<Adapter>(new Adapter[]{dhcp}))==dhcp,"USB network without default gateway is discovered by DHCP server");
  Check(NetworkCatalog.Peers(dhcp).Count==1,"repeated DHCP and DNS peer is attempted only once");
  dhcp.Gateway=IPAddress.Parse("192.168.47.129");Check(NetworkCatalog.Peers(dhcp).Count==2&&NetworkCatalog.Peers(dhcp)[0].Equals(dhcp.Gateway),"on-link gateway precedes DHCP peer");
  dhcp.Dns=IPAddress.Parse("8.8.8.8");Check(NetworkCatalog.Peers(dhcp).Count==2,"off-link DNS is never contacted as a phone");
  Check(!NetworkCatalog.OnLink(dhcp,IPAddress.Parse("192.168.47.255"))&&!NetworkCatalog.OnLink(dhcp,IPAddress.Parse("192.168.47.0"))&&!NetworkCatalog.OnLink(dhcp,dhcp.Address),"broadcast network and own address are not phone peers");
  Check(!NetworkCatalog.OnLink(dhcp,IPAddress.Parse("127.0.0.1"))&&!NetworkCatalog.OnLink(dhcp,IPAddress.Parse("224.0.0.1")),"loopback and multicast are not discovery targets");
  Adapter dock=Adapter("USB Ethernet","10.10.0.2",false,true,true,true,"10.10.0.1");dock.UsbCandidate=true;Check(NetworkCatalog.Choose(new List<Adapter>(new Adapter[]{dock,ethernet}))==dock,"USB Ethernet stays an uplink until Bridge handshake confirms phone");
  NetworkCatalog.RememberPhone(dock);Check(NetworkCatalog.PhoneOwnsDefault(new List<Adapter>(new Adapter[]{dock,ethernet}))&&NetworkCatalog.Choose(new List<Adapter>(new Adapter[]{dock,ethernet}))==ethernet,"confirmed generic phone is excluded from uplink and unsafe default detected");
  phone.Preferred=false;phone.DefaultMetric=10;ethernet.DefaultMetric=25;Check(NetworkCatalog.PhoneOwnsDefault(new List<Adapter>(new Adapter[]{phone,ethernet,vpn})),"VPN preferred source does not hide phone taking physical default route");
  phone.DefaultMetric=50;Check(!NetworkCatalog.PhoneOwnsDefault(new List<Adapter>(new Adapter[]{phone,ethernet,vpn})),"lower priority phone gateway is safe behind independent Ethernet default");
  phone.DefaultMetric=25;Check(NetworkCatalog.PhoneOwnsDefault(new List<Adapter>(new Adapter[]{phone,ethernet,vpn})),"equal metric phone default requires independent uplink verification");
  byte[] table=new byte[4+56];Buffer.BlockCopy(BitConverter.GetBytes(1),0,table,0,4);table[16]=192;table[17]=168;table[18]=0;table[19]=1;Buffer.BlockCopy(BitConverter.GetBytes(21),0,table,20,4);Buffer.BlockCopy(BitConverter.GetBytes(4),0,table,24,4);Buffer.BlockCopy(BitConverter.GetBytes(25),0,table,40,4);
  Dictionary<int,NetworkCatalog.DefaultRoute> routes=NetworkCatalog.ParseDefaultRoutes(table);Check(routes.Count==1&&routes[21].Metric==25&&routes[21].Gateway.Equals(IPAddress.Parse("192.168.0.1")),"native route parser preserves interface metric and network-order gateway");
  table[4]=192;Check(NetworkCatalog.ParseDefaultRoutes(table).Count==0,"specific routes cannot be mistaken for default route");
  Check(NetworkCatalog.ParseDefaultRoutes(new byte[]{255,255,255,255}).Count==0,"truncated native route table is rejected without reading beyond buffer");
  UplinkPolicy();
  DeferredUplinkPolicy();
  Console.WriteLine("TOTAL "+count+" passed");return 0;
 }catch(Exception error){Console.Error.WriteLine(error);return 1;}}
}
