package com.wenguang.ai.assistant.vllm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wenguang.ai.assistant.api.ApiException
import com.wenguang.ai.assistant.api.ApiMessage
import com.wenguang.ai.assistant.api.ApiRequest
import com.wenguang.ai.assistant.api.ApiResponse
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.AppSettings
import com.wenguang.ai.assistant.settings.ModelConfig
import com.wenguang.ai.assistant.settings.ModelProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.*

/**
 * vLLM客户端 - 使用OpenAI兼容的API
 *
 * vLLM是一个高性能的LLM推理引擎，提供OpenAI兼容的API接口
 * 默认端口: 8000
 * API端点: /v1/chat/completions
 */
class VLLMClient {
    /**
     * 优化的ObjectMapper配置 - 提高JSON序列化性能
     * - 注册Kotlin模块支持
     * - 忽略未知属性避免解析错误
     */
    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * 使用ThreadPoolExecutor替代newCachedThreadPool
     * - 核心线程数: 2 (处理轻量级任务)
     * - 最大线程数: 10 (限制并发数量防止OOM)
     * - 空闲线程存活时间: 60秒
     * - 有界队列: 100个任务，防止内存耗尽
     */
    private val executorService = ThreadPoolExecutor(
        2,  // 核心线程数
        10, // 最大线程数
        60L, TimeUnit.SECONDS, // 空闲线程存活时间
        LinkedBlockingQueue<Runnable>(100), // 有界队列，防止OOM
        ThreadFactory { r ->
            Thread(r, "vllm-client-${System.currentTimeMillis()}-${r.hashCode()}")
        }
    )

    private var vllmApiUrl = "http://localhost:8000/v1"
    private var currentChatModel = ""
    private var apiKey: String? = null

    /**
     * 更新配置
     */
    fun updateConfiguration(apiUrl: String, modelName: String, apiKey: String? = null) {
        vllmApiUrl = apiUrl.removeSuffix("/")
        currentChatModel = modelName
        this.apiKey = apiKey?.takeIf { it.isNotBlank() }
    }

    /**
     * 发送聊天请求
     */
    fun sendChatMessage(
        userMessage: String,
        historyMessages: List<Pair<String, String>> = emptyList(),
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        executorService.submit {
            try {
                sendChatMessageWithRetry(
                    userMessage,
                    historyMessages,
                    onChunk,
                    onDone,
                    maxRetries = 3,
                    currentAttempt = 1
                )
            } catch (e: Exception) {
                println("vLLM chat request failed: $e")
                onChunk(I18nManager.getMessage("api.error", e.message ?: "Unknown error"))
                onDone()
            }
        }
    }

    /**
     * 带重试机制的发送聊天请求
     */
    private fun sendChatMessageWithRetry(
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        maxRetries: Int,
        currentAttempt: Int
    ) {
        try {
            streamRequest(userMessage, historyMessages, onChunk, onDone)
        } catch (e: Exception) {
            println(I18nManager.getMessage("log.vllm.request.exception", currentAttempt, maxRetries, e.message ?: "Unknown error"))

            if (currentAttempt < maxRetries && ApiException.isRetryable(e)) {
                val delaySeconds = calculateRetryDelay(currentAttempt)
                onChunk(I18nManager.getMessage("api.retrying", delaySeconds, currentAttempt + 1, maxRetries))

                Thread.sleep(delaySeconds * 1000L)
                sendChatMessageWithRetry(userMessage, historyMessages, onChunk, onDone, maxRetries, currentAttempt + 1)
            } else {
                println(I18nManager.getMessage("log.vllm.request.final_failed"))
                val finalErrorMessage = ApiException.createFromModelConfig(
                    ModelConfig(
                        apiUrl = vllmApiUrl,
                        modelName = currentChatModel,
                        provider = ModelProvider.VLLM
                    ),
                    exception = e
                ).message ?: "Unknown error"
                onChunk(finalErrorMessage)
                onDone()
            }
        }
    }

    /**
     * 计算重试延迟（指数退避）
     */
    private fun calculateRetryDelay(attempt: Int): Int {
        return minOf(2 * attempt * attempt, 30) // 最大30秒
    }

