package com.example.yixiang

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** 中文错误提示，与 iOS 版文案一致 */
class AIException(message: String) : Exception(message)

object AIClient {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * 通用对话：返回模型输出的文本（已去首尾空白）。
     * 回调一定在主线程执行，可直接更新 Compose 状态。
     */
    fun chat(system: String, user: String, callback: (Result<String>) -> Unit) {
        if (!SettingsStore.hasKey()) {
            postToMain { callback(Result.failure(AIException("请先在设置里填写 API Key"))) }
            return
        }
        val base = SettingsStore.baseUrl.trim().trimEnd('/')
        val urlString = "$base/chat/completions"
        val url: URL
        try {
            url = URL(urlString)
        } catch (e: Exception) {
            postToMain { callback(Result.failure(AIException("Base URL 不正确，请在设置里检查"))) }
            return
        }
        Thread {
            try {
                val requestBody = JSONObject().apply {
                    put("model", SettingsStore.model.trim())
                    put(
                        "messages",
                        JSONArray().apply {
                            put(JSONObject().apply { put("role", "system"); put("content", system) })
                            put(JSONObject().apply { put("role", "user"); put("content", user) })
                        }
                    )
                }
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty(
                    "Authorization",
                    "Bearer " + SettingsStore.apiKey.trim()
                )
                conn.doOutput = true
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(requestBody.toString())
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    postToMain {
                        callback(
                            Result.failure(
                                AIException("请求失败，状态码 $code，请检查 Base URL、API Key 和模型名是否正确")
                            )
                        )
                    }
                    return@Thread
                }
                val responseText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val content = try {
                    JSONObject(responseText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                        .trim()
                } catch (e: Exception) {
                    ""
                }
                if (content.isEmpty()) {
                    postToMain { callback(Result.failure(AIException("接口返回了空结果，请重试"))) }
                } else {
                    postToMain { callback(Result.success(content)) }
                }
            } catch (e: Exception) {
                postToMain {
                    callback(
                        Result.failure(
                            AIException("请求失败：${e.message ?: "未知错误"}，请检查网络和接口配置")
                        )
                    )
                }
            }
        }.start()
    }

