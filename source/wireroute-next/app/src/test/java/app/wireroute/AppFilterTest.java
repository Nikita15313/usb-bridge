package app.wireroute;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppFilterTest {
    @Test
    public void filtersDoNotMutateOrConfusePredicates() {
        assertTrue(AppFilter.ALL.matches(false, false, false));
        assertTrue(AppFilter.RECOMMENDED.matches(true, false, false));
        assertFalse(AppFilter.RECOMMENDED.matches(false, true, true));
        assertTrue(AppFilter.LINKS.matches(false, true, false));
        assertFalse(AppFilter.LINKS.matches(true, false, true));
        assertTrue(AppFilter.SELECTED.matches(false, false, true));
        assertFalse(AppFilter.SELECTED.matches(true, true, false));
    }
}
