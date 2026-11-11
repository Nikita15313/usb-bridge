package app.wireroute;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VpnAppCatalogTest {
    @Test
    public void recognizesExactKnownPackages() {
        assertTrue(VpnAppCatalog.contains("com.instagram.android"));
        assertTrue(VpnAppCatalog.contains("com.google.android.youtube"));
        assertTrue(VpnAppCatalog.contains("com.android.chrome"));
        assertTrue(VpnAppCatalog.contains("com.chrome.beta"));
        assertTrue(VpnAppCatalog.contains("com.google.android.googlequicksearchbox"));
        assertTrue(VpnAppCatalog.contains("com.google.android.apps.bard"));
        assertTrue(VpnAppCatalog.contains("com.twitter.android"));
        assertTrue(VpnAppCatalog.isKnownLinkApp("com.openai.chatgpt"));
        assertTrue(VpnAppCatalog.isKnownLinkApp("com.yandex.browser"));
    }

    @Test
    public void doesNotUseSpoofableNamesOrPrefixes() {
        assertFalse(VpnAppCatalog.contains("Instagram"));
        assertFalse(VpnAppCatalog.contains("com.instagram.android.fake"));
        assertFalse(VpnAppCatalog.contains(null));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void catalogIsImmutable() {
        VpnAppCatalog.packagesForTest().add("example.invalid");
    }
}
