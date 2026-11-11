package app.wireroute;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class RoutingPreferences {
    private static final String PREFS = "routing";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PACKAGES = "packages";

    private final SharedPreferences preferences;
    private long revision;

    public RoutingPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized RoutingMode getMode() {
        return RoutingMode.fromStored(preferences.getString(KEY_MODE, null));
    }

    public synchronized boolean hasStoredMode() {
        return preferences.contains(KEY_MODE);
    }

    public synchronized void setMode(RoutingMode mode) {
        if (hasStoredMode() && getMode() == mode) return;
        revision++;
        preferences.edit().putString(KEY_MODE, mode.name()).apply();
    }

    public synchronized Set<String> getSelectedPackages() {
        return new HashSet<>(preferences.getStringSet(KEY_PACKAGES, Collections.emptySet()));
    }

    public synchronized void setSelectedPackages(Set<String> packages) {
        if (preferences.contains(KEY_PACKAGES) && getSelectedPackages().equals(packages)) return;
        revision++;
        preferences.edit().putStringSet(KEY_PACKAGES, new HashSet<>(packages)).apply();
    }

    /** Application-scoped revision: a recreated Activity cannot reset the edit history. */
    public synchronized long getRevision() {
        return revision;
    }

    /** A slow profile import must never overwrite a newer manual choice. */
    public synchronized boolean compareAndSetRouting(long expectedRevision,
            RoutingMode mode, Set<String> packages) {
        if (revision != expectedRevision) return false;
        revision++;
        preferences.edit().putString(KEY_MODE, mode.name())
                .putStringSet(KEY_PACKAGES, new HashSet<>(packages)).apply();
        return true;
    }

    public void addChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public void removeChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
