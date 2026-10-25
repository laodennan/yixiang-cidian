import Foundation

// MARK: - 词典数据模型

struct PoemLine: Codable, Hashable, Identifiable {
    let text: String
    let author: String
    let source: String

    var id: String { author + "《" + source + "》" + text }
}

/// 容错解码放在 extension 里（不影响 memberwise 初始化）：
/// 模型偶尔漏写作者或篇名时按空字符串处理，不丢整句
extension PoemLine {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = try container.decodeIfPresent(String.self, forKey: .text) ?? ""
        author = try container.decodeIfPresent(String.self, forKey: .author) ?? ""
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? ""
    }

    private enum CodingKeys: String, CodingKey {
        case text, author, source
    }

    /// 出处文案：作者篇名都缺时为空，调用方据此决定是否显示
    var attribution: String {
        switch (author.isEmpty, source.isEmpty) {
        case (true, true): return ""
        case (false, true): return author
        case (true, false): return "《\(source)》"
        case (false, false): return "\(author) · 《\(source)》"
        }
    }
}

struct LexiconEntry: Codable, Hashable, Identifiable {
    let word: String
    let category: String
    let poems: [PoemLine]
    let expressions: [String]

    var id: String { word }
}

/// 容错解码放在 extension 里（不影响 memberwise 初始化）：
/// 模型偶尔漏写字段时按空值处理，不丢整条
extension LexiconEntry {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        word = try container.decodeIfPresent(String.self, forKey: .word) ?? ""
        category = try container.decodeIfPresent(String.self, forKey: .category) ?? ""
        poems = try container.decodeIfPresent([PoemLine].self, forKey: .poems) ?? []
        expressions = try container.decodeIfPresent([String].self, forKey: .expressions) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case word, category, poems, expressions
    }
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

// MARK: - 近义词/替换词：内置种子 + AI 生成结果的本机缓存

struct SynonymItem: Codable, Hashable {
    let word: String
    let note: String
}

struct SynonymGroup: Codable, Hashable {
    let title: String
    let items: [SynonymItem]
}

/// 内置种子：先显示种子，AI 生成只是补充。每个词 2–4 组，每组配一句话用法差别。
enum SynonymSeeds {
    static let seeds: [String: [SynonymGroup]] = [
        "生气": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "不悦", note: "比生气轻，脸上还挂得住"),
                SynonymItem(word: "恼怒", note: "已经动了气，语气变硬"),
                SynonymItem(word: "震怒", note: "气到极点，压不住"),
                SynonymItem(word: "暴怒", note: "彻底爆发，接近失控"),
            ]),
            SynonymGroup(title: "隐忍", items: [
                SynonymItem(word: "脸色沉了下来", note: "怒意藏在表情里"),
                SynonymItem(word: "指尖微微收紧", note: "身体先露了馅"),
            ]),
            SynonymGroup(title: "爆发", items: [
                SynonymItem(word: "猛地拍桌", note: "动作比话先到"),
                SynonymItem(word: "声音陡然拔高", note: "音量失控"),
            ]),
            SynonymGroup(title: "克制", items: [
                SynonymItem(word: "他没说话，只将杯子放回桌上", note: "用动作压住情绪"),
            ]),
        ],
        "高兴": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "愉悦", note: "淡淡的开心"),
                SynonymItem(word: "欣喜", note: "喜出望外"),
                SynonymItem(word: "狂喜", note: "高兴到失态"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "嘴角压不住地上扬", note: "藏不住的笑"),
                SynonymItem(word: "走路都带风", note: "整个人都轻快"),
            ]),
        ],
        "难过": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "低落", note: "情绪下沉"),
                SynonymItem(word: "难过", note: "心里发堵"),
                SynonymItem(word: "悲痛", note: "痛到极点"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "眼眶发热", note: "眼泪在打转"),
                SynonymItem(word: "喉咙像被堵住", note: "话说不出来"),
            ]),
        ],
        "害怕": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "紧张", note: "心里发紧"),
                SynonymItem(word: "害怕", note: "想躲"),
                SynonymItem(word: "恐惧", note: "腿软动不了"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "后背发凉", note: "寒意从背上起来"),
                SynonymItem(word: "手心全是汗", note: "紧张到出汗"),
            ]),
        ],
        "紧张": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "拘谨", note: "放不开"),
                SynonymItem(word: "紧张", note: "心跳加快"),
                SynonymItem(word: "焦虑", note: "坐立难安"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "反复抠手指", note: "小动作暴露紧张"),
                SynonymItem(word: "说话磕磕巴巴", note: "话都说不利索"),
            ]),
        ],
        "失望": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "失落", note: "空落落的"),
                SynonymItem(word: "失望", note: "盼头落空"),
                SynonymItem(word: "绝望", note: "不再指望"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "眼里的光暗了下去", note: "希望灭了"),
                SynonymItem(word: "肩膀塌了下来", note: "整个人垮了"),
            ]),
        ],
        "兴奋": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "激动", note: "心跳快"),
                SynonymItem(word: "兴奋", note: "停不下来"),
                SynonymItem(word: "亢奋", note: "整个人都在发光"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "说话语速飞快", note: "兴奋到刹不住"),
                SynonymItem(word: "眼睛亮得吓人", note: "眼里有光"),
            ]),
        ],
        "平静": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "平和", note: "心里没波澜"),
                SynonymItem(word: "平静", note: "波澜不惊"),
                SynonymItem(word: "淡然", note: "什么都不挂心"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "呼吸又慢又稳", note: "身体先静下来"),
                SynonymItem(word: "脸上没什么表情", note: "喜怒不形于色"),
            ]),
        ],
        "着急": [
            SynonymGroup(title: "程度", items: [
                SynonymItem(word: "着急", note: "心里冒火"),
                SynonymItem(word: "焦急", note: "坐不住"),
                SynonymItem(word: "急不可耐", note: "一秒都等不了"),
            ]),
            SynonymGroup(title: "表现", items: [
                SynonymItem(word: "原地转圈", note: "急得团团转"),
                SynonymItem(word: "不停看表", note: "时间成了敌人"),
            ]),
        ],
    ]
}

