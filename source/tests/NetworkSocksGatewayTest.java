package local.usbbridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real socket tests of the router's authenticated, fail-closed SOCKS gateway.
 * Run with Wire.java + NetworkSocksGateway.java; no Android/Internet required. */
public final class NetworkSocksGatewayTest {
    static final InetAddress LOOPBACK;
    static { try { LOOPBACK=InetAddress.getByAddress(new byte[]{127,0,0,1}); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    static void expect(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); }
    static byte[] request(int cmd,int port) { return new byte[]{5,(byte)cmd,0,1,(byte)203,0,113,9,(byte)(port>>8),(byte)port}; }
    static Socket socket(NetworkSocksGateway gateway)throws Exception { Socket s=new Socket(LOOPBACK,gateway.port());s.setSoTimeout(2500);return s; }
    static void auth(Socket s,String password)throws Exception {
        OutputStream out=s.getOutputStream();InputStream in=s.getInputStream();
        out.write(new byte[]{5,1,2});expect(Arrays.equals(new byte[]{5,2},Wire.read(in,2)),"method2");
        byte[] user=Wire.bytes("usbbridge"),pass=Wire.bytes(password);
        out.write(Wire.cat(new byte[]{1,(byte)user.length},user,new byte[]{(byte)pass.length},pass));
        expect(Arrays.equals(new byte[]{1,0},Wire.read(in,2)),"authentication accepted");
    }
    static final class Fixture implements AutoCloseable {
        final ServerSocket tcp=new ServerSocket(0,16,LOOPBACK);
        final DatagramSocket udp=new DatagramSocket(0,LOOPBACK);
        final AtomicInteger opens=new AtomicInteger(),dns=new AtomicInteger();
        final AtomicBoolean valid=new AtomicBoolean(true);
        volatile int lastPort;
        final NetworkSocksGateway gateway;
        Fixture()throws Exception {
            gateway=new NetworkSocksGateway(new NetworkSocksGateway.Route(){
                public void check()throws IOException{if(!valid.get())throw new IOException("VPN removed");}
                public InetAddress resolve(String host)throws IOException{check();return LOOPBACK;}
                public InetAddress dns()throws IOException{check();dns.incrementAndGet();return LOOPBACK;}
                public Socket tcp(InetAddress address,int port)throws IOException{check();lastPort=port;opens.incrementAndGet();return new Socket(LOOPBACK,tcp.getLocalPort());}
                public DatagramSocket udp(InetAddress address,int port)throws IOException{
                    check();lastPort=port;opens.incrementAndGet();
                    DatagramSocket s=port==4999?new DatagramSocket(){
                        @Override public void receive(DatagramPacket packet)throws IOException{throw new PortUnreachableException("Simulated blocked QUIC endpoint");}
                    }:new DatagramSocket();
                    s.connect(LOOPBACK,udp.getLocalPort());return s;
                }
            });
            Wire.run(()->{while(!tcp.isClosed())try{Socket s=tcp.accept();Wire.run(()->{try(Socket own=s){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[1024];for(int n;(n=s.getInputStream().read(block))>=0;)b.write(block,0,n);s.getOutputStream().write(Wire.cat(Wire.bytes("reply:"),b.toByteArray()));}catch(IOException ignored){}});}catch(IOException ignored){}});
            Wire.run(()->{byte[] b=new byte[65507];while(!udp.isClosed())try{DatagramPacket p=new DatagramPacket(b,b.length);udp.receive(p);udp.send(p);}catch(IOException ignored){}});
        }
        public void close(){gateway.close();try{tcp.close();}catch(IOException ignored){}udp.close();}
    }
    static void authRejectsWithoutOpeningRoute()throws Exception {
        try(Fixture f=new Fixture()){
            try(Socket s=socket(f.gateway)){s.getOutputStream().write(new byte[]{5,1,0});expect(Arrays.equals(Wire.read(s.getInputStream(),2),new byte[]{5,(byte)255}),"anonymous proxy denied");}
            try(Socket s=socket(f.gateway)){
                s.getOutputStream().write(new byte[]{5,1,2});Wire.read(s.getInputStream(),2);
                s.getOutputStream().write(new byte[]{1,1,'x',1,'y'});
                expect(Arrays.equals(Wire.read(s.getInputStream(),2),new byte[]{1,1}),"bad password denied");
            }
            expect(f.opens.get()==0,"unauthorized client reached outbound route");
        }
    }
    static void tcpPreservesHalfClose()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(1,443));expect(Wire.read(s.getInputStream(),10)[1]==0,"TCP connected");
            byte[] payload=new byte[80000];new Random(23).nextBytes(payload);
            s.getOutputStream().write(payload);s.shutdownOutput();
            expect(Arrays.equals(Wire.read(s.getInputStream(),payload.length+6),Wire.cat(Wire.bytes("reply:"),payload)),"response after upload FIN lost");
            expect(f.lastPort==443,"destination port changed");
        }
    }
    static byte[] udpFrame(boolean dns,byte[] payload){
        byte[] address=dns?new byte[]{(byte)198,18,7,2}:new byte[]{(byte)203,0,113,9};int port=dns?53:4444;
        return Wire.cat(new byte[]{(byte)(payload.length>>8),(byte)payload.length,10,1},address,new byte[]{(byte)(port>>8),(byte)port},payload);
    }
    static void udpAndDnsKeepOriginalDestination()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(5,0));expect(Wire.read(s.getInputStream(),10)[1]==0,"UDP connected");
            byte[] payload=new byte[2048];new Random(12).nextBytes(payload);
            for(boolean dns:new boolean[]{false,true}){
                byte[] frame=udpFrame(dns,payload);s.getOutputStream().write(frame);
                expect(Arrays.equals(Wire.read(s.getInputStream(),frame.length),frame),"UDP response destination/body changed");
            }
            expect(f.dns.get()==1&&f.lastPort==53,"fake DNS not mapped inside selected route");
        }
    }
    static void revokedRouteHasNoFallback()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);f.valid.set(false);s.getOutputStream().write(request(1,443));
            expect(s.getInputStream().read()==-1,"revoked VPN accepted connection");
            expect(f.opens.get()==0,"revocation fell through to outbound socket");
        }
    }
    static void closeRevokesBusyAndUnauthenticatedSessions()throws Exception {
        try(Fixture f=new Fixture()){
            List<Socket> sockets=new ArrayList<>();
            try{
                for(int i=0;i<20;i++){
                    Socket s=socket(f.gateway);sockets.add(s);
                    if(i%2==0){auth(s,f.gateway.password);s.getOutputStream().write(request(1,443));Wire.read(s.getInputStream(),10);}
                }
                long until=System.nanoTime()+2_000_000_000L;while(f.gateway.sessions.size()<20&&System.nanoTime()<until)Thread.sleep(10);
                long before=System.nanoTime();f.gateway.close();
                expect(System.nanoTime()-before<1_000_000_000L,"gateway cleanup blocked");
                for(Socket s:sockets)try{expect(s.getInputStream().read()==-1,"open socket survived close");}catch(SocketException reset){}
                expect(f.gateway.sessions.isEmpty(),"session retained after close");
            }finally{for(Socket s:sockets)s.close();}
        }
    }
    static void malformedUdpNeverOpensRoute()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(5,0));Wire.read(s.getInputStream(),10);
            byte[] frame=udpFrame(false,new byte[]{1,2,3});frame[2]=9;s.getOutputStream().write(frame);
            expect(s.getInputStream().read()==-1,"malformed frame survived");expect(f.opens.get()==0,"malformed frame opened socket");
        }
    }
    static byte[] udpFrameForPort(int port,byte[] payload){
        byte[] frame=udpFrame(false,payload);frame[8]=(byte)(port>>8);frame[9]=(byte)port;return frame;
    }
    static void oneUnreachableUdpEndpointDoesNotResetDns()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(5,0));Wire.read(s.getInputStream(),10);
            s.getOutputStream().write(udpFrameForPort(4999,new byte[]{1,2,3}));
            long until=System.nanoTime()+1_000_000_000L;
            while(f.opens.get()==0&&System.nanoTime()<until)Thread.sleep(5);
            // A receive error from the previous destination may race this DNS
            // query. Both are on the same real SOCKS TCP association.
            byte[] dns=udpFrame(true,new byte[]{7,8,9});s.getOutputStream().write(dns);
            expect(Arrays.equals(dns,Wire.read(s.getInputStream(),dns.length)),"one blocked UDP endpoint reset unrelated DNS");
        }
    }
    static void manyUdpEndpointsEvictLocallyWithoutClosingAssociation()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(5,0));Wire.read(s.getInputStream(),10);
            for(int i=0;i<96;i++){
                byte[] frame=udpFrameForPort(5000+i,new byte[]{(byte)i});s.getOutputStream().write(frame);
                expect(Arrays.equals(frame,Wire.read(s.getInputStream(),frame.length)),"UDP association reset at endpoint "+i);
            }
            byte[] recreated=udpFrameForPort(5000,new byte[]{99});s.getOutputStream().write(recreated);
            expect(Arrays.equals(recreated,Wire.read(s.getInputStream(),recreated.length)),"evicted endpoint could not reopen");
            NetworkSocksGateway.Session session=f.gateway.sessions.iterator().next();
            synchronized(session){expect(session.datagrams.size()<=64,"UDP endpoint resource limit exceeded");}
        }
    }
    static void revokedVpnStillClosesWholeUdpAssociation()throws Exception {
        try(Fixture f=new Fixture();Socket s=socket(f.gateway)){
            auth(s,f.gateway.password);s.getOutputStream().write(request(5,0));Wire.read(s.getInputStream(),10);
            byte[] frame=udpFrame(false,new byte[]{1});s.getOutputStream().write(frame);Wire.read(s.getInputStream(),frame.length);
            int opened=f.opens.get();f.valid.set(false);
            s.getOutputStream().write(udpFrame(true,new byte[]{2}));
            expect(s.getInputStream().read()==-1,"revoked VPN retained UDP association");
            expect(f.opens.get()==opened,"revoked VPN fell through to DNS or another route");
        }
    }
    static byte[] dnsQuery(int id)throws Exception {
        ByteArrayOutputStream q=new ByteArrayOutputStream();q.write(new byte[]{(byte)(id>>8),(byte)id,1,0,0,1,0,0,0,0,0,0});
        for(String part:"router-proof.invalid".split("\\.")){byte[] b=Wire.bytes(part);q.write(b.length);q.write(b);}
        q.write(new byte[]{0,0,1,0,1});return q.toByteArray();
    }
    static byte[] dnsAnswer(byte[] q,boolean truncated){
        byte[] r=Arrays.copyOf(q,q.length+(truncated?0:16));r[2]=(byte)(truncated?0x83:0x81);r[3]=(byte)0x80;r[7]=(byte)(truncated?0:1);
        if(!truncated)System.arraycopy(new byte[]{(byte)192,12,0,1,0,1,0,0,0,30,0,4,10,88,0,1},0,r,q.length,16);
        return r;
    }
    static void tcpDnsUsesUdpOnlyResolverAndHandlesTruncation(boolean tcpFallback)throws Exception {
        final DatagramSocket dnsUdp=new DatagramSocket(0,LOOPBACK);final ServerSocket dnsTcp=new ServerSocket(0,8,LOOPBACK);
        AtomicInteger tcpCalls=new AtomicInteger();
        Wire.run(()->{while(!dnsUdp.isClosed())try{byte[] b=new byte[2048];DatagramPacket p=new DatagramPacket(b,b.length);dnsUdp.receive(p);byte[] r=dnsAnswer(Arrays.copyOf(b,p.getLength()),tcpFallback);dnsUdp.send(new DatagramPacket(r,r.length,p.getSocketAddress()));}catch(IOException ignored){}});
        Wire.run(()->{while(!dnsTcp.isClosed())try{Socket socket=dnsTcp.accept();Wire.run(()->{try(Socket s=socket){byte[] h=Wire.read(s.getInputStream(),2);byte[] q=Wire.read(s.getInputStream(),((h[0]&255)<<8)|(h[1]&255));byte[] r=dnsAnswer(q,false);s.getOutputStream().write(new byte[]{(byte)(r.length>>8),(byte)r.length});s.getOutputStream().write(r);}catch(IOException ignored){}});}catch(IOException ignored){}});
        try(NetworkSocksGateway gateway=new NetworkSocksGateway(new NetworkSocksGateway.Route(){
            public void check(){}
            public InetAddress resolve(String host)throws IOException{throw new IOException("Unexpected hostname resolution");}
            public InetAddress dns(){return LOOPBACK;}
            public Socket tcp(InetAddress address,int port)throws IOException{expect(address.equals(LOOPBACK)&&port==53,"DNS escaped selected route");tcpCalls.incrementAndGet();if(!tcpFallback)throw new ConnectException("UDP-only resolver");return new Socket(LOOPBACK,dnsTcp.getLocalPort());}
            public DatagramSocket udp(InetAddress address,int port)throws IOException{expect(address.equals(LOOPBACK)&&port==53,"DNS escaped selected route");DatagramSocket s=new DatagramSocket();s.connect(LOOPBACK,dnsUdp.getLocalPort());return s;}
        });Socket client=socket(gateway)){
            auth(client,gateway.password);client.getOutputStream().write(new byte[]{5,1,0,1,(byte)198,18,7,2,0,53});
            expect(Wire.read(client.getInputStream(),10)[1]==0,"synthetic TCP DNS SOCKS rejected");
            for(int i=0;i<2;i++){
                byte[] q=dnsQuery(123+i);client.getOutputStream().write(new byte[]{(byte)(q.length>>8),(byte)q.length});client.getOutputStream().write(q);
                byte[] h=Wire.read(client.getInputStream(),2);byte[] r=Wire.read(client.getInputStream(),((h[0]&255)<<8)|(h[1]&255));
                expect(Arrays.equals(r,dnsAnswer(q,false)),"TCP DNS framing did not use configured resolver reply");
            }
            if(tcpFallback)expect(tcpCalls.get()==2,"truncated UDP response did not trigger TCP fallback");
        }finally{dnsUdp.close();dnsTcp.close();}
    }
    static void dnsReplyValidationRejectsWrongQueries()throws Exception {
        byte[] q=dnsQuery(321),valid=dnsAnswer(q,false);
        expect(NetworkSocksGateway.dnsResponseMatches(q,valid),"valid DNS response rejected");
        byte[] wrong=valid.clone();wrong[1]^=1;expect(!NetworkSocksGateway.dnsResponseMatches(q,wrong),"wrong DNS transaction accepted");
        wrong=valid.clone();wrong[13]='x';expect(!NetworkSocksGateway.dnsResponseMatches(q,wrong),"wrong DNS question accepted");
        wrong=valid.clone();wrong[q.length-3]=28;expect(!NetworkSocksGateway.dnsResponseMatches(q,wrong),"wrong DNS type accepted");
        wrong=valid.clone();wrong[2]&=127;expect(!NetworkSocksGateway.dnsResponseMatches(q,wrong),"DNS query accepted as response");
        byte[] failed=NetworkSocksGateway.dnsFailure(q);
        expect(NetworkSocksGateway.dnsResponseMatches(q,failed)&&(failed[3]&15)==2,"unavailable DNS must produce matching SERVFAIL");
        expect(failed[6]==0&&failed[7]==0&&failed.length==q.length,"SERVFAIL contained fabricated answer");
    }
    public static void main(String[] args)throws Exception {
        authRejectsWithoutOpeningRoute();tcpPreservesHalfClose();udpAndDnsKeepOriginalDestination();
        revokedRouteHasNoFallback();closeRevokesBusyAndUnauthenticatedSessions();malformedUdpNeverOpensRoute();
        oneUnreachableUdpEndpointDoesNotResetDns();manyUdpEndpointsEvictLocallyWithoutClosingAssociation();revokedVpnStillClosesWholeUdpAssociation();
        tcpDnsUsesUdpOnlyResolverAndHandlesTruncation(false);tcpDnsUsesUdpOnlyResolverAndHandlesTruncation(true);
        dnsReplyValidationRejectsWrongQueries();
        System.out.println("PASS 12 gateway tests: auth, TCP half-close, UDP/DNS, revocation, bounded cleanup, malformed frame, isolated UDP error, 96 destinations without association reset, UDP VPN revocation, TCP DNS to UDP-only resolver, truncated DNS TCP fallback, DNS reply matching/SERVFAIL");
    }
}
