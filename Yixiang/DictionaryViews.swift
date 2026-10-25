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
    @ObservedObject private var dictStore = DictionaryStore.shared
    @ObservedObject private var webCache = WebSearchCache.shared

    @State private var query = ""
    @State private var generatedEntry: LexiconEntry?
    @State private var showGenerated = false
    @State private var isGenerating = false
    @State private var aiError = ""
    @State private var showingSettings = false
    @State private var webSummary: WebSummary?
    @State private var webSummaryIsCached = false
    @State private var webLoading = false
    @State private var webError = ""

    private var results: [LexiconEntry] {
        let base = Lexicon.search(query)
        let q = trimmedQuery
        guard !q.isEmpty else { return base }
        let mine = customLexicon.words.filter { $0.word.contains(q) && !base.contains($0) }
        return base + mine
    }

    /// 字典库联查结果（离线）：词典 Tab 没有的词，先看字典里有没有
    private var dictMatches: DictResults {
        dictStore.search(trimmedQuery, scope: .all)
    }

    private var webFallbackURL: URL {
        let encoded = trimmedQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trimmedQuery
        return URL(string: "https://www.baidu.com/s?wd=\(encoded)") ?? URL(string: "https://www.baidu.com")!
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
                    ScrollView {
                        VStack(alignment: .leading, spacing: 24) {
                            Text("意象词库里还没有「\(trimmedQuery)」")
                                .font(.headline)

                            // 字典库联查（离线）：词典没有的词，字典里可能有
                            if dictStore.loaded {
                                if !dictMatches.isEmpty {
                                    VStack(alignment: .leading, spacing: 12) {
                                        Text("字典里找到了")
                                            .font(.headline)
                                        if !dictMatches.idioms.isEmpty {
                                            Text("成语")
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                            ForEach(Array(dictMatches.idioms.prefix(5)), id: \.word) { item in
                                                NavigationLink(destination: DictIdiomDetailView(item: item)) {
                                                    VStack(alignment: .leading, spacing: 2) {
                                                        Text(item.word)
                                                        Text(item.explanation)
                                                            .font(.callout)
                                                            .foregroundStyle(.secondary)
                                                            .lineLimit(1)
                                                    }
                                                }
                                            }
                                        }
                                        if !dictMatches.words.isEmpty {
                                            Text("词语")
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                            ForEach(Array(dictMatches.words.prefix(5)), id: \.word) { item in
                                                NavigationLink(destination: DictWordDetailView(item: item)) {
                                                    VStack(alignment: .leading, spacing: 2) {
                                                        Text(item.word)
                                                        Text(item.explanation)
                                                            .font(.callout)
                                                            .foregroundStyle(.secondary)
                                                            .lineLimit(1)
                                                    }
                                                }
                                            }
                                        }
                                        if !dictMatches.chars.isEmpty {
                                            Text("汉字")
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                            ForEach(Array(dictMatches.chars.prefix(3)), id: \.word) { item in
                                                NavigationLink(destination: DictCharDetailView(item: item)) {
                                                    VStack(alignment: .leading, spacing: 2) {
                                                        Text(item.word)
                                                        Text(item.explanation)
                                                            .font(.callout)
                                                            .foregroundStyle(.secondary)
                                                            .lineLimit(1)
                                                    }
                                                }
                                            }
                                        }
                                        if !dictMatches.xiehouyu.isEmpty {
                                            Text("歇后语")
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                            ForEach(Array(dictMatches.xiehouyu.prefix(3)), id: \.riddle) { item in
                                                NavigationLink(destination: DictXiehouyuDetailView(item: item)) {
                                                    VStack(alignment: .leading, spacing: 2) {
                                                        Text(item.riddle)
                                                        Text(item.answer)
                                                            .font(.callout)
                                                            .foregroundStyle(.secondary)
                                                            .lineLimit(1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                HStack(spacing: 8) {
                                    ProgressView()
                                    Text("正在加载离线字典…")
                                        .font(.callout)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            // 联网搜索（免费接口，无需 Key；结果自动缓存到本机）
                            VStack(alignment: .leading, spacing: 12) {
                                Text("联网搜索")
                                    .font(.headline)
                                if let summary = webSummary {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(summary.title)
                                            .font(.headline)
                                        Text(summary.extract)
                                        HStack {
                                            if webSummaryIsCached {
                                                Text("本地缓存 · \(MySentencesView.dateFormatter.string(from: summary.fetchedAt))")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                            Spacer()
                                            AddToLexiconButton(text: summary.extract, source: "联网搜索")
                                            CopyButton(text: summary.extract)
                                        }
                                        if let url = URL(string: summary.pageURL) {
                                            Link("来源：维基百科", destination: url)
                                                .font(.callout)
                                        }
                                        Button("重新获取") {
                                            Task { await fetchWeb(forceRefresh: true) }
                                        }
                                        .font(.callout)
                                    }
                                    .padding()
                                    .background(Color.secondary.opacity(0.1))
                                    .cornerRadius(12)
                                } else if webLoading {
                                    ProgressView("正在联网搜索…")
                                } else {
                                    Button("联网搜索「\(trimmedQuery)」") {
                                        Task { await fetchWeb() }
                                    }
                                    .buttonStyle(.bordered)
                                    if !webError.isEmpty {
                                        Text(webError)
                                            .foregroundStyle(.red)
                                            .font(.callout)
                                        Link("用浏览器搜索「\(trimmedQuery)」", destination: webFallbackURL)
                                            .font(.callout)
                                    }
                                }
                            }

                            // AI 生成（原有）
                            VStack(spacing: 12) {
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
                                }
                            }
                        }
                        .padding()
                    }
                }
            }
            .onAppear {
                dictStore.load()
            }
            .onChange(of: trimmedQuery) { _, _ in
                webSummary = nil
                webSummaryIsCached = false
                webError = ""
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

    /// 联网搜索：先查本机缓存（有缓存直接显示、不断网也能看），没有才联网；
    /// 联网到的结果自动缓存到本机，并记入历史。
    private func fetchWeb(forceRefresh: Bool = false) async {
        let word = trimmedQuery
        guard !word.isEmpty else { return }
        if !forceRefresh, let cached = webCache.cached(word) {
            webSummary = cached
            webSummaryIsCached = true
            webError = ""
            return
        }
        webError = ""
        webLoading = true
        defer { webLoading = false }
        do {
            let summary = try await WebSearchService.fetchSummary(for: word)
            webCache.save(summary)
            webSummary = summary
            webSummaryIsCached = false
            history.addWebSearch(word: word, summary: summary.extract)
        } catch WebSearchError.notFound {
            webSummary = nil
            webError = "维基百科上没有「\(word)」的词条"
        } catch {
            webSummary = nil
            webError = "联网搜索失败，请检查网络后重试"
        }
    }
}

// MARK: - 联网搜索：中文维基百科免费接口，无需 Key；结果缓存在本机

struct WebSummary: Codable, Hashable {
    let word: String
    let title: String
    let extract: String
    let pageURL: String
    let fetchedAt: Date
}

enum WebSearchError: Error {
    case notFound
    case network
}

enum WebSearchService {
    private struct WikiSummary: Decodable {
        let title: String?
        let extract: String?
        let content_urls: ContentURLs?

        struct ContentURLs: Decodable {
            let desktop: PageURL?

            struct PageURL: Decodable {
                let page: String?
            }
        }
    }

    static func fetchSummary(for word: String) async throws -> WebSummary {
        let encoded = word.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? word
        guard let url = URL(string: "https://zh.wikipedia.org/api/rest_v1/page/summary/\(encoded)") else {
            throw WebSearchError.network
        }
        var request = URLRequest(url: url)
        request.setValue("Wenqi/1.5 (iOS personal dictionary app)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw WebSearchError.network
        }
        guard http.statusCode == 200 else {
            throw WebSearchError.notFound
        }
        let decoded = try JSONDecoder().decode(WikiSummary.self, from: data)
        guard let extract = decoded.extract, !extract.isEmpty else {
            throw WebSearchError.notFound
        }
        let pageURL = decoded.content_urls?.desktop?.page ?? "https://zh.wikipedia.org/wiki/\(encoded)"
        return WebSummary(word: word, title: decoded.title ?? word, extract: extract, pageURL: pageURL, fetchedAt: Date())
    }
}

/// 联网搜索结果的本机缓存：按词存，重复搜直接显示，断网也能看
final class WebSearchCache: ObservableObject {
    static let shared = WebSearchCache()

    private let key = "webSearchCache"

    @Published private(set) var items: [String: WebSummary] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([String: WebSummary].self, from: data) {
            items = decoded
        }
    }

    func cached(_ word: String) -> WebSummary? {
        items[word]
    }

    func save(_ summary: WebSummary) {
        items[summary.word] = summary
        UserDefaults.standard.set(try? JSONEncoder().encode(items), forKey: key)
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

    static let dateFormatter: DateFormatter = {
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
