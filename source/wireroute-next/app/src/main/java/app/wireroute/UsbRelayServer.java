package app.wireroute;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

/** Local SOCKS stream bridge. Every outbound connection is a session-bound PFD. */
final class UsbRelayServer implements AutoCloseable {
    private final Context context;
    private final long session;
    private final ServerSocket listener;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<ParcelFileDescriptor> descriptors=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(256);
    private volatile boolean closed;

    UsbRelayServer(Context context,long session) throws IOException {
        this.context=context;this.session=session;
        listener=new ServerSocket(0,64,InetAddress.getByName("127.0.0.1"));
        workers.execute(()->{
            while(!closed)try{
                Socket socket=listener.accept();socket.setTcpNoDelay(true);
                synchronized(this){
                    if(closed||!slots.tryAcquire()){socket.close();continue;}
                    sockets.add(socket);
                    try{workers.execute(()->forward(socket));}
                    catch(RejectedExecutionException rejected){sockets.remove(socket);slots.release();socket.close();}
                }
            }catch(IOException failure){if(!closed)close();}
        });
    }
    int port(){return listener.getLocalPort();}
    private void forward(Socket socket){
        ParcelFileDescriptor fd=null;
        try{
            fd=UsbBridgeUnderlay.openRelay(context,session);
            synchronized(this){
                if(closed) return;
                descriptors.add(fd);
            }
            ParcelFileDescriptor active=fd;
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);
            OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(fd);
            workers.execute(()->{
                try{copy(socket.getInputStream(),output);Os.shutdown(active.getFileDescriptor(),OsConstants.SHUT_WR);}
                catch(Exception failure){closeSocket(socket);try{active.close();}catch(IOException ignored){}}
            });
            copy(input,socket.getOutputStream());
        }catch(Exception ignored){}
        finally{
            closeSocket(socket);sockets.remove(socket);
            if(fd!=null){descriptors.remove(fd);try{fd.close();}catch(IOException ignored){}}
            slots.release();
        }
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] bytes=new byte[65536];int count;
        while((count=in.read(bytes))!=-1)out.write(bytes,0,count);
    }
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        try{listener.close();}catch(IOException ignored){}
        for(Socket socket:sockets)closeSocket(socket);
        for(ParcelFileDescriptor fd:descriptors)try{fd.close();}catch(IOException ignored){}
        workers.shutdownNow();
    }
}
