package com.soum91.embertv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CompanionServer {
    static final int PORT = 45787;
    final String code;
    private final Context context;
    private final InstallEngine installer;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final SharedPreferences preferences;
    private final String token;
    private volatile boolean paired;
    private volatile boolean listening = false;
    private volatile boolean running = true;
    private volatile String lastError = "";
    private volatile ServerSocket socket;
    private int invalidPairAttempts = 0;
    private long pairBlockedUntil = 0;

    CompanionServer(Context context, InstallEngine installer) {
        this.context = context.getApplicationContext();
        this.installer = installer;
        this.preferences = this.context.getSharedPreferences("embertv_pairing", Context.MODE_PRIVATE);
        String savedCode = preferences.getString("code", "");
        if (!savedCode.matches("[0-9]{6}")) {
            savedCode = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
            preferences.edit().putString("code", savedCode).apply();
        }
        code = savedCode;
        String savedToken = preferences.getString("token", "");
        if (savedToken.isEmpty()) {
            savedToken = UUID.randomUUID().toString() + UUID.randomUUID();
            preferences.edit().putString("token", savedToken).apply();
        }
        token = savedToken;
        paired = preferences.getBoolean("paired", false);
    }
    void start() {
        workers.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT), 32);
                socket = server;
                listening = true;
                lastError = "";
                installer.status("Background receiver listening on port " + PORT);
                server.setSoTimeout(2000);
                while (running) {
                    try { Socket client = server.accept(); workers.execute(() -> handle(client)); }
                    catch (java.net.SocketTimeoutException ignored) { }
                }
            } catch (Exception e) {
                if (running) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    installer.status("Companion listener unavailable: " + lastError);
                }
            } finally {
                listening = false;
                socket = null;
            }
        });
    }
    void stop() { running = false; try { if (socket != null) socket.close(); } catch (Exception ignored) { } workers.shutdownNow(); }
    boolean paired() { return paired; }
    boolean listening() { return listening; }
    String lastError() { return lastError; }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(120000);
            InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream();
            String first = line(in);
            String[] parts = first.split(" ");
            if (parts.length < 2) { response(out, 400, "Bad request"); return; }
            String path = parts[1], method = parts[0];
            long length = 0; String auth = "";
            for (int i = 0; i < 48; i++) {
                String header = line(in); if (header.isEmpty()) break;
                int split = header.indexOf(':'); if (split < 0) continue;
                String key = header.substring(0, split).trim().toLowerCase(Locale.ROOT);
                String val = header.substring(split + 1).trim();
                if (key.equals("content-length")) length = Long.parseLong(val);
                if (key.equals("x-ember-token")) auth = val;
            }
            if (length < 0 || length > 1_000_000_000L) { response(out, 413, "Payload too large"); return; }
            if ("GET".equals(method) && "/status".equals(path)) {
                response(out, 200, new JSONObject().put("name", "EmberTV").put("ready", listening)
                    .put("paired", paired).put("port", PORT).toString());
                return;
            }
            if ("POST".equals(method) && "/pair".equals(path)) {
                JSONObject request = json(in, length);
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    if (now < pairBlockedUntil) { response(out, 429, "Too many attempts; wait one minute"); return; }
                    if (!code.equals(request.optString("code"))) {
                        invalidPairAttempts++;
                        if (invalidPairAttempts >= 5) {
                            pairBlockedUntil = now + 60_000;
                            invalidPairAttempts = 0;
                        }
                        response(out, 403, "Invalid pairing code");
                        return;
                    }
                    invalidPairAttempts = 0;
                    paired = true;
                    preferences.edit().putBoolean("paired", true).apply();
                }
                response(out, 200, new JSONObject().put("token", token).toString());
                installer.status("iPhone companion paired");
                return;
            }
            if (!paired || !token.equals(auth)) { response(out, 401, "Pair first"); return; }
            if (("POST".equals(method) && ("/install".equals(path) || "/upload".equals(path)))
                && !context.getPackageManager().canRequestPackageInstalls()) {
                response(out, 403, "Allow EmberTV to install unknown apps in TV settings first");
                return;
            }
            if ("POST".equals(method) && "/install".equals(path)) {
                JSONObject request = json(in, length);
                String url = request.optString("url", "");
                if (!url.startsWith("https://")) { response(out, 400, "HTTPS APK URL required"); return; }
                installer.fromUrl(url, request.optString("packageName", ""), request.optString("sha256", ""));
                response(out, 202, "Install requested; approve on TV");
                return;
            }
            if ("POST".equals(method) && "/upload".equals(path)) {
                if (length < 4 || length > 1_000_000_000L) { response(out, 413, "Invalid APK size"); return; }
                File file = new File(context.getCacheDir(), "iphone-" + UUID.randomUUID() + ".apk");
                try (FileOutputStream fileOut = new FileOutputStream(file)) {
                    byte[] b = new byte[65536]; long remaining = length;
                    while (remaining > 0) {
                        int n = in.read(b, 0, (int)Math.min(b.length, remaining));
                        if (n < 0) throw new IllegalArgumentException("Incomplete upload");
                        fileOut.write(b, 0, n); remaining -= n;
                    }
                }
                installer.fromFile(file, "", "");
                response(out, 202, "APK received; approve on TV");
                return;
            }
            response(out, 404, "Not found");
        } catch (Exception e) {
            if (running) installer.status("iPhone request interrupted: " +
                (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
    private static JSONObject json(InputStream in, long length) throws Exception {
        if (length > 32768) throw new IllegalArgumentException("JSON request too large");
        byte[] b = new byte[(int)length]; int read = 0;
        while (read < b.length) { int n = in.read(b, read, b.length - read); if (n < 0) throw new IllegalArgumentException("Truncated JSON"); read += n; }
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }
    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int ch;
        while ((ch = in.read()) != -1 && ch != '\n') {
            if (b.size() >= 8192) throw new IllegalArgumentException("HTTP header too large");
            if (ch != '\r') b.write(ch);
        }
        return b.toString(StandardCharsets.US_ASCII.name());
    }
    private static void response(OutputStream out, int status, String message) throws Exception {
        boolean json = message.startsWith("{");
        String data = json ? message : new JSONObject().put("message", message).toString();
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + (status < 300 ? "OK" : "Error") + "\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: none\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    static String localIp() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "Check TV network settings";
    }
}
