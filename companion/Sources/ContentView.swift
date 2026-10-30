import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var model: TVModel
    @State private var selectedTab = 0
    @State private var chooseAPK = false
    @State private var showDetails: TVApp?
    @FocusState private var sourceFieldFocused: Bool

    var body: some View {
        TabView(selection: $selectedTab) {
            browse.tabItem { Label("Apps", systemImage: "square.grid.2x2.fill") }.tag(0)
            transfer.tabItem { Label("Send APK", systemImage: "arrow.up.doc.fill") }.tag(1)
            pairing.tabItem { Label("Connect", systemImage: "tv") }.tag(2)
            sources.tabItem { Label("Sources", systemImage: "tray.full.fill") }.tag(3)
        }
        .preferredColorScheme(.dark)
        .onChange(of: selectedTab) { _ in sourceFieldFocused = false }
        .sheet(isPresented: $chooseAPK) {
            EmberTVNativeAPKPicker(
                onPick: { file in
                    chooseAPK = false
                    guard file.pathExtension.lowercased() == "apk" else {
                        model.status = "Choose an .apk file, not \(file.lastPathComponent)."
                        return
                    }
                    model.status = "Selected \(file.lastPathComponent) from Files."
                    Task { await model.upload(file: file) }
                },
                onCancel: {
                    chooseAPK = false
                    model.status = "APK selection cancelled."
                }
            )
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

private struct EmberTVNativeAPKPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        // Unknown .apk UTTypes can make SwiftUI fileImporter grey out valid files.
        // Allow the system's broad file type and check the extension on selection.
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        let onCancel: () -> Void

        init(onPick: @escaping (URL) -> Void, onCancel: @escaping () -> Void) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let file = urls.first else { onCancel(); return }
            onPick(file)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onCancel()
        }
    }
}
