package com.soum91.embertv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Catalog {
    static final String DEFAULT = "https://raw.githubusercontent.com/soum91/embertv/main/repo.json";
    static final String REYNARD = "https://github.com/soum91/reynard-browser";
    static final String AMAZON = "https://github.com/soum91/amazonmusictvplus";
    static final String[] NAMES = {"repo.json", "source.json", "apps.json", "app-repo.json", "AltStore.json"};

    static final class Version {
        String name, url, sha256, changelog;
        long code;
        boolean privateGitHubAsset;
    }
    static final class App {
        String name, packageName, summary, icon;
        Version latest;
    }
    interface Result { void complete(List<App> apps, List<String> errors); }

    static List<String> sources(Context c) {
        SharedPreferences p = c.getSharedPreferences("sources", 0);
        String stored = p.getString("urls", "");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(DEFAULT);
        result.add(REYNARD);
        result.add(AMAZON);
        if (!stored.isEmpty()) for (String x : stored.split("\\n")) if (!x.trim().isEmpty()) result.add(x.trim());
        return new ArrayList<>(result);
    }
    static void add(Context c, String source) {
        List<String> all = sources(c);
        if (!all.contains(source)) all.add(source);
        save(c, all);
    }
    static void remove(Context c, String source) {
        List<String> all = sources(c);
        all.remove(source);
        save(c, all);
    }
    private static void save(Context c, List<String> all) {
        all.remove(DEFAULT);
        c.getSharedPreferences("sources", 0).edit().putString("urls", String.join("\n", all)).apply();
    }
    static String canonical(String input) {
        String x = input.trim();
        if (!x.startsWith("https://")) throw new IllegalArgumentException("Only HTTPS sources are supported");
        try {
            URL u = new URL(x);
            if ("github.com".equalsIgnoreCase(u.getHost())) {
                String[] p = u.getPath().split("/");
                if (p.length >= 3) return "https://github.com/" + p[1] + "/" + p[2];
            }
        } catch (Exception ignored) { }
        return x;
    }
    static List<App> fetch(String source) throws Exception {
        URL sourceURL = new URL(source);
        if ("github.com".equalsIgnoreCase(sourceURL.getHost())) return githubReleases(sourceURL);
        String json;
        try { json = get(source); }
        catch (Exception first) {
            if (!source.endsWith("/repo.json")) throw first;
            Exception failure = first;
            json = null;
            for (String name : NAMES) {
                try { json = get(source.substring(0, source.length() - "repo.json".length()) + name); break; }
                catch (Exception e) { failure = e; }
            }
            if (json == null) throw failure;
        }
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("apps");
        if (array == null) throw new IllegalArgumentException("No apps array in JSON catalog");
        List<App> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            App app = new App();
            app.name = obj.optString("name", "Unnamed app");
            app.packageName = obj.optString("packageName", obj.optString("bundleIdentifier", ""));
            app.summary = obj.optString("subtitle", obj.optString("localizedDescription", ""));
            app.icon = obj.optString("iconURL", "");
            JSONArray versions = obj.optJSONArray("versions");
            List<Version> candidates = new ArrayList<>();
            if (versions != null) for (int j = 0; j < versions.length(); j++) {
                JSONObject v = versions.optJSONObject(j);
                if (v != null) { Version parsed = parse(v); if (parsed != null) candidates.add(parsed); }
            }
            if (candidates.isEmpty()) {
                Version single = parse(obj);
                if (single != null) candidates.add(single);
            }
            for (Version v : candidates) if (app.latest == null || v.code > app.latest.code || (v.code == app.latest.code && compare(v.name, app.latest.name) > 0)) app.latest = v;
            if (app.latest != null) result.add(app);
        }
        return result;
    }
    private static List<App> githubReleases(URL github) throws Exception {
        String[] parts = github.getPath().split("/");
        if (parts.length < 3 || !parts[1].matches("[A-Za-z0-9_.-]+") || !parts[2].matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Expected a GitHub owner/repository URL");
        String owner = parts[1], repo = parts[2];
        JSONArray releases = new JSONArray(get("https://api.github.com/repos/" + owner + "/" + repo + "/releases?per_page=50"));
        App app = new App();
        boolean reynard = owner.equalsIgnoreCase("soum91") && repo.equalsIgnoreCase("reynard-browser");
        boolean amazon = owner.equalsIgnoreCase("soum91") && repo.equalsIgnoreCase("amazonmusictvplus");
        app.name = reynard ? "Reynard TV" : amazon ? "Amazon Music TV Plus" : repo;
        app.packageName = reynard ? "com.soum91.reynardtv" : amazon ? "com.amazon.mp3" : "";
        app.summary = "GitHub APK releases from " + owner + "/" + repo;
        app.icon = reynard
                ? "https://raw.githubusercontent.com/soum91/reynard-browser/android-tv/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
                : amazon ? "https://raw.githubusercontent.com/soum91/amazonmusictvplus/main/assets/amazon_music_tv_banner.png" : "";
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.optJSONObject(a);
                if (asset == null) continue;
                String filename = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".apk") || !url.startsWith("https://github.com/")) continue;
                Version v = new Version();
                v.url = url;
                String tag = release.optString("tag_name", "");
                long releaseBuild = buildNumber(filename, tag);
                if (amazon && releaseBuild == 0) continue;
                v.code = amazon ? 526360110L + releaseBuild : releaseBuild;
                Matcher ver = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)+)").matcher(filename);
                v.name = amazon ? tag.replaceFirst("^v", "")
                        : ver.find() ? ver.group(1) + " (build " + releaseBuild + ")" : tag;
                String digest = asset.optString("digest", "");
                v.sha256 = digest.startsWith("sha256:") ? digest.substring(7) : "";
                v.changelog = release.optString("body", "");
                if (app.latest == null || v.code > app.latest.code) app.latest = v;
            }
        }
        if (app.latest == null) throw new IllegalArgumentException("No public APK release assets in " + owner + "/" + repo);
        List<App> out = new ArrayList<>();
        out.add(app);
        return out;
    }
    private static long buildNumber(String filename, String tag) {
        Matcher m = Pattern.compile("(?:build[-_.]|tv[.]|[-_.])(\\d+)(?:[-_.][^\\d]|\\.apk|$)", Pattern.CASE_INSENSITIVE).matcher(filename);
        long best = 0;
        while (m.find()) try { best = Math.max(best, Long.parseLong(m.group(1))); } catch (NumberFormatException ignored) { }
        if (best != 0) return best;
        Matcher t = Pattern.compile("(?:build[-_.]|tv[.])(\\d+)$", Pattern.CASE_INSENSITIVE).matcher(tag);
        return t.find() ? Long.parseLong(t.group(1)) : 0;
    }
    private static Version parse(JSONObject v) {
        Version r = new Version();
        JSONObject privateAsset = v.optJSONObject("githubReleaseAsset");
        if (privateAsset != null) {
            String repository = privateAsset.optString("repository", "");
            long assetId = privateAsset.optLong("assetId", 0);
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || assetId <= 0) return null;
            r.url = "https://api.github.com/repos/" + repository + "/releases/assets/" + assetId;
            r.privateGitHubAsset = true;
        } else {
            String url = v.optString("downloadURL", v.optString("apkURL", ""));
            if (!url.startsWith("https://") || !url.toLowerCase(java.util.Locale.ROOT).split("\\?", 2)[0].endsWith(".apk")) return null;
            r.url = url;
        }
        r.name = v.optString("version", "0.0.0");
        r.code = v.optLong("versionCode", v.optLong("buildVersion", 0));
        r.sha256 = v.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
        r.changelog = v.optString("changelog", v.optString("localizedDescription", ""));
        return r;
    }
    private static int compare(String a, String b) {
        String[] aa = a.split("\\."), bb = b.split("\\.");
        for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
            long x = num(i < aa.length ? aa[i] : "0"), y = num(i < bb.length ? bb[i] : "0");
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }
    private static long num(String v) { try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0; } }
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "EmberTV/0.1");
        try {
            if (c.getResponseCode() != 200 || !"https".equalsIgnoreCase(c.getURL().getProtocol())) throw new IllegalArgumentException("Feed HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 4_000_000) throw new IllegalArgumentException("Catalog exceeds 4 MB");
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { c.disconnect(); }
    }
}
