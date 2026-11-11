package app.wireroute;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Packet inspection is independent from Android so malformed packets are testable. */
final class UsbSplitPolicy {
    final RoutingMode mode;
    final Set<Integer> selected;
    UsbSplitPolicy(RoutingMode mode,Set<Integer> selected){this.mode=mode;this.selected=new HashSet<>(selected);}
    boolean encrypted(int uid){
        // System DNS and the shell-owned Wi-Fi gateway must use encryption.
        // An unresolved owner must never send a selected app outside its VPN.
        int appId=uid<0?uid:uid%100000;
        if(appId<10000||appId>=90000)return true;
        if(appId>=20000&&appId<30000)uid-=10000; // SDK sandbox follows its app.
        return mode==RoutingMode.ONLY_SELECTED?selected.contains(uid):!selected.contains(uid);
    }
    static final class Packet {
        final int protocol,sourcePort,destinationPort,transportOffset;
        final byte[] source,destination;
        final boolean tcpSyn,tcpEnd;
        final long tcpSequence;
        Packet(int p,int sp,int dp,int offset,byte[] s,byte[] d,boolean syn,boolean end,long sequence){
            protocol=p;sourcePort=sp;destinationPort=dp;transportOffset=offset;source=s;destination=d;tcpSyn=syn;tcpEnd=end;tcpSequence=sequence;
        }
        InetSocketAddress local()throws Exception{return new InetSocketAddress(InetAddress.getByAddress(source),sourcePort);}
        InetSocketAddress remote()throws Exception{return new InetSocketAddress(InetAddress.getByAddress(destination),destinationPort);}
        String key(){return protocol+":"+Arrays.toString(source)+":"+sourcePort+":"+Arrays.toString(destination)+":"+destinationPort;}
    }
    static Packet inspect(byte[] b,int n){
        if(n<20||n>b.length)return null;
        int version=(b[0]>>>4)&15,off,protocol;byte[] source,dest;
        if(version==4){
            off=(b[0]&15)*4;if(off<20||off>n||u16(b,2)!=n)return null;
            // Fragmented flows cannot be safely classified from later fragments.
            // Keep all fragments on the encrypted path instead of mixing egress.
            if((u16(b,6)&0x3fff)!=0)return null;
            protocol=b[9]&255;source=Arrays.copyOfRange(b,12,16);dest=Arrays.copyOfRange(b,16,20);
        }else if(version==6){
            if(n<40||u16(b,4)+40!=n)return null;
            off=40;protocol=b[6]&255;source=Arrays.copyOfRange(b,8,24);dest=Arrays.copyOfRange(b,24,40);
            for(int i=0;i<8&&(protocol==0||protocol==43||protocol==60);i++){
                if(off+2>n)return null;int next=b[off]&255;off+=((b[off+1]&255)+1)*8;
                if(off>n)return null;protocol=next;
            }
        }else return null;
        if(protocol!=6&&protocol!=17)return null;
        if(off+(protocol==6?20:8)>n)return null;
        if(protocol==6){int header=((b[off+12]&255)>>>4)*4;if(header<20||off+header>n)return null;}
        else if(u16(b,off+4)<8||off+u16(b,off+4)>n)return null;
        int flags=protocol==6?b[off+13]&255:0;
        long sequence=protocol==6?((long)u16(b,off+4)<<16)|u16(b,off+6):0;
        return new Packet(protocol,u16(b,off),u16(b,off+2),off,source,dest,(flags&2)!=0,(flags&5)!=0,sequence);
    }
    private static int u16(byte[] b,int off){return (b[off]&255)*256+(b[off+1]&255);}
}