/// 近义词 AI 结果的本机缓存：按词存 JSON 字符串，与 WebSearchCache 同款做法
final class SynonymCache: ObservableObject {
    static let shared = SynonymCache()

    private let key = "synonymCache"

    @Published private(set) var items: [String: String] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            items = decoded
        }
    }

    func cached(_ word: String) -> [SynonymGroup]? {
        guard let json = items[word],
              let data = json.data(using: .utf8),
              let groups = try? JSONDecoder().decode([SynonymGroup].self, from: data),
              !groups.isEmpty else {
            return nil
        }
        return groups
    }

    func save(word: String, groups: [SynonymGroup]) {
        guard let data = try? JSONEncoder().encode(groups),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        items[word] = json
        UserDefaults.standard.set(try? JSONEncoder().encode(items), forKey: key)
    }
}

// MARK: - 场景灵感库：手写内容，词典页入口 + 搜索命中

struct SceneEntry: Hashable, Identifiable {
    let keyword: String
    let characters: [String]
    let environment: [String]
    let actions: [String]
    let sentences: [String]

    var id: String { keyword }
}

enum SceneLibrary {
    static let entries: [SceneEntry] = [
        SceneEntry(keyword: "紧张", characters: ["手指无意识地抠着衣角", "喉咙发干，咽了好几次口水"], environment: ["空气像凝住了一样", "墙上的钟走得格外响"], actions: ["反复把笔帽拔下来又按回去", "说话前先深吸一口气"], sentences: ["他站在门口，脚像生了根，半天迈不出去。", "心跳快得像要撞破胸口。"]),
        SceneEntry(keyword: "离别", characters: ["笑着挥手，转身却红了眼眶", "一句话在嘴边转了三圈还是没说出口"], environment: ["站台的风把围巾吹得乱晃", "车窗外的人影越来越小"], actions: ["把车票捏得发皱", "一步三回头"], sentences: ["车开动的那一刻，他才发现自己一直没松开攥着的手。", "有些告别，说出口就成了永远。"]),
        SceneEntry(keyword: "重逢", characters: ["愣在原地，名字卡在喉咙里", "眼睛先红了，笑却先到了"], environment: ["老街的灯还是暖黄色的", "雨刚停，空气里有泥土味"], actions: ["伸出手又缩回来，最后只拍了拍对方的肩", "一把抱住，力气大得像要确认对方是真的"], sentences: ["多年不见，他还是第一眼就认出了她。", "时间把什么都改了，只有这一声招呼没变。"]),
        SceneEntry(keyword: "深夜", characters: ["眼睛熬得发涩，还不想睡", "抱着膝盖坐在床边发呆"], environment: ["整栋楼只剩一盏灯还亮着", "窗外的路灯把影子拉得很长"], actions: ["轻手轻脚倒了杯水", "把手机翻过来扣在桌上"], sentences: ["深夜里，连冰箱的嗡鸣都显得格外清楚。", "他数着自己的呼吸，数到后来就乱了。"]),
        SceneEntry(keyword: "雨夜", characters: ["浑身湿透，却不想打伞", "睫毛上挂着水珠"], environment: ["雨点砸在铁皮棚上，噼里啪啦", "路灯在雨里晕成一团黄"], actions: ["把外套顶在头上往前跑", "踩进水洼，溅起一片泥点"], sentences: ["雨越下越大，整条街只剩下雨声。", "他在雨里站了很久，像在等一个不会来的人。"]),
        SceneEntry(keyword: "战场", characters: ["耳朵被炮声震得嗡嗡响", "手抖得几乎握不住枪"], environment: ["硝烟呛得人睁不开眼", "地面被炸出一个又一个坑"], actions: ["匍匐着往弹坑里滚", "把受伤的同伴往掩体后拖"], sentences: ["炮火停下的那几秒，安静得让人害怕。", "他趴在泥里，听见自己的心跳比枪声还响。"]),
        SceneEntry(keyword: "婚礼", characters: ["新娘的手一直在微微发抖", "新郎笑得合不拢嘴"], environment: ["花瓣铺满了红毯", "乐队刚奏起第一个音符，全场就安静了"], actions: ["交换戒指时，两人的手碰在了一起", "父亲把女儿的手交出去，半天没松开"], sentences: ["她一步步走过去，裙摆扫过的地方都带着香气。", "这一刻，所有的等待都有了交代。"]),
        SceneEntry(keyword: "葬礼", characters: ["所有人都低着头，没人说话", "有人哭出了声，又赶紧捂住嘴"], environment: ["白花摆满了灵堂", "香灰落在黑白照片的边框上"], actions: ["一鞠躬，再鞠躬，腰弯得很低", "把一枝花轻轻放在棺木上"], sentences: ["哀乐响起的时候，雨也开始下了。", "他最后看了一眼照片，转身走进了雨里。"]),
        SceneEntry(keyword: "考试", characters: ["笔尖悬在卷子上，半天落不下去", "额头上全是汗"], environment: ["考场里只听得见笔尖划纸的声音", "墙上的钟一格一格往前走"], actions: ["先把会做的题全做完，难题留到最后", "写完又划掉，改了三遍"], sentences: ["交卷铃响的那一刻，他的手还停在最后一题上。", "考完走出考场，阳光刺得他眯起了眼。"]),
        SceneEntry(keyword: "面试", characters: ["坐得笔直，手却放在膝盖上绞着", "笑容有点僵"], environment: ["会议室的空调开得很足", "对面三个人都在看他"], actions: ["回答前先停顿一秒", "说完把简历往前推了推"], sentences: ["他推开门的时候，深吸了一口气。", "那个问题问出口，全场安静了两秒。"]),
        SceneEntry(keyword: "追逐", characters: ["跑得上气不接下气，还不敢停", "回头看了一眼，又加快了脚步"], environment: ["巷子越跑越窄", "路灯一盏接一盏往后退"], actions: ["撞翻了路边的摊子也没回头", "跳过水沟，差点摔倒"], sentences: ["脚步声在身后越来越近，他咬紧了牙。", "转过街角，前方是一堵墙。"]),
        SceneEntry(keyword: "对峙", characters: ["两人都不说话，只盯着对方", "下巴绷得紧紧的"], environment: ["房间里静得能听见呼吸", "桌上的水杯还冒着热气"], actions: ["把手慢慢从口袋里抽出来", "往前走了一步，又停住"], sentences: ["谁先开口，谁就输了。", "他盯着对方的眼睛，一眨也不眨。"]),
        SceneEntry(keyword: "告白", characters: ["脸红到了耳根", "声音越来越小"], environment: ["晚风把树叶吹得沙沙响", "路灯下只有他们两个人"], actions: ["把准备好的话在心里背了三遍", "伸手递过去一封信，手在抖"], sentences: ["“我喜欢你。”说出口之后，世界突然安静了。", "她低着头，等了很久，才听见一声“好”。"]),
        SceneEntry(keyword: "背叛", characters: ["笑容僵在脸上", "眼神一下子冷了"], environment: ["刚才还热闹的房间，突然没人说话", "窗外的天阴了下来"], actions: ["把手机慢慢扣在桌上", "转身就走，没回头"], sentences: ["他一直以为最信任的人，不会骗他。", "那一刻，所有的解释都显得多余。"]),
        SceneEntry(keyword: "饥饿", characters: ["肚子叫得自己都听见了", "眼睛盯着吃的移不开"], environment: ["街边摊子的香味飘出老远", "厨房里一点动静都没有"], actions: ["不停地咽口水", "把最后一块面包掰成两半"], sentences: ["他饿得前胸贴后背，连走路都发飘。", "闻到香味的那一刻，脚就不听使唤了。"]),
        SceneEntry(keyword: "寒冷", characters: ["缩着脖子，双手插进口袋", "说话都带着白气"], environment: ["雪落在地上，一踩一个印子", "风像刀子一样往脸上刮"], actions: ["把围巾往上拉了拉，只露出眼睛", "跺着脚取暖"], sentences: ["冷得他直打哆嗦，手指都快握不住了。", "雪越下越大，整条街都白了。"]),
        SceneEntry(keyword: "酷热", characters: ["汗顺着脖子往下淌", "衣服贴在背上"], environment: ["地面被晒得发白", "蝉叫得一声比一声急"], actions: ["不停地扇风", "把冰水一口气喝了半瓶"], sentences: ["热得连空气都在发抖。", "他躲在树荫下，一步都不想挪。"]),
        SceneEntry(keyword: "迷路", characters: ["越走越慌", "拿出手机，信号只有一格"], environment: ["四周的房子长得都一样", "天慢慢黑了下来"], actions: ["原地转了两圈", "拦住路人问路"], sentences: ["他走了半小时，又回到了原地。", "巷子深处传来狗叫，他的心跳更快了。"]),
        SceneEntry(keyword: "等待", characters: ["每隔几分钟就看一次表", "坐立难安"], environment: ["车站的广播一遍遍响起", "阳光一点点往西移"], actions: ["把手机拿起又放下", "走到门口张望，又走回来"], sentences: ["等人的时间，总是过得特别慢。", "他等到天黑，那个人还是没来。"]),
        SceneEntry(keyword: "胜利", characters: ["高举双手，大喊出声", "眼泪和汗水混在一起"], environment: ["全场都在欢呼", "彩带从天上飘下来"], actions: ["和队友抱成一团", "把奖杯举过头顶"], sentences: ["终场哨响的那一刻，他跪在了地上。", "这一路的苦，在这一刻都值了。"]),
        SceneEntry(keyword: "失败", characters: ["低着头，一句话都不说", "眼眶红了，却没哭出来"], environment: ["场馆里的人陆续散去", "灯一盏盏暗下来"], actions: ["把毛巾盖在头上", "慢慢往更衣室走"], sentences: ["输了就是输了，没什么好解释的。", "他坐在空荡荡的看台上，坐了很久。"]),
        SceneEntry(keyword: "童年", characters: ["光着脚在院子里跑", "笑得没心没肺"], environment: ["夏天的傍晚，蝉鸣声不断", "院子里的老槐树遮着大片阴凉"], actions: ["踮起脚够树上的果子", "把泥巴捏成各种形状"], sentences: ["那时候，天总是很蓝，日子总是很长。", "长大后才明白，回不去的才叫童年。"]),
        SceneEntry(keyword: "故乡", characters: ["一下车就深吸了一口气", "眼眶有点热"], environment: ["村口的老树还在", "炊烟从各家屋顶升起来"], actions: ["摸了摸门口的石狮子", "推开老屋的门，吱呀一声"], sentences: ["走了这么多年，家乡的路还是记得他的脚步。", "不管走多远，胃还记得家乡的味道。"]),
        SceneEntry(keyword: "医院", characters: ["坐在走廊的椅子上，双手合十", "眼睛盯着手术室的灯"], environment: ["消毒水的味道很浓", "走廊里很安静，只有脚步声"], actions: ["来回踱步", "接到电话时手抖了一下"], sentences: ["手术室的灯灭了，所有人都站了起来。", "他在走廊里等了一夜，天亮时才敢眨眼。"]),
    ]

