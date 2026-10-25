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
