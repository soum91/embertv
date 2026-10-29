package com.soum91.embertv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class InstallEngine {
    interface Progress { void status(String message); }
    private final Activity activity;
    private final Progress progress;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    InstallEngine(Activity activity, Progress progress) { this.activity = activity; this.progress = progress; }
    void status(String s) { activity.runOnUiThread(() -> progress.status(s)); }

    void fromUrl(String url, String expectedPackage, String sha256) {
        if (!checkPermission()) return;
        executor.execute(() -> {
            File file = new File(activity.getCacheDir(), "download-" + UUID.randomUUID() + ".apk");
            try {
                if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS download required");
                status("Downloading APK...");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "EmberTV/0.1");
                try {
                    if (c.getResponseCode() != 200 || !"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new IllegalArgumentException("Download HTTP " + c.getResponseCode());
                    long length = c.getContentLengthLong();
                    if (length > 1_000_000_000L) throw new IllegalArgumentException("APK exceeds 1 GB");
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[65536]; int read; long total = 0; int last = -1;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > 1_000_000_000L) throw new IllegalArgumentException("APK exceeds 1 GB");
                            out.write(buffer, 0, read);
                            if (length > 0) {
                                int percent = (int)(100L * total / length);
                                if (percent / 5 != last / 5) { last = percent; status("Downloading " + percent + "%"); }
                            }
                        }
                    }
                } finally { c.disconnect(); }
                install(file, expectedPackage, sha256);
            } catch (Exception e) { status("Install stopped: " + e.getMessage()); }
            finally { /* APK may be needed until session write completes; install() is synchronous. */ file.delete(); }
        });
    }
    void fromFile(File file, String expectedPackage, String sha256) {
        if (!checkPermission()) return;
        executor.execute(() -> { try { install(file, expectedPackage, sha256); } catch (Exception e) { status("Install stopped: " + e.getMessage()); } finally { file.delete(); } });
    }
    private boolean checkPermission() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            status("On TV, allow EmberTV to install unknown apps, then retry.");
            activity.runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            });
            return false;
        }
        return true;
    }
    private void install(File file, String expectedPackage, String expectedHash) throws Exception {
        if (expectedHash != null && !expectedHash.isEmpty() && !sha256(file).equalsIgnoreCase(expectedHash)) throw new IllegalArgumentException("SHA-256 mismatch; download discarded");
        PackageManager pm = activity.getPackageManager();
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || candidate.packageName == null) throw new IllegalArgumentException("Invalid APK manifest");
        if (expectedPackage != null && !expectedPackage.isEmpty() && !expectedPackage.equals(candidate.packageName)) throw new IllegalArgumentException("Catalog package ID differs from APK");
        if (candidate.signingInfo == null || signers(candidate.signingInfo).isEmpty()) throw new IllegalArgumentException("Cannot inspect APK signing certificate");
        PackageInfo installed = null;
        try { installed = pm.getPackageInfo(candidate.packageName, PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException ignored) { }
        if (installed != null) {
            if (candidate.getLongVersionCode() < installed.getLongVersionCode()) throw new IllegalArgumentException("Downgrade blocked: installed version " + installed.versionName);
            if (installed.signingInfo == null || !compatible(installed.signingInfo, candidate.signingInfo)) {
                throw new IllegalArgumentException("SIGNATURE MISMATCH for " + candidate.packageName + ". Existing app and data were preserved. Obtain an APK signed by the original key.");
            }
        }
        status(installed == null ? "Ready to install " + candidate.packageName : "Verified update for " + candidate.packageName);
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(candidate.packageName);
        params.setSize(file.length());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             FileInputStream in = new FileInputStream(file);
             OutputStream out = session.openWrite("base.apk", 0, file.length())) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            session.fsync(out);
            Intent result = new Intent(activity, InstallReceiver.class);
            result.setAction("com.soum91.embertv.INSTALL_RESULT");
            PendingIntent pi = PendingIntent.getBroadcast(activity, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
            status("Installation submitted. Confirm on the TV when prompted.");
        } catch (Exception e) { installer.abandonSession(sessionId); throw e; }
    }
    static boolean compatible(SigningInfo oldInfo, SigningInfo newInfo) throws Exception {
        Set<String> oldCurrent = signers(oldInfo);
        Set<String> newCurrent = signers(newInfo);
        if (!oldCurrent.isEmpty() && oldCurrent.equals(newCurrent)) return true;
        // A new APK may present Android's proof-of-rotation history. The OS
        // validates the actual cryptographic lineage at session commit.
        if (!newInfo.hasMultipleSigners()) {
            Signature[] history = newInfo.getSigningCertificateHistory();
            Set<String> prior = new HashSet<>();
            if (history != null) for (Signature s : history)
                prior.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
            return !oldCurrent.isEmpty() && prior.containsAll(oldCurrent);
        }
        return false;
    }
    private static Set<String> signers(SigningInfo info) throws Exception {
        Set<String> hashes = new HashSet<>();
        Signature[] signatures = info.getApkContentsSigners();
        if (signatures != null) for (Signature s : signatures) hashes.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        return hashes;
    }
    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) md.update(b, 0, n); }
        return hex(md.digest());
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 255)); return s.toString(); }
}