    static func entry(for keyword: String) -> SceneEntry? {
        entries.first { $0.keyword == keyword }
    }
}

// MARK: - 素材本：文件夹 + 条目（UserDefaults 持久化，可导出/备份）

struct MaterialFolder: Codable, Hashable, Identifiable {
    let id: UUID
    var name: String

    init(name: String) {
        self.id = UUID()
        self.name = name
    }
}

struct MaterialItem: Codable, Hashable, Identifiable {
    let id: UUID
    var folderId: UUID
    var text: String
    var note: String
    var tags: [String]
    var source: String
    var createdAt: Date

    init(folderId: UUID, text: String, note: String = "", tags: [String] = [], source: String = "") {
        self.id = UUID()
        self.folderId = folderId
        self.text = text
        self.note = note
        self.tags = tags
        self.source = source
        self.createdAt = Date()
    }
}

/// 备份文件格式：文件夹 + 条目一起导出/导入
struct MaterialBackup: Codable {
    let folders: [MaterialFolder]
    let items: [MaterialItem]
}

final class MaterialStore: ObservableObject {
    static let shared = MaterialStore()

    private let foldersKey = "materialFolders"
    private let itemsKey = "materialItems"

    @Published private(set) var folders: [MaterialFolder] = []
    @Published private(set) var items: [MaterialItem] = []

