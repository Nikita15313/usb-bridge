package app.wireroute;

import app.wireroute.release263.R;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small native screens that share the same navigation and route controls as desktop. */
final class SimplePanels {
    private final MainActivity activity;
    private final SecureConfigStore configStore;
    private final RoutingPreferences routing;
    private final TunnelController tunnel;
    private final AppListAdapter apps;
    private final ProfileLibrary profiles;
    private final SiteLibrary sites;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout profileHost;
    private final LinearLayout sitesHost;
    private LinearLayout siteRows;
    private TextView siteCount;
    private String siteQuery = "";
    private int siteFilter;
    private boolean closed;
    private boolean editable = true;
    private int profileGeneration;
    private SiteLibrary.Site pendingSite;
    private String pendingBrowser;
    private long pendingUserGeneration;
    private final Map<String, Boolean> siteDraft = new HashMap<>();
    private EditText siteSearch;
    private android.widget.RadioGroup siteFilters;
    private final List<Button> profileUseButtons = new ArrayList<>();
    private final Map<Button, Boolean> profileRemoveButtons = new HashMap<>();

    boolean hasSiteDraft() { return !siteDraft.isEmpty(); }
    boolean canSaveSites() {
        if (sites.isReadable()) return true;
        toast("Не удалось прочитать список сайтов. Старые записи сохранены."); return false;
    }
    void discardSites() { siteDraft.clear(); refreshSites(); }
    void saveState(android.os.Bundle state) {
        android.os.Bundle choices = new android.os.Bundle();
        for (Map.Entry<String, Boolean> entry : siteDraft.entrySet()) choices.putBoolean(entry.getKey(), entry.getValue());
        state.putBundle("siteDraft", choices); state.putString("siteQuery", siteQuery); state.putInt("siteFilter", siteFilter);
    }
    void restoreState(android.os.Bundle state) {
        android.os.Bundle choices = state.getBundle("siteDraft");
        if (choices != null) for (String key : choices.keySet()) siteDraft.put(key, choices.getBoolean(key));
        siteSearch.setText(state.getString("siteQuery", ""));
        siteFilter = state.getInt("siteFilter", 0);
        ((android.widget.RadioButton) siteFilters.getChildAt(siteFilter)).setChecked(true);
        refreshSites();
    }
    void saveSites() {
        for (Map.Entry<String, Boolean> choice : siteDraft.entrySet()) sites.route(choice.getKey(), choice.getValue());
        siteDraft.clear(); refreshSites();
    }
    private void stageSite(SiteLibrary.Site site, boolean vpn) {
        boolean stored = site.vpn;
        for (SiteLibrary.Site entry : sites.list()) if (entry.id.equals(site.id)) stored = entry.vpn;
        if (stored == vpn) siteDraft.remove(site.id); else siteDraft.put(site.id, vpn);
        refreshSites(); activity.refreshRoutingUi();
    }

    SimplePanels(MainActivity activity, SecureConfigStore configStore,
                 RoutingPreferences routing, TunnelController tunnel,
                 AppListAdapter apps, ProfileLibrary profiles) {
        this.activity = activity; this.configStore = configStore; this.routing = routing;
        this.tunnel = tunnel; this.apps = apps; this.profiles = profiles;
        sites = new SiteLibrary(activity);
        profileHost = activity.findViewById(R.id.profileLibrary);
        sitesHost = activity.findViewById(R.id.sitesContent);
        activity.findViewById(R.id.editProfileButton).setOnClickListener(view -> editProfile());
        activity.findViewById(R.id.popularAppsButton).setOnClickListener(view -> showAppCollection());
        buildSites();
    }

    void close() { closed = true; profileGeneration++; worker.shutdownNow(); }
    void setEditable(boolean value) { editable = value; updateProfileActionsAvailability(); }

    private void updateProfileActionsAvailability() {
        TunnelController.State state = tunnel.getSnapshot().state;
        boolean currentCanBeRemoved = state == TunnelController.State.DOWN
                || state == TunnelController.State.ERROR;
        for (Button button : profileUseButtons) {
            button.setEnabled(editable);
            button.setAlpha(editable ? 1f : 0.45f);
        }
        for (Map.Entry<Button, Boolean> entry : profileRemoveButtons.entrySet()) {
            boolean enabled = editable && (!entry.getValue() || currentCanBeRemoved);
            entry.getKey().setEnabled(enabled);
            entry.getKey().setAlpha(enabled ? 1f : 0.45f);
        }
        activity.findViewById(R.id.editProfileButton).setEnabled(configStore.hasConfig() && editable);
    }

