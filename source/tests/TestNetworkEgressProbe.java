import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.io.InputStream;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;
import local.usbbridge.probe.ProbeBody;

/** Shell-only probe of the real router TEST TUN. The URL connection and DNS
 * are bound to that exact network; HTTPS uses Android's normal trust and
 * hostname checks. A response through another default network cannot pass. */
public final class TestNetworkEgressProbe {
    private static Context context() throws Exception {
        if(android.os.Looper.myLooper()==null)android.os.Looper.prepare();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context system=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell=system.createPackageContext("com.android.shell",0);
        java.lang.reflect.Field attribution=shell.getClass().getDeclaredField("mAttributionSource");
        attribution.setAccessible(true);
        Object source=attribution.get(shell);
        attribution.set(shell,source.getClass().getMethod("withPackageName",String.class).invoke(source,"com.android.shell"));
        return shell;
    }
    private static void run(String[] args) throws Exception {
        if(args.length<2||args.length>5)
            throw new IllegalArgumentException("TestNetworkEgressProbe <TEST-network-handle|auto> <http(s)-url> [expected-status=204] [bytes sha256 | --read-body]");
        if(android.os.Process.myUid()!=2000)
            throw new IllegalStateException("Run as Android shell UID 2000");
        ConnectivityManager manager=(ConnectivityManager)context().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network=null;
        if("auto".equals(args[0])) {
            for(Network candidate:manager.getAllNetworks()) {
                NetworkCapabilities capabilities=manager.getNetworkCapabilities(candidate);
                if(capabilities!=null&&capabilities.hasTransport(7)&&!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if(network!=null)throw new IOException("More than one TEST network; supply exact handle");
                    network=candidate;
                }
            }
            if(network==null)throw new IOException("No TEST network");
        } else network=Network.fromNetworkHandle(Long.parseLong(args[0]));
        URL url=new URL(args[1]);
        if(!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
        if(url.getUserInfo()!=null)throw new IllegalArgumentException("Credentials in probe URL are not supported");
        int expected=args.length>=3?Integer.parseInt(args[2]):204;
        boolean readBody=args.length==4&&"--read-body".equals(args[3]);
        if(args.length==4&&!readBody)throw new IllegalArgumentException("Unknown fourth argument");
        if(readBody&&expected!=200)throw new IllegalArgumentException("--read-body requires expected HTTP 200");
        NetworkCapabilities caps=manager.getNetworkCapabilities(network);
        if(caps==null||!caps.hasTransport(7)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            throw new IOException("Handle is not a live non-VPN TEST network");
        LinkProperties properties=manager.getLinkProperties(network);
        System.out.println("UID="+android.os.Process.myUid()+" TEST_HANDLE="+network.getNetworkHandle()
                +" IFACE="+(properties==null?"unknown":properties.getInterfaceName())+" capabilities="+caps);
        long started=System.nanoTime();
        InetAddress[] addresses=network.getAllByName(url.getHost());
        System.out.println("DNS "+url.getHost()+" -> "+Arrays.toString(addresses));
        HttpURLConnection connection=(HttpURLConnection)network.openConnection(url,Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Connection","close");
            connection.setRequestProperty("Cache-Control","no-cache");
            if(readBody)connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();
            if(status!=expected)throw new IOException("Expected HTTP "+expected+", received "+status);
            if(readBody){
                if(connection.getContentLengthLong()>ProbeBody.LIMIT)throw new IOException("Response body exceeds 16 KiB");
                byte[] body;try(InputStream input=connection.getInputStream()){body=ProbeBody.read(input);}
                System.out.println("BODY bytes="+body.length+" "+ProbeBody.escaped(ProbeBody.text(body)));
            }
            if(args.length==5){
                long expectedBytes=Long.parseLong(args[3]);
                if(expectedBytes<1||expectedBytes>32L*1024*1024||!args[4].matches("(?i)[0-9a-f]{64}"))throw new IOException("Invalid payload expectations");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
                try(InputStream input=connection.getInputStream()){
                    byte[] buffer=new byte[32768];int count;
                    while((count=input.read(buffer))!=-1){bytes+=count;if(bytes>expectedBytes)throw new IOException("Payload too long");digest.update(buffer,0,count);}
                }
                StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(java.util.Locale.US,"%02x",value&255));
                if(bytes!=expectedBytes||!actual.toString().equalsIgnoreCase(args[4]))throw new IOException("Payload integrity mismatch");
                System.out.println("PAYLOAD bytes="+bytes+" sha256="+actual+" route="+connection.getHeaderField("X-Fixture-Route"));
            }
            if(connection instanceof HttpsURLConnection)
                System.out.println("TLS verified: "+((HttpsURLConnection)connection).getCipherSuite());
            long elapsed=(System.nanoTime()-started)/1000000;
            System.out.println("PASS TEST TUN egress: "+url+" HTTP="+status+" elapsed_ms="+elapsed);
        } finally { connection.disconnect(); }
    }
    public static void main(String[] args) {
        try {run(args);System.exit(0);}
        catch(Throwable failure){failure.printStackTrace(System.err);System.exit(1);}
    }
}
