package com.wenguang.ai.assistant.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.ApiResponseFormat
import com.wenguang.ai.assistant.settings.ModelConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 通用API消息格式（兼容OpenAI格式）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiMessage(
    val role: String?,
    val content: String?
)

/**
 * 通用API请求格式（兼容OpenAI、Gemini、Cursor等）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    @JsonProperty("top_p")
    val topP: Float? = null,
    @JsonProperty("frequency_penalty")
    val frequencyPenalty: Float? = null,
    @JsonProperty("presence_penalty")
    val presencePenalty: Float? = null,
    // Gemini特殊参数
    @JsonProperty("safety_settings")
    val safetySettings: List<Map<String, Any>>? = null,
    // 其他通用参数
    val stop: List<String>? = null
)

/**
 * 通用API响应格式
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiChoice(
    val index: Int? = null,
    val message: ApiMessage? = null,
    val delta: ApiMessage? = null,
    @JsonProperty("finish_reason")
    val finishReason: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int? = null,
    @JsonProperty("completion_tokens")
    val completionTokens: Int? = null,
    @JsonProperty("total_tokens")
    val totalTokens: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiResponse(
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ApiChoice>? = null,
    val usage: ApiUsage? = null,
    // Gemini特殊字段
    val candidates: List<ApiChoice>? = null,
    val content: String? = null,
    // 错误处理
    val error: Map<String, Any>? = null
)

/**
 * 通用API客户端，支持多种第三方API
 */
class UniversalApiClient : AutoCloseable {
    /**
     * 优化的ObjectMapper配置 - 提高JSON序列化性能
     * - 注册Kotlin模块支持
     * - 忽略未知属性避免解析错误
     * - 优化性能配置
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
            Thread(r, "ai-client-${System.currentTimeMillis()}-${r.hashCode()}")
        }
    )

    /**
     * 发送聊天请求（流式）
     */
    fun sendChatMessage(
        modelConfig: ModelConfig,
        messages: List<ApiMessage>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        executorService.submit {
            sendChatMessageWithRetry(modelConfig, messages, onChunk, onDone, maxRetries = 3, currentAttempt = 1)
        }
    }

    /**
     * 带重试机制的发送聊天请求
     */
    private fun sendChatMessageWithRetry(
        modelConfig: ModelConfig,
        messages: List<ApiMessage>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit,
        maxRetries: Int,
        currentAttempt: Int
    ) {
        return try {
            streamRequest(modelConfig, messages, onChunk, onDone)
        } catch (e: Exception) {
            println(
                I18nManager.getMessage(
                    "log.universalapi.request.exception",
                    currentAttempt,
                    maxRetries,
                    e.message ?: "Unknown error"
                )
            )

            if (currentAttempt < maxRetries && ApiException.isRetryable(e)) {
                val delaySeconds = calculateRetryDelay(currentAttempt)

                onChunk(I18nManager.getMessage("api.retrying", delaySeconds, currentAttempt + 1, maxRetries))

                Thread.sleep(delaySeconds * 1000L)
                sendChatMessageWithRetry(modelConfig, messages, onChunk, onDone, maxRetries, currentAttempt + 1)
            } else {
                println(I18nManager.getMessage("log.universalapi.request.final_failed"))
                // 使用新的ApiException来格式化最终失败的错误消息
                val finalErrorMessage = if (e is ApiException) {
                    e.message ?: "Unknown error"
                } else {
                    ApiException.createFromModelConfig(modelConfig, exception = e).message ?: "Unknown error"
                }
                onChunk(finalErrorMessage)
                onDone()
            }
        }
    }

    /**
     * 判断是否应该重试 - 已被ApiException.isRetryable替代
     * @deprecated 使用ApiException.isRetryable代替
     */
    @Deprecated("Use ApiException.isRetryable instead")
    private fun shouldRetry(exception: Exception): Boolean {
        return ApiException.isRetryable(exception)
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
        modelConfig: ModelConfig,
        messages: List<ApiMessage>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val request = buildApiRequest(modelConfig, messages, true)

        if (request.stream) {
            streamRequest(modelConfig, request, onChunk, onDone)
        } else {
            // 非流式请求
            val response = sendNonStreamRequest(modelConfig, request)
            val content = extractContentFromResponse(response)
            if (content.isNotEmpty()) {
                onChunk(content)
            }
            onDone()
        }
    }

    /**
     * 构建API请求对象
     */
    private fun buildApiRequest(
        modelConfig: ModelConfig,
        messages: List<ApiMessage>,
        stream: Boolean
    ): ApiRequest {
        return ApiRequest(
            model = modelConfig.modelName,
            messages = messages,
            stream = stream,
            temperature = modelConfig.temperature?.takeIf { it > 0 },
            maxTokens = modelConfig.maxTokens?.takeIf { it > 0 },
            topP = 0.9f,
            frequencyPenalty = 0.0f,
            presencePenalty = 0.0f
        )
    }

