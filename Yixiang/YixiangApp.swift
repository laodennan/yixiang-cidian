import SwiftUI

@main
struct YixiangApp: App {
    @StateObject private var tabSelection = TabSelection()
    @StateObject private var rewriteRefill = RewriteRefill()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(FavoritesStore.shared)
                .environmentObject(SentenceFavoritesStore.shared)
                .environmentObject(HistoryStore.shared)
                .environmentObject(CustomLexiconStore.shared)
                .environmentObject(tabSelection)
                .environmentObject(rewriteRefill)
        }
    }
}
