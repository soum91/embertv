package com.soum91.embertv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class InstallEngine {
    private static final String GITHUB_KEY_ALIAS = "EmberTV-private-GitHub-token";
    private static final String GITHUB_PREFS = "embertv_github_access";
    interface Progress { void status(String message); }
    private final Context context;
    private final Progress progress;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    InstallEngine(Context context, Progress progress) { this.context = context.getApplicationContext(); this.progress = progress; }
    void status(String s) { mainHandler.post(() -> progress.status(s)); }
    void shutdown() { executor.shutdown(); }

    void fromUrl(String url, String expectedPackage, String sha256) {
        fromUrl(url, expectedPackage, sha256, false);
    }
    void fromUrl(String url, String expectedPackage, String sha256, boolean privateGitHubAsset) {
        if (!checkPermission()) return;
        executor.execute(() -> {
            File file = new File(context.getCacheDir(), "download-" + UUID.randomUUID() + ".apk");
            try {
                if (!url.startsWith("https://")) throw new IllegalArgumentException("HTTPS download required");
                status("Downloading APK...");
                HttpURLConnection c = openDownload(url, privateGitHubAsset);
                try {
                    if (c.getResponseCode() != 200 || !"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new IllegalArgumentException("Download HTTP " + c.getResponseCode());
                    if (privateGitHubAsset && c.getContentType() != null && c.getContentType().toLowerCase(Locale.ROOT).contains("json"))
                        throw new IllegalArgumentException("GitHub returned release metadata, not an APK");
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
    private HttpURLConnection openDownload(String url, boolean privateGitHubAsset) throws Exception {
        if (!privateGitHubAsset) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "EmberTV/0.1");
            return c;
        }
        if (!url.matches("https://api\\.github\\.com/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/assets/[1-9][0-9]*"))
            throw new IllegalArgumentException("Untrusted private GitHub release URL");
        String token = githubToken(context);
        if (token.isEmpty()) throw new IllegalArgumentException("Private GitHub access needs a token in Sources");
        HttpURLConnection api = (HttpURLConnection) new URL(url).openConnection();
        api.setConnectTimeout(15000); api.setReadTimeout(30000);
        api.setInstanceFollowRedirects(false);
        api.setRequestProperty("User-Agent", "EmberTV/0.1");
        api.setRequestProperty("Accept", "application/octet-stream");
        api.setRequestProperty("Authorization", "Bearer " + token);
        int status = api.getResponseCode();
        if (status == 401 || status == 403 || status == 404) {
            api.disconnect();
            throw new IllegalArgumentException("Private GitHub asset inaccessible (" + status + "); check token and repository access");
        }
        if (status == 200) return api;
        if (status != 302 && status != 301 && status != 307 && status != 308) {
            api.disconnect();
            throw new IllegalArgumentException("GitHub download HTTP " + status);
        }
        // Release assets redirect to a short-lived storage URL. Never forward the token to that host.
        String location = api.getHeaderField("Location");
        api.disconnect();
        if (location == null) throw new IllegalArgumentException("GitHub release redirect missing");
        URL destination = new URL(new URL(url), location);
        if (!"https".equalsIgnoreCase(destination.getProtocol())) throw new IllegalArgumentException("GitHub redirected to non-HTTPS");
        HttpURLConnection download = (HttpURLConnection) destination.openConnection();
        download.setConnectTimeout(15000); download.setReadTimeout(30000);
        download.setRequestProperty("User-Agent", "EmberTV/0.1");
        return download;
    }
    static boolean hasGithubToken(Context context) {
        return context.getSharedPreferences(GITHUB_PREFS, Context.MODE_PRIVATE).contains("ciphertext");
    }
    static void clearGithubToken(Context context) {
        context.getSharedPreferences(GITHUB_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
    static void saveGithubToken(Context context, String value) throws Exception {
        String token = value.trim();
        if (token.isEmpty() || token.contains("\n") || token.contains("\r")) throw new IllegalArgumentException("Invalid GitHub token");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, githubKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(GITHUB_PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }
    private static String githubToken(Context context) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences(GITHUB_PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString("ciphertext", "");
        if (encrypted.isEmpty()) return "";
        try {
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, githubKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Stored GitHub token cannot be opened; clear and enter it again");
        }
    }
    private static SecretKey githubKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(GITHUB_KEY_ALIAS)) return (SecretKey) store.getKey(GITHUB_KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(GITHUB_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    void fromFile(File file, String expectedPackage, String sha256) {
        if (!checkPermission()) { file.delete(); return; }
        executor.execute(() -> { try { install(file, expectedPackage, sha256); } catch (Exception e) { status("Install stopped: " + e.getMessage()); } finally { file.delete(); } });
    }
    private boolean checkPermission() {
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            status("Allow EmberTV to install unknown apps in TV Settings, then retry.");
            return false;
        }
        return true;
    }
    private void install(File file, String expectedPackage, String expectedHash) throws Exception {
        if (expectedHash != null && !expectedHash.isEmpty() && !sha256(file).equalsIgnoreCase(expectedHash)) throw new IllegalArgumentException("SHA-256 mismatch; download discarded");
        PackageManager pm = context.getPackageManager();
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
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            // PackageInstaller rejects commit() with "Files still open" if any
            // write stream remains open, even after fsync(). Finish and close
            // the entire APK write before submitting the session.
            try (FileInputStream in = new FileInputStream(file);
                 OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(context, InstallReceiver.class);
            result.setAction("com.soum91.embertv.INSTALL_RESULT");
            PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
            status("Installation submitted. Confirm on the TV when prompted.");
        } catch (Exception e) {
            try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
            throw e;
        }
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
