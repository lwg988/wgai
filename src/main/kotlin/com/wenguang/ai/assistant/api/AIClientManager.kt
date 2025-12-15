package com.wenguang.ai.assistant.api

import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.lmstudio.LMStudioClient
import com.wenguang.ai.assistant.ollama.OllamaClient
import com.wenguang.ai.assistant.vllm.VLLMClient
import com.wenguang.ai.assistant.settings.ModelConfig
import com.wenguang.ai.assistant.settings.ModelProvider

/**
 * 统一的AI客户端管理器
 */
class AIClientManager {
    private val ollamaClient = OllamaClient()
    private val lmStudioClient = LMStudioClient.getInstance()
    private val vllmClient = VLLMClient.getInstance()
    private val universalApiClient = UniversalApiClient()

    // 请求隔离机制 - 使用锁保证线程安全
    private val requestLock = Any()
    @Volatile
    private var currentRequestId: String? = null
    @Volatile
    private var shouldStopRequest: Boolean = false
    // 存储已停止的请求ID，防止旧请求内容泄漏
    private val stoppedRequestIds = mutableSetOf<String>()

    /**
     * 发送聊天消息
     */
    fun sendChatMessage(
        modelConfig: ModelConfig,
        userMessage: String,
        historyMessages: List<Pair<String, String>> = emptyList(),
        requestId: String? = null,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val actualRequestId: String

        // 使用同步块确保请求ID和停止标志的原子性操作
        synchronized(requestLock) {
            // 生成新的请求ID
            actualRequestId = requestId ?: System.currentTimeMillis().toString()

            // 将旧请求标记为已停止（如果存在且不同）
            currentRequestId?.let { oldId ->
                if (oldId != actualRequestId) {
                    stoppedRequestIds.add(oldId)
                }
            }

            // 设置当前请求ID和重置停止标志
            currentRequestId = actualRequestId
            shouldStopRequest = false

            // 清理过期的已停止请求ID（保留最近100个）
            if (stoppedRequestIds.size > 100) {
                val toRemove = stoppedRequestIds.take(stoppedRequestIds.size - 100)
                stoppedRequestIds.removeAll(toRemove.toSet())
            }
        }

        // 包装onChunk回调，增加请求隔离检查
        val wrappedOnChunk: (String) -> Unit = { chunk ->
            val shouldProcess = synchronized(requestLock) {
                // 检查：不在已停止列表中 && 未被停止
                !stoppedRequestIds.contains(actualRequestId) && !shouldStopRequest
            }
            if (shouldProcess) {
                onChunk(chunk)
            }
        }

        // 包装onDone回调 - 简化逻辑，只检查是否被停止
        val wrappedOnDone: () -> Unit = {
            val shouldProcess = synchronized(requestLock) {
                val isStopped = stoppedRequestIds.contains(actualRequestId) || shouldStopRequest
                if (!isStopped) {
                    // 清除当前请求ID（如果是当前请求）
                    if (currentRequestId == actualRequestId) {
                        currentRequestId = null
                    }
                }
                !isStopped
            }
            if (shouldProcess) {
                onDone()
            }
        }
        
        when (modelConfig.provider) {
            ModelProvider.OLLAMA -> {
                sendOllamaChatMessage(modelConfig, userMessage, historyMessages, wrappedOnChunk, wrappedOnDone)
            }

            ModelProvider.LMSTUDIO -> {
                sendLMStudioChatMessage(modelConfig, userMessage, historyMessages, wrappedOnChunk, wrappedOnDone)
            }

            ModelProvider.VLLM -> {
                sendVLLMChatMessage(modelConfig, userMessage, historyMessages, wrappedOnChunk, wrappedOnDone)
            }

            ModelProvider.CHATGPT,
            ModelProvider.CLAUDE,
            ModelProvider.GEMINI,
            ModelProvider.MINIMAX,
            ModelProvider.MISTRAL,
            ModelProvider.META,
            ModelProvider.GROK,
            ModelProvider.DEEPSEEK,
            ModelProvider.QWEN,
            ModelProvider.DOUBAO,
            ModelProvider.WENXIN,
            ModelProvider.HUNYUAN,
            ModelProvider.SPARK,
            ModelProvider.STEPFUN,
            ModelProvider.KIMI,
            ModelProvider.ZHIPU,
            ModelProvider.COHERE,
            ModelProvider.SILICONFLOW,
            ModelProvider.HUGGINGFACE,
            ModelProvider.MODELSCOPE,
            ModelProvider.OPENROUTER,
            ModelProvider.CUSTOM_API -> {
                sendUniversalApiChatMessage(modelConfig, userMessage, historyMessages, wrappedOnChunk, wrappedOnDone)
            }
        }
    }

    /**
     * 清除聊天历史
     */
    fun clearChatHistory() {
        ollamaClient.clearChatHistory()
        // 通用API不需要清除历史，因为它是无状态的
    }

    /**
     * 停止当前请求
     */
    fun stopCurrentRequest() {
        println(I18nManager.getMessage("ai.client.stop.request"))
        synchronized(requestLock) {
            shouldStopRequest = true
            // 将当前请求ID添加到已停止列表
            currentRequestId?.let { id ->
                stoppedRequestIds.add(id)
            }
            currentRequestId = null
        }
    }

    /**
     * 发送Ollama聊天消息
     */
    private fun sendOllamaChatMessage(
        modelConfig: ModelConfig,
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // 临时设置Ollama客户端的配置
        ollamaClient.updateConfiguration(modelConfig.apiUrl, modelConfig.modelName)
        ollamaClient.sendChatMessage(userMessage, onChunk, onDone)
    }

    /**
     * 发送LM Studio聊天消息
     */
    private fun sendLMStudioChatMessage(
        modelConfig: ModelConfig,
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // 临时设置LM Studio客户端的配置
        lmStudioClient.updateConfiguration(modelConfig.apiUrl, modelConfig.modelName)
        lmStudioClient.sendChatMessage(userMessage, historyMessages, onChunk, onDone)
    }

    /**
     * 发送vLLM聊天消息
     */
    private fun sendVLLMChatMessage(
        modelConfig: ModelConfig,
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // 临时设置vLLM客户端的配置
        vllmClient.updateConfiguration(modelConfig.apiUrl, modelConfig.modelName, modelConfig.apiKey)
        vllmClient.sendChatMessage(userMessage, historyMessages, onChunk, onDone)
    }

    /**
     * 发送通用API聊天消息
     */
    private fun sendUniversalApiChatMessage(
        modelConfig: ModelConfig,
        userMessage: String,
        historyMessages: List<Pair<String, String>>,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // 构建消息历史
        val messages = mutableListOf<ApiMessage>()

        // 添加系统消息
        messages.add(ApiMessage(role = "system", content = I18nManager.getMessage("ai.system.prompt.default")))

        // 添加历史消息
        historyMessages.forEach { (role, content) ->
            messages.add(ApiMessage(role = role, content = content))
        }

        // 添加当前用户消息
        messages.add(ApiMessage(role = "user", content = userMessage))

        universalApiClient.sendChatMessage(modelConfig, messages, onChunk, onDone)
    }


    companion object {
        @Volatile
        private var INSTANCE: AIClientManager? = null

        fun getInstance(): AIClientManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AIClientManager().also { INSTANCE = it }
            }
        }
    }
} 