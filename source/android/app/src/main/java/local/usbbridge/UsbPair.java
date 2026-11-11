package local.usbbridge;
import java.io.*;
import java.util.Arrays;

/** Pairing only on a physical USB link or a loopback test connection.
 * The USB host is trusted: this is not a password protocol for an untrusted LAN.
 * The listener must never bind Wi-Fi/cellular interfaces. Each cable session
 * receives a fresh random record key; no short PIN or reusable shared password.
 */
final class UsbPair {
 static String accept(InputStream in,OutputStream out)throws IOException {
  if(!Arrays.equals(Wire.read(in,8),Wire.bytes("USBBR05!")))throw new IOException("Обновите USB Bridge на компьютере до версии 0.5");
  byte[] secret=Wire.random(32);out.write(secret);out.flush();
  StringBuilder s=new StringBuilder();for(byte b:secret)s.append(String.format(java.util.Locale.ROOT,"%02X",b&255));return s.toString();
 }
}
