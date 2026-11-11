package app.wireroute;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A deliberately conservative, exact-match catalog for the optional quick filter.
 * It is a convenience list, not a claim about the legal or network status of a service.
 */
public final class VpnAppCatalog {
    private static final Set<String> PACKAGES;
    private static final Set<String> LINK_PACKAGES;

    static {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        Collections.addAll(packages,
                "com.instagram.android",
                "com.instagram.lite",
                "com.instagram.barcelona",
                "com.google.android.youtube",
                "com.google.android.apps.youtube.music",
                "com.google.android.apps.youtube.kids",
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.chrome.canary",
                "com.brave.browser",
                "com.microsoft.emmx",
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.yandex.browser",
                "com.opera.browser",
                "com.vivaldi.browser",
                "com.sec.android.app.sbrowser",
                "com.heytap.browser",
                "com.coloros.browser",
                "com.google.android.webview",
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.magazines",
                "com.google.android.apps.searchlite",
                "com.google.android.apps.googleassistant",
                "com.twitter.android",
                "com.discord",
                "com.facebook.katana",
                "com.facebook.lite",
                "com.facebook.orca",
                "com.linkedin.android",
                "org.thoughtcrime.securesms",
                "com.viber.voip",
                "com.whatsapp",
                "com.whatsapp.w4b",
                "org.telegram.messenger",
                "org.thunderdog.challegram",
                "com.snapchat.android",
                "com.spotify.music",
                "com.zhiliaoapp.musically",
                "com.openai.chatgpt",
                "com.anthropic.claude",
                "com.google.android.apps.bard",
                "com.facebook.stella",
                "com.microsoft.copilot",
                "ai.x.grok"
        );
        PACKAGES = Collections.unmodifiableSet(packages);

        LinkedHashSet<String> linkPackages = new LinkedHashSet<>();
        Collections.addAll(linkPackages,
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.chrome.canary",
                "com.brave.browser",
                "com.microsoft.emmx",
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.yandex.browser",
                "com.opera.browser",
                "com.vivaldi.browser",
                "com.sec.android.app.sbrowser",
                "com.heytap.browser",
                "com.coloros.browser",
                "com.google.android.webview",
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.magazines",
                "com.openai.chatgpt");
        LINK_PACKAGES = Collections.unmodifiableSet(linkPackages);
    }

    private VpnAppCatalog() {
    }

    public static boolean contains(String packageName) {
        return packageName != null && PACKAGES.contains(packageName);
    }

    public static boolean isKnownLinkApp(String packageName) {
        return packageName != null && LINK_PACKAGES.contains(packageName);
    }

    static Set<String> packagesForTest() {
        return PACKAGES;
    }
}
