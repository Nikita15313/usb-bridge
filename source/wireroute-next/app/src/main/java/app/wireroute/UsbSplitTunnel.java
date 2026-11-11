package app.wireroute;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** One Android TUN, two independent egress paths, selected by real socket UID. */
final class UsbSplitTunnel implements AutoCloseable {
    private final Context context;
    private final ConnectivityManager connectivity;
    private final UsbSplitPolicy policy;
    private final ParcelFileDescriptor tun;
    private final ParcelFileDescriptor[] encrypted,plain;
    private final UsbRelayServer relay;
    private final ExecutorService workers=Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService watch=Executors.newSingleThreadScheduledExecutor();
    private final BlockingQueue<byte[]> encryptedQueue=new ArrayBlockingQueue<>(256);
    private final BlockingQueue<byte[]> plainQueue=new ArrayBlockingQueue<>(256);
    private final Runnable lost;
    private final Object writeLock=new Object();
    private final UsbTcpDecisions tcp=new UsbTcpDecisions();
    private volatile boolean closed;
    private volatile long encryptedPackets,plainPackets,unknownOwners,droppedPackets;

    @androidx.annotation.RequiresApi(29)
    UsbSplitTunnel(Context context,ParcelFileDescriptor tun,UsbSplitPolicy policy,int mtu,Runnable lost)throws Exception{
        this.context=context;this.tun=tun;this.policy=policy;this.lost=lost;
        connectivity=context.getSystemService(ConnectivityManager.class);
        ParcelFileDescriptor[] first=null,second=null;UsbRelayServer server=null;
        try{
            first=packetPair();second=packetPair();
            server=new UsbRelayServer(context,UsbBridgeUnderlay.offeredRelaySession(context));
            encrypted=first;plain=second;relay=server;
            System.loadLibrary("hev-socks5-tunnel");
            File config=new File(context.getFilesDir(),"usb-split.yml");
            String yaml="tunnel:\n  name: wireroute-plain\n  mtu: "+mtu+"\n  ipv4: 198.18.8.1\n  ipv6: 'fd00::1'\n"
                    +"socks5:\n  address: 127.0.0.1\n  port: "+relay.port()+"\n  udp: tcp\n"
                    +"misc:\n  max-session-count: 256\n  connect-timeout: 15000\n"
                    +"  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";
            try(FileOutputStream out=new FileOutputStream(config)){out.write(yaml.getBytes(StandardCharsets.UTF_8));}
            if(!HevDatapath.start(this,config.getAbsolutePath(),plain[1].getFd()))throw new IOException("Plain USB route failed");
            workers.execute(this::dispatch);
            workers.execute(()->receive(encrypted[0]));
            workers.execute(()->receive(plain[0]));
            workers.execute(()->send(encrypted[0],encryptedQueue));
            workers.execute(()->send(plain[0],plainQueue));
            watch.scheduleWithFixedDelay(()->{
                try {
                    if(!closed&&!HevDatapath.isRunning(this))fail(new IOException("Plain USB native route stopped"));
                } catch(RuntimeException|LinkageError failure) {
                    fail(new IOException("Plain USB native health check failed",failure));
                }
            },1,1,TimeUnit.SECONDS);
        }catch(Exception|LinkageError failure){
            try{HevDatapath.stop(this);}catch(Throwable ignored){}
            if(server!=null)server.close();closePair(first);closePair(second);tun.close();workers.shutdownNow();watch.shutdownNow();throw failure;
        }
    }
    ParcelFileDescriptor backendDescriptor(){return encrypted[1];}

