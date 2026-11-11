package app.wireroute;

import app.wireroute.release263.R;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Uses a separate package on the real device; never touches a user profile or VPN permission. */
public final class RoutingUiSmokeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private MainActivity launch() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue("UI tests require the isolated package", context.getPackageName().endsWith(".uitest"));
        assertNotEquals("app.wireroute.release263", context.getPackageName());
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        assertEquals(TunnelController.State.DOWN, app.getTunnelController().getSnapshot().state);
        app.getRoutingPreferences().compareAndSetRouting(app.getRoutingPreferences().getRevision(),
                RoutingMode.ONLY_SELECTED, Collections.emptySet());
        return launchStored();
    }

    private MainActivity launchStored() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".uitest"));
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            final int[] count = {0};
            instrumentation.runOnMainSync(() -> count[0] = ((AppListAdapter)
                    ((android.widget.ListView) activity.findViewById(R.id.appsList)).getAdapter()).getTotalAppCount());
            if (count[0] > 0) return activity;
            Thread.sleep(100);
        }
        throw new AssertionError("Installed applications did not load");
    }

    @Test public void previousChoicesAndLegacySitesSurviveOpeningTheNewScreens() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".uitest"));
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        java.util.Set<String> previous = new java.util.HashSet<>(java.util.Arrays.asList(
                "com.android.chrome", "org.telegram.messenger", "old.uninstalled.package"));
        RoutingPreferences prefs = app.getRoutingPreferences();
        prefs.compareAndSetRouting(prefs.getRevision(), RoutingMode.ONLY_SELECTED, previous);
        context.getSharedPreferences("site_library_v1", 0).edit().clear().commit();
        context.getSharedPreferences("wireroute_site_routes_v1", 0).edit().putStringSet("domains",
                new java.util.HashSet<>(java.util.Arrays.asList("gemini.google.com", "example.org"))).commit();
        MainActivity activity = launchStored();
        try {
            instrumentation.runOnMainSync(() -> {
                activity.showSection("apps"); activity.showSection("sites"); activity.showSection("profiles");
                assertEquals(RoutingMode.ONLY_SELECTED, prefs.getMode());
                assertEquals("Opening screens must retain every previous choice", previous, prefs.getSelectedPackages());
                SiteLibrary sites = new SiteLibrary(activity);
                assertEquals(2, sites.list().size());
                assertTrue(sites.list().stream().anyMatch(site -> site.url.equals("https://gemini.google.com")));
                assertEquals("Repeated opening must not duplicate sites", 2, new SiteLibrary(activity).list().size());
                assertEquals(2, context.getSharedPreferences("wireroute_site_routes_v1", 0).getStringSet("domains", Collections.emptySet()).size());
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void draftSurvivesActivityRecreationAndCanBeCancelled() throws Exception {
        MainActivity original = launch();
        instrumentation.runOnMainSync(() -> {
            original.showSection("apps");
            ((EditText) original.findViewById(R.id.searchInput)).setText("com.android.chrome");
            android.widget.ListView list = original.findViewById(R.id.appsList);
            ((AppListAdapter) list.getAdapter()).getView(0, null, list).findViewById(R.id.appVpnButton).performClick();
        });
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        instrumentation.runOnMainSync(original::recreate);
        MainActivity replacement = (MainActivity) monitor.waitForActivityWithTimeout(10_000);
        instrumentation.removeMonitor(monitor);
        assertNotNull("Activity must recreate", replacement);
        assertNotSame("Recreation must return the new instance", original, replacement);
        Thread.sleep(300);
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals("com.android.chrome", ((EditText) replacement.findViewById(R.id.searchInput)).getText().toString());
                assertEquals("Сохранить", ((Button) replacement.findViewById(R.id.applyChangesButton)).getText().toString());
                assertEquals(android.view.View.VISIBLE, replacement.findViewById(R.id.pendingChangesCard).getVisibility());
                assertEquals(android.view.View.GONE, replacement.findViewById(R.id.statusDot).getVisibility());
                replacement.findViewById(R.id.discardChangesButton).performClick();
                assertEquals(android.view.View.GONE, replacement.findViewById(R.id.pendingChangesCard).getVisibility());
                assertFalse(((WireRouteApplication) replacement.getApplication()).getRoutingPreferences()
                        .getSelectedPackages().contains("com.android.chrome"));
            });
        } finally { instrumentation.runOnMainSync(replacement::finish); }
    }

    @Test public void geminiIsVisibleAndItsNetworkOwnerIsGoogle() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                activity.showSection("apps");
                ((EditText) activity.findViewById(R.id.searchInput)).setText("Gemini");
                AppListAdapter adapter = (AppListAdapter) ((android.widget.ListView) activity.findViewById(R.id.appsList)).getAdapter();
                InstalledApp gemini = null;
                for (int i = 0; i < adapter.getCount(); i++)
                    if (adapter.getItem(i).packageName.equals("com.google.android.apps.bard")) gemini = adapter.getItem(i);
                assertNotNull("The installed Gemini launcher must be searchable", gemini);
                assertFalse(gemini.systemComponent);
                assertEquals("com.google.android.googlequicksearchbox", gemini.routingPackage);
                assertTrue(gemini.routingPackages.contains("com.google.android.apps.bard"));
                assertTrue(gemini.routingPackages.contains("com.google.android.googlequicksearchbox"));
                assertEquals(TunnelController.State.DOWN, ((WireRouteApplication) activity.getApplication()).getTunnelController().getSnapshot().state);
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void editingProfilePreservesNewerAppChoicesAndDraft() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue("Profile editing tests require the isolated package", context.getPackageName().endsWith(".uitest"));
        WireRouteApplication app = (WireRouteApplication) context.getApplicationContext();
        assertEquals(TunnelController.State.DOWN, app.getTunnelController().getSnapshot().state);
        java.util.Set<String> saved = new java.util.HashSet<>(java.util.Arrays.asList(
                "org.telegram.messenger", "com.android.chrome", "old.uninstalled.package"));
        java.util.Set<String> draft = new java.util.HashSet<>(saved);
        draft.add("com.google.android.googlequicksearchbox");
        try {
            for (RoutingMode mode : RoutingMode.values()) {
                String directive = mode == RoutingMode.ONLY_SELECTED
                        ? "IncludedApplications" : "ExcludedApplications";
                // A synthetic profile retains its original list, while the user has
                // subsequently chosen different apps through WireRoute's app screen.
                String original = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
                        + "Address = 198.18.0.2/32\nDNS = 1.1.1.1\n" + directive + " = original.profile.app\n"
                        + "[Peer]\nPublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
                        + "Endpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0\n";
                app.getConfigStore().save(original, "Original " + mode.name());
                RoutingPreferences prefs = app.getRoutingPreferences();
                prefs.compareAndSetRouting(prefs.getRevision(), mode, saved);
                MainActivity activity = launchStored();
                String renamed = "Renamed " + mode.name();
                String edited = original.replace("DNS = 1.1.1.1\n", "");
                try {
                    instrumentation.runOnMainSync(() -> {
                        activity.stageAppChoices(draft);
                        activity.saveProfileText(edited, renamed, true);
                    });
                    long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
                    boolean[] completed = {false};
                    while (!completed[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync(() -> completed[0] = renamed.equals(app.getConfigStore().getDisplayName())
                                && activity.findViewById(R.id.applyChangesButton).isEnabled());
                        if (!completed[0]) Thread.sleep(50);
                    }
                    assertTrue("Profile editing must finish", completed[0]);
                    assertEquals("Editing must save the new profile name", renamed, app.getConfigStore().getDisplayName());
                    assertTrue("Editing must apply the DNS change", ConfigUtils.parse(app.getConfigStore().load())
                            .getInterface().getDnsServers().isEmpty());
                    assertEquals("Editing must retain the newer saved app mode", mode, prefs.getMode());
                    assertEquals("Editing must retain every newer saved package", saved, prefs.getSelectedPackages());
                    instrumentation.waitForIdleSync();
                    instrumentation.runOnMainSync(() -> {
                        AppListAdapter adapter = (AppListAdapter) ((android.widget.ListView)
                                activity.findViewById(R.id.appsList)).getAdapter();
                        assertEquals("Editing must retain the unsaved app draft", draft, adapter.getSelectedPackages());
                        assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.pendingChangesCard).getVisibility());
                        activity.findViewById(R.id.discardChangesButton).performClick();
                        assertEquals("Discard must restore the newer saved selection", saved, adapter.getSelectedPackages());
                    });
                    assertEquals("Editing while disconnected must not start VPN", TunnelController.State.DOWN,
                            app.getTunnelController().getSnapshot().state);
                } finally { instrumentation.runOnMainSync(activity::finish); }
            }
        } finally {
            app.getConfigStore().clear();
            new java.io.File(context.getNoBackupFilesDir(), "profile-library.wrc").delete();
        }
    }

    @Test public void siteDraftRequiresSaveSurvivesRecreationAndDiscardRetainsSavedRoute() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue("Website tests require the isolated package", context.getPackageName().endsWith(".uitest"));
        context.getSharedPreferences("site_library_v1", 0).edit().clear().commit();
        context.getSharedPreferences("wireroute_site_routes_v1", 0).edit().clear().commit();
        SiteLibrary sites = new SiteLibrary(context);
        sites.add("Example", "example.org", true);
        MainActivity activity = launch();
        try {
            MainActivity initial = activity;
            instrumentation.runOnMainSync(() -> {
                initial.showSection("sites");
                routeButton(initial.findViewById(R.id.sitesContent), "Без VPN").performClick();
                assertTrue("A site route tap must leave the saved choice untouched", sites.list().get(0).vpn);
                assertTrue("The common Save button must become enabled", initial.findViewById(R.id.applyChangesButton).isEnabled());
                initial.findViewById(R.id.applyChangesButton).performClick();
                assertFalse("Site Save must persist the explicit direct choice", sites.list().get(0).vpn);
                assertEquals(android.view.View.GONE, initial.findViewById(R.id.pendingChangesCard).getVisibility());
                routeButton(initial.findViewById(R.id.sitesContent), "С VPN").performClick();
                siteSearch(initial.findViewById(R.id.sitesContent)).setText("example.org");
                siteFilter(initial.findViewById(R.id.sitesContent), "С VPN").setChecked(true);
            });
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
            instrumentation.runOnMainSync(initial::recreate);
            MainActivity replacement = (MainActivity) monitor.waitForActivityWithTimeout(10_000);
            instrumentation.removeMonitor(monitor);
            assertNotNull("The site screen must recreate", replacement);
            activity = replacement;
            instrumentation.waitForIdleSync();
            android.content.IntentFilter links = new android.content.IntentFilter(Intent.ACTION_VIEW);
            links.addCategory(Intent.CATEGORY_BROWSABLE); links.addDataScheme("https");
            // Block any accidental external launch if the draft gate regresses.
            Instrumentation.ActivityMonitor browser = instrumentation.addMonitor(links,
                    new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
            try {
            instrumentation.runOnMainSync(() -> {
                android.view.View content = replacement.findViewById(R.id.sitesContent);
                assertEquals("Website search must survive recreation", "example.org", siteSearch(content).getText().toString());
                assertTrue("Website route filter must survive recreation", siteFilter(content, "С VPN").isChecked());
                assertEquals("The pending site choice must survive recreation", android.view.View.VISIBLE,
                        replacement.findViewById(R.id.pendingChangesCard).getVisibility());
                assertFalse("Recreation must not save the pending site choice", sites.list().get(0).vpn);
                replacement.findViewById(R.id.discardChangesButton).performClick();
                assertEquals(android.view.View.GONE, replacement.findViewById(R.id.pendingChangesCard).getVisibility());
                assertFalse("Discard must retain the saved direct route", sites.list().get(0).vpn);
                assertTrue("VPN filter must exclude the discarded direct route", containsText(content, "В списке: 0"));
                sites.add("Added again from collection", "https://example.org", true);
                replacement.showSection("sites");
                assertEquals("Duplicate addition must retain one site", 1, sites.list().size());
                assertFalse("Duplicate addition must retain the user's saved direct route", sites.list().get(0).vpn);
                siteFilter(content, "Все").setChecked(true);
                RoutingPreferences prefs = ((WireRouteApplication) replacement.getApplication()).getRoutingPreferences();
                java.util.Set<String> savedApps = prefs.getSelectedPackages();
                replacement.stageAppChoices(Collections.singleton("com.android.chrome"));
                routeButton(content, "Открыть").performClick();
                assertEquals("Opening a site must not save an unrelated app draft", savedApps, prefs.getSelectedPackages());
                assertTrue("Opening a site must retain the pending app draft", replacement.hasRoutingDraft());
                assertEquals("A pending app draft must block browser launch", 0, browser.getHits());
                replacement.findViewById(R.id.discardChangesButton).performClick();
            });
            } finally { instrumentation.removeMonitor(browser); }
            assertEquals("Site Save must not connect VPN", TunnelController.State.DOWN,
                    ((WireRouteApplication) context.getApplicationContext()).getTunnelController().getSnapshot().state);
        } finally {
            MainActivity finished = activity;
            instrumentation.runOnMainSync(finished::finish);
            context.getSharedPreferences("site_library_v1", 0).edit().clear().commit();
        }
    }

    @Test public void malformedSiteDocumentIsNeverOverwrittenByMigrationOrNewAddition() {
        Context context = instrumentation.getTargetContext();
        assertTrue("Malformed-data tests require the isolated package", context.getPackageName().endsWith(".uitest"));
        android.content.SharedPreferences storage = context.getSharedPreferences("site_library_v1", 0);
        android.content.SharedPreferences legacy = context.getSharedPreferences("wireroute_site_routes_v1", 0);
        // A syntactically valid document with a malformed item must not be rewritten
        // into only the readable prefix, even when a legacy URL could be appended.
        String raw = "[{\"id\":\"original\",\"name\":\"Old site\",\"url\":\"https://original.example\",\"vpn\":false},{\"name\":\"Broken entry\"}]";
        try {
            for (java.util.Set<String> oldDomains : java.util.Arrays.asList(Collections.<String>emptySet(),
                    Collections.singleton("legacy.example"))) {
                storage.edit().clear().putString("sites", raw).commit();
                legacy.edit().clear().putStringSet("domains", oldDomains).commit();
                SiteLibrary sites = new SiteLibrary(context);
                assertFalse("The malformed list must be identified", sites.isReadable());
                assertEquals("Opening the new library must retain the exact old document", raw, storage.getString("sites", null));
                assertFalse("An unreadable document must not mark migration complete", storage.getBoolean("legacy_imported", false));
                try { sites.add("New", "new.example", true); fail("A new addition must not replace an unreadable list"); }
                catch (SiteLibrary.UnreadableSitesException expected) { }
                assertEquals("A failed addition must retain the exact old document", raw, storage.getString("sites", null));
                assertEquals("Old legacy URL choices must remain untouched", oldDomains,
                        legacy.getStringSet("domains", Collections.emptySet()));
            }
        } finally { storage.edit().clear().commit(); legacy.edit().clear().commit(); }
    }

    @Test public void appCountSummaryUsesRussianPluralForms() {
        Context context = instrumentation.getTargetContext();
        assertTrue("Resource tests require the isolated package", context.getPackageName().endsWith(".uitest"));
        android.content.res.Configuration russian = new android.content.res.Configuration(context.getResources().getConfiguration());
        russian.setLocale(java.util.Locale.forLanguageTag("ru"));
        android.content.res.Resources resources = context.createConfigurationContext(russian).getResources();
        assertEquals("С VPN: 1 приложение", resources.getQuantityString(R.plurals.route_only_summary_count, 1, 1));
        assertEquals("С VPN: 2 приложения", resources.getQuantityString(R.plurals.route_only_summary_count, 2, 2));
        assertEquals("С VPN: 5 приложений", resources.getQuantityString(R.plurals.route_only_summary_count, 5, 5));
        assertEquals("С VPN: 21 приложение", resources.getQuantityString(R.plurals.route_only_summary_count, 21, 21));
    }

    private static Button routeButton(android.view.View root, String text) {
        if (root instanceof Button && !(root instanceof RadioButton)
                && text.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button found = findRouteButton(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Website route button missing: " + text);
    }
    private static Button findRouteButton(android.view.View root, String text) {
        try { return routeButton(root, text); } catch (AssertionError missing) { return null; }
    }
    private static EditText siteSearch(android.view.View root) {
        if (root instanceof EditText && "Найти сайт".contentEquals(((EditText) root).getHint())) return (EditText) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                try { return siteSearch(group.getChildAt(index)); } catch (AssertionError missing) { }
            }
        }
        throw new AssertionError("Website search input missing");
    }
    private static RadioButton siteFilter(android.view.View root, String text) {
        if (root instanceof RadioButton && text.contentEquals(((RadioButton) root).getText())) return (RadioButton) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                try { return siteFilter(group.getChildAt(index), text); } catch (AssertionError missing) { }
            }
        }
        throw new AssertionError("Website filter missing: " + text);
    }
    private static boolean containsText(android.view.View root, String text) {
        if (root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) root).getText())) return true;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++)
                if (containsText(group.getChildAt(index), text)) return true;
        }
        return false;
    }

    @Test public void routeChoiceRequiresSaveAndRevertingClearsTheDraft() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                assertTrue("Default mode must become editable after the app catalogue loads",
                        activity.findViewById(R.id.modeOnlySelected).isEnabled());
                assertTrue("Both default mode choices must be editable after loading",
                        activity.findViewById(R.id.modeBypassSelected).isEnabled());
                activity.showSection("apps");
                ((EditText) activity.findViewById(R.id.searchInput)).setText("com.android.chrome");
                android.widget.ListView list = activity.findViewById(R.id.appsList);
                AppListAdapter adapter = (AppListAdapter) list.getAdapter();
                assertEquals(1, adapter.getCount());
                android.view.View row = adapter.getView(0, null, list);
                row.findViewById(R.id.appVpnButton).performClick();
                RoutingPreferences prefs = ((WireRouteApplication) activity.getApplication()).getRoutingPreferences();
                assertFalse("A row tap must not persist", prefs.getSelectedPackages().contains("com.android.chrome"));
                Button save = activity.findViewById(R.id.applyChangesButton);
                assertTrue(save.isEnabled()); assertEquals("Сохранить", save.getText().toString());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.pendingChangesCard).getVisibility());
                row = adapter.getView(0, null, list);
                row.findViewById(R.id.appDirectButton).performClick();
                assertEquals("Reverting must remove the pending state", android.view.View.GONE,
                        activity.findViewById(R.id.pendingChangesCard).getVisibility());
                row = adapter.getView(0, null, list);
                row.findViewById(R.id.appVpnButton).performClick();
                activity.showSection("connection"); activity.showSection("apps");
                assertTrue("Navigation must retain the draft", save.isEnabled());
                save.performClick();
                assertTrue(prefs.getSelectedPackages().contains("com.android.chrome"));
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.pendingChangesCard).getVisibility());
                assertEquals("Saving while disconnected must not start VPN", TunnelController.State.DOWN,
                        ((WireRouteApplication) activity.getApplication()).getTunnelController().getSnapshot().state);
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void searchKeepsRouteFilterAndSystemComponentsAreSeparated() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                activity.showSection("apps");
                AppListAdapter adapter = (AppListAdapter) ((android.widget.ListView) activity.findViewById(R.id.appsList)).getAdapter();
                for (int i = 0; i < adapter.getCount(); i++) assertFalse(adapter.getItem(i).systemComponent);
                ((RadioButton) activity.findViewById(R.id.filterVpn)).setChecked(true);
                ((EditText) activity.findViewById(R.id.searchInput)).setText("Chrome");
                assertTrue("VPN chip must remain checked", ((RadioButton) activity.findViewById(R.id.filterVpn)).isChecked());
                assertEquals("Search must respect the empty VPN filter", 0, adapter.getCount());
                ((RadioButton) activity.findViewById(R.id.filterAll)).setChecked(true);
                ((EditText) activity.findViewById(R.id.searchInput)).setText("");
                ((Spinner) activity.findViewById(R.id.appCategory)).setSelection(1);
            });
            Thread.sleep(300); // Spinner selection is delivered after the next layout frame.
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                AppListAdapter adapter = (AppListAdapter) ((android.widget.ListView) activity.findViewById(R.id.appsList)).getAdapter();
                assertTrue("The phone has system components", adapter.getCount() > 0);
                for (int i = 0; i < adapter.getCount(); i++) assertTrue("System filter contains " + adapter.getItem(i).packageName, adapter.getItem(i).systemComponent);
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
