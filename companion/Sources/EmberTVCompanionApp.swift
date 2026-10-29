import SwiftUI

@main
struct EmberTVCompanionApp: App {
    @StateObject private var model = TVModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .environmentObject(model)
                .tint(.orange)
        }
    }
}
