package com.wenguang.ai.assistant.settings

import com.wenguang.ai.assistant.i18n.I18nManager

/**
 * 模型供应商枚举
 */
enum class ModelProvider(val displayName: String, val isOfficialProvider: Boolean = false) {
    OLLAMA("Ollama", false),
    LMSTUDIO("LM Studio", false),
    VLLM("vLLM", false),
    CHATGPT("ChatGPT", true),
    CLAUDE("Claude", true),
    GEMINI("Gemini", true),
    GROK("Grok", true),
    MISTRAL("Mistral", true),
    META("Meta", true),
    DEEPSEEK("DeepSeek", true),
    QWEN("通义千问", true),
    DOUBAO("豆包", true),
    WENXIN("文心一言", true),
    HUNYUAN("腾讯混元", true),
    SPARK("讯飞星火", true),
    STEPFUN("阶跃星辰", true),
    KIMI("Kimi", true),
    ZHIPU("智谱AI", true),
    MINIMAX("MiniMax", true),
    COHERE("Cohere", true),
    SILICONFLOW("硅基流动", false),
    HUGGINGFACE("Hugging Face", false),
    MODELSCOPE("ModelScope", false),
    OPENROUTER("OpenRouter", false),
    CUSTOM_API("自定义API", false)
}

/**
 * API响应格式类型
 */
enum class ApiResponseFormat(val displayName: String) {
    OPENAI("OpenAI格式"),
    ANTHROPIC("Anthropic格式"),
    GEMINI("Gemini格式"),
    CUSTOM("自定义格式")
}

/**
 * 模型配置数据类
 */
data class ModelConfig(
    var id: String = "",
    var name: String = "",
    var provider: ModelProvider = ModelProvider.OLLAMA,
    var modelName: String = "",
    var apiUrl: String = "",
    var apiKey: String = "",
    var enabled: Boolean = true,
    var temperature: Float? = 0.7f,
    var maxTokens: Int? = 8192,
    var responseFormat: ApiResponseFormat = ApiResponseFormat.OPENAI,
    var customContentPath: String = "choices[0].delta.content" // 自定义内容提取路径
) {
    fun getDisplayName(): String {
        return "$name (${provider.displayName})"
    }

    fun isValid(): Boolean {
        return name.isNotBlank() && modelName.isNotBlank() &&
                apiUrl.isNotBlank() && (provider == ModelProvider.OLLAMA || provider == ModelProvider.LMSTUDIO || provider == ModelProvider.VLLM || apiKey.isNotBlank())
    }

    override fun toString(): String {
        return getDisplayName()
    }
}

/**
 * 默认模型配置
 */
object DefaultModels {
    fun getDefaultOllamaModels(): List<ModelConfig> {
        return listOf(
            ModelConfig(
                id = "ollama-gemini-3pro-preview-chat",
                name = "gemini-3-pro-preview",
                provider = ModelProvider.OLLAMA,
                modelName = "gemini-3-pro-preview",
                apiUrl = "http://localhost:11434"
            )
        )
    }

    fun getDefaultVLLMModels(): List<ModelConfig> {
        return listOf(
            ModelConfig(
                id = "vllm-deepseek-v3.2-special",
                name = "deepseek-v3.2-special",
                provider = ModelProvider.VLLM,
                modelName = "deepseek-ai/DeepSeek-V3.2-Speciale",
                apiUrl = "http://localhost:8000/v1",
                responseFormat = ApiResponseFormat.OPENAI
            )
        )
    }

    fun getDefaultLMStudioModels(): List<ModelConfig> {
        return listOf(
            ModelConfig(
                id = "lmstudio-minimax-m2-chat",
                name = "minimax-m2",
                provider = ModelProvider.LMSTUDIO,
                modelName = "minimax/minimax-m2",
                apiUrl = "http://localhost:1234/v1",
                responseFormat = ApiResponseFormat.OPENAI
            )
        )
    }

