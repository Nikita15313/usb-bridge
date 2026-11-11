package app.wireroute;

import app.wireroute.release263.R;
import app.wireroute.release263.BuildConfig;
import app.wireroute.diagnostics.DiagnosticsActivity;
import app.wireroute.sites.LegacyProfileMigration;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;

import com.wireguard.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TunnelController.Listener {
    private static final int REQUEST_IMPORT = 1001;
    private static final int REQUEST_VPN = 1002;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    private SecureConfigStore configStore;
    private RoutingPreferences routingPreferences;
    private TunnelController tunnelController;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Thread priority is only an optimization; work must continue.
            }
            runnable.run();
        }, "WireRoute-main-io");
        thread.setDaemon(true);
        return thread;
    });

    private DrawerLayout drawerLayout;
    private View rootView;
    private View menuButton;
    private View drawerAppsButton;
    private TextView drawerVpnStatus;
    private TextView configName;
    private TextView configDetails;
    private Button importButton;
    private View diagnosticsButton;
    private RadioGroup routingModes;
    private RadioButton modeOnlySelected;
    private RadioButton modeBypassSelected;
    private TextView modeHint;
    private View editingBlockedCard;
    private TextView editingBlockedText;
    private Button editingBlockedAction;
    private EditText searchInput;
    private View clearSearchButton;
    private RadioGroup appFilters;
    private RadioButton filterAll;
    private RadioButton filterVpn;
    private RadioButton filterDirect;
    private TextView selectedCount;
    private Button selectVisibleButton;
    private Button clearVisibleButton;
    private ListView appsList;
    private ProgressBar appsProgress;
    private TextView appsEmpty;
    private View statusDot;
    private TextView statusText;
    private TextView routeSummary;
    private Button connectButton;
    private View pendingChangesCard;
    private Button applyChangesButton;
    private AppListAdapter appsAdapter;

    private ProfileLibrary profileLibrary;
    private SimplePanels panels;
    private String currentSection = "connection";
    private boolean resumed;
    private boolean transferInFlight;
    private long appsLoadedAt;
    private boolean appsLoading;
    private int packageSequence;
    private boolean profileLoaded;
    private Set<String> draftPackages;
    private RoutingMode draftMode;
    private boolean editingProfile;
    private final Runnable transferPoll = this::pollTransfer;

    private boolean settingMode;
    private boolean editingEnabled = true;
    private boolean switchingToAppRouting;
    private boolean legacyRecoveryBlocked;
    private boolean legacyConflictDialogVisible;
    private boolean importInProgress;
    private boolean connectActionStops;
    private boolean destroyed;
    private boolean usbPlainPermissionPending;
    private int appsLoadGeneration;
    private int profileLoadGeneration;
    private OnBackInvokedCallback backInvokedCallback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener routingChangeListener =
            (preferences, key) -> uiHandler.post(this::syncStoredRouting);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbPlainPermissionPending = savedInstanceState != null && savedInstanceState.getBoolean("usbPlainPermissionPending");
        setContentView(R.layout.activity_main);
        ((TextView) findViewById(R.id.versionText)).setText("WireRoute " + BuildConfig.VERSION_NAME);

        WireRouteApplication application = (WireRouteApplication) getApplication();
        configStore = application.getConfigStore();
        routingPreferences = application.getRoutingPreferences();
        tunnelController = application.getTunnelController();

        // v1.0 used BYPASS_SELECTED as an implicit default. Preserve that for an existing
        // profile, while a clean install gets the clearer "checkmark means VPN" mode.
        if (!routingPreferences.hasStoredMode()) {
            routingPreferences.setMode(configStore.hasConfig()
                    ? RoutingMode.BYPASS_SELECTED
                    : RoutingMode.ONLY_SELECTED);
        }

        profileLibrary = new ProfileLibrary(this);
        bindViews();
        panels = new SimplePanels(this, configStore, routingPreferences,
                tunnelController, appsAdapter, profileLibrary);
        showSection(savedInstanceState == null ? "connection"
                : savedInstanceState.getString("section", "connection"));
        registerBackHandler();
        applySystemBarInsets();
        configureInteractions();
        if (savedInstanceState != null) restoreDraftState(savedInstanceState);
        applyStoredRouting();
        updateProfileCard();
        refreshAppUi();
        retireLegacySiteModeIfNeeded();
        handleUsbIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbIntent(intent);
    }

    private void handleUsbIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("local.usbbridge.START_PLAIN", false)) return;
        intent.removeExtra("local.usbbridge.START_PLAIN");
        if (!tunnelController.canStartPlainUsb() || UsbPlainVpnService.running) return;
        if (UsbBridgeUnderlay.offeredRelaySession(this) == 0) {
            toast("Сначала подключите компьютер в USB Bridge"); return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission == null) UsbPlainVpnService.startIfAvailable(this);
        else {
            usbPlainPermissionPending = true;
            startActivityForResult(permission, REQUEST_VPN);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        routingPreferences.addChangeListener(routingChangeListener);
        tunnelController.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        uiHandler.post(transferPoll);
        syncStoredRouting();
        if (Build.VERSION.SDK_INT >= 26) {
            android.content.pm.ChangedPackages changes = getPackageManager().getChangedPackages(packageSequence);
            if (changes != null) { packageSequence = changes.getSequenceNumber(); appsLoadedAt = 0; }
        }
        loadInstalledApps();
        updateProfileCard();
        onTunnelStateChanged(tunnelController.getSnapshot());
        refreshAppUi();
    }

    @Override
    protected void onStop() {
        resumed = false;
        uiHandler.removeCallbacks(transferPoll);
        routingPreferences.removeChangeListener(routingChangeListener);
        tunnelController.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacksAndMessages(null);
        appsLoadGeneration++;
        profileLoadGeneration++;
        if (appsAdapter != null) {
            appsAdapter.close();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    backInvokedCallback);
            backInvokedCallback = null;
        }
        if (panels != null) panels.close();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        rootView = findViewById(R.id.rootView);
        menuButton = findViewById(R.id.menuButton);
        drawerAppsButton = findViewById(R.id.drawerAppsButton);
        drawerVpnStatus = findViewById(R.id.drawerVpnStatus);
        configName = findViewById(R.id.configName);
        configDetails = findViewById(R.id.configDetails);
        importButton = findViewById(R.id.importButton);
        diagnosticsButton = findViewById(R.id.diagnosticsButton);
        routingModes = findViewById(R.id.routingModes);
        modeOnlySelected = findViewById(R.id.modeOnlySelected);
        modeBypassSelected = findViewById(R.id.modeBypassSelected);
        modeHint = findViewById(R.id.modeHint);
        editingBlockedCard = findViewById(R.id.editingBlockedCard);
        editingBlockedText = findViewById(R.id.editingBlockedText);
        editingBlockedAction = findViewById(R.id.editingBlockedAction);
        searchInput = findViewById(R.id.searchInput);
        clearSearchButton = findViewById(R.id.clearSearchButton);
        appFilters = findViewById(R.id.appFilters);
        filterAll = findViewById(R.id.filterAll);
        filterVpn = findViewById(R.id.filterVpn);
        filterDirect = findViewById(R.id.filterDirect);
        selectedCount = findViewById(R.id.selectedCount);
        selectVisibleButton = findViewById(R.id.selectVisibleButton);
        clearVisibleButton = findViewById(R.id.clearVisibleButton);
        appsList = findViewById(R.id.appsList);
        appsProgress = findViewById(R.id.appsProgress);
        appsEmpty = findViewById(R.id.appsEmpty);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        routeSummary = findViewById(R.id.routeSummary);
        connectButton = findViewById(R.id.connectButton);
        pendingChangesCard = findViewById(R.id.pendingChangesCard);
        applyChangesButton = findViewById(R.id.applyChangesButton);

        appsAdapter = new AppListAdapter(this, packages -> {
            draftPackages = new HashSet<>(packages);
            refreshAppUi();
        });
        draftPackages = routingPreferences.getSelectedPackages();
        draftMode = routingPreferences.getMode();
        appsAdapter.setRoutingMode(draftMode);
        appsAdapter.setSelectedPackages(draftPackages);
        appsList.setAdapter(appsAdapter);
        appsList.setEmptyView(appsEmpty);
    }

    private void configureInteractions() {
        menuButton.setOnClickListener(view -> drawerLayout.openDrawer(GravityCompat.START));
        drawerAppsButton.setOnClickListener(view -> showSection("apps"));
        importButton.setOnClickListener(view -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openConfigPicker();
        });
        diagnosticsButton.setOnClickListener(view -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openDiagnostics(false);
        });

        findViewById(R.id.drawerConnectionButton).setOnClickListener(view -> showSection("connection"));
        findViewById(R.id.drawerSitesButton).setOnClickListener(view -> showSection("sites"));
        findViewById(R.id.drawerProfilesButton).setOnClickListener(view -> showSection("profiles"));
        findViewById(R.id.drawerSettingsButton).setOnClickListener(view -> showSection("settings"));
        findViewById(R.id.drawerStatisticsButton).setOnClickListener(view -> openDiagnostics(true));
        findViewById(R.id.connectionProfileButton).setOnClickListener(view -> showSection("profiles"));
        Spinner category = findViewById(R.id.appCategory);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Приложения", "Системные", "Все приложения"}));
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                appsAdapter.setCategory(AppCategory.values()[position]); refreshAppUi();
            }
        });

        routingModes.setOnCheckedChangeListener((group, checkedId) -> {
            if (settingMode) {
                return;
            }
            RoutingMode mode = checkedId == R.id.modeOnlySelected
                    ? RoutingMode.ONLY_SELECTED
                    : RoutingMode.BYPASS_SELECTED;
            if (mode != draftMode) {
                // Changing the default preserves every currently visible application's
                // route; only the meaning of the stored include/exclude set changes.
                Set<String> known = new HashSet<>();
                for (InstalledApp app : appsAdapter.getAllApps()) known.addAll(app.routingPackages);
                Set<String> inverted = new HashSet<>(known);
                inverted.removeAll(draftPackages);
                draftMode = mode; draftPackages = inverted;
                appsAdapter.setRoutingMode(mode);
                appsAdapter.setSelectedPackages(draftPackages);
                updateModeUi(mode);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = text == null ? "" : text.toString();
                appsAdapter.setQuery(query);
                refreshAppUi();
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        clearSearchButton.setOnClickListener(view -> searchInput.setText(""));

        appFilters.setOnCheckedChangeListener((group, checkedId) -> {
            AppFilter filter;
            if (checkedId == R.id.filterVpn) {
                filter = AppFilter.VPN;
            } else if (checkedId == R.id.filterDirect) {
                filter = AppFilter.DIRECT;
            } else {
                filter = AppFilter.ALL;
            }
            appsAdapter.setFilter(filter);
            refreshAppUi();
        });
        if (appFilters.getCheckedRadioButtonId() == -1) {
            filterAll.setChecked(true);
        }

        selectVisibleButton.setOnClickListener(view -> appsAdapter.routeVisible(true));
        clearVisibleButton.setOnClickListener(view -> appsAdapter.routeVisible(false));
        connectButton.setOnClickListener(view -> handleConnectButton());
        applyChangesButton.setOnClickListener(view -> saveRoutingDraft());
        findViewById(R.id.discardChangesButton).setOnClickListener(view -> {
            draftPackages = routingPreferences.getSelectedPackages(); draftMode = routingPreferences.getMode();
            appsAdapter.setRoutingMode(draftMode); appsAdapter.setSelectedPackages(draftPackages);
            panels.discardSites(); applyStoredRouting(); refreshAppUi();
        });
    }

    private void applyStoredRouting() {
        setRoutingMode(draftMode == null ? routingPreferences.getMode() : draftMode, false);
    }

    private void syncStoredRouting() {
        if (destroyed || appsAdapter == null) return;
        if (!hasRoutingDraft()) {
            draftPackages = routingPreferences.getSelectedPackages();
            draftMode = routingPreferences.getMode();
            appsAdapter.setRoutingMode(draftMode);
            appsAdapter.setSelectedPackages(draftPackages);
        }
        applyStoredRouting();
        refreshAppUi();
    }

    private void setRoutingMode(RoutingMode mode, boolean persist) {
        settingMode = true;
        if (mode == RoutingMode.ONLY_SELECTED) {
            modeOnlySelected.setChecked(true);
        } else {
            modeBypassSelected.setChecked(true);
        }
        settingMode = false;
        if (persist) {
            routingPreferences.setMode(mode);
        }
        updateModeUi(mode);
    }

    private void updateModeUi(RoutingMode mode) {
        refreshAppUi();
    }

    private void refreshAppUi() {
        if (appsAdapter == null) {
            return;
        }
        int total = appsAdapter.getCategoryCount();
        int vpn = appsAdapter.getVpnCount();
        boolean catalogReady = appsLoadedAt > 0 || appsAdapter.getTotalAppCount() > 0;
        String catalogStatus = appsLoading || appsLoadGeneration == 0
                ? "Загружаем приложения…" : "Список приложений пока недоступен";
        filterAll.setText(catalogReady ? "Все " + total : "Все");
        filterVpn.setText(catalogReady ? "С VPN " + vpn : "С VPN");
        filterDirect.setText(catalogReady ? "Без VPN " + (total - vpn) : "Без VPN");
        selectedCount.setText(catalogReady ? "Показано: " + appsAdapter.getVisibleAppCount() : catalogStatus);
        updateBulkActionsAvailability();
        clearSearchButton.setVisibility(appsAdapter.hasQuery() ? View.VISIBLE : View.INVISIBLE);
        appsEmpty.setText(appsAdapter.hasQuery() ? R.string.apps_empty_search : R.string.apps_empty);
        modeHint.setText(currentSection.equals("apps") ? "Выберите режим и нажмите «Сохранить»" : "WireRoute");
        int allVpn = 0;
        for (InstalledApp app : appsAdapter.getAllApps()) if (appsAdapter.isVpn(app)) allVpn++;
        routeSummary.setText(draftMode == RoutingMode.BYPASS_SELECTED ? "Все приложения с VPN, кроме ваших исключений"
                : !catalogReady ? catalogStatus
                : getResources().getQuantityString(R.plurals.route_only_summary_count, allVpn, allVpn));
        ((TextView) findViewById(R.id.connectionNote)).setText(hasRoutingDraft()
                ? "Есть несохранённые изменения. Текущее подключение пока работает с прежним выбором."
                : "VPN работает для приложений с выбранным режимом.");
        updatePendingChanges();
        updateDrawerState();
    }

    private void updateBulkActionsAvailability() {
        boolean enabled = editingEnabled && appsAdapter.getVisibleAppCount() > 0;
        selectVisibleButton.setEnabled(enabled);
        clearVisibleButton.setEnabled(enabled);
        selectVisibleButton.setAlpha(enabled ? 1f : 0.45f);
        clearVisibleButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void updatePendingChanges() {
        boolean pending = hasRoutingDraft() || (panels != null && panels.hasSiteDraft()) || (tunnelController.getSnapshot().state == TunnelController.State.UP
                && tunnelController.hasPendingConfigurationChanges());
        pendingChangesCard.setVisibility(pending ? View.VISIBLE : View.GONE);
        boolean draft = hasRoutingDraft() || panels != null && panels.hasSiteDraft();
        applyChangesButton.setText(draft ? "Сохранить" : "Применить");
        ((TextView) findViewById(R.id.pendingChangesText)).setText(draft
                ? "Есть несохранённый выбор" : "Профиль сохранён. Примените его к подключению.");
        findViewById(R.id.discardChangesButton).setVisibility(draft ? View.VISIBLE : View.GONE);
        findViewById(R.id.discardChangesButton).setEnabled(editingEnabled);
        applyChangesButton.setBackgroundResource(R.drawable.bg_button_primary);
        boolean dark = (getResources().getConfiguration().uiMode & 48) == 32;
        applyChangesButton.setTextColor(dark ? 0xFF163626 : 0xFFFFFFFF);
        applyChangesButton.setEnabled(pending && editingEnabled && !importInProgress);
        if (pending && tunnelController.getSnapshot().state == TunnelController.State.UP)
            routeSummary.setText(R.string.previous_selection_active);
    }

    boolean hasRoutingDraft() {
        return draftPackages != null && (draftMode != routingPreferences.getMode()
                || !draftPackages.equals(routingPreferences.getSelectedPackages()));
    }

    void saveRoutingDraft() {
        if (!editingEnabled) return;
        boolean stopEmpty = hasRoutingDraft() && draftMode == RoutingMode.ONLY_SELECTED && draftPackages.isEmpty()
                && tunnelController.getSnapshot().state == TunnelController.State.UP;
        boolean changed = hasRoutingDraft();
        boolean sitesChanged = panels != null && panels.hasSiteDraft();
        if (sitesChanged && !panels.canSaveSites()) return;
        if (changed && !routingPreferences.compareAndSetRouting(routingPreferences.getRevision(), draftMode, draftPackages)) {
            toast("Не удалось сохранить выбор. Попробуйте ещё раз."); return;
        }
        if (sitesChanged) panels.saveSites();
        if (stopEmpty) tunnelController.disconnect();
        else if (tunnelController.getSnapshot().state == TunnelController.State.UP
                && tunnelController.hasPendingConfigurationChanges()) tunnelController.applyRoutingChanges();
        refreshAppUi(); if (changed || sitesChanged) toast(stopEmpty
                ? "Выбор сохранён. VPN отключён: все приложения без VPN." : "Выбор сохранён");
    }

    private void loadInstalledApps() {
        if (appsLoading || appsAdapter.getTotalAppCount() > 0
                && android.os.SystemClock.elapsedRealtime() - appsLoadedAt < 30_000L) return;
        appsLoading = true;
        final int generation = ++appsLoadGeneration;
        if (appsAdapter.getTotalAppCount() == 0) {
            appsProgress.setVisibility(View.VISIBLE);
            appsEmpty.setVisibility(View.GONE);
        }
        ioExecutor.execute(() -> {
            List<InstalledApp> apps = null;
            Throwable failure = null;
            try {
                apps = new InstalledAppsRepository(this).load();
            } catch (Throwable error) {
                failure = error;
            }
            List<InstalledApp> result = apps;
            Throwable error = failure;
            runOnUiThread(() -> {
                if (destroyed || generation != appsLoadGeneration) {
                    return;
                }
                appsLoading = false;
                appsProgress.setVisibility(View.GONE);
                if (error == null) {
                    appsLoadedAt = android.os.SystemClock.elapsedRealtime();
                    appsAdapter.setItems(result);
                    updateEditingAvailability();
                    refreshAppUi();
                } else if (appsAdapter.getTotalAppCount() == 0) {
                    refreshAppUi();
                    appsEmpty.setText(R.string.apps_load_error);
                    appsEmpty.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void updateProfileCard() {
        findViewById(R.id.connectionProfileButton).setVisibility(configStore.hasConfig() ? View.VISIBLE : View.GONE);
        if (configStore.hasConfig()) ((Button) findViewById(R.id.connectionProfileButton)).setText(configStore.getDisplayName());
        if (profileLoaded && configStore.hasConfig()) {
            updateReadyProfileStatus();
            return;
        }
        final int generation = ++profileLoadGeneration;
        if (!configStore.hasConfig()) {
            ((Button) findViewById(R.id.connectionProfileButton)).setText("Добавить профиль");
            configName.setText(R.string.config_missing);
            configDetails.setText(R.string.config_missing_details);
            importButton.setText(R.string.import_config);
            updateDrawerState();
            return;
        }

        configName.setText("Управление профилями");
        configDetails.setText(R.string.config_reading);
        importButton.setText(R.string.replace_config);
        ioExecutor.execute(() -> {
            try {
                String raw = configStore.load();
                ConfigUtils.Info info = ConfigUtils.inspect(ConfigUtils.parse(raw));
                runOnUiThread(() -> {
                    if (!destroyed && generation == profileLoadGeneration) {
                        profileLoaded = true;
                        updateReadyProfileStatus();
                        ((Button) findViewById(R.id.connectionProfileButton)).setText(configStore.getDisplayName());
                    }
                });
            } catch (Throwable ignored) {
                runOnUiThread(() -> {
                    if (!destroyed && generation == profileLoadGeneration) {
                        configDetails.setText(R.string.config_read_error);
                    }
                });
            }
        });
    }

    private void updateReadyProfileStatus() {
        TunnelController.State state = tunnelController.getSnapshot().state;
        configDetails.setText(state == TunnelController.State.UP
                ? tunnelController.hasPendingConfigurationChanges()
                    ? "Есть неприменённые изменения" : "VPN включён"
                : state == TunnelController.State.CONNECTING ? "VPN подключается…"
                : state == TunnelController.State.DISCONNECTING ? "VPN отключается…"
                : "Готов к подключению");
    }

    private String formatConfigInfo(ConfigUtils.Info info) {
        int routeText;
        if (info.fullIpv4 && info.fullIpv6) {
            routeText = R.string.config_route_full;
        } else if (info.fullIpv4) {
            routeText = R.string.config_route_ipv4;
        } else if (info.fullIpv6) {
            routeText = R.string.config_route_ipv6;
        } else {
            routeText = R.string.config_route_split;
        }
        return getResources().getQuantityString(
                R.plurals.config_peers_count,
                info.peerCount,
                info.peerCount,
                getString(routeText)
        );
    }

    private void openConfigPicker() {
        if (importInProgress || switchingToAppRouting) {
            toast("Подождите — текущее изменение уже применяется");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "application/octet-stream",
                "application/x-wireguard-profile"
        });
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void handleConnectButton() {
        TunnelController.Snapshot current = tunnelController.getSnapshot();
        // Preserve the action the user saw. A late Android DOWN callback must
        // never turn a tap on the visible Stop/Cancel button into a new Connect.
        if (connectActionStops) {
            tunnelController.disconnect();
            return;
        }
        if (importInProgress || switchingToAppRouting
                || current.state == TunnelController.State.UP
                || current.state == TunnelController.State.CONNECTING
                || current.state == TunnelController.State.DISCONNECTING) return;

        if (!configStore.hasConfig()) {
            openConfigPicker();
            return;
        }
        if (legacyRecoveryBlocked || LegacyProfileMigration.hasPendingRecovery(this)) {
            legacyRecoveryBlocked = true;
            updateEditingAvailability();
            updateEditingBlocker();
            toast("Сначала завершим возврат к обычному профилю");
            return;
        }
        if (routingPreferences.getMode() == RoutingMode.ONLY_SELECTED
                && appsAdapter.getSelectedInstalledCount() == 0) {
            toast(R.string.select_one_required);
            return;
        }

        String usbIssue = UsbBridgeUnderlay.preflightIssue(this);
        if (usbIssue != null) {
            statusText.setText(usbIssue);
            toast(usbIssue);
            return;
        }

        Intent permission = VpnService.prepare(this);
        if (permission == null) {
            tunnelController.connect();
        } else {
            startActivityForResult(permission, REQUEST_VPN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                if (usbPlainPermissionPending) UsbPlainVpnService.startIfAvailable(this);
                else tunnelController.connect();
            } else {
                toast(R.string.permission_denied);
            }
            usbPlainPermissionPending = false;
        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    importConfig(uri);
                }
            }
        }
    }

    private void importConfig(Uri uri) {
        String displayName = queryDisplayName(uri);
        final long routingRevisionAtStart = routingPreferences.getRevision();
        importInProgress = true;
        updateEditingAvailability();
        ioExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("Content resolver returned no stream");
                }
                byte[] bytes = readLimited(input);
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                    text = text.substring(1);
                }

                Config parsed = ConfigUtils.parse(text);
                String canonical = parsed.toWgQuickString();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (LegacyProfileMigration.hasPendingRecovery(this)) {
                        importInProgress = false;
                        legacyRecoveryBlocked = true;
                        updateEditingAvailability();
                        updateEditingBlocker();
                        toast("Сначала завершим обновление старого профиля");
                        retireLegacySiteModeIfNeeded();
                        return;
                    }
                    commitImportedConfig(canonical, displayName, parsed,
                            routingRevisionAtStart);
                });
            } catch (ConfigTooLargeException error) {
                runOnUiThread(() -> finishFailedImport(R.string.config_too_large));
            } catch (com.wireguard.config.BadConfigException error) {
                runOnUiThread(() -> finishFailedImport(R.string.config_invalid));
            } catch (Throwable error) {
                runOnUiThread(() -> finishFailedImport(R.string.config_read_error));
            }
        });
    }

    private void commitImportedConfig(String canonical, String displayName, Config parsed,
                                      long routingRevisionAtStart) {
        final boolean preserveDraft = hasRoutingDraft();
        final boolean updateCurrentProfile = editingProfile;
        ioExecutor.execute(() -> {
            try {
                String previous = configStore.load();
                if (previous != null) profileLibrary.remember(previous, configStore.getDisplayName());
                configStore.prepareEncryptionKey();
                if (updateCurrentProfile) profileLibrary.update(previous, canonical, displayName);
                else profileLibrary.remember(canonical, displayName);
                // Write the current profile last. An archive failure must leave the current connection intact.
                configStore.save(canonical, displayName);
                editingProfile = false;
                // The current choice belongs to WireRoute, not to a possibly stale
                // Included/ExcludedApplications list inside the original profile.
                // Only a new explicit import may adopt the imported app list.
                if (!updateCurrentProfile) adoptRoutingFromConfig(parsed, routingRevisionAtStart);
                // The controller outlives this Activity. Record the change even if
                // Android recreates the screen before the storage callback returns.
                tunnelController.noteProfileChanged();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    importInProgress = false;
                    if (!preserveDraft) { draftPackages = routingPreferences.getSelectedPackages(); draftMode = routingPreferences.getMode(); }
                    appsAdapter.setRoutingMode(draftMode);
                    appsAdapter.setSelectedPackages(draftPackages);
                    applyStoredRouting();
                    profileLoaded = false;
                    panels.refreshProfiles();
                    updateProfileCard();
                    updateEditingAvailability();
                    refreshAppUi();
                    onTunnelStateChanged(tunnelController.getSnapshot());
                    toast(R.string.config_saved);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    finishFailedImport(R.string.config_read_error);
                });
            }
        });
    }

    private void adoptRoutingFromConfig(Config config, long expectedRevision) {
        Set<String> included = config.getInterface().getIncludedApplications();
        Set<String> excluded = config.getInterface().getExcludedApplications();
        if (!included.isEmpty()) {
            routingPreferences.compareAndSetRouting(expectedRevision,
                    RoutingMode.ONLY_SELECTED, included);
        } else if (!excluded.isEmpty()) {
            routingPreferences.compareAndSetRouting(expectedRevision,
                    RoutingMode.BYPASS_SELECTED, excluded);
        }
        // A normal WireGuard profile has neither directive. In that case the user's
        // per-app choices belong to WireRoute and must survive a profile replacement.
    }

    private byte[] readLimited(InputStream input) throws IOException, ConfigTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_CONFIG_BYTES) {
                throw new ConfigTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A provider may omit metadata. A neutral name is fine.
        }
        return "WireGuard.conf";
    }

    private void finishFailedImport(int message) {
        importInProgress = false;
        updateEditingAvailability();
        toast(message);
    }

    @Override
    public void onTunnelStateChanged(TunnelController.Snapshot snapshot) {
        connectActionStops = snapshot.state == TunnelController.State.UP
                || snapshot.state == TunnelController.State.CONNECTING;
        syncStoredRouting();
        updateProfileCard();
        updateEditingAvailability();
        updateEditingBlocker();

        switch (snapshot.state) {
            case CONNECTING:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_off);
                statusText.setText(R.string.status_connecting);
                connectButton.setText(R.string.cancel_connecting);
                connectButton.setEnabled(true);
                connectButton.setBackgroundResource(R.drawable.bg_button_primary);
                break;
            case UP:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_on);
                statusText.setText(UsbBridgeUnderlay.active()
                        ? "VPN через USB · по вашему выбору" : getString(R.string.status_on));
                connectButton.setText(R.string.disconnect);
                connectButton.setEnabled(true);
                connectButton.setBackgroundResource(R.drawable.bg_button_danger);
                break;
            case DISCONNECTING:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_off);
                statusText.setText(R.string.status_disconnecting);
                connectButton.setText(R.string.working);
                connectButton.setEnabled(false);
                connectButton.setBackgroundResource(R.drawable.bg_button_primary);
                break;
            case ERROR:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_error);
                statusText.setText(errorText(snapshot.failure));
                connectButton.setText(configStore.hasConfig() ? R.string.connect : R.string.add_config);
                connectButton.setEnabled(true);
                connectButton.setBackgroundResource(R.drawable.bg_button_primary);
                break;
            case DOWN:
            default:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_off);
                statusText.setText(UsbPlainVpnService.running
                        ? "VPN телефона выключен · интернет от ПК" : getString(R.string.status_off));
                connectButton.setText(configStore.hasConfig() ? R.string.connect : R.string.add_config);
                connectButton.setEnabled(true);
                connectButton.setBackgroundResource(R.drawable.bg_button_primary);
                break;
        }
        if (legacyRecoveryBlocked || switchingToAppRouting || importInProgress) {
            connectButton.setEnabled(snapshot.state == TunnelController.State.UP
                    || snapshot.state == TunnelController.State.CONNECTING);
        }
        refreshAppUi();
        if (panels != null) panels.onTunnelStateChanged(snapshot);
    }

    private void updateEditingAvailability() {
        TunnelController.State state = tunnelController.getSnapshot().state;
        boolean changingConnection = state == TunnelController.State.CONNECTING
                || state == TunnelController.State.DISCONNECTING;
        boolean canEdit = !importInProgress && !switchingToAppRouting
                && !legacyRecoveryBlocked && !changingConnection;
        editingEnabled = canEdit;
        importButton.setEnabled(canEdit);
        importButton.setAlpha(canEdit ? 1f : 0.72f);
        modeOnlySelected.setEnabled(canEdit && appsAdapter.getTotalAppCount() > 0);
        modeBypassSelected.setEnabled(canEdit && appsAdapter.getTotalAppCount() > 0);
        updateBulkActionsAvailability();
        appsAdapter.setEditingEnabled(canEdit);
        if (panels != null) panels.setEditable(canEdit);
        updatePendingChanges();
    }

    private void updateEditingBlocker() {
        if (switchingToAppRouting) {
            editingBlockedCard.setVisibility(View.VISIBLE);
            editingBlockedText.setText(R.string.switching_to_apps);
            editingBlockedAction.setText("Подождите");
            editingBlockedAction.setEnabled(false);
            editingBlockedAction.setOnClickListener(null);
            return;
        }
        if (legacyRecoveryBlocked) {
            editingBlockedCard.setVisibility(View.VISIBLE);
            editingBlockedText.setText(
                    "Нужно завершить возврат к обычному WireGuard-профилю.");
            editingBlockedAction.setText("Завершить");
            editingBlockedAction.setEnabled(true);
            editingBlockedAction.setOnClickListener(view -> retireLegacySiteModeIfNeeded());
            return;
        }
        editingBlockedCard.setVisibility(View.GONE);
        editingBlockedAction.setOnClickListener(null);
    }

    private void retireLegacySiteModeIfNeeded() {
        if (switchingToAppRouting) return;
        if (!LegacyProfileMigration.hasPendingRecovery(this)) {
            legacyRecoveryBlocked = false;
            updateEditingAvailability();
            updateEditingBlocker();
            return;
        }
        legacyRecoveryBlocked = true;
        updateEditingAvailability();
        updateEditingBlocker();
        if (LegacyProfileMigration.hasConflict(this)) {
            showLegacyProfileConflictDialog();
        } else {
            switchToAppRouting();
        }
    }

    private void showLegacyProfileConflictDialog() {
        if (legacyConflictDialogVisible || destroyed) return;
        legacyConflictDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Завершить обновление")
                .setMessage("Старая функция сайтов сохранила исходный профиль, но позже конфиг был изменён. Какой вариант оставить?")
                .setPositiveButton("Текущий", (choiceDialog, which) ->
                        resolveLegacyProfileConflict(true))
                .setNegativeButton("Исходный", (choiceDialog, which) ->
                        resolveLegacyProfileConflict(false))
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(ignored -> legacyConflictDialogVisible = false);
        dialog.show();
    }

    private void resolveLegacyProfileConflict(boolean keepCurrent) {
        startLegacyRecovery();
        LegacyProfileMigration.resolveConflict(this, keepCurrent,
                this::finishLegacyRecovery);
    }

    private void switchToAppRouting() {
        if (switchingToAppRouting) return;
        startLegacyRecovery();
        LegacyProfileMigration.recover(this, this::finishLegacyRecovery);
    }

    private void startLegacyRecovery() {
        switchingToAppRouting = true;
        legacyRecoveryBlocked = true;
        connectButton.setEnabled(false);
        updateEditingAvailability();
        updateEditingBlocker();
    }

    private void finishLegacyRecovery(boolean success, String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            switchingToAppRouting = false;
            legacyRecoveryBlocked = LegacyProfileMigration.hasPendingRecovery(this);
            if (success) {
                appsAdapter.setSelectedPackages(routingPreferences.getSelectedPackages());
                applyStoredRouting();
                updateProfileCard();
            }
            onTunnelStateChanged(tunnelController.getSnapshot());
            toast(message);
            if (!success && LegacyProfileMigration.hasConflict(this)) {
                showLegacyProfileConflictDialog();
            }
        });
    }

    private void updateDrawerState() {
        if (drawerVpnStatus == null) return;
        drawerVpnStatus.setText(statusText.getText());
    }

    @Override
    @SuppressLint("GestureBackNavigation") // API 24–32 still dispatch here.
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (hasRoutingDraft() || panels != null && panels.hasSiteDraft()) { showUnsavedExit(); return; }
        super.onBackPressed();
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        backInvokedCallback = () -> {
            if (drawerLayout != null
                    && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else if (hasRoutingDraft() || panels != null && panels.hasSiteDraft()) {
                showUnsavedExit();
            } else {
                finishAfterTransition();
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String errorText(TunnelController.Failure failure) {
        int detail;
        switch (failure) {
            case USB_SOURCE:
                return "Проверьте USB-кабель, выберите MIDI на телефоне и нажмите «Подключить» в USB Bridge на компьютере";
            case USB_SWITCH:
                return "USB Bridge не подтвердил переключение на VPN. Обновите USB Bridge на телефоне и компьютере, затем повторите включение";
            case USB_PROFILE:
                return "Для USB нужен полный VPN-профиль одного сервера; частичные маршруты не поддерживаются";
            case NO_CONFIG:
                detail = R.string.error_no_config;
                break;
            case EMPTY_SELECTION:
                detail = R.string.select_one_required;
                break;
            case DNS:
                detail = R.string.error_dns;
                break;
            case VPN_INTERFACE:
                detail = R.string.error_vpn_busy;
                break;
            case BACKEND:
                detail = R.string.error_backend;
                break;
            case PROFILE:
                detail = R.string.config_read_error;
                break;
            case UNKNOWN:
            case NONE:
            default:
                detail = R.string.error_unknown;
                break;
        }
        return getString(R.string.status_error) + ": " + getString(detail);
    }

    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final int left = rootView.getPaddingLeft();
            final int top = rootView.getPaddingTop();
            final int right = rootView.getPaddingRight();
            final int bottom = rootView.getPaddingBottom();
            rootView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                view.setPadding(
                        left + bars.left,
                        top + bars.top,
                        right + bars.right,
                        bottom + bars.bottom
                );
                return windowInsets;
            });
            rootView.requestApplyInsets();
        } else {
            rootView.setFitsSystemWindows(true);
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }


    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("usbPlainPermissionPending", usbPlainPermissionPending);
        state.putString("section", currentSection);
        state.putStringArrayList("draftPackages", new java.util.ArrayList<>(draftPackages));
        state.putString("draftMode", draftMode.name());
        state.putString("appQuery", searchInput.getText().toString());
        state.putInt("appFilter", appFilters.getCheckedRadioButtonId());
        state.putInt("appCategory", ((Spinner) findViewById(R.id.appCategory)).getSelectedItemPosition());
        if (panels != null) panels.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        settingMode = true;
        super.onRestoreInstanceState(state);
        settingMode = false;
        restoreDraftState(state);
    }

    private void restoreDraftState(Bundle state) {
        if (panels != null) panels.restoreState(state);
        java.util.ArrayList<String> restored = state.getStringArrayList("draftPackages");
        if (restored != null) {
            draftPackages = new HashSet<>(restored);
            draftMode = RoutingMode.fromStored(state.getString("draftMode"));
            appsAdapter.setRoutingMode(draftMode); appsAdapter.setSelectedPackages(draftPackages);
            searchInput.setText(state.getString("appQuery", ""));
            appFilters.check(state.getInt("appFilter", R.id.filterAll));
            ((Spinner) findViewById(R.id.appCategory)).setSelection(state.getInt("appCategory", 0));
            applyStoredRouting(); refreshAppUi();
        }
    }

    void showSection(String section) {
        currentSection = section;
        int[] panelsIds = {R.id.connectionPanel, R.id.appsPanel, R.id.sitesPanel,
                R.id.profilesPanel, R.id.settingsPanel};
        String[] sections = {"connection", "apps", "sites", "profiles", "settings"};
        String[] titles = {"Подключение", "Приложения", "Сайты", "Профили", "Настройки"};
        int[] navIds = {R.id.drawerConnectionButton, R.id.drawerAppsButton, R.id.drawerSitesButton,
                R.id.drawerProfilesButton, R.id.drawerSettingsButton};
        for (int i = 0; i < sections.length; i++) {
            boolean chosen = sections[i].equals(section);
            findViewById(panelsIds[i]).setVisibility(chosen ? View.VISIBLE : View.GONE);
            findViewById(navIds[i]).setBackgroundResource(chosen ? R.drawable.bg_recommended_card : R.drawable.bg_row_selector);
            if (chosen) ((TextView) findViewById(R.id.sectionTitle)).setText(titles[i]);
        }
        if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
        if (!section.equals("apps")) {
            searchInput.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
        }
        if (panels != null && section.equals("profiles")) panels.refreshProfiles();
        if (panels != null && section.equals("sites")) panels.refreshSites();
        if (appsAdapter != null) refreshAppUi();
    }

    private void openDiagnostics(boolean statistics) {
        drawerLayout.closeDrawer(GravityCompat.START);
        Intent intent = new Intent(this, DiagnosticsActivity.class);
        intent.putExtra("section", statistics ? "statistics" : "diagnostics");
        startActivity(intent);
    }

    private void showUnsavedExit() {
        new AlertDialog.Builder(this).setTitle("Есть несохранённый выбор")
                .setMessage("Сохранить изменения перед выходом?")
                .setNegativeButton("Остаться", null)
                .setNeutralButton("Не сохранять", (dialog, which) -> finish())
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    saveRoutingDraft(); if (!hasRoutingDraft() && !panels.hasSiteDraft()) finish();
                }).show();
    }

    void refreshRoutingUi() { syncStoredRouting(); }
    RoutingMode getDraftMode() { return draftMode; }
    void stageAppChoices(Set<String> packages) {
        draftPackages = new HashSet<>(packages);
        appsAdapter.setSelectedPackages(draftPackages); refreshAppUi();
    }

    void profileChanged() {
        profileLoaded = false; updateProfileCard();
        onTunnelStateChanged(tunnelController.getSnapshot());
    }

    void saveProfileText(String raw, String name, boolean edit) {
        if (!editingEnabled || raw.length() > MAX_CONFIG_BYTES) { toast(R.string.config_too_large); return; }
        importInProgress = true; editingProfile = edit; updateEditingAvailability();
        final long revision = routingPreferences.getRevision();
        ioExecutor.execute(() -> {
            try {
                Config parsed = ConfigUtils.parse(raw);
                String canonical = parsed.toWgQuickString();
                runOnUiThread(() -> {
                    if (!destroyed) commitImportedConfig(canonical, name, parsed, revision);
                });
            } catch (Throwable ignored) {
                runOnUiThread(() -> finishFailedImport(R.string.config_invalid));
            }
        });
    }

    private void pollTransfer() {
        if (!resumed || destroyed) return;
        if (!currentSection.equals("connection") || transferInFlight
                || tunnelController.getSnapshot().state != TunnelController.State.UP) {
            if (tunnelController.getSnapshot().state != TunnelController.State.UP) {
                ((TextView) findViewById(R.id.connectionReceived)).setText("Получено: —");
                ((TextView) findViewById(R.id.connectionSent)).setText("Отправлено: —");
            }
            uiHandler.postDelayed(transferPoll, 2_000L); return;
        }
        transferInFlight = true;
        ioExecutor.execute(() -> {
            TunnelController.TransferSnapshot transfer = tunnelController.getTransferSnapshot();
            runOnUiThread(() -> {
                transferInFlight = false;
                if (destroyed || !resumed) return;
                if (transfer.available) {
                    ((TextView) findViewById(R.id.connectionReceived)).setText("Получено: "
                            + app.wireroute.diagnostics.TrafficUsageRepository.formatBytes(transfer.rxBytes));
                    ((TextView) findViewById(R.id.connectionSent)).setText("Отправлено: "
                            + app.wireroute.diagnostics.TrafficUsageRepository.formatBytes(transfer.txBytes));
                }
                uiHandler.postDelayed(transferPoll, 2_000L);
            });
        });
    }

    private static final class ConfigTooLargeException extends Exception {
    }
}
