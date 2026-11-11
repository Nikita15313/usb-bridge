package local.usbbridge.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded text response reader, used only when explicitly requested by a probe. */
public final class ProbeBody {
    public static final int LIMIT = 16 * 1024;
    private ProbeBody() { }

    public static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long deadline = System.nanoTime() + 30_000_000_000L;
        for (;;) {
            if (System.nanoTime() > deadline) throw new IOException("Response body deadline exceeded");
            int count = input.read(buffer, 0, Math.min(buffer.length, LIMIT - bytes.size() + 1));
            if (count < 0) return bytes.toByteArray();
            if (bytes.size() + count > LIMIT) throw new IOException("Response body exceeds 16 KiB");
            bytes.write(buffer, 0, count);
        }
    }

    public static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    /** Keep log lines unambiguous: response content cannot forge another log entry. */
    public static String escaped(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') result.append("\\\\");
            else if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029')
                result.append(String.format(java.util.Locale.US, "\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
}
