package local.usbbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Soft AP associations are authoritative; DHCP metadata only supplies names.
 * A remembered lease must never make a disconnected client appear connected. */
final class HotspotClients {
    static final class Snapshot {
        final boolean known;
        final String clients;
        Snapshot(boolean known, String clients) { this.known = known; this.clients = clients; }
    }
    static final Snapshot UNKNOWN = new Snapshot(false, "[]");
    static final Snapshot EMPTY = new Snapshot(true, "[]");

    static final class Tracker {
        private Snapshot associated = UNKNOWN, metadata = UNKNOWN;
        synchronized Snapshot associations(Collection<?> clients) {
            associated = readAssociations(clients);
            return current();
        }
        synchronized Snapshot metadata(Collection<?> clients) {
            metadata = read(clients);
            return current();
        }
        synchronized Snapshot reset() {
            associated = metadata = UNKNOWN;
            return UNKNOWN;
        }
        private Snapshot current() { return enrich(associated, metadata); }
    }

    static String mac(Object value) {
        String result = clean(value).toLowerCase(Locale.ROOT);
        return result.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}") ? result : "";
    }

    static JSONObject unnamed(String mac) throws Exception {
        return new JSONObject().put("id", mac).put("name", "Устройство "
                + mac.substring(Math.max(0, mac.length() - 8)).toUpperCase(Locale.ROOT))
                .put("named", false).put("address", "");
    }

    static Snapshot readAssociations(Collection<?> clients) {
        if (clients == null) return UNKNOWN;
        SortedMap<String, JSONObject> rows = new TreeMap<>();
        for (Object client : clients) try {
            String mac = mac(get(client, "getMacAddress"));
            if (mac.isEmpty()) return UNKNOWN;
            rows.put(mac, unnamed(mac));
        } catch (Exception unsupported) { return UNKNOWN; }
        JSONArray result = new JSONArray();
        for (JSONObject row : rows.values()) result.put(row);
        return new Snapshot(true, result.toString());
    }

    static Snapshot enrich(Snapshot associated, Snapshot metadata) {
        if (!associated.known) return UNKNOWN;
        try {
            Map<String, JSONObject> names = new HashMap<>();
            JSONArray leases = new JSONArray(metadata.clients);
            for (int i = 0; i < leases.length(); i++) {
                JSONObject lease = leases.getJSONObject(i);
                String mac = mac(lease.optString("id"));
                if (!mac.isEmpty()) names.put(mac, lease);
            }
            JSONArray current = new JSONArray(associated.clients);
            for (int i = 0; i < current.length(); i++) {
                JSONObject client = current.getJSONObject(i), lease = names.get(client.getString("id"));
                if (lease == null) continue;
                if (lease.optBoolean("named")) client.put("name", lease.getString("name")).put("named", true);
                client.put("address", lease.optString("address"));
            }
            return new Snapshot(true, current.toString());
        } catch (Exception unsupported) { return associated; }
    }

    static String clean(Object value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        String text = String.valueOf(value).trim();
        for (int i = 0; i < text.length() && out.length() < 96; i++) {
            char ch = text.charAt(i);
            if (!Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT)
                out.append(ch == '|' ? ' ' : ch);
        }
        return out.toString().trim();
    }

    static Object get(Object item, String method) throws Exception {
        return item.getClass().getMethod(method).invoke(item);
    }

    static Snapshot read(Collection<?> clients) {
        if (clients == null) return UNKNOWN;
        // Sorted snapshots do not reorder devices every time Android sends its
        // intentionally unordered collection. Deduplicate dual-stack entries.
        SortedMap<String, JSONObject> rows = new TreeMap<>();
        boolean supported = clients.isEmpty();
        for (Object client : clients) try {
            int type = ((Number) get(client, "getTetheringType")).intValue();
            supported = true;
            if (type != 0) continue; // Wi-Fi only; exclude the USB cable itself.
            String mac = mac(get(client, "getMacAddress"));
            String name = "", address = "";
            try {
                for (Object entry : (Collection<?>) get(client, "getAddresses")) try {
                    String candidate = clean(get(entry, "getHostname"));
                    if (name.isEmpty() && !candidate.isEmpty()) name = candidate;
                    Object linkAddress = get(entry, "getAddress");
                    String ip = clean(get(get(linkAddress, "getAddress"), "getHostAddress"));
                    if (address.isEmpty() || ip.indexOf(':') < 0) address = ip;
                } catch (Exception absentAddress) { /* Still show the known client. */ }
            } catch (Exception absentAddresses) { /* Wi-Fi association may precede DHCP. */ }
            boolean named = !name.isEmpty();
            if (!named) name = mac.isEmpty() ? "Устройство" : "Устройство "
                    + mac.substring(Math.max(0, mac.length() - 8)).toUpperCase(Locale.ROOT);
            JSONObject row = new JSONObject().put("name", name).put("named", named)
                    .put("address", address).put("id", mac);
            rows.put(mac.isEmpty() ? name + "/" + address : mac, row);
        } catch (Exception unsupported) {
            // A firmware may lack client metadata. Missing metadata is not zero
            // connected devices and must not be presented as a precise count.
        }
        JSONArray result = new JSONArray();
        for (JSONObject row : rows.values()) result.put(row);
        return new Snapshot(supported, result.toString());
    }

    static String json(boolean enabled, boolean forwarding, boolean vpn, Snapshot snapshot) {
        return json(enabled, forwarding, vpn, snapshot, true);
    }
    static String json(boolean enabled, boolean forwarding, boolean vpn, Snapshot snapshot, boolean owned) {
        return json(enabled, forwarding, vpn, snapshot, owned, false);
    }
    static String json(boolean enabled, boolean forwarding, boolean vpn, Snapshot snapshot, boolean owned, boolean waitingForVpn) {
        try {
            JSONObject value = new JSONObject().put("enabled", enabled)
                    .put("owned", owned)
                    .put("routing", enabled && forwarding).put("source", vpn ? "vpn" : "pc")
                    .put("waitingForVpn", enabled && owned && vpn && !forwarding && waitingForVpn)
                    .put("clientsKnown", enabled && snapshot.known)
                    .put("clients", enabled ? new JSONArray(snapshot.clients) : new JSONArray());
            // Router's existing status protocol uses '|'. Keep this appended
            // JSON field backward compatible even with a hostile DHCP hostname.
            return value.toString().replace("|", "\\u007c");
        } catch (Exception impossible) { return "{}"; }
    }
}
