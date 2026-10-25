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
    private let retentionKey = "historyRetentionDays"
    private let maxCount = 200

    /// 保留天数：0 = 永久
    @Published var retentionDays: Int = 30 {
        didSet {
            UserDefaults.standard.set(retentionDays, forKey: retentionKey)
            pruneExpired()
        }
    }

    @Published private(set) var entries: [HistoryEntry] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = decoded
        }
        if UserDefaults.standard.object(forKey: retentionKey) != nil {
            retentionDays = UserDefaults.standard.integer(forKey: retentionKey)
        }
        pruneExpired()
    }

    /// 删掉超过保留时间的记录；保留时间为 0（永久）时不删
    func pruneExpired() {
        guard retentionDays > 0 else { return }
        let cutoff = Date().addingTimeInterval(TimeInterval(-retentionDays * 86400))
        let kept = entries.filter { $0.date >= cutoff }
        if kept.count != entries.count {
            entries = kept
            save()
        }
    }

    func addRewrite(input: String, detail: String, results: [String]) {
        guard !results.isEmpty else { return }
        add(HistoryEntry(kind: "改写", input: input, detail: detail, results: results))
    }

    func addWebSearch(word: String, summary: String) {
        guard !summary.isEmpty else { return }
        add(HistoryEntry(kind: "联网搜索", input: word, detail: "联网搜索到的释义", results: [summary]))
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

    func remove(_ entry: HistoryEntry) {
        entries.removeAll { $0.id == entry.id }
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

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M月d日"
        return formatter
    }()

    private struct HistorySection: Identifiable {
        let title: String
        let entries: [HistoryEntry]

        var id: String { title }
    }

    /// 按天分组：今天 / 昨天 / M月d日（跨年加年份）
    private var sections: [HistorySection] {
        let calendar = Calendar.current
        let currentYear = calendar.component(.year, from: Date())
        var groups: [HistorySection] = []
        for entry in history.entries {
            let title: String
            if calendar.isDateInToday(entry.date) {
                title = "今天"
            } else if calendar.isDateInYesterday(entry.date) {
                title = "昨天"
            } else if calendar.component(.year, from: entry.date) != currentYear {
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyy年M月d日"
                title = formatter.string(from: entry.date)
            } else {
                title = Self.dayFormatter.string(from: entry.date)
            }
            if groups.last?.title == title {
                groups[groups.count - 1] = HistorySection(
                    title: title,
                    entries: groups[groups.count - 1].entries + [entry]
                )
            } else {
                groups.append(HistorySection(title: title, entries: [entry]))
            }
        }
        return groups
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(sections) { section in
                    Section(section.title) {
                        ForEach(section.entries) { entry in
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
                                    Text(Self.timeFormatter.string(from: entry.date))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .onDelete { offsets in
                            for index in offsets {
                                history.remove(section.entries[index])
                            }
                        }
                    }
                }
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("历史")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Picker("保留时间", selection: $history.retentionDays) {
                            Text("7 天").tag(7)
                            Text("30 天").tag(30)
                            Text("90 天").tag(90)
                            Text("永久").tag(0)
                        }
                    } label: {
                        Label("保留时间", systemImage: "clock")
                    }
                    if !history.entries.isEmpty {
                        Button("清空") {
                            showingClearConfirm = true
                        }
                    }
                }
            }
            .onAppear {
                history.pruneExpired()
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

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M月d日 HH:mm"
        return formatter
    }()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.kind)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(Self.dateFormatter.string(from: entry.date))
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
                            AddToLexiconButton(text: line, source: "历史·\(entry.input)")
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
