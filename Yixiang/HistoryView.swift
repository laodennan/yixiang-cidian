import SwiftUI

// MARK: - 历史记录：AI 改写与 AI 生成词条自动留档，只存本机

struct HistoryEntry: Codable, Identifiable, Hashable {
    let id: UUID
    let date: Date
    /// “改写”或“AI 生成词条”
    let kind: String
    let input: String
    let detail: String
    let results: [String]

    init(kind: String, input: String, detail: String, results: [String]) {
        self.id = UUID()
        self.date = Date()
        self.kind = kind
        self.input = input
        self.detail = detail
        self.results = results
    }
}

final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()

    private let key = "historyEntries"
    private let maxCount = 200

    @Published private(set) var entries: [HistoryEntry] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = decoded
        }
    }

    func addRewrite(input: String, detail: String, results: [String]) {
        guard !results.isEmpty else { return }
        add(HistoryEntry(kind: "改写", input: input, detail: detail, results: results))
    }

    func addGenerated(word: String, poems: [PoemLine], expressions: [String]) {
        let results = poems.map { "\($0.text) —— \($0.author)·《\($0.source)》" } + expressions
        guard !results.isEmpty else { return }
        add(HistoryEntry(kind: "AI 生成词条", input: word, detail: "为「\(word)」生成的词条", results: results))
    }

    private func add(_ entry: HistoryEntry) {
        entries.insert(entry, at: 0)
        if entries.count > maxCount {
            entries = Array(entries.prefix(maxCount))
        }
        save()
    }

    func remove(at offsets: IndexSet) {
        entries.remove(atOffsets: offsets)
        save()
    }

    func clear() {
        entries = []
        save()
    }

    private func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(entries), forKey: key)
    }
}

// MARK: - 历史页：按时间倒序，点开可查看全文、复制、收藏、回填改写

struct HistoryView: View {
    @EnvironmentObject private var history: HistoryStore
    @State private var showingClearConfirm = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        NavigationStack {
            List {
                ForEach(history.entries) { entry in
                    NavigationLink(destination: HistoryDetailView(entry: entry)) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(entry.input)
                                    .font(.headline)
                                    .lineLimit(1)
                                Spacer()
                                Text(entry.kind)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Text(entry.results.first ?? "")
                                .font(.callout)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Text(Self.dateFormatter.string(from: entry.date))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { offsets in
                    history.remove(at: offsets)
                }
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("历史")
            .toolbar {
                if !history.entries.isEmpty {
                    Button("清空") {
                        showingClearConfirm = true
                    }
                }
            }
            .alert("清空全部历史？", isPresented: $showingClearConfirm) {
                Button("清空", role: .destructive) {
                    history.clear()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("清空后不可恢复，收藏的内容不受影响。")
            }
            .overlay {
                if history.entries.isEmpty {
                    ContentUnavailableView(
                        "还没有历史记录",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("AI 改写和 AI 生成的词条都会自动出现在这里，点开可以查看全文、复制或收藏")
                    )
                }
            }
        }
    }
}

// MARK: - 历史详情

struct HistoryDetailView: View {
    let entry: HistoryEntry

    @EnvironmentObject private var tabSelection: TabSelection
    @EnvironmentObject private var refill: RewriteRefill

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.kind)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(entry.input)
                        .font(.title2.bold())
                    Text(entry.detail)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("结果")
                        .font(.headline)
                    ForEach(entry.results, id: \.self) { line in
                        HStack(alignment: .top) {
                            Text(line)
                            Spacer()
                            SentenceStarButton(text: line, source: "历史·\(entry.input)")
                            CopyButton(text: line)
                        }
                        Divider()
                    }
                }

                if entry.kind == "改写" {
                    Button("用这段描述再改写") {
                        refill.request(entry.input)
                        tabSelection.selection = 2
                    }
                    .buttonStyle(.borderedProminent)
                }

                Button("复制全部结果") {
                    UIPasteboard.general.string = entry.results.joined(separator: "\n")
                }
                .buttonStyle(.bordered)
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle("历史详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}
