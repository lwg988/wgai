package com.wenguang.ai.assistant.completion

import com.google.gson.Gson
import com.wenguang.ai.assistant.i18n.I18nManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Tabby服务客户端
 */
class TabbyClient(
    private val endpoint: String,
    private val token: String
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val gson = Gson()

    /**
     * 请求代码补全
     */
    suspend fun requestCompletion(
        language: String,
        code: String,
        prefix: String,
        suffix: String
    ): String? {
        return try {
            val request = mapOf(
                "language" to language,
                "segments" to mapOf(
                    "prefix" to prefix,
                    "suffix" to suffix
                ),
                "max_lines" to 15,
                "temperature" to 0.1
            )

            val jsonBody = gson.toJson(request)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$endpoint/v1/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(30))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val completionResponse = gson.fromJson(response.body(), CompletionResponse::class.java)
                val completionText = completionResponse.choices?.firstOrNull()?.text

                completionText
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 测试连接
     */
    fun testConnection(): Boolean {
        return try {
            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("$endpoint/v1/health"))
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 补全响应数据类
 */
data class CompletionResponse(
    val id: String?,
    val choices: List<CompletionChoice>?
)

data class CompletionChoice(
    val index: Int?,
    val text: String?
)
