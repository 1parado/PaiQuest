package com.paradox.snapask.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * 刻意使用 HttpURLConnection + org.json（系统自带），不引入 OkHttp/Retrofit，压小 APK 体积。
 */
object LlmClient {

    /**
     * @param history 已有对话历史，Pair(role, content)，按顺序追加在 system 之后
     */
    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
    ): String {
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", system),
        )
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", messages)

        val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(300)}")
            return JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }
}
