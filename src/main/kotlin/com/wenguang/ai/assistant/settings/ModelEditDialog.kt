package com.wenguang.ai.assistant.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.IconUtil
import com.intellij.util.ui.FormBuilder
import com.wenguang.ai.assistant.i18n.I18nManager
import java.awt.Component
import java.util.*
import javax.swing.*
import javax.swing.plaf.basic.BasicComboBoxRenderer

class ModelEditDialog(private val originalModel: ModelConfig?) : DialogWrapper(true) {

    private val idField = JBTextField().apply { isEditable = false }  // ID字段不可编辑
    private val nameField = JBTextField()
    private val providerCombo = ComboBox(ModelProvider.values()).apply {
        renderer = ProviderComboBoxRenderer()  // 添加自定义渲染器
    }
    private val modelNameField = JBTextField()
    private val apiUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val enabledCheckbox = JBCheckBox(I18nManager.getMessage("model.edit.enabled", "启用"))
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.1))
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(8192, 1, 10000, 1))
    private val responseFormatCombo = ComboBox(ApiResponseFormat.values())
    private val customContentPathField = JBTextField()
    private val customContentPathLabel = JBLabel(
        "<html>${I18nManager.getMessage("model.edit.custom.path.label")}:<br/><small style='color: gray;'>${
            I18nManager.getMessage("model.edit.custom.path.desc")
        }<br/>${I18nManager.getMessage("model.edit.custom.path.example")}<br/>${I18nManager.getMessage("model.edit.custom.path.format")}</small></html>"
    )

    private var initializing = true

    init {
        title =
            if (originalModel == null) I18nManager.getMessage("model.edit.add.title") else I18nManager.getMessage("model.edit.edit.title")
        init()

        // 设置对话框尺寸
        setSize(600, 700)

        // 如果是编辑模式，填充现有数据
        originalModel?.let { model ->
            idField.text = model.id
            nameField.text = model.name
            providerCombo.selectedItem = model.provider
            modelNameField.text = model.modelName
            apiUrlField.text = model.apiUrl
            apiKeyField.text = model.apiKey
            enabledCheckbox.isSelected = model.enabled
            temperatureSpinner.value = (model.temperature ?: 0.7f).toDouble()
            maxTokensSpinner.value = model.maxTokens ?: 8192
            responseFormatCombo.selectedItem = model.responseFormat
            customContentPathField.text = model.customContentPath
        }

        // 如果是新建模式，生成ID
        if (originalModel == null) {
            idField.text = "model-${UUID.randomUUID().toString().take(8)}"
            enabledCheckbox.isSelected = true
            customContentPathField.text = "choices[0].delta.content"
        }

        // 监听供应商变化，自动填充默认值
        providerCombo.addActionListener {
            updateFieldsForProvider()
        }

        // 监听响应格式变化
        responseFormatCombo.addActionListener {
            updateCustomContentPathVisibility()
        }

        updateFieldsForProvider()
        updateCustomContentPathVisibility()

        // 初始化完成
        initializing = false
    }

    private fun updateFieldsForProvider() {
        val provider = providerCombo.selectedItem as ModelProvider

        // 只有在新建模型或者用户主动切换提供商时，才自动设置默认值
        // 编辑现有模型时，保留原有配置
        val isNewModel = originalModel == null
        val shouldSetDefaults = isNewModel || !initializing

        when (provider) {
            ModelProvider.OLLAMA -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "http://localhost:11434"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = true  // Ollama允许自定义地址
                apiKeyField.isEnabled = false
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.LMSTUDIO -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "http://localhost:1234/v1"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = true  // LM Studio允许自定义地址
                apiKeyField.isEnabled = false  // LM Studio不需要API密钥
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.VLLM -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "http://localhost:8000/v1"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = true  // vLLM允许自定义地址
                apiKeyField.isEnabled = true  // vLLM可选API密钥
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.CHATGPT -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.openai.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.CLAUDE -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.anthropic.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.ANTHROPIC
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.GEMINI -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://generativelanguage.googleapis.com/v1beta/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.GEMINI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.GROK -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.x.ai/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.MISTRAL -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.mistral.ai/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.META -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.llama.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.DEEPSEEK -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.deepseek.com/v1"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.QWEN -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.DOUBAO -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://ark.cn-beijing.volces.com/api/v3/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.WENXIN -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://qianfan.baidubce.com/v2/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.HUNYUAN -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.hunyuan.cloud.tencent.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.SPARK -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://spark-api-open.xf-yun.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.STEPFUN -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.stepfun.com/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.HUGGINGFACE -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api-inference.huggingface.co/v1/"
                }
                apiUrlField.isEnabled = true  // HuggingFace允许自定义端点
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.MODELSCOPE -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api-inference.modelscope.cn/v1/"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = false  // ModelScope地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = true  // 允许选择响应格式
            }

            ModelProvider.CUSTOM_API -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = ""  // 自定义API默认为空
                }
                apiUrlField.isEnabled = true  // 自定义API允许修改地址
                apiKeyField.isEnabled = true
                responseFormatCombo.isEnabled = true
            }

            ModelProvider.KIMI -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.moonshot.cn/v1/"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = false
                apiKeyField.isEnabled = true
                responseFormatCombo.isEnabled = false
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
            }

            ModelProvider.ZHIPU -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://open.bigmodel.cn/api/paas/v4"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.MINIMAX -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.minimaxi.com/anthropic"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.ANTHROPIC
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.COHERE -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.cohere.ai/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.SILICONFLOW -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://api.siliconflow.cn/v1/"
                }
                apiUrlField.isEnabled = false  // 官方API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }

            ModelProvider.OPENROUTER -> {
                if (shouldSetDefaults) {
                    apiUrlField.text = "https://openrouter.ai/api/v1/"
                    apiKeyField.text = ""
                }
                apiUrlField.isEnabled = false  // OpenRouter API地址固定
                apiKeyField.isEnabled = true
                responseFormatCombo.selectedItem = ApiResponseFormat.OPENAI
                responseFormatCombo.isEnabled = false
            }
        }
    }

    private fun updateCustomContentPathVisibility() {
        val format = responseFormatCombo.selectedItem as ApiResponseFormat
        customContentPathField.isEnabled = format == ApiResponseFormat.CUSTOM
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(I18nManager.getMessage("model.edit.id"), idField)
            .addLabeledComponent(I18nManager.getMessage("model.edit.name"), nameField)
            .addLabeledComponent(I18nManager.getMessage("model.edit.provider"), providerCombo)
            .addLabeledComponent(I18nManager.getMessage("model.edit.model.name"), modelNameField)
            .addLabeledComponent(I18nManager.getMessage("model.edit.api.url"), apiUrlField)
            .addLabeledComponent(I18nManager.getMessage("model.edit.api.key"), apiKeyField)
            .addSeparator()
            .addComponent(enabledCheckbox)
            .addSeparator()
            .addLabeledComponent(I18nManager.getMessage("model.edit.response.format"), responseFormatCombo)
            .addComponent(customContentPathLabel)
            .addComponent(customContentPathField)
            .addSeparator()
            .addLabeledComponent(I18nManager.getMessage("model.edit.temperature"), temperatureSpinner)
            .addLabeledComponent(I18nManager.getMessage("model.edit.max.tokens"), maxTokensSpinner)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.getMessage("model.edit.validation.id.empty"), idField)
        }
        if (nameField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.getMessage("model.edit.validation.name.empty"), nameField)
        }
        if (modelNameField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.getMessage("model.edit.validation.model.name.empty"), modelNameField)
        }
        if (apiUrlField.text.trim().isEmpty()) {
            return ValidationInfo(I18nManager.getMessage("model.edit.validation.api.url.empty"), apiUrlField)
        }

        val provider = providerCombo.selectedItem as ModelProvider
        if (provider != ModelProvider.OLLAMA && provider != ModelProvider.LMSTUDIO && provider != ModelProvider.VLLM && apiKeyField.text.trim()
                .isEmpty()
        ) {
            return ValidationInfo(I18nManager.getMessage("model.edit.validation.api.key.required"), apiKeyField)
        }

        val responseFormat = responseFormatCombo.selectedItem as ApiResponseFormat
        if (responseFormat == ApiResponseFormat.CUSTOM && customContentPathField.text.trim().isEmpty()) {
            return ValidationInfo(
                I18nManager.getMessage("model.edit.validation.custom.path.empty"),
                customContentPathField
            )
        }

        return null
    }

    fun getModel(): ModelConfig {
        return ModelConfig(
            id = idField.text.trim(),
            name = nameField.text.trim(),
            provider = providerCombo.selectedItem as ModelProvider,
            modelName = modelNameField.text.trim(),
            apiUrl = apiUrlField.text.trim(),
            apiKey = apiKeyField.text.trim(),
            enabled = enabledCheckbox.isSelected,
            temperature = (temperatureSpinner.value as Double).toFloat(),
            maxTokens = maxTokensSpinner.value as Int,
            responseFormat = responseFormatCombo.selectedItem as ApiResponseFormat,
            customContentPath = customContentPathField.text.trim()
        )
    }
}

