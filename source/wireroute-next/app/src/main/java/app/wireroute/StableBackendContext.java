package app.wireroute;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Build;
import com.wireguard.android.backend.GoBackend;

/** Keeps the official backend/JNI intact, while hosting its VPN in our foreground service. */
final class StableBackendContext extends ContextWrapper {
    StableBackendContext(Context context) { super(context); }

    @Override public ComponentName startService(Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null && GoBackend.VpnService.class.getName().equals(component.getClassName())) {
            Intent foreground = new Intent(intent).setClass(this, StableVpnService.class);
            return Build.VERSION.SDK_INT >= 26
                    ? super.startForegroundService(foreground) : super.startService(foreground);
        }
        return super.startService(intent);
    }
}
