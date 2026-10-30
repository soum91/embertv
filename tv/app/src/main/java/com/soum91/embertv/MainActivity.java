package com.soum91.embertv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final int BG = Color.rgb(17, 19, 25), PANEL = Color.rgb(29, 32, 41), WHITE = Color.rgb(247, 247, 249);
    static final int ORANGE = Color.rgb(242, 112, 56), MUTED = Color.rgb(165, 170, 184);
    private static volatile MainActivity current;
    private static volatile String lastMessage = "Ready";
    private volatile boolean visible;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService icons = Executors.newFixedThreadPool(4);
    private final Map<String, Bitmap> iconCache = new ConcurrentHashMap<>();
    private final List<Catalog.App> apps = new ArrayList<>();
    private final List<String> downloadHistory = new ArrayList<>();
    private LinearLayout body, sidebar;
    private TextView feedback, pageTitle;
    private String page = "Discover";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); current = this;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildShell(); show("Discover"); refresh();
        if (!lastMessage.equals("Ready")) setFeedback(lastMessage);
        if (getSharedPreferences("embertv_receiver", MODE_PRIVATE).getBoolean("enabled", true)) startBackgroundReceiver();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4578);
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (page.equals("Pair iPhone")) show("Pair iPhone");
        InstallReceiver.approveWhenVisible(this);
    }
    @Override protected void onPause() { visible = false; super.onPause(); }
    @Override public void onDestroy() { if (current == this) current = null; network.shutdownNow(); icons.shutdownNow(); super.onDestroy(); }
    static boolean isVisible() { MainActivity a = current; return a != null && a.visible; }
    static void report(String message) {
        lastMessage = message;
        MainActivity a = current;
        if (a != null) a.runOnUiThread(() -> { if (!a.isFinishing()) a.setFeedback(message); });
    }
    private void startBackgroundReceiver() {
        getSharedPreferences("embertv_receiver", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        try { startForegroundService(new Intent(this, ReceiverService.class)); }
        catch (Exception e) { setFeedback("Cannot start background receiver: " + e.getMessage()); }
    }
    private void installFromCatalog(Catalog.App app) {
        ReceiverService service = ReceiverService.active;
        if (service == null) {
            startBackgroundReceiver();
            setFeedback("Receiver is starting. Please retry in a moment.");
            return;
        }
        service.installer.fromUrl(app.latest.url, app.packageName, app.latest.sha256);
    }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private StateListDrawable focusBackground() {
        StateListDrawable s = new StateListDrawable(); s.addState(new int[]{android.R.attr.state_focused}, rounded(ORANGE, 15));
        s.addState(new int[]{}, rounded(PANEL, 15)); return s;
    }
    private TextView label(String txt, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(txt); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private Button button(String text, Runnable click) {
        Button b = new Button(this); b.setText(text); b.setTextSize(17); b.setTextColor(WHITE);
        b.setAllCaps(false); b.setBackground(focusBackground()); b.setPadding(dp(17), dp(11), dp(17), dp(11));
        b.setFocusable(true); b.setOnClickListener(v -> click.run());
        b.setOnFocusChangeListener((v, focused) -> b.setTextColor(focused ? Color.BLACK : WHITE));
        return b;
    }
    private void buildShell() {
        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.HORIZONTAL); main.setBackgroundColor(BG);
        main.setPadding(dp(26), dp(25), dp(26), dp(25));
        setContentView(main);
        sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL); sidebar.setPadding(0, 0, dp(24), 0);
        main.addView(sidebar, new LinearLayout.LayoutParams(dp(235), -1));
        LinearLayout branding = new LinearLayout(this);
        branding.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.embertv_icon);
        brandIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        branding.addView(brandIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView logo = label("EmberTV", 28, ORANGE, true);
        branding.addView(logo, new LinearLayout.LayoutParams(-1, dp(70)));
        sidebar.addView(branding, new LinearLayout.LayoutParams(-1, dp(74)));
        for (String menu : new String[]{"Discover", "Installed", "Downloads", "Sources", "Pair iPhone", "About"}) {
            Button b = button(menu, () -> show(menu));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(60)); p.bottomMargin = dp(9); sidebar.addView(b, p);
        }
        TextView note = label("GitHub APK catalogs", 14, MUTED, false); sidebar.addView(note);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); main.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        pageTitle = label("", 28, WHITE, true); content.addView(pageTitle, new LinearLayout.LayoutParams(-1, dp(65)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(3), dp(10), dp(12), dp(20)); scroll.addView(body);
        feedback = label("Ready", 14, MUTED, false); feedback.setPadding(dp(7), dp(12), 0, 0);
        content.addView(feedback, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private void setFeedback(String msg) {
        feedback.setText(msg);
        if (downloadHistory.isEmpty() || !msg.equals(downloadHistory.get(0))) {
            downloadHistory.add(0, msg);
            if (downloadHistory.size() > 25) downloadHistory.remove(downloadHistory.size() - 1);
        }
        if (page.equals("Downloads")) show("Downloads");
    }
    private void show(String section) {
        page = section; pageTitle.setText(section); body.removeAllViews();
        switch (section) {
            case "Discover": showApps(); break;
            case "Installed": showInstalled(); break;
            case "Downloads": showDownloads(); break;
            case "Sources": showSources(); break;
            case "Pair iPhone": showPair(); break;
            default: showAbout(); break;
        }
    }
    private void paragraph(String text) {
        TextView t = label(text, 18, MUTED, false); t.setPadding(dp(3), dp(8), dp(3), dp(16)); body.addView(t);
    }
    private void action(String text, Runnable handler) {
        Button b = button(text, handler); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(60)); p.bottomMargin = dp(12); body.addView(b, p);
    }
    private void showApps() {
        action("↻  Refresh catalogs", this::refresh);
        if (apps.isEmpty()) { paragraph("No APKs in your sources yet. Add a GitHub catalog in Sources, then refresh."); return; }
        EditText search = new EditText(this);
        search.setSingleLine(true); search.setTextColor(WHITE); search.setHintTextColor(MUTED);
        search.setHint("Search installed apps and GitHub sources..."); search.setTextSize(20);
        search.setPadding(dp(15), dp(11), dp(15), dp(11)); search.setBackground(rounded(PANEL, 12));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(65)); searchParams.bottomMargin = dp(20);
        body.addView(search, searchParams);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(3); body.addView(grid);
        populateGrid(grid, "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) { populateGrid(grid, s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        });
    }
    private void populateGrid(GridLayout grid, String query) {
        grid.removeAllViews();
        PackageManager pm = getPackageManager();
        for (Catalog.App app : apps) {
            if (!app.name.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)) &&
                    !app.packageName.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
            String badge = "GET";
            if (!app.packageName.isEmpty()) try {
                PackageInfo installed = pm.getPackageInfo(app.packageName, 0);
                badge = app.latest.code > 0 && app.latest.code > installed.getLongVersionCode() ? "UPDATE" : "INSTALLED";
            } catch (PackageManager.NameNotFoundException ignored) { }
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true); card.setClickable(true); card.setBackground(focusBackground());
            card.setPadding(dp(15), dp(14), dp(14), dp(12)); card.setOnClickListener(v -> details(app));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(rounded(ORANGE, 14));
            card.addView(image, new LinearLayout.LayoutParams(dp(58), dp(58)));
            if (!app.icon.isEmpty()) loadIcon(app.icon, image);
            TextView name = label(app.name, 18, WHITE, true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(32)); np.topMargin = dp(10); card.addView(name, np);
            TextView version = label(app.latest.name + "  •  " + badge, 14, MUTED, false);
            card.addView(version, new LinearLayout.LayoutParams(-1, dp(23)));
            card.setOnFocusChangeListener((v, focused) -> {
                name.setTextColor(focused ? Color.BLACK : WHITE);
                version.setTextColor(focused ? Color.DKGRAY : MUTED);
                card.setScaleX(focused ? 1.03f : 1f); card.setScaleY(focused ? 1.03f : 1f);
            });
            int screenDp = (int)(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
            int cardDp = Math.max(165, Math.min(240, (screenDp - 345) / 3 - 15));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = dp(cardDp); p.height = dp(166);
            p.setMargins(0, 0, dp(15), dp(16)); grid.addView(card, p);
        }
    }
    private void loadIcon(String url, ImageView view) {
        Bitmap cached = iconCache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        icons.execute(() -> {
            if (!url.startsWith("https://")) return;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200 || conn.getContentLengthLong() > 2_000_000L) return;
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) { iconCache.put(url, bmp); runOnUiThread(() -> view.setImageBitmap(bmp)); }
                }
            } catch (Exception ignored) { } finally { if (conn != null) conn.disconnect(); }
        });
    }
    private void showDownloads() {
        paragraph("Recent download and installation status (this session)");
        for (String event : downloadHistory) paragraph("•  " + event);
    }
    private void details(Catalog.App app) {
        String info = app.summary + "\n\nPackage: " + app.packageName + "\nVersion: " + app.latest.name +
                "\nBuild: " + app.latest.code + "\n\nChangelog:\n" + (app.latest.changelog.isEmpty() ? "Not provided" : app.latest.changelog);
        new AlertDialog.Builder(this).setTitle(app.name).setMessage(info)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download & install", (d, which) -> installFromCatalog(app)).show();
    }
    private void refresh() {
        setFeedback("Refreshing GitHub sources...");
        network.execute(() -> {
            Map<String, Catalog.App> combined = new LinkedHashMap<>(); List<String> errors = new ArrayList<>();
            for (String url : Catalog.sources(this)) {
                try { for (Catalog.App a : Catalog.fetch(url)) {
                    String key = a.packageName.isEmpty() ? a.latest.url : a.packageName;
                    Catalog.App old = combined.get(key);
                    if (old == null || a.latest.code > old.latest.code) combined.put(key, a);
                }} catch (Exception e) { errors.add(url + ": " + e.getMessage()); }
            }
            runOnUiThread(() -> {
                apps.clear(); apps.addAll(combined.values());
                setFeedback(apps.size() + " APKs loaded" + (errors.isEmpty() ? "" : "; " + errors.size() + " feed error(s)"));
                if (page.equals("Discover")) show("Discover");
                if (!errors.isEmpty()) new AlertDialog.Builder(this).setTitle("Source errors").setMessage(String.join("\n", errors)).setPositiveButton("OK", null).show();
            });
        });
    }
    private void showSources() {
        paragraph("Add a public GitHub repository URL or direct HTTPS JSON catalog URL.");
        action("＋  Add source", () -> {
            EditText input = new EditText(this); input.setSingleLine(true); input.setHint("https://github.com/owner/repo");
            new AlertDialog.Builder(this).setTitle("Add GitHub source").setView(input)
                    .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                        try { Catalog.add(this, Catalog.canonical(input.getText().toString())); show("Sources"); refresh(); }
                        catch (Exception e) { setFeedback(e.getMessage()); }
                    }).show();
        });
        for (String source : Catalog.sources(this)) {
            if (source.equals(Catalog.DEFAULT) || source.equals(Catalog.REYNARD) || source.equals(Catalog.AMAZON)) { paragraph("Built in: " + source); continue; }
            action(source + "   ✕", () -> new AlertDialog.Builder(this).setTitle("Remove source?")
                    .setMessage(source).setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (d, w) -> { Catalog.remove(this, source); show("Sources"); refresh(); }).show());
        }
    }
    private void showInstalled() {
        paragraph("Installed user applications. EmberTV will not uninstall apps to resolve signature conflicts.");
        List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
        List<PackageInfo> userApps = new ArrayList<>();
        for (PackageInfo p : packages) if (p.applicationInfo != null && (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) userApps.add(p);
        Collections.sort(userApps, Comparator.comparing(p -> String.valueOf(p.applicationInfo.loadLabel(getPackageManager())).toLowerCase()));
        for (PackageInfo p : userApps) {
            String name = String.valueOf(p.applicationInfo.loadLabel(getPackageManager()));
            action(name + "   •   " + p.versionName, () -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(p.packageName);
                if (launch != null) startActivity(launch); else setFeedback("No launch activity for " + p.packageName);
            });
        }
    }
    private void showPair() {
        paragraph("Open EmberTV Companion on your iPhone. Connect both devices to the same trusted Wi-Fi network.");
        ReceiverService service = ReceiverService.active;
        CompanionServer server = service == null ? null : service.server;
        TextView address = label("TV IP: " + CompanionServer.localIp() + "\nPort: " + CompanionServer.PORT, 27, WHITE, true);
        address.setPadding(0, dp(12), 0, dp(28)); body.addView(address);
        TextView code = label("PAIRING CODE:  " + (server == null ? "Starting receiver..." : server.code), 31, ORANGE, true);
        code.setPadding(0, dp(12), 0, dp(28)); body.addView(code);
        String state = server == null ? "Not running" :
                server.listening() ? "Listening in background" :
                server.lastError().isEmpty() ? "Starting..." : "Unavailable: " + server.lastError();
        paragraph("Receiver: " + state + ". Pairing: " + (server != null && server.paired() ? "Paired" : "Not paired") + ".");
        paragraph("The background receiver remains active when you leave EmberTV. Android may still require approval for each APK installation.");
        action("Start background receiver", () -> { startBackgroundReceiver(); show("Pair iPhone"); });
        action("Allow APK installations", () -> {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        action("Refresh status", () -> show("Pair iPhone"));
        action("Stop background receiver", () -> {
            getSharedPreferences("embertv_receiver", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, ReceiverService.class));
            show("Pair iPhone");
        });
    }
    private void showAbout() {
        paragraph("EmberTV 0.1 • GitHub APK catalog and installer");
        paragraph("Package and signing-certificate checks protect installed app data. All APKs are delivered to Android's official installer.");
        paragraph("APK signature private keys cannot be extracted from installed apps or public GitHub releases. Publishers must keep signing keys consistent.");
        paragraph("iPhone pairing uses local-network HTTP. Only pair on your trusted Wi-Fi. Never forward port " + CompanionServer.PORT + " to the internet.");
    }

    /** Foreground listener survives Activity destruction and keeps APK transfers alive. */
    public static final class ReceiverService extends Service {
        private static final String CHANNEL = "embertv_receiver";
        private static final int NOTIFICATION_ID = 45787;
        private static volatile ReceiverService active;
        private CompanionServer server;
        private InstallEngine installer;

        @Override public void onCreate() {
            super.onCreate();
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "EmberTV background receiver", NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent launch = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.embertv_icon)
                .setContentTitle("EmberTV receiver active")
                .setContentText("Ready to receive APKs from your iPhone")
                .setContentIntent(launch)
                .setOngoing(true)
                .build();
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID, notification);
            installer = new InstallEngine(this, MainActivity::report);
            server = new CompanionServer(this, installer);
            active = this;
            server.start();
        }

        @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public void onDestroy() {
            if (active == this) active = null;
            if (server != null) server.stop();
            if (installer != null) installer.shutdown();
            MainActivity.report("Background receiver stopped");
            super.onDestroy();
        }
    }

    /** Restart the opted-in receiver after TV boot and an EmberTV update. */
    public static final class AutoStartReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
            if (!context.getSharedPreferences("embertv_receiver", Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
            try { context.startForegroundService(new Intent(context, ReceiverService.class)); }
            catch (Exception ignored) { }
        }
    }
}
