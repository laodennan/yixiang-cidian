import SwiftUI
import UIKit

// MARK: - 主界面（五个 Tab：分类浏览已收进词典页顶部）

struct ContentView: View {
    @EnvironmentObject private var tabSelection: TabSelection

    var body: some View {
        TabView(selection: $tabSelection.selection) {
            SearchView()
                .tabItem { Label("词典", systemImage: "magnifyingglass") }
                .tag(0)
            ChineseDictionaryView()
                .tabItem { Label("字典", systemImage: "book.closed") }
                .tag(1)
            RewriteView()
                .tabItem { Label("AI 改写", systemImage: "wand.and.stars") }
                .tag(2)
            HistoryView()
                .tabItem { Label("历史", systemImage: "clock.arrow.circlepath") }
                .tag(3)
            FavoritesView()
                .tabItem { Label("收藏", systemImage: "star") }
                .tag(4)
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

// MARK: - 加入词典按钮（把 AI 结果存进“我的词典”，点按后短暂显示对勾）

struct AddToLexiconButton: View {
    let text: String
    let source: String

    @EnvironmentObject private var customLexicon: CustomLexiconStore
    @State private var added = false

    var body: some View {
        Button {
            customLexicon.addSentence(text, source: source)
            added = true
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                added = false
            }
        } label: {
            Image(systemName: added || customLexicon.isSentenceAdded(text) ? "checkmark" : "plus")
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
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var customLexicon: CustomLexiconStore

    @State private var query = ""
    @State private var generatedEntry: LexiconEntry?
    @State private var showGenerated = false
    @State private var isGenerating = false
    @State private var aiError = ""
    @State private var showingSettings = false

    private var results: [LexiconEntry] {
        let base = Lexicon.search(query)
        let q = trimmedQuery
        guard !q.isEmpty else { return base }
        let mine = customLexicon.words.filter { $0.word.contains(q) && !base.contains($0) }
        return base + mine
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            List {
                if trimmedQuery.isEmpty {
                    if !customLexicon.words.isEmpty {
                        NavigationLink(destination: MyWordsView()) {
                            HStack {
                                Text("我的词条")
                                Spacer()
                                Text("\(customLexicon.words.count) 个")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    if !customLexicon.sentences.isEmpty {
                        NavigationLink(destination: MySentencesView()) {
                            HStack {
                                Text("我的词句")
                                Spacer()
                                Text("\(customLexicon.sentences.count) 条")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink(destination: CategoryListView()) {
                        HStack {
                            Text("按分类浏览")
                            Spacer()
                            Text("\(Lexicon.entries.count) 个词条")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                ForEach(results) { entry in
                    NavigationLink(destination: EntryDetailView(entry: entry)) {
                        HStack {
                            Text(entry.word)
                            if customLexicon.isWordAdded(entry.word) {
                                Text("我的")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
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
            history.addGenerated(word: entry.word, poems: entry.poems, expressions: entry.expressions)
            generatedEntry = entry
            showGenerated = true
        } catch {
            aiError = (error as? AIError)?.errorDescription ?? "生成失败，请重试"
        }
    }
}

// MARK: - 分类列表（从词典页顶部进入）

struct CategoryListView: View {
    var body: some View {
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
    @EnvironmentObject private var customLexicon: CustomLexiconStore

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
                    if !isGenerated && entry.category != "我的" {
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
                    Button(customLexicon.isWordAdded(entry.word) ? "已加入我的词典" : "加入我的词典") {
                        customLexicon.addWord(entry)
                    }
                    .buttonStyle(.bordered)
                    .disabled(customLexicon.isWordAdded(entry.word))
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

// MARK: - 我的词条 / 我的词句（从 AI 结果里亲手加入的，可删）

struct MyWordsView: View {
    @EnvironmentObject private var customLexicon: CustomLexiconStore

    var body: some View {
        List {
            ForEach(customLexicon.words) { entry in
                NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
            }
            .onDelete { offsets in
                for index in offsets {
                    customLexicon.removeWord(customLexicon.words[index].word)
                }
            }
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle("我的词条")
        .overlay {
            if customLexicon.words.isEmpty {
                ContentUnavailableView(
                    "还没有加入的词条",
                    systemImage: "plus",
                    description: Text("在 AI 生成的词条页点“加入我的词典”，就会出现在这里")
                )
            }
        }
    }
}

struct MySentencesView: View {
    @EnvironmentObject private var customLexicon: CustomLexiconStore

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        List {
            ForEach(customLexicon.sentences) { item in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .top) {
                        Text(item.text)
                        Spacer()
                        CopyButton(text: item.text)
                    }
                    Text("\(item.source) · \(Self.dateFormatter.string(from: item.date))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .onDelete { offsets in
                for index in offsets {
                    customLexicon.removeSentence(customLexicon.sentences[index].id)
                }
            }
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle("我的词句")
        .overlay {
            if customLexicon.sentences.isEmpty {
                ContentUnavailableView(
                    "还没有加入的词句",
                    systemImage: "plus",
                    description: Text("在 AI 改写结果或历史记录旁点加号，就会保存到这里")
                )
            }
        }
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
