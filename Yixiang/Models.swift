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
    static let categories = ["自然", "时节", "情感"]

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
