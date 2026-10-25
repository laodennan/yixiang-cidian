import SwiftUI

@main
struct YixiangApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(FavoritesStore.shared)
                .environmentObject(SentenceFavoritesStore.shared)
        }
    }
}
