import Foundation

// MARK: - 用户配置的 OpenAI 兼容接口（密钥只存本机 UserDefaults）

enum AISettings {
    private static let baseURLKey = "aiBaseURL"
    private static let apiKeyKey = "aiAPIKey"
    private static let modelKey = "aiModel"

    /// 默认接口为通义千问（阿里云百炼）：模板内置，Key 由使用者自己填
    static let defaultBaseURL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    static let defaultModel = "qwen-turbo"

    static var baseURL: String {
        UserDefaults.standard.string(forKey: baseURLKey) ?? defaultBaseURL
    }

    static var apiKey: String {
        UserDefaults.standard.string(forKey: apiKeyKey) ?? ""
    }

    static var model: String {
        UserDefaults.standard.string(forKey: modelKey) ?? defaultModel
    }

    static var hasKey: Bool {
        !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

// MARK: - 错误（中文提示）

enum AIError: Error, LocalizedError {
    case missingKey
    case badBaseURL
    case httpStatus(Int)
    case emptyResult
    case invalidResponse
    case underlying(Error)

    var errorDescription: String? {
        switch self {
        case .missingKey:
            return "请先在设置里填写 API Key"
        case .badBaseURL:
            return "Base URL 不正确，请在设置里检查"
        case .httpStatus(let code):
            return "请求失败，状态码 \(code)，请检查 Base URL、API Key 和模型名是否正确"
        case .emptyResult:
            return "接口返回了空结果，请重试"
        case .invalidResponse:
            return "接口返回的内容无法解析，请重试"
        case .underlying(let error):
            return "请求失败：\(error.localizedDescription)，请检查网络和接口配置"
        }
    }
}

// MARK: - OpenAI 兼容接口的请求 / 响应模型

private struct ChatMessage: Codable {
    let role: String
    let content: String
}

private struct ChatRequest: Codable {
    let model: String
    let messages: [ChatMessage]
}

private struct ChatChoice: Codable {
    let message: ChatMessage
}

private struct ChatResponse: Codable {
    let choices: [ChatChoice]
}

// MARK: - 共享 AI 客户端

enum AIClient {
    /// 通用对话：返回模型输出的文本（已去首尾空白）
    static func chat(system: String, user: String) async throws -> String {
        guard AISettings.hasKey else {
            throw AIError.missingKey
        }

        var base = AISettings.baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") {
            base.removeLast()
        }
        guard let url = URL(string: base + "/chat/completions") else {
            throw AIError.badBaseURL
        }

        let requestBody = ChatRequest(
            model: AISettings.model.trimmingCharacters(in: .whitespacesAndNewlines),
            messages: [
                ChatMessage(role: "system", content: system),
                ChatMessage(role: "user", content: user),
            ]
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(
            "Bearer \(AISettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines))",
            forHTTPHeaderField: "Authorization"
        )
        do {
            request.httpBody = try JSONEncoder().encode(requestBody)
        } catch {
            throw AIError.underlying(error)
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw AIError.invalidResponse
            }
            guard (200...299).contains(http.statusCode) else {
                throw AIError.httpStatus(http.statusCode)
            }
            let decoded = try JSONDecoder().decode(ChatResponse.self, from: data)
            guard let content = decoded.choices.first?.message.content
                .trimmingCharacters(in: .whitespacesAndNewlines),
                !content.isEmpty
            else {
                throw AIError.emptyResult
            }
            return content
        } catch let error as AIError {
            throw error
        } catch {
            throw AIError.underlying(error)
        }
    }

    /// 为离线词库里没有的意象词生成一则词条（古诗句 + 生动表达），
    /// 返回结构与内置词条一致，可直接用词条详情页展示。
    static func generateEntry(for word: String) async throws -> LexiconEntry {
        let system = """
        你是中文文学意象词典的编纂者。用户给一个意象词，你只返回一个 JSON 对象，格式固定为：
        {"word":"词","category":"分类","poems":[{"text":"诗句","author":"作者","source":"篇名"}],"expressions":["生动表达", "..."]}
        要求：古诗句只写真实存在、作者与篇名无误的名句（2-4句），不确定的不要写；生动表达写3-5句现代中文，要具体、有动词、有质感。只返回 JSON，不要任何解释，也不要加代码围栏。
        """
        let raw = try await chat(system: system, user: word)

        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("```") {
            // 去掉可能的代码围栏
            if let firstNewline = text.firstIndex(of: "\n") {
                text = String(text[text.index(after: firstNewline)...])
            }
            if text.hasSuffix("```") {
                text = String(text.dropLast(3))
            }
            text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        guard let data = text.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(LexiconEntry.self, from: data)
        else {
            throw AIError.invalidResponse
        }
        // 容错：过滤诗句文本为空的条目；词名缺失时用查询词补；
        // 古诗或生动表达有一边有内容即接受（原来要求两边都非空，
        // 模型稍有缺失就整条丢弃，是“你好”两次解析失败的根因）
        let poems = decoded.poems.filter { !$0.text.isEmpty }
        let entry = LexiconEntry(
            word: decoded.word.isEmpty ? word : decoded.word,
            category: decoded.category,
            poems: poems,
            expressions: decoded.expressions
        )
        guard !entry.poems.isEmpty || !entry.expressions.isEmpty else {
            throw AIError.invalidResponse
        }
        return entry
    }

    /// 改写：一次调用返回恰好 3 个扩写版本。
    /// 模型被要求返回 JSON 字符串数组；解析时依次尝试：
    /// 1) 去围栏后按 JSON 数组解码；2) 按行切分并去掉行首编号；3) 整体作为一个版本。
    static func rewriteVariants(system: String, user: String) async throws -> [String] {
        let raw = try await chat(system: system, user: user)
        return parseVariants(raw)
    }

    static func parseVariants(_ raw: String) -> [String] {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // 去掉可能的代码围栏（```json ... ``` 或 ``` ... ```）
        if text.hasPrefix("```") {
            if let firstNewline = text.firstIndex(of: "\n") {
                text = String(text[text.index(after: firstNewline)...])
            }
            if text.hasSuffix("```") {
                text = String(text.dropLast(3))
            }
            text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        // 1) 干净的 JSON 数组（或去围栏后的 JSON 数组）
        if let data = text.data(using: .utf8),
           let array = try? JSONDecoder().decode([String].self, from: data) {
            let cleaned = array
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            if !cleaned.isEmpty {
                return Array(cleaned.prefix(3))
            }
        }

        // 2) 按行切分：去掉行首编号（如 1. / 1、/ ① / 一、），去掉空行
        let lines = text.components(separatedBy: .newlines)
            .map { stripVariantNumbering($0) }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if lines.count > 1 {
            return Array(lines.prefix(3))
        }

        // 3) 兜底：整体作为一个版本（空文本则返回空数组，由调用方提示重试）
        return text.isEmpty ? [] : [text]
    }

    /// 去掉一行开头的编号：1. / 1、/ 1) / （1）/ ① / 一、，以及 - / • 项目符号
    private static func stripVariantNumbering(_ line: String) -> String {
        var s = line.trimmingCharacters(in: .whitespaces)
        if let range = s.range(
            of: #"^(\d+[.、)）]|[①②③④⑤⑥⑦⑧⑨⑩]|[一二三四五六七八九十]+[、.．]|[（(]\d+[）)])\s*"#,
            options: .regularExpression
        ) {
            s = String(s[range.upperBound...])
        }
        if s.hasPrefix("- ") {
            s = String(s.dropFirst(2))
        }
        if s.hasPrefix("• ") {
            s = String(s.dropFirst(2))
        }
        return s
    }
}
