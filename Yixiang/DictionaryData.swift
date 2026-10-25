import Foundation

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

    /// 检索：含汉字时按字面子串匹配；纯字母时按拼音（或成语首字母缩写）匹配。
    func search(_ query: String, scope: DictScope) -> DictResults {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard loaded, !q.isEmpty else { return DictResults() }
        let isCJK = PinyinKey.containsCJK(q)
        let qk = PinyinKey.normalize(q)
        var results = DictResults()

        if scope == .all || scope == .chars {
            if isCJK {
                results.chars = Array(chars.filter { $0.word.contains(q) || (!$0.oldword.isEmpty && $0.oldword.contains(q)) }.prefix(50))
            } else if !qk.isEmpty {
                results.chars = Array(zip(chars, charPinyinKeys).filter { $0.1.contains(qk) }.map { $0.0 }.prefix(50))
            }
        }
        if scope == .all || scope == .words {
            if isCJK {
                results.words = Array(words.filter { $0.word.contains(q) }.prefix(50))
            } else if !qk.isEmpty {
                results.words = Array(zip(words, wordPinyinKeys).filter { $0.1.contains(qk) }.map { $0.0 }.prefix(50))
            }
        }
        if scope == .all || scope == .idioms {
            if isCJK {
                results.idioms = Array(idioms.filter { $0.word.contains(q) }.prefix(50))
            } else if !qk.isEmpty {
                results.idioms = Array(zip(idioms, idiomPinyinKeys).filter {
                    $0.1.contains(qk) || $0.0.abbreviation.lowercased().contains(qk)
                }.map { $0.0 }.prefix(50))
            }
        }
        if scope == .all || scope == .xiehouyu {
            if isCJK {
                results.xiehouyu = Array(xiehouyu.filter { $0.riddle.contains(q) || $0.answer.contains(q) }.prefix(50))
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
