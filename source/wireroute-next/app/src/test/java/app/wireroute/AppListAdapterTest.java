package app.wireroute;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppListAdapterTest {
    @Test
    public void textSearchHonorsTheVisibleCategory() {
        assertFalse(AppListAdapter.matchesForDisplay(
                "Chrome",
                "com.android.chrome",
                "chrome",
                AppFilter.RECOMMENDED,
                false,
                false,
                false
        ));
        assertFalse(AppListAdapter.matchesForDisplay(
                "Google",
                "com.google.android.googlequicksearchbox",
                "googlequicksearchbox",
                AppFilter.SELECTED,
                false,
                false,
                false
        ));
    }

    @Test
    public void emptySearchStillHonorsSelectedCategory() {
        assertFalse(AppListAdapter.matchesForDisplay(
                "Chrome",
                "com.android.chrome",
                "",
                AppFilter.SELECTED,
                true,
                true,
                false
        ));
        assertTrue(AppListAdapter.matchesForDisplay(
                "Chrome",
                "com.android.chrome",
                "",
                AppFilter.SELECTED,
                true,
                true,
                true
        ));
    }

    @Test
    public void linksCategoryShowsDiscoveredBrowser() {
        assertTrue(AppListAdapter.matchesForDisplay(
                "OnePlus Browser",
                "com.vendor.browser",
                "",
                AppFilter.LINKS,
                false,
                true,
                false
        ));
    }
}
