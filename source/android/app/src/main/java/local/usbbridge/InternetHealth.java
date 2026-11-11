package local.usbbridge;

/** HTTPS evidence belongs to one cable session; a failed website check is not cable loss. */
final class InternetHealth {
 private boolean proven;
 private int failures;
 private long lastSuccess;
 synchronized void success(long now){proven=true;failures=0;lastSuccess=now;}
 synchronized void failure(){if(failures<Integer.MAX_VALUE)failures++;}
 synchronized void reset(){proven=false;failures=0;lastSuccess=0;}
 synchronized boolean proven(){return proven;}
 synchronized int failures(){return failures;}
 synchronized long lastSuccess(){return lastSuccess;}
 synchronized boolean repeatedFailure(){return failures>=3;}
}
