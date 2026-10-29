import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var model: TVModel
    @State private var selectedTab = 0
    @State private var chooseAPK = false
    @State private var chooseDriftFile = false
    @State private var driftMode = "wifi"
    @StateObject private var drift = DriftModel()
    @State private var showDetails: TVApp?
    @FocusState private var sourceFieldFocused: Bool

    var body: some View {
        TabView(selection: $selectedTab) {
            browse.tabItem { Label("Apps", systemImage: "square.grid.2x2.fill") }.tag(0)
            transfer.tabItem { Label("Send APK", systemImage: "arrow.up.doc.fill") }.tag(1)
            pairing.tabItem { Label("Connect", systemImage: "tv") }.tag(2)
            sources.tabItem { Label("Sources", systemImage: "tray.full.fill") }.tag(3)
            driftTransfer.tabItem { Label("Drift", systemImage: "arrow.left.arrow.right.square") }.tag(4)
        }
        .preferredColorScheme(.dark)
        .onChange(of: selectedTab) { _ in sourceFieldFocused = false }
        .fileImporter(isPresented: $chooseAPK, allowedContentTypes: [UTType(filenameExtension: "apk") ?? .data]) { result in
            switch result {
            case .success(let url): Task { await model.upload(file: url) }
            case .failure(let error): model.status = error.localizedDescription
            }
        }
        .fileImporter(isPresented: $chooseDriftFile, allowedContentTypes: [.item]) { result in
            switch result {
            case .success(let url):
                if driftMode == "wifi" { Task { await drift.sendWiFi(url) } }
                else { drift.sendBluetooth(url) }
            case .failure(let error): drift.status = error.localizedDescription
            }
        }
        .sheet(item: $showDetails) { app in
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        Text(app.summary).foregroundStyle(.secondary)
                        LabeledContent("Package ID", value: app.packageName.isEmpty ? "Verified on TV" : app.packageName)
                        LabeledContent("Version", value: app.version)
                        Text("Changelog").font(.headline)
                        Text(app.changelog.isEmpty ? "No changelog provided" : app.changelog)
                        Button { Task { await model.install(app) } } label: { Label("Install on TV", systemImage: "arrow.down.to.line.compact").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent)
                    }.padding()
                }
                .navigationTitle(app.name)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showDetails = nil } } }
            }
        }
    }
    private var browse: some View {
        VStack(spacing: 0) {
            HStack {
                Text("EmberTV").font(.system(size: 32, weight: .bold))
                Spacer()
                Button { Task { await model.refresh() } } label: {
                    Image(systemName: "arrow.clockwise").font(.title3).foregroundStyle(.orange)
                }
                .disabled(model.isWorking)
                .accessibilityLabel("Refresh app catalog")
            }
            .padding(.horizontal, 22).padding(.top, 12).padding(.bottom, 8)
            List {
                statusRow
                ForEach(model.apps) { app in
                    Button { showDetails = app } label: {
                        HStack(spacing: 14) {
                            AsyncImage(url: URL(string: app.iconURL)) { phase in
                                if let image = phase.image {
                                    image.resizable().scaledToFit()
                                } else {
                                    Image(systemName: "square.stack.3d.up.fill")
                                        .resizable().scaledToFit().padding(10).foregroundStyle(.orange)
                                }
                            }
                            .frame(width: 45, height: 45)
                            .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 11))
                            .clipShape(RoundedRectangle(cornerRadius: 11))
                            VStack(alignment: .leading) {
                                Text(app.name).font(.headline)
                                Text("v\(app.version) • \(app.summary)").font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.secondary)
                        }
                    }.buttonStyle(.plain)
                }
                if model.apps.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "square.stack.3d.down.right").font(.largeTitle).foregroundStyle(.orange)
                        Text("No Android APKs").font(.headline)
                        Text("Add a public GitHub repository or JSON source, then refresh.").font(.footnote).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity).padding(30)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { scrollClearance }
            .task { if model.apps.isEmpty { await model.refresh() } }
        }
    }
    private var transfer: some View {
        VStack(spacing: 0) {
            pageHeader("Send APK")
            Form {
                Section("From iPhone Files") {
                    Button { chooseAPK = true } label: { Label("Choose an APK to send", systemImage: "folder") }
                    Text("The TV receives the file, checks its signing certificate, and opens Android's installer. Keep both apps open until the transfer finishes.").font(.footnote).foregroundStyle(.secondary)
                }
                statusRow
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { scrollClearance }
        }
    }
    private var driftTransfer: some View {
        VStack(spacing: 0) {
            pageHeader("Drift transfer")
            Form {
                Section("Connection") {
                    Picker("Transfer mode", selection: $driftMode) {
                        Text("Wi-Fi").tag("wifi")
                        Text("Bluetooth").tag("bluetooth")
                    }.pickerStyle(.segmented)
                    if driftMode == "wifi" {
                        TextField("TV IPv4 address", text: $drift.address)
                            .keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled()
                        TextField("Six-digit code from Drift on TV", text: $drift.code).keyboardType(.numberPad)
                        Button("Pair with Drift") { Task { await drift.pairWiFi() } }.disabled(drift.busy)
                    } else {
                        Button("Find nearby Drift TV") { drift.scanBluetooth() }.disabled(drift.busy)
                        ForEach(drift.peers) { peer in
                            Button("Connect: \(peer.name)") { drift.connectBluetooth(peer.id) }.disabled(drift.busy)
                        }
                        if !drift.connectedName.isEmpty {
                            Label("Connected: \(drift.connectedName)", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                        }
                    }
                }
                Section("Send files") {
                    Button { chooseDriftFile = true } label: { Label("Choose any file", systemImage: "doc.badge.arrow.up") }
                        .disabled(drift.busy)
                    if drift.busy { ProgressView(value: drift.progress) }
                    Text(driftMode == "wifi" ? "For large files. Supports the TV's private ad hoc Wi-Fi network or the same trusted LAN. Accept each file on TV." :
                         "For files up to 8 MiB. Requires TV Bluetooth LE peripheral advertising, encryption, and an on-TV approval.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("Status") { Text(drift.status).font(.footnote).foregroundStyle(.secondary) }
                Section("How to connect") {
                    if driftMode == "wifi" {
                        Text("1. Open Drift on TV and choose ad hoc Wi-Fi or existing network.")
                        Text("2. For ad hoc mode, join the displayed TV Wi-Fi network in iPhone Settings first.")
                        Text("3. Enter the TV IP and code above, pair, then send your file.")
                    } else {
                        Text("1. Open Drift on TV and start its Bluetooth LE receiver.")
                        Text("2. Scan and connect here. Accept any iOS Bluetooth pairing prompt.")
                        Text("3. Choose a small file and accept it with the TV remote.")
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) { scrollClearance }
        }
    }
    private var pairing: some View {
        VStack(spacing: 0) {
            pageHeader("Connect")
            Form {
                Section("Android TV") {
                    TextField("TV IP address", text: $model.tvAddress).keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Six-digit TV pairing code", text: $model.code).keyboardType(.numberPad)
                    Button("Pair with TV") { Task { await model.pair() } }.disabled(model.isWorking)
                    if model.isPaired { Label("Paired", systemImage: "checkmark.circle.fill").foregroundStyle(.green); Button("Forget pairing", role: .destructive) { model.unpair() } }
                }
                Section("Instructions") {
                    Text("1. Open EmberTV on your Android TV.")
                    Text("2. Select Pair iPhone in the TV sidebar.")
                    Text("3. Enter its TV IP and pairing code above.")
                    Text("4. Browse an APK or send a file, then approve installation on the TV.")
                }
                statusRow
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) { scrollClearance }
        }
    }
    private var sources: some View {
        VStack(spacing: 0) {
            pageHeader("Sources")
            Form {
                Section("Add GitHub APK repo or JSON catalog") {
                    TextField("GitHub repo or JSON URL", text: $model.newSource)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .focused($sourceFieldFocused)
                        .submitLabel(.done)
                        .onSubmit { sourceFieldFocused = false }
                    Button("Add source") { sourceFieldFocused = false; model.addSource() }
                }
                Section("Saved sources") {
                    ForEach(model.sources, id: \.self) { source in Text(source).font(.footnote).textSelection(.enabled) }
                        .onDelete { index in index.map { model.sources[$0] }.forEach { model.removeSource($0) } }
                }
                statusRow
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) { scrollClearance }
        }
    }
    private func pageHeader(_ title: String) -> some View {
        HStack {
            Text(title).font(.system(size: 32, weight: .bold))
            Spacer()
        }
        .padding(.horizontal, 22).padding(.top, 12).padding(.bottom, 8)
    }
    private var scrollClearance: some View {
        Color.clear.frame(height: 88).accessibilityHidden(true)
    }
    private var statusRow: some View {
        Section("Status") {
            HStack {
                if model.isWorking { ProgressView() }
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}
