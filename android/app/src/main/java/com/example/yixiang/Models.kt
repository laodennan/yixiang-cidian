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

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

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
