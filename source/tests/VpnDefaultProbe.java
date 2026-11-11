import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Range;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;

public final class VpnDefaultProbe {
    public static void main(String[] args) throws Exception {
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Collection<Range<Integer>> ranges = new ArrayList<>();
        if (args.length > 0 && "set".equals(args[0])) {
            ranges.add(Range.create(10202, 10202));
            ranges.add(Range.create(10486, 10486));
        }
        Method method = ConnectivityManager.class.getMethod("setVpnDefaultForUids", String.class, Collection.class);
        method.invoke(connectivity, "USB Bridge", ranges);
        System.out.println(args.length > 0 && "set".equals(args[0]) ? "VPN_DEFAULT_SET" : "VPN_DEFAULT_CLEARED");
    }
}
