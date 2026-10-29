package com.soum91.embertv;

import android.app.Activity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
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
    final String code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    private final Activity activity;
    private final InstallEngine installer;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private volatile boolean paired = false;
    private volatile boolean running = true;
    private ServerSocket socket;

    CompanionServer(Activity activity, InstallEngine installer) { this.activity = activity; this.installer = installer; }
    void start() {
        workers.execute(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) {
                socket = server;
                server.setSoTimeout(2000);
                while (running) {
                    try { Socket client = server.accept(); workers.execute(() -> handle(client)); }
                    catch (java.net.SocketTimeoutException ignored) { }
                }
            } catch (Exception e) { installer.status("Companion server unavailable: " + e.getMessage()); }
        });
    }
    void stop() { running = false; try { if (socket != null) socket.close(); } catch (Exception ignored) { } workers.shutdownNow(); }
    boolean paired() { return paired; }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(45000);
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
            if ("GET".equals(method) && "/status".equals(path)) { response(out, 200, "{\"name\":\"EmberTV\",\"paired\":" + paired + "}"); return; }
            if ("POST".equals(method) && "/pair".equals(path)) {
                JSONObject request = json(in, length);
                if (!code.equals(request.optString("code"))) { response(out, 403, "Invalid pairing code"); return; }
                paired = true;
                response(out, 200, new JSONObject().put("token", token).toString());
                installer.status("iPhone companion paired");
                return;
            }
            if (!paired || !token.equals(auth)) { response(out, 401, "Pair first"); return; }
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
                File file = new File(activity.getCacheDir(), "iphone-" + UUID.randomUUID() + ".apk");
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
        } catch (Exception ignored) { }
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
