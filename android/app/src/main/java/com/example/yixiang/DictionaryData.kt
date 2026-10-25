package com.example.yixiang

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------- 离线字典数据模型（数据来自 chinese-xinhua 本地包，与 iOS 版同一份 JSON） ----------

data class DictChar(
    val word: String,
    val oldword: String,
    val strokes: String,
    val pinyin: String,
    val radicals: String,
    val explanation: String
)

data class DictWord(
    val word: String,
    val pinyin: String,
    val explanation: String
)

data class DictIdiom(
    val word: String,
    val pinyin: String,
    val explanation: String,
    val derivation: String,
    val example: String,
    val abbreviation: String
)

data class DictXiehouyu(
    val riddle: String,
    val answer: String
)

// ---------- 拼音检索键：去声调、去空格，ü 与 v 统一成 u ----------

object PinyinKey {
    fun normalize(s: String): String {
        val mapped = s.replace('ü', 'u').replace('Ü', 'u')
        val sb = StringBuilder()
        for (ch in mapped) {
            when (ch) {
                'ā', 'á', 'ǎ', 'à' -> sb.append('a')
                'ē', 'é', 'ě', 'è' -> sb.append('e')
                'ī', 'í', 'ǐ', 'ì' -> sb.append('i')
                'ō', 'ó', 'ǒ', 'ò' -> sb.append('o')
                'ū', 'ú', 'ǔ', 'ù' -> sb.append('u')
                'ǖ', 'ǘ', 'ǚ', 'ǜ' -> sb.append('u')
                else -> {
                    if (ch.isLetterOrDigit()) sb.append(ch)
                }
            }
        }
        return sb.toString().lowercase().replace('v', 'u')
    }

    fun containsCJK(s: String): Boolean {
        for (ch in s) {
            val code = ch.code
            if ((code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)) return true
        }
        return false
    }
}

// ---------- 字典检索范围与结果 ----------

enum class DictScope(val label: String) {
    ALL("全部"),
    CHARS("汉字"),
    WORDS("词语"),
    IDIOMS("成语"),
    XIEHOUYU("歇后语");

    companion object {
        fun fromLabel(label: String): DictScope {
            return values().firstOrNull { it.label == label } ?: ALL
        }
    }
}

data class DictResults(
    val chars: List<DictChar> = emptyList(),
    val words: List<DictWord> = emptyList(),
    val idioms: List<DictIdiom> = emptyList(),
    val xiehouyu: List<DictXiehouyu> = emptyList()
) {
    fun isEmpty(): Boolean {
        return chars.isEmpty() && words.isEmpty() && idioms.isEmpty() && xiehouyu.isEmpty()
    }
}

/** 字典详情页导航目标：按范围 + 词条键查找详情 */
data class DictDetailRef(
    val scope: DictScope,
    val key: String
)

// ---------- 离线字典仓库：首次打开字典页时在后台解码，之后常驻内存 ----------

object DictionaryStore {
    private val mainHandler = Handler(Looper.getMainLooper())

    var loaded by mutableStateOf(false)
        private set

    private var chars: List<DictChar> = emptyList()
    private var words: List<DictWord> = emptyList()
    private var idioms: List<DictIdiom> = emptyList()
    private var xiehouyu: List<DictXiehouyu> = emptyList()

    private var charPinyinKeys: List<String> = emptyList()
    private var wordPinyinKeys: List<String> = emptyList()
    private var idiomPinyinKeys: List<String> = emptyList()

    private var loading = false

    fun load(context: Context) {
        if (loaded || loading) return
        loading = true
        val start = System.currentTimeMillis()
        val appContext = context.applicationContext
        Thread {
            try {
                val decodedChars = decodeChars(appContext)
                // 词语数据分两个文件存放（单个文件超过 GitHub 网页上传的 25MB 限制）
                val wordsA = decodeWords(appContext, "dict_words_a")
                val wordsB = decodeWords(appContext, "dict_words_b")
                val decodedIdioms = decodeIdioms(appContext)
                val decodedXiehouyu = decodeXiehouyu(appContext)
                val charKeys = decodedChars.map { PinyinKey.normalize(it.pinyin) }
                val wordKeys = (wordsA + wordsB).map { PinyinKey.normalize(it.pinyin) }
                val idiomKeys = decodedIdioms.map { PinyinKey.normalize(it.pinyin) }
                mainHandler.post {
                    chars = decodedChars
                    words = wordsA + wordsB
                    idioms = decodedIdioms
                    xiehouyu = decodedXiehouyu
                    charPinyinKeys = charKeys
                    wordPinyinKeys = wordKeys
                    idiomPinyinKeys = idiomKeys
                    loaded = true
                    val ms = System.currentTimeMillis() - start
                    AppLog.log("加载", "离线字典加载完成，耗时 ${ms}ms")
                }
            } catch (e: Exception) {
                mainHandler.post { loading = false }
            }
        }.start()
    }

    private fun readAsset(context: Context, name: String): String {
        return context.assets.open("$name.json").bufferedReader().use { it.readText() }
    }

