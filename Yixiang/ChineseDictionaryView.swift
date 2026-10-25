import SwiftUI

// MARK: - 字典页：离线汉字词典（查字 / 词语 / 成语 / 歇后语，断网可用）

struct ChineseDictionaryView: View {
    @ObservedObject private var store = DictionaryStore.shared
    @State private var query = ""
    @State private var scope: DictScope = .all

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var results: DictResults {
        store.search(query, scope: scope)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("范围", selection: $scope) {
                    ForEach(DictScope.allCases, id: \.self) { item in
                        Text(item.rawValue).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)

                Group {
                    if !store.loaded {
                        VStack(spacing: 12) {
                            ProgressView()
                            Text("正在加载离线字典…")
                                .font(.callout)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if trimmedQuery.isEmpty {
                        ContentUnavailableView(
                            "离线汉字词典",
                            systemImage: "book.closed",
                            description: Text("输入汉字、词语、成语，或拼音，如：月亮 / yueliang / 风花雪月。数据已内置在 App 里，断网也能查。")
                        )
                    } else if results.isEmpty {
                        ContentUnavailableView(
                            "没有找到「\(trimmedQuery)」",
                            systemImage: "magnifyingglass",
                            description: Text("换个写法试试，或到「AI 改写」页让 AI 帮你展开")
                        )
                    } else {
                        List {
                            if !results.chars.isEmpty {
                                Section("汉字（\(results.chars.count)）") {
                                    ForEach(results.chars, id: \.word) { item in
                                        NavigationLink(destination: DictCharDetailView(item: item)) {
                                            DictRowView(
                                                title: item.word,
                                                subtitle: item.pinyin,
                                                preview: item.explanation
                                            )
                                        }
                                    }
                                }
                            }
                            if !results.words.isEmpty {
                                Section("词语（\(results.words.count)）") {
                                    ForEach(results.words, id: \.word) { item in
                                        NavigationLink(destination: DictWordDetailView(item: item)) {
                                            DictRowView(
                                                title: item.word,
                                                subtitle: item.pinyin,
                                                preview: item.explanation
                                            )
                                        }
                                    }
                                }
                            }
                            if !results.idioms.isEmpty {
                                Section("成语（\(results.idioms.count)）") {
                                    ForEach(results.idioms, id: \.word) { item in
                                        NavigationLink(destination: DictIdiomDetailView(item: item)) {
                                            DictRowView(
                                                title: item.word,
                                                subtitle: item.pinyin,
                                                preview: item.explanation
                                            )
                                        }
                                    }
                                }
                            }
                            if !results.xiehouyu.isEmpty {
                                Section("歇后语（\(results.xiehouyu.count)）") {
                                    ForEach(results.xiehouyu, id: \.riddle) { item in
                                        NavigationLink(destination: DictXiehouyuDetailView(item: item)) {
                                            DictRowView(
                                                title: item.riddle,
                                                subtitle: "歇后语",
                                                preview: item.answer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .safeAreaPadding(.bottom, 96)
            .navigationTitle("字典")
            .searchable(text: $query, prompt: "输入汉字、词语、成语或拼音")
            .onAppear {
                store.load()
            }
        }
    }
}

// MARK: - 字典结果行

private struct DictRowView: View {
    let title: String
    let subtitle: String
    let preview: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(title)
                    .font(.headline)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Text(preview)
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

// MARK: - 与意象词条的联动行

private struct RelatedLexiconSection: View {
    let word: String

    private var entries: [LexiconEntry] {
        DictionaryStore.relatedLexiconEntries(for: word)
    }

    var body: some View {
        if !entries.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("相关意象词条")
                    .font(.headline)
                ForEach(entries) { entry in
                    NavigationLink(entry.word, destination: EntryDetailView(entry: entry))
                }
            }
        }
    }
}

// MARK: - 汉字详情

struct DictCharDetailView: View {
    let item: DictChar

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    Text(item.word)
                        .font(.largeTitle.bold())
                    Spacer()
                    SentenceStarButton(text: item.explanation, source: "字典·\(item.word)")
                    CopyButton(text: item.explanation)
                }
                if !item.pinyin.isEmpty {
                    Text("拼音：\(item.pinyin)")
                }
                HStack(spacing: 20) {
                    if !item.radicals.isEmpty {
                        Text("部首：\(item.radicals)")
                    }
                    if !item.strokes.isEmpty {
                        Text("笔画：\(item.strokes)")
                    }
                    if !item.oldword.isEmpty, item.oldword != item.word {
                        Text("繁体：\(item.oldword)")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                Text(item.explanation)
                RelatedLexiconSection(word: item.word)
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle(item.word)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 词语详情

struct DictWordDetailView: View {
    let item: DictWord

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    Text(item.word)
                        .font(.largeTitle.bold())
                    Spacer()
                    SentenceStarButton(text: item.explanation, source: "字典·\(item.word)")
                    CopyButton(text: item.explanation)
                }
                if !item.pinyin.isEmpty {
                    Text("拼音：\(item.pinyin)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(item.explanation)
                RelatedLexiconSection(word: item.word)
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle(item.word)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 成语详情

struct DictIdiomDetailView: View {
    let item: DictIdiom

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    Text(item.word)
                        .font(.largeTitle.bold())
                    Spacer()
                    SentenceStarButton(text: item.explanation, source: "字典·\(item.word)")
                    CopyButton(text: item.explanation)
                }
                if !item.pinyin.isEmpty {
                    Text("拼音：\(item.pinyin)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(item.explanation)
                if !item.derivation.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("出处")
                            .font(.headline)
                        Text(item.derivation)
                    }
                }
                if !item.example.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("例句")
                            .font(.headline)
                        Text(item.example)
                    }
                }
                RelatedLexiconSection(word: item.word)
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle(item.word)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 歇后语详情

struct DictXiehouyuDetailView: View {
    let item: DictXiehouyu

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    Text(item.riddle)
                        .font(.title2.bold())
                    Spacer()
                    SentenceStarButton(text: "\(item.riddle)——\(item.answer)", source: "字典·歇后语")
                    CopyButton(text: "\(item.riddle)——\(item.answer)")
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("谜底")
                        .font(.headline)
                    Text(item.answer)
                        .font(.title3)
                }
            }
            .padding()
        }
        .safeAreaPadding(.bottom, 96)
        .navigationTitle("歇后语")
        .navigationBarTitleDisplayMode(.inline)
    }
}
