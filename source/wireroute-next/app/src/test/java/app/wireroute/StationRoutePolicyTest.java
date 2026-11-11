package app.wireroute;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StationRoutePolicyTest {
    private static StationRoutePolicy.Plan plan(long network,int prefix) {
        return StationRoutePolicy.subtract(StationRoutePolicy.address4(network),prefix);
    }
    /** Exhaustive interval proof: sorted disjoint output covers every source
     * address except Station, without enumerating2^32 individual addresses. */
    private static void exactCoverage(long network,int prefix,StationRoutePolicy.Plan result) {
        long end=network+(1L<<(32-prefix))-1;
        boolean contains=StationRoutePolicy.STATION>=network && StationRoutePolicy.STATION<=end;
        assertEquals(contains,result.stationExcluded);
        List<StationRoutePolicy.Route> sorted=new ArrayList<>(result.routes);
        sorted.sort(Comparator.comparingLong(route -> StationRoutePolicy.unsigned4(route.address.getAddress())));
        long cursor=network,total=0;
        for(StationRoutePolicy.Route route:sorted) {
            long begin=StationRoutePolicy.unsigned4(route.address.getAddress());
            long size=1L<<(32-route.prefix), finish=begin+size-1;
            if(contains && cursor==StationRoutePolicy.STATION) cursor++;
            assertEquals(cursor,begin); assertTrue(finish<=end);
            assertEquals(begin,begin&StationRoutePolicy.mask(route.prefix));
            assertFalse(StationRoutePolicy.STATION>=begin && StationRoutePolicy.STATION<=finish);
            cursor=finish+1; total+=size;
        }
        if(contains && cursor==StationRoutePolicy.STATION) cursor++;
        assertEquals(end+1,cursor);
        assertEquals((1L<<(32-prefix))-(contains?1:0),total);
    }
    @Test public void defaultRouteProduces32ExactDisjointSiblings() {
        StationRoutePolicy.Plan result=plan(0,0);
        assertEquals(32,result.routes.size()); assertFalse(result.preserveIpv4Family); exactCoverage(0,0,result);
    }
    @Test public void everyAncestorPrefixExcludesOnlyStation() {
        for(int prefix=0;prefix<=32;prefix++) {
            long network=StationRoutePolicy.STATION&StationRoutePolicy.mask(prefix);
            StationRoutePolicy.Plan result=plan(network,prefix);
            assertEquals(32-prefix,result.routes.size()); exactCoverage(network,prefix,result);
        }
    }
    @Test public void singleStationRouteIsEmptyButAdjacentHostsRemain() {
        assertTrue(plan(StationRoutePolicy.STATION,32).routes.isEmpty());
        assertTrue(plan(StationRoutePolicy.STATION,32).preserveIpv4Family);
        for(long host:new long[]{0,1,StationRoutePolicy.STATION-1,StationRoutePolicy.STATION+1,0xfffffffeL,0xffffffffL}) {
            StationRoutePolicy.Plan result=plan(host,32); assertEquals(1,result.routes.size()); assertFalse(result.preserveIpv4Family); exactCoverage(host,32,result);
        }
    }
    @Test public void unrelatedRangesRemainOneUnchangedRoute() {
        for(long network:new long[]{0x0a000000L,0x7f000000L,0xc0a80100L,0xac100000L}) {
            StationRoutePolicy.Plan result=plan(network,24);
            assertFalse(result.stationExcluded); assertEquals(1,result.routes.size()); exactCoverage(network,24,result);
        }
    }
    @Test public void randomCanonicalRoutesHaveCompleteCoverage() {
        Random random=new Random(310321);
        for(int i=0;i<10000;i++) {
            int prefix=random.nextInt(33);
            long network=(random.nextInt()&0xffffffffL)&StationRoutePolicy.mask(prefix);
            exactCoverage(network,prefix,plan(network,prefix));
        }
    }
    @Test public void ipv6DefaultAndSpecificRoutesAreByteIdentical() throws Exception {
        for(int prefix:new int[]{0,32,64,128}) {
            byte[] bytes=new byte[16]; if(prefix!=0) { bytes[0]=0x20; bytes[1]=1; }
            InetAddress address=InetAddress.getByAddress(bytes);
            StationRoutePolicy.Plan result=StationRoutePolicy.subtract(address,prefix);
            assertFalse(result.stationExcluded); assertEquals(1,result.routes.size());
            assertArrayEquals(bytes,result.routes.get(0).address.getAddress()); assertEquals(prefix,result.routes.get(0).prefix);
        }
    }
    @Test public void invalidPrefixesHostBitsAndNullAreRejected() throws Exception {
        for(int prefix:new int[]{-1,33,128}) {
            try { plan(0,prefix); fail(); } catch(IllegalArgumentException expected) { }
        }
        try { StationRoutePolicy.subtract(null,0); fail(); } catch(IllegalArgumentException expected) { }
        try { plan(StationRoutePolicy.STATION,24); fail(); } catch(IllegalArgumentException expected) { }
        byte[] v6=new byte[16]; v6[15]=1;
        try { StationRoutePolicy.subtract(InetAddress.getByAddress(v6),64); fail(); } catch(IllegalArgumentException expected) { }
    }
}
