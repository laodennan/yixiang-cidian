import Foundation

// MARK: - 本机日志：只存本机，供排查卡顿与失败（出现问题时从设置页复制发给助手）

struct LogEntry: Codable, Hashable, Identifiable {
    let id: UUID
    let date: Date
    let tag: String
    let message: String

    init(tag: String, message: String) {
        self.id = UUID()
        self.date = Date()
        self.tag = tag
        self.message = message
    }
}

final class AppLog: ObservableObject {
    static let shared = AppLog()

    private let key = "appLogEntries"
    private let maxCount = 500

    @Published private(set) var entries: [LogEntry] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([LogEntry].self, from: data) {
            entries = decoded
        }
    }

    /// 任何线程都可调用，内部回到主线程写；日志只存本机，不含任何密钥
    static func log(tag: String, _ message: String) {
        Task { @MainActor in
            shared.add(LogEntry(tag: tag, message: message))
        }
    }

    private func add(_ entry: LogEntry) {
        entries.insert(entry, at: 0)
        if entries.count > maxCount {
            entries = Array(entries.prefix(maxCount))
        }
        UserDefaults.standard.set(try? JSONEncoder().encode(entries), forKey: key)
    }

    func clear() {
        entries = []
        UserDefaults.standard.removeObject(forKey: key)
    }
}

// MARK: - 离线字典数据模型（数据来自 chinese-xinhua 本地包，已转为 App 内 JSON，断网可用）

struct DictChar: Codable, Hashable {
    let word: String
    let oldword: String
    let strokes: String
    let pinyin: String
    let radicals: String
    let explanation: String

    enum CodingKeys: String, CodingKey {
        case word = "w", oldword = "o", strokes = "s", pinyin = "p", radicals = "r", explanation = "e"
    }
}

struct DictWord: Codable, Hashable {
    let word: String
    let pinyin: String
    let explanation: String

    enum CodingKeys: String, CodingKey {
        case word = "w", pinyin = "p", explanation = "e"
    }
}

struct DictIdiom: Codable, Hashable {
    let word: String
    let pinyin: String
    let explanation: String
    let derivation: String
    let example: String
    let abbreviation: String

    enum CodingKeys: String, CodingKey {
        case word = "w", pinyin = "p", explanation = "e", derivation = "d", example = "x", abbreviation = "a"
    }
}

struct DictXiehouyu: Codable, Hashable {
    let riddle: String
    let answer: String

    enum CodingKeys: String, CodingKey {
        case riddle = "r", answer = "a"
    }
}

// MARK: - 拼音检索键：去声调、去空格，ü 与 v 统一成 u（如 lǜ、lv、lu 都归一为 lu）

enum PinyinKey {
    static func normalize(_ s: String) -> String {
        let mapped = s
            .replacingOccurrences(of: "ü", with: "u")
            .replacingOccurrences(of: "Ü", with: "u")
        var result = ""
        for scalar in mapped.unicodeScalars {
            switch scalar {
            case "ā", "á", "ǎ", "à": result.append("a")
            case "ē", "é", "ě", "è": result.append("e")
            case "ī", "í", "ǐ", "ì": result.append("i")
            case "ō", "ó", "ǒ", "ò": result.append("o")
            case "ū", "ú", "ǔ", "ù": result.append("u")
            case "ǖ", "ǘ", "ǚ", "ǜ": result.append("u")
            default:
                let c = Character(scalar)
                if c.isLetter || c.isNumber {
                    result.append(c)
                }
            }
        }
        return result.lowercased().replacingOccurrences(of: "v", with: "u")
    }

    static func containsCJK(_ s: String) -> Bool {
        s.unicodeScalars.contains { scalar in
            (0x4E00...0x9FFF).contains(scalar.value) || (0x3400...0x4DBF).contains(scalar.value)
        }
    }
}

// MARK: - 字典检索范围与结果

enum DictScope: String, CaseIterable {
    case all = "全部"
    case chars = "汉字"
    case words = "词语"
    case idioms = "成语"
    case xiehouyu = "歇后语"
}

struct DictResults {
    var chars: [DictChar] = []
    var words: [DictWord] = []
    var idioms: [DictIdiom] = []
    var xiehouyu: [DictXiehouyu] = []

    var isEmpty: Bool {
        chars.isEmpty && words.isEmpty && idioms.isEmpty && xiehouyu.isEmpty
    }
}

// MARK: - 离线字典仓库：首次打开字典页时在后台解码，之后常驻内存

final class DictionaryStore: ObservableObject {
    static let shared = DictionaryStore()

    @Published private(set) var loaded = false

    private var chars: [DictChar] = []
    private var words: [DictWord] = []
    private var idioms: [DictIdiom] = []
    private var xiehouyu: [DictXiehouyu] = []

    private var charPinyinKeys: [String] = []
    private var wordPinyinKeys: [String] = []
    private var idiomPinyinKeys: [String] = []

    private var loading = false

