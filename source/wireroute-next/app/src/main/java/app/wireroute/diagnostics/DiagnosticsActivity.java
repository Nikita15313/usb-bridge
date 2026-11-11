package app.wireroute.diagnostics;

import app.wireroute.release263.R;
import app.wireroute.RoutingMode;
import app.wireroute.RoutingPreferences;
import app.wireroute.ReportStore;
import app.wireroute.SecureConfigStore;
import app.wireroute.WireRouteApplication;
import app.wireroute.SessionDiagnostics;
import app.wireroute.TunnelController;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-facing replacement for the diagnostics, traffic and speed tabs present in 2.4. */
public final class DiagnosticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService transferWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService diagnosticWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NetworkTestEngine testEngine;
    private TunnelProbe tunnelProbe;
    private TrafficUsageRepository trafficRepository;
    private boolean destroyed;
    private boolean speedRunning;
    private boolean networkRunning;
    private boolean resumed;
    private boolean transferRequestInFlight;

    private TextView overviewTitle;
    private TextView overviewText;
    private LinearLayout overviewPanel;
    private TextView overviewProfile;
    private TextView overviewRouting;
    private TextView overviewTransfer;
    private LinearLayout speedPanel;
    private LinearLayout trafficPanel;
    private LinearLayout networkPanel;
    private Button runSpeedButton;
    private ProgressBar speedProgress;
    private TextView speedStatus;
    private TextView directResult;
    private TextView vpnResult;
    private Button shareSpeedButton;
    private Button trafficActionButton;
    private TextView trafficHeading;
    private ProgressBar trafficProgress;
    private TextView trafficResult;
    private TextView networkResult;
    private Button refreshNetworkButton;
    private ProgressBar networkProgress;
    private Button shareNetworkButton;
    private String lastNetworkReport;
    private NetworkTestEngine.PathResult lastDirect;
    private NetworkTestEngine.PathResult lastVpn;
    private int trafficDays = 30;
    private int trafficLoadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        testEngine = new NetworkTestEngine(this);
        tunnelProbe = new TunnelProbe(this);
        trafficRepository = new TrafficUsageRepository(this);
        bindViews();
        applyInsets();
        configureUi();
        refreshNetworkState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshNetworkState();
        scheduleTransferPoll(0L);
        RadioGroup tabs = findViewById(R.id.diagnosticsTabs);
        if (tabs.getCheckedRadioButtonId() == R.id.tabTraffic
                && trafficRepository.hasAccess()) {
            loadTraffic();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (testEngine != null) testEngine.cancel();
        if (tunnelProbe != null) tunnelProbe.cancel();
        worker.shutdownNow();
        transferWorker.shutdownNow();
        diagnosticWorker.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        overviewTitle = findViewById(R.id.diagnosticsOverviewTitle);
        overviewText = findViewById(R.id.diagnosticsOverviewText);
        overviewPanel = findViewById(R.id.overviewPanel);
        overviewProfile = findViewById(R.id.diagnosticsOverviewProfile);
        overviewRouting = findViewById(R.id.diagnosticsOverviewRouting);
        overviewTransfer = findViewById(R.id.diagnosticsOverviewTransfer);
        speedPanel = findViewById(R.id.speedPanel);
        trafficPanel = findViewById(R.id.trafficPanel);
        networkPanel = findViewById(R.id.networkPanel);
        runSpeedButton = findViewById(R.id.runSpeedButton);
        speedProgress = findViewById(R.id.speedProgress);
        speedStatus = findViewById(R.id.speedStatus);
        directResult = findViewById(R.id.directResult);
        vpnResult = findViewById(R.id.vpnResult);
        shareSpeedButton = findViewById(R.id.shareSpeedButton);
        trafficActionButton = findViewById(R.id.trafficActionButton);
        trafficHeading = findViewById(R.id.trafficHeading);
        trafficProgress = findViewById(R.id.trafficProgress);
        trafficResult = findViewById(R.id.trafficResult);
        networkResult = findViewById(R.id.networkResult);
        refreshNetworkButton = findViewById(R.id.refreshNetworkButton);
        networkProgress = findViewById(R.id.networkProgress);
        shareNetworkButton = findViewById(R.id.shareNetworkButton);
    }

    private void configureUi() {
        Button journalButton = new Button(this, null, 0, R.style.Widget_WireRoute_OutlinedButton);
        journalButton.setText("Журнал соединения");
        journalButton.setAllCaps(false);
        LinearLayout.LayoutParams journalLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * getResources().getDisplayMetrics().density));
        journalLayout.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
        journalButton.setLayoutParams(journalLayout);
        overviewPanel.addView(journalButton);
        journalButton.setOnClickListener(view -> showSessionJournal());
        findViewById(R.id.diagnosticsBack).setOnClickListener(view -> finish());
        findViewById(R.id.overviewMainButton).setOnClickListener(view -> finish());
        RadioGroup tabs = findViewById(R.id.diagnosticsTabs);
        tabs.setOnCheckedChangeListener((group, checkedId) -> showPanel(checkedId));
        runSpeedButton.setOnClickListener(view -> {
            if (speedRunning) stopSpeedTest();
            else runSpeedTest();
        });
        shareSpeedButton.setOnClickListener(view -> shareSpeedResult());
        trafficActionButton.setOnClickListener(view -> {
            if (trafficRepository.hasAccess()) loadTraffic();
            else openUsageAccess();
        });
        RadioGroup periods = findViewById(R.id.trafficPeriods);
        periods.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.trafficToday) trafficDays = 1;
            else if (checkedId == R.id.trafficWeek) trafficDays = 7;
            else trafficDays = 30;
            trafficHeading.setText(trafficDays == 1
                    ? "Трафик телефона за сегодня" : "Трафик телефона за " + trafficDays + " дней");
            if (((android.widget.RadioButton) findViewById(R.id.tabTraffic)).isChecked()
                    && trafficRepository.hasAccess()) {
                loadTraffic();
            }
        });
        refreshNetworkButton.setOnClickListener(view -> runNetworkDiagnostics());
        shareNetworkButton.setOnClickListener(view -> shareNetworkResult());
        boolean statistics = "statistics".equals(getIntent().getStringExtra("section"));
        ((TextView) findViewById(R.id.diagnosticsTitle)).setText(statistics ? "Статистика" : "Диагностика");
        findViewById(R.id.diagnosticsStatusCard).setVisibility(statistics ? View.GONE : View.VISIBLE);
        findViewById(R.id.overviewMainButton).setVisibility(View.GONE);
        findViewById(R.id.tabOverview).setVisibility(statistics ? View.GONE : View.VISIBLE);
        findViewById(R.id.tabTraffic).setVisibility(statistics ? View.VISIBLE : View.GONE);
        findViewById(R.id.tabSpeed).setVisibility(statistics ? View.VISIBLE : View.GONE);
        findViewById(R.id.tabNetwork).setVisibility(statistics ? View.GONE : View.VISIBLE);
        ((android.widget.RadioButton) findViewById(statistics ? R.id.tabTraffic : R.id.tabOverview)).setChecked(true);
        showPanel(statistics ? R.id.tabTraffic : R.id.tabOverview);
    }

    private void showSessionJournal() {
        SessionDiagnostics diagnostics = SessionDiagnostics.get(this);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Профиль и журнал соединения")
                .setMessage("Читаю метаданные профиля…")
                .setPositiveButton("Скопировать", null)
                .setNegativeButton("Закрыть", null).create();
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        diagnosticWorker.execute(() -> {
            String metadata = ProfileDiagnostics.collect(this);
            ProfileDiagnostics.logGeneratedReport(metadata);
            diagnostics.dumpToUsb();
            SessionDiagnostics.Report report = diagnostics.getReport();
            String text = "Сохранённый профиль: ключи, адреса и список приложений скрыты. "
                    + "Это настройки сохранённого профиля; активный туннель мог быть запущен раньше.\n\n"
                    + metadata + "\n\n" + report.summary + "\n" + report.details
                    + "\n\n" + diagnostics.getLogText();
            runOnUiThread(() -> {
                if (destroyed || !dialog.isShowing()) return;
                dialog.setMessage(text);
                Button copy = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                copy.setEnabled(true);
                copy.setOnClickListener(view -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WireRoute", text));
                    Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    private void showPanel(int checkedId) {
        boolean overview = checkedId == R.id.tabOverview;
        boolean traffic = checkedId == R.id.tabTraffic;
        boolean speed = checkedId == R.id.tabSpeed;
        boolean network = checkedId == R.id.tabNetwork;
        overviewPanel.setVisibility(overview ? View.VISIBLE : View.GONE);
        speedPanel.setVisibility(speed ? View.VISIBLE : View.GONE);
        trafficPanel.setVisibility(traffic ? View.VISIBLE : View.GONE);
        networkPanel.setVisibility(network ? View.VISIBLE : View.GONE);
        if (traffic) {
            trafficActionButton.setText(trafficRepository.hasAccess()
                    ? "Обновить статистику" : "Разрешить просмотр трафика");
            if (trafficRepository.hasAccess()) loadTraffic();
        } else if (network) {
            refreshNetworkState();
        }
    }

    private void runSpeedTest() {
        if (networkRunning) {
            Toast.makeText(this, "Сначала дождитесь проверки сети", Toast.LENGTH_SHORT).show();
            return;
        }
        speedRunning = true;
        lastDirect = null;
        lastVpn = null;
        runSpeedButton.setText("Остановить");
        speedProgress.setVisibility(View.VISIBLE);
        shareSpeedButton.setVisibility(View.GONE);
        directResult.setText("Проверяю…");
        vpnResult.setText("Ожидает проверки");
        speedStatus.setText("Подготовка…");
        worker.execute(() -> {
            NetworkTestEngine.PathResult direct = testEngine.measure(
                    NetworkTestEngine.Path.DIRECT, this::postSpeedProgress);
            if (!speedRunning || destroyed) return;
            runOnUiThread(() -> directResult.setText(direct.format()));
            NetworkTestEngine.PathResult vpn = testEngine.measure(
                    NetworkTestEngine.Path.VPN, this::postSpeedProgress);
            if (!speedRunning || destroyed) return;
            runOnUiThread(() -> finishSpeedTest(direct, vpn));
        });
    }

    private void stopSpeedTest() {
        speedRunning = false;
        testEngine.cancel();
        runSpeedButton.setText("Начать заново");
        speedProgress.setVisibility(View.GONE);
        speedStatus.setText("Проверка остановлена");
    }

    private void postSpeedProgress(String message) {
        if (!destroyed && speedRunning) runOnUiThread(() -> speedStatus.setText(message));
    }

    private void finishSpeedTest(NetworkTestEngine.PathResult direct,
                                 NetworkTestEngine.PathResult vpn) {
        speedRunning = false;
        lastDirect = direct;
        lastVpn = vpn;
        runSpeedButton.setText("Проверить ещё раз");
        speedProgress.setVisibility(View.GONE);
        directResult.setText(direct.format());
        vpnResult.setText(vpn.format());
        speedStatus.setText(speedHeadline(direct, vpn));
        shareSpeedButton.setVisibility(View.VISIBLE);
        refreshNetworkState();
    }

    static String speedHeadline(NetworkTestEngine.PathResult direct,
                                NetworkTestEngine.PathResult vpn) {
        if (!direct.available) return "Прямое соединение проверить не удалось";
        if (!vpn.available) return "Прямой путь проверен · включите WireRoute для сравнения";
        if (direct.downloadMbps <= 0) return "Оба пути проверены";
        double ratio = vpn.downloadMbps / direct.downloadMbps;
        if (ratio >= 0.85) return "WireRoute почти не снижает скорость";
        if (ratio >= 0.55) return "WireRoute немного снижает скорость";
        return "Через WireRoute заметно медленнее";
    }

    private void shareSpeedResult() {
        if (lastDirect == null || lastVpn == null) return;
        String text = "WireRoute — проверка скорости\n\nНапрямую:\n"
                + lastDirect.format() + "\n\nЧерез WireRoute:\n" + lastVpn.format()
                + "\n\nКороткий замер через Cloudflare; результат меняется из-за нагрузки сети.";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Поделиться результатом"));
    }

    private void loadTraffic() {
        if (!trafficRepository.hasAccess()) {
            trafficActionButton.setText("Разрешить просмотр трафика");
            return;
        }
        trafficProgress.setVisibility(View.VISIBLE);
        trafficActionButton.setEnabled(false);
        trafficResult.setText("Собираю статистику Android…");
        final int requestedDays = trafficDays;
        final int generation = ++trafficLoadGeneration;
        worker.execute(() -> {
            TrafficUsageRepository.UsageReport report = trafficRepository.loadDays(requestedDays);
            if (destroyed) return;
            runOnUiThread(() -> {
                if (destroyed || generation != trafficLoadGeneration) return;
                trafficProgress.setVisibility(View.GONE);
                trafficActionButton.setEnabled(true);
                trafficActionButton.setText("Обновить статистику");
                trafficResult.setText(formatTraffic(report));
            });
        });
    }

    static String formatTraffic(TrafficUsageRepository.UsageReport report) {
        if (!report.available) return report.error;
        StringBuilder text = new StringBuilder();
        text.append("Всего: ").append(TrafficUsageRepository.formatBytes(report.totalBytes()))
                .append("\nWi‑Fi: ").append(TrafficUsageRepository.formatBytes(report.wifiBytes))
                .append(" · мобильная сеть: ")
                .append(TrafficUsageRepository.formatBytes(report.mobileBytes));
        if (!report.apps.isEmpty()) text.append("\n\nБольше всего использовали:\n");
        int index = 1;
        for (TrafficUsageRepository.AppUsage app : report.apps) {
            text.append(index++).append(". ").append(app.label).append(" — ")
                    .append(TrafficUsageRepository.formatBytes(app.totalBytes)).append('\n');
        }
        if (!report.notes.isEmpty()) text.append("\n").append(report.notes);
        return text.toString().trim();
    }

    private void openUsageAccess() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
            Toast.makeText(this, "Разрешите доступ для WireRoute", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void runNetworkDiagnostics() {
        if (networkRunning) return;
        if (speedRunning) {
            Toast.makeText(this, "Сначала дождитесь проверки скорости", Toast.LENGTH_LONG).show();
            return;
        }
        networkRunning = true;
        lastNetworkReport = null;
        refreshNetworkButton.setEnabled(false);
        refreshNetworkButton.setText("Проверяю…");
        networkProgress.setVisibility(View.VISIBLE);
        shareNetworkButton.setVisibility(View.GONE);
        networkResult.setText("Однократная проверка TCP по числовым адресам и DNS. Сетевые запросы ограничены 8 секундами…");
        diagnosticWorker.execute(() -> {
            try {
                TunnelProbe.Result result = tunnelProbe.run();
                result.logToUsb();
                String metadata = ProfileDiagnostics.collect(this);
                ProfileDiagnostics.logGeneratedReport(metadata);
                String report = result.format() + "\n\nМетаданные сохранённого профиля\n" + metadata
                        + "\n\nНаблюдения соединения\n" + SessionDiagnostics.get(this).getLogText();
                new ReportStore(this).append(
                        ReportStore.Severity.INFO,
                        "Сеть", "Однократная проверка", "TCP по IP и отдельные запросы DNS", report);
                runOnUiThread(() -> finishNetworkDiagnostics(report));
            } catch (Throwable error) {
                // Exception messages may contain configuration or network addresses.
                String message = "Проверка не завершилась. Это не означает, что VPN оборвался.";
                runOnUiThread(() -> finishNetworkDiagnostics(message));
            }
        });
    }

    private void finishNetworkDiagnostics(String report) {
        if (destroyed) return;
        networkRunning = false;
        lastNetworkReport = report;
        refreshNetworkButton.setEnabled(true);
        refreshNetworkButton.setText("Проверить ещё раз");
        networkProgress.setVisibility(View.GONE);
        networkResult.setText(report);
        shareNetworkButton.setVisibility(View.VISIBLE);
        refreshNetworkState();
        networkResult.setText(report);
    }

    private void shareNetworkResult() {
        if (lastNetworkReport == null || lastNetworkReport.trim().isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "WireRoute — проверка сети\n\n" + lastNetworkReport);
        startActivity(Intent.createChooser(share, "Поделиться результатом"));
    }

    private void refreshNetworkState() {
        NetworkTestEngine.NetworkSnapshot snapshot = testEngine.inspect();
        overviewTitle.setText(snapshot.state);
        overviewText.setText(snapshot.transport + (snapshot.privateDns
                ? " · защищённый DNS включён" : " · обычный DNS"));
        if (!networkRunning) networkResult.setText(lastNetworkReport != null ? lastNetworkReport : snapshot.format()
                + "\n\nПодключение WireRoute: " + (snapshot.vpn ? "активно" : "не активно")
                + "\nПроверка доступа Android: " + (snapshot.validated ? "успешно" : "не подтверждена"));
        refreshOverview();
    }

    private void refreshOverview() {
        WireRouteApplication application = (WireRouteApplication) getApplication();
        SecureConfigStore config = application.getConfigStore();
        RoutingPreferences routing = application.getRoutingPreferences();
        TunnelController.Snapshot tunnel = application.getTunnelController().getSnapshot();

        String profile = config.hasConfig() ? config.getDisplayName() : "не загружен";
        overviewProfile.setText("Профиль: " + profile + "\nVPN: " + tunnelState(tunnel.state.name()));

        int selected = routing.getSelectedPackages().size();
        if (routing.getMode() == RoutingMode.ONLY_SELECTED) {
            overviewRouting.setText("Приложения через VPN: " + selected);
        } else {
            overviewRouting.setText("Без VPN: " + selected + " выбранных приложений");
        }
    }

    private void scheduleTransferPoll(long delayMillis) {
        if (!resumed || destroyed) return;
        mainHandler.postDelayed(this::pollTransfer, Math.max(0L, delayMillis));
    }

    private void pollTransfer() {
        if (!resumed || destroyed) return;
        boolean overviewVisible = ((android.widget.RadioButton) findViewById(
                R.id.tabOverview)).isChecked();
        if (!overviewVisible || transferRequestInFlight) {
            scheduleTransferPoll(2_000L);
            return;
        }
        transferRequestInFlight = true;
        transferWorker.execute(() -> {
            TunnelController.TransferSnapshot transfer;
            try {
                transfer = ((WireRouteApplication) getApplication()).getTunnelController().getTransferSnapshot();
            } catch (Throwable ignored) {
                transfer = null;
            }
            TunnelController.TransferSnapshot result = transfer;
            runOnUiThread(() -> {
                transferRequestInFlight = false;
                if (!resumed || destroyed) return;
                if (result != null && result.available) {
                    overviewTransfer.setText("WireGuard сейчас: ↓ "
                            + TrafficUsageRepository.formatBytes(result.rxBytes)
                            + " · ↑ " + TrafficUsageRepository.formatBytes(result.txBytes)
                            + (result.stale ? " · данные обновляются" : ""));
                    SessionDiagnostics.Report observation = SessionDiagnostics.get(this).getReport();
                    overviewTitle.setText(observation.summary);
                    overviewText.setText(observation.details);
                } else {
                    overviewTransfer.setText("Трафик WireGuard появится после подключения");
                }
                scheduleTransferPoll(2_000L);
            });
        });
    }

    private static String tunnelState(String state) {
        if ("UP".equals(state)) return "подключён";
        if ("CONNECTING".equals(state)) return "подключается";
        if ("DISCONNECTING".equals(state)) return "отключается";
        if ("ERROR".equals(state)) return "ошибка";
        return "выключен";
    }

    private void applyInsets() {
        if (Build.VERSION.SDK_INT < 23) return;
        View root = findViewById(R.id.diagnosticsRoot);
        final int start = root.getPaddingStart();
        final int top = root.getPaddingTop();
        final int end = root.getPaddingEnd();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                view.setPaddingRelative(start, top + bars.top, end, bottom + bars.bottom);
            } else {
                view.setPaddingRelative(start, top + insets.getSystemWindowInsetTop(), end,
                        bottom + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
}
