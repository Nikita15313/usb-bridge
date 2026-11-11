package app.wireroute;

import java.util.LinkedHashMap;
import java.util.Map;

/** Retains one egress for a TCP lifetime, including SYN/FIN retransmissions. */
final class UsbTcpDecisions {
    private static final class Entry {
        final boolean vpn;final long initialSequence;long last;
        Entry(boolean vpn,long sequence,long now){this.vpn=vpn;initialSequence=sequence;last=now;}
    }
    private final Map<String,Entry> entries=new LinkedHashMap<String,Entry>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,UsbTcpDecisions.Entry> entry){return size()>8192;}
    };
    Boolean find(UsbSplitPolicy.Packet packet,long now){
        if(packet.protocol!=6)return null;
        Entry entry=entries.get(packet.key());
        if(entry==null||now-entry.last>=300000||(packet.tcpSyn&&entry.initialSequence!=packet.tcpSequence))return null;
        entry.last=now;return entry.vpn;
    }
    void remember(UsbSplitPolicy.Packet packet,boolean vpn,long now){
        if(packet.protocol==6)entries.put(packet.key(),new Entry(vpn,packet.tcpSyn?packet.tcpSequence:-1,now));
    }
}