    /**
     * 发送流式请求
     */
    private fun streamRequest(
        modelConfig: ModelConfig,
        request: ApiRequest,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        try {
            val finalUrl = buildApiUrl(modelConfig.apiUrl)

            val url = URL(finalUrl)
            val connection = url.openConnection() as HttpURLConnection

            setupHttpConnection(connection, modelConfig)

            // 发送请求
            val requestBody = objectMapper.writeValueAsString(request)

            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }
            }

            // 检查响应状态
            val responseCode = connection.responseCode

            if (responseCode != 200) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                println("API request failed, status code: $responseCode, error response: $errorText")

                // 对于可重试的错误，抛出异常让重试机制处理
                if (ApiException.isRetryable(responseCode)) {
                    throw ApiException.createFromModelConfig(modelConfig, responseCode, errorText)
                }

                // 处理其他不可重试的错误，使用新的ApiException统一处理
                val apiException = ApiException.createFromModelConfig(modelConfig, responseCode, errorText)
                onChunk(apiException.message ?: "Unknown error")
                onDone()
                return
            }

            // 读取流式响应
            connection.inputStream.bufferedReader().use { reader ->
                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null) {
                    lineCount++
                    line?.let {
                        processStreamLine(it, modelConfig, onChunk)
                    }
                }
            }

            onDone()

        } catch (e: Exception) {
            println(I18nManager.getMessage("log.universalapi.stream.request_failed", e.message ?: "Unknown error"))
            // 使用新的ApiException来格式化流式请求错误
            val apiException = ApiException.createFromModelConfig(modelConfig, exception = e)
            onChunk(apiException.message ?: "Unknown error")
            onDone()
        }
    }

    /**
     * 发送非流式请求
     */
    private fun sendNonStreamRequest(
        modelConfig: ModelConfig,
        request: ApiRequest
    ): ApiResponse {
        val url = URL(buildApiUrl(modelConfig.apiUrl))
        val connection = url.openConnection() as HttpURLConnection

        setupHttpConnection(connection, modelConfig)

        // 发送请求
        connection.outputStream.use { outputStream ->
            OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                writer.write(objectMapper.writeValueAsString(request.copy(stream = false)))
                writer.flush()
            }
        }

        // 读取响应
        val responseText = if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            println("API request failed, status code: ${connection.responseCode}, error message: $errorText")
            throw ApiException.createFromModelConfig(modelConfig, statusCode = connection.responseCode)
        }

        return objectMapper.readValue(responseText, ApiResponse::class.java)
    }

    /**
     * 设置HTTP连接
     */
    private fun setupHttpConnection(connection: HttpURLConnection, modelConfig: ModelConfig) {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "WingCode-Assistant/1.0")


        // 设置认证头
        if (!modelConfig.apiKey.isNullOrEmpty()) {
            val authType = when {
                modelConfig.apiUrl.contains("deepseek", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "DeepSeek Bearer"
                }

                modelConfig.apiUrl.contains("dashscope", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("aliyun", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("qwen", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "通义千问 Bearer"
                }

                modelConfig.apiUrl.contains("x.ai", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("grok", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "Grok Bearer"
                }

                modelConfig.apiUrl.contains("openai", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("chatgpt", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "OpenAI Bearer"
                }

                modelConfig.apiUrl.contains("gemini", ignoreCase = true) -> {
                    connection.setRequestProperty("x-goog-api-key", modelConfig.apiKey)
                    "Gemini API Key"
                }

                modelConfig.apiUrl.contains("anthropic", ignoreCase = true) -> {
                    connection.setRequestProperty("x-api-key", modelConfig.apiKey)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                    "Anthropic API Key"
                }

                modelConfig.apiUrl.contains("moonshot", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("kimi", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "Kimi Bearer"
                }

                modelConfig.apiUrl.contains("bigmodel.cn", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("zhipu", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "智谱AI Bearer"
                }

                modelConfig.apiUrl.contains("minimaxi", ignoreCase = true) ||
                        modelConfig.apiUrl.contains("minimax", ignoreCase = true) -> {
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "MiniMax Bearer"
                }

                else -> {
                    // 默认使用Bearer认证
                    connection.setRequestProperty("Authorization", "Bearer ${modelConfig.apiKey}")
                    "默认Bearer"
                }
            }
        } else {
            println(I18nManager.getMessage("log.universalapi.no_api_key"))
        }

        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
    }

    /**
     * 构建API URL
     */
    private fun buildApiUrl(baseUrl: String): String {
        var url = baseUrl.trim()

        // 确保以/结尾
        if (!url.endsWith("/")) {
            url += "/"
        }

        // 如果没有指定端点，添加默认端点
        if (!url.contains("/chat/completions") && !url.contains("/v1/messages")) {
            url += "chat/completions"
        }

        return url
    }

    /**
     * 处理流式响应行
     */
    private fun processStreamLine(line: String, modelConfig: ModelConfig, onChunk: (String) -> Unit) {
        try {
            if (line.trim().isEmpty()) return

            // 处理Server-Sent Events格式
            val jsonData = when {
                line.startsWith("data: ") -> line.substring(6).trim()
                line.startsWith("data:") -> line.substring(5).trim()
                line.trim().startsWith("{") -> line.trim() // 直接是JSON数据
                else -> {
                    return
                }
            }

            if (jsonData == "[DONE]" || jsonData.isEmpty()) {
                return
            }


            try {
                val response = objectMapper.readValue(jsonData, ApiResponse::class.java)

                // 检查错误
                response.error?.let { error ->
                    val errorMessage = error["message"]?.toString() ?: "API返回错误"
                    println("API returned error: $errorMessage")
                    onChunk(I18nManager.getMessage("api.error", "Error: {0}", errorMessage))
                    return
                }

                val content = extractContentFromResponseWithConfig(response, modelConfig)
                if (content.isNotEmpty()) {
                    onChunk(content)
                }
            } catch (jsonException: Exception) {
                println(
                    I18nManager.getMessage(
                        "log.universalapi.json_parse_failed",
                        jsonData,
                        jsonException.message ?: "Unknown error"
                    )
                )

                // 对于某些API（如某些自定义格式），尝试直接提取文本
                if (jsonData.contains("\"content\"")) {
                    try {
                        // 尝试简单的文本提取
                        val contentMatch = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"").find(jsonData)
                        contentMatch?.groupValues?.get(1)?.let { extractedContent ->
                            if (extractedContent.isNotEmpty()) {
                                onChunk(extractedContent)
                            }
                        }
                    } catch (regexException: Exception) {
                        // Ignore regex extraction failures
                    }
                }
            }
        } catch (e: Exception) {
            println(
                I18nManager.getMessage(
                    "log.universalapi.stream.line_processing_failed",
                    line,
                    e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * 从响应中提取内容
     */
    private fun extractContentFromResponse(response: ApiResponse): String {
        // 检查错误
        response.error?.let { error ->
            val message = error["message"]?.toString() ?: "Unknown API error"
            throw Exception("API错误: $message")
        }

        // 尝试从不同字段提取内容
        return response.choices?.firstOrNull()?.message?.content
            ?: response.choices?.firstOrNull()?.delta?.content
            ?: response.candidates?.firstOrNull()?.message?.content
            ?: response.content
            ?: ""
    }

    /**
     * 根据模型配置提取内容（支持自定义路径）
     */
    private fun extractContentFromResponseWithConfig(response: ApiResponse, modelConfig: ModelConfig): String {
        // 检查错误
        response.error?.let { error ->
            val message = error["message"]?.toString() ?: "Unknown API error"
            throw Exception("API错误: $message")
        }

        return when (modelConfig.responseFormat) {
            ApiResponseFormat.OPENAI -> {
                response.choices?.firstOrNull()?.message?.content
                    ?: response.choices?.firstOrNull()?.delta?.content
                    ?: ""
            }

            ApiResponseFormat.ANTHROPIC -> {
                // Anthropic格式处理
                response.choices?.firstOrNull()?.message?.content
                    ?: response.choices?.firstOrNull()?.delta?.content
                    ?: response.content
                    ?: ""
            }

            ApiResponseFormat.GEMINI -> {
                // Gemini格式处理
                response.candidates?.firstOrNull()?.message?.content
                    ?: response.content
                    ?: ""
            }

            ApiResponseFormat.CUSTOM -> {
                // 自定义格式，使用JSON路径提取
                extractContentByPath(response, modelConfig.customContentPath)
            }
        }
    }

    /**
     * 根据JSON路径提取内容
     */
    private fun extractContentByPath(response: ApiResponse, path: String): String {
        try {
            val jsonMap = objectMapper.convertValue(response, Map::class.java)
            return getValueByPath(jsonMap, path)?.toString() ?: ""
        } catch (e: Exception) {
            println(
                I18nManager.getMessage(
                    "log.universalapi.content_extraction_failed",
                    path,
                    e.message ?: "Unknown error"
                )
            )
            return ""
        }
    }

    /**
     * 根据路径从Map中获取值
     */
    private fun getValueByPath(map: Map<*, *>, path: String): Any? {
        val parts = path.split(".")
        var current: Any? = map

        for (part in parts) {
            when {
                current is Map<*, *> -> {
                    if (part.contains("[") && part.contains("]")) {
                        // 处理数组索引，如 "choices[0]"
                        val key = part.substring(0, part.indexOf("["))
                        val index = part.substring(part.indexOf("[") + 1, part.indexOf("]")).toIntOrNull() ?: 0
                        val list = current[key] as? List<*>
                        current = list?.getOrNull(index)
                    } else {
                        current = current[part]
                    }
                }

                current is List<*> -> {
                    val index = part.toIntOrNull() ?: 0
                    current = current.getOrNull(index)
                }

                else -> return null
            }
        }

        return current
    }

    /**
     * 关闭线程池，释放资源
     */
    override fun close() {
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
} 