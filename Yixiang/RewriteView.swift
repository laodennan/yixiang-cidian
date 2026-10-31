import SwiftUI

// MARK: - 改写风格（只改变表达方式，不改变“口语进、美文出”的核心任务）

enum RewriteStyle: String, CaseIterable {
    case classical = "古风"
    case modern = "现代抒情"
    case plain = "白描细腻"

    var styleSentence: String {
        switch self {
        case .classical:
            return "风格要求：古风——表达方式用典雅的文言词汇与古典意象，句式错落有致，如古文小品。"
        case .modern:
            return "风格要求：现代抒情——表达方式用柔和的现代散文语言，抒情但不失具体细节。"
        case .plain:
            return "风格要求：白描细腻——表达方式克制平实，少堆砌形容词，用具体动作和细节说话。"
        }
    }
}

// MARK: - 改写模式：单句（一次 3 个版本）或成段（一段连贯长文，可调字数）

enum RewriteMode: String, CaseIterable {
    case single = "单句"
    case paragraph = "成段"
}

// MARK: - 改写页分段：改写 / 对话 / 检查

enum RewriteSection: String, CaseIterable {
    case rewrite = "改写"
    case dialogue = "对话"
    case check = "检查"
}

// MARK: - 改写模式 6 档：可与单句/成段、风格三档叠加使用

enum RewritePolishMode: String, CaseIterable {
    case dedup = "去重复"
    case depattern = "去套路"
    case action = "加强动作"
    case emotion = "加强情绪"
    case webnovel = "网文润色"
    case concise = "精简废话"

    var polishSentence: String {
        switch self {
        case .dedup:
            return "改写模式要求（去重复）：删掉重复啰嗦的表达，但保留原意。"
        case .depattern:
            return "改写模式要求（去套路）：去掉常见网文套话，换更新鲜的说法。"
        case .action:
            return "改写模式要求（加强动作）：多写具体动作，少写概括。"
        case .emotion:
            return "改写模式要求（加强情绪）：把情绪写进动作和细节里，而不是直说情绪词。"
        case .webnovel:
            return "改写模式要求（网文润色）：按网文节奏润色，句子更短、更有力。"
        case .concise:
            return "改写模式要求（精简废话）：压缩到原来一半左右长度，只留有效信息。"
        }
    }
}

// MARK: - 去 AI 腔三项自查：可与风格、改写模式叠加使用，默认开启

enum AntiAICheck {
    /// 追加到改写系统提示词里的自查要求：只输出自查后的成稿，不输出自查过程。
    /// 三项来自对 AI 文本通病的研究：嵌套长句、别扭搭配、不自然比喻。
    static let promptSentence = "成稿前请做去 AI 腔三项自查，并直接按自查后的结果输出，不要输出自查过程：①拆嵌套长句：一句话里不要层层套从句，长句拆成清楚的短句，让读者一次读懂主干；②换别扭搭配：词语搭配要符合中文自然说法，每个词单看都对、连起来却没人会这么说的组合要换掉；③删不自然比喻：比喻必须贴合眼前的场景和人物，牵强、没人会用的比喻直接删掉，或改成具体细节。"
}

// MARK: - 对话助手：4 档情绪演绎 + 对话前后细节

enum DialogueEmotion: String, CaseIterable {
    case calm = "冷静克制"
    case angry = "愤怒质问"
    case disappointed = "失望隐忍"
    case calmOutsideBrokenInside = "表面平静内心崩溃"
}

struct DialogueDelivery: Codable, Hashable {
    let style: String
    let line: String
}

struct DialogueResult: Codable {
    let deliveries: [DialogueDelivery]
    let beforeAction: String
    let afterReaction: String
    let silenceDetail: String
}

// MARK: - AI 改写页：用大白话描述，得到优美的形容（接口调用走共享 AIClient，配置见设置页）

struct RewriteView: View {
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var refill: RewriteRefill

