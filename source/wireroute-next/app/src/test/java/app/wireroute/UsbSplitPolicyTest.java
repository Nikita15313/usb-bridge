package app.wireroute;

import static org.junit.Assert.*;
import java.util.Set;
import org.junit.Test;

public final class UsbSplitPolicyTest {
    @Test public void selectionKeepsUnselectedAppsOnPlainUsb(){
        UsbSplitPolicy p=new UsbSplitPolicy(RoutingMode.ONLY_SELECTED,Set.of(10123));
        assertTrue(p.encrypted(10123));assertFalse(p.encrypted(10124));
        assertTrue(p.encrypted(20123));assertFalse(p.encrypted(20124));
    }
    @Test public void bypassSelectionIsRespected(){
        UsbSplitPolicy p=new UsbSplitPolicy(RoutingMode.BYPASS_SELECTED,Set.of(10123));
        assertFalse(p.encrypted(10123));assertTrue(p.encrypted(10124));
    }
    @Test public void wifiGatewaySystemDnsUnknownAndIsolatedNeverEscapeEncryption(){
        UsbSplitPolicy p=new UsbSplitPolicy(RoutingMode.ONLY_SELECTED,Set.of(10123));
        for(int uid:new int[]{-1,0,1051,2000,99000,199000})assertTrue("uid="+uid,p.encrypted(uid));
    }
    @Test public void tcpTupleHasBothEndpointsAndFlags(){
        byte[] b=ipv4(6);UsbSplitPolicy.Packet p=UsbSplitPolicy.inspect(b,b.length);
        assertNotNull(p);assertEquals(6,p.protocol);assertEquals(12345,p.sourcePort);assertEquals(443,p.destinationPort);
        assertTrue(p.tcpSyn);assertFalse(p.tcpEnd);
        b[19]++;assertNotEquals(p.key(),UsbSplitPolicy.inspect(b,b.length).key());
        b[33]=1;assertTrue(UsbSplitPolicy.inspect(b,b.length).tcpEnd);
    }
    @Test public void udpTupleIncludesRemoteForSharedLocalPort(){
        byte[] b=ipv4(17);UsbSplitPolicy.Packet one=UsbSplitPolicy.inspect(b,b.length);
        assertNotNull(one);b[23]=53;UsbSplitPolicy.Packet two=UsbSplitPolicy.inspect(b,b.length);
        assertNotEquals(one.key(),two.key());
    }
    @Test public void malformedIpv4AndFragmentsHaveNoAppClassification(){
        byte[] b=ipv4(6);assertNull(UsbSplitPolicy.inspect(b,19));
        b[0]=0x44;assertNull(UsbSplitPolicy.inspect(b,b.length));b=ipv4(6);
        b[6]=0x20;assertNull(UsbSplitPolicy.inspect(b,b.length));b=ipv4(6);
        b[7]=1;assertNull(UsbSplitPolicy.inspect(b,b.length));b=ipv4(6);
        b[32]=0x10;assertNull(UsbSplitPolicy.inspect(b,b.length));b=ipv4(6);
        b[3]--;assertNull(UsbSplitPolicy.inspect(b,b.length));
    }
    @Test public void ipv6ExtensionIsParsedButFragmentsAreNotGuessed(){
        byte[] b=new byte[68];b[0]=0x60;b[5]=28;b[6]=0;b[40]=6;b[41]=0;
        b[48]=1;b[50]=1;b[60]=0x50;
        UsbSplitPolicy.Packet p=UsbSplitPolicy.inspect(b,b.length);assertNotNull(p);assertEquals(48,p.transportOffset);
        b[40]=44;assertNull(UsbSplitPolicy.inspect(b,b.length));
    }
    @Test public void malformedUdpLengthIsRejected(){
        byte[] b=ipv4(17);b[25]=7;assertNull(UsbSplitPolicy.inspect(b,b.length));
        b[25]=30;assertNull(UsbSplitPolicy.inspect(b,b.length));
    }
    @Test public void retransmitsAndClosedOwnerKeepTheirOriginalEgress(){
        UsbTcpDecisions cache=new UsbTcpDecisions();byte[] b=ipv4(6);
        UsbSplitPolicy.Packet syn=UsbSplitPolicy.inspect(b,b.length);
        assertNull(cache.find(syn,100));cache.remember(syn,false,100);
        assertEquals(Boolean.FALSE,cache.find(syn,500)); // same SYN retry
        b[33]=1;UsbSplitPolicy.Packet fin=UsbSplitPolicy.inspect(b,b.length);
        assertEquals(Boolean.FALSE,cache.find(fin,600));
        assertEquals(Boolean.FALSE,cache.find(fin,800)); // owner may be gone
        b[33]=2;b[27]=1; // a new TCP connection reusing the tuple
        assertNull(cache.find(UsbSplitPolicy.inspect(b,b.length),900));
    }
    @Test public void unknownInitialOwnerCannotMoveEstablishedTcpBetweenRoutes(){
        UsbTcpDecisions cache=new UsbTcpDecisions();byte[] b=ipv4(6);
        UsbSplitPolicy.Packet syn=UsbSplitPolicy.inspect(b,b.length);
        cache.remember(syn,true,100);b[33]=16;
        UsbSplitPolicy.Packet data=UsbSplitPolicy.inspect(b,b.length);
        assertEquals(Boolean.TRUE,cache.find(data,200));
        assertNull(cache.find(data,300200));
    }
    private static byte[] ipv4(int protocol){
        byte[] b=new byte[protocol==6?40:28];b[0]=0x45;b[3]=(byte)b.length;b[9]=(byte)protocol;
        b[12]=10;b[15]=2;b[16]=1;b[19]=1;b[20]=0x30;b[21]=0x39;b[22]=1;b[23]=(byte)187;
        if(protocol==6){b[32]=0x50;b[33]=2;}else b[25]=8;
        return b;
    }
}