    fun getDefaultOfficialModels(): List<ModelConfig> {
        val models = mutableListOf<ModelConfig>()

        // 根据语言环境决定显示哪些模型
        val isChineseUser = I18nManager.isChineseLocale()

        if (isChineseUser) {
            // 中国用户：优先显示国内模型
            models.addAll(
                listOf(
                    // DeepSeek
                    ModelConfig(
                        id = "official-deepseek-chat",
                        name = "DeepSeek-V3",
                        provider = ModelProvider.DEEPSEEK,
                        modelName = "deepseek-chat",
                        apiUrl = "https://api.deepseek.com/v1",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // MiniMax
                    ModelConfig(
                        id = "official-minimax-m2",
                        name = "MiniMax-M2",
                        provider = ModelProvider.MINIMAX,
                        modelName = "MiniMax-M2",
                        apiUrl = "https://api.minimaxi.com/v1",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // 智谱AI (ZhipuAI)
                    ModelConfig(
                        id = "official-zhipu-glm-4-6",
                        name = "GLM-4.6",
                        provider = ModelProvider.ZHIPU,
                        modelName = "GLM-4.6",
                        apiUrl = "https://open.bigmodel.cn/api/paas/v4",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Qwen (新的Qwen3-Coder)
                    ModelConfig(
                        id = "official-qwen3-coder",
                        name = "Qwen3-Coder",
                        provider = ModelProvider.QWEN,
                        modelName = "qwen3-coder-480b-a35b-instruct",
                        apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Moonshot AI (Kimi)
                    ModelConfig(
                        id = "official-moonshot-kimi-k2",
                        name = "Kimi-K2",
                        provider = ModelProvider.KIMI,
                        modelName = "kimi-k2-turbo-preview",
                        apiUrl = "https://api.moonshot.cn/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // 豆包 (Doubao)
                    ModelConfig(
                        id = "official-doubao-seed-code",
                        name = "doubao-seed-code",
                        provider = ModelProvider.DOUBAO,
                        modelName = "doubao-seed-code-preview-251028",
                        apiUrl = "https://ark.cn-beijing.volces.com/api/v3/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // 腾讯混元 (Hunyuan)
                    ModelConfig(
                        id = "official-hunyuan-2.0",
                        name = "hunyuan-2.0-instruct-20251111",
                        provider = ModelProvider.HUNYUAN,
                        modelName = "hunyuan-2.0",
                        apiUrl = "https://hunyuan.tencentcloudapi.com/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // 阶跃星辰 (Stepfun)
                    ModelConfig(
                        id = "official-stepfun-step-3",
                        name = "step-3",
                        provider = ModelProvider.STEPFUN,
                        modelName = "step-3",
                        apiUrl = "https://api.stepfun.ai/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    )
                )
            )
        } else {
            // 国外用户：显示国际模型
            models.addAll(
                listOf(
                    // OpenAI (GPT-5)
                    ModelConfig(
                        id = "official-openai-gpt-5",
                        name = "GPT-5",
                        provider = ModelProvider.CHATGPT,
                        modelName = "gpt-5",
                        apiUrl = "https://api.openai.com/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Anthropic (Claude Opus 4.5)
                    ModelConfig(
                        id = "official-claude-opus-4-5",
                        name = "Claude Opus 4.5",
                        provider = ModelProvider.CLAUDE,
                        modelName = "claude-opus-4-5",
                        apiUrl = "https://api.anthropic.com/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.ANTHROPIC
                    ),
                    // Google (Gemini 3 Pro)
                    ModelConfig(
                        id = "official-gemini-3-pro",
                        name = "Gemini 3 Pro",
                        provider = ModelProvider.GEMINI,
                        modelName = "gemini-3-pro",
                        apiUrl = "https://aiplatform.googleapis.com/v1",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.GEMINI
                    ),
                    // xAI (Grok 4.1)
                    ModelConfig(
                        id = "official-grok-4-1",
                        name = "Grok 4.1",
                        provider = ModelProvider.GROK,
                        modelName = "grok-4-1",
                        apiUrl = "https://api.x.ai/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Mistral (Mistral Large 2)
                    ModelConfig(
                        id = "official-mistral-large-2",
                        name = "Mistral Large 2",
                        provider = ModelProvider.MISTRAL,
                        modelName = "mistral-large-2",
                        apiUrl = "https://api.mistral.ai/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Meta (Llama 3.3 Instruct)
                    ModelConfig(
                        id = "official-meta-llama-3-3",
                        name = "Llama 3.3 Instruct",
                        provider = ModelProvider.META,
                        modelName = "llama-3.3-instruct-70b",
                        apiUrl = "https://api.meta.ai/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    ),
                    // Cohere (Command-R-Plus)
                    ModelConfig(
                        id = "official-cohere-command-r-plus",
                        name = "Command-R-Plus",
                        provider = ModelProvider.COHERE,
                        modelName = "command-r-plus",
                        apiUrl = "https://api.cohere.ai/v1/",
                        apiKey = "",
                        responseFormat = ApiResponseFormat.OPENAI
                    )
                )
            )
        }

        return models
    }

    fun getDefaultCustomApiModels(): List<ModelConfig> {
        return listOf(
            // ModelScope 魔塔社区
            ModelConfig(
                id = "modelscope-DeepSeek-V3.2-Speciale",
                name = "DeepSeek-V3.2-Speciale",
                provider = ModelProvider.MODELSCOPE,
                modelName = "deepseek-ai/DeepSeek-V3.2-Speciale",
                apiUrl = "https://api-inference.modelscope.cn/v1/",
                apiKey = "",
                responseFormat = ApiResponseFormat.OPENAI,
                customContentPath = "choices[0].delta.content"
            ),
            // OpenRouter
            ModelConfig(
                id = "openrouter-grok-4.1-fast-coder",
                name = "grok-4.1-fast",
                provider = ModelProvider.OPENROUTER,
                modelName = "x-ai/grok-4.1-fast:free",
                apiUrl = "https://openrouter.ai/api/v1/",
                apiKey = "",
                responseFormat = ApiResponseFormat.OPENAI
            ),
        )
    }
}