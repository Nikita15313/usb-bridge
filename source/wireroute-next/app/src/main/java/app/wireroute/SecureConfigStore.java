package app.wireroute;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureConfigStore {
    private static final String KEY_ALIAS = "wireroute_profile_key_v1";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;
    private static final String PREFS = "profile_metadata";
    private static final String KEY_NAME = "display_name";

    private final AtomicFile profileFile;
    private final SharedPreferences metadata;

    public SecureConfigStore(Context context) {
        File file = new File(context.getNoBackupFilesDir(), "profile.wrc");
        profileFile = new AtomicFile(file);
        metadata = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasConfig() {
        return profileFile.getBaseFile().isFile();
    }

    public String getDisplayName() {
        return metadata.getString(KEY_NAME, "WireGuard.conf");
    }

    public synchronized void clear() {
        profileFile.delete();
        metadata.edit().remove(KEY_NAME).apply();
    }

    synchronized void prepareEncryptionKey() throws GeneralSecurityException, IOException {
        getOrCreateKey();
    }

    public synchronized void save(String config, String displayName)
            throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(config.getBytes(StandardCharsets.UTF_8));

        ByteBuffer payload = ByteBuffer.allocate(2 + iv.length + encrypted.length);
        payload.put(FORMAT_VERSION);
        payload.put((byte) iv.length);
        payload.put(iv);
        payload.put(encrypted);

        FileOutputStream output = null;
        try {
            output = profileFile.startWrite();
            output.write(payload.array());
            profileFile.finishWrite(output);
            output = null;
        } finally {
            if (output != null) {
                profileFile.failWrite(output);
            }
        }
        metadata.edit().putString(KEY_NAME, displayName).apply();
    }

    public synchronized String load() throws GeneralSecurityException, IOException {
        if (!hasConfig()) {
            return null;
        }
        byte[] payload;
        try (FileInputStream input = profileFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            payload = output.toByteArray();
        }

        if (payload.length < 2 + 12 + 16) {
            throw new GeneralSecurityException("Encrypted profile is truncated");
        }
        ByteBuffer input = ByteBuffer.wrap(payload);
        byte version = input.get();
        int ivLength = Byte.toUnsignedInt(input.get());
        if (version != FORMAT_VERSION || ivLength < 12 || ivLength > 16 || input.remaining() <= ivLength) {
            throw new GeneralSecurityException("Encrypted profile has an invalid format");
        }
        byte[] iv = new byte[ivLength];
        input.get(iv);
        byte[] encrypted = new byte[input.remaining()];
        input.get(encrypted);

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
