using System;
using System.IO;
using System.Text;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Collections.Generic;
namespace UsbBridge {
public sealed class Relay {
    readonly IPAddress source,dns;readonly int index;int pendingDns,udpReceiveBuffer;long udpDrops,udpEvictions,ipv6Rejects;readonly DnsTransport dnsTransport;public long TcpCount,UdpCount; public Action<string> Log; public bool Trace;
    public Relay(IPAddress source,int index,IPAddress dns):this(source,index,dns,53,2500){}
    internal Relay(IPAddress source,int index,IPAddress dns,int dnsPort,int dnsTimeout){this.source=source;this.index=index;this.dns=dns;dnsTransport=new DnsTransport(NewSocket,dns,dnsPort,dnsTimeout);dnsTransport.Diagnostic=delegate(string message){if(Log!=null)Log("Pinned source="+source+"; ifindex="+index+"; "+message);};}
    public string Diagnostics {get{return "Pinned source="+source+"; ifindex="+index+"; "+dnsTransport.Diagnostics+"; TCP connections="+Interlocked.Read(ref TcpCount)+"; UDP sent="+Interlocked.Read(ref UdpCount)+"; UDP receive buffer="+Interlocked.CompareExchange(ref udpReceiveBuffer,0,0)+"; IPv6 rejected="+Interlocked.Read(ref ipv6Rejects)+"; UDP dropped="+Interlocked.Read(ref udpDrops)+"; UDP cache evictions="+Interlocked.Read(ref udpEvictions);}}
    // Legacy callers may still supply a second source. All traffic honors the selected connection.
    public Relay(IPAddress source,int index,IPAddress dns,IPAddress unusedSource,int unusedIndex):this(source,index,dns){}
    Socket NewSocket(SocketType kind){Socket s=new Socket(AddressFamily.InterNetwork,kind,kind==SocketType.Stream?ProtocolType.Tcp:ProtocolType.Udp);int chosenIndex=index;IPAddress chosenSource=source;try{if(chosenIndex>0)s.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,IPAddress.HostToNetworkOrder(chosenIndex));if(chosenSource!=null)s.Bind(new IPEndPoint(chosenSource,0));s.SendTimeout=15000;s.ReceiveTimeout=kind==SocketType.Stream?300000:60000;if(kind==SocketType.Stream)s.NoDelay=true;else{
        // MIDI backpressure can pause this socket's reader. The Windows default
        // UDP queue (often 8-64 KiB) drops a normal VPN burst during that pause.
        // Request bounded headroom before any datagrams arrive; no packet
        // batching, bandwidth throttle, global registry or route change.
        try{s.ReceiveBufferSize=1048576;}catch(SocketException){try{s.ReceiveBufferSize=262144;}catch(SocketException){}}
        int actual=s.ReceiveBufferSize;
        if(Interlocked.Exchange(ref udpReceiveBuffer,actual)==0&&Log!=null)Log("UDP receive buffer="+actual+" bytes");
    }return s;}catch{s.Close();throw;}}
    IPAddress Resolve(string host){
        IPAddress literal;
        if(IPAddress.TryParse(host,out literal)){
            if(literal.AddressFamily!=AddressFamily.InterNetwork){Interlocked.Increment(ref ipv6Rejects);throw new IOException("IPv6 destination unavailable on the selected IPv4 relay");}
            return literal;
        }
        return QueryA(host);
    }
    IPAddress QueryA(string host){
        byte[] id=Wire.Random(2);MemoryStream q=new MemoryStream();q.Write(id,0,2);q.Write(new byte[]{1,0,0,1,0,0,0,0,0,0},0,10);
        foreach(string part in host.TrimEnd('.').Split('.')){byte[] b=Encoding.ASCII.GetBytes(part);if(b.Length==0||b.Length>63)throw new IOException("Bad DNS name");q.WriteByte((byte)b.Length);q.Write(b,0,b.Length);}
        q.Write(new byte[]{0,0,1,0,1},0,5);byte[] request=q.ToArray(),response=dnsTransport.Exchange(request);int n=response.Length;

        if(n<12||response[0]!=id[0]||response[1]!=id[1]||(response[3]&15)!=0)throw new IOException("DNS server did not answer");
        int p=12,qs=(response[4]<<8)|response[5],an=(response[6]<<8)|response[7];
        for(int i=0;i<qs;i++){SkipName(response,ref p,n);p+=4;}
        for(int i=0;i<an;i++){SkipName(response,ref p,n);if(p+10>n)break;int type=response[p]*256+response[p+1],len=response[p+8]*256+response[p+9];p+=10;if(p+len>n)break;if(type==1&&len==4)return new IPAddress(new byte[]{response[p],response[p+1],response[p+2],response[p+3]});p+=len;}
        throw new IOException("No IPv4 DNS answer for "+host);
    }
    static void SkipName(byte[] b,ref int p,int n){for(int c=0;c<128;c++){if(p>=n)throw new IOException("Bad DNS");int k=b[p++];if(k==0)return;if((k&192)==192){if(p>=n)throw new IOException("Bad DNS");p++;return;}if(k>63||p+k>n)throw new IOException("Bad DNS");p+=k;}throw new IOException("Bad DNS");}
    public Socket Connect(string host,int port){Socket s=NewSocket(SocketType.Stream);try{IAsyncResult r=s.BeginConnect(new IPEndPoint(host=="198.18.7.2"&&port==53?dns:Resolve(host),port),null,null);using(WaitHandle w=r.AsyncWaitHandle){if(!w.WaitOne(12000,false))throw new IOException("Connect timeout");s.EndConnect(r);}return s;}catch{s.Close();throw;}}
    static string Address(Stream s,int type){if(type==1)return new IPAddress(Wire.Read(s,4)).ToString();if(type==4)return new IPAddress(Wire.Read(s,16)).ToString();if(type==3){int n=s.ReadByte();if(n<1)throw new IOException("Bad address");return Encoding.ASCII.GetString(Wire.Read(s,n));}throw new IOException("Unknown address type");}
    static int Port(Stream s){byte[] p=Wire.Read(s,2);return p[0]*256+p[1];}
    public void Serve(Channel s){Socket upstream=null;bool replied=false;try{byte[] head=Wire.Read(s,2);if(head[0]!=5||head[1]<1)throw new IOException("Not SOCKS5");byte[] methods=Wire.Read(s,head[1]);bool found=false;foreach(byte m in methods)if(m==0)found=true;s.Write(new byte[]{5,found?(byte)0:(byte)255},0,2);if(!found)return;byte[] req=Wire.Read(s,4);if(req[0]!=5||req[2]!=0)throw new IOException("Bad request");string host=Address(s,req[3]);int port=Port(s);if(req[1]==1){if(host=="198.18.7.2"&&port==53){s.Write(new byte[]{5,0,0,1,0,0,0,0,0,0},0,10);replied=true;Interlocked.Increment(ref TcpCount);TcpDns(s);return;}upstream=Connect(host,port);Interlocked.Increment(ref TcpCount);s.Write(new byte[]{5,0,0,1,0,0,0,0,0,0},0,10);replied=true;Socket u=upstream;object remoteGate=new object();bool remoteDone=false;Wire.Run(delegate{try{byte[] buf=new byte[32768];int n;while((n=u.Receive(buf))>0)s.Write(buf,0,n);s.Finish();}catch{try{s.Close();}catch{}try{u.Close();}catch{}}finally{lock(remoteGate){remoteDone=true;Monitor.PulseAll(remoteGate);}}});byte[] b=new byte[32768];int k;while((k=s.Read(b,0,b.Length))>0){int p=0;while(p<k)p+=u.Send(b,p,k-p,SocketFlags.None);}try{u.Shutdown(SocketShutdown.Send);}catch{}// Wait for the remote response after a client half-close.
            lock(remoteGate){DateTime until=DateTime.UtcNow.AddMinutes(5);while(!remoteDone&&DateTime.UtcNow<until)Monitor.Wait(remoteGate,1000);}
        }else if(req[1]==5){s.Write(new byte[]{5,0,0,1,0,0,0,0,0,0},0,10);replied=true;Udp(s);}else{s.Write(new byte[]{5,7,0,1,0,0,0,0,0,0},0,10);}}catch(Exception e){if(Log!=null&&!(e is EndOfStreamException)&&e.Message!="Stream closed")Log(e.Message);try{if(!replied)s.Write(new byte[]{5,4,0,1,0,0,0,0,0,0},0,10);}catch{}}finally{if(upstream!=null)upstream.Close();}}
    // HEV deliberately forwards Android DNS over TCP. Terminate that local DNS
    // stream here instead of requiring the Windows resolver to accept TCP too.
    // The upstream transport may use UDP or TCP at the exact configured server.
    void TcpDns(Channel stream){while(true){int first=stream.ReadByte();if(first<0)return;int second=stream.ReadByte();if(second<0)throw new EndOfStreamException();int length=first*256+second;if(length<12)throw new IOException("Short DNS query");byte[] response=LocalDns(Wire.Read(stream,length));byte[] packet=new byte[response.Length+2];packet[0]=(byte)(response.Length>>8);packet[1]=(byte)response.Length;Buffer.BlockCopy(response,0,packet,2,response.Length);stream.Write(packet,0,packet.Length);}}
    void QueueDns(Channel stream,object writeLock,byte[] query){
        // A slow DNS reply cannot hold WireGuard/QUIC datagrams behind it. Bound
        // concurrent jobs across all associations so the cable cannot spawn an
        // unlimited number of resolver threads.
        if(Interlocked.Increment(ref pendingDns)>64){Interlocked.Decrement(ref pendingDns);byte[] busy=DnsFailure(query);byte[] packet=UdpFrame(IPAddress.Parse("198.18.7.2"),53,busy,busy.Length);lock(writeLock)stream.Write(packet,0,packet.Length);return;}
        Wire.Run(delegate{try{byte[] response=LocalDns(query);byte[] packet=UdpFrame(IPAddress.Parse("198.18.7.2"),53,response,response.Length);lock(writeLock)stream.Write(packet,0,packet.Length);Interlocked.Increment(ref UdpCount);}catch(Exception e){if(Log!=null&&!(e is EndOfStreamException)&&e.Message!="Stream closed"&&e.Message!="Канал закрыт")Log(e.Message);}finally{Interlocked.Decrement(ref pendingDns);}});
    }
    static byte[] DnsFailure(byte[] query){if(query.Length<12)throw new IOException("Short DNS query");byte[] response=(byte[])query.Clone();response[2]=(byte)((query[2]&1)|128);response[3]=130;for(int i=6;i<12;i++)response[i]=0;return response;}
    byte[] LocalDns(byte[] query){
        try{return dnsTransport.Exchange(query);}
        catch(IOException error){
            if(Log!=null)Log(error.Message);
            // A temporary DNS failure is SERVFAIL, never a cached "name does not exist".
            return DnsFailure(query);
        }
    }
    static byte[] UdpFrame(IPAddress address,int port,byte[] payload,int length){byte[] packet=new byte[length+10];packet[0]=(byte)(length>>8);packet[1]=(byte)length;packet[2]=10;packet[3]=1;Buffer.BlockCopy(address.GetAddressBytes(),0,packet,4,4);packet[8]=(byte)(port>>8);packet[9]=(byte)port;Buffer.BlockCopy(payload,0,packet,10,length);return packet;}
    internal sealed class UdpFlow {
        static long useSequence;readonly object activityGate=new object();long lastUse;
        public readonly Socket Socket;public volatile bool Closed;public volatile int LastActivity;
        public long LastUse {get{return Interlocked.Read(ref lastUse);}}
        public UdpFlow(Socket socket){Socket=socket;Touch();}
        // TickCount is sufficient for expiry but may have 16 ms granularity.
        // Sequence order also distinguishes several busy flows in the same tick.
        public void Touch(){lock(activityGate){LastActivity=Environment.TickCount;Interlocked.Exchange(ref lastUse,Interlocked.Increment(ref useSequence));}}
        public void Close(){Closed=true;try{Socket.Close();}catch{}}
    }
    internal static bool UdpIdle(int lastActivity,int now){return unchecked((uint)(now-lastActivity))>=120000;}
    // Only the association reader modifies the dictionary. Receiver threads only
    // mark their own flow closed or refresh its timestamp; no cross-flow teardown.
    internal static void PruneUdpFlows(Dictionary<string,UdpFlow> flows,int now){
        List<string> expired=new List<string>();
        foreach(KeyValuePair<string,UdpFlow> entry in flows)if(entry.Value.Closed||UdpIdle(entry.Value.LastActivity,now))expired.Add(entry.Key);
        foreach(string key in expired){flows[key].Close();flows.Remove(key);}
    }
    internal static string EvictLruUdpFlow(Dictionary<string,UdpFlow> flows){
        string oldest=null;long sequence=Int64.MaxValue;
        foreach(KeyValuePair<string,UdpFlow> entry in flows){long used=entry.Value.LastUse;if(oldest==null||used<sequence){oldest=entry.Key;sequence=used;}}
        if(oldest!=null){flows[oldest].Close();flows.Remove(oldest);}return oldest;
    }
    void Udp(Channel stream){
        Dictionary<string,UdpFlow> flows=new Dictionary<string,UdpFlow>();object writeLock=new object();int dropped=0;
        try{while(true){
            byte[] h=Wire.Read(stream,4);int len=h[0]*256+h[1],header=h[2];string host=Address(stream,h[3]);int port=Port(stream);
            int expected=3+(h[3]==1?7:h[3]==4?19:4+Encoding.ASCII.GetByteCount(host));
            if(header!=expected||len>65507)throw new IOException("Bad UDP frame");
            byte[] payload=Wire.Read(stream,len);string key=host+":"+port;
            if(host=="198.18.7.2"&&port==53){QueueDns(stream,writeLock,payload);continue;}
            UdpFlow flow=null;
            // Address resolution, socket creation and a failed send belong to this
            // destination. A valid IPv6 packet or one unreachable host must never
            // disconnect DNS, QUIC and WireGuard sharing the same association.
            try{
                if(!flows.TryGetValue(key,out flow)||flow.Closed){
                    PruneUdpFlows(flows,Environment.TickCount);
                    IPAddress requested=Resolve(host);Socket socket=NewSocket(SocketType.Dgram);
                    try{socket.Connect(new IPEndPoint(requested,port));}catch{socket.Close();throw;}
                    // Admit a new destination immediately. LRU bounds resource
                    // use without making new pages wait for a two-minute expiry.
                    // Resolve/connect first: an invalid destination evicts nothing.
                    if(flows.Count>=64){EvictLruUdpFlow(flows);Interlocked.Increment(ref udpEvictions);}
                    flow=new UdpFlow(socket);flows[key]=flow;Socket receiver=socket;UdpFlow receiving=flow;
                    Wire.Run(delegate{
                        try{
                            byte[] b=new byte[65507];
                            while(!receiving.Closed){
                                int k;
                                try{k=receiver.Receive(b);}
                                catch(SocketException ex){
                                    if(ex.SocketErrorCode==SocketError.TimedOut){if(UdpIdle(receiving.LastActivity,Environment.TickCount))break;continue;}
                                    if(ex.SocketErrorCode==SocketError.ConnectionReset||ex.SocketErrorCode==SocketError.ConnectionRefused||ex.SocketErrorCode==SocketError.NetworkReset)continue;
                                    throw;
                                }
                                receiving.Touch();byte[] packet=UdpFrame(requested,port,b,k);
                                if(Trace&&Log!=null)Log("UDP received "+k+" from "+key);
                                lock(writeLock)stream.Write(packet,0,packet.Length);
                            }
                        }catch{}finally{receiving.Close();}
                    });
                }
                flow.Socket.Send(payload);flow.Touch();Interlocked.Increment(ref UdpCount);
                if(Trace&&Log!=null)Log("UDP sent "+payload.Length+" to "+key);
            }catch(Exception error){
                if(flow!=null){flow.Close();flows.Remove(key);}
                dropped++;
                Interlocked.Increment(ref udpDrops);
                if(Log!=null&&(dropped<=4||(dropped&63)==0))Log("UDP destination dropped ("+dropped+"): "+key+"; "+error.Message);
            }
        }}finally{foreach(UdpFlow flow in flows.Values)flow.Close();}
    }
}
}