    /// 默认文件夹：第一个即默认，删文件夹时条目会移入默认文件夹
    var defaultFolder: MaterialFolder? {
        folders.first
    }

    private init() {
        if let data = UserDefaults.standard.data(forKey: foldersKey),
           let decoded = try? JSONDecoder().decode([MaterialFolder].self, from: data),
           !decoded.isEmpty {
            folders = decoded
        } else {
            folders = [MaterialFolder(name: "默认")]
        }
        if let data = UserDefaults.standard.data(forKey: itemsKey),
           let decoded = try? JSONDecoder().decode([MaterialItem].self, from: data) {
            items = decoded
        }
    }

    func addFolder(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        folders.append(MaterialFolder(name: trimmed))
        saveFolders()
    }

    func renameFolder(_ folder: MaterialFolder, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let index = folders.firstIndex(where: { $0.id == folder.id }) else {
            return
        }
        folders[index].name = trimmed
        saveFolders()
    }

    /// 删除文件夹：条目不删除，移入默认文件夹；默认文件夹本身不能删
    func deleteFolder(_ folder: MaterialFolder) {
        guard folder.id != defaultFolder?.id else { return }
        folders.removeAll { $0.id == folder.id }
        if let fallback = defaultFolder {
            for index in items.indices where items[index].folderId == folder.id {
                items[index].folderId = fallback.id
            }
        }
        saveFolders()
        saveItems()
    }

