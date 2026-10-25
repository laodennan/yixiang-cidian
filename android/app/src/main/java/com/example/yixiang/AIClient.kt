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
        val system = "你是中文文学意象词典的编纂者。用户给一个意象词，你只返回一个 JSON 对象，格式固定为：" +
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
                        if (poems.isEmpty() || expressions.isEmpty()) {
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
}
