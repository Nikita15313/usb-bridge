package app.wireroute;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Additional profiles use the existing Android Keystore key and atomic encrypted storage. */
public final class ProfileLibrary {
    public static final class Entry {
        public final String id, name, config;
        Entry(String id, String name, String config) {
            this.id = id; this.name = name; this.config = config;
        }
    }

    private final AtomicFile file;
    public ProfileLibrary(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "profile-library.wrc"));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry("wireroute_profile_key_v1", null);
        if (!(entry instanceof KeyStore.SecretKeyEntry))
            throw new java.security.GeneralSecurityException("Profile key unavailable");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    public synchronized List<Entry> list() throws Exception {
        List<Entry> result = new ArrayList<>();
        if (!file.getBaseFile().isFile()) return result;
        byte[] payload = file.readFully();
        if (payload.length < 30 || payload[0] != 1)
            throw new java.security.GeneralSecurityException("Profile archive format");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get();
        int ivLength = Byte.toUnsignedInt(buffer.get());
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() < ivLength + 16)
            throw new java.security.GeneralSecurityException("Profile archive truncated");
        byte[] iv = new byte[ivLength]; buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()]; buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new javax.crypto.spec.GCMParameterSpec(128, iv));
        JSONArray items = new JSONArray(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            result.add(new Entry(item.getString("id"), item.getString("name"), item.getString("config")));
        }
        return result;
    }

    public synchronized void remember(String config, String name) throws Exception {
        if (config == null) return;
        List<Entry> entries = list();
        for (Entry entry : entries) if (entry.config.equals(config)) return;
        entries.add(new Entry(UUID.randomUUID().toString(), name, config));
        write(entries);
    }

    public synchronized void update(String oldConfig, String config, String name) throws Exception {
        List<Entry> entries = list();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.config.equals(oldConfig)) {
                entries.set(i, new Entry(entry.id, name, config));
                write(entries); return;
            }
        }
        remember(config, name);
    }

    public synchronized void remove(String id) throws Exception {
        List<Entry> entries = list();
        entries.removeIf(entry -> entry.id.equals(id));
        write(entries);
    }

    private void write(List<Entry> entries) throws Exception {
        JSONArray items = new JSONArray();
        for (Entry entry : entries) items.put(new JSONObject()
                .put("id", entry.id).put("name", entry.name).put("config", entry.config));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(items.toString().getBytes(StandardCharsets.UTF_8));
        byte[] payload = ByteBuffer.allocate(2 + iv.length + ciphertext.length)
                .put((byte) 1).put((byte) iv.length).put(iv).put(ciphertext).array();
        FileOutputStream stream = file.startWrite();
        try { stream.write(payload); file.finishWrite(stream); }
        catch (Throwable error) { file.failWrite(stream); throw error; }
    }
}