    private init() {}

    func load() {
        guard !loaded, !loading else { return }
        loading = true
        let start = Date()
        Task {
            let data = await Self.decodeAll()
            await MainActor.run {
                self.chars = data.chars
                self.words = data.words
                self.idioms = data.idioms
                self.xiehouyu = data.xiehouyu
                self.charPinyinKeys = data.chars.map { PinyinKey.normalize($0.pinyin) }
                self.wordPinyinKeys = data.words.map { PinyinKey.normalize($0.pinyin) }
                self.idiomPinyinKeys = data.idioms.map { PinyinKey.normalize($0.pinyin) }
                self.loaded = true
                let ms = Int(Date().timeIntervalSince(start) * 1000)
                AppLog.log(tag: "加载", "离线字典加载完成，耗时 \(ms)ms")
            }
        }
    }

    private struct LoadedData {
        var chars: [DictChar] = []
        var words: [DictWord] = []
        var idioms: [DictIdiom] = []
        var xiehouyu: [DictXiehouyu] = []
    }

    private static func decodeAll() async -> LoadedData {
        await Task.detached(priority: .userInitiated) {
            func decode<T: Decodable>(_ name: String, as type: T.Type) -> T? {
                guard let url = Bundle.main.url(forResource: name, withExtension: "json"),
                      let data = try? Data(contentsOf: url) else {
                    return nil
                }
                return try? JSONDecoder().decode(type, from: data)
            }
            // 词语数据分两个文件存放（单个文件超过 GitHub 网页上传的 25MB 限制）
            let wordsA = decode("dict_words_a", as: [DictWord].self) ?? []
            let wordsB = decode("dict_words_b", as: [DictWord].self) ?? []
            return LoadedData(
                chars: decode("dict_chars", as: [DictChar].self) ?? [],
                words: wordsA + wordsB,
                idioms: decode("dict_idioms", as: [DictIdiom].self) ?? [],
                xiehouyu: decode("dict_xiehouyu", as: [DictXiehouyu].self) ?? []
            )
        }.value
    }

    /// 取够即停的顺序扫描：每类最多取 50 条，够数立即停，不再扫全库。
    /// 原先每类 filter 全量扫完再截 50 条（词语 26 万条），每敲一字主线程扫 30 万次，是卡顿主因。
    private func firstMatches<S: Sequence>(_ items: S, limit: Int, where matches: (S.Element) -> Bool) -> [S.Element] {
        var out: [S.Element] = []
        out.reserveCapacity(limit)
        for item in items {
            if matches(item) {
                out.append(item)
                if out.count >= limit { break }
            }
        }
        return out
    }

    /// 检索：含汉字时按字面子串匹配；纯字母时按拼音（或成语首字母缩写）匹配。
    /// 注意：调用方应在后台线程执行（搜索本身是纯计算，不触 UI），避免堵主线程。
    func search(_ query: String, scope: DictScope) -> DictResults {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard loaded, !q.isEmpty else { return DictResults() }
        let isCJK = PinyinKey.containsCJK(q)
        let qk = PinyinKey.normalize(q)
        var results = DictResults()

        if scope == .all || scope == .chars {
            if isCJK {
                results.chars = firstMatches(chars, limit: 50) { $0.word.contains(q) || (!$0.oldword.isEmpty && $0.oldword.contains(q)) }
            } else if !qk.isEmpty {
                results.chars = firstMatches(zip(chars, charPinyinKeys), limit: 50) { $0.1.contains(qk) }.map { $0.0 }
            }
        }
        if scope == .all || scope == .words {
            if isCJK {
                results.words = firstMatches(words, limit: 50) { $0.word.contains(q) }
            } else if !qk.isEmpty {
                results.words = firstMatches(zip(words, wordPinyinKeys), limit: 50) { $0.1.contains(qk) }.map { $0.0 }
            }
        }
        if scope == .all || scope == .idioms {
            if isCJK {
                results.idioms = firstMatches(idioms, limit: 50) { $0.word.contains(q) }
            } else if !qk.isEmpty {
                results.idioms = firstMatches(zip(idioms, idiomPinyinKeys), limit: 50) {
                    $0.1.contains(qk) || $0.0.abbreviation.lowercased().contains(qk)
                }.map { $0.0 }
            }
        }
        if scope == .all || scope == .xiehouyu {
            if isCJK {
                results.xiehouyu = firstMatches(xiehouyu, limit: 50) { $0.riddle.contains(q) || $0.answer.contains(q) }
            }
        }
        return results
    }

    /// 与意象词库的联动：单字查出含该字的词条；多字查出同名词条或被包含的词条。
    static func relatedLexiconEntries(for word: String) -> [LexiconEntry] {
        if word.count == 1 {
            return Array(Lexicon.entries.filter { $0.word.contains(word) }.prefix(8))
        }
        return Array(Lexicon.entries.filter { $0.word == word || word.contains($0.word) }.prefix(8))
    }
}
