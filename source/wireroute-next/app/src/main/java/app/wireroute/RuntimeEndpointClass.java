package app.wireroute;

/** Coarse diagnostics only. Numeric parsing is local and never performs DNS. */
final class RuntimeEndpointClass {
    private RuntimeEndpointClass() { }

    static String classify(String input) {
        if (input == null) return "UNKNOWN";
        String host = input.trim();
        if (host.isEmpty() || host.length() > 512) return "UNKNOWN";
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length()-1);
        byte[] v4 = parse4(host);
        if (v4 != null) return classify4(v4);
        if (host.indexOf(':') >= 0) {
            byte[] v6 = parse6(host);
            return v6 == null ? "UNKNOWN" : classify6(v6);
        }
        if (host.matches("[0-9.]+")) return "UNKNOWN";
        return host.matches("[A-Za-z0-9_.-]+") ? "HOSTNAME_UNVERIFIED" : "UNKNOWN";
    }

    static String merge(String previous, String current) {
        if (previous == null) return known(current) ? current : "UNKNOWN";
        return known(previous) && previous.equals(current) ? previous : "UNKNOWN";
    }

    private static boolean known(String value) {
        return "LOOPBACK".equals(value) || "DESKTOP_LAN".equals(value)
            || "PRIVATE_LAN".equals(value) || "REMOTE_NUMERIC".equals(value)
            || "HOSTNAME_UNVERIFIED".equals(value) || "UNKNOWN".equals(value);
    }

    private static String classify4(byte[] bytes) {
        int first = bytes[0]&255, second = bytes[1]&255, third = bytes[2]&255, fourth = bytes[3]&255;
        if (first == 127) return "LOOPBACK";
        if (first == 192 && second == 168 && third == 0 && (fourth == 176 || fourth == 66)) return "DESKTOP_LAN";
        if (first == 10 || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168) || (first == 169 && second == 254)
            || (first == 100 && second >= 64 && second <= 127)) return "PRIVATE_LAN";
        if (first == 0 || first >= 224) return "UNKNOWN";
        return "REMOTE_NUMERIC";
    }

    private static String classify6(byte[] bytes) {
        boolean leadingZero = true;
        for (int index=0; index<15; index++) leadingZero &= bytes[index] == 0;
        if (leadingZero && bytes[15] == 1) return "LOOPBACK";
        if (leadingZero && bytes[15] == 0) return "UNKNOWN";
        boolean mapped = true;
        for (int index=0; index<10; index++) mapped &= bytes[index] == 0;
        if (mapped && (bytes[10]&255) == 255 && (bytes[11]&255) == 255)
            return classify4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        int first = bytes[0]&255, second = bytes[1]&255;
        if ((first&254) == 252 || (first == 254 && ((second&192) == 128 || (second&192) == 192))) return "PRIVATE_LAN";
        return first == 255 ? "UNKNOWN" : "REMOTE_NUMERIC";
    }

    private static byte[] parse4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] value = new byte[4];
        for (int index=0; index<4; index++) {
            if (!parts[index].matches("[0-9]{1,3}")) return null;
            int number = Integer.parseInt(parts[index]);
            if (number > 255) return null;
            value[index] = (byte)number;
        }
        return value;
    }

    private static byte[] parse6(String raw) {
        String host = raw;
        int zone = host.indexOf('%');
        if (zone >= 0) {
            if (!host.substring(zone+1).matches("[A-Za-z0-9_.-]+")) return null;
            host = host.substring(0, zone);
        }
        if (host.indexOf('.') >= 0) {
            int colon = host.lastIndexOf(':');
            if (colon < 0) return null;
            byte[] tail = parse4(host.substring(colon+1));
            if (tail == null) return null;
            host = host.substring(0, colon+1) + Integer.toHexString(((tail[0]&255)<<8)|(tail[1]&255))
                + ":" + Integer.toHexString(((tail[2]&255)<<8)|(tail[3]&255));
        }
        String[] halves = host.split("::", -1);
        if (halves.length > 2) return null;
        int[] head = groups(halves[0]);
        int[] tail = halves.length == 2 ? groups(halves[1]) : new int[0];
        if (head == null || tail == null) return null;
        int total = head.length+tail.length;
        if ((halves.length == 1 && total != 8) || (halves.length == 2 && total >= 8)) return null;
        byte[] value = new byte[16];
        for (int index=0; index<head.length; index++) put(value, index, head[index]);
        for (int index=0; index<tail.length; index++) put(value, 8-tail.length+index, tail[index]);
        return value;
    }

    private static int[] groups(String text) {
        if (text.isEmpty()) return new int[0];
        String[] parts = text.split(":", -1);
        if (parts.length > 8) return null;
        int[] result = new int[parts.length];
        for (int index=0; index<parts.length; index++) {
            if (!parts[index].matches("[0-9A-Fa-f]{1,4}")) return null;
            result[index] = Integer.parseInt(parts[index], 16);
        }
        return result;
    }
    private static void put(byte[] bytes, int index, int group) {
        bytes[index*2] = (byte)(group>>>8);
        bytes[index*2+1] = (byte)group;
    }
}
