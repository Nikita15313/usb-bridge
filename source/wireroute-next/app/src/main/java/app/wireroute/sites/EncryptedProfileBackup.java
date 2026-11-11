package app.wireroute.sites;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Crash-safe, device-bound backup of the profile displaced by the site overlay.
 * The WireGuard configuration is never written to SharedPreferences or plaintext.
 */
final class EncryptedProfileBackup {
    static final int PHASE_PREPARED = 0;
    static final int PHASE_APPLYING = 1;
    static final int PHASE_APPLIED = 2;
    static final int PHASE_RESTORING = 3;
    static final int PHASE_CONFLICT = 4;

    private static final byte[] FILE_MAGIC = new byte[]{
            'W', 'R', 'S', 'I', 'T', 'E', 'B', '1'
    };
    private static final byte[] AAD =
            "WireRoute site profile overlay backup v1".getBytes(StandardCharsets.UTF_8);
    private static final int FILE_VERSION = 1;
    private static final int PAYLOAD_VERSION = 3;
    private static final int GCM_TAG_BITS = 128;
    private static final int EXPECTED_IV_BYTES = 12;
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = MAX_CONFIG_BYTES + 1024 * 1024;
    private static final int MAX_FILE_BYTES = MAX_PAYLOAD_BYTES + 1024;
    private static final int MAX_DISPLAY_NAME_BYTES = 16 * 1024;
    private static final int MAX_MODE_BYTES = 256;
    private static final int MAX_HASH_BYTES = 64;
    private static final int MAX_PACKAGES = 20_000;
    private static final int MAX_PACKAGE_BYTES = 4 * 1024;
    private static final String KEY_ALIAS = "WireRoute.SiteProfileOverlay.AesGcm.v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String FILE_NAME = "site-profile-overlay-v1.wrc";
    private static final Object KEY_LOCK = new Object();

    private final AtomicFile atomicFile;

    EncryptedProfileBackup(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext == null ? context : applicationContext;
        atomicFile = new AtomicFile(new File(safeContext.getNoBackupFilesDir(), FILE_NAME));
    }

    boolean exists() {
        File base = atomicFile.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    Backup load() throws IOException, GeneralSecurityException {
        byte[] encoded = null;
        byte[] cleartext = null;
        try {
            encoded = readAtomicFile();
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            byte[] magic = new byte[FILE_MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(FILE_MAGIC, magic)) {
                throw new IOException("Site profile backup has an invalid header");
            }
            int version = input.readInt();
            if (version != FILE_VERSION) {
                throw new IOException("Unsupported site profile backup version: " + version);
            }
            int ivLength = input.readInt();
            if (ivLength != EXPECTED_IV_BYTES) {
                throw new IOException("Site profile backup has an invalid IV");
            }
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            int encryptedLength = input.readInt();
            if (encryptedLength < GCM_TAG_BITS / 8
                    || encryptedLength > MAX_PAYLOAD_BYTES + GCM_TAG_BITS / 8
                    || encryptedLength != input.available()) {
                throw new IOException("Site profile backup has an invalid length");
            }
            byte[] encrypted = new byte[encryptedLength];
            input.readFully(encrypted);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            cleartext = cipher.doFinal(encrypted);
            return decodePayload(cleartext);
        } catch (EOFException truncated) {
            throw new IOException("Site profile backup is truncated", truncated);
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            if (cleartext != null) Arrays.fill(cleartext, (byte) 0);
        }
    }

    void save(Backup backup) throws IOException, GeneralSecurityException {
        if (backup == null) {
            throw new IllegalArgumentException("backup == null");
        }
        byte[] cleartext = null;
        byte[] encrypted = null;
        byte[] encoded = null;
        try {
            cleartext = encodePayload(backup);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(AAD);
            encrypted = cipher.doFinal(cleartext);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != EXPECTED_IV_BYTES) {
                throw new GeneralSecurityException("Android Keystore returned an invalid GCM IV");
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    FILE_MAGIC.length + 16 + iv.length + encrypted.length);
            DataOutputStream output = new DataOutputStream(bytes);
            output.write(FILE_MAGIC);
            output.writeInt(FILE_VERSION);
            output.writeInt(iv.length);
            output.write(iv);
            output.writeInt(encrypted.length);
            output.write(encrypted);
            output.flush();
            encoded = bytes.toByteArray();
            writeAtomicFile(encoded);
        } finally {
            if (cleartext != null) Arrays.fill(cleartext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    void delete() {
        atomicFile.delete();
    }

    private byte[] readAtomicFile() throws IOException {
        FileInputStream input = null;
        try {
            input = atomicFile.openRead();
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("Site profile backup is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            if (input != null) input.close();
        }
    }

    private void writeAtomicFile(byte[] encoded) throws IOException {
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(encoded);
            output.flush();
            output.getFD().sync();
            atomicFile.finishWrite(output);
            output = null;
        } finally {
            if (output != null) {
                atomicFile.failWrite(output);
            }
        }
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        synchronized (KEY_LOCK) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }
            if (existing != null || keyStore.containsAlias(KEY_ALIAS)) {
                throw new GeneralSecurityException("Site profile backup key has an invalid type");
            }

            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build());
            return generator.generateKey();
        }
    }

