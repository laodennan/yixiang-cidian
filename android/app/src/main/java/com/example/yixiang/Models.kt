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
    private const val KEY_WEB_SEARCH = "webSearchEnabled"

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
        webSearchEnabled = false
    }

    /** 维基百科搜索开关：默认关闭；打开后词典搜索页才显示维基百科区块 */
    var webSearchVersion by mutableStateOf(0)
        private set

    var webSearchEnabled: Boolean
        get() = prefs?.getBoolean(KEY_WEB_SEARCH, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_WEB_SEARCH, value)?.apply()
            webSearchVersion++
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

// ---------- 近义词 / 替换词：内置种子 + AI 生成结果缓存 ----------

data class SynonymItem(
    val word: String,
    val note: String
)

data class SynonymGroup(
    val title: String,
    val items: List<SynonymItem>
)

/** 内置种子：手写，按程度 / 语气 / 场景分组，配一句话用法差别说明 */
object SynonymSeeds {
    val seeds: Map<String, List<SynonymGroup>> = mapOf(
        "生气" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("不悦", "轻微不满，还能克制"),
                    SynonymItem("恼怒", "明显生气，语气变硬"),
                    SynonymItem("震怒", "极度愤怒，难以自持"),
                    SynonymItem("暴怒", "怒到失控，言行激烈")
                )
            ),
            SynonymGroup(
                "隐忍",
                listOf(
                    SynonymItem("脸色沉了下来", "怒意藏在脸上，不发作"),
                    SynonymItem("指尖微微收紧", "身体小动作泄露情绪")
                )
            ),
            SynonymGroup(
                "爆发",
                listOf(
                    SynonymItem("猛地拍桌", "动作突然，情绪外泄"),
                    SynonymItem("声音陡然拔高", "音量失控，气势压人")
                )
            ),
            SynonymGroup(
                "克制",
                listOf(
                    SynonymItem("他没说话，只将杯子放回桌上", "以静制动，怒而不发")
                )
            )
        ),
        "高兴" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("愉快", "心情轻松，没什么负担"),
                    SynonymItem("欣喜", "超出预期的开心"),
                    SynonymItem("狂喜", "极度兴奋，难以自持")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("眉眼弯了起来", "笑意藏不住"),
                    SynonymItem("脚步都轻快了", "情绪带动身体")
                )
            )
        ),
        "难过" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("失落", "期望落空，心里空一块"),
                    SynonymItem("难过", "心里发沉，提不起劲"),
                    SynonymItem("悲痛", "极深的伤心，难以承受")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("眼眶发热", "想哭又忍着"),
                    SynonymItem("肩膀垮了下来", "整个人泄了气")
                )
            )
        ),
        "害怕" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("紧张", "心里发紧，绷着"),
                    SynonymItem("恐惧", "明确的怕，知道怕什么"),
                    SynonymItem("惊恐", "突如其来的怕，来不及反应")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("手心冒汗", "身体反应最直接"),
                    SynonymItem("后背发凉", "寒意从脊背升起")
                )
            )
        ),
        "紧张" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("忐忑", "心里没底，七上八下"),
                    SynonymItem("紧绷", "全身戒备，不敢放松"),
                    SynonymItem("如坐针毡", "坐立难安，度日如年")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("反复看表", "焦虑外显的小动作"),
                    SynonymItem("话到嘴边又咽回去", "欲言又止，拿不定主意")
                )
            )
        ),
        "失望" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("失落", "期望落空，空落落的"),
                    SynonymItem("寒心", "对人失望，心凉了半截"),
                    SynonymItem("绝望", "不再抱希望，彻底放弃")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("笑容一点点收回去", "情绪冷却的过程"),
                    SynonymItem("转身时脚步慢了下来", "不舍又无奈")
                )
            )
        ),
        "兴奋" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("激动", "情绪高涨，难以平静"),
                    SynonymItem("亢奋", "停不下来，精力过剩"),
                    SynonymItem("热血沸腾", "全身被点燃")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("说话语速变快", "压不住的劲头"),
                    SynonymItem("眼睛发亮", "期待写在脸上")
                )
            )
        ),
        "平静" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("淡然", "不为所动，看得开"),
                    SynonymItem("从容", "稳得住，不慌不忙"),
                    SynonymItem("心如止水", "全无波澜")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("语速不急不缓", "节奏稳，情绪稳"),
                    SynonymItem("坐姿端正，神色如常", "外在无破绽")
                )
            )
        ),
        "着急" to listOf(
            SynonymGroup(
                "程度",
                listOf(
                    SynonymItem("焦急", "心里催着，等不及"),
                    SynonymItem("急躁", "耐性耗尽，容易发火"),
                    SynonymItem("火烧眉毛", "十万火急，刻不容缓")
                )
            ),
            SynonymGroup(
                "表现",
                listOf(
                    SynonymItem("来回踱步", "坐不住，只能走动"),
                    SynonymItem("一遍遍催问", "急在嘴上")
                )
            )
        )
    )
}

