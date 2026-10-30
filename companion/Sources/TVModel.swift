import Foundation
import Security

struct TVApp: Identifiable {
    let id: String
    let packageName: String
    let name: String
    let iconURL: String
    let summary: String
    let version: String
    let downloadURL: String
    let sha256: String
    let changelog: String
}

struct TVInstalledApp: Identifiable, Decodable {
    let packageName: String
    let name: String
    let version: String
    let system: Bool
    let splitCount: Int
    var id: String { packageName }
    var extensionName: String { splitCount > 0 ? "apks" : "apk" }
}
struct TVInstalledList: Decodable { let apps: [TVInstalledApp] }
struct TVBackupShare: Identifiable {
    let id = UUID()
    let url: URL
}

@MainActor final class TVModel: ObservableObject {
    @Published var tvAddress = UserDefaults.standard.string(forKey: "tvAddress") ?? ""
    @Published var code = ""
    @Published var status = "Pair with EmberTV on your local Wi-Fi"
    @Published var apps: [TVApp] = []
    @Published var installedApps: [TVInstalledApp] = []
    @Published var backupShare: TVBackupShare?
    @Published var sources: [String] = {
        let defaults = UserDefaults.standard
        var saved = defaults.stringArray(forKey: "sources") ?? ["https://raw.githubusercontent.com/soum91/embertv/main/repo.json"]
        // One-time migration: preserve existing user sources and add the verified
        // public Reynard Android TV APK release repository.
        if !defaults.bool(forKey: "emberTVSeededReynardSource") {
            let reynard = "https://github.com/soum91/reynard-browser"
            if !saved.contains(reynard) { saved.append(reynard) }
            defaults.set(saved, forKey: "sources")
            defaults.set(true, forKey: "emberTVSeededReynardSource")
        }
        if !defaults.bool(forKey: "emberTVSeededAmazonSource") {
            let amazon = "https://github.com/soum91/amazonmusictvplus"
            if !saved.contains(amazon) { saved.append(amazon) }
            defaults.set(saved, forKey: "sources")
            defaults.set(true, forKey: "emberTVSeededAmazonSource")
        }
        return saved
    }()
    @Published var newSource = ""
    @Published var isWorking = false

    private var token: String = {
        guard let data = keychainRead("emberTVToken") else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }()