    private fun decodeChars(context: Context): List<DictChar> {
        val arr = JSONArray(readAsset(context, "dict_chars"))
        val list = mutableListOf<DictChar>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                DictChar(
                    word = o.optString("w"),
                    oldword = o.optString("o"),
                    strokes = o.optString("s"),
                    pinyin = o.optString("p"),
                    radicals = o.optString("r"),
                    explanation = o.optString("e")
                )
            )
        }
        return list
    }

    private fun decodeWords(context: Context, name: String): List<DictWord> {
        val arr = JSONArray(readAsset(context, name))
        val list = mutableListOf<DictWord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                DictWord(
                    word = o.optString("w"),
                    pinyin = o.optString("p"),
                    explanation = o.optString("e")
                )
            )
        }
        return list
    }

    private fun decodeIdioms(context: Context): List<DictIdiom> {
        val arr = JSONArray(readAsset(context, "dict_idioms"))
        val list = mutableListOf<DictIdiom>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                DictIdiom(
                    word = o.optString("w"),
                    pinyin = o.optString("p"),
                    explanation = o.optString("e"),
                    derivation = o.optString("d"),
                    example = o.optString("x"),
                    abbreviation = o.optString("a")
                )
            )
        }
        return list
    }

    private fun decodeXiehouyu(context: Context): List<DictXiehouyu> {
        val arr = JSONArray(readAsset(context, "dict_xiehouyu"))
        val list = mutableListOf<DictXiehouyu>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                DictXiehouyu(
                    riddle = o.optString("r"),
                    answer = o.optString("a")
                )
            )
        }
        return list
    }

    /** 取够即停的顺序扫描：每类最多取 50 条，够数立即停，不再扫全库 */
    private fun <T> firstMatches(items: List<T>, keys: List<String>?, limit: Int, matches: (T, String) -> Boolean): List<T> {
        val out = mutableListOf<T>()
        for (i in items.indices) {
            val key = keys?.get(i) ?: ""
            if (matches(items[i], key)) {
                out.add(items[i])
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * 检索：含汉字时按字面子串匹配；纯字母时按拼音（或成语首字母缩写）匹配。
     * 注意：调用方应在后台线程执行（搜索本身是纯计算，不触 UI），避免堵主线程。
     */
    fun search(query: String, scope: DictScope): DictResults {
        val q = query.trim()
        if (!loaded || q.isEmpty()) return DictResults()
        val isCJK = PinyinKey.containsCJK(q)
        val qk = PinyinKey.normalize(q)
        var outChars: List<DictChar> = emptyList()
        var outWords: List<DictWord> = emptyList()
        var outIdioms: List<DictIdiom> = emptyList()
        var outXiehouyu: List<DictXiehouyu> = emptyList()

        if (scope == DictScope.ALL || scope == DictScope.CHARS) {
            outChars = if (isCJK) {
                firstMatches(chars, null, 50) { item, _ ->
                    item.word.contains(q) || (item.oldword.isNotEmpty() && item.oldword.contains(q))
                }
            } else if (qk.isNotEmpty()) {
                firstMatches(chars, charPinyinKeys, 50) { _, key -> key.contains(qk) }
            } else {
                emptyList()
            }
        }
        if (scope == DictScope.ALL || scope == DictScope.WORDS) {
            outWords = if (isCJK) {
                firstMatches(words, null, 50) { item, _ -> item.word.contains(q) }
            } else if (qk.isNotEmpty()) {
                firstMatches(words, wordPinyinKeys, 50) { _, key -> key.contains(qk) }
            } else {
                emptyList()
            }
        }
        if (scope == DictScope.ALL || scope == DictScope.IDIOMS) {
            outIdioms = if (isCJK) {
                firstMatches(idioms, null, 50) { item, _ -> item.word.contains(q) }
            } else if (qk.isNotEmpty()) {
                firstMatches(idioms, idiomPinyinKeys, 50) { item, key ->
                    key.contains(qk) || item.abbreviation.lowercase().contains(qk)
                }
            } else {
                emptyList()
            }
        }
        if (scope == DictScope.ALL || scope == DictScope.XIEHOUYU) {
            outXiehouyu = if (isCJK) {
                firstMatches(xiehouyu, null, 50) { item, _ ->
                    item.riddle.contains(q) || item.answer.contains(q)
                }
            } else {
                emptyList()
            }
        }
        return DictResults(
            chars = outChars,
            words = outWords,
            idioms = outIdioms,
            xiehouyu = outXiehouyu
        )
    }

    fun findChar(word: String): DictChar? = chars.firstOrNull { it.word == word }
    fun findWord(word: String): DictWord? = words.firstOrNull { it.word == word }
    fun findIdiom(word: String): DictIdiom? = idioms.firstOrNull { it.word == word }
    fun findXiehouyu(riddle: String): DictXiehouyu? = xiehouyu.firstOrNull { it.riddle == riddle }
}

// ---------- 联网搜索：中文维基百科免费接口，无需 Key；结果缓存在本机 ----------

class WebSearchNotFoundException : Exception("维基百科上没有该词条")

object WebSearch {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /** 回调一定在主线程执行，可直接更新 Compose 状态 */
    fun fetchSummary(word: String, callback: (Result<WebSummary>) -> Unit) {
        Thread {
            try {
                val encoded = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
                val url = URL("https://zh.wikipedia.org/api/rest_v1/page/summary/$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Wenqi/1.8 (Android personal dictionary app)")
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code != 200) {
                    postToMain { callback(Result.failure(WebSearchNotFoundException())) }
                    return@Thread
                }
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val obj = JSONObject(text)
                val extract = obj.optString("extract", "")
                if (extract.isEmpty()) {
                    postToMain { callback(Result.failure(WebSearchNotFoundException())) }
                    return@Thread
                }
                val title = obj.optString("title", word)
                var pageUrl = "https://zh.wikipedia.org/wiki/$encoded"
                val contentUrls = obj.optJSONObject("content_urls")
                val desktop = contentUrls?.optJSONObject("desktop")
                val page = desktop?.optString("page", "")
                if (!page.isNullOrEmpty()) {
                    pageUrl = page
                }
                val summary = WebSummary(
                    word = word,
                    title = title,
                    extract = extract,
                    pageUrl = pageUrl,
                    fetchedAt = System.currentTimeMillis()
                )
                postToMain { callback(Result.success(summary)) }
            } catch (e: Exception) {
                postToMain { callback(Result.failure(e)) }
            }
        }.start()
    }
}
