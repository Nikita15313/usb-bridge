package app.wireroute;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed single-owned-IPv4 subtraction. Pure numeric math, no DNS or config IO. */
final class StationRoutePolicy {
    static final long STATION = 0xc0a800ebL; //192.168.0.235
    private StationRoutePolicy() { }
    static final class Route {
        final InetAddress address;
        final int prefix;
        Route(InetAddress address,int prefix) { this.address=address; this.prefix=prefix; }
    }
    static final class Plan {
        final List<Route> routes;
        final boolean stationExcluded;
        final boolean preserveIpv4Family;
        Plan(List<Route> routes,boolean stationExcluded) {
            this.routes=Collections.unmodifiableList(new ArrayList<>(routes));
            this.stationExcluded=stationExcluded;
            preserveIpv4Family=stationExcluded && routes.isEmpty();
        }
    }
    static Plan subtract(InetAddress address,int prefix) {
        if(address==null) throw new IllegalArgumentException("route_address_required");
        byte[] bytes=address.getAddress(); int bits=bytes.length*8;
        if((bits!=32 && bits!=128) || prefix<0 || prefix>bits) throw new IllegalArgumentException("route_prefix_rejected");
        // Preserve Builder's rejection of noncanonical network addresses.
        for(int bit=prefix;bit<bits;bit++)
            if((bytes[bit/8] & (1<<(7-bit%8)))!=0) throw new IllegalArgumentException("route_host_bits_rejected");
        if(bits==128) return new Plan(Collections.singletonList(new Route(address,prefix)),false);
        long network=unsigned4(bytes), mask=mask(prefix);
        if((STATION&mask)!=network) return new Plan(Collections.singletonList(new Route(address,prefix)),false);
        List<Route> result=new ArrayList<>(32-prefix);
        // At each remaining bit, retain the sibling subtree opposite Station.
        // Their disjoint union is precisely the ancestor CIDR minus this /32.
        for(int next=prefix+1;next<=32;next++) {
            long sibling=(STATION&mask(next)) ^ (1L<<(32-next));
            result.add(new Route(address4(sibling),next));
        }
        return new Plan(result,true);
    }
    static long mask(int prefix) { return prefix==0 ? 0 : (0xffffffffL<<(32-prefix))&0xffffffffL; }
    static long unsigned4(byte[] bytes) {
        return ((bytes[0]&255L)<<24)|((bytes[1]&255L)<<16)|((bytes[2]&255L)<<8)|(bytes[3]&255L);
    }
    static InetAddress address4(long value) {
        try { return InetAddress.getByAddress(new byte[]{(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value}); }
        catch(UnknownHostException impossible) { throw new AssertionError("fixed_ipv4_length"); }
    }
}
