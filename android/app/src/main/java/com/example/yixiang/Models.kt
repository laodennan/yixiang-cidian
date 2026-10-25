package com.example.yixiang

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

// ---------- 词典数据模型（与 iOS 版共用同一份 lexicon.json） ----------

data class PoemLine(
    val text: String,
    val author: String,
    val source: String
)

data class LexiconEntry(
    val word: String,
    val category: String,
    val poems: List<PoemLine>,
    val expressions: List<String>,
    // AI 现场生成的词条不进内置词库，因此不提供收藏，只展示内容
    val isGenerated: Boolean = false
)

// ---------- 内置词库加载 ----------

object Lexicon {
    private val preferredCategories = listOf(
        "自然", "时节", "植物", "动物", "器物", "色彩", "身体", "饮食", "建筑", "节气", "神话", "情感"
    )

    var entries: List<LexiconEntry> = emptyList()
        private set

    fun load(context: Context) {
        if (entries.isNotEmpty()) return
        try {
            val text = context.assets.open("lexicon.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("entries")
            val list = mutableListOf<LexiconEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val poems = mutableListOf<PoemLine>()
                val poemArr = obj.optJSONArray("poems") ?: JSONArray()
                for (j in 0 until poemArr.length()) {
                    val p = poemArr.getJSONObject(j)
                    poems.add(
                        PoemLine(
                            text = p.optString("text"),
                            author = p.optString("author"),
                            source = p.optString("source")
                        )
                    )
                }
                val expressions = mutableListOf<String>()
                val expArr = obj.optJSONArray("expressions") ?: JSONArray()
                for (j in 0 until expArr.length()) {
                    expressions.add(expArr.optString(j))
                }
                list.add(
                    LexiconEntry(
                        word = obj.optString("word"),
                        category = obj.optString("category"),
                        poems = poems,
                        expressions = expressions
                    )
                )
            }
            entries = list
        } catch (e: Exception) {
            entries = emptyList()
        }
    }

    /** 分类：按固定顺序排列词库里已有的分类，未列出的分类排在后面 */
    val categories: List<String>
        get() {
            val present = entries.map { it.category }.toSet()
            val ordered = preferredCategories.filter { present.contains(it) }
            val rest = present.filter { !preferredCategories.contains(it) }.sorted()
            return ordered + rest
        }

    fun entriesIn(category: String): List<LexiconEntry> {
        return entries.filter { it.category == category }
    }

    /** 模糊搜索：按词条名做子串匹配；空查询返回全部 */
    fun search(query: String): List<LexiconEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { it.word.contains(q) }
    }
}

// ---------- 收藏（SharedPreferences 持久化） ----------

object FavoritesStore {
    private const val PREFS = "favorites"
    private const val KEY = "favoriteWords"

    private var prefs: android.content.SharedPreferences? = null

    /** 每次收藏变化时递增，驱动 Compose 界面刷新 */
    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun words(): MutableSet<String> {
        return prefs?.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun isFavorite(word: String): Boolean {
        return words().contains(word)
    }

    fun toggle(word: String) {
        val set = words()
        if (set.contains(word)) {
            set.remove(word)
        } else {
            set.add(word)
        }
        prefs?.edit()?.putStringSet(KEY, set)?.apply()
        version++
    }

    fun favoriteEntries(): List<LexiconEntry> {
        val set = words()
        return Lexicon.entries.filter { set.contains(it.word) }
    }
}

// ---------- 接口设置（SharedPreferences 持久化，密钥只存本机） ----------

object SettingsStore {
    private const val PREFS = "ai_settings"
    private const val KEY_BASE_URL = "aiBaseURL"
    private const val KEY_API_KEY = "aiAPIKey"
    private const val KEY_MODEL = "aiModel"

    // 模板内置：默认接口为通义千问（阿里云百炼），Key 栏留空由使用者自己填
    const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val DEFAULT_MODEL = "qwen-turbo"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var baseUrl: String
        get() = prefs?.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs?.edit()?.putString(KEY_BASE_URL, value)?.apply()
        }

