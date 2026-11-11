package hev.htproxy;
public final class TProxyService {
 public static native boolean TProxyStartService(String path,int fd);
 public static native boolean TProxyStopService();
 public static native boolean TProxyIsRunning();
 public static native long[] TProxyGetStats();
}