    @State private var input = ""
    @State private var hint = ""
    @State private var selectedStyle: RewriteStyle = .modern
    @State private var mode: RewriteMode = .single
    @State private var targetWords: Double = 300
    @State private var versions: [String] = []
    @State private var paragraphText = ""
    @State private var errorMessage = ""
    @State private var isLoading = false
    @State private var showingSettings = false
    @State private var section: RewriteSection = .rewrite
    @State private var polishMode: RewritePolishMode = .dedup
    @AppStorage("rewriteAntiAICheck") private var antiAICheck = true
    @State private var originalText = ""
    @State private var dialogueLine = ""
    @State private var dialogueCharacter = ""
    @State private var dialogueEmotion: DialogueEmotion = .calm
    @State private var dialogueResult: DialogueResult?
    @State private var dialogueError = ""
    @State private var dialogueLoading = false
    @State private var checkInput = ""
    @State private var checkResult: RepetitionCheck.Result?
    @FocusState private var inputFocused: Bool

    private var trimmedInput: String {
        input.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Picker("分段", selection: $section) {
                        ForEach(RewriteSection.allCases, id: \.self) { item in
                            Text(item.rawValue).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)

                    if section == .rewrite {
                        Text("用大白话描述，得到优美的形容")
                            .font(.headline)

                    TextField("用平时说话的方式描述，比如：他这个人冷冷的，不爱搭理人", text: $input, axis: .vertical)
                        .lineLimit(3...8)
                        .textFieldStyle(.roundedBorder)
                        .focused($inputFocused)

                    TextField("意象提示（可选，如月亮）", text: $hint)
                        .textFieldStyle(.roundedBorder)
                        .focused($inputFocused)

                    Picker("风格", selection: $selectedStyle) {
                        ForEach(RewriteStyle.allCases, id: \.self) { style in
                            Text(style.rawValue).tag(style)
                        }
                    }
                    .pickerStyle(.segmented)

                    Picker("模式", selection: $mode) {
                        ForEach(RewriteMode.allCases, id: \.self) { item in
                            Text(item.rawValue).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)

                    Picker("改写模式", selection: $polishMode) {
                        ForEach(RewritePolishMode.allCases, id: \.self) { item in
                            Text(item.rawValue).tag(item)
                        }
                    }
                    .pickerStyle(.menu)
                    Text("改写模式可与单句/成段、风格叠加使用")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Toggle("去 AI 腔三项自查", isOn: $antiAICheck)
                        .font(.callout)
                    Text("生成前自查：拆嵌套长句 · 换别扭搭配 · 删不自然比喻")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if mode == .paragraph {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("目标字数")
                                Spacer()
                                Text("约 \(Int(targetWords)) 字")
                                    .foregroundStyle(.secondary)
                            }
                            .font(.callout)
                            Slider(value: $targetWords, in: 100...1000, step: 50)
                            Text("左右拖动，100–1000 字，长文分段写、保持连贯")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    HStack(spacing: 12) {
                        if inputFocused {
                            Button("收起键盘") {
                                inputFocused = false
                            }
                            .buttonStyle(.bordered)
                        }

                        Button {
                            Task { await rewrite() }
                        } label: {
                            if isLoading {
                                ProgressView()
                            } else {
                                Text("改写")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(trimmedInput.isEmpty || isLoading)

                        Button(mode == .paragraph ? "换一段" : "换一批") {
                            Task { await rewrite() }
                        }
                        .buttonStyle(.bordered)
                        .disabled(trimmedInput.isEmpty || isLoading || (mode == .paragraph ? paragraphText.isEmpty : versions.isEmpty))
                    }

                    if !errorMessage.isEmpty {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }

                    if mode == .single, !versions.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("形容结果")
                                .font(.headline)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("原文")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Text(originalText)
                                Button("恢复原文") {
                                    input = originalText
                                }
                                .buttonStyle(.bordered)
                            }
                            ForEach(Array(versions.enumerated()), id: \.offset) { _, version in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(version)
                                    HStack {
                                        Spacer()
                                        AddToLexiconButton(text: version, source: "AI 改写")
                                        SentenceStarButton(text: version, source: "AI 改写")
                                        MaterialBookmarkButton(text: version, source: "AI 改写")
                                        CopyButton(text: version)
                                    }
                                }
                                .padding()
                                .background(Color.secondary.opacity(0.1))
                                .cornerRadius(12)
                            }
                        }
                    }

                    if mode == .paragraph, !paragraphText.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("成段结果（约 \(paragraphText.count) 字）")
                                .font(.headline)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("原文")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Text(originalText)
                                Button("恢复原文") {
                                    input = originalText
                                }
                                .buttonStyle(.bordered)
                            }
                            VStack(alignment: .leading, spacing: 8) {
                                Text(paragraphText)
                                HStack {
                                    Spacer()
                                    AddToLexiconButton(text: paragraphText, source: "AI 改写·成段")
                                    SentenceStarButton(text: paragraphText, source: "AI 改写·成段")
                                    MaterialBookmarkButton(text: paragraphText, source: "AI 改写·成段")
                                    CopyButton(text: paragraphText)
                                }
                            }
                            .padding()
                            .background(Color.secondary.opacity(0.1))
                            .cornerRadius(12)
                        }
                    }
                    }

                    if section == .dialogue {
                        Text("输入一句台词，得到不同情绪下的演绎")
                            .font(.headline)
                        TextField("台词（必填），比如：你到底想怎么样", text: $dialogueLine, axis: .vertical)
                            .lineLimit(2...6)
                            .textFieldStyle(.roundedBorder)
                        TextField("人物性格（可选，如内向隐忍）", text: $dialogueCharacter)
                            .textFieldStyle(.roundedBorder)
                        Picker("情绪", selection: $dialogueEmotion) {
                            ForEach(DialogueEmotion.allCases, id: \.self) { item in
                                Text(item.rawValue).tag(item)
                            }
                        }
                        .pickerStyle(.segmented)
                        Button {
                            Task { await generateDialogue() }
                        } label: {
                            if dialogueLoading {
                                ProgressView()
                            } else {
                                Text("生成演绎")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(dialogueLine.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || dialogueLoading)

                        if !dialogueError.isEmpty {
                            Text(dialogueError)
                                .foregroundStyle(.red)
                                .font(.callout)
                        }

                        if let result = dialogueResult {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("情绪演绎")
                                    .font(.headline)
                                ForEach(Array(result.deliveries.enumerated()), id: \.offset) { _, delivery in
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(delivery.style)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                        Text(delivery.line)
                                        HStack {
                                            Spacer()
                                            SentenceStarButton(text: delivery.line, source: "对话·\(delivery.style)")
                                            MaterialBookmarkButton(text: delivery.line, source: "对话·\(delivery.style)")
                                            CopyButton(text: delivery.line)
                                        }
                                    }
                                    .padding()
                                    .background(Color.secondary.opacity(0.1))
                                    .cornerRadius(12)
                                }
                                if !result.beforeAction.isEmpty {
                                    dialogueDetailRow(title: "对话前动作", text: result.beforeAction)
                                }
                                if !result.afterReaction.isEmpty {
                                    dialogueDetailRow(title: "说完后反应", text: result.afterReaction)
                                }
                                if !result.silenceDetail.isEmpty {
                                    dialogueDetailRow(title: "沉默细节", text: result.silenceDetail)
                                }
                            }
                        }
                    }

                    if section == .check {
                        Text("粘贴长文，检查疑似重复的表达")
                            .font(.headline)
                        Text("完全离线，不用 Key；启发式检查，只能看出疑似重复")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        TextField("粘贴要检查的长文", text: $checkInput, axis: .vertical)
                            .lineLimit(8...20)
                            .textFieldStyle(.roundedBorder)
                        Button("检查") {
                            checkResult = RepetitionCheck.analyze(checkInput)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(checkInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                        if let result = checkResult {
                            VStack(alignment: .leading, spacing: 16) {
                                Text("以下为疑似重复，最终由作者定夺")
                                    .font(.headline)
                                DisclosureGroup("高频 2 字词（出现 ≥3 次）") {
                                    if result.frequentWords.isEmpty {
                                        Text("没有出现 ≥3 次的 2 字词")
                                            .foregroundStyle(.secondary)
                                    } else {
                                        ForEach(Array(result.frequentWords.enumerated()), id: \.offset) { _, item in
                                            HStack {
                                                Text(item.word)
                                                Spacer()
                                                Text("\(item.count) 次")
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                    }
                                }
                                DisclosureGroup("重复句首（相同前 2 字开头 ≥3 句）") {
                                    if result.repeatedOpenings.isEmpty {
                                        Text("没有重复的句首")
                                            .foregroundStyle(.secondary)
                                    } else {
                                        ForEach(Array(result.repeatedOpenings.enumerated()), id: \.offset) { _, group in
                                            DisclosureGroup("“\(group.opening)”开头 · \(group.sentences.count) 句") {
                                                ForEach(group.sentences, id: \.self) { sentence in
                                                    Text(sentence)
                                                        .font(.callout)
                                                }
                                            }
                                        }
                                    }
                                }
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("形容词密度")
                                        .font(.headline)
                                    Text("命中 \(result.adjectiveHits) 次 / 全文 \(result.totalChars) 字，密度 \(String(format: "%.1f", result.adjectiveDensity))%\(result.adjectiveDensity > 8 ? "（偏高）" : "")")
                                }
                                DisclosureGroup("相邻段落意思相近（字符集合相似度 ≥0.6）") {
                                    if result.similarParagraphs.isEmpty {
                                        Text("没有意思相近的相邻段落")
                                            .foregroundStyle(.secondary)
                                    } else {
                                        ForEach(Array(result.similarParagraphs.enumerated()), id: \.offset) { _, pair in
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text("第 \(pair.first) 段与第 \(pair.second) 段，相似度 \(String(format: "%.0f", pair.similarity * 100))%")
                                                Text(pair.preview)
                                                    .font(.callout)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding()
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("AI 改写")
            .toolbar {
                Button("设置") { showingSettings = true }
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("收起键盘") { inputFocused = false }
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
            .onChange(of: refill.nonce) { _, _ in
                input = refill.text
            }
        }
    }

    private func rewrite() async {
        errorMessage = ""
        versions = []
        paragraphText = ""

        let sentence = trimmedInput
        guard !sentence.isEmpty else { return }
        // 原文对照：记下这次生成时的输入，恢复原文按钮用它
        originalText = sentence

        // 核心任务：口语描述 → 文学形容。输入是通俗口语，输出必须精准、具体、有意境、优美。
        var system = "你是一位擅长中文文学表达的助手。用户输入的是通俗口语——他用平时说话的方式描述一个意思，不需要先想好文绉绉的句子。你的任务是把这段口语描述翻译成精准、具体、有意境、优美的文学形容：精准指贴合原意、不跑题；具体指必须写出可感知的细节，如神情、动作、环境、五感、恰当的比喻，必要时化用古典意象；有意境、优美指读起来像一句值得写进文章里的好句子。禁止只做同义词替换，禁止空洞抒情；意思不能改变，只能丰富细节。"
        system += selectedStyle.styleSentence
        system += polishMode.polishSentence
        if antiAICheck {
            system += AntiAICheck.promptSentence
        }
        let hintText = hint.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hintText.isEmpty {
            system += "可围绕「\(hintText)」这一意象展开。"
        }

        isLoading = true
        defer { isLoading = false }

        do {
            if mode == .paragraph {
                system += "请写成一段连贯的长文，可分 2–4 个自然段，约 \(Int(targetWords)) 字，起承转合，前后承接，可直接用在文章里。只返回正文，不要任何解释，也不要加代码围栏。"
                let text = try await AIClient.chat(system: system, user: sentence)
                paragraphText = text
                history.addRewrite(
                    input: sentence,
                    detail: "\(selectedStyle.rawValue)·\(polishMode.rawValue)\(antiAICheck ? "·去AI腔自查" : "")·成段·约 \(Int(targetWords)) 字",
                    results: [text]
                )
            } else {
                system += "请恰好返回 3 个不同版本，以 JSON 字符串数组的形式输出，例如 [\"版本一\", \"版本二\", \"版本三\"]，不要加任何解释，也不要加代码围栏。"
                versions = try await AIClient.rewriteVariants(system: system, user: sentence)
                if versions.isEmpty {
                    errorMessage = "接口返回了空结果，请重试"
                } else {
                    history.addRewrite(
                        input: sentence,
                        detail: "\(selectedStyle.rawValue)·\(polishMode.rawValue)\(antiAICheck ? "·去AI腔自查" : "")·单句·3 个版本",
                        results: versions
                    )
                }
            }
        } catch {
            errorMessage = (error as? AIError)?.errorDescription ?? "请求失败，请检查网络和接口配置"
        }
    }

    private func generateDialogue() async {
        dialogueError = ""
        dialogueResult = nil
        let line = dialogueLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return }
        let character = dialogueCharacter.trimmingCharacters(in: .whitespacesAndNewlines)
        var system = "你是一位擅长人物对话描写的助手。用户给一句台词，你只返回一个 JSON 对象，格式固定为：{\"deliveries\":[{\"style\":\"情绪档\",\"line\":\"台词\"}],\"beforeAction\":\"对话前动作\",\"afterReaction\":\"说完后反应\",\"silenceDetail\":\"沉默细节\"}。要求：deliveries 恰好 4 条，情绪档依次为冷静克制、愤怒质问、失望隐忍、表面平静内心崩溃，每条是同一句台词在该情绪下的演绎；对话前动作、说完后反应、沉默细节各写一句具体细节。不要解释，不要加代码围栏。"
        if !character.isEmpty {
            system += "人物性格：\(character)。"
        }
        system += "人物当前情绪：\(dialogueEmotion.rawValue)。"
        dialogueLoading = true
        defer { dialogueLoading = false }
        do {
            let raw = try await AIClient.chat(system: system, user: line)
            let result = parseDialogue(raw)
            dialogueResult = result
            var results: [String] = result.deliveries.map { "\($0.style)：\($0.line)" }
            if !result.beforeAction.isEmpty {
                results.append("对话前动作：\(result.beforeAction)")
            }
            if !result.afterReaction.isEmpty {
                results.append("说完后反应：\(result.afterReaction)")
            }
            if !result.silenceDetail.isEmpty {
                results.append("沉默细节：\(result.silenceDetail)")
            }
            history.addRewrite(input: line, detail: "对话·\(dialogueEmotion.rawValue)", results: results)
        } catch {
            dialogueError = (error as? AIError)?.errorDescription ?? "请求失败，请检查网络和接口配置"
        }
    }

    /// 对话 JSON 解析：失败时把原始文本整体显示为一个版本
    private func parseDialogue(_ raw: String) -> DialogueResult {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("```") {
            if let firstNewline = text.firstIndex(of: "\n") {
                text = String(text[text.index(after: firstNewline)...])
            }
            if text.hasSuffix("```") {
                text = String(text.dropLast(3))
            }
            text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let data = text.data(using: .utf8),
           let decoded = try? JSONDecoder().decode(DialogueResult.self, from: data) {
            return decoded
        }
        return DialogueResult(deliveries: [DialogueDelivery(style: "原文", line: text)], beforeAction: "", afterReaction: "", silenceDetail: "")
    }

    private func dialogueDetailRow(title: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(text)
            HStack {
                Spacer()
                SentenceStarButton(text: text, source: "对话·\(title)")
                MaterialBookmarkButton(text: text, source: "对话·\(title)")
                CopyButton(text: text)
            }
        }
        .padding()
        .background(Color.secondary.opacity(0.1))
        .cornerRadius(12)
    }
}

// MARK: - 重复表达检查器：纯启发式、完全离线（App 里没有中文分词器）

enum RepetitionCheck {
    struct Result {
        let totalChars: Int
        let frequentWords: [(word: String, count: Int)]
        let repeatedOpenings: [(opening: String, sentences: [String])]
        let adjectiveHits: Int
        let adjectiveDensity: Double
        let similarParagraphs: [(first: Int, second: Int, similarity: Double, preview: String)]
    }

    /// 功能字：含这些字的 2 字组合不统计（虚词组合没有意义）
    private static let functionChars: Set<Character> = ["的", "了", "在", "是", "有", "和", "就", "都", "也", "不", "还", "这", "那", "他", "她", "它", "我", "你", "您", "们", "个", "上", "下", "中", "大", "小"]

    private static let adjectives: [String] = ["美丽", "漂亮", "微微", "轻轻", "缓缓", "突然", "非常", "十分", "特别", "明亮", "昏暗", "安静", "热闹", "孤独", "紧张", "疲惫", "坚定", "犹豫", "温柔", "冰冷", "火热", "巨大", "渺小", "迅速", "缓慢", "悄悄", "默默", "深深", "淡淡", "浓浓", "远远", "高高", "低低", "甜甜", "苦苦", "重重", "急急", "慢慢", "偷偷", "静静", "空空", "满满", "柔柔", "硬硬", "冷冷", "暖暖", "喜悦", "悲伤", "愤怒", "恐惧", "惊讶", "好奇", "害羞", "自豪", "失望", "满足", "空虚", "充实", "华丽", "朴素", "优雅", "粗糙", "细腻", "清晰", "模糊", "遥远", "亲近", "陌生", "熟悉"]

    private static func isCJK(_ ch: Character) -> Bool {
        guard let scalar = ch.unicodeScalars.first else { return false }
        return (0x4E00...0x9FFF).contains(scalar.value) || (0x3400...0x4DBF).contains(scalar.value)
    }

    static func analyze(_ text: String) -> Result {
        let chars = Array(text)

        // (a) 高频 2 字词：所有汉字 2 字窗口，含功能字的组合排除，出现 ≥3 次的列出
        var counts: [String: Int] = [:]
        if chars.count >= 2 {
            for i in 0..<(chars.count - 1) {
                let a = chars[i]
                let b = chars[i + 1]
                guard isCJK(a), isCJK(b) else { continue }
                if functionChars.contains(a) || functionChars.contains(b) { continue }
                counts[String([a, b]), default: 0] += 1
            }
        }
        let frequent = counts
            .filter { $0.value >= 3 }
            .sorted { $0.value > $1.value }
            .map { (word: $0.key, count: $0.value) }

        // (b) 重复句首：按 。！？；切分句子，相同前 2 字开头的句子 ≥3 句的列出
        let separators: Set<Character> = ["。", "！", "？", "；"]
        var sentences: [String] = []
        var current = ""
        for ch in text {
            current.append(ch)
            if separators.contains(ch) {
                let trimmed = current.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    sentences.append(trimmed)
                }
                current = ""
            }
        }
        let tail = current.trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty {
            sentences.append(tail)
        }
        var openingGroups: [String: [String]] = [:]
        for sentence in sentences {
            let stripped = sentence.trimmingCharacters(in: .whitespacesAndNewlines)
            guard stripped.count >= 2 else { continue }
            let opening = String(stripped.prefix(2))
            openingGroups[opening, default: []].append(sentence)
        }
        let repeated = openingGroups
            .filter { $0.value.count >= 3 }
            .sorted { $0.value.count > $1.value.count }
            .map { (opening: $0.key, sentences: $0.value) }

        // (c) 形容词密度：内置形容词表命中数 / 全文总字数（去空白）
        var hits = 0
        for adjective in adjectives {
            var searchFrom = text.startIndex
            while let range = text.range(of: adjective, range: searchFrom..<text.endIndex) {
                hits += 1
                searchFrom = range.upperBound
            }
        }
        let total = text.filter { !$0.isWhitespace }.count
        let density = total > 0 ? Double(hits) / Double(total) * 100 : 0

        // (d) 相邻段落意思相近：按换行分段，相邻两段按字符集合 Jaccard 相似度 ≥0.6 的列出
        let paragraphs = text
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        var similar: [(first: Int, second: Int, similarity: Double, preview: String)] = []
        if paragraphs.count >= 2 {
            for i in 0..<(paragraphs.count - 1) {
                let a = Set(paragraphs[i])
                let b = Set(paragraphs[i + 1])
                let union = a.union(b).count
                guard union > 0 else { continue }
                let similarity = Double(a.intersection(b).count) / Double(union)
                if similarity >= 0.6 {
                    similar.append((first: i + 1, second: i + 2, similarity: similarity, preview: String(paragraphs[i].prefix(30))))
                }
            }
        }

        return Result(
            totalChars: total,
            frequentWords: frequent,
            repeatedOpenings: repeated,
            adjectiveHits: hits,
            adjectiveDensity: density,
            similarParagraphs: similar
        )
    }
}

// MARK: - 日志查看页（本机日志，供排查卡顿与失败）

struct LogView: View {
    @ObservedObject private var appLog = AppLog.shared
    @State private var showingClearConfirm = false

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    private var logText: String {
        appLog.entries.map { "\(Self.timeFormatter.string(from: $0.date)) [\($0.tag)] \($0.message)" }.joined(separator: "\n")
    }

    var body: some View {
        List {
            if appLog.entries.isEmpty {
                ContentUnavailableView(
                    "还没有日志",
                    systemImage: "doc.text",
                    description: Text("搜索、联网、AI 生成的耗时与失败都会记在这里")
                )
            } else {
                ForEach(appLog.entries) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(Self.timeFormatter.string(from: entry.date))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(entry.tag)
                                .font(.caption)
                                .foregroundStyle(entry.tag.contains("失败") || entry.tag.contains("慢") ? .red : .secondary)
                        }
                        Text(entry.message)
                            .font(.callout)
                    }
                }
            }
        }
        .navigationTitle("日志")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !appLog.entries.isEmpty {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    ShareLink(item: logText)
                    Button("清空") {
                        showingClearConfirm = true
                    }
                }
            }
        }
        .alert("清空全部日志？", isPresented: $showingClearConfirm) {
            Button("清空", role: .destructive) {
                appLog.clear()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("清空后不可恢复。")
        }
    }
}

// MARK: - 接口设置页（密钥只存本机）

struct SettingsView: View {
    @AppStorage("aiBaseURL") private var baseURL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    @AppStorage("aiAPIKey") private var apiKey = ""
    @AppStorage("aiModel") private var model = "qwen-turbo"
    @AppStorage("webSearchEnabled") private var webSearchEnabled = false
    @ObservedObject private var webCache = WebSearchCache.shared
    @ObservedObject private var appLog = AppLog.shared

    @Environment(\.dismiss) private var dismiss

    private var appVersion: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return "\(short) (\(build))"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("接口配置") {
                    TextField("Base URL", text: $baseURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("API Key", text: $apiKey)
                    TextField("模型名", text: $model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("维基百科搜索") {
                    Toggle("维基百科搜索", isOn: $webSearchEnabled)
                    Text("默认关闭；打开后，词典搜索页会显示维基百科摘要。维基百科在部分网络下无法直连")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Text("API Key 只保存在本机，不会上传到任何服务器（除你配置的接口外）。默认接口为通义千问（阿里云百炼），只需填写自己的 API Key；也可改为 OpenAI、DeepSeek 等任何 OpenAI 兼容接口，只需修改 Base URL 和模型名。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("联网搜索缓存") {
                    HStack {
                        Text("已缓存")
                        Spacer()
                        Text("\(webCache.items.count) 条")
                            .foregroundStyle(.secondary)
                    }
                    Button("清除联网搜索缓存", role: .destructive) {
                        webCache.clear()
                    }
                    .disabled(webCache.items.isEmpty)
                }

                Section("日志") {
                    NavigationLink(destination: LogView()) {
                        HStack {
                            Text("查看日志")
                            Spacer()
                            Text("\(appLog.entries.count) 条")
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text("日志只存在本机，不含密钥。出现卡顿或问题时，从这里复制日志发给 nana 排查。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("关于") {
                    HStack {
                        Text("版本")
                        Spacer()
                        Text("文栖 \(appVersion)")
                            .foregroundStyle(.secondary)
                    }
                    Text("词库与字典数据均保存在本机；AI 功能使用你自己配置的接口，密钥只存本机。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button("恢复默认") {
                        baseURL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                        model = "qwen-turbo"
                    }
                }
            }
            .navigationTitle("设置")
            .toolbar {
                Button("完成") { dismiss() }
            }
        }
    }
}
