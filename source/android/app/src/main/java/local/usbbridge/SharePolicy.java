package local.usbbridge;

/** Hotspot intent is independent of the phone's per-UID VPN policy. */
final class SharePolicy {
 static final String DIRECT="direct",VPN="vpn";
 static String normalize(String value){return DIRECT.equals(value)?DIRECT:VPN;}
 static boolean requiresVpn(String preference,boolean phoneRequiresVpn){return phoneRequiresVpn&&VPN.equals(normalize(preference));}
 static boolean vpnReady(String mode,long seenAt,long now,long handle){return "vpn".equals(mode)&&handle!=0&&seenAt>0&&now>=seenAt&&now-seenAt<15000;}
}
