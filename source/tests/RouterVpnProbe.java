import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run as shell with app_process. All traffic is explicitly pinned to the
 * supplied TEST network, proving its TUN -> SOCKS -> phone VPN data path. */
public final class RouterVpnProbe {
    static void barrierTests()throws Exception {
        java.lang.reflect.Method barrier=Class.forName("local.usbbridge.RouterService").getDeclaredMethod("stopDatapathVerified",boolean.class,Runnable.class,java.util.function.BooleanSupplier.class);
        barrier.setAccessible(true);
        Runnable forbidden=()->{throw new AssertionError("native code must not run without a TUN");};
        java.util.function.BooleanSupplier forbiddenRead=()->{throw new AssertionError("native state must not be read without a TUN");};
        if(!(Boolean)barrier.invoke(null,false,forbidden,forbiddenRead))throw new AssertionError("no-TUN barrier rejected");
        AtomicBoolean running=new AtomicBoolean(true);
        if(!(Boolean)barrier.invoke(null,true,(Runnable)()->running.set(false),(java.util.function.BooleanSupplier)running::get))throw new AssertionError("stopped datapath not confirmed");
        if((Boolean)barrier.invoke(null,true,(Runnable)()->{},(java.util.function.BooleanSupplier)()->true))throw new AssertionError("ACK despite native still running");
        if((Boolean)barrier.invoke(null,true,(Runnable)()->{throw new IllegalStateException("stop failed");},(java.util.function.BooleanSupplier)()->false))throw new AssertionError("ACK despite stop error");
        if((Boolean)barrier.invoke(null,true,(Runnable)()->{},(java.util.function.BooleanSupplier)()->{throw new UnsatisfiedLinkError("state unavailable");}))throw new AssertionError("ACK without native state");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> ack=worker.submit(()->(Boolean)barrier.invoke(null,true,(Runnable)()->{
                entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("not released");}catch(InterruptedException e){throw new IllegalStateException(e);}
            },(java.util.function.BooleanSupplier)()->false));
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("stop never entered");
            if(ack.isDone())throw new AssertionError("ACK preceded completion of native stop");
            release.countDown();if(!ack.get(2,TimeUnit.SECONDS))throw new AssertionError("completed stop not ACKed");
        }finally{release.countDown();worker.shutdownNow();}
        System.out.println("PASS 6 barrier cases: no TUN, verified stop, still running, stop error, unavailable state, delayed-stop race");
    }
    static void policyTests()throws Exception {
        Class<?> type=Class.forName("android.net.NetworkCapabilities");
        java.lang.reflect.Method accepts=Class.forName("local.usbbridge.RouterService").getDeclaredMethod("validVpnCapabilities",type,int.class);
        accepts.setAccessible(true);
        Object caps=type.getConstructor().newInstance();
        if((Boolean)accepts.invoke(null,null,10123))throw new AssertionError("null capabilities accepted");
        if((Boolean)accepts.invoke(null,caps,10123))throw new AssertionError("non-VPN network accepted");
        type.getMethod("addTransportType",int.class).invoke(caps,android.net.NetworkCapabilities.TRANSPORT_VPN);
        if(!(Boolean)accepts.invoke(null,caps,10123))throw new AssertionError("redacted owner rejected");
        type.getMethod("setOwnerUid",int.class).invoke(caps,10123);
        if(!(Boolean)accepts.invoke(null,caps,10123))throw new AssertionError("authenticated owner rejected");
        type.getMethod("setOwnerUid",int.class).invoke(caps,10999);
        if((Boolean)accepts.invoke(null,caps,10123))throw new AssertionError("mismatched visible owner accepted");
        System.out.println("PASS 5 real Android capability cases: missing, non-VPN, redacted owner, matching owner, wrong owner");
        barrierTests();
    }
    static String fetch(Network network, InetAddress destination, String host)throws Exception {
        try(Socket socket=network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(destination,80),8000);
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(("GET /generate_204 HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String line=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            if(line==null||!line.startsWith("HTTP/1.")||!line.contains(" 204 "))throw new IOException("HTTP response: "+line);
            return line;
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("policy")){policyTests();System.exit(0);return;}
        if(args.length<1)throw new IllegalArgumentException("RouterVpnProbe <TEST-network-handle> [fixture-hostname] [expected-ip]");
        if(android.os.Process.myUid()!=2000)throw new IllegalStateException("Run as Android shell UID 2000, not root");
        Network network=Network.fromNetworkHandle(Long.parseLong(args[0]));
        String hostname=args.length>1?args[1]:"usbbridge-proof.invalid";
        InetAddress expected=InetAddress.getByName(args.length>2?args[2]:"10.88.0.1");
        System.out.println("UID="+android.os.Process.myUid()+" TEST_HANDLE="+network.getNetworkHandle());
        System.out.println("PASS TEST TUN numeric TCP: "+fetch(network,expected,hostname));
        InetAddress[] resolved=network.getAllByName(hostname);
        if(!Arrays.asList(resolved).contains(expected))throw new IOException("DNS answer did not come from WG fixture: "+Arrays.toString(resolved));
        System.out.println("PASS TEST TUN DNS: "+hostname+" -> "+expected.getHostAddress());
        System.out.println("PASS TEST TUN hostname TCP: "+fetch(network,expected,hostname));
        System.out.println("PASS: TEST network -> native router -> exact phone VPN -> WireGuard fixture");
        System.exit(0);
    }
}