    /**
     * 为离线词库里没有的意象词生成一则词条（古诗句 + 生动表达），
     * 返回结构与内置词条一致，可直接用词条详情页展示。
     */
    fun generateEntry(word: String, callback: (Result<LexiconEntry>) -> Unit) {
        val system = "你是文栖词典的编纂者。用户给一个意象词，你只返回一个 JSON 对象，格式固定为：" +
            "{\"word\":\"词\",\"category\":\"分类\",\"poems\":[{\"text\":\"诗句\",\"author\":\"作者\",\"source\":\"篇名\"}],\"expressions\":[\"生动表达\", \"...\"]}" +
            "要求：古诗句只写真实存在、作者与篇名无误的名句（2-4句），不确定的不要写；生动表达写3-5句现代中文，要具体、有动词、有质感。只返回 JSON，不要任何解释，也不要加代码围栏。"
        chat(system, word) { result ->
            result.fold(
                onSuccess = { raw ->
                    var text = raw.trim()
                    if (text.startsWith("```")) {
                        // 去掉可能的代码围栏
                        val firstNewline = text.indexOf('\n')
                        if (firstNewline >= 0) {
                            text = text.substring(firstNewline + 1)
                        }
                        if (text.endsWith("```")) {
                            text = text.dropLast(3).toString()
                        }
                        text = text.trim()
                    }
                    try {
                        val obj = JSONObject(text)
                        // 放宽解析：作者/篇名缺失按空字符串处理，诗句文本为空的条目过滤掉；
                        // 只要古诗或生动表达有一边有内容就算成功（原来要求两边都非空，
                        // 是“你好”两次解析失败的根因）
                        val poems = mutableListOf<PoemLine>()
                        val poemArr = obj.optJSONArray("poems") ?: JSONArray()
                        for (i in 0 until poemArr.length()) {
                            val p = poemArr.getJSONObject(i)
                            val poemText = p.optString("text")
                            if (poemText.isNotEmpty()) {
                                poems.add(
                                    PoemLine(
                                        text = poemText,
                                        author = p.optString("author"),
                                        source = p.optString("source")
                                    )
                                )
                            }
                        }
                        val expressions = mutableListOf<String>()
                        val expArr = obj.optJSONArray("expressions") ?: JSONArray()
                        for (i in 0 until expArr.length()) {
                            val line = expArr.optString(i)
                            if (line.isNotEmpty()) {
                                expressions.add(line)
                            }
                        }
                        if (poems.isEmpty() && expressions.isEmpty()) {
                            callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                        } else {
                            callback(
                                Result.success(
                                    LexiconEntry(
                                        word = obj.optString("word").ifEmpty { word },
                                        category = obj.optString("category"),
                                        poems = poems,
                                        expressions = expressions,
                                        isGenerated = true
                                    )
                                )
                            )
                        }
                    } catch (e: Exception) {
                        callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }
    }

    /**
     * 改写版本解析：1) 干净的 JSON 数组；2) 按行切分并去掉行首编号；3) 整体作为一个版本。
     */
    fun parseVariants(raw: String): List<String> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1)
            }
            if (text.endsWith("```")) {
                text = text.dropLast(3).toString()
            }
            text = text.trim()
        }
        // 1) 干净的 JSON 数组
        try {
            val arr = JSONArray(text)
            val cleaned = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) cleaned.add(item)
            }
            if (cleaned.isNotEmpty()) return cleaned.take(3)
        } catch (e: Exception) {
        }
        // 2) 按行切分：去掉行首编号（如 1. / 1、/ ① / 一、），去掉空行
        val lines = text.split("\n")
            .map { stripVariantNumbering(it).trim() }
            .filter { it.isNotEmpty() }
        if (lines.size > 1) return lines.take(3)
        // 3) 兜底：整体作为一个版本（空文本则返回空列表，由调用方提示重试）
        return if (text.isEmpty()) emptyList() else listOf(text)
    }

    /** 去掉一行开头的编号：1. / 1、/ 1) / （1）/ ① / 一、，以及 - / • 项目符号 */
    private fun stripVariantNumbering(line: String): String {
        var s = line.trim()
        val digitPrefix = Regex("^(\\d+[.、)）]|（\\d+）|\\(\\d+\\))\\s*")
        s = digitPrefix.replaceFirst(s, "")
        if (s.isNotEmpty() && s[0] in '①'..'⑩') {
            s = s.substring(1).trim()
        }
        val cnPrefix = Regex("^[一二三四五六七八九十]+[、.．]\\s*")
        s = cnPrefix.replaceFirst(s, "")
        if (s.startsWith("- ")) s = s.substring(2)
        if (s.startsWith("• ")) s = s.substring(2)
        return s
    }

    /** 去掉可能的代码围栏 */
    private fun stripFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1)
            }
            if (text.endsWith("```")) {
                text = text.dropLast(3).toString()
            }
            text = text.trim()
        }
        return text
    }

    /**
     * 近义词 / 替换词生成：按程度 / 语气 / 场景分组，每组给词和一句话用法差别说明。
     * JSON 解析失败时按行切分兜底为一组。
     */
    fun generateSynonyms(word: String, callback: (Result<List<SynonymGroup>>) -> Unit) {
        val system = "你是中文写作助手。用户给一个情绪或状态词，你只返回一个 JSON 对象，格式固定为：" +
            "{\"groups\":[{\"title\":\"分组名，如程度/语气/场景\",\"items\":[{\"word\":\"词或短语\",\"note\":\"一句话用法差别说明\"}]}]}" +
            "要求：分组 2-4 组，每组 2-4 个词；词要贴合网文写作场景，多给动作化、画面感强的替代表达，少给干巴巴的同义词。只返回 JSON，不要任何解释，也不要加代码围栏。"
        chat(system, word) { result ->
            result.fold(
                onSuccess = { raw ->
                    val text = stripFences(raw)
                    try {
                        val obj = JSONObject(text)
                        val groups = mutableListOf<SynonymGroup>()
                        val groupArr = obj.optJSONArray("groups") ?: JSONArray()
                        for (i in 0 until groupArr.length()) {
                            val g = groupArr.getJSONObject(i)
                            val items = mutableListOf<SynonymItem>()
                            val itemArr = g.optJSONArray("items") ?: JSONArray()
                            for (j in 0 until itemArr.length()) {
                                val o = itemArr.getJSONObject(j)
                                val w = o.optString("word")
                                if (w.isNotEmpty()) {
                                    items.add(SynonymItem(w, o.optString("note")))
                                }
                            }
                            if (items.isNotEmpty()) {
                                groups.add(SynonymGroup(g.optString("title").ifEmpty { "AI 生成" }, items))
                            }
                        }
                        if (groups.isEmpty()) {
                            callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                        } else {
                            callback(Result.success(groups))
                        }
                    } catch (e: Exception) {
                        // 兜底：按行切分，整体作为一组
                        val lines = text.split("\n")
                            .map { stripVariantNumbering(it).trim() }
                            .filter { it.isNotEmpty() }
                        if (lines.isEmpty()) {
                            callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                        } else {
                            callback(
                                Result.success(
                                    listOf(
                                        SynonymGroup(
                                            "AI 生成",
                                            lines.map { SynonymItem(it, "") }
                                        )
                                    )
                                )
                            )
                        }
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }
    }

    /** 对话助手的一版台词 */
    data class DialogueDelivery(
        val style: String,
        val line: String
    )

    /** 对话助手结果：4 档情绪各一版 + 对话前动作 + 说完后反应 + 沉默细节 */
    data class DialogueResult(
        val deliveries: List<DialogueDelivery>,
        val beforeAction: String,
        val afterReaction: String,
        val silenceDetail: String
    )

    /**
     * 对话助手：输入一句台词 + 人物性格 + 情绪，返回 4 档情绪演绎 + 动作细节。
     * JSON 解析失败时把原始文本整体显示为一个版本。
     */
    fun generateDialogue(
        line: String,
        character: String,
        emotion: String,
        callback: (Result<DialogueResult>) -> Unit
    ) {
        val system = "你是网文人物对话助手。用户给一句台词、人物性格和一种情绪，你只返回一个 JSON 对象，格式固定为：" +
            "{\"deliveries\":[{\"style\":\"冷静克制\",\"line\":\"台词\"},{\"style\":\"愤怒质问\",\"line\":\"台词\"},{\"style\":\"失望隐忍\",\"line\":\"台词\"},{\"style\":\"表面平静内心崩溃\",\"line\":\"台词\"}],\"beforeAction\":\"对话前动作\",\"afterReaction\":\"说完后反应\",\"silenceDetail\":\"沉默细节\"}" +
            "要求：4 档情绪各给一版，台词要符合人物性格；动作和细节要具体、有画面感，不要概括。只返回 JSON，不要任何解释，也不要加代码围栏。"
        val user = buildString {
            append("台词：$line")
            if (character.isNotEmpty()) append("\n人物性格：$character")
            if (emotion.isNotEmpty()) append("\n情绪：$emotion")
        }
        chat(system, user) { result ->
            result.fold(
                onSuccess = { raw ->
                    val text = stripFences(raw)
                    try {
                        val obj = JSONObject(text)
                        val deliveries = mutableListOf<DialogueDelivery>()
                        val delArr = obj.optJSONArray("deliveries") ?: JSONArray()
                        for (i in 0 until delArr.length()) {
                            val o = delArr.getJSONObject(i)
                            val lineText = o.optString("line")
                            if (lineText.isNotEmpty()) {
                                deliveries.add(
                                    DialogueDelivery(
                                        o.optString("style").ifEmpty { "演绎" },
                                        lineText
                                    )
                                )
                            }
                        }
                        if (deliveries.isEmpty()) {
                            callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                        } else {
                            callback(
                                Result.success(
                                    DialogueResult(
                                        deliveries = deliveries,
                                        beforeAction = obj.optString("beforeAction"),
                                        afterReaction = obj.optString("afterReaction"),
                                        silenceDetail = obj.optString("silenceDetail")
                                    )
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // 兜底：原始文本整体显示为一个版本
                        if (text.isEmpty()) {
                            callback(Result.failure(AIException("接口返回的内容无法解析，请重试")))
                        } else {
                            callback(
                                Result.success(
                                    DialogueResult(
                                        deliveries = listOf(DialogueDelivery("AI 生成", text)),
                                        beforeAction = "",
                                        afterReaction = "",
                                        silenceDetail = ""
                                    )
                                )
                            )
                        }
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }
    }
}
