import SwiftUI

@main
struct EmberTVCompanionApp: App {
    @StateObject private var model = TVModel()

    var body: some Scene {
        WindowGroup { ContentView().environmentObject(model).tint(.orange) }
    }
}
