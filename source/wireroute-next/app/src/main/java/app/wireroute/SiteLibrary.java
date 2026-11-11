package app.wireroute;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Address book only. Routes are applied to the explicitly chosen browser, never inferred from DNS IPs. */
public final class SiteLibrary {
    public static final class UnreadableSitesException extends IllegalStateException {
        UnreadableSitesException(Throwable cause) { super("Saved website list is unreadable", cause); }
    }
    public static final class Site {
        public final String id, name, url;
        public boolean vpn;
        Site(String id, String name, String url, boolean vpn) {
            this.id = id; this.name = name; this.url = url; this.vpn = vpn;
        }
    }
    private final SharedPreferences preferences;
    public SiteLibrary(Context context) {
        preferences = context.getSharedPreferences("site_library_v1", Context.MODE_PRIVATE);
        migrateLegacySites(context);
    }

    private void migrateLegacySites(Context context) {
        if (preferences.getBoolean("legacy_imported", false)) return;
        SharedPreferences legacy = context.getSharedPreferences("wireroute_site_routes_v1", Context.MODE_PRIVATE);
        List<Site> current;
        try { current = readStoredSites(); }
        catch (Exception unreadable) { return; } // Keep the exact old document for recovery.
        java.util.Set<String> addresses = new java.util.HashSet<>();
        for (Site site : current) addresses.add(site.url);
        boolean added = false;
        for (String domain : legacy.getStringSet("domains", java.util.Collections.emptySet())) {
            try {
                String url = normalize(domain);
                if (addresses.add(url)) {
                    current.add(new Site(UUID.randomUUID().toString(), domain, url, true));
                    added = true;
                }
            } catch (IllegalArgumentException ignored) { /* Original entries remain untouched in the old store. */ }
        }
        if (added) save(current);
        // apply() updates the in-memory snapshot immediately; Android flushes it before lifecycle transitions.
        preferences.edit().putBoolean("legacy_imported", true).apply();
    }
    public List<Site> list() {
        try { return readStoredSites(); }
        catch (Exception ignored) { return new ArrayList<>(); }
    }
    public boolean isReadable() {
        try { readStoredSites(); return true; }
        catch (Exception ignored) { return false; }
    }
    private List<Site> readForUpdate() {
        try { return readStoredSites(); }
        catch (Exception unreadable) { throw new UnreadableSitesException(unreadable); }
    }
    private List<Site> readStoredSites() throws org.json.JSONException {
        List<Site> sites = new ArrayList<>();
        JSONArray items = new JSONArray(preferences.getString("sites", "[]"));
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            sites.add(new Site(item.getString("id"), item.getString("name"),
                    item.getString("url"), item.optBoolean("vpn", true)));
        }
        return sites;
    }
    public static String normalize(String raw) {
        String value = raw.trim();
        if (!value.contains("://")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter a website address");
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (!host.contains(".") || host.length() > 253)
            throw new IllegalArgumentException("Enter a website address");
        return uri.buildUpon().scheme(scheme.toLowerCase(Locale.ROOT))
                .encodedAuthority(host + (uri.getPort() == -1 ? "" : ":" + uri.getPort())).build().toString();
    }
    public void add(String name, String address, boolean vpn) {
        String url = normalize(address);
        List<Site> sites = readForUpdate();
        // Adding from the collection again must retain the user's saved route.
        // A route change belongs to the explicit VPN/Direct draft and Save action.
        for (Site site : sites) if (site.url.equals(url)) return;
        String title = name.trim().isEmpty() ? Uri.parse(url).getHost() : name.trim();
        sites.add(new Site(UUID.randomUUID().toString(), title, url, vpn)); save(sites);
    }
    public void route(String id, boolean vpn) {
        List<Site> sites = readForUpdate();
        for (Site site : sites) if (site.id.equals(id)) site.vpn = vpn;
        save(sites);
    }
    public void remove(String id) {
        List<Site> sites = readForUpdate(); sites.removeIf(site -> site.id.equals(id)); save(sites);
    }
    private void save(List<Site> sites) {
        JSONArray items = new JSONArray();
        try {
            for (Site site : sites) items.put(new JSONObject().put("id", site.id)
                    .put("name", site.name).put("url", site.url).put("vpn", site.vpn));
            preferences.edit().putString("sites", items.toString()).apply();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