/**
 * 供应商下拉框的自定义渲染器，用于显示图标
 */
class ProviderComboBoxRenderer : BasicComboBoxRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        if (value is ModelProvider) {
            try {
                // 根据供应商枚举加载对应图标
                val providerName = when (value) {
                    ModelProvider.CHATGPT -> "chatgpt"
                    ModelProvider.CLAUDE -> "claude"
                    ModelProvider.GEMINI -> "gemini"
                    ModelProvider.MINIMAX -> "minimax"
                    ModelProvider.MISTRAL -> "mistral"
                    ModelProvider.META -> "meta"
                    ModelProvider.DEEPSEEK -> "deepseek"
                    ModelProvider.QWEN -> "qwen"
                    ModelProvider.DOUBAO -> "doubao"
                    ModelProvider.WENXIN -> "yiyan"
                    ModelProvider.HUNYUAN -> "hunyuan"
                    ModelProvider.SPARK -> "xfyun"
                    ModelProvider.STEPFUN -> "stepfun"
                    ModelProvider.KIMI -> "kimi"
                    ModelProvider.GROK -> "grok"
                    ModelProvider.ZHIPU -> "zhipu"
                    ModelProvider.COHERE -> "cohere"
                    ModelProvider.SILICONFLOW -> "siliconflow"
                    ModelProvider.HUGGINGFACE -> "huggingface"
                    ModelProvider.MODELSCOPE -> "modelscope"
                    ModelProvider.OLLAMA -> "ollama"
                    ModelProvider.LMSTUDIO -> "lmstudio"
                    ModelProvider.VLLM -> "vllm"
                    ModelProvider.OPENROUTER -> "openrouter"
                    ModelProvider.CUSTOM_API -> "custom"
                }

                // 加载图标，使用webview资源路径
                val iconPath = "/webview/lib/icon/${providerName}.svg"
                val iconUrl = javaClass.getResource(iconPath)

                if (iconUrl != null) {
                    val icon = IconLoader.getIcon(iconPath, javaClass)
                    val scaledIcon = IconUtil.scale(icon, component, 16.0f / icon.iconWidth)
                    (component as JLabel).icon = scaledIcon
                } else {
                    // 如果图标不存在，使用默认图标
                    val defaultIconPath = "/webview/lib/icon/custom.svg"
                    val defaultIconUrl = javaClass.getResource(defaultIconPath)
                    if (defaultIconUrl != null) {
                        val defaultIcon = IconLoader.getIcon(defaultIconPath, javaClass)
                        val scaledDefaultIcon = IconUtil.scale(defaultIcon, component, 16.0f / defaultIcon.iconWidth)
                        (component as JLabel).icon = scaledDefaultIcon
                    }
                }

                // 设置文本为供应商的显示名称
                (component as JLabel).text = value.displayName
            } catch (e: Exception) {
                // 如果加载图标失败，只显示文本
                (component as JLabel).icon = null
                (component as JLabel).text = value.displayName
            }
        }

        return component
    }
} 