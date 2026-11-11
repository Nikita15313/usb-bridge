import local.usbbridge.probe.ProbeBody;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ProbeBodyTest {
    public static void main(String[] args) throws Exception {
        byte[] json="{\"ip\":\"192.0.2.1\",\"label\":\"проверка\"}".getBytes(StandardCharsets.UTF_8);
        if(!Arrays.equals(json,ProbeBody.read(new ByteArrayInputStream(json))))throw new AssertionError("UTF-8 body changed");
        if(ProbeBody.read(new ByteArrayInputStream(new byte[0])).length!=0)throw new AssertionError("Empty body");
        if(ProbeBody.read(new ByteArrayInputStream(new byte[16384])).length!=16384)throw new AssertionError("Exact limit");
        try{ProbeBody.read(new ByteArrayInputStream(new byte[16385]));throw new AssertionError("Oversized body accepted");}catch(IOException expected){}
        if(!"a\\nb\\r\\t\\u0000\\\\".equals(ProbeBody.escaped("a\nb\r\t\0\\")))throw new AssertionError("Control characters escaped incorrectly");
        System.out.println("PASS 5 bounded response body checks");
    }
}
