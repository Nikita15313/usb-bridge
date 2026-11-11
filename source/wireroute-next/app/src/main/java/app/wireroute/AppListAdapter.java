package app.wireroute;

import app.wireroute.release263.R;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class AppListAdapter extends BaseAdapter {
    public interface SelectionListener {
        void onSelectionChanged(Set<String> packages);
    }

    /** Receives a tap that needs an Activity-level mode transition before it can be applied. */
    public interface DeferredSelectionListener {
        void onSelectionRequested(String packageName, boolean selected);
    }

    private final LayoutInflater inflater;
    private final Context context;
    private final PackageManager packageManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Thread priority is only an optimization; icon loading must continue.
            }
            runnable.run();
        }, "WireRoute-app-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final LruCache<String, Drawable> iconCache = new LruCache<>(48);
    private final Set<String> iconRequests = new HashSet<>();
    private final Map<String, List<WeakReference<ImageView>>> iconWaiters = new HashMap<>();
    private final SelectionListener selectionListener;
    private DeferredSelectionListener deferredSelectionListener;
    private final List<InstalledApp> allApps = new ArrayList<>();
    private final List<InstalledApp> visibleApps = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private String query = "";
    private AppFilter filter = AppFilter.ALL;
    private boolean editingEnabled = true;
    private RoutingMode routingMode = RoutingMode.ONLY_SELECTED;
    private AppCategory category = AppCategory.APPS;

    public void setRoutingMode(RoutingMode mode) {
        if (routingMode != mode) { routingMode = mode; applyFilter(); }
    }

    public void setCategory(AppCategory value) { category = value; applyFilter(); }

    public List<InstalledApp> getAllApps() { return new ArrayList<>(allApps); }

    public boolean isVpn(InstalledApp app) {
        boolean marked = selected.contains(app.routingPackage);
        if (app.routingPackage.equals(app.packageName)) {
            for (String pkg : app.routingPackages) {
                if (!pkg.equals("com.google.android.apps.bard") && selected.contains(pkg)) marked = true;
            }
        }
        return routingMode == RoutingMode.ONLY_SELECTED ? marked : !marked;
    }

    public int getVpnCount() {
        int count = 0;
        for (InstalledApp app : allApps) if (category.matches(app) && isVpn(app)) count++;
        return count;
    }

    public int getCategoryCount() {
        int count = 0;
        for (InstalledApp app : allApps) if (category.matches(app)) count++;
        return count;
    }

    public void routeVisible(boolean vpn) {
        if (!editingEnabled) return;
        for (InstalledApp app : new ArrayList<>(visibleApps)) changeRoute(app, vpn);
        selectionChanged();
    }

    private void changeRoute(InstalledApp app, boolean vpn) {
        boolean mark = routingMode == RoutingMode.ONLY_SELECTED ? vpn : !vpn;
        if (mark) selected.addAll(app.routingPackages);
        else selected.removeAll(app.routingPackages);
    }

    private void setRoute(InstalledApp app, boolean vpn) {
        if (!editingEnabled) return;
        if (isVpn(app) == vpn) return;
        changeRoute(app, vpn);
        selectionChanged();
    }

    private volatile boolean closed;

    public AppListAdapter(Context context, SelectionListener selectionListener) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        packageManager = context.getApplicationContext().getPackageManager();
        this.selectionListener = selectionListener;
    }

    /** Stops lazy icon work when the owning Activity is destroyed. */
    public void close() {
        closed = true;
        iconExecutor.shutdownNow();
        iconRequests.clear();
        iconWaiters.clear();
        iconCache.evictAll();
    }

    public void setItems(List<InstalledApp> apps) {
        allApps.clear();
        allApps.addAll(apps);
        applyFilter();
    }

    public void setSelectedPackages(Set<String> packages) {
        selected.clear();
        selected.addAll(packages);
        applyFilter();
    }

    public Set<String> getSelectedPackages() {
        return new HashSet<>(selected);
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void setFilter(AppFilter filter) {
        this.filter = filter == null ? AppFilter.ALL : filter;
        applyFilter();
    }

    public AppFilter getFilter() {
        return filter;
    }

    public void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        notifyDataSetChanged();
    }

    public void setDeferredSelectionListener(DeferredSelectionListener listener) {
        deferredSelectionListener = listener;
    }

    public void toggle(int position) {
        if (!editingEnabled || position < 0 || position >= visibleApps.size()) {
            return;
        }
        String packageName = visibleApps.get(position).packageName;
        if (!selected.add(packageName)) {
            selected.remove(packageName);
        }
        selectionChanged();
    }

    private void togglePackage(String packageName) {
        if (!editingEnabled) {
            notifyDeferredSelection(packageName, !selected.contains(packageName));
            return;
        }
        if (!selected.add(packageName)) {
            selected.remove(packageName);
        }
        selectionChanged();
    }

    public void selectVisible() {
        if (!editingEnabled) {
            return;
        }
        for (InstalledApp app : visibleApps) {
            selected.add(app.packageName);
        }
        selectionChanged();
    }

    public void clearVisible() {
        if (!editingEnabled) {
            return;
        }
        for (InstalledApp app : new ArrayList<>(visibleApps)) {
            selected.remove(app.packageName);
        }
        selectionChanged();
    }

    public void addPackages(Set<String> packages) {
        if (!editingEnabled) {
            return;
        }
        selected.addAll(packages);
        selectionChanged();
    }

    public void removePackages(Set<String> packages) {
        if (!editingEnabled) {
            return;
        }
        selected.removeAll(packages);
        selectionChanged();
    }

    public int getTotalAppCount() {
        return allApps.size();
    }

    public int getVisibleAppCount() {
        return visibleApps.size();
    }

    public int getSelectedInstalledCount() {
        int count = 0;
        for (InstalledApp app : allApps) {
            if (selected.contains(app.packageName)) {
                count++;
            }
        }
        return count;
    }

    public int getRecommendedAppCount() {
        int count = 0;
        for (InstalledApp app : allApps) {
            if (app.recommended) {
                count++;
            }
        }
        return count;
    }

    public int getLinkHandlerCount() {
        int count = 0;
        for (InstalledApp app : allApps) {
            if (app.linkHandler) {
                count++;
            }
        }
        return count;
    }

    public int getRecommendedSelectedCount() {
        int count = 0;
        for (InstalledApp app : allApps) {
            if (app.recommended && selected.contains(app.packageName)) {
                count++;
            }
        }
        return count;
    }

    public Set<String> getRecommendedPackages() {
        Set<String> packages = new HashSet<>();
        for (InstalledApp app : allApps) {
            if (app.recommended) {
                packages.add(app.packageName);
            }
        }
        return packages;
    }

    public boolean hasQuery() {
        return !query.isEmpty();
    }

    private void applyFilter() {
        visibleApps.clear();
        for (InstalledApp app : allApps) {
            if (!category.matches(app)) continue;
            if (!query.isEmpty() && !app.searchText.contains(query.replace('ё', 'е'))) continue;
            if (filter.matches(app.recommended, app.linkHandler, isVpn(app))) visibleApps.add(app);
        }
        notifyDataSetChanged();
    }

    static boolean matchesForDisplay(
            String label,
            String packageName,
            String normalizedQuery,
            AppFilter filter,
            boolean recommended,
            boolean linkHandler,
            boolean selected
    ) {
        String safeQuery = normalizedQuery == null ? "" : normalizedQuery;
        boolean hasQuery = !safeQuery.isEmpty();
        boolean matchesQuery = !hasQuery
                || label.toLowerCase(Locale.ROOT).contains(safeQuery)
                || packageName.toLowerCase(Locale.ROOT).contains(safeQuery);
        // Search keeps the visible route filter so the selected chip always describes the list.
        boolean matchesCategory = (filter == null ? AppFilter.ALL : filter).matches(
                        recommended, linkHandler, selected);
        return matchesQuery && matchesCategory;
    }

    private void selectionChanged() {
        applyFilter();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        selectionListener.onSelectionChanged(new HashSet<>(selected));
    }

    private void notifyDeferredSelection(String packageName, boolean desiredSelected) {
        if (deferredSelectionListener != null) {
            deferredSelectionListener.onSelectionRequested(packageName, desiredSelected);
        }
    }

    @Override
    public int getCount() {
        return visibleApps.size();
    }

    @Override
    public InstalledApp getItem(int position) {
        return visibleApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).packageName.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_app, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        InstalledApp app = getItem(position);
        bindIcon(holder.icon, app.packageName);
        holder.label.setText(app.label);
        holder.packageName.setText(app.subtitle);
        holder.packageName.setVisibility(app.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        boolean vpn = isVpn(app);
        styleRoute(holder.vpnButton, vpn);
        styleRoute(holder.directButton, !vpn);
        holder.vpnButton.setEnabled(editingEnabled);
        holder.directButton.setEnabled(editingEnabled);
        holder.vpnButton.setOnClickListener(view -> setRoute(app, true));
        holder.directButton.setOnClickListener(view -> setRoute(app, false));
        convertView.setContentDescription(app.label + (vpn ? ", с VPN" : ", без VPN"));
        convertView.setOnClickListener(null);
        return convertView;
    }

    private void bindIcon(ImageView target, String packageName) {
        target.setTag(packageName);
        Drawable cached = iconCache.get(packageName);
        if (cached != null) {
            target.setImageDrawable(cached);
            return;
        }
        // Never open every installed APK during Activity startup. Loading only
        // visible icons avoids retaining hundreds of AssetManagers and keeps
        // package-resource I/O away from the input/focus critical path.
        target.setImageResource(android.R.drawable.sym_def_app_icon);
        if (closed) {
            return;
        }
        List<WeakReference<ImageView>> waiters = iconWaiters.computeIfAbsent(
                packageName, ignored -> new ArrayList<>());
        boolean alreadyWaiting = false;
        for (WeakReference<ImageView> reference : waiters) {
            if (reference.get() == target) {
                alreadyWaiting = true;
                break;
            }
        }
        if (!alreadyWaiting) {
            waiters.add(new WeakReference<>(target));
        }
        if (!iconRequests.add(packageName)) {
            return;
        }
        try {
            iconExecutor.execute(() -> loadIcon(packageName));
        } catch (RejectedExecutionException ignored) {
            iconRequests.remove(packageName);
            iconWaiters.remove(packageName);
        }
    }

    private void loadIcon(String packageName) {
        Drawable loaded = null;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            loaded = info.loadIcon(packageManager);
        } catch (Throwable ignored) {
            // The package can disappear while the installed-app list is visible.
        }
        final Drawable result = loaded;
        mainHandler.post(() -> deliverIcon(packageName, result));
    }

    private void deliverIcon(String packageName, Drawable icon) {
        iconRequests.remove(packageName);
        List<WeakReference<ImageView>> waiters = iconWaiters.remove(packageName);
        if (closed) {
            return;
        }
        if (icon != null) {
            iconCache.put(packageName, icon);
        }
        if (waiters == null) {
            return;
        }
        for (WeakReference<ImageView> reference : waiters) {
            ImageView target = reference.get();
            if (target != null && packageName.equals(target.getTag()) && icon != null) {
                target.setImageDrawable(icon);
            }
        }
    }

    private void styleRoute(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_route_on : R.drawable.bg_route_off);
        int color = active ? (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF163626 : 0xFFFFFFFF
                : context.getColor(R.color.text_secondary);
        button.setTextColor(color);
        button.setSelected(active);
        button.setContentDescription(button.getText() + (active ? ", выбран" : ", выбрать"));
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final Button vpnButton;
        final Button directButton;

        ViewHolder(View view) {
            icon = view.findViewById(R.id.appIcon);
            label = view.findViewById(R.id.appLabel);
            packageName = view.findViewById(R.id.appPackage);
            vpnButton = view.findViewById(R.id.appVpnButton);
            directButton = view.findViewById(R.id.appDirectButton);
        }
    }
}
