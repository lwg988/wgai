package com.wenguang.ai.assistant.ollama

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wenguang.ai.assistant.api.ApiException
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.AppSettings
import com.wenguang.ai.assistant.settings.ModelConfig
import com.wenguang.ai.assistant.settings.ModelProvider
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class OllamaClient : AutoCloseable {
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
            Thread(r, "ollama-client-${System.currentTimeMillis()}-${r.hashCode()}")
        }
    )

    private var ollamaApiUrl = "http://localhost:11434"
    private var currentChatModel = ""

    // 聊天历史记录管理器
    private val chatHistoryManager = ChatHistoryManager()

    /**
     * 更新配置
     */
    fun updateConfiguration(apiUrl: String, modelName: String) {
        ollamaApiUrl = apiUrl.removeSuffix("/")
        currentChatModel = modelName
    }

    /**
     * 发送聊天请求
     */
    fun sendChatMessage(userMessage: String, onChunk: (String) -> Unit, onDone: () -> Unit) {
        executorService.submit {
            try {
                val model = currentChatModel

                // 添加用户消息到历史记录
                chatHistoryManager.addUserMessage(userMessage)

                // 系统提示词
                val systemPrompt = AppSettings.instance.state.systemPrompt

                // 获取聊天历史
                val messages = chatHistoryManager.getMessages()
                val contextIds = chatHistoryManager.getContextIds()

                val options = OllamaOptions(temperature = 0.7f).toMap()

                // 基于是否有历史记录选择不同的API调用方式
                val request = if (messages.size > 1 && contextIds != null && contextIds.isNotEmpty()) {
                    // 有历史记录和上下文ID时使用context方式
                    OllamaRequest(
                        model = model,
                        prompt = userMessage,
                        stream = true,
                        options = options,
                        context = contextIds
                    )
                } else if (messages.size > 1) {
                    // 只有历史记录但没有上下文ID时使用messages
                    OllamaRequest(
                        model = model,
                        prompt = "",
                        stream = true,
                        options = options,
                        messages = messages,
                        system = systemPrompt
                    )
                } else {
                    // 第一条消息，直接使用prompt
                    OllamaRequest(
                        model = model,
                        prompt = "$systemPrompt\n\n${userMessage}",
                        stream = true,
                        options = options
                    )
                }

                // 创建一个StringBuilder来收集完整响应
                val fullResponse = StringBuilder()

                // 流式处理响应
                streamResponse(
                    request = request,
                    onChunk = { chunk ->
                        fullResponse.append(chunk)
                        onChunk(chunk)
                    },
                    onContextIds = { ids ->
                        // 更新上下文ID
                        if (ids != null && ids.isNotEmpty()) {
                            chatHistoryManager.updateContextIds(ids)
                        }
                    },
                    onDone = {
                        // 添加助手响应到历史记录
                        chatHistoryManager.addAssistantMessage(fullResponse.toString())
                        onDone()
                    }
                )
            } catch (e: Exception) {
                println(I18nManager.getMessage("log.ollama.send_chat_failed", e.message ?: "Unknown error"))
                // 创建Ollama模型配置用于错误处理
                val modelConfig = ModelConfig(
                    id = "ollama-current",
                    name = "Ollama",
                    provider = ModelProvider.OLLAMA,
                    modelName = currentChatModel,
                    apiUrl = ollamaApiUrl,
                    enabled = true
                )
                val apiException = ApiException.createFromModelConfig(modelConfig, exception = e)
                onChunk(apiException.message ?: "Unknown error")
                onDone()
            }
        }
    }

    /**
     * 清空聊天历史
     */
    fun clearChatHistory() {
        chatHistoryManager.clear()
    }

    /**
     * 流式处理响应
     */
    private fun streamResponse(
        request: OllamaRequest,
        onChunk: (String) -> Unit,
        onContextIds: (List<Int>?) -> Unit,
        onDone: () -> Unit
    ) {
        try {
            val url = URL("$ollamaApiUrl/api/generate")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            // 优化HTTP超时配置：避免线程长时间阻塞
            // 连接超时：30秒（建立连接的最大时间）
            // 读取超时：60秒（读取数据的最大时间）
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            // 发送请求
            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(objectMapper.writeValueAsString(request))
                    writer.flush()
                }
            }

            // 读取流式响应
            connection.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { jsonLine ->
                        try {
                            val response = objectMapper.readValue<OllamaResponse>(jsonLine)

                            // 处理响应内容
                            response.response?.let { content ->
                                if (content.isNotEmpty()) {
                                    onChunk(content)
                                }
                            }

                            // 如果响应完成，处理上下文ID
                            if (response.done == true) {
                                onContextIds(response.context)
                                onDone()
                                return@use
                            }
                        } catch (e: Exception) {
                            // Ignore parsing errors for individual lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println(I18nManager.getMessage("log.ollama.stream_response_failed", e.message ?: "Unknown error"))
            // 创建Ollama模型配置用于错误处理
            val modelConfig = ModelConfig(
                id = "ollama-current",
                name = "Ollama",
                provider = ModelProvider.OLLAMA,
                modelName = currentChatModel,
                apiUrl = ollamaApiUrl,
                enabled = true
            )
            val apiException = ApiException.createFromModelConfig(modelConfig, exception = e)
            onChunk(apiException.message ?: "Unknown error")
            onDone()
        }
    }

    /**
     * 聊天历史记录管理器
     */
    private class ChatHistoryManager {
        private val messages = mutableListOf<ChatMessage>()
        private var contextIds: List<Int>? = null

        fun addUserMessage(content: String) {
            messages.add(ChatMessage(role = "user", content = content))
        }

        fun addAssistantMessage(content: String) {
            messages.add(ChatMessage(role = "assistant", content = content))
        }

        fun getMessages(): List<ChatMessage> = messages.toList()

        fun getContextIds(): List<Int>? = contextIds

        fun updateContextIds(ids: List<Int>?) {
            contextIds = ids
        }

        fun clear() {
            messages.clear()
            contextIds = null
        }
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