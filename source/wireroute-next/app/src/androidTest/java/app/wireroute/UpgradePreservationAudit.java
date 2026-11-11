package app.wireroute;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Xml;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.xmlpull.v1.XmlPullParser;
import org.junit.Test;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Explicit, read-only upgrade audit. Never launches an Activity or edits user preferences. */
public final class UpgradePreservationAudit {
    @Test public void savedUserChoicesMatchThePreUpgradeCopy() throws Exception {
        assertEquals("Audit must be requested explicitly", "true",
                InstrumentationRegistry.getArguments().getString("verifyPreservedData"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Audit must target the actual installation", "app.wireroute.release263", context.getPackageName());
        File before = new File(context.getNoBackupFilesDir(), "before-3.1");
        assertTrue("Pre-upgrade copy exists", new File(before, "complete").isFile());
        Set<String> oldPackages = readSet(new File(before, "routing.xml"), "packages");
        SharedPreferences routing = context.getSharedPreferences("routing", Context.MODE_PRIVATE);
        Set<String> currentPackages = routing.getStringSet("packages", Collections.emptySet());
        assertEquals("Every previous package choice must remain", oldPackages, currentPackages);
        assertEquals("Previous default mode must remain", readString(new File(before, "routing.xml"), "mode"),
                routing.getString("mode", null));

        File oldProfile = new File(before, "profile.wrc");
        File currentProfile = new File(context.getNoBackupFilesDir(), "profile.wrc");
        assertTrue("Previous encrypted profile exists", oldProfile.isFile());
        assertArrayEquals("Encrypted active profile is unchanged",
                Files.readAllBytes(oldProfile.toPath()), Files.readAllBytes(currentProfile.toPath()));
        assertEquals("Previous profile name remains", readString(new File(before, "profile_metadata.xml"), "display_name"),
                context.getSharedPreferences("profile_metadata", 0).getString("display_name", null));

        Set<String> oldDomains = readSet(new File(before, "wireroute_site_routes_v1.xml"), "domains");
        assertEquals("Original site list is retained", oldDomains,
                context.getSharedPreferences("wireroute_site_routes_v1", 0).getStringSet("domains", Collections.emptySet()));
        JSONArray sites = new JSONArray(context.getSharedPreferences("site_library_v1", 0).getString("sites", "[]"));
        Set<String> migratedUrls = new HashSet<>();
        for (int i = 0; i < sites.length(); i++) migratedUrls.add(sites.getJSONObject(i).getString("url"));
        for (String domain : oldDomains)
            assertTrue("Previous site is visible in the new list", migratedUrls.contains(SiteLibrary.normalize(domain)));

        Bundle report = new Bundle();
        report.putString("upgradeAudit", "PASS: " + oldPackages.size() + " package choices retained; "
                + oldDomains.size() + " previous sites retained; " + sites.length() + " current sites; encrypted profile unchanged");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }

    private static Set<String> readSet(File file, String name) throws Exception {
        Set<String> result = new HashSet<>();
        if (!file.isFile()) return result;
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            boolean inSet = false;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "set".equals(parser.getName()))
                    inSet = name.equals(parser.getAttributeValue(null, "name"));
                else if (inSet && event == XmlPullParser.START_TAG && "string".equals(parser.getName()))
                    result.add(parser.nextText());
                else if (event == XmlPullParser.END_TAG && "set".equals(parser.getName())) inSet = false;
            }
        }
        return result;
    }

    private static String readString(File file, String name) throws Exception {
        if (!file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next())
                if (event == XmlPullParser.START_TAG && "string".equals(parser.getName())
                        && name.equals(parser.getAttributeValue(null, "name"))) return parser.nextText();
        }
        return null;
    }
}