    var isPaired: Bool { !token.isEmpty }
    private var base: URL? {
        let host = tvAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, !host.contains("/"), !host.contains(":") else { return nil }
        return URL(string: "http://\(host):45787")
    }
    func pair() async {
        guard let base else { status = "Enter the TV's IPv4 address"; return }
        guard code.count == 6 else { status = "Enter the six-digit pairing code from your TV"; return }
        isWorking = true; defer { isWorking = false }
        do {
            try await verifyTVReachable(base)
            let data: Data
            do {
                data = try await request(base.appendingPathComponent("pair"), json: ["code": code], authenticated: false)
            } catch let error as URLError where error.code == .networkConnectionLost {
                // A TV listener that is restarting can close one in-flight
                // connection. Retry once; never retry an invalid pairing code.
                try await Task.sleep(nanoseconds: 350_000_000)
                data = try await request(base.appendingPathComponent("pair"), json: ["code": code], authenticated: false)
            }
            guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let value = result["token"] as? String else { throw TVError.invalidResponse }
            token = value
            keychainWrite(Data(value.utf8), "emberTVToken")
            UserDefaults.standard.set(tvAddress, forKey: "tvAddress")
            status = "Paired! Your TV can now receive APKs in the background."
        } catch { status = "Pairing failed: \(diagnostic(error))" }
    }
    func testConnection() async {
        guard let base else { status = "Enter the TV's IPv4 address"; return }
        isWorking = true; defer { isWorking = false }
        do {
            try await verifyTVReachable(base)
            status = "TV reachable at \(base.host ?? tvAddress):45787. Enter the pairing code to connect."
        } catch { status = "TV not reachable: \(diagnostic(error))" }
    }
    private func verifyTVReachable(_ base: URL) async throws {
        var request = URLRequest(url: base.appendingPathComponent("status"))
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data)
        guard let info = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              info["name"] as? String == "EmberTV" else { throw TVError.invalidResponse }
        if let ready = info["ready"] as? Bool, !ready { throw TVError.server("The TV receiver has not started yet") }
    }
    private func diagnostic(_ error: Error) -> String {
        guard let network = error as? URLError else { return error.localizedDescription }
        switch network.code {
        case .cannotConnectToHost, .timedOut, .networkConnectionLost, .notConnectedToInternet:
            return "\(network.localizedDescription). Check that EmberTV's TV receiver is running, both devices use the same Wi-Fi, iOS Local Network access is enabled, and VPN is temporarily off."
        default: return network.localizedDescription
        }
    }
    func unpair() { token = ""; keychainDelete("emberTVToken"); status = "Unpaired locally" }
    func refresh() async {
        isWorking = true; defer { isWorking = false }
        var found: [String: TVApp] = [:]; var errors = 0
        for source in sources {
            do {
                let data = try await fetchSource(source)
                guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let records = root["apps"] as? [[String: Any]] else { throw TVError.invalidResponse }
                for app in records {
                    let versions = (app["versions"] as? [[String: Any]]) ?? [app]
                    let matching = versions.compactMap { entry -> (String, String, String, String, Int64)? in
                        let url = (entry["downloadURL"] ?? entry["apkURL"]) as? String ?? ""
                        guard url.lowercased().split(separator: "?").first?.hasSuffix(".apk") == true, url.hasPrefix("https://") else { return nil }
                        let name = entry["version"] as? String ?? "0.0.0"
                        let changelog = (entry["changelog"] ?? entry["localizedDescription"]) as? String ?? ""
                        let hash = entry["sha256"] as? String ?? ""
                        let code = (entry["versionCode"] as? NSNumber)?.int64Value ?? Int64((entry["buildVersion"] as? String) ?? "0") ?? 0
                        return (name, url, hash, changelog, code)
                    }
                    guard let version = matching.max(by: { $0.4 < $1.4 || ($0.4 == $1.4 && $0.0.compare($1.0, options: .numeric) == .orderedAscending) }) else { continue }
                    let packageName = (app["packageName"] ?? app["bundleIdentifier"]) as? String ?? ""
                    let id = packageName.isEmpty ? version.1 : packageName
                    let entry = TVApp(id: id, packageName: packageName, name: app["name"] as? String ?? "Unnamed", iconURL: app["iconURL"] as? String ?? "", summary: (app["subtitle"] ?? app["localizedDescription"]) as? String ?? "", version: version.0, downloadURL: version.1, sha256: version.2, changelog: version.3)
                    found[id] = entry
                }
            } catch { errors += 1 }
        }
        apps = found.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        status = "Loaded \(apps.count) APKs" + (errors == 0 ? "" : " (\(errors) source errors)")
    }
    func addSource() {
        var input = newSource.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.hasPrefix("https://") else { status = "Source must use HTTPS"; return }
        if let url = URL(string: input), url.host == "github.com" {
            let components = url.pathComponents.filter { $0 != "/" }
            if components.count >= 2 { input = "https://github.com/\(components[0])/\(components[1])" }
        }
        if !sources.contains(input) { sources.append(input); UserDefaults.standard.set(sources, forKey: "sources") }
        newSource = ""
        Task { await refresh() }
    }
    func removeSource(_ source: String) {
        sources.removeAll { $0 == source }
        UserDefaults.standard.set(sources, forKey: "sources")
    }
    func install(_ app: TVApp) async {
        guard let base else { status = "Set TV address and pair first"; return }
        isWorking = true; defer { isWorking = false }
        do {
            _ = try await request(base.appendingPathComponent("install"), json: ["url": app.downloadURL, "packageName": app.packageName, "sha256": app.sha256], authenticated: true)
            status = "Sent \(app.name) to TV. Confirm installation on TV."
        } catch { status = "Install request failed: \(error.localizedDescription)" }
    }
    func upload(file: URL) async {
        guard let base, !token.isEmpty else { status = "Pair with your TV first"; return }
        let scoped = file.startAccessingSecurityScopedResource()
        defer { if scoped { file.stopAccessingSecurityScopedResource() } }
        isWorking = true; defer { isWorking = false }
        do {
            let local = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".apk")
            try FileManager.default.copyItem(at: file, to: local)
            defer { try? FileManager.default.removeItem(at: local) }
            var req = URLRequest(url: base.appendingPathComponent("upload"))
            req.httpMethod = "POST"
            req.setValue(token, forHTTPHeaderField: "X-Ember-Token")
            req.setValue("application/vnd.android.package-archive", forHTTPHeaderField: "Content-Type")
            let (data, response) = try await URLSession.shared.upload(for: req, fromFile: local)
            try validate(response, data: data)
            status = "APK sent. Confirm installation on the TV."
        } catch { status = "Upload failed: \(error.localizedDescription)" }
    }
    func loadInstalledApps() async {
        guard let base, !token.isEmpty else { status = "Pair with your TV first"; return }
        isWorking = true; defer { isWorking = false }
        do {
            var req = URLRequest(url: base.appendingPathComponent("apps"))
            req.timeoutInterval = 25
            req.cachePolicy = .reloadIgnoringLocalCacheData
            req.setValue(token, forHTTPHeaderField: "X-Ember-Token")
            let (data, response) = try await URLSession.shared.data(for: req)
            try validate(response, data: data)
            installedApps = try JSONDecoder().decode(TVInstalledList.self, from: data).apps
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            status = "Found \(installedApps.count) installed packages on TV."
        } catch { status = "Cannot list installed TV apps: \(diagnostic(error))" }
    }
    func exportBackup(_ app: TVInstalledApp) async {
        guard let base, !token.isEmpty else { status = "Pair with your TV first"; return }
        isWorking = true; defer { isWorking = false }
        do {
            var components = URLComponents(url: base.appendingPathComponent("backup"), resolvingAgainstBaseURL: false)
            components?.queryItems = [URLQueryItem(name: "package", value: app.packageName)]
            guard let endpoint = components?.url else { throw TVError.invalidResponse }
            var req = URLRequest(url: endpoint)
            req.timeoutInterval = 180
            req.setValue(token, forHTTPHeaderField: "X-Ember-Token")
            let (download, response) = try await URLSession.shared.download(for: req)
            guard let http = response as? HTTPURLResponse else { throw TVError.invalidResponse }
            if !(200..<300).contains(http.statusCode) {
                let data = try Data(contentsOf: download)
                try validate(response, data: data)
                throw TVError.server("Export HTTP \(http.statusCode)")
            }
            let attributes = try FileManager.default.attributesOfItem(atPath: download.path)
            let length = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            guard length > 0, length <= 1_600_000_000 else { throw TVError.server("Backup file empty or too large") }
            let safeName = app.packageName.replacingOccurrences(of: #"[^A-Za-z0-9_.-]"#, with: "_", options: .regularExpression)
            let target = FileManager.default.temporaryDirectory
                .appendingPathComponent("\(UUID().uuidString)-\(safeName).\(app.extensionName)")
            try FileManager.default.moveItem(at: download, to: target)
            backupShare = TVBackupShare(url: target)
            status = "Backup ready: \(app.name) (\(app.extensionName.uppercased())). Save to Files or share."
        } catch { status = "Backup failed: \(diagnostic(error))" }
    }
    private func request(_ url: URL, json: [String: Any], authenticated: Bool) async throws -> Data {
        if authenticated && token.isEmpty { throw TVError.notPaired }
        var req = URLRequest(url: url)
        req.timeoutInterval = 25
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.httpMethod = "POST"; req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authenticated { req.setValue(token, forHTTPHeaderField: "X-Ember-Token") }
        req.httpBody = try JSONSerialization.data(withJSONObject: json)
        let (data, response) = try await URLSession.shared.data(for: req)
        try validate(response, data: data)
        return data
    }
    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw TVError.invalidResponse }
        if !(200..<300).contains(http.statusCode) {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: String])?["message"] ?? "HTTP \(http.statusCode)"
            throw TVError.server(msg)
        }
    }
    private func fetchSource(_ source: String) async throws -> Data {
        guard let url = URL(string: source), url.scheme == "https" else { throw TVError.invalidResponse }
        if url.host?.lowercased() == "github.com" {
            let components = url.pathComponents.filter { $0 != "/" }
            guard components.count >= 2 else { throw TVError.invalidResponse }
            return try await fetchGitHubReleases(owner: components[0], repo: components[1])
        }
        let paths = source.hasSuffix("/repo.json") ? [source, source.replacingOccurrences(of: "repo.json", with: "source.json"), source.replacingOccurrences(of: "repo.json", with: "apps.json"), source.replacingOccurrences(of: "repo.json", with: "app-repo.json")] : [source]
        for candidate in paths {
            guard let u = URL(string: candidate) else { continue }
            do {
                let (data, response) = try await URLSession.shared.data(from: u)
                if (response as? HTTPURLResponse)?.statusCode == 200 && data.count < 4_000_000 { return data }
            } catch { continue }
        }
        throw TVError.server("No valid JSON at \(source)")
    }
    private func fetchGitHubReleases(owner: String, repo: String) async throws -> Data {
        guard owner.range(of: #"^[A-Za-z0-9_.-]+$"#, options: .regularExpression) != nil,
              repo.range(of: #"^[A-Za-z0-9_.-]+$"#, options: .regularExpression) != nil,
              let url = URL(string: "https://api.github.com/repos/\(owner)/\(repo)/releases?per_page=50")
        else { throw TVError.invalidResponse }
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw TVError.server("GitHub release list unavailable for \(owner)/\(repo) (private repositories require authorization)")
        }
        guard let releases = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw TVError.invalidResponse }
        let reynard = owner == "soum91" && repo == "reynard-browser"
        let amazon = owner == "soum91" && repo == "amazonmusictvplus"
        var versions: [[String: Any]] = []
        for release in releases where !(release["draft"] as? Bool ?? false) {
            let tag = release["tag_name"] as? String ?? "latest"
            let changelog = release["body"] as? String ?? ""
            for asset in release["assets"] as? [[String: Any]] ?? [] {
                let filename = asset["name"] as? String ?? ""
                let downloadURL = asset["browser_download_url"] as? String ?? ""
                guard filename.lowercased().hasSuffix(".apk"), downloadURL.hasPrefix("https://github.com/") else { continue }
                let releaseBuild = githubBuildNumber(filename: filename, tag: tag)
                if amazon && releaseBuild == 0 { continue }
                let build = amazon ? 526_360_110 + releaseBuild : releaseBuild
                let versionPattern = #"\d+\.\d+(?:\.\d+)+"#
                let version = filename.range(of: versionPattern, options: .regularExpression).map { String(filename[$0]) } ?? tag
                let digest = asset["digest"] as? String ?? ""
                versions.append(["version": amazon ? String(tag.dropFirst(tag.hasPrefix("v") ? 1 : 0)) : "\(version) (build \(releaseBuild))", "versionCode": build,
                                 "downloadURL": downloadURL, "sha256": digest.hasPrefix("sha256:") ? String(digest.dropFirst(7)) : "",
                                 "changelog": changelog])
            }
        }
        guard !versions.isEmpty else { throw TVError.server("No public APK release assets in \(owner)/\(repo)") }
        var app: [String: Any] = ["name": reynard ? "Reynard TV" : amazon ? "Amazon Music TV Plus" : repo,
                                  "subtitle": "GitHub APK releases from \(owner)/\(repo)", "versions": versions]
        if reynard {
            app["packageName"] = "com.soum91.reynardtv"
            app["iconURL"] = "https://raw.githubusercontent.com/soum91/reynard-browser/android-tv/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
        } else if amazon {
            app["packageName"] = "com.amazon.mp3"
            app["iconURL"] = "https://raw.githubusercontent.com/soum91/amazonmusictvplus/main/assets/amazon_music_tv_banner.png"
        }
        return try JSONSerialization.data(withJSONObject: ["apps": [app]])
    }
    private func githubBuildNumber(filename: String, tag: String) -> Int64 {
        let pattern = #"(?:build[-_.]|tv[.])(\d+)"#
        for value in [filename, tag] {
            if let match = value.range(of: pattern, options: [.regularExpression, .caseInsensitive]),
               let number = value[match].split(whereSeparator: { !$0.isNumber }).last,
               let build = Int64(number) { return build }
        }
        return 0
    }
}

enum TVError: LocalizedError {
    case invalidResponse, notPaired, server(String)
    var errorDescription: String? {
        switch self { case .invalidResponse: return "Invalid response"; case .notPaired: return "Pair with your TV first"; case .server(let s): return s }
    }
}

private func keychainRead(_ key: String) -> Data? {
    let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
    var result: CFTypeRef?
    return SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess ? result as? Data : nil
}
private func keychainWrite(_ data: Data, _ key: String) {
    keychainDelete(key)
    let entry: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key, kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
    SecItemAdd(entry as CFDictionary, nil)
}
private func keychainDelete(_ key: String) {
    SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key] as CFDictionary)
}
