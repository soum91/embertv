import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var model: TVModel
    @State private var selectedTab = 0
    @State private var chooseAPK = false
    @State private var showDetails: TVApp?

    var body: some View {
        TabView(selection: $selectedTab) {
            browse.tabItem { Label("Apps", systemImage: "square.grid.2x2.fill") }.tag(0)
            transfer.tabItem { Label("Send APK", systemImage: "arrow.up.doc.fill") }.tag(1)
            pairing.tabItem { Label("Connect", systemImage: "tv") }.tag(2)
            sources.tabItem { Label("Sources", systemImage: "tray.full.fill") }.tag(3)
        }
        .preferredColorScheme(.dark)
        .fileImporter(isPresented: $chooseAPK, allowedContentTypes: [UTType(filenameExtension: "apk") ?? .data]) { result in
            switch result {
            case .success(let url): Task { await model.upload(file: url) }
            case .failure(let error): model.status = error.localizedDescription
            }
        }
        .sheet(item: $showDetails) { app in
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        Text(app.summary).foregroundStyle(.secondary)
                        LabeledContent("Package ID", value: app.id)
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
        NavigationStack {
            List {
                statusRow
                ForEach(model.apps) { app in
                    Button { showDetails = app } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "square.stack.3d.up.fill").font(.title2).foregroundStyle(.orange).frame(width: 45, height: 45).background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 11))
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
                        Text("Add a public GitHub JSON source, then refresh.").font(.footnote).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity).padding(30)
                }
            }
            .navigationTitle("EmberTV")
            .toolbar { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.disabled(model.isWorking) }
            .task { if model.apps.isEmpty { await model.refresh() } }
        }
    }
    private var transfer: some View {
        NavigationStack {
            Form {
                Section("From iPhone Files") {
                    Button { chooseAPK = true } label: { Label("Choose an APK to send", systemImage: "folder") }
                    Text("The TV receives the file, checks its signing certificate, and opens Android's installer. Keep both apps open until the transfer finishes.").font(.footnote).foregroundStyle(.secondary)
                }
                statusRow
            }.navigationTitle("Send APK")
        }
    }
    private var pairing: some View {
        NavigationStack {
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
            }.navigationTitle("Connect")
        }
    }
    private var sources: some View {
        NavigationStack {
            Form {
                Section("Add GitHub JSON catalog") {
                    TextField("GitHub repo or JSON URL", text: $model.newSource).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    Button("Add source") { model.addSource() }
                }
                Section("Saved sources") {
                    ForEach(model.sources, id: \.self) { source in Text(source).font(.footnote).textSelection(.enabled) }
                        .onDelete { index in index.map { model.sources[$0] }.forEach { model.removeSource($0) } }
                }
                statusRow
            }.navigationTitle("Sources")
        }
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