    /**
     * 执行流式请求
     */
    private fun streamRequest(
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val messages = mutableListOf<ApiMessage>()

        // 添加系统消息
        val systemPrompt = AppSettings.instance.state.getEffectiveSystemPrompt()
        messages.add(ApiMessage(role = "system", content = systemPrompt))

        // 添加历史消息
        historyMessages.forEach { (role, content) ->
            messages.add(ApiMessage(role = role, content = content))
        }

        // 添加当前用户消息
        messages.add(ApiMessage(role = "user", content = userMessage))

        val request = ApiRequest(
            model = currentChatModel,
            messages = messages,
            stream = true,
            temperature = 0.7f,
            maxTokens = 8192
        )

        val url = URL("$vllmApiUrl/chat/completions")
        val connection = url.openConnection() as HttpURLConnection

        try {
            // 配置连接
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")

            // 如果配置了API Key，添加认证头
            apiKey?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            connection.doOutput = true
            // 优化HTTP超时配置：避免线程长时间阻塞
            // 连接超时：30秒（建立连接的最大时间）
            // 读取超时：120秒（vLLM推理可能较慢，给更多时间）
            connection.connectTimeout = 30000
            connection.readTimeout = 120000

            // 发送请求
            val requestBody = objectMapper.writeValueAsString(request)
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 处理流式响应
                BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { processStreamLine(it, onChunk) }
                    }
                }
            } else {
                // 处理错误响应
                val errorStream = connection.errorStream
                val errorMessage = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "HTTP $responseCode"
                }

                // 解析并提供友好的错误信息
                val friendlyError = parseVLLMError(errorMessage, responseCode)
                throw RuntimeException(friendlyError)
            }
        } finally {
            connection.disconnect()
            onDone()
        }
    }

    /**
     * 处理流式响应行
     */
    private fun processStreamLine(line: String, onChunk: (String) -> Unit) {
        if (line.startsWith("data: ")) {
            val jsonData = line.substring(6).trim()

            if (jsonData == "[DONE]") {
                return
            }

            if (jsonData.isEmpty()) {
                return
            }

            try {
                val response = objectMapper.readValue(jsonData, ApiResponse::class.java)
                val content = response.choices?.firstOrNull()?.delta?.content
                if (!content.isNullOrEmpty()) {
                    onChunk(content)
                }
            } catch (e: Exception) {
                println(I18nManager.getMessage("log.vllm.stream.parse_failed", jsonData, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 解析vLLM错误并提供友好的提示信息
     */
    private fun parseVLLMError(errorResponse: String, statusCode: Int): String {
        try {
            // 尝试解析JSON错误响应
            val errorJson = objectMapper.readTree(errorResponse)
            val error = errorJson.get("error")

            if (error != null) {
                val errorType = error.get("type")?.asText()
                val errorMessage = error.get("message")?.asText()
                val errorCode = error.get("code")?.asText()

                return when {
                    errorCode == "model_not_found" || errorMessage?.contains("not found") == true -> {
                        I18nManager.getMessage("api.error.vllm.model_not_found", currentChatModel)
                    }

                    errorType == "invalid_request_error" -> {
                        I18nManager.getMessage("api.error.vllm.invalid_request", errorMessage ?: "未知请求错误")
                    }

                    statusCode == 500 -> {
                        I18nManager.getMessage("api.error.vllm.server_error")
                    }

                    statusCode == 404 -> {
                        I18nManager.getMessage("api.error.vllm.endpoint_not_found")
                    }

                    statusCode == 401 || statusCode == 403 -> {
                        I18nManager.getMessage("api.error.vllm.auth_failed")
                    }

                    else -> {
                        I18nManager.getMessage("api.error.vllm.generic", errorMessage ?: "未知错误")
                    }
                }
            }
        } catch (e: Exception) {
            // 如果JSON解析失败，检查常见的错误模式
            when {
                errorResponse.contains("not found", ignoreCase = true) -> {
                    return I18nManager.getMessage("api.error.vllm.model_not_found", currentChatModel)
                }

                errorResponse.contains("Connection refused", ignoreCase = true) -> {
                    return I18nManager.getMessage("api.error.vllm.connection_refused")
                }

                errorResponse.contains("timeout", ignoreCase = true) -> {
                    return I18nManager.getMessage("api.error.vllm.timeout")
                }

                statusCode == 404 -> {
                    return I18nManager.getMessage("api.error.vllm.endpoint_not_found")
                }

                statusCode == 401 || statusCode == 403 -> {
                    return I18nManager.getMessage("api.error.vllm.auth_failed")
                }
            }
        }

        // 默认错误信息
        return I18nManager.getMessage("api.error.vllm.generic", "HTTP $statusCode")
    }

    companion object {
        @Volatile
        private var INSTANCE: VLLMClient? = null

        fun getInstance(): VLLMClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VLLMClient().also { INSTANCE = it }
            }
        }
    }
}
