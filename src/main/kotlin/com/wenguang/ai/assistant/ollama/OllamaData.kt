package com.wenguang.ai.assistant.ollama

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Ollama请求数据类
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OllamaRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val options: Map<String, Any>? = null,
    val messages: List<ChatMessage>? = null,
    val system: String? = null,
    val context: List<Int>? = null,
    val format: String? = null
)

/**
 * Ollama响应数据类
 */
data class OllamaResponse(
    val model: String,
    val response: String,
    val done: Boolean,
    val context: List<Int>? = null,
    @JsonProperty("total_duration") val totalDuration: Long? = null,
    @JsonProperty("load_duration") val loadDuration: Long? = null,
    @JsonProperty("prompt_eval_count") val promptEvalCount: Int? = null,
    @JsonProperty("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @JsonProperty("eval_count") val evalCount: Int? = null,
    @JsonProperty("eval_duration") val evalDuration: Long? = null
)

/**
 * Ollama选项数据类
 */
data class OllamaOptions(
    val temperature: Float = 0.8f,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val num_predict: Int? = null,
    val stop: List<String>? = null,
    val num_ctx: Int? = 8192,
    val repeat_penalty: Float? = null,
    val num_gpu: Int? = null,
    val seed: Int? = null,
    val mirostat: Int? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["temperature"] = temperature
        top_p?.let { map["top_p"] = it }
        top_k?.let { map["top_k"] = it }
        num_predict?.let { map["num_predict"] = it }
        stop?.let { map["stop"] = it }
        num_ctx?.let { map["num_ctx"] = it }
        repeat_penalty?.let { map["repeat_penalty"] = it }
        num_gpu?.let { map["num_gpu"] = it }
        seed?.let { map["seed"] = it }
        mirostat?.let { map["mirostat"] = it }
        return map
    }
}

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val role: String,    // "user" 或 "assistant"
    val content: String
)