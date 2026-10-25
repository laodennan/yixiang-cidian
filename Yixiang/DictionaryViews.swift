import SwiftUI
import UIKit

// MARK: - 主界面（四个 Tab）

struct ContentView: View {
    var body: some View {
        TabView {
            SearchView()
                .tabItem { Label("词典", systemImage: "magnifyingglass") }
            CategoryView()
                .tabItem { Label("分类", systemImage: "square.grid.2x2") }
            RewriteView()
                .tabItem { Label("AI 改写", systemImage: "wand.and.stars") }
            FavoritesView()
                .tabItem { Label("收藏", systemImage: "star") }
        }
    }
}

// MARK: - 复制按钮（点按后短暂显示对勾）

struct CopyButton: View {
    let text: String

    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = text
            copied = true
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                copied = false
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
        }
        .buttonStyle(.borderless)
    }
}

// MARK: - 搜索页

struct SearchView: View {
    @State private var query = ""

    private var results: [LexiconEntry] {
        Lexicon.search(query)
    }

    var body: some View {
        NavigationStack {
            List(results) { entry in
                NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
            }
            .navigationTitle("意象词典")
            .searchable(text: $query, prompt: "输入意象，如月亮")
            .overlay {
                if results.isEmpty {
                    ContentUnavailableView(
                        "没有找到相关意象",
                        systemImage: "magnifyingglass",
                        description: Text("换个词试试，比如雨、雪、离别")
                    )
                }
            }
        }
    }
}

// MARK: - 分类页

struct CategoryView: View {
    var body: some View {
        NavigationStack {
            List {
                ForEach(Lexicon.categories, id: \.self) { category in
                    NavigationLink {
                        EntryListView(category: category)
                    } label: {
                        HStack {
                            Text(category)
                            Spacer()
                            Text("\(Lexicon.entries(in: category).count) 个")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("分类")
        }
    }
}

struct EntryListView: View {
    let category: String

    var body: some View {
        List(Lexicon.entries(in: category)) { entry in
            NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
        }
        .navigationTitle(category)
    }
}

// MARK: - 词条详情页

struct EntryDetailView: View {
    let entry: LexiconEntry

    @EnvironmentObject private var favorites: FavoritesStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    Text(entry.word)
                        .font(.largeTitle.bold())
                    Spacer()
                    Button {
                        favorites.toggle(entry.word)
                    } label: {
                        Image(systemName: favorites.isFavorite(entry.word) ? "star.fill" : "star")
                            .font(.title2)
                    }
                    .buttonStyle(.borderless)
                }

                Text(entry.category)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 12) {
                    Text("古诗句")
                        .font(.headline)
                    ForEach(entry.poems) { poem in
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(poem.text)
                                    .font(.body)
                                Text("\(poem.author) · 《\(poem.source)》")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            CopyButton(text: poem.text)
                        }
                        Divider()
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("生动表达")
                        .font(.headline)
                    ForEach(entry.expressions, id: \.self) { line in
                        HStack(alignment: .top) {
                            Text(line)
                                .font(.body)
                            Spacer()
                            CopyButton(text: line)
                        }
                        Divider()
                    }
                }
            }
            .padding()
        }
        .navigationTitle(entry.word)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 收藏页

struct FavoritesView: View {
    @EnvironmentObject private var favorites: FavoritesStore

    var body: some View {
        NavigationStack {
            List(favorites.favoriteEntries) { entry in
                NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
            }
            .navigationTitle("收藏")
            .overlay {
                if favorites.favoriteEntries.isEmpty {
                    ContentUnavailableView(
                        "还没有收藏",
                        systemImage: "star",
                        description: Text("在词条详情里点亮星星，就会出现在这里")
                    )
                }
            }
        }
    }
}
