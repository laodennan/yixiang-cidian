import SwiftUI

// MARK: - AI 改写页（接口调用走共享 AIClient，配置见设置页）

struct RewriteView: View {
    @State private var input = ""
    @State private var hint = ""
    @State private var result = ""
    @State private var errorMessage = ""
    @State private var isLoading = false
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("输入一句话，改写得更生动")
                        .font(.headline)

                    TextField("输入你想改写的句子", text: $input, axis: .vertical)
                        .lineLimit(3...8)
                        .textFieldStyle(.roundedBorder)

                    TextField("意象提示（可选，如月亮）", text: $hint)
                        .textFieldStyle(.roundedBorder)

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
                    .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isLoading)

                    if !errorMessage.isEmpty {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.callout)
                    }

                    if !result.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("改写结果")
                                .font(.headline)
                            Text(result)
                            HStack {
                                Spacer()
                                CopyButton(text: result)
                            }
                        }
                        .padding()
                        .background(Color.secondary.opacity(0.1))
                        .cornerRadius(12)
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
        result = ""

        let sentence = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sentence.isEmpty else { return }

        var system = "你是一位擅长中文文学表达的助手。请把用户给的句子改写得更生动、更有意象，可适度化用古典意象，但不要改变原意。只返回改写后的句子，不要加任何解释。"
        let hintText = hint.trimmingCharacters(in: .whitespacesAndNewlines)
        if !hintText.isEmpty {
            system += "可围绕「\(hintText)」这一意象展开。"
        }

        isLoading = true
        defer { isLoading = false }

        do {
            result = try await AIClient.chat(system: system, user: sentence)
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
