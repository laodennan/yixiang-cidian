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

// MARK: - AI 改写页：用大白话描述，得到优美的形容（接口调用走共享 AIClient，配置见设置页）

struct RewriteView: View {
    @State private var input = ""
    @State private var hint = ""
    @State private var selectedStyle: RewriteStyle = .modern
    @State private var versions: [String] = []
    @State private var errorMessage = ""
    @State private var isLoading = false
    @State private var showingSettings = false

    private var trimmedInput: String {
        input.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("用大白话描述，得到优美的形容")
                        .font(.headline)

                    TextField("用平时说话的方式描述，比如：他这个人冷冷的，不爱搭理人", text: $input, axis: .vertical)
                        .lineLimit(3...8)
                        .textFieldStyle(.roundedBorder)

                    TextField("意象提示（可选，如月亮）", text: $hint)
                        .textFieldStyle(.roundedBorder)

                    Picker("风格", selection: $selectedStyle) {
                        ForEach(RewriteStyle.allCases, id: \.self) { style in
                            Text(style.rawValue).tag(style)
                        }
                    }
                    .pickerStyle(.segmented)

                    HStack(spacing: 12) {
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

                        Button("换一批") {
                            Task { await rewrite() }
                        }
                        .buttonStyle(.bordered)
                        .disabled(trimmedInput.isEmpty || isLoading || versions.isEmpty)
                    }

                    if !errorMessage.isEmpty {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }

                    if !versions.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("形容结果")
                                .font(.headline)
                            ForEach(Array(versions.enumerated()), id: \.offset) { _, version in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(version)
                                    HStack {
                                        Spacer()
                                        SentenceStarButton(text: version, source: "AI 改写")
                                        CopyButton(text: version)
                                    }
                                }
                                .padding()
                                .background(Color.secondary.opacity(0.1))
                                .cornerRadius(12)
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
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }

    private func rewrite() async {
        errorMessage = ""
        versions = []

        let sentence = trimmedInput
        guard !sentence.isEmpty else { return }

        // 核心任务：口语描述 → 文学形容。输入是通俗口语，输出必须精准、具体、有意境、优美。
        var system = "你是一位擅长中文文学表达的助手。用户输入的是通俗口语——他用平时说话的方式描述一个意思，不需要先想好文绉绉的句子。你的任务是把这段口语描述翻译成精准、具体、有意境、优美的文学形容：精准指贴合原意、不跑题；具体指必须写出可感知的细节，如神情、动作、环境、五感、恰当的比喻，必要时化用古典意象；有意境、优美指读起来像一句值得写进文章里的好句子。禁止只做同义词替换，禁止空洞抒情；意思不能改变，只能丰富细节。"
        system += selectedStyle.styleSentence
        system += "请恰好返回 3 个不同版本，以 JSON 字符串数组的形式输出，例如 [\"版本一\", \"版本二\", \"版本三\"]，不要加任何解释，也不要加代码围栏。"
        let hintText = hint.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hintText.isEmpty {
            system += "可围绕「\(hintText)」这一意象展开。"
        }

        isLoading = true
        defer { isLoading = false }

        do {
            versions = try await AIClient.rewriteVariants(system: system, user: sentence)
            if versions.isEmpty {
                errorMessage = "接口返回了空结果，请重试"
            }
        } catch {
            errorMessage = (error as? AIError)?.errorDescription ?? "请求失败，请检查网络和接口配置"
        }
    }
}

// MARK: - 接口设置页（密钥只存本机）

struct SettingsView: View {
    @AppStorage("aiBaseURL") private var baseURL = "https://api.openai.com/v1"
    @AppStorage("aiAPIKey") private var apiKey = ""
    @AppStorage("aiModel") private var model = "gpt-4o-mini"

    @Environment(\.dismiss) private var dismiss

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

                Section {
                    Text("API Key 只保存在本机，不会上传到任何服务器（除你配置的接口外）。默认接口为 OpenAI，也可改为 DeepSeek、通义千问等任何 OpenAI 兼容接口，只需修改 Base URL 和模型名。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Button("恢复默认") {
                        baseURL = "https://api.openai.com/v1"
                        model = "gpt-4o-mini"
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