    private boolean canEdit() {
        if (editable) return true;
        toast("Дождитесь завершения текущего действия"); return false;
    }

    void refreshProfiles() {
        int generation = ++profileGeneration;
        worker.execute(() -> {
            try {
                String current = configStore.load();
                if (current != null) profiles.remember(current, configStore.getDisplayName());
                List<ProfileLibrary.Entry> entries = profiles.list();
                activity.runOnUiThread(() -> {
                    if (closed || generation != profileGeneration) return;
                    profileHost.removeAllViews();
                    profileUseButtons.clear();
                    profileRemoveButtons.clear();
                    for (ProfileLibrary.Entry entry : entries) {
                        boolean active = entry.config.equals(current);
                        LinearLayout card = card(profileHost);
                        card.addView(label(entry.name, 17, false));
                        card.addView(label(active ? "Текущий профиль" : "Сохранённый профиль", 13, true));
                        LinearLayout actions = horizontal(card);
                        Button use = action(actions, active ? "Выбран" : "Выбрать", active);
                        use.setEnabled(!active);
                        if (active) use.setAlpha(0.65f);
                        else profileUseButtons.add(use);
                        use.setOnClickListener(view -> {
                            if (canEdit()) activity.saveProfileText(entry.config, entry.name, false);
                        });
                        Button remove = action(actions, "Удалить", false);
                        profileRemoveButtons.put(remove, active);
                        remove.setOnClickListener(view -> removeProfile(entry));
                    }
                    updateProfileActionsAvailability();
                });
            } catch (Exception ignored) {
                activity.runOnUiThread(() -> {
                    if (!closed && generation == profileGeneration) {
                        profileHost.removeAllViews();
                        profileUseButtons.clear();
                        profileRemoveButtons.clear();
                        profileHost.addView(label("Не удалось открыть сохранённые профили. Текущий профиль сохранён: "
                                + configStore.getDisplayName(), 14, true));
                        updateProfileActionsAvailability();
                    }
                });
            }
        });
    }