/** AI 生成的近义词结果缓存：与 WebSearchCache 同款做法，按词存 JSON */
object SynonymCache {
    private const val PREFS = "synonym_cache"
    private const val KEY = "synonymCache"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun cached(word: String): List<SynonymGroup>? {
        val raw = prefs?.getString(KEY, null) ?: return null
        try {
            val map = JSONObject(raw)
            if (!map.has(word)) return null
            val arr = map.getJSONArray(word)
            val groups = mutableListOf<SynonymGroup>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val items = mutableListOf<SynonymItem>()
                val itemArr = g.optJSONArray("items") ?: JSONArray()
                for (j in 0 until itemArr.length()) {
                    val o = itemArr.getJSONObject(j)
                    items.add(SynonymItem(o.optString("word"), o.optString("note")))
                }
                groups.add(SynonymGroup(g.optString("title"), items))
            }
            return groups
        } catch (e: Exception) {
            return null
        }
    }

    fun save(word: String, groups: List<SynonymGroup>) {
        try {
            val raw = prefs?.getString(KEY, null)
            val map = if (raw != null) JSONObject(raw) else JSONObject()
            val arr = JSONArray()
            for (g in groups) {
                val itemArr = JSONArray()
                for (item in g.items) {
                    itemArr.put(
                        JSONObject().apply {
                            put("word", item.word)
                            put("note", item.note)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("title", g.title)
                        put("items", itemArr)
                    }
                )
            }
            map.put(word, arr)
            prefs?.edit()?.putString(KEY, map.toString())?.apply()
        } catch (e: Exception) {
        }
    }
}

// ---------- 场景灵感库：手写静态表，不进 assets，避免打包体积再涨 ----------

data class SceneEntry(
    val keyword: String,
    val personas: List<String>,
    val environments: List<String>,
    val actions: List<String>,
    val sentences: List<String>
)