    var apiKey: String
        get() = prefs?.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_API_KEY, value)?.apply()
        }

    var model: String
        get() = prefs?.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs?.edit()?.putString(KEY_MODEL, value)?.apply()
        }

    fun hasKey(): Boolean = apiKey.trim().isNotEmpty()

    fun resetDefaults() {
        baseUrl = DEFAULT_BASE_URL
        model = DEFAULT_MODEL
    }
}

// ---------- 句子收藏（SharedPreferences 持久化，每句独立收藏） ----------

data class SentenceFavorite(
    val text: String,
    val source: String
)

object SentenceFavoritesStore {
    private const val PREFS = "sentence_favorites"
    private const val KEY = "favoriteSentences"

    private var prefs: android.content.SharedPreferences? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun loadList(): MutableList<SentenceFavorite> {
        val list = mutableListOf<SentenceFavorite>()
        val raw = prefs?.getString(KEY, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SentenceFavorite(
                        text = obj.optString("t"),
                        source = obj.optString("s")
                    )
                )
            }
        } catch (e: Exception) {
            // 损坏的数据直接丢弃
        }
        return list
    }

    private fun saveList(list: List<SentenceFavorite>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("t", item.text)
                    put("s", item.source)
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        version++
    }

    fun isFavorite(text: String): Boolean {
        return loadList().any { it.text == text }
    }

    fun toggle(text: String, source: String) {
        val list = loadList()
        val existing = list.firstOrNull { it.text == text }
        if (existing != null) {
            list.remove(existing)
        } else {
            list.add(0, SentenceFavorite(text = text, source = source))
        }
        saveList(list)
    }

    fun sentences(): List<SentenceFavorite> = loadList()
}

// ---------- 我的词典（AI 结果亲手加入的，永久保存，不受历史保留影响） ----------

data class CustomSentence(
    val id: String,
    val text: String,
    val source: String,
    val date: Long
)

object CustomLexiconStore {
    private const val PREFS = "custom_lexicon"
    private const val KEY_WORDS = "customLexiconWords"
    private const val KEY_SENTENCES = "customLexiconSentences"

    private var prefs: android.content.SharedPreferences? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun entryToJson(entry: LexiconEntry): JSONObject {
        val poems = JSONArray()
        for (poem in entry.poems) {
            poems.put(
                JSONObject().apply {
                    put("text", poem.text)
                    put("author", poem.author)
                    put("source", poem.source)
                }
            )
        }
        val expressions = JSONArray()
        for (line in entry.expressions) {
            expressions.put(line)
        }
        return JSONObject().apply {
            put("word", entry.word)
            put("category", entry.category)
            put("poems", poems)
            put("expressions", expressions)
        }
    }

    fun entryFromJson(obj: JSONObject): LexiconEntry {
        val poems = mutableListOf<PoemLine>()
        val poemArr = obj.optJSONArray("poems") ?: JSONArray()
        for (i in 0 until poemArr.length()) {
            val p = poemArr.getJSONObject(i)
            poems.add(
                PoemLine(
                    text = p.optString("text"),
                    author = p.optString("author"),
                    source = p.optString("source")
                )
            )
        }
        val expressions = mutableListOf<String>()
        val expArr = obj.optJSONArray("expressions") ?: JSONArray()
        for (i in 0 until expArr.length()) {
            expressions.add(expArr.optString(i))
        }
        return LexiconEntry(
            word = obj.optString("word"),
            category = obj.optString("category"),
            poems = poems,
            expressions = expressions
        )
    }