    private void removeProfile(ProfileLibrary.Entry entry) {
        if (!canEdit()) return;
        new AlertDialog.Builder(activity).setTitle("Удалить профиль «" + entry.name + "»?")
                .setMessage("Другие профили и выбор приложений сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    if (!canEdit()) return;
                    worker.execute(() -> {
                        try {
                            String current = configStore.load();
                            if (entry.config.equals(current)
                                    && tunnel.getSnapshot().state != TunnelController.State.DOWN
                                    && tunnel.getSnapshot().state != TunnelController.State.ERROR) {
                                activity.runOnUiThread(() -> toast("Сначала отключите VPN, чтобы удалить текущий профиль"));
                                return;
                            }
                            profiles.remove(entry.id);
                            if (entry.config.equals(current)) {
                                configStore.clear(); tunnel.noteProfileChanged();
                            }
                            activity.runOnUiThread(() -> {
                                if (!closed) { activity.profileChanged(); refreshProfiles(); }
                            });
                        } catch (Exception ignored) {
                            activity.runOnUiThread(() -> toast("Не удалось удалить профиль"));
                        }
                    });
                }).show();
    }

    private void editProfile() {
        if (!canEdit()) return;
        if (!configStore.hasConfig()) { toast("Сначала добавьте профиль"); return; }
        worker.execute(() -> {
            try {
                String config = configStore.load();
                activity.runOnUiThread(() -> { if (!closed) showProfileEditor(config); });
            } catch (Exception ignored) {
                activity.runOnUiThread(() -> toast("Не удалось прочитать профиль"));
            }
        });
    }

    private void showProfileEditor(String config) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(12));
        scroll.addView(form);
        form.addView(label("Название", 13, true));
        EditText name = input("Название профиля"); name.setText(configStore.getDisplayName());
        form.addView(name);
        CheckBox networkDns = new CheckBox(activity);
        networkDns.setText("Использовать DNS сети");
        networkDns.setTextColor(color(R.color.text_primary));
        networkDns.setChecked(!hasDns(config));
        form.addView(networkDns);
        form.addView(label("Если включено, DNS из профиля будет убран. В режиме интернета от компьютера применяется DNS для USB-подключения.", 13, true));
        Button advanced = new Button(activity, null, 0, R.style.Widget_WireRoute_OutlinedButton);
        advanced.setText("Показать параметры подключения"); form.addView(advanced);
        EditText source = input("");
        source.setText(config); source.setTypeface(Typeface.MONOSPACE);
        source.setTextSize(12); source.setSingleLine(false); source.setMinLines(8);
        source.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            source.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        source.setVisibility(View.GONE); form.addView(source);
        advanced.setOnClickListener(view -> {
            boolean visible = source.getVisibility() != View.VISIBLE;
            source.setVisibility(visible ? View.VISIBLE : View.GONE);
            advanced.setText(visible ? "Скрыть параметры подключения" : "Показать параметры подключения");
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Редактировать подключение")
                .setView(scroll).setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null).create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String edited = source.getText().toString();
            if (networkDns.isChecked()) edited = removeDns(edited);
            if (!networkDns.isChecked() && !hasDns(edited) && hasDns(config)) {
                // Merely opening and closing the advanced parameters retains the original DNS.
                toast("Добавьте DNS в параметрах подключения или включите DNS сети"); return;
            }
            String displayName = name.getText().toString().trim();
            if (displayName.isEmpty()) { name.setError("Введите название"); return; }
            activity.saveProfileText(edited, displayName, true);
            source.setText(""); dialog.dismiss();
        });
        dialog.setOnDismissListener(ignored -> source.setText(""));
    }

    private static boolean hasDns(String config) {
        return java.util.regex.Pattern.compile("(?im)^\\s*DNS\\s*=").matcher(config).find();
    }
    private static String removeDns(String config) {
        return config.replaceAll("(?im)^\\s*DNS\\s*=[^\\r\\n]*(?:\\r?\\n)?", "");
    }

    private void showAppCollection() {
        List<InstalledApp> choices = new ArrayList<>();
        for (InstalledApp app : apps.getAllApps()) if (app.recommended && !app.systemComponent) choices.add(app);
        if (choices.isEmpty()) { toast("Приложения из подборки пока не установлены"); return; }
        String[] labels = new String[choices.size()];
        boolean[] selected = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label; selected[i] = apps.isVpn(choices.get(i));
        }
        new AlertDialog.Builder(activity).setTitle("Популярные приложения с VPN")
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (dialog, which) -> {
                    if (!canEdit()) return;
                    Set<String> packages = apps.getSelectedPackages();
                    for (int i = 0; i < choices.size(); i++) {
                        boolean mark = activity.getDraftMode() == RoutingMode.ONLY_SELECTED ? selected[i] : !selected[i];
                        if (mark) packages.addAll(choices.get(i).routingPackages);
                        else packages.removeAll(choices.get(i).routingPackages);
                    }
                    activity.stageAppChoices(packages);
                }).show();
    }

    private void buildSites() {
        sitesHost.addView(label("Любимые сайты", 22, false));
        TextView note = label("Открывайте сайты в браузере с нужным режимом. На Android VPN применяется ко всему выбранному браузеру.", 14, true);
        margins(note, 8, 16); sitesHost.addView(note);
        LinearLayout add = horizontal(sitesHost);
        action(add, "Добавить сайт", true).setOnClickListener(view -> addSiteDialog("", ""));
        action(add, "Подборка", false).setOnClickListener(view -> siteCollection());
        EditText search = input("Найти сайт"); siteSearch = search;
        margins(search, 14, 10); sitesHost.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void afterTextChanged(Editable s) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                siteQuery = s.toString().trim().toLowerCase(Locale.ROOT); refreshSites();
            }
        });
        android.widget.RadioGroup filters = new android.widget.RadioGroup(activity); siteFilters = filters;
        filters.setOrientation(LinearLayout.HORIZONTAL); filters.setPadding(dp(3), dp(3), dp(3), dp(3));
        filters.setBackgroundResource(R.drawable.bg_filters_group);
        String[] labels = {"Все", "С VPN", "Без VPN"};
        for (int i = 0; i < labels.length; i++) {
            android.widget.RadioButton chip = new android.widget.RadioButton(activity, null, 0, R.style.Widget_WireRoute_FilterChip);
            chip.setId(View.generateViewId()); chip.setText(labels[i]); chip.setTag(i);
            filters.addView(chip, new LinearLayout.LayoutParams(0, dp(42), 1));
            if (i == 0) chip.setChecked(true);
        }
        filters.setOnCheckedChangeListener((group, checkedId) -> {
            siteFilter = (int) group.findViewById(checkedId).getTag(); refreshSites();
        });
        sitesHost.addView(filters);
        siteCount = label("", 12, true); margins(siteCount, 12, 0); sitesHost.addView(siteCount);
        siteRows = column(); sitesHost.addView(siteRows); refreshSites();
    }

    void refreshSites() {
        if (siteRows == null) return;
        siteRows.removeAllViews(); int count = 0;
        for (SiteLibrary.Site site : sites.list()) {
            if (siteDraft.containsKey(site.id)) site.vpn = siteDraft.get(site.id);
            if (siteFilter == 1 && !site.vpn || siteFilter == 2 && site.vpn) continue;
            if (!(site.name + " " + site.url).toLowerCase(Locale.ROOT).contains(siteQuery)) continue;
            count++;
            LinearLayout card = card(siteRows);
            card.addView(label(site.name, 17, false)); card.addView(label(Uri.parse(site.url).getHost(), 13, true));
            LinearLayout routes = horizontal(card);
            action(routes, "С VPN", site.vpn).setOnClickListener(view -> stageSite(site, true));
            action(routes, "Без VPN", !site.vpn).setOnClickListener(view -> stageSite(site, false));
            LinearLayout controls = horizontal(card);
            action(controls, "Открыть", false).setOnClickListener(view -> openSite(site));
            action(controls, "Удалить", false).setOnClickListener(view -> {
                new AlertDialog.Builder(activity).setTitle("Удалить «" + site.name + "» из списка?")
                        .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (dialog, which) -> {
                            try {
                                sites.remove(site.id); siteDraft.remove(site.id); refreshSites(); activity.refreshRoutingUi();
                            } catch (SiteLibrary.UnreadableSitesException unreadable) { canSaveSites(); }
                        }).show();
            });
        }
        siteCount.setText("В списке: " + count);
        if (!sites.isReadable()) siteRows.addView(label("Не удалось прочитать список сайтов. Старые записи сохранены.", 15, true));
        else if (count == 0) siteRows.addView(label(sites.list().isEmpty()
                ? "Добавьте первый сайт или выберите его из подборки."
                : "В этом фильтре ничего не найдено.", 15, true));
    }

    private void siteCollection() {
        String[] names = {"ChatGPT", "Gemini", "YouTube", "Instagram", "Wikipedia"};
        String[] addresses = {"https://chatgpt.com", "https://gemini.google.com", "https://youtube.com",
                "https://instagram.com", "https://wikipedia.org"};
        new AlertDialog.Builder(activity).setTitle("Добавить из подборки")
                .setItems(names, (dialog, which) -> addSiteDialog(names[which], addresses[which]))
                .setNegativeButton("Отмена", null).show();
    }

    private void addSiteDialog(String title, String address) {
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText name = input("Название"); name.setText(title); form.addView(name);
        EditText url = input("Адрес сайта"); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI); url.setText(address); form.addView(url);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Добавить сайт")
                .setView(form).setNegativeButton("Отмена", null).setPositiveButton("Добавить", null).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { sites.add(name.getText().toString(), url.getText().toString(), true); refreshSites(); dialog.dismiss(); }
            catch (SiteLibrary.UnreadableSitesException unreadable) { canSaveSites(); }
            catch (IllegalArgumentException ignored) { url.setError("Введите адрес, например example.com"); }
        });
    }

    private void openSite(SiteLibrary.Site site) {
        if (activity.hasRoutingDraft()) { toast("Сохраните или отмените выбор приложений перед открытием сайта"); return; }
        if (hasSiteDraft()) { toast("Сначала нажмите «Сохранить», чтобы применить выбор сайтов"); return; }
        List<InstalledApp> browsers = new ArrayList<>();
        List<InstalledApp> matching = new ArrayList<>();
        for (InstalledApp app : apps.getAllApps()) if (app.browser) {
            browsers.add(app);
            if (tunnel.isPackageUsingVpn(app.routingPackage) == site.vpn) matching.add(app);
        }
        if (browsers.isEmpty()) { toast("Не найден браузер. Установите браузер и вернитесь в приложение."); return; }
        if (site.vpn && tunnel.getSnapshot().state != TunnelController.State.UP) {
            new AlertDialog.Builder(activity).setTitle("Сначала подключите VPN")
                    .setMessage("Затем вернитесь к сайту и нажмите «Открыть».")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("К подключению", (dialog, which) -> activity.showSection("connection")).show(); return;
        }
        if (tunnel.getSnapshot().state == TunnelController.State.CONNECTING
                || tunnel.getSnapshot().state == TunnelController.State.DISCONNECTING) {
            toast("Дождитесь завершения подключения"); return;
        }
        if (!matching.isEmpty()) {
            chooseBrowser(matching, site, false); return;
        }
        chooseBrowser(browsers, site, true);
    }

    private void chooseBrowser(List<InstalledApp> choices, SiteLibrary.Site site, boolean configure) {
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) names[i] = choices.get(i).label;
        if (!configure && choices.size() == 1) { launchSite(site, choices.get(0).packageName); return; }
        new AlertDialog.Builder(activity)
                .setTitle(configure ? "Выберите браузер для этого режима" : "Открыть " + (site.vpn ? "с VPN" : "без VPN"))
                .setItems(names, (dialog, which) -> {
                    InstalledApp browser = choices.get(which);
                    if (!configure) { launchSite(site, browser.packageName); return; }
                    new AlertDialog.Builder(activity).setTitle("" + browser.label + (site.vpn ? " с VPN" : " без VPN"))
                            .setMessage("Этот режим применяется ко всему браузеру. VPN переподключится и применит все сохранённые изменения.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Применить и открыть", (confirmation, button) -> {
                                if (!canEdit()) return;
                                Set<String> packages = apps.getSelectedPackages();
                                boolean mark = activity.getDraftMode() == RoutingMode.ONLY_SELECTED ? site.vpn : !site.vpn;
                                if (mark) packages.addAll(browser.routingPackages);
                                else packages.removeAll(browser.routingPackages);
                                activity.stageAppChoices(packages); activity.saveRoutingDraft();
                                if (tunnel.getSnapshot().state == TunnelController.State.UP
                                        && tunnel.isPackageUsingVpn(browser.routingPackage) == site.vpn) {
                                    launchSite(site, browser.packageName);
                                } else if (tunnel.getSnapshot().state == TunnelController.State.DISCONNECTING
                                        || tunnel.getSnapshot().state == TunnelController.State.CONNECTING) {
                                    pendingSite = site; pendingBrowser = browser.packageName;
                                    pendingUserGeneration = tunnel.getUserActionGeneration();
                                } else toast("Вернитесь к подключению VPN и примените изменения");
                            }).show();
                }).setNegativeButton("Отмена", null).show();
    }

    void onTunnelStateChanged(TunnelController.Snapshot snapshot) {
        updateProfileActionsAvailability();
        if (pendingSite == null) return;
        if (tunnel.getUserActionGeneration() != pendingUserGeneration
                || snapshot.state == TunnelController.State.ERROR
                || !tunnel.isConnectionDesired()) { pendingSite = null; pendingBrowser = null; return; }
        if (snapshot.state == TunnelController.State.UP) {
            SiteLibrary.Site site = pendingSite; String browser = pendingBrowser;
            pendingSite = null; pendingBrowser = null;
            if (tunnel.isPackageUsingVpn(browser) == site.vpn) launchSite(site, browser);
            else toast("Режим браузера изменился. Откройте сайт ещё раз.");
        }
    }

    private void launchSite(SiteLibrary.Site site, String browser) {
        // The explicit browser prevents Android App Links from silently opening
        // a native application with a different VPN choice.
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(site.url));
        intent.setPackage(browser); intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try { activity.startActivity(intent); }
        catch (RuntimeException ignored) { toast("Не удалось открыть сайт в этом браузере"); }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); return layout;
    }
    private LinearLayout horizontal(LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(10);
        parent.addView(row, params); return row;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = column(); layout.setPadding(dp(18), dp(16), dp(18), dp(16));
        layout.setBackgroundResource(R.drawable.bg_card); margins(layout, 12, 0); parent.addView(layout); return layout;
    }
    private TextView label(String text, int size, boolean muted) {
        TextView label = new TextView(activity); label.setText(text); label.setTextSize(size);
        label.setTextColor(color(muted ? R.color.text_secondary : R.color.text_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        if (!muted) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dp(3), 0, dp(3)); return label;
    }
    private EditText input(String hint) {
        EditText text = new EditText(activity); text.setHint(hint); text.setSingleLine(true);
        text.setTextSize(15); text.setTextColor(color(R.color.text_primary));
        text.setHintTextColor(color(R.color.text_muted)); text.setMinHeight(dp(52));
        text.setPadding(dp(14), dp(8), dp(14), dp(8));
        text.setBackgroundResource(R.drawable.bg_search);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8);
        text.setLayoutParams(params); return text;
    }
    private Button action(LinearLayout parent, String text, boolean active) {
        Button button = new Button(activity, null, 0, R.style.Widget_WireRoute_OutlinedButton);
        button.setText(text); button.setTextSize(13);
        button.setBackgroundResource(active ? R.drawable.bg_route_on : R.drawable.bg_route_off);
        boolean dark = (activity.getResources().getConfiguration().uiMode & 48) == 32;
        button.setTextColor(active ? (dark ? 0xFF163626 : 0xFFFFFFFF) : color(R.color.text_secondary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(8);
        parent.addView(button, params); return button;
    }
    private void margins(View view, int top, int bottom) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(top); params.bottomMargin = dp(bottom); view.setLayoutParams(params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private int color(int resource) { return activity.getColor(resource); }
    private void toast(String text) { if (!closed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
