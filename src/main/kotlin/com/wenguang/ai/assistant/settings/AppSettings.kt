package com.wenguang.ai.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.wenguang.ai.assistant.i18n.I18nManager

@State(name = "WingCodeAssistantSettings", storages = [Storage("wingCodeAssistantSettings.xml")])
class AppSettings : PersistentStateComponent<AppSettings.State> {

    class State {
        var models: MutableList<ModelConfig> = mutableListOf()
        var selectedChatModelId: String = ""
        var selectedCompletionModelId: String = ""
        var isInitialized: Boolean = false
        var systemPrompt: String = ""
        var commitMessageTemplate: String = ""
        var codeCompletionEnabled: Boolean = false
        var codeColorScheme: String = "idea-dark"
        var chatBackgroundTheme: String = "dark"  // 新增：聊天背景主题
        var tabbyEndpoint: String = "http://localhost:8080"  // Tabby服务端点
        var tabbyToken: String = ""  // Tabby认证令牌
        var language: String = "auto"  // 用户选择的语言，auto表示自动检测

        /**
         * 获取系统提示词（懒加载国际化）
         */
        fun getEffectiveSystemPrompt(): String {
            if (systemPrompt.isEmpty()) {
                systemPrompt = I18nManager.getMessage("settings.default.system.prompt")
            }
            return systemPrompt
        }

        /**
         * 获取提交消息模板（懒加载国际化）
         */
        fun getEffectiveCommitMessageTemplate(): String {
            if (commitMessageTemplate.isEmpty()) {
                commitMessageTemplate = I18nManager.getMessage("settings.default.commit.template")
            }
            return commitMessageTemplate
        }

        fun initializeDefaultModels() {
            if (!isInitialized || models.isEmpty()) {
                models.clear()
                models.addAll(DefaultModels.getDefaultOllamaModels())
                models.addAll(DefaultModels.getDefaultLMStudioModels())
                models.addAll(DefaultModels.getDefaultOfficialModels())
                models.addAll(DefaultModels.getDefaultCustomApiModels())

                // 设置默认选中的模型
                selectedChatModelId = models.find { it.provider == ModelProvider.OLLAMA }?.id ?: ""
                selectedCompletionModelId = models.find { it.id == "ollama-qwen3-chat" }?.id ?: ""

                isInitialized = true
            }
        }

        fun getChatModels(): List<ModelConfig> {
            return models.filter { it.enabled }
        }

        fun getSelectedChatModel(): ModelConfig? {
            return models.find { it.id == selectedChatModelId && it.enabled }
        }

        fun getSelectedCompletionModel(): ModelConfig? {
            return models.find { it.id == selectedCompletionModelId && it.enabled }
        }

        fun addModel(model: ModelConfig) {
            models.add(model)
        }

        fun removeModel(modelId: String) {
            models.removeIf { it.id == modelId }
            // 如果删除的是当前选中的模型，重新选择
            if (selectedChatModelId == modelId) {
                selectedChatModelId = getChatModels().firstOrNull()?.id ?: ""
            }
            if (selectedCompletionModelId == modelId) {
                selectedCompletionModelId = getChatModels().firstOrNull()?.id ?: ""
            }
        }

        fun updateModel(model: ModelConfig) {
            val index = models.indexOfFirst { it.id == model.id }
            if (index >= 0) {
                models[index] = model
            }
        }
    }

    private var myState = State()
    private var previousLanguage: String = "auto"

    /**
     * 同步语言设置到I18nManager
     * 当用户修改语言设置后应调用此方法
     */
    fun syncLanguageSetting() {
        try {
            val oldLanguage = previousLanguage
            val newLanguage = myState.language

            // 只有在语言真正改变时才处理
            if (oldLanguage != newLanguage) {
                // 更新I18nManager
                I18nManager.refreshLocale(newLanguage)

                // 语言发生变化，清空系统提示词和提交注释模板，让它们重新加载默认的本地化版本
                myState.systemPrompt = ""
                myState.commitMessageTemplate = ""

                // 发布语言变更事件
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(LanguageChangeNotifier.TOPIC)
                    .onLanguageChanged(oldLanguage, newLanguage)

                previousLanguage = newLanguage
                }
        } catch (e: Exception) {
            // 静默处理
        }
    }

    override fun getState(): State {
        // 确保在获取状态时初始化默认模型
        myState.initializeDefaultModels()
        return myState
    }

    override fun loadState(state: State) {
        val oldLanguage = myState.language
        myState = state
        // 加载状态后也要确保初始化
        myState.initializeDefaultModels()
        // 更新语言环境设置，传入用户选择的语言
        try {
            // 检查语言是否发生变化
            if (oldLanguage != myState.language && oldLanguage.isNotEmpty()) {
                // 语言发生变化，清空系统提示词和提交注释模板，让它们重新加载默认的本地化版本
                myState.systemPrompt = ""
                myState.commitMessageTemplate = ""
            }
            previousLanguage = myState.language
            I18nManager.refreshLocale(myState.language)
        } catch (e: Exception) {
            // 静默处理，避免在初始化时出现问题
        }
    }

    companion object {
        val instance: AppSettings
            get() = ApplicationManager.getApplication().getService(AppSettings::class.java)
    }
} 