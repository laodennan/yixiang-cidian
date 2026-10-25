import Foundation

// MARK: - 词典数据模型

struct PoemLine: Codable, Hashable, Identifiable {
    let text: String
    let author: String
    let source: String

    var id: String { author + "《" + source + "》" + text }
}

struct LexiconEntry: Codable, Hashable, Identifiable {
    let word: String
    let category: String
    let poems: [PoemLine]
    let expressions: [String]

    var id: String { word }
}

struct LexiconFile: Codable {
    let entries: [LexiconEntry]
}

// MARK: - 内置词库加载

enum Lexicon {
    /// 分类：按固定顺序排列词库里已有的分类，未列出的分类排在后面
    static let categories: [String] = {
        let preferred = ["自然", "时节", "植物", "动物", "器物", "色彩", "身体", "饮食", "建筑", "节气", "神话", "情感"]
        let present = Set(entries.map { $0.category })
        return preferred.filter { present.contains($0) } + present.subtracting(preferred).sorted()
    }()

    static let entries: [LexiconEntry] = {
        guard let url = Bundle.main.url(forResource: "lexicon", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(LexiconFile.self, from: data) else {
            return []
        }
        return file.entries
    }()

    static func entries(in category: String) -> [LexiconEntry] {
        entries.filter { $0.category == category }
    }

    /// 模糊搜索：按词条名做子串匹配
    static func search(_ query: String) -> [LexiconEntry] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return entries }
        return entries.filter { $0.word.contains(q) }
    }
}

// MARK: - 收藏（UserDefaults 持久化）

final class FavoritesStore: ObservableObject {
    static let shared = FavoritesStore()

    private let key = "favoriteWords"

    @Published private(set) var words: Set<String> = []

    private init() {
        words = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
    }

    func isFavorite(_ word: String) -> Bool {
        words.contains(word)
    }

    func toggle(_ word: String) {
        if words.contains(word) {
            words.remove(word)
        } else {
            words.insert(word)
        }
        UserDefaults.standard.set(Array(words), forKey: key)
    }

    var favoriteEntries: [LexiconEntry] {
        Lexicon.entries.filter { words.contains($0.word) }
    }
}

// MARK: - 句子收藏（每句独立收藏，UserDefaults 持久化）

/// 一句被收藏的句子：词条里的古诗句、生动表达，或 AI 改写/生成的句子都可收藏。
/// 同一句话只存一条（按文本去重）；若同一句话从不同来源再次收藏，保留第一次的来源。
struct SentenceFavorite: Codable, Hashable, Identifiable {
    let text: String
    let source: String

    var id: String { text }
}

final class SentenceFavoritesStore: ObservableObject {
    static let shared = SentenceFavoritesStore()

    private let key = "favoriteSentences"

    @Published private(set) var sentences: [SentenceFavorite] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([SentenceFavorite].self, from: data) {
            sentences = decoded
        }
    }

    func isFavorite(_ text: String) -> Bool {
        sentences.contains { $0.text == text }
    }

    func add(_ text: String, source: String) {
        guard !isFavorite(text) else { return }
        sentences.append(SentenceFavorite(text: text, source: source))
        save()
    }

    func remove(_ text: String) {
        guard let index = sentences.firstIndex(where: { $0.text == text }) else { return }
        sentences.remove(at: index)
        save()
    }

    func toggle(_ text: String, source: String) {
        if isFavorite(text) {
            remove(text)
        } else {
            add(text, source: source)
        }
    }

    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(sentences), forKey: key)
    }
}

// MARK: - 我的词典：用户从 AI 结果里亲手加入的词条与词句（只存本机）

/// 一句用户从 AI 改写/生成结果里加入词典的词句：本身就是一条可查的表达。
struct CustomSentence: Codable, Hashable, Identifiable {
    let id: UUID
    let text: String
    let source: String
    let date: Date

    init(text: String, source: String) {
        self.id = UUID()
        self.text = text
        self.source = source
        self.date = Date()
    }
}

final class CustomLexiconStore: ObservableObject {
    static let shared = CustomLexiconStore()

    private let wordsKey = "customLexiconWords"
    private let sentencesKey = "customLexiconSentences"

    @Published private(set) var words: [LexiconEntry] = []
    @Published private(set) var sentences: [CustomSentence] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: wordsKey),
           let decoded = try? JSONDecoder().decode([LexiconEntry].self, from: data) {
            words = decoded
        }
        if let data = UserDefaults.standard.data(forKey: sentencesKey),
           let decoded = try? JSONDecoder().decode([CustomSentence].self, from: data) {
            sentences = decoded
        }
    }

    func isWordAdded(_ word: String) -> Bool {
        words.contains { $0.word == word }
    }

    /// AI 现场生成的整条词条存为“我的”词条：类别统一记为“我的”，与内置词库区分。
    func addWord(_ entry: LexiconEntry) {
        guard !isWordAdded(entry.word) else { return }
        words.insert(LexiconEntry(word: entry.word, category: "我的", poems: entry.poems, expressions: entry.expressions), at: 0)
        saveWords()
    }

    func removeWord(_ word: String) {
        words.removeAll { $0.word == word }
        saveWords()
    }

    func isSentenceAdded(_ text: String) -> Bool {
        sentences.contains { $0.text == text }
    }

    func addSentence(_ text: String, source: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isSentenceAdded(trimmed) else { return }
        sentences.insert(CustomSentence(text: trimmed, source: source), at: 0)
        saveSentences()
    }

    func removeSentence(_ id: UUID) {
        sentences.removeAll { $0.id == id }
        saveSentences()
    }

    private func saveWords() {
        UserDefaults.standard.set(try? JSONEncoder().encode(words), forKey: wordsKey)
    }

    private func saveSentences() {
        UserDefaults.standard.set(try? JSONEncoder().encode(sentences), forKey: sentencesKey)
    }
}

// MARK: - 跨 Tab 协作：Tab 选中态与“回填改写”请求（历史页点“再改写”时用）

final class TabSelection: ObservableObject {
    @Published var selection = 0
}

final class RewriteRefill: ObservableObject {
    @Published var text = ""
    @Published var nonce = 0

    func request(_ text: String) {
        self.text = text
        nonce += 1
    }
}
