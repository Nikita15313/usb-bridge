package local.usbbridge;
import java.util.*;
public final class PolishLogicTest {
 static int passed;static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;}
 public static void main(String[] args)throws Exception{
  Object session=new Object();TrafficRate rate=new TrafficRate();rate.sample(session,1000,0,0);rate.sample(session,1200,2000,4000);
  check(rate.up==10000&&rate.down==20000,"actual 200ms rate");check(rate.smoothUp>0&&rate.smoothUp<rate.up,"smooth starts immediately without fabricated rate");
  rate.sample(session,1200,3000,6000);check(rate.up==10000&&rate.down==20000,"duplicate timestamp preserves rate and accumulated bytes");
  rate.sample(session,1400,4000,8000);check(rate.smoothDown>rate.smoothUp,"directions independent");
  rate.sample(session,2000,4000,8000);check(rate.smoothDown==0&&rate.smoothUp==0,"600ms idle hard zero");
  rate.sample(session,2200,6000,10000);rate.sample(null,2201,0,0);check(rate.total==0&&rate.smoothDown==0,"disconnect zero");
  rate.sample(session,2400,100,200);check(rate.up==0&&rate.smoothUp==0,"new baseline");
  rate.sample(session,2600,90,190);check(rate.down==0&&rate.smoothDown==0,"counter rollback");
  rate.sample(session,2500,1000,1000);check(rate.up==0,"clock rollback");
  check(!SharePolicy.requiresVpn(SharePolicy.DIRECT,true),"direct independent of phoneVPN");
  check(SharePolicy.requiresVpn(SharePolicy.VPN,true),"encrypted requested while active/starting");
  check(!SharePolicy.requiresVpn(SharePolicy.VPN,false),"VPNoff ordinary sharing");
  check(!SharePolicy.vpnReady("starting",1000,1200,42),"not ready on starting");
  check(!SharePolicy.vpnReady("vpn",1000,17000,42),"not ready stale");
  check(!SharePolicy.vpnReady("vpn",1000,1200,0),"not ready missingnetwork");
  check(SharePolicy.vpnReady("vpn",1000,1200,42),"ready current network");
  Wire.Mux mux=new Wire.Mux(null,new Wire.Events(){public void status(String t){}public void closed(String t){}});
  Wire.Channel ch=new Wire.Channel(mux,1);ch.feed(2,new byte[]{1,2,3});ch.feed(2,new byte[]{4,5,6,7});byte[] read=new byte[10];
  check(ch.in.read(read,2,5)==5&&Arrays.equals(Arrays.copyOfRange(read,2,7),new byte[]{1,2,3,4,5}),"coalesce partial frames with destination offset");
  byte[] ack=mux.sends.remove();check(ack[0]==7&&Wire.number(ack,5)==5,"exact consumed credit");
  check(ch.in.read(read)==2&&read[0]==6&&read[1]==7,"return short available immediately");
  ack=mux.sends.remove();check(Wire.number(ack,5)==2&&ch.queued==0,"remaining credit");ch.feed(3,new byte[0]);
  check(ch.in.read(read)==-1,"FIN after payload");check(ch.in.read(read,0,0)==0,"zero read at EOF");
  boolean bounds=false;try{ch.in.read(read,-1,1);}catch(IndexOutOfBoundsException expected){bounds=true;}check(bounds,"read validation");
  System.out.println("PASS "+passed+" rate, mode and stream invariants");
 }
}