    fun words(): List<LexiconEntry> {
        val list = mutableListOf<LexiconEntry>()
        val raw = prefs?.getString(KEY_WORDS, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(entryFromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun isWordAdded(word: String): Boolean {
        return words().any { it.word == word }
    }

    /** AI 生成词条整条加入，分类记为“我的” */
    fun addWord(entry: LexiconEntry) {
        val list = words().toMutableList()
        if (list.any { it.word == entry.word }) return
        list.add(0, entry.copy(category = "我的"))
        val arr = JSONArray()
        for (item in list) {
            arr.put(entryToJson(item))
        }
        prefs?.edit()?.putString(KEY_WORDS, arr.toString())?.apply()
        version++
    }

    fun removeWord(word: String) {
        val list = words().filter { it.word != word }
        val arr = JSONArray()
        for (item in list) {
            arr.put(entryToJson(item))
        }
        prefs?.edit()?.putString(KEY_WORDS, arr.toString())?.apply()
        version++
    }

    fun sentences(): List<CustomSentence> {
        val list = mutableListOf<CustomSentence>()
        val raw = prefs?.getString(KEY_SENTENCES, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CustomSentence(
                        id = obj.optString("id"),
                        text = obj.optString("t"),
                        source = obj.optString("s"),
                        date = obj.optLong("d")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun isSentenceAdded(text: String): Boolean {
        return sentences().any { it.text == text }
    }

    fun addSentence(text: String, source: String) {
        val list = sentences().toMutableList()
        if (list.any { it.text == text }) return
        list.add(
            0,
            CustomSentence(
                id = java.util.UUID.randomUUID().toString(),
                text = text,
                source = source,
                date = System.currentTimeMillis()
            )
        )
        saveSentences(list)
    }

    fun removeSentence(id: String) {
        saveSentences(sentences().filter { it.id != id })
    }

    private fun saveSentences(list: List<CustomSentence>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("t", item.text)
                    put("s", item.source)
                    put("d", item.date)
                }
            )
        }
        prefs?.edit()?.putString(KEY_SENTENCES, arr.toString())?.apply()
        version++
    }
}

// ---------- 历史记录：AI 改写、AI 生成词条、联网搜索自动留档，只存本机 ----------

data class HistoryEntry(
    val id: String,
    val date: Long,
    /** “改写” / “AI 生成词条” / “联网搜索” */
    val kind: String,
    val input: String,
    val detail: String,
    val results: List<String>
)

object HistoryStore {
    private const val PREFS = "history"
    private const val KEY = "historyEntries"
    private const val MAX_COUNT = 200

    private var prefs: android.content.SharedPreferences? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun entries(): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        val raw = prefs?.getString(KEY, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val results = mutableListOf<String>()
                val resArr = obj.optJSONArray("r") ?: JSONArray()
                for (j in 0 until resArr.length()) {
                    results.add(resArr.optString(j))
                }
                list.add(
                    HistoryEntry(
                        id = obj.optString("id"),
                        date = obj.optLong("d"),
                        kind = obj.optString("k"),
                        input = obj.optString("i"),
                        detail = obj.optString("e"),
                        results = results
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun add(entry: HistoryEntry) {
        val list = entries().toMutableList()
        list.add(0, entry)
        val kept = list.take(MAX_COUNT)
        val arr = JSONArray()
        for (item in kept) {
            val resArr = JSONArray()
            for (line in item.results) {
                resArr.put(line)
            }
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("d", item.date)
                    put("k", item.kind)
                    put("i", item.input)
                    put("e", item.detail)
                    put("r", resArr)
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        version++
    }

    fun addRewrite(input: String, detail: String, results: List<String>) {
        if (results.isEmpty()) return
        add(
            HistoryEntry(
                id = java.util.UUID.randomUUID().toString(),
                date = System.currentTimeMillis(),
                kind = "改写",
                input = input,
                detail = detail,
                results = results
            )
        )
    }

    fun addGenerated(word: String, poems: List<PoemLine>, expressions: List<String>) {
        val results = poems.map { "${it.text} —— ${it.author}·《${it.source}》" } + expressions
        if (results.isEmpty()) return
        add(
            HistoryEntry(
                id = java.util.UUID.randomUUID().toString(),
                date = System.currentTimeMillis(),
                kind = "AI 生成词条",
                input = word,
                detail = "为「$word」生成的词条",
                results = results
            )
        )
    }

    fun addWebSearch(word: String, summary: String) {
        if (summary.isEmpty()) return
        add(
            HistoryEntry(
                id = java.util.UUID.randomUUID().toString(),
                date = System.currentTimeMillis(),
                kind = "联网搜索",
                input = word,
                detail = "联网搜索到的释义",
                results = listOf(summary)
            )
        )
    }

    fun remove(entry: HistoryEntry) {
        val list = entries().filter { it.id != entry.id }
        saveRaw(list)
    }

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
        version++
    }

    private fun saveRaw(list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (item in list) {
            val resArr = JSONArray()
            for (line in item.results) {
                resArr.put(line)
            }
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("d", item.date)
                    put("k", item.kind)
                    put("i", item.input)
                    put("e", item.detail)
                    put("r", resArr)
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        version++
    }
}

// ---------- 字典搜索历史：只记点开看过的词条，存在本机（不进历史页） ----------

data class DictHistoryItem(
    val scope: String,
    val text: String,
    val date: Long
)

object DictHistoryStore {
    private const val PREFS = "dict_history"
    private const val KEY = "dictSearchHistory"
    private const val MAX_COUNT = 20

    private var prefs: android.content.SharedPreferences? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun items(): List<DictHistoryItem> {
        val list = mutableListOf<DictHistoryItem>()
        val raw = prefs?.getString(KEY, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DictHistoryItem(
                        scope = obj.optString("s"),
                        text = obj.optString("t"),
                        date = obj.optLong("d")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    /** 点开词条详情时记一次；重复的移到最前面 */
    fun record(scope: String, text: String) {
        if (text.isEmpty()) return
        val list = items().filter { !(it.scope == scope && it.text == text) }.toMutableList()
        list.add(0, DictHistoryItem(scope = scope, text = text, date = System.currentTimeMillis()))
        val kept = list.take(MAX_COUNT)
        val arr = JSONArray()
        for (item in kept) {
            arr.put(
                JSONObject().apply {
                    put("s", item.scope)
                    put("t", item.text)
                    put("d", item.date)
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        version++
    }

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
        version++
    }
}

// ---------- 本机日志：只存本机，供排查卡顿与失败（不含任何密钥） ----------

data class LogEntry(
    val date: Long,
    val tag: String,
    val message: String
)

object AppLog {
    private const val PREFS = "app_log"
    private const val KEY = "appLogEntries"
    private const val MAX_COUNT = 500

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** 任何线程都可调用；日志只存本机，不含密钥 */
    fun log(tag: String, message: String) {
        val p = prefs ?: return
        try {
            val list = entries().toMutableList()
            list.add(0, LogEntry(date = System.currentTimeMillis(), tag = tag, message = message))
            val kept = list.take(MAX_COUNT)
            val arr = JSONArray()
            for (item in kept) {
                arr.put(
                    JSONObject().apply {
                        put("d", item.date)
                        put("t", item.tag)
                        put("m", item.message)
                    }
                )
            }
            p.edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
            // 日志本身绝不能影响正常功能
        }
    }

    fun entries(): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        val raw = prefs?.getString(KEY, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LogEntry(
                        date = obj.optLong("d"),
                        tag = obj.optString("t"),
                        message = obj.optString("m")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
    }
}

// ---------- 联网搜索结果的本机缓存：按词存，重复搜直接显示，断网也能看 ----------

data class WebSummary(
    val word: String,
    val title: String,
    val extract: String,
    val pageUrl: String,
    val fetchedAt: Long
)

object WebSearchCache {
    private const val PREFS = "web_cache"
    private const val KEY = "webSearchCache"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun cached(word: String): WebSummary? {
        val raw = prefs?.getString(KEY, null) ?: return null
        try {
            val map = JSONObject(raw)
            if (!map.has(word)) return null
            val obj = map.getJSONObject(word)
            return WebSummary(
                word = word,
                title = obj.optString("t"),
                extract = obj.optString("e"),
                pageUrl = obj.optString("u"),
                fetchedAt = obj.optLong("d")
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun save(summary: WebSummary) {
        try {
            val raw = prefs?.getString(KEY, null)
            val map = if (raw != null) JSONObject(raw) else JSONObject()
            map.put(
                summary.word,
                JSONObject().apply {
                    put("t", summary.title)
                    put("e", summary.extract)
                    put("u", summary.pageUrl)
                    put("d", summary.fetchedAt)
                }
            )
            prefs?.edit()?.putString(KEY, map.toString())?.apply()
        } catch (e: Exception) {
        }
    }

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
    }

    fun count(): Int {
        val raw = prefs?.getString(KEY, null) ?: return 0
        return try {
            JSONObject(raw).length()
        } catch (e: Exception) {
            0
        }
    }
}