    @androidx.annotation.RequiresApi(29)
    private static ParcelFileDescriptor[] packetPair()throws Exception{
        FileDescriptor one=new FileDescriptor(),two=new FileDescriptor();
        Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM|OsConstants.SOCK_CLOEXEC,0,one,two);
        try{
            for(FileDescriptor fd:new FileDescriptor[]{one,two}){
                Os.setsockoptInt(fd,OsConstants.SOL_SOCKET,OsConstants.SO_SNDBUF,1024*1024);
                Os.setsockoptInt(fd,OsConstants.SOL_SOCKET,OsConstants.SO_RCVBUF,1024*1024);
            }
            return new ParcelFileDescriptor[]{ParcelFileDescriptor.dup(one),ParcelFileDescriptor.dup(two)};
        }finally{Os.close(one);Os.close(two);}
    }
    @androidx.annotation.RequiresApi(29)
    private void dispatch(){
        byte[] packet=new byte[65535];
        try{
            while(!closed){
                int count=Os.read(tun.getFileDescriptor(),packet,0,packet.length);
                if(count<=0)throw new EOFException("Android TUN closed");
                UsbSplitPolicy.Packet flow=UsbSplitPolicy.inspect(packet,count);
                boolean vpn=true;
                if(flow!=null){
                    long now=SystemClock.elapsedRealtime();
                    Boolean cached=tcp.find(flow,now);
                    if(cached!=null)vpn=cached;
                    else{
                        int uid=-1;
                        try{uid=connectivity.getConnectionOwnerUid(flow.protocol,flow.local(),flow.remote());}
                        catch(RuntimeException ignored){}
                        if(uid<0)unknownOwners++;
                        vpn=policy.encrypted(uid);
                        // Android's shared resolver may be netd-owned. Profile
                        // DNS always stays encrypted; app-owned DoH uses its UID.
                        if(flow.destinationPort==53)vpn=true;
                        // Pin even an unknown-owner decision. A later lookup,
                        // SYN retransmission or FIN must not switch a live flow.
                        tcp.remember(flow,vpn,now);
                    }
                }
                // Each egress has a bounded queue. Congestion on one path must
                // never freeze the other. TCP retransmits dropped packets.
                BlockingQueue<byte[]> queue=vpn?encryptedQueue:plainQueue;
                if(!queue.offer(java.util.Arrays.copyOf(packet,count))){droppedPackets++;continue;}
                if(vpn)encryptedPackets++;else plainPackets++;
            }
        }catch(Exception failure){fail(failure);}
    }
    private void send(ParcelFileDescriptor target,BlockingQueue<byte[]> queue){
        try{
            while(!closed){byte[] packet=queue.take();
                if(Os.write(target.getFileDescriptor(),packet,0,packet.length)!=packet.length)throw new IOException("Short packet write");
            }
        }catch(Exception failure){fail(failure);}
    }
    private void receive(ParcelFileDescriptor from){
        byte[] packet=new byte[65535];
        try{
            while(!closed){
                int count=Os.read(from.getFileDescriptor(),packet,0,packet.length);
                if(count<=0)throw new EOFException("Packet route closed");
                synchronized(writeLock){
                    if(!closed&&Os.write(tun.getFileDescriptor(),packet,0,count)!=count)throw new IOException("Short TUN write");
                }
            }
        }catch(Exception failure){fail(failure);}
    }
    private void fail(Exception failure){
        if(closed)return;
        android.util.Log.e("WireRouteUsb","Split USB route stopped",failure);
        close();lost.run();
    }
    String diagnostic(){return "split encrypted_packets="+encryptedPackets+" plain_packets="+plainPackets+" unknown_owner_packets="+unknownOwners+" dropped_packets="+droppedPackets;}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        watch.shutdownNow();
        android.util.Log.i("WireRouteUsb",diagnostic());
        try{HevDatapath.stop(this);}catch(Throwable ignored){}
        relay.close();closePair(encrypted);closePair(plain);
        try{tun.close();}catch(IOException ignored){}
        workers.shutdownNow();
        encryptedQueue.clear();plainQueue.clear();
    }
    private static void closePair(ParcelFileDescriptor[] pair){
        if(pair==null)return;
        for(ParcelFileDescriptor fd:pair)try{
            if(fd.getFileDescriptor().valid())try{Os.shutdown(fd.getFileDescriptor(),OsConstants.SHUT_RDWR);}catch(Exception ignored){}
            fd.close();
        }catch(IOException ignored){}
    }
}
