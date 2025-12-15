package com.wenguang.ai.assistant.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.ModelConfig
import com.wenguang.ai.assistant.settings.ModelProvider

/**
 * API异常处理类
 * 统一处理各种API调用异常并提供用户友好且详细的错误信息
 */
class ApiException(
    val apiType: ApiType,
    val statusCode: Int? = null,
    val errorResponse: String? = null,
    val originalException: Exception? = null,
    message: String
) : Exception(message) {

    enum class ApiType {
        OLLAMA,
        THIRD_PARTY_API,
        OFFICIAL_API,
        UNKNOWN
    }

    companion object {
        private val objectMapper = ObjectMapper()

        /**
         * 创建Ollama连接异常
         */
        fun createOllamaException(exception: Exception): ApiException {
            val message = exception.message ?: "Unknown connection error"
            val userFriendlyMessage = formatOllamaError(message)

            return ApiException(
                apiType = ApiType.OLLAMA,
                originalException = exception,
                message = userFriendlyMessage
            )
        }

        /**
         * 创建第三方API异常
         */
        fun createThirdPartyApiException(
            statusCode: Int,
            errorResponse: String,
            modelConfig: ModelConfig
        ): ApiException {
            val userFriendlyMessage = formatThirdPartyApiError(statusCode, errorResponse, modelConfig)

            return ApiException(
                apiType = ApiType.THIRD_PARTY_API,
                statusCode = statusCode,
                errorResponse = errorResponse,
                message = userFriendlyMessage
            )
        }

        /**
         * 创建官方API异常
         */
        fun createOfficialApiException(
            statusCode: Int,
            errorResponse: String
        ): ApiException {
            val userFriendlyMessage = formatOfficialApiError(statusCode, errorResponse)

            return ApiException(
                apiType = ApiType.OFFICIAL_API,
                statusCode = statusCode,
                errorResponse = errorResponse,
                message = userFriendlyMessage
            )
        }

        /**
         * 创建通用异常（兼容旧代码）
         */
        fun create(statusCode: Int, errorResponse: String): ApiException {
            return ApiException(
                apiType = ApiType.UNKNOWN,
                statusCode = statusCode,
                errorResponse = errorResponse,
                message = formatGenericError(statusCode, errorResponse)
            )
        }

        /**
         * 根据模型配置判断API类型并创建对应异常
         */
        fun createFromModelConfig(
            modelConfig: ModelConfig,
            statusCode: Int? = null,
            errorResponse: String? = null,
            exception: Exception? = null
        ): ApiException {
            return when (modelConfig.provider) {
                ModelProvider.OLLAMA -> {
                    if (exception != null) {
                        createOllamaException(exception)
                    } else {
                        createThirdPartyApiException(statusCode ?: 500, errorResponse ?: "Unknown error", modelConfig)
                    }
                }

                ModelProvider.LMSTUDIO -> {
                    if (exception != null) {
                        createThirdPartyApiException(
                            statusCode ?: 500,
                            exception.message ?: "Unknown error",
                            modelConfig
                        )
                    } else {
                        createThirdPartyApiException(statusCode ?: 500, errorResponse ?: "Unknown error", modelConfig)
                    }
                }

                ModelProvider.CHATGPT, ModelProvider.CLAUDE, ModelProvider.GEMINI, ModelProvider.MINIMAX -> {
                    createOfficialApiException(statusCode ?: 500, errorResponse ?: "Unknown error")
                }

                ModelProvider.MODELSCOPE, ModelProvider.OPENROUTER -> {
                    createThirdPartyApiException(statusCode ?: 500, errorResponse ?: "Unknown error", modelConfig)
                }

                ModelProvider.CUSTOM_API -> {
                    createThirdPartyApiException(statusCode ?: 500, errorResponse ?: "Unknown error", modelConfig)
                }

                else -> {
                    create(statusCode ?: 500, errorResponse ?: "Unknown error")
                }
            }
        }

        /**
         * 格式化Ollama错误
         */
        private fun formatOllamaError(errorMessage: String): String {
            val baseMessage = I18nManager.getMessage("api.error.ollama.connection")

            // 提取具体的连接错误信息
            val detailMessage = when {
                errorMessage.contains("Connection refused", ignoreCase = true) -> {
                    I18nManager.getMessage("api.error.ollama.connection.refused")
                }

                errorMessage.contains("timeout", ignoreCase = true) -> {
                    I18nManager.getMessage("api.error.ollama.timeout")
                }

                errorMessage.contains("UnknownHostException", ignoreCase = true) -> {
                    I18nManager.getMessage("api.error.ollama.host.unknown")
                }

                else -> {
                    I18nManager.getMessage("api.error.ollama.generic")
                }
            }

            return "$baseMessage\n\n$detailMessage\n\n${I18nManager.getMessage("api.error.detail.prefix")}$errorMessage"
        }

        /**
         * 格式化第三方API错误
         */
        private fun formatThirdPartyApiError(statusCode: Int, errorResponse: String, modelConfig: ModelConfig): String {
            val providerName = modelConfig.provider.displayName
            val baseMessage = I18nManager.getMessage("api.error.third.party", providerName)

            // 尝试解析错误响应中的具体信息
            val errorDetail = extractErrorDetail(errorResponse)
            val statusMessage = getHttpStatusMessage(statusCode)

            return buildString {
                append(baseMessage)
                append("\n\n")
                append(I18nManager.getMessage("api.error.status.code", statusCode, statusMessage))
                append("\n\n")

                if (errorDetail.isNotEmpty()) {
                    append(I18nManager.getMessage("api.error.message.prefix"))
                    append(errorDetail)
                } else {
                    append(I18nManager.getMessage("api.error.raw.response"))
                    append("\n")
                    append(errorResponse)
                }
            }
        }

        /**
         * 格式化官方API错误
         */
        private fun formatOfficialApiError(statusCode: Int, errorResponse: String): String {
            val baseMessage = I18nManager.getMessage("api.error.official")
            val errorDetail = extractErrorDetail(errorResponse)
            val statusMessage = getHttpStatusMessage(statusCode)

            return buildString {
                append(baseMessage)
                append("\n\n")
                append(I18nManager.getMessage("api.error.status.code", statusCode, statusMessage))
                append("\n\n")

                if (errorDetail.isNotEmpty()) {
                    append(I18nManager.getMessage("api.error.message.prefix"))
                    append(errorDetail)
                } else {
                    append(I18nManager.getMessage("api.error.raw.response"))
                    append("\n")
                    append(errorResponse)
                }
            }
        }

        /**
         * 格式化通用错误（向后兼容）
         */
        private fun formatGenericError(statusCode: Int, errorResponse: String): String {
            val statusMessage = getHttpStatusMessage(statusCode)
            val errorDetail = extractErrorDetail(errorResponse)

            return buildString {
                append(I18nManager.getMessage("api.error.request.failed"))
                append("\n\n")
                append(I18nManager.getMessage("api.error.status.code", statusCode, statusMessage))
                append("\n\n")

                if (errorDetail.isNotEmpty()) {
                    append(I18nManager.getMessage("api.error.message.prefix"))
                    append(errorDetail)
                } else if (errorResponse.isNotEmpty()) {
                    append(I18nManager.getMessage("api.error.raw.response"))
                    append("\n")
                    append(errorResponse)
                }
            }
        }

        /**
         * 从错误响应中提取具体错误信息
         */
        private fun extractErrorDetail(errorResponse: String): String {
            if (errorResponse.isEmpty()) return ""

            try {
                // 尝试解析JSON错误响应
                val errorJson = objectMapper.readTree(errorResponse)

                // 常见的错误字段
                val possibleErrorFields = listOf("error", "message", "detail", "msg", "error_description")

                for (field in possibleErrorFields) {
                    val errorNode = errorJson.get(field)
                    if (errorNode != null) {
                        // 如果错误字段本身是对象，尝试提取message
                        if (errorNode.isObject) {
                            val messageNode = errorNode.get("message") ?: errorNode.get("msg")
                            if (messageNode != null && messageNode.isTextual) {
                                return messageNode.asText()
                            }
                        } else if (errorNode.isTextual) {
                            return errorNode.asText()
                        }
                    }
                }

                // 如果没有找到标准错误字段，返回格式化的JSON
                return formatJsonError(errorResponse)

            } catch (e: Exception) {
                // 如果不是JSON，直接返回原始错误响应（可能需要清理HTML标签等）
                return cleanErrorResponse(errorResponse)
            }
        }

        /**
         * 格式化JSON错误响应
         */
        private fun formatJsonError(jsonResponse: String): String {
            return try {
                val jsonNode = objectMapper.readTree(jsonResponse)
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode)
            } catch (e: Exception) {
                jsonResponse
            }
        }

        /**
         * 清理错误响应（移除HTML标签等）
         */
        private fun cleanErrorResponse(response: String): String {
            return response
                .replace(Regex("<[^>]+>"), "") // 移除HTML标签
                .replace(Regex("\\s+"), " ") // 合并多个空白字符
                .trim()
        }

        /**
         * 根据HTTP状态码获取状态消息
         */
        private fun getHttpStatusMessage(statusCode: Int): String {
            return when (statusCode) {
                400 -> I18nManager.getMessage("api.error.400")
                401 -> I18nManager.getMessage("api.error.401")
                403 -> I18nManager.getMessage("api.error.403")
                404 -> I18nManager.getMessage("api.error.404")
                429 -> I18nManager.getMessage("api.error.429")
                500 -> I18nManager.getMessage("api.error.500")
                502 -> I18nManager.getMessage("api.error.502")
                503 -> I18nManager.getMessage("api.error.503")
                504 -> I18nManager.getMessage("api.error.504")
                else -> I18nManager.getMessage("api.error.unknown")
            }
        }

        /**
         * 判断是否为可重试的错误
         */
        fun isRetryable(statusCode: Int): Boolean {
            return when (statusCode) {
                429, 500, 502, 503, 504 -> true
                else -> false
            }
        }

        /**
         * 判断是否为可重试的异常
         */
        fun isRetryable(exception: Exception): Boolean {
            return when (exception) {
                is ApiException -> {
                    exception.statusCode?.let { isRetryable(it) } ?: {
                        // 对于没有状态码的异常（如连接异常），检查消息内容
                        val message = exception.message?.lowercase() ?: ""
                        message.contains("timeout") ||
                                message.contains("connection") ||
                                message.contains("429") ||
                                message.contains("500") ||
                                message.contains("502") ||
                                message.contains("503") ||
                                message.contains("504")
                    }()
                }

                else -> {
                    val message = exception.message?.lowercase() ?: ""
                    message.contains("timeout") ||
                            message.contains("connection") ||
                            message.contains("429") ||
                            message.contains("500") ||
                            message.contains("502") ||
                            message.contains("503") ||
                            message.contains("504")
                }
            }
        }

        /**
         * 向后兼容的方法
         */
        @Deprecated("Use createFromModelConfig or specific create methods instead")
        fun getErrorMessage(statusCode: Int, errorResponse: String? = null): String {
            return formatGenericError(statusCode, errorResponse ?: "")
        }
    }
}