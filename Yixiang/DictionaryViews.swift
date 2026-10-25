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

// MARK: - 句子收藏按钮（每句独立收藏，点亮即收藏、再点即取消）

struct SentenceStarButton: View {
    let text: String
    let source: String

    @EnvironmentObject private var sentenceFavorites: SentenceFavoritesStore

    var body: some View {
        Button {
            sentenceFavorites.toggle(text, source: source)
        } label: {
            Image(systemName: sentenceFavorites.isFavorite(text) ? "star.fill" : "star")
        }
        .buttonStyle(.borderless)
    }
}

// MARK: - 搜索页（含 AI 兜底：离线词库没有的词可调用用户配置的接口生成）

struct SearchView: View {
    @State private var query = ""
    @State private var generatedEntry: LexiconEntry?
    @State private var showGenerated = false
    @State private var isGenerating = false
    @State private var aiError = ""
    @State private var showingSettings = false

    private var results: [LexiconEntry] {
        Lexicon.search(query)
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            List(results) { entry in
                NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("意象词典")
            .searchable(text: $query, prompt: "输入意象，如月亮")
            .overlay {
                if results.isEmpty && !trimmedQuery.isEmpty {
                    VStack(spacing: 16) {
                        ContentUnavailableView(
                            "离线词库里还没有「\(trimmedQuery)」",
                            systemImage: "magnifyingglass",
                            description: Text("可以让 AI 现场为这个词生成一则词条")
                        )
                        if isGenerating {
                            ProgressView("正在生成…")
                        } else {
                            Button("用 AI 生成「\(trimmedQuery)」的内容") {
                                Task { await generate() }
                            }
                            .buttonStyle(.borderedProminent)
                            if !AISettings.hasKey {
                                Button("去设置 API Key") {
                                    showingSettings = true
                                }
                                .font(.callout)
                            }
                        }
                        if !aiError.isEmpty {
                            Text(aiError)
                                .foregroundStyle(.red)
                                .font(.callout)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }
                    }
                }
            }
            .navigationDestination(isPresented: $showGenerated) {
                if let entry = generatedEntry {
                    EntryDetailView(entry: entry, isGenerated: true)
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }

    private func generate() async {
        let word = trimmedQuery
        guard !word.isEmpty else { return }
        aiError = ""
        isGenerating = true
        defer { isGenerating = false }

        if !AISettings.hasKey {
            aiError = "还没有配置 API Key，请先到设置里填写（「AI 改写」页右上角的设置）"
            return
        }

        do {
            let entry = try await AIClient.generateEntry(for: word)
            generatedEntry = entry
            showGenerated = true
        } catch {
            aiError = (error as? AIError)?.errorDescription ?? "生成失败，请重试"
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
            .safeAreaPadding(.bottom, 96)
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
        .safeAreaPadding(.bottom, 96)
        .navigationTitle(category)
    }
}

// MARK: - 词条详情页

struct EntryDetailView: View {
    let entry: LexiconEntry
    /// AI 现场生成的词条不进内置词库，因此不提供收藏，只展示内容
    var isGenerated = false

    @EnvironmentObject private var favorites: FavoritesStore

    /// 句子收藏的来源标注：内置词条按“出自「词」· 古诗句 / 生动表达”，
    /// AI 现场生成的词条统一标为“AI 生成”（词条顶部已有“由 AI 现场生成”提示）。
    private var poemSource: String {
        isGenerated ? "AI 生成" : "出自「\(entry.word)」· 古诗句"
    }

    private var expressionSource: String {
        isGenerated ? "AI 生成" : "出自「\(entry.word)」· 生动表达"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    Text(entry.word)
                        .font(.largeTitle.bold())
                    Spacer()
                    if !isGenerated {
                        Button {
                            favorites.toggle(entry.word)
                        } label: {
                            Image(systemName: favorites.isFavorite(entry.word) ? "star.fill" : "star")
                                .font(.title2)
                        }
                        .buttonStyle(.borderless)
                    }
                }

                Text(entry.category)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if isGenerated {
                    Text("由 AI 现场生成，仅供参考；古诗句请以原文核对")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

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
                            SentenceStarButton(text: poem.text, source: poemSource)
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
                            SentenceStarButton(text: line, source: expressionSource)
                            CopyButton(text: line)
                        }
                        Divider()
                    }
                }
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle(entry.word)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 收藏页

struct FavoritesView: View {
    @EnvironmentObject private var favorites: FavoritesStore
    @EnvironmentObject private var sentenceFavorites: SentenceFavoritesStore

    var body: some View {
        NavigationStack {
            List {
                if !favorites.favoriteEntries.isEmpty {
                    Section("词条") {
                        ForEach(favorites.favoriteEntries) { entry in
                            NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
                        }
                    }
                }
                if !sentenceFavorites.sentences.isEmpty {
                    Section("句子") {
                        ForEach(sentenceFavorites.sentences) { favorite in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(alignment: .top) {
                                    Text(favorite.text)
                                    Spacer()
                                    SentenceStarButton(text: favorite.text, source: favorite.source)
                                    CopyButton(text: favorite.text)
                                }
                                Text(favorite.source)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("收藏")
            .overlay {
                if favorites.favoriteEntries.isEmpty && sentenceFavorites.sentences.isEmpty {
                    ContentUnavailableView(
                        "还没有收藏",
                        systemImage: "star",
                        description: Text("在词条详情里点亮星星，或在任意句子旁点亮星星，就会出现在这里")
                    )
                }
            }
        }
    }
}