object SceneLibrary {
    val scenes: List<SceneEntry> = listOf(
        SceneEntry(
            "紧张",
            listOf("他反复看表，手指无意识地敲着桌面。", "她深吸一口气，又缓缓吐出来，肩膀却还是绷着。"),
            listOf("走廊里静得能听见自己的心跳。", "灯光惨白，墙上的钟走得格外慢。"),
            listOf("他把笔帽拔开又盖上，重复了三次。", "她把衣角攥出了一道褶，又松开。"),
            listOf("空气像绷紧的弦，一触即断。", "他站在门口，脚像钉在了地上。")
        ),
        SceneEntry(
            "离别",
            listOf("她笑着挥手，转身后眼泪才掉下来。", "他把车票攥在手里，纸边都被汗洇软了。"),
            listOf("站台的风很凉，广播声一遍遍报着车次。", "月台尽头的灯一盏盏暗下去。"),
            listOf("他把行李箱的拉杆推回去，又拉出来。", "她把围巾往上拉了拉，遮住半张脸。"),
            listOf("车开动的时候，他才发现自己一直没眨眼。", "有些告别没有仪式，只有一句“到了说一声”。")
        ),
        SceneEntry(
            "重逢",
            listOf("两人隔着人群对视了一眼，都愣住了。", "她先笑出声，笑着笑着就红了眼眶。"),
            listOf("老街的灯还是暖黄的，仿佛时间没走远。", "雨刚停，空气里有青草和旧木头的味道。"),
            listOf("他伸出手，又改成拍了拍对方的肩。", "她把伞往旁边让了让，两个人挤在一把伞下。"),
            listOf("原来有些人再见面，第一句话还是当年的语气。", "时间把人推远，又把人推回原地。")
        ),
        SceneEntry(
            "深夜",
            listOf("他揉了揉发酸的眼睛，又把台灯调暗了一档。", "她裹着毯子，听楼下最后一班车开过去。"),
            listOf("窗外只剩路灯，整条街都睡着了。", "电脑屏幕的光是房间里唯一亮着的东西。"),
            listOf("他起身去倒水，拖鞋声在安静里格外清晰。", "她把手机倒扣在桌上，不再看时间。"),
            listOf("深夜会把白天的事都放大，也会把人变诚实。", "夜越深，越容易想起不该想的人。")
        ),
        SceneEntry(
            "雨夜",
            listOf("他把衣领竖起来，还是挡不住顺着脖子往下淌的雨水。", "她站在屋檐下，看雨把街道冲成一条河。"),
            listOf("雨点砸在铁皮棚上，密集得像有人在擂鼓。", "远处的霓虹被雨水晕开，模糊成一片彩色。"),
            listOf("他一脚踩进水洼，裤脚瞬间湿透。", "她把伞压得很低，只看得见脚下的路。"),
            listOf("雨夜适合赶路，也适合把心事都淋湿。", "雨声越大，人心里的话反而越清楚。")
        ),
        SceneEntry(
            "战场",
            listOf("他咬紧牙关，把受伤的同伴往掩体后拖。", "她握着枪的手在抖，却一步也没退。"),
            listOf("硝烟呛得人睁不开眼，耳边全是轰鸣。", "焦土上没有一棵完整的树。"),
            listOf("他匍匐着前进，泥水灌进了袖口。", "她换弹匣的动作快得像练过一千遍。"),
            listOf("枪声停下的那一刻，世界安静得不真实。", "活下来的人，先想到的往往是别人的名字。")
        ),
        SceneEntry(
            "婚礼",
            listOf("新娘捏着捧花，指尖微微发抖。", "新郎笑得合不拢嘴，又努力装作镇定。"),
            listOf("花瓣铺满红毯，阳光从玻璃顶洒下来。", "乐队刚起第一个音，全场就安静了。"),
            listOf("他单膝跪下，戒指盒打开时手抖了一下。", "她把头纱轻轻别到耳后，露出笑容。"),
            listOf("所有的誓言里，最动人的是那句“我愿意”。", "这一天会被记住很久，连阳光的味道都会记得。")
        ),
        SceneEntry(
            "葬礼",
            listOf("他站在墓前，一句话也说不出来。", "她把一束白花轻轻放在碑前，手指久久没松开。"),
            listOf("天阴沉沉的，风把纸钱吹得到处都是。", "灵堂里很安静，只有压抑的抽泣声。"),
            listOf("他一遍遍整理自己的黑衣领口。", "她把照片擦了又擦，像怕它沾上灰。"),
            listOf("人走后，留下的东西反而比人更长久。", "有些告别，是连再见都说不出口的。")
        ),
        SceneEntry(
            "考试",
            listOf("他盯着试卷，第一道题就卡住了。", "她深呼吸三次，才开始写名字。"),
            listOf("考场里只有笔尖划过纸面的沙沙声。", "窗外的蝉鸣一阵高过一阵。"),
            listOf("他把草稿纸翻过来，重新列了一遍公式。", "她咬着笔帽，在选项之间来回划线。"),
            listOf("交卷铃响的那一刻，有人如释重负，有人还在写。", "考试考的不只是知识，还有心态。")
        ),
        SceneEntry(
            "面试",
            listOf("他挺直腰背，推门时手心全是汗。", "她微笑着点头，努力让声音不发抖。"),
            listOf("会议室很亮，白墙和长桌让人无处可藏。", "空调的风一阵阵吹过来，冷得人清醒。"),
            listOf("他把简历抚平，放在桌角最整齐的位置。", "她回答问题时，眼睛会先看向对方再开口。"),
            listOf("面试就像一场短暂的戏，主角只有你自己。", "有时候被问住不可怕，可怕的是乱了阵脚。")
        ),
        SceneEntry(
            "追逐",
            listOf("他喘着粗气，脚步却不敢慢下来。", "她回头看了一眼，咬牙继续往前跑。"),
            listOf("小巷越来越窄，两边的墙几乎擦着肩膀。", "路灯一盏接一盏从身边掠过去。"),
            listOf("他翻过低矮的栅栏，落地时踉跄了一下。", "她把外套脱下来扔在路边，减轻负担。"),
            listOf("追逐的人和被追的人，拼的都是最后一口气。", "脚步声在身后越来越近，像贴在耳边。")
        ),
        SceneEntry(
            "对峙",
            listOf("两人隔着一张桌子，谁也不先开口。", "他的目光钉在对方脸上，一动不动。"),
            listOf("房间里的空气像凝固了，连钟表声都格外响。", "窗外的光斜斜地切进来，把影子拉得很长。"),
            listOf("她把茶杯慢慢放回桌上，没有发出一点声音。", "他十指交叉，拇指无意识地互相摩挲。"),
            listOf("对峙最难熬的不是争吵，而是沉默。", "有时候先开口的人，反而先输了气势。")
        ),
        SceneEntry(
            "告白",
            listOf("他练了一路的话，站到她面前全忘了。", "她低着头，耳尖一点点红起来。"),
            listOf("晚风很轻，樱花瓣落在两个人的肩上。", "路灯把影子拉得很长，又慢慢重合。"),
            listOf("他把藏了一路的花从身后拿出来。", "她接过花，手指碰到他的手指，又缩回去。"),
            listOf("喜欢这种事，藏得再好也会从眼睛里跑出来。", "告白的那一刻，时间好像被按了暂停。")
        ),
        SceneEntry(
            "背叛",
            listOf("他看着那条消息，手指停在屏幕上很久。", "她笑了一下，笑意却没到眼底。"),
            listOf("房间还是那个房间，只是突然变得很陌生。", "窗外的雨下起来了，像早就约好了似的。"),
            listOf("他把手机倒扣在桌上，不再看第二遍。", "她把那件旧外套叠好，放进了柜子最深处。"),
            listOf("背叛最伤人的不是失去，而是信任被亲手打碎。", "有些话一旦说出口，就再也收不回去了。")
        ),
        SceneEntry(
            "饥饿",
            listOf("他盯着路边的面馆，脚步不由自主慢下来。", "她咽了口口水，肚子叫得更响了。"),
            listOf("夜市的香气一阵阵飘过来，无处可躲。", "厨房里只剩空碗和一口冷锅。"),
            listOf("他把最后一块饼掰成两半，留了一半给明天。", "她数着口袋里的零钱，算了三遍还是不够。"),
            listOf("饿的时候，连空气里都飘着饭菜的味道。", "饥饿会把人变小，也会把一碗热汤变大。")
        ),
        SceneEntry(
            "寒冷",
            listOf("他把手缩进袖子里，还是挡不住寒气。", "她呵出的白气在空中散开，又很快消失。"),
            listOf("雪落在屋顶上，世界安静得只剩脚步声。", "窗玻璃结了一层霜，外面什么都看不清。"),
            listOf("他跺了跺脚，把围巾又绕了一圈。", "她把热水袋抱在怀里，指尖才慢慢回暖。"),
            listOf("冷到极致的时候，连呼吸都带着疼。", "冬天最暖的东西，往往是一间亮着灯的屋子。")
        ),
        SceneEntry(
            "酷热",
            listOf("他刚出门，汗就顺着额头往下淌。", "她把头发全部扎起来，还是热得心烦。"),
            listOf("柏油路被晒得发软，空气都在晃动。", "蝉鸣从早到晚没停过，像一场不会结束的雨。"),
            listOf("他拧开瓶盖，一口气喝掉半瓶水。", "她把袖子卷到最高，露出被晒红的手臂。"),
            listOf("热浪扑面而来，连影子都躲进了墙角。", "夏天最漫长的，是午后那段没有风的时间。")
        ),
        SceneEntry(
            "迷路",
            listOf("他站在岔路口，左右看了三遍还是拿不定主意。", "她把地图翻过来又翻过去，越看越晕。"),
            listOf("小巷长得一模一样，拐了几个弯就分不清方向。", "天色渐渐暗下来，路灯还没亮。"),
            listOf("他凭着感觉往前走，脚步越来越慢。", "她问了三个人，得到三个不同的答案。"),
            listOf("迷路的时候，最怕的不是走远，而是原地打转。", "有时候路走错了，反而会遇见意想不到的风景。")
        ),
        SceneEntry(
            "等待",
            listOf("他每隔一分钟就看一次手机屏幕。", "她坐在长椅上，把包带绕在手指上又松开。"),
            listOf("车站的广播一遍遍报着晚点，时间被拉得很长。", "窗外的天从亮等到暗，灯一盏盏亮起来。"),
            listOf("他站起来走了两步，又坐回去。", "她把水喝了一口又一口，瓶子很快就空了。"),
            listOf("等待最磨人的，是不知道还要等多久。", "等的人没来，倒是把自己等成了风景。")
        ),
        SceneEntry(
            "胜利",
            listOf("终场哨响的那一刻，他跪在地上，双手掩面。", "她举起奖杯，笑得眼泪都出来了。"),
            listOf("全场的灯光都聚过来，欢呼声像潮水。", "彩带从空中飘下来，落在每个人的肩上。"),
            listOf("他和队友紧紧拥抱，拍着彼此的后背。", "她把奖牌举过头顶，朝着看台挥手。"),
            listOf("胜利的滋味，是把所有苦都变成值得。", "这一刻值得所有的汗水和等待。")
        ),
        SceneEntry(
            "失败",
            listOf("他盯着记分牌，久久没有动。", "她低着头走出场馆，脚步很慢。"),
            listOf("雨不知什么时候下起来了，淋湿了回家的路。", "更衣室里很安静，没人说话。"),
            listOf("他把毛巾盖在头上，遮住眼睛。", "她一遍遍回想最后一个失误，像放电影。"),
            listOf("失败不是终点，只是下一次开始的起点。", "输掉比赛的人，往往比赢的人记得更久。")
        ),
        SceneEntry(
            "童年",
            listOf("他蹲在田埂上，看蚂蚁排队搬家。", "她光着脚丫在院子里跑，笑声传出很远。"),
            listOf("夏天的傍晚，蝉鸣和炊烟混在一起。", "老屋的木门吱呀作响，是童年最熟悉的声音。"),
            listOf("他把弹珠一颗颗排好，舍不得输掉任何一颗。", "她踮起脚去够树上的杏子，够不着就跳起来。"),
            listOf("童年是一场不会重来的梦，醒来才发现它最珍贵。", "长大后才明白，小时候的夏天是最长的。")
        ),
        SceneEntry(
            "故乡",
            listOf("他站在村口，路还是那条路，人却少了很多。", "她推开老屋的门，灰尘在阳光里飞舞。"),
            listOf("炊烟从各家屋顶升起来，味道还是记忆里的味道。", "河边的柳树又绿了，只是树下的人换了。"),
            listOf("他蹲下来摸了摸门槛，木头已经被磨得很光滑。", "她把行李放在堂屋，先去给长辈上了柱香。"),
            listOf("故乡是走多远都会回头看的地方。", "离家越久，故乡的味道就越清晰。")
        ),
        SceneEntry(
            "医院",
            listOf("他坐在走廊的长椅上，双手交握，盯着地面。", "她握着病床边那只瘦削的手，不敢用力。"),
            listOf("消毒水的味道弥漫在每个角落，白得刺眼。", "走廊尽头的灯一夜没灭，像守着什么。"),
            listOf("他一遍遍看检查单上的数字，像在确认什么。", "她把水杯递过去，又轻轻扶对方坐起来。"),
            listOf("医院里的时间走得很慢，每一分钟都被拉长。", "在医院里，最动听的话往往是“没事了”。")
        )
    )