    private static byte[] encodePayload(Backup backup) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(PAYLOAD_VERSION);
        output.writeInt(checkedPhase(backup.phase));
        output.writeBoolean(backup.originalTunnelActive);
        output.writeBoolean(backup.desiredTunnelActive);
        writeString(output, backup.appliedConfigHash, MAX_HASH_BYTES, true);
        writeString(output, backup.previousAppliedConfigHash, MAX_HASH_BYTES, true);
        writeString(output, backup.originalConfig, MAX_CONFIG_BYTES, false);
        writeString(output, backup.displayName, MAX_DISPLAY_NAME_BYTES, false);
        output.writeBoolean(backup.modePresent);
        writeString(output, backup.storedMode, MAX_MODE_BYTES, true);
        output.writeBoolean(backup.packagesPresent);
        if (backup.storedPackages.size() > MAX_PACKAGES) {
            throw new IOException("Too many routing packages in site profile backup");
        }
        output.writeInt(backup.storedPackages.size());
        for (String packageName : backup.storedPackages) {
            writeString(output, packageName, MAX_PACKAGE_BYTES, false);
            if (bytes.size() > MAX_PAYLOAD_BYTES) {
                throw new IOException("Site profile backup payload is too large");
            }
        }
        output.flush();
        if (bytes.size() > MAX_PAYLOAD_BYTES) {
            throw new IOException("Site profile backup payload is too large");
        }
        return bytes.toByteArray();
    }

    private static Backup decodePayload(byte[] cleartext) throws IOException {
        if (cleartext == null || cleartext.length == 0
                || cleartext.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Site profile backup payload has an invalid length");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(cleartext));
        int version = input.readInt();
        if (version < 1 || version > PAYLOAD_VERSION) {
            throw new IOException("Unsupported site profile payload version: " + version);
        }
        int phase = checkedPhase(input.readInt());
        boolean originalTunnelActive = input.readBoolean();
        boolean desiredTunnelActive = input.readBoolean();
        String appliedConfigHash = version >= 2
                ? readString(input, MAX_HASH_BYTES, true) : null;
        if (appliedConfigHash != null && !isSha256(appliedConfigHash)) {
            throw new IOException("Site profile backup has an invalid overlay fingerprint");
        }
        String previousAppliedConfigHash = version >= 3
                ? readString(input, MAX_HASH_BYTES, true) : null;
        if (previousAppliedConfigHash != null
                && !isSha256(previousAppliedConfigHash)) {
            throw new IOException("Site profile backup has an invalid previous overlay fingerprint");
        }
        if (previousAppliedConfigHash != null && phase != PHASE_APPLYING) {
            throw new IOException("Site profile backup has a stale previous overlay fingerprint");
        }
        String config = readString(input, MAX_CONFIG_BYTES, false);
        String displayName = readString(input, MAX_DISPLAY_NAME_BYTES, false);
        boolean modePresent = input.readBoolean();
        String storedMode = readString(input, MAX_MODE_BYTES, true);
        boolean packagesPresent = input.readBoolean();
        int packageCount = input.readInt();
        if (packageCount < 0 || packageCount > MAX_PACKAGES) {
            throw new IOException("Site profile backup has an invalid package count");
        }
        LinkedHashSet<String> packages = new LinkedHashSet<String>();
        for (int i = 0; i < packageCount; i++) {
            packages.add(readString(input, MAX_PACKAGE_BYTES, false));
        }
        if (input.available() != 0) {
            throw new IOException("Site profile backup has trailing data");
        }
        if (config.length() == 0) {
            throw new IOException("Site profile backup contains an empty profile");
        }
        return new Backup(config, displayName, modePresent, packagesPresent,
                storedMode, packages, originalTunnelActive, desiredTunnelActive,
                phase, appliedConfigHash, previousAppliedConfigHash);
    }

    private static void writeString(DataOutputStream output, String value,
                                    int maximumBytes, boolean nullable) throws IOException {
        if (value == null) {
            if (!nullable) {
                throw new IOException("Required site profile backup field is missing");
            }
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (encoded.length > maximumBytes) {
                throw new IOException("Site profile backup field is too large");
            }
            output.writeInt(encoded.length);
            output.write(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String readString(DataInputStream input, int maximumBytes,
                                     boolean nullable) throws IOException {
        int length = input.readInt();
        if (nullable && length == -1) {
            return null;
        }
        if (length < 0 || length > maximumBytes || length > input.available()) {
            throw new IOException("Site profile backup field has an invalid length");
        }
        byte[] encoded = new byte[length];
        try {
            input.readFully(encoded);
            return new String(encoded, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static int checkedPhase(int phase) throws IOException {
        if (phase < PHASE_PREPARED || phase > PHASE_CONFLICT) {
            throw new IOException("Site profile backup has an invalid transaction phase");
        }
        return phase;
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) return false;
        }
        return true;
    }

    static final class Backup {
        final String originalConfig;
        final String displayName;
        final boolean modePresent;
        final boolean packagesPresent;
        final String storedMode;
        final Set<String> storedPackages;
        final boolean originalTunnelActive;
        final boolean desiredTunnelActive;
        final int phase;
        /** Pending target in APPLYING; committed/authorized current hash otherwise. */
        final String appliedConfigHash;
        /** Exact profile displaced by an APPLYING refresh; null in every other phase. */
        final String previousAppliedConfigHash;

        Backup(String originalConfig, String displayName,
               boolean modePresent, boolean packagesPresent,
                String storedMode, Set<String> storedPackages,
                boolean originalTunnelActive, boolean desiredTunnelActive, int phase) {
            this(originalConfig, displayName, modePresent, packagesPresent,
                    storedMode, storedPackages, originalTunnelActive,
                    desiredTunnelActive, phase, null);
        }

        Backup(String originalConfig, String displayName,
               boolean modePresent, boolean packagesPresent,
               String storedMode, Set<String> storedPackages,
               boolean originalTunnelActive, boolean desiredTunnelActive,
               int phase, String appliedConfigHash) {
            this(originalConfig, displayName, modePresent, packagesPresent,
                    storedMode, storedPackages, originalTunnelActive,
                    desiredTunnelActive, phase, appliedConfigHash, null);
        }

        Backup(String originalConfig, String displayName,
               boolean modePresent, boolean packagesPresent,
               String storedMode, Set<String> storedPackages,
               boolean originalTunnelActive, boolean desiredTunnelActive,
               int phase, String appliedConfigHash,
               String previousAppliedConfigHash) {
            if (originalConfig == null || originalConfig.length() == 0) {
                throw new IllegalArgumentException("originalConfig is empty");
            }
            if (appliedConfigHash != null && !isSha256(appliedConfigHash)) {
                throw new IllegalArgumentException("appliedConfigHash is invalid");
            }
            if (previousAppliedConfigHash != null
                    && !isSha256(previousAppliedConfigHash)) {
                throw new IllegalArgumentException(
                        "previousAppliedConfigHash is invalid");
            }
            this.originalConfig = originalConfig;
            this.displayName = displayName == null ? "WireRoute" : displayName;
            this.modePresent = modePresent;
            this.packagesPresent = packagesPresent;
            this.storedMode = storedMode;
            this.storedPackages = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(storedPackages));
            this.originalTunnelActive = originalTunnelActive;
            this.desiredTunnelActive = desiredTunnelActive;
            this.phase = phase;
            this.appliedConfigHash = appliedConfigHash;
            // A previous overlay is authorized only across the narrow APPLYING
            // crash window. Phase changes normalize it away automatically.
            this.previousAppliedConfigHash = phase == PHASE_APPLYING
                    ? previousAppliedConfigHash : null;
        }

        Backup withPhase(int newPhase, boolean newDesiredTunnelActive) {
            return new Backup(originalConfig, displayName, modePresent, packagesPresent,
                    storedMode, storedPackages, originalTunnelActive,
                    newDesiredTunnelActive, newPhase, appliedConfigHash,
                    previousAppliedConfigHash);
        }

        Backup beginApplying(String previousHash, String pendingHash,
                             boolean newDesiredTunnelActive) {
            if (pendingHash == null) {
                throw new IllegalArgumentException("pendingHash == null");
            }
            if (pendingHash.equals(previousHash)) previousHash = null;
            return new Backup(originalConfig, displayName, modePresent, packagesPresent,
                    storedMode, storedPackages, originalTunnelActive,
                    newDesiredTunnelActive, PHASE_APPLYING,
                    pendingHash, previousHash);
        }

        Backup beginRestoring(String authorizedCurrentHash,
                              boolean newDesiredTunnelActive) {
            return new Backup(originalConfig, displayName, modePresent, packagesPresent,
                    storedMode, storedPackages, originalTunnelActive,
                    newDesiredTunnelActive, PHASE_RESTORING,
                    authorizedCurrentHash, null);
        }

        Backup withOriginalRouting(LegacyProfileMigration.RoutingSnapshot routing) {
            if (routing == null) throw new IllegalArgumentException("routing == null");
            return new Backup(originalConfig, displayName,
                    routing.modePresent, routing.packagesPresent,
                    routing.storedMode, routing.storedPackages,
                    originalTunnelActive, desiredTunnelActive,
                    phase, appliedConfigHash, previousAppliedConfigHash);
        }

        LegacyProfileMigration.RoutingSnapshot originalRouting() {
            String effectiveMode = storedMode == null ? "ONLY_SELECTED" : storedMode;
            return new LegacyProfileMigration.RoutingSnapshot(effectiveMode, storedPackages,
                    modePresent, packagesPresent, storedMode, storedPackages);
        }
    }
}