    func addItem(text: String, note: String = "", tags: [String] = [], source: String = "", folderId: UUID? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let folder = folderId.flatMap({ id in folders.first { $0.id == id } }) ?? defaultFolder else {
            return
        }
        items.insert(MaterialItem(folderId: folder.id, text: trimmed, note: note, tags: tags, source: source), at: 0)
        saveItems()
    }

    func removeItem(_ id: UUID) {
        items.removeAll { $0.id == id }
        saveItems()
    }

    func items(in folder: MaterialFolder) -> [MaterialItem] {
        items.filter { $0.folderId == folder.id }
    }

    /// 全文搜索：文本、备注、标签都算
    func search(_ query: String) -> [MaterialItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return items }
        return items.filter {
            $0.text.contains(q) || $0.note.contains(q) || $0.tags.contains { $0.contains(q) }
        }
    }

    func exportMarkdown() -> String {
        var lines = ["# 素材本", ""]
        for folder in folders {
            lines.append("## \(folder.name)")
            lines.append("")
            for item in items(in: folder) {
                lines.append("- \(item.text)")
                if !item.note.isEmpty {
                    lines.append("  - 备注：\(item.note)")
                }
                if !item.tags.isEmpty {
                    lines.append("  - 标签：\(item.tags.joined(separator: "、"))")
                }
                if !item.source.isEmpty {
                    lines.append("  - 来源：\(item.source)")
                }
            }
            lines.append("")
        }
        return lines.joined(separator: "\n")
    }

    func exportText() -> String {
        items.map { item in
            var block = item.text
            if !item.note.isEmpty {
                block += "\n备注：\(item.note)"
            }
            return block
        }.joined(separator: "\n\n")
    }

    func exportBackup() -> String {
        let backup = MaterialBackup(folders: folders, items: items)
        guard let data = try? JSONEncoder().encode(backup),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return json
    }

    /// 导入备份：按 id 合并，已存在的条目/文件夹跳过
    @discardableResult
    func importBackup(_ json: String) -> Bool {
        guard let data = json.data(using: .utf8),
              let backup = try? JSONDecoder().decode(MaterialBackup.self, from: data) else {
            return false
        }
        for folder in backup.folders where !folders.contains(where: { $0.id == folder.id }) {
            folders.append(folder)
        }
        for item in backup.items where !items.contains(where: { $0.id == item.id }) {
            items.append(item)
        }
        items.sort { $0.createdAt > $1.createdAt }
        saveFolders()
        saveItems()
        return true
    }

    private func saveFolders() {
        UserDefaults.standard.set(try? JSONEncoder().encode(folders), forKey: foldersKey)
    }

    private func saveItems() {
        UserDefaults.standard.set(try? JSONEncoder().encode(items), forKey: itemsKey)
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