    fun find(keyword: String): SceneEntry? = scenes.firstOrNull { it.keyword == keyword }

    fun match(query: String): SceneEntry? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return scenes.firstOrNull { it.keyword == q || q.contains(it.keyword) }
    }
}

// ---------- 素材本：文件夹 + 条目，SharedPreferences 持久化 ----------

data class MaterialFolder(
    val id: String,
    val name: String
)

data class MaterialItem(
    val id: String,
    val folderId: String,
    val text: String,
    val note: String,
    val tags: List<String>,
    val source: String,
    val createdAt: Long
)

object MaterialStore {
    private const val PREFS = "material_book"
    private const val KEY_FOLDERS = "materialFolders"
    private const val KEY_ITEMS = "materialItems"
    const val DEFAULT_FOLDER_ID = "default"
    const val DEFAULT_FOLDER_NAME = "默认"

    private var prefs: android.content.SharedPreferences? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        ensureDefaultFolder()
    }

    private fun ensureDefaultFolder() {
        val list = folders().toMutableList()
        if (list.none { it.id == DEFAULT_FOLDER_ID }) {
            list.add(0, MaterialFolder(DEFAULT_FOLDER_ID, DEFAULT_FOLDER_NAME))
            saveFolders(list)
        }
    }

    fun folders(): List<MaterialFolder> {
        val list = mutableListOf<MaterialFolder>()
        val raw = prefs?.getString(KEY_FOLDERS, null) ?: return listOf(
            MaterialFolder(DEFAULT_FOLDER_ID, DEFAULT_FOLDER_NAME)
        )
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(MaterialFolder(obj.optString("id"), obj.optString("name")))
            }
        } catch (e: Exception) {
        }
        if (list.none { it.id == DEFAULT_FOLDER_ID }) {
            list.add(0, MaterialFolder(DEFAULT_FOLDER_ID, DEFAULT_FOLDER_NAME))
        }
        return list
    }

    private fun saveFolders(list: List<MaterialFolder>) {
        val arr = JSONArray()
        for (f in list) {
            arr.put(JSONObject().apply { put("id", f.id); put("name", f.name) })
        }
        prefs?.edit()?.putString(KEY_FOLDERS, arr.toString())?.apply()
        version++
    }

    fun createFolder(name: String): MaterialFolder {
        val folder = MaterialFolder(java.util.UUID.randomUUID().toString(), name)
        saveFolders(folders() + folder)
        return folder
    }

    fun renameFolder(id: String, name: String) {
        saveFolders(folders().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteFolder(id: String) {
        if (id == DEFAULT_FOLDER_ID) return
        saveFolders(folders().filter { it.id != id })
        saveItems(items().filter { it.folderId != id })
    }

    fun items(): List<MaterialItem> {
        val list = mutableListOf<MaterialItem>()
        val raw = prefs?.getString(KEY_ITEMS, null) ?: return list
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tags = mutableListOf<String>()
                val tagArr = obj.optJSONArray("tags") ?: JSONArray()
                for (j in 0 until tagArr.length()) {
                    tags.add(tagArr.optString(j))
                }
                list.add(
                    MaterialItem(
                        id = obj.optString("id"),
                        folderId = obj.optString("folderId"),
                        text = obj.optString("text"),
                        note = obj.optString("note"),
                        tags = tags,
                        source = obj.optString("source"),
                        createdAt = obj.optLong("createdAt")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveItems(list: List<MaterialItem>) {
        val arr = JSONArray()
        for (item in list) {
            val tagArr = JSONArray()
            for (tag in item.tags) {
                tagArr.put(tag)
            }
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("folderId", item.folderId)
                    put("text", item.text)
                    put("note", item.note)
                    put("tags", tagArr)
                    put("source", item.source)
                    put("createdAt", item.createdAt)
                }
            )
        }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
        version++
    }

    fun itemsIn(folderId: String): List<MaterialItem> =
        items().filter { it.folderId == folderId }.sortedByDescending { it.createdAt }

    fun addItem(text: String, note: String, tags: List<String>, source: String, folderId: String = DEFAULT_FOLDER_ID) {
        if (text.isBlank()) return
        val item = MaterialItem(
            id = java.util.UUID.randomUUID().toString(),
            folderId = folderId,
            text = text,
            note = note,
            tags = tags,
            source = source,
            createdAt = System.currentTimeMillis()
        )
        saveItems(listOf(item) + items())
    }

    /** 句子行的小书签按钮：存入默认文件夹 */
    fun addToDefault(text: String, source: String) {
        ensureDefaultFolder()
        addItem(text = text, note = "", tags = emptyList(), source = source)
    }

    fun removeItem(id: String) {
        saveItems(items().filter { it.id != id })
    }

    fun search(query: String): List<MaterialItem> {
        val q = query.trim()
        if (q.isEmpty()) return items().sortedByDescending { it.createdAt }
        return items().filter {
            it.text.contains(q) || it.note.contains(q) || it.source.contains(q) ||
                it.tags.any { tag -> tag.contains(q) }
        }.sortedByDescending { it.createdAt }
    }

    fun exportMarkdown(): String {
        val sb = StringBuilder("# 素材本\n")
        for (folder in folders()) {
            val list = itemsIn(folder.id)
            if (list.isEmpty()) continue
            sb.append("\n## ${folder.name}\n")
            for (item in list) {
                sb.append("\n- ${item.text}")
                if (item.note.isNotEmpty()) sb.append("\n  备注：${item.note}")
                if (item.tags.isNotEmpty()) sb.append("\n  标签：${item.tags.joinToString("、")}")
                if (item.source.isNotEmpty()) sb.append("\n  来源：${item.source}")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun exportText(): String {
        val sb = StringBuilder()
        for (folder in folders()) {
            val list = itemsIn(folder.id)
            if (list.isEmpty()) continue
            sb.append("【${folder.name}】\n")
            for (item in list) {
                sb.append("${item.text}\n")
                if (item.note.isNotEmpty()) sb.append("备注：${item.note}\n")
                if (item.tags.isNotEmpty()) sb.append("标签：${item.tags.joinToString("、")}\n")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun exportJson(): String {
        val folderArr = JSONArray()
        for (f in folders()) {
            folderArr.put(JSONObject().apply { put("id", f.id); put("name", f.name) })
        }
        val itemArr = JSONArray()
        for (item in items()) {
            val tagArr = JSONArray()
            for (tag in item.tags) {
                tagArr.put(tag)
            }
            itemArr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("folderId", item.folderId)
                    put("text", item.text)
                    put("note", item.note)
                    put("tags", tagArr)
                    put("source", item.source)
                    put("createdAt", item.createdAt)
                }
            )
        }
        return JSONObject().apply {
            put("folders", folderArr)
            put("items", itemArr)
        }.toString()
    }

    /** 导入备份 JSON：按 id 去重合并，返回新导入的条数 */
    fun importJson(json: String): Int {
        try {
            val root = JSONObject(json)
            val folderArr = root.optJSONArray("folders") ?: JSONArray()
            val existingFolders = folders().toMutableList()
            for (i in 0 until folderArr.length()) {
                val obj = folderArr.getJSONObject(i)
                val folder = MaterialFolder(obj.optString("id"), obj.optString("name"))
                if (folder.id.isNotEmpty() && existingFolders.none { it.id == folder.id }) {
                    existingFolders.add(folder)
                }
            }
            saveFolders(existingFolders)
            val existingIds = items().map { it.id }.toSet()
            val itemArr = root.optJSONArray("items") ?: JSONArray()
            val imported = mutableListOf<MaterialItem>()
            for (i in 0 until itemArr.length()) {
                val obj = itemArr.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isEmpty() || existingIds.contains(id)) continue
                val tags = mutableListOf<String>()
                val tagArr = obj.optJSONArray("tags") ?: JSONArray()
                for (j in 0 until tagArr.length()) {
                    tags.add(tagArr.optString(j))
                }
                imported.add(
                    MaterialItem(
                        id = id,
                        folderId = obj.optString("folderId").ifEmpty { DEFAULT_FOLDER_ID },
                        text = obj.optString("text"),
                        note = obj.optString("note"),
                        tags = tags,
                        source = obj.optString("source"),
                        createdAt = obj.optLong("createdAt")
                    )
                )
            }
            if (imported.isNotEmpty()) {
                saveItems(imported + items())
            }
            return imported.size
        } catch (e: Exception) {
            return -1
        }
    }
}

// ---------- 重复表达检查器：纯本地启发式规则（没有中文分词器，结果只能是“疑似重复”） ----------

data class RepeatedWord(
    val word: String,
    val count: Int
)

data class RepeatedStart(
    val start: String,
    val sentences: List<String>
)

data class AdjacentPair(
    val firstIndex: Int,
    val first: String,
    val second: String,
    val similarity: Double
)

data class CheckReport(
    val frequentWords: List<RepeatedWord>,
    val repeatedStarts: List<RepeatedStart>,
    val adjectiveHits: Int,
    val totalChars: Int,
    val densityPercent: Double,
    val adjacentPairs: List<AdjacentPair>
)

object RepetitionChecker {
    /** 功能字：含这些字的 2 字组合不计入高频统计 */
    private val functionChars = setOf(
        '的', '了', '在', '是', '有', '和', '就', '都', '也', '不', '还',
        '这', '那', '他', '她', '它', '我', '你', '您', '们', '个',
        '上', '下', '中', '大', '小'
    )

    /** 内置形容词 / 副词表（约 60 个） */
    val adjectives = listOf(
        "美丽", "漂亮", "英俊", "潇洒", "微微", "轻轻", "缓缓", "慢慢",
        "突然", "忽然", "非常", "十分", "特别", "格外", "明亮", "昏暗",
        "安静", "热闹", "孤独", "紧张", "疲惫", "坚定", "犹豫", "温柔",
        "冷漠", "愤怒", "悲伤", "快乐", "兴奋", "失望", "惊讶", "好奇",
        "羞涩", "尴尬", "狼狈", "整齐", "凌乱", "干净", "肮脏", "巨大",
        "渺小", "宽广", "狭窄", "高大", "矮小", "深邃", "浅薄", "浓烈",
        "清淡", "香甜", "苦涩", "柔软", "坚硬", "光滑", "粗糙", "温暖",
        "寒冷", "明媚", "阴沉", "朦胧", "清晰", "模糊", "悠闲", "匆忙",
        "熟练", "生疏", "果断", "迟疑"
    )

    private fun isCJK(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF
    }

    fun check(text: String): CheckReport {
        val totalChars = text.count { !it.isWhitespace() }

        // (a) 高频 2 字词：滑动统计两个汉字组合，排除含功能字的组合，≥3 次列出
        val wordCounts = mutableMapOf<String, Int>()
        for (i in 0 until text.length - 1) {
            val a = text[i]
            val b = text[i + 1]
            if (!isCJK(a) || !isCJK(b)) continue
            if (a in functionChars || b in functionChars) continue
            val word = "$a$b"
            wordCounts[word] = (wordCounts[word] ?: 0) + 1
        }
        val frequentWords = wordCounts
            .filter { it.value >= 3 }
            .map { RepeatedWord(it.key, it.value) }
            .sortedByDescending { it.count }

        // (b) 重复句首：按 。！？；切分，相同前 2 字开头的句子 ≥3 句列出
        val sentences = text.split('。', '！', '？', '；')
            .map { it.trim().trimStart('“', '"', '‘', '\'') }
            .filter { it.length >= 2 }
        val startGroups = sentences.groupBy { it.substring(0, 2) }
            .filter { it.value.size >= 3 }
            .map { RepeatedStart(it.key, it.value) }

        // (c) 形容词密度：命中数 / 全文总字数
        var hits = 0
        for (word in adjectives) {
            var from = 0
            while (true) {
                val found = text.indexOf(word, from)
                if (found < 0) break
                hits++
                from = found + word.length
            }
        }
        val density = if (totalChars > 0) hits * 100.0 / totalChars else 0.0

        // (d) 相邻段落意思相近：按换行分段，相邻两段字符集合 Jaccard 相似度 ≥0.6 列出
        val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val pairs = mutableListOf<AdjacentPair>()
        for (i in 0 until paragraphs.size - 1) {
            val setA = paragraphs[i].toSet()
            val setB = paragraphs[i + 1].toSet()
            val union = (setA + setB).size
            if (union == 0) continue
            val similarity = (setA intersect setB).size.toDouble() / union
            if (similarity >= 0.6) {
                pairs.add(AdjacentPair(i, paragraphs[i], paragraphs[i + 1], similarity))
            }
        }

        return CheckReport(
            frequentWords = frequentWords,
            repeatedStarts = startGroups,
            adjectiveHits = hits,
            totalChars = totalChars,
            densityPercent = density,
            adjacentPairs = pairs
        )
    }
}
