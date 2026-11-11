package app.wireroute.sites;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.*;

public final class LegacyProfileMigrationTest {
    private static EncryptedProfileBackup.Backup backup(int phase, String currentHash,
            String previousHash) {
        return new EncryptedProfileBackup.Backup("original", "phone.conf", true, true,
                "ONLY_SELECTED", Set.of("com.google.android.youtube"), true, true,
                phase, currentHash, previousHash);
    }

    @Test public void preparedTransactionCannotOverwriteANewerImportedProfile() throws Exception {
        EncryptedProfileBackup.Backup record = backup(EncryptedProfileBackup.PHASE_PREPARED, null, null);
        assertFalse(LegacyProfileMigration.isProfileConflict(record, "original"));
        assertTrue(LegacyProfileMigration.isProfileConflict(record, "new imported profile"));
    }

    @Test public void interruptedRefreshAcceptsOnlyItsTwoOverlayVersionsAndOriginal() throws Exception {
        EncryptedProfileBackup.Backup record = backup(EncryptedProfileBackup.PHASE_APPLYING,
                LegacyProfileMigration.sha256("new overlay"), LegacyProfileMigration.sha256("old overlay"));
        assertFalse(LegacyProfileMigration.isProfileConflict(record, "original"));
        assertFalse(LegacyProfileMigration.isProfileConflict(record, "old overlay"));
        assertFalse(LegacyProfileMigration.isProfileConflict(record, "new overlay"));
        assertTrue(LegacyProfileMigration.isProfileConflict(record, "unrelated config"));
    }

    @Test public void restorationAfterCrashDoesNotAuthorizeAnEarlierOverlay() throws Exception {
        EncryptedProfileBackup.Backup applying = backup(EncryptedProfileBackup.PHASE_APPLYING,
                LegacyProfileMigration.sha256("new overlay"), LegacyProfileMigration.sha256("old overlay"));
        EncryptedProfileBackup.Backup restoring = applying.beginRestoring(
                LegacyProfileMigration.sha256("new overlay"), false);
        assertNull(restoring.previousAppliedConfigHash);
        assertFalse(LegacyProfileMigration.isProfileConflict(restoring, "new overlay"));
        assertFalse(LegacyProfileMigration.isProfileConflict(restoring, "original"));
        assertTrue(LegacyProfileMigration.isProfileConflict(restoring, "old overlay"));
        assertFalse("Migration must never request automatic VPN start", restoring.desiredTunnelActive);
    }

    @Test public void appliedRecordCannotAssumeAnUnhashedOldProfileIsOwned() throws Exception {
        assertTrue(LegacyProfileMigration.isProfileConflict(
                backup(EncryptedProfileBackup.PHASE_APPLIED, null, null), "original"));
        EncryptedProfileBackup.Backup record = backup(EncryptedProfileBackup.PHASE_APPLIED,
                LegacyProfileMigration.sha256("overlay"), null);
        assertFalse(LegacyProfileMigration.isProfileConflict(record, "overlay"));
        assertTrue(LegacyProfileMigration.isProfileConflict(record, "original"));
    }

    @Test public void conflictOrMissingProfileAlwaysRequiresAChoice() throws Exception {
        EncryptedProfileBackup.Backup record = backup(EncryptedProfileBackup.PHASE_CONFLICT, null, null);
        assertTrue(LegacyProfileMigration.isProfileConflict(record, "original"));
        assertTrue(LegacyProfileMigration.isProfileConflict(
                backup(EncryptedProfileBackup.PHASE_PREPARED, null, null), null));
    }

    @Test public void versionThreePayloadPreservesProfileAndAppSelection() throws Exception {
        EncryptedProfileBackup.Backup original = backup(EncryptedProfileBackup.PHASE_APPLYING,
                LegacyProfileMigration.sha256("new overlay"), LegacyProfileMigration.sha256("old overlay"));
        Method encode = EncryptedProfileBackup.class.getDeclaredMethod("encodePayload",
                EncryptedProfileBackup.Backup.class);
        Method decode = EncryptedProfileBackup.class.getDeclaredMethod("decodePayload", byte[].class);
        encode.setAccessible(true);
        decode.setAccessible(true);
        EncryptedProfileBackup.Backup restored = (EncryptedProfileBackup.Backup)
                decode.invoke(null, encode.invoke(null, original));
        assertEquals(original.originalConfig, restored.originalConfig);
        assertEquals(original.displayName, restored.displayName);
        assertEquals(original.storedMode, restored.storedMode);
        assertEquals(original.storedPackages, restored.storedPackages);
        assertEquals(original.appliedConfigHash, restored.appliedConfigHash);
        assertEquals(original.previousAppliedConfigHash, restored.previousAppliedConfigHash);
        assertTrue(restored.modePresent);
        assertTrue(restored.packagesPresent);
    }

    @Test public void ordinaryAppSelectionIsNeverTreatedAsAnOverlayMarker() {
        assertFalse(new LegacyProfileMigration.RoutingSnapshot("BYPASS_SELECTED", Set.of("bank.app"),
                true, true, "BYPASS_SELECTED", Set.of("bank.app")).isOverlay());
        assertFalse(new LegacyProfileMigration.RoutingSnapshot("ONLY_SELECTED", Set.of(),
                true, true, "ONLY_SELECTED", Set.of()).isOverlay());
        assertFalse(new LegacyProfileMigration.RoutingSnapshot("BYPASS_SELECTED", Set.of(),
                false, false, "BYPASS_SELECTED", Set.of()).isOverlay());
        assertTrue(new LegacyProfileMigration.RoutingSnapshot("BYPASS_SELECTED", Set.of(),
                true, true, "BYPASS_SELECTED", Set.of()).isOverlay());
    }
}
