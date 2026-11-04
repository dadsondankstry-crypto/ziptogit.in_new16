package com.guardiao.dados;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 100;
    private static final int REQ_CSV = 101;
    private static final int BG = 0xFFF2F6FB;
    private static final int NAVY = 0xFF0D2948;
    private static final int PRIMARY = 0xFF166CB5;
    private static final int GREEN = 0xFF0E886F;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<AppInfoRow> installedApps = new ArrayList<>();
    private final ArrayList<AppInfoRow> visibleBlockApps = new ArrayList<>();
    private final HashSet<String> blockedPackages = new HashSet<>();
    private final ArrayList<TrafficRow> trafficRows = new ArrayList<>();
    private ArrayList<String> pendingVpnPackages = new ArrayList<>();
    private int intervalDays = 1;
    private boolean blockTab = false;
    private long lastScanAt = 0;
    private int requestSerial = 0;
    private String statsMessage = "Ative o acesso ao uso e toque em Atualizar.";

    private LinearLayout root, contentActions;
    private TextView subtitle, status, info, bottomTip, liveSpeed;
    private long previousRx = -1, previousTx = -1, previousTime = -1;
    private Button usageTabButton, blockTabButton, blockButton;
    private ListView listView;
    private EditText searchBox;
    private Spinner intervalSpinner;
    private BaseAdapter listAdapter;
    private SharedPreferences preferences;

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            updateLiveSpeed();
            if (blockTab && status != null) {
                String state = BlockVpnService.isActive() ? "🛡️ BLOQUEIO ATIVO" : "Bloqueio desligado";
                status.setText(state + "   •   " + blockedPackages.size() + " app(s) selecionado(s)" +
                    (BlockVpnService.isActive() ? "\nPacotes descartados: " +
                    formatBytes(BlockVpnService.bytesDiscarded()) : ""));
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("guardiao", MODE_PRIVATE);
        blockedPackages.addAll(preferences.getStringSet("block_list", Collections.emptySet()));
        buildScreen();
        loadInstalledApps();
        handler.post(statusTicker);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusTicker);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null && !blockTab && !hasUsagePermission()) {
            status.setText("⚠ Conceda o acesso especial a dados de uso.");
        }
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + 0.5f); }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable b = new GradientDrawable();
        b.setColor(color);
        b.setCornerRadius(dp(radius));
        return b;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String value, boolean emphasis) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(emphasis ? Color.WHITE : NAVY);
        b.setBackground(rounded(emphasis ? PRIMARY : 0xFFE6EEF7, 14));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    private void buildScreen() {
        root = column();
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout top = row();
        top.setPadding(dp(18), dp(18), dp(18), dp(16));
        top.setBackgroundColor(NAVY);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_shield);
        top.addView(logo, lp(58, 58));
        LinearLayout headings = column();
        headings.setPadding(dp(11), 0, 0, 0);
        headings.addView(text("Guardião de Dados", 22, Color.WHITE, true));
        headings.addView(text("Android nativo • privado • sem servidor", 12, 0xFFBEE8E5, false));
        top.addView(headings, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, lp(-1, -2));

        LinearLayout tabs = row();
        tabs.setPadding(dp(12), dp(12), dp(12), dp(4));
        usageTabButton = button("📊  Consumo", !blockTab);
        blockTabButton = button("🛡️  Bloqueio", blockTab);
        LinearLayout.LayoutParams tab1 = new LinearLayout.LayoutParams(0, dp(46), 1);
        tab1.rightMargin = dp(5);
        tabs.addView(usageTabButton, tab1);
        LinearLayout.LayoutParams tab2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        tab2.leftMargin = dp(5);
        tabs.addView(blockTabButton, tab2);
        usageTabButton.setOnClickListener(v -> { blockTab = false; rebuildBody(); });
        blockTabButton.setOnClickListener(v -> { blockTab = true; rebuildBody(); });
        root.addView(tabs);

        contentActions = column();
        contentActions.setPadding(dp(16), dp(6), dp(16), dp(8));
        root.addView(contentActions, lp(-1, -2));

        listView = new ListView(this);
        listView.setDividerHeight(dp(1));
        listView.setBackgroundColor(Color.WHITE);
        listView.setFastScrollEnabled(true);
        listView.setEmptyView(null);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomTip = text("O app não compartilha seus dados. Funciona sem conta ou login.",
            11, 0xFF50627A, false);
        bottomTip.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.addView(bottomTip, lp(-1, -2));
        rebuildBody();
    }

    private void rebuildBody() {
        contentActions.removeAllViews();
        usageTabButton.setBackground(rounded(!blockTab ? PRIMARY : 0xFFE6EEF7, 14));
        usageTabButton.setTextColor(blockTab ? NAVY : Color.WHITE);
        blockTabButton.setBackground(rounded(blockTab ? PRIMARY : 0xFFE6EEF7, 14));
        blockTabButton.setTextColor(blockTab ? Color.WHITE : NAVY);
        subtitle = text(blockTab ? "Escolha quais aplicativos ficarão sem internet" :
            "Descubra quem consumiu seu Wi‑Fi e dados móveis", 17, NAVY, true);
        contentActions.addView(subtitle, lp(-1, -2));
        info = text(blockTab ?
            "VPN local: selecionados ficam sem internet. Outros apps continuam normalmente.\nNão confunde com a liberação de WhatsApp da operadora." :
            "Histórico aproximado do Android (não mostra picos por segundo). Dê acesso ao uso para analisar outros apps.",
            12, 0xFF516780, false);
        info.setPadding(0, dp(5), 0, dp(10));
        contentActions.addView(info, lp(-1, -2));

        if (blockTab) makeBlockPanel(); else makeUsagePanel();
    }

    private void makeUsagePanel() {
        LinearLayout controls = row();
        Button access = button("🔓 Dar acesso", false);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        controls.addView(access, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button update = button("🔄 Atualizar", true);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, dp(46), 1);
        up.leftMargin = dp(8);
        controls.addView(update, up);
        update.setOnClickListener(v -> refreshTraffic());
        contentActions.addView(controls);

        LinearLayout time = row();
        TextView lbl = text("Período: ", 13, NAVY, true);
        time.addView(lbl);
        intervalSpinner = new Spinner(this);
        String[] ranges = {"Últimas 24h", "Últimos 7 dias", "Últimos 30 dias"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, ranges);
        intervalSpinner.setAdapter(adapter);
        intervalSpinner.setSelection(intervalDays == 1 ? 0 : intervalDays == 7 ? 1 : 2);
        time.addView(intervalSpinner, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button export = button("Exportar CSV", false);
        export.setOnClickListener(v -> exportCsv());
        time.addView(export, lp(122, 43));
        contentActions.addView(time, lp(-1, -2));

        liveSpeed = text("Velocidade atual do celular: medindo...", 13, PRIMARY, true);
        liveSpeed.setPadding(0, dp(7), 0, dp(4));
        contentActions.addView(liveSpeed);
        status = text(statsMessage, 13, GREEN, false);
        status.setPadding(0, dp(2), 0, dp(8));
        contentActions.addView(status);
        listAdapter = new BaseAdapter() {
            @Override public int getCount() { return trafficRows.size(); }
            @Override public Object getItem(int p) { return trafficRows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View recycled, ViewGroup parent) {
                TrafficRow item = trafficRows.get(p);
                LinearLayout r = column();
                r.setPadding(dp(18), dp(11), dp(12), dp(11));
                TextView title = text(item.title, 15, NAVY, true);
                r.addView(title);
                String data = "Wi‑Fi " + formatBytes(item.wifiBytes) + "   •   Móvel " +
                    formatBytes(item.mobileBytes) + "   •   Total " + formatBytes(item.total());
                r.addView(text(data, 12, 0xFF50627A, false));
                r.addView(text(item.packageHint, 10, 0xFF7C8CA0, false));
                return r;
            }
        };
        listView.setAdapter(listAdapter);
        bottomTip.setText("Relatório por aplicativo depende do Android e pode aparecer com atraso. Refrescando não gasta internet.");
    }

    private void makeBlockPanel() {
        liveSpeed = null;
        status = text("", 13, GREEN, true);
        contentActions.addView(status);
        LinearLayout actions = row();
        blockButton = button(BlockVpnService.isActive() ? "■ Desligar bloqueio" :
            "▶ Ativar bloqueio", true);
        actions.addView(blockButton, new LinearLayout.LayoutParams(0, dp(47), 1));
        blockButton.setOnClickListener(v -> {
            if (BlockVpnService.isActive()) stopVpn(); else askToActivateVpn();
        });
        Button clear = button("Limpar seleção", false);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, dp(47), 1);
        c.leftMargin = dp(8);
        actions.addView(clear, c);
        clear.setOnClickListener(v -> {
            blockedPackages.clear();
            saveBlockedList();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
            if (BlockVpnService.isActive()) {
                toast("Seleção limpa. Desative o bloqueio e reative para aplicar.");
            }
        });
        contentActions.addView(actions);
        Button quietMode = button("Selecionar apps comuns (exceto ChatGPT)", false);
        quietMode.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Selecionar muitos aplicativos?")
            .setMessage("Isto marca TODOS os apps instalados pelo usuário, exceto o ChatGPT e o próprio Guardião. " +
                "É uma seleção, não ativa o bloqueio ainda. Serviços do sistema continuarão com internet. " +
                "Mensageiros, navegador e outros apps marcados poderão parar de funcionar depois de ativar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Selecionar", (d, w) -> {
                for (AppInfoRow app : installedApps) {
                    if (!app.pkg.equals("com.openai.chatgpt") &&
                        !app.label.trim().equalsIgnoreCase("ChatGPT")) {
                        blockedPackages.add(app.pkg);
                    }
                }
                saveBlockedList();
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                toast("Seleção salva. Confira antes de ativar.");
            }).show());
        LinearLayout.LayoutParams quietLp = lp(-1, 42);
        quietLp.topMargin = dp(7);
        contentActions.addView(quietMode, quietLp);

        TextView warning = text("⚠ Selecione apps comuns. Apps de sistema ficam fora do bloqueio por segurança."
            + " Selecione com cuidado: você poderá perder chamadas ou mensagens nesses apps.",
            12, 0xFF9B5418, false);
        warning.setPadding(0, dp(7), 0, dp(7));
        contentActions.addView(warning);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setTextSize(15);
        searchBox.setHint("Buscar aplicativo para bloquear...");
        searchBox.setBackground(rounded(Color.WHITE, 12));
        searchBox.setPadding(dp(13), dp(10), dp(13), dp(10));
        contentActions.addView(searchBox, lp(-1, 48));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateBlockResults(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listAdapter = new BaseAdapter() {
            @Override public int getCount() { return visibleBlockApps.size(); }
            @Override public Object getItem(int p) { return visibleBlockApps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View recycled, ViewGroup parent) {
                AppInfoRow item = visibleBlockApps.get(p);
                LinearLayout r = row();
                r.setPadding(dp(14), dp(7), dp(12), dp(7));
                ImageView icon = new ImageView(MainActivity.this);
                try { icon.setImageDrawable(getPackageManager().getApplicationIcon(item.pkg)); }
                catch (Exception ignored) { icon.setImageResource(R.drawable.ic_shield); }
                r.addView(icon, lp(35, 35));
                LinearLayout names = column();
                names.setPadding(dp(11), 0, dp(4), 0);
                names.addView(text(item.label, 14, NAVY, true));
                names.addView(text(item.pkg, 10, 0xFF708198, false));
                r.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox check = new CheckBox(MainActivity.this);
                check.setChecked(blockedPackages.contains(item.pkg));
                check.setOnCheckedChangeListener((v, selected) -> {
                    if (selected) blockedPackages.add(item.pkg);
                    else blockedPackages.remove(item.pkg);
                    saveBlockedList();
                });
                r.addView(check, lp(45, 50));
                r.setOnClickListener(v -> check.setChecked(!check.isChecked()));
                return r;
            }
        };
        listView.setAdapter(listAdapter);
        updateBlockResults("");
        bottomTip.setText("Uma VPN por vez. O bloqueio continua ao sair da tela; pare pela notificação ou pelo botão.");
        status.setText(BlockVpnService.isActive() ? "🛡️ BLOQUEIO ATIVO" : "Bloqueio desligado");
    }

    private void loadInstalledApps() {
        executor.execute(() -> {
            ArrayList<AppInfoRow> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            try {
                for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                    if (getPackageName().equals(a.packageName)) continue;
                    boolean system = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (a.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (system) continue; // não bloquear apps críticos do sistema nesta versão
                    String label = a.loadLabel(pm).toString();
                    apps.add(new AppInfoRow(label, a.packageName));
                }
                apps.sort(Comparator.comparing(v -> v.label.toLowerCase(Locale.ROOT)));
            } catch (Exception e) {
                android.util.Log.e("Guardiao", "Falha ao listar apps", e);
            }
            runOnUiThread(() -> {
                installedApps.clear();
                installedApps.addAll(apps);
                if (blockTab) updateBlockResults(searchBox == null ? "" :
                    searchBox.getText().toString());
            });
        });
    }

    private void updateBlockResults(String query) {
        visibleBlockApps.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (AppInfoRow app : installedApps) {
            if (q.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(q) ||
                app.pkg.toLowerCase(Locale.ROOT).contains(q)) visibleBlockApps.add(app);
        }
        if (blockTab && listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private boolean hasUsagePermission() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int result = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), getPackageName());
        return result == AppOpsManager.MODE_ALLOWED;
    }

    private void refreshTraffic() {
        if (!hasUsagePermission()) {
            statsMessage = "⚠ Toque em 'Dar acesso' e permita acesso aos dados de uso para Guardião de Dados.";
            status.setText(statsMessage);
            return;
        }
        int selection = intervalSpinner.getSelectedItemPosition();
        intervalDays = selection == 1 ? 7 : selection == 2 ? 30 : 1;
        status.setText("Analisando estatísticas locais do Android...");
        int id = ++requestSerial;
        executor.execute(() -> {
            try {
                long end = System.currentTimeMillis();
                long start = end - intervalDays * 24L * 60L * 60L * 1000L;
                NetworkStatsManager mgr = (NetworkStatsManager)
                    getSystemService(Context.NETWORK_STATS_SERVICE);
                Map<Integer, TrafficRow> byUid = new HashMap<>();
                StringBuilder errors = new StringBuilder();
                collectUsage(mgr, ConnectivityManager.TYPE_WIFI, start, end, byUid, false, errors);
                collectUsage(mgr, ConnectivityManager.TYPE_MOBILE, start, end, byUid, true, errors);
                ArrayList<TrafficRow> result = new ArrayList<>(byUid.values());
                result.removeIf(r -> r.total() <= 0);
                result.sort((a, b) -> Long.compare(b.total(), a.total()));
                long allBytes = 0;
                for (TrafficRow r : result) allBytes += r.total();
                String msg = "Últimos " + (intervalDays == 1 ? "24 horas" : intervalDays + " dias") +
                    " • " + result.size() + " grupos com tráfego • " + formatBytes(allBytes) +
                    " identificados" + (errors.length() > 0 ? "\n⚠ " + errors : "");
                runOnUiThread(() -> {
                    if (id != requestSerial) return;
                    trafficRows.clear();
                    trafficRows.addAll(result);
                    lastScanAt = System.currentTimeMillis();
                    statsMessage = msg;
                    if (!blockTab) {
                        status.setText(statsMessage);
                        listAdapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!blockTab) status.setText("Falha ao consultar uso: " + e.getClass().getSimpleName()
                        + " • Verifique a permissão e tente novamente.");
                });
            }
        });
    }

    private void collectUsage(NetworkStatsManager mgr, int type, long start, long end,
                              Map<Integer, TrafficRow> result, boolean mobile, StringBuilder errors) {
        NetworkStats stats = null;
        try {
            stats = mgr.querySummary(type, null, start, end);
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                int uid = bucket.getUid();
                if (uid < 0) continue;
                long bytes = Math.max(0, bucket.getRxBytes()) + Math.max(0, bucket.getTxBytes());
                if (bytes <= 0) continue;
                TrafficRow r = result.get(uid);
                if (r == null) {
                    r = resolveUid(uid);
                    result.put(uid, r);
                }
                if (mobile) r.mobileBytes += bytes; else r.wifiBytes += bytes;
            }
        } catch (Exception e) {
            if (errors.length() > 0) errors.append("; ");
            errors.append(mobile ? "Móvel indisponível" : "Wi‑Fi indisponível");
        } finally {
            if (stats != null) stats.close();
        }
    }

    private TrafficRow resolveUid(int uid) {
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return new TrafficRow("Serviço ou UID " + uid, "Tráfego do sistema / desconhecido", uid);
        }
        try {
            ApplicationInfo a = pm.getApplicationInfo(packages[0], 0);
            String title = a.loadLabel(pm).toString();
            String details = packages[0];
            if (packages.length > 1) details += " (+ apps com mesmo UID)";
            return new TrafficRow(title, details, uid);
        } catch (Exception e) {
            return new TrafficRow(packages[0], "UID " + uid, uid);
        }
    }

    private void saveBlockedList() {
        preferences.edit().putStringSet("block_list", new HashSet<>(blockedPackages)).apply();
    }

    private void askToActivateVpn() {
        pendingVpnPackages.clear();
        for (AppInfoRow app : installedApps) {
            if (blockedPackages.contains(app.pkg)) pendingVpnPackages.add(app.pkg);
        }
        if (pendingVpnPackages.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Nenhum aplicativo selecionado")
                .setMessage("Marque os aplicativos que deseja deixar sem acesso à internet.")
                .setPositiveButton("Entendi", null).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Ativar bloqueio local?")
            .setMessage("Os " + pendingVpnPackages.size() + " aplicativos marcados serão impedidos " +
                "de usar a internet. Os demais continuarão normalmente, sujeito às regras " +
                "da VPN e do Android. Pode afetar mensagens e notificações dos apps escolhidos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ativar", (d, which) -> requestVpnPermission()).show();
    }

    private void requestVpnPermission() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) startActivityForResult(prepare, REQ_VPN);
            else startVpnService();
        } catch (Exception e) {
            showError("Permissão de VPN não disponível: " + e.getMessage());
        }
    }

    private void startVpnService() {
        if (pendingVpnPackages.isEmpty()) return;
        Intent vpn = new Intent(this, BlockVpnService.class);
        vpn.setAction(BlockVpnService.ACTION_START);
        vpn.putStringArrayListExtra(BlockVpnService.EXTRA_PACKAGES, pendingVpnPackages);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn);
            else startService(vpn);
            handler.postDelayed(() -> {
                if (blockButton != null && blockTab) blockButton.setText("■ Desligar bloqueio");
            }, 800);
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            }
        } catch (Exception e) {
            showError("Não foi possível iniciar a VPN: " + e.getMessage());
        }
    }

    private void stopVpn() {
        Intent stop = new Intent(this, BlockVpnService.class);
        stop.setAction(BlockVpnService.ACTION_STOP);
        try { startService(stop); }
        catch (Exception e) { showError("Falha ao parar VPN: " + e.getMessage()); }
        if (blockButton != null) blockButton.setText("▶ Ativar bloqueio");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpnService();
            else toast("Autorização VPN não concedida. Nenhum app foi bloqueado.");
        } else if (requestCode == REQ_CSV && resultCode == RESULT_OK &&
            data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("Destino indisponível");
                StringBuilder csv = new StringBuilder("App,Pacote,UID,WiFi_Bytes,Movel_Bytes,Total_Bytes\n");
                for (TrafficRow row : trafficRows) {
                    csv.append(csvText(row.title)).append(',')
                        .append(csvText(row.packageHint)).append(',')
                        .append(row.uid).append(',').append(row.wifiBytes).append(',')
                        .append(row.mobileBytes).append(',').append(row.total()).append('\n');
                }
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                toast("Relatório salvo no celular.");
            } catch (Exception e) { showError("Erro ao salvar CSV: " + e.getMessage()); }
        }
    }

    private String csvText(String s) {
        return "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private void exportCsv() {
        if (trafficRows.isEmpty() || lastScanAt == 0) {
            toast("Faça uma análise antes de exportar.");
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("text/csv");
        create.putExtra(Intent.EXTRA_TITLE, "guardiao_relatorio.csv");
        startActivityForResult(create, REQ_CSV);
    }

    private String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.getDefault(), unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private void updateLiveSpeed() {
        if (blockTab || liveSpeed == null) return;
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = android.os.SystemClock.elapsedRealtime();
        if (rx < 0 || tx < 0) {
            liveSpeed.setText("Velocidade em tempo real indisponível neste Android.");
        } else if (previousTime > 0 && now > previousTime && rx >= previousRx && tx >= previousTx) {
            double seconds = (now - previousTime) / 1000.0;
            long down = Math.round((rx - previousRx) / seconds);
            long up = Math.round((tx - previousTx) / seconds);
            liveSpeed.setText("⬇ " + formatBytes(down) + "/s     ⬆ " + formatBytes(up)
                + "/s  •  Celular inteiro");
        }
        previousRx = rx;
        previousTx = tx;
        previousTime = now;
    }

    private void toast(String msg) { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show(); }
    private void showError(String msg) {
        new AlertDialog.Builder(this).setTitle("Aviso").setMessage(msg)
            .setPositiveButton("Entendi", null).show();
    }

    private static class AppInfoRow {
        final String label, pkg;
        AppInfoRow(String name, String packageName) { label = name; pkg = packageName; }
    }
    private static class TrafficRow {
        final String title, packageHint;
        final int uid;
        long wifiBytes, mobileBytes;
        TrafficRow(String title, String packageHint, int uid) {
            this.title = title; this.packageHint = packageHint; this.uid = uid;
        }
        long total() { return wifiBytes + mobileBytes; }
    }
}
