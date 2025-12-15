package com.wenguang.ai.assistant.settings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.IconUtil
import com.intellij.util.ui.FormBuilder
import com.wenguang.ai.assistant.i18n.I18nManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class AppSettingsConfigurable : Configurable {
    private var settingsPanel: JPanel? = null
    private val modelTableModel = ModelTableModel()
    private var modelTable: JBTable? = null
    private var chatModelCombo: ComboBox<ModelConfig>? = null
    private val systemPromptField = JBTextArea(2, 40)
    private val commitMessageTemplateField = JBTextArea(6, 40)

    // 新增配色方案选择
    private var colorSchemeCombo: ComboBox<String>? = null

    // 新增聊天背景主题选择
    private var chatBackgroundThemeCombo: ComboBox<String>? = null

    // 新增语言选择器
    private var languageCombo: ComboBox<String>? = null

    // 新增Ollama模型识别相关
    private val ollamaUrlField = JBTextField("http://localhost:11434", 30)
    private var detectOllamaButton: JButton? = null

    // 新增vLLM模型识别相关
    private val vllmUrlField = JBTextField("http://localhost:8000/v1", 30)
    private var detectVllmButton: JButton? = null

    // 新增LM Studio模型识别相关
    private val lmStudioUrlField = JBTextField("http://localhost:1234/v1", 30)
    private var detectLmStudioButton: JButton? = null

    // 新增代码补全配置相关
    private val tabbyEndpointField = JBTextField("http://localhost:8080", 30)
    private val tabbyTokenField = JBTextField("", 30)
    private var testTabbyButton: JButton? = null
    private var deployGuideLabel: JLabel? = null

    override fun getDisplayName(): String {
        return I18nManager.Settings.title()
    }

    override fun createComponent(): JComponent? {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 800)  // 设置面板默认尺寸

        // 模型选择
        chatModelCombo = ComboBox<ModelConfig>()

        // 语言选择器
        languageCombo = ComboBox<String>()
        val languages = mapOf(
            "auto" to I18nManager.getMessage("settings.language.auto"),
            "zh" to I18nManager.getMessage("settings.language.zh"),
            "zh_TW" to I18nManager.getMessage("settings.language.zh_TW"),
            "en" to I18nManager.getMessage("settings.language.en"),
            "es" to I18nManager.getMessage("settings.language.es"),
            "fr" to I18nManager.getMessage("settings.language.fr"),
            "ar" to I18nManager.getMessage("settings.language.ar"),
            "hi" to I18nManager.getMessage("settings.language.hi"),
            "ja" to I18nManager.getMessage("settings.language.ja"),
            "bn" to I18nManager.getMessage("settings.language.bn"),
            "ru" to I18nManager.getMessage("settings.language.ru"),
            "de" to I18nManager.getMessage("settings.language.de"),
            "ko" to I18nManager.getMessage("settings.language.ko")
        )
        languages.forEach { (code, name) ->
            languageCombo!!.addItem(name)
        }

        // 聊天背景主题选择
        chatBackgroundThemeCombo = ComboBox<String>()
        val chatBackgroundThemes = arrayOf(
            I18nManager.getMessage("chat.theme.dark"),
            I18nManager.getMessage("chat.theme.light"),
            I18nManager.getMessage("chat.theme.dark.old")
        )
        chatBackgroundThemes.forEach { chatBackgroundThemeCombo!!.addItem(it) }

        // 添加聊天背景主题变化监听器
        chatBackgroundThemeCombo!!.addActionListener {
            updateColorSchemeBasedOnTheme()
        }

        // 配色方案选择 - 初始时为空，等待根据主题动态填充
        colorSchemeCombo = ComboBox<String>()

        // 模型管理表格
        modelTable = JBTable(modelTableModel)
        modelTable!!.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        modelTable!!.rowHeight = 24  // 设置行高为24像素
        modelTable!!.intercellSpacing = Dimension(0, 3)  // 设置单元格间距

        // 设置供应商列的自定义渲染器
        val providerRenderer = ProviderCellRenderer()
        modelTable!!.columnModel.getColumn(1).cellRenderer = providerRenderer

        // 设置启用列的自定义渲染器（图标）
        val enabledRenderer = EnabledCellRenderer()
        modelTable!!.columnModel.getColumn(5).cellRenderer = enabledRenderer
        modelTable!!.columnModel.getColumn(5).maxWidth = 60
        modelTable!!.columnModel.getColumn(5).minWidth = 60

        // 模型管理按钮
        val addButton = JButton(I18nManager.Settings.modelAdd())
        val editButton = JButton(I18nManager.Settings.modelEdit())
        val deleteButton = JButton(I18nManager.Settings.modelDelete())

        addButton.addActionListener { showModelEditDialog(null) }
        editButton.addActionListener {
            val selectedRow = modelTable!!.selectedRow
            if (selectedRow >= 0) {
                val model = modelTableModel.getModelAt(selectedRow)
                showModelEditDialog(model)
            } else {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.modelSelectFirst(),
                    I18nManager.Settings.prompt(),
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
        deleteButton.addActionListener {
            val selectedRow = modelTable!!.selectedRow
            if (selectedRow >= 0) {
                val result = JOptionPane.showConfirmDialog(
                    panel,
                    I18nManager.Settings.modelDeleteConfirm(),
                    I18nManager.Settings.modelDeleteTitle(),
                    JOptionPane.YES_NO_OPTION
                )
                if (result == JOptionPane.YES_OPTION) {
                    modelTableModel.removeModelAt(selectedRow)
                    updateModelCombos()
                }
            } else {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.modelSelectFirst(),
                    I18nManager.Settings.prompt(),
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }

        // 创建按钮面板
        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER, 5, 5))  // 添加垂直间距5
        buttonPanel.add(addButton)
        buttonPanel.add(editButton)
        buttonPanel.add(deleteButton)

        // 创建导入模型按钮
        val importButton = JButton(I18nManager.getMessage("settings.model.detection.import"))
        importButton.addActionListener {
            showModelImportDialog()
        }
        buttonPanel.add(importButton)

        // 创建模型管理标题和按钮的水平面板
        val modelManagementPanel = JPanel(BorderLayout(10, 0))
        modelManagementPanel.add(JBLabel(I18nManager.Settings.modelManage()), BorderLayout.WEST)  // 标题居左
        modelManagementPanel.add(buttonPanel, BorderLayout.CENTER)  // 按钮居中

        val tablePanel = JPanel(BorderLayout())
        val scrollPane = JScrollPane(modelTable)
        scrollPane.minimumSize = Dimension(750, 250)  // 设置最小高度
        tablePanel.add(scrollPane, BorderLayout.CENTER)
        tablePanel.preferredSize = Dimension(750, 310)

        // Tabby配置面板
        testTabbyButton = JButton(I18nManager.getMessage("settings.completion.test.connection"))
        
        // 创建部署指南标签
        deployGuideLabel = JLabel("<html><span style='color:#999999'>${I18nManager.getMessage("settings.completion.deploy.guide")}</span></html>")
        deployGuideLabel!!.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        deployGuideLabel!!.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI("https://tabby.tabbyml.com/docs/quick-start/installation/docker/"))
                } catch (ex: Exception) {
                    // 如果无法打开浏览器，忽略错误
                }
            }
        })
        val tabbyPanel = JPanel()
        tabbyPanel.add(JBLabel("Tabby Endpoint:"))
        tabbyPanel.add(tabbyEndpointField)
        tabbyPanel.add(JBLabel("Token:"))
        tabbyPanel.add(tabbyTokenField)
        tabbyPanel.add(testTabbyButton)
        testTabbyButton!!.addActionListener { testTabbyConnection() }
        // 代码补全配置面板 - 三行布局
        val testButtonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0))
        testButtonPanel.add(testTabbyButton!!)
        testButtonPanel.add(Box.createHorizontalStrut(10)) // 添加间距
        testButtonPanel.add(deployGuideLabel!!)
        
        val completionFormPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(I18nManager.getMessage("settings.completion.endpoint"), tabbyEndpointField)
            .addLabeledComponent(I18nManager.getMessage("settings.completion.token"), tabbyTokenField)
            .addComponent(testButtonPanel)
            .panel

        // 构建表单
        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(I18nManager.Settings.defaultModel(), chatModelCombo!!)
            .addLabeledComponent(I18nManager.getMessage("settings.language"), languageCombo!!)
            .addLabeledComponent(I18nManager.getMessage("chat.theme.title"), chatBackgroundThemeCombo!!)
            .addLabeledComponent(I18nManager.getMessage("colorscheme.title"), colorSchemeCombo!!)
            .addLabeledComponent(I18nManager.Settings.systemPrompt(), JBScrollPane(systemPromptField))
            .addLabeledComponent(
                I18nManager.getMessage("settings.commit.template"),
                JBScrollPane(commitMessageTemplateField)
            )
            .addSeparator()
            .addComponent(JBLabel(I18nManager.getMessage("settings.completion.configuration")))
            .addComponent(completionFormPanel)
            .addSeparator()
            .addComponent(modelManagementPanel)
            .addComponentFillVertically(tablePanel, 1)
            .panel

        panel.add(formPanel, BorderLayout.CENTER)
        settingsPanel = panel

        reset()
        return panel
    }

    private fun showModelEditDialog(model: ModelConfig?) {
        val dialog = ModelEditDialog(model)
        if (dialog.showAndGet()) {
            val editedModel = dialog.getModel()
            if (model == null) {
                // 添加新模型
                modelTableModel.addModel(editedModel)
            } else {
                // 编辑现有模型
                val index = modelTableModel.getModelIndex(model)
                if (index >= 0) {
                    modelTableModel.updateModel(index, editedModel)
                }
            }
            updateModelCombos()
        }
    }

    private fun showModelImportDialog() {
        val dialog = ModelImportDialog()
        if (dialog.showAndGet()) {
            val importedModels = dialog.getImportedModels()
            if (importedModels.isNotEmpty()) {
                importedModels.forEach { model ->
                    modelTableModel.addModel(model)
                }
                updateModelCombos()
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.getMessage("settings.model.import.success", importedModels.size),
                    I18nManager.Settings.success(),
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
    }

    private fun updateModelCombos() {
        val chatModels = modelTableModel.getChatModels()

        chatModelCombo!!.removeAllItems()
        chatModels.forEach {
            chatModelCombo!!.addItem(it)
        }
    }

    private fun detectOllamaModels() {
        val url = ollamaUrlField.text.trim().removeSuffix("/") + "/api/tags"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.ollamaImportError(code.toString()),
                    I18nManager.Settings.error(),
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val models = resp["models"] ?: resp["tags"] // 兼容tags/models
            if (models == null || !models.isArray || models.size() == 0) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.ollamaImportNone(),
                    I18nManager.Settings.prompt(),
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            val modelNames = mutableListOf<String>()
            for (node in models) {
                val name = node["name"]?.asText() ?: node["tag"]?.asText()
                if (!name.isNullOrBlank()) modelNames.add(name)
            }
            if (modelNames.isEmpty()) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.ollamaImportNone(),
                    I18nManager.Settings.prompt(),
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            // 弹窗多选导入
            val listModel = DefaultListModel<String>()
            modelNames.forEach { listModel.addElement(it) }
            val jList = JList(listModel)
            jList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            val result = JOptionPane.showConfirmDialog(
                settingsPanel,
                JScrollPane(jList),
                I18nManager.Settings.ollamaImportTitle(),
                JOptionPane.OK_CANCEL_OPTION
            )
            if (result == JOptionPane.OK_OPTION) {
                val selected = jList.selectedValuesList
                if (selected.isNullOrEmpty()) return
                for (modelName in selected) {
                    val newModel = ModelConfig(
                        id = "ollama-" + UUID.randomUUID().toString().take(8),
                        name = modelName,
                        provider = ModelProvider.OLLAMA,
                        modelName = modelName,
                        apiUrl = ollamaUrlField.text.trim(),
                        enabled = true
                    )
                    modelTableModel.addModel(newModel)
                }
                updateModelCombos()
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.Settings.ollamaImportSuccess(selected.size),
                    I18nManager.Settings.success(),
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                settingsPanel,
                I18nManager.Settings.ollamaImportError(e.message ?: ""),
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun detectVllmModels() {
        val url = vllmUrlField.text.trim().removeSuffix("/") + "/models"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "Failed to connect to vLLM: HTTP $code",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val modelsArray = resp["data"]
            if (modelsArray == null || !modelsArray.isArray || modelsArray.size() == 0) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "No models found on vLLM server",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            val modelNames = mutableListOf<String>()
            for (node in modelsArray) {
                val modelName = node["id"]?.asText()
                if (!modelName.isNullOrBlank()) modelNames.add(modelName)
            }
            if (modelNames.isEmpty()) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "No valid models found",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            // 弹窗多选导入
            val listModel = DefaultListModel<String>()
            modelNames.forEach { listModel.addElement(it) }
            val jList = JList(listModel)
            jList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            val result = JOptionPane.showConfirmDialog(
                settingsPanel,
                JScrollPane(jList),
                "Select vLLM Models to Import",
                JOptionPane.OK_CANCEL_OPTION
            )
            if (result == JOptionPane.OK_OPTION) {
                val selected = jList.selectedValuesList
                if (selected.isNullOrEmpty()) return
                for (modelName in selected) {
                    val newModel = ModelConfig(
                        id = "vllm-" + UUID.randomUUID().toString().take(8),
                        name = modelName,
                        provider = ModelProvider.VLLM,
                        modelName = modelName,
                        apiUrl = vllmUrlField.text.trim(),
                        enabled = true,
                        responseFormat = ApiResponseFormat.OPENAI
                    )
                    modelTableModel.addModel(newModel)
                }
                updateModelCombos()
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "Successfully imported ${selected.size} vLLM model(s)",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                settingsPanel,
                "Error connecting to vLLM: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun detectLmStudioModels() {
        val url = lmStudioUrlField.text.trim().removeSuffix("/") + "/models"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "Failed to connect to LM Studio: HTTP $code",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val modelsArray = resp["data"]
            if (modelsArray == null || !modelsArray.isArray || modelsArray.size() == 0) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "No models found on LM Studio server",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            val modelNames = mutableListOf<String>()
            for (node in modelsArray) {
                val modelName = node["id"]?.asText()
                if (!modelName.isNullOrBlank()) modelNames.add(modelName)
            }
            if (modelNames.isEmpty()) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "No valid models found",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            // 弹窗多选导入
            val listModel = DefaultListModel<String>()
            modelNames.forEach { listModel.addElement(it) }
            val jList = JList(listModel)
            jList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            val result = JOptionPane.showConfirmDialog(
                settingsPanel,
                JScrollPane(jList),
                "Select LM Studio Models to Import",
                JOptionPane.OK_CANCEL_OPTION
            )
            if (result == JOptionPane.OK_OPTION) {
                val selected = jList.selectedValuesList
                if (selected.isNullOrEmpty()) return
                for (modelName in selected) {
                    val newModel = ModelConfig(
                        id = "lmstudio-" + UUID.randomUUID().toString().take(8),
                        name = modelName,
                        provider = ModelProvider.LMSTUDIO,
                        modelName = modelName,
                        apiUrl = lmStudioUrlField.text.trim(),
                        enabled = true,
                        responseFormat = ApiResponseFormat.OPENAI
                    )
                    modelTableModel.addModel(newModel)
                }
                updateModelCombos()
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    "Successfully imported ${selected.size} LM Studio model(s)",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                settingsPanel,
                "Error connecting to LM Studio: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun testTabbyConnection() {
        val endpoint = tabbyEndpointField.text.trim()
        val token = tabbyTokenField.text.trim()

        if (endpoint.isEmpty() || token.isEmpty()) {
            JOptionPane.showMessageDialog(
                settingsPanel,
                I18nManager.getMessage("settings.completion.validation.empty"),
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        try {
            val client = com.wenguang.ai.assistant.completion.TabbyClient(endpoint, token)
            val success = client.testConnection()

            if (success) {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.getMessage("settings.completion.test.success"),
                    I18nManager.Settings.success(),
                    JOptionPane.INFORMATION_MESSAGE
                )
            } else {
                JOptionPane.showMessageDialog(
                    settingsPanel,
                    I18nManager.getMessage("settings.completion.test.failed"),
                    I18nManager.Settings.error(),
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                settingsPanel,
                I18nManager.getMessage("settings.completion.test.error", e.message ?: "Unknown error"),
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    override fun isModified(): Boolean {
        val settings = AppSettings.instance.state
        return modelTableModel.isModified() ||
                (chatModelCombo!!.selectedItem as? ModelConfig)?.id != settings.selectedChatModelId ||
                getSelectedLanguageCode() != settings.language ||
                systemPromptField.text != settings.getEffectiveSystemPrompt() ||
                commitMessageTemplateField.text != settings.getEffectiveCommitMessageTemplate() ||
                getSelectedColorSchemeId() != settings.codeColorScheme ||
                getSelectedChatBackgroundThemeId() != settings.chatBackgroundTheme ||
                tabbyEndpointField.text != settings.tabbyEndpoint ||
                tabbyTokenField.text != settings.tabbyToken
    }

    override fun apply() {
        val settings = AppSettings.instance.state

        // 更新模型列表
        settings.models.clear()
        settings.models.addAll(modelTableModel.getAllModels())

        // 更新选中的模型
        (chatModelCombo!!.selectedItem as? ModelConfig)?.let {
            settings.selectedChatModelId = it.id
        }

        // 更新语言设置
        val oldLanguage = settings.language
        settings.language = getSelectedLanguageCode()

        // 如果语言发生变化，刷新国际化
        if (oldLanguage != settings.language) {
            println("AppSettingsConfigurable: Language changed from $oldLanguage to ${settings.language}")

            // 同步AppSettings中的语言设置（这会更新I18nManager并发布语言变更事件）
            AppSettings.instance.syncLanguageSetting()

            // 刷新当前设置面板以应用新语言
            refreshCurrentPanel()
        }

        settings.systemPrompt = systemPromptField.text
        settings.commitMessageTemplate = commitMessageTemplateField.text
        settings.codeColorScheme = getSelectedColorSchemeId()
        settings.chatBackgroundTheme = getSelectedChatBackgroundThemeId()
        settings.tabbyEndpoint = tabbyEndpointField.text
        settings.tabbyToken = tabbyTokenField.text

        modelTableModel.resetModified()

        // 发布模型配置变更通知
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ModelConfigChangeNotifier.TOPIC)
            .onModelConfigChanged()
    }

    override fun reset() {
        val settings = AppSettings.instance.state

        // 加载模型列表
        modelTableModel.setModels(settings.models.toMutableList())
        updateModelCombos()

        // 设置当前选中的模型
        val selectedChatModel = settings.getSelectedChatModel()

        if (selectedChatModel != null) {
            chatModelCombo!!.selectedItem = selectedChatModel
        }

        // 设置当前选中的语言
        setSelectedLanguage(settings.language)

        systemPromptField.text = settings.getEffectiveSystemPrompt()
        commitMessageTemplateField.text = settings.getEffectiveCommitMessageTemplate()

        // 设置聊天背景主题 - 必须在设置配色方案之前
        setSelectedChatBackgroundTheme(settings.chatBackgroundTheme)

        // 根据主题动态填充配色方案
        updateColorSchemeBasedOnTheme()

        // 设置Tabby配置
        tabbyEndpointField.text = settings.tabbyEndpoint
        tabbyTokenField.text = settings.tabbyToken

        modelTableModel.resetModified()
    }

    // 根据聊天背景主题动态更新配色方案
    private fun updateColorSchemeBasedOnTheme() {
        val currentTheme = getSelectedChatBackgroundThemeId()

        colorSchemeCombo!!.removeAllItems()

        val availableSchemes = when (currentTheme) {
            "light" -> arrayOf(
                I18nManager.getMessage("colorscheme.idea.light")
            )
            "dark", "dark-old" -> arrayOf(
                I18nManager.getMessage("colorscheme.idea.dark"),
                I18nManager.getMessage("colorscheme.vscode"),
                I18nManager.getMessage("colorscheme.sublime.text")
            )
            else -> arrayOf(
                I18nManager.getMessage("colorscheme.idea.dark"),
                I18nManager.getMessage("colorscheme.vscode"),
                I18nManager.getMessage("colorscheme.sublime.text")
            )
        }

        availableSchemes.forEach { colorSchemeCombo!!.addItem(it) }

        // 根据主题设置默认配色
        val defaultScheme = when (currentTheme) {
            "light" -> "idea-light"
            "dark", "dark-old" -> "idea-dark"
            else -> "idea-dark"
        }

        setSelectedColorScheme(defaultScheme)
    }

    // 配色方案相关方法
    private fun getSelectedColorSchemeId(): String {
        val selectedName =
            colorSchemeCombo!!.selectedItem as? String ?: I18nManager.getMessage("colorscheme.idea.dark")
        return when (selectedName) {
            I18nManager.getMessage("colorscheme.idea.dark") -> "idea-dark"
            I18nManager.getMessage("colorscheme.idea.light") -> "idea-light"
            I18nManager.getMessage("colorscheme.vscode") -> "vscode"
            I18nManager.getMessage("colorscheme.sublime.text") -> "sublime-text"
            else -> "idea-dark"
        }
    }

    private fun setSelectedColorScheme(schemeId: String) {
        val schemeName = when (schemeId) {
            "idea-dark" -> I18nManager.getMessage("colorscheme.idea.dark")
            "idea-light" -> I18nManager.getMessage("colorscheme.idea.light")
            "vscode" -> I18nManager.getMessage("colorscheme.vscode")
            "sublime-text" -> I18nManager.getMessage("colorscheme.sublime.text")
            else -> I18nManager.getMessage("colorscheme.idea.dark")
        }
        colorSchemeCombo!!.selectedItem = schemeName
    }

    // 聊天背景主题相关方法
    private fun getSelectedChatBackgroundThemeId(): String {
        val selectedName =
            chatBackgroundThemeCombo!!.selectedItem as? String ?: I18nManager.getMessage("chat.theme.dark")
        return when (selectedName) {
            I18nManager.getMessage("chat.theme.dark") -> "dark"
            I18nManager.getMessage("chat.theme.light") -> "light"
            I18nManager.getMessage("chat.theme.dark.old") -> "dark-old"
            else -> "dark"
        }
    }

    private fun setSelectedChatBackgroundTheme(themeId: String) {
        val themeName = when (themeId) {
            "dark" -> I18nManager.getMessage("chat.theme.dark")
            "light" -> I18nManager.getMessage("chat.theme.light")
            "dark-old" -> I18nManager.getMessage("chat.theme.dark.old")
            else -> I18nManager.getMessage("chat.theme.dark")
        }
        chatBackgroundThemeCombo!!.selectedItem = themeName
    }

    // 语言选择相关方法
    private fun getSelectedLanguageCode(): String {
        val selectedName = languageCombo!!.selectedItem as? String ?: I18nManager.getMessage("settings.language.auto")
        return when (selectedName) {
            I18nManager.getMessage("settings.language.auto") -> "auto"
            I18nManager.getMessage("settings.language.zh") -> "zh"
            I18nManager.getMessage("settings.language.zh_TW") -> "zh_TW"
            I18nManager.getMessage("settings.language.en") -> "en"
            I18nManager.getMessage("settings.language.es") -> "es"
            I18nManager.getMessage("settings.language.fr") -> "fr"
            I18nManager.getMessage("settings.language.ar") -> "ar"
            I18nManager.getMessage("settings.language.hi") -> "hi"
            I18nManager.getMessage("settings.language.ja") -> "ja"
            I18nManager.getMessage("settings.language.bn") -> "bn"
            I18nManager.getMessage("settings.language.ru") -> "ru"
            I18nManager.getMessage("settings.language.de") -> "de"
            I18nManager.getMessage("settings.language.ko") -> "ko"
            else -> "auto"
        }
    }

    private fun setSelectedLanguage(languageCode: String) {
        val languageName = when (languageCode) {
            "auto" -> I18nManager.getMessage("settings.language.auto")
            "zh" -> I18nManager.getMessage("settings.language.zh")
            "zh_TW" -> I18nManager.getMessage("settings.language.zh_TW")
            "en" -> I18nManager.getMessage("settings.language.en")
            "es" -> I18nManager.getMessage("settings.language.es")
            "fr" -> I18nManager.getMessage("settings.language.fr")
            "ar" -> I18nManager.getMessage("settings.language.ar")
            "hi" -> I18nManager.getMessage("settings.language.hi")
            "ja" -> I18nManager.getMessage("settings.language.ja")
            "bn" -> I18nManager.getMessage("settings.language.bn")
            "ru" -> I18nManager.getMessage("settings.language.ru")
            "de" -> I18nManager.getMessage("settings.language.de")
            "ko" -> I18nManager.getMessage("settings.language.ko")
            else -> I18nManager.getMessage("settings.language.auto")
        }
        languageCombo!!.selectedItem = languageName
    }

    /**
     * 刷新当前设置面板以应用新语言
     */
    private fun refreshCurrentPanel() {
        try {
            // 重新设置面板以更新所有文本
            reset()
            println("Settings panel refreshed with new language")
        } catch (e: Exception) {
            println("Error refreshing settings panel: ${e.message}")
        }
    }
}

/**
 * 模型表格数据模型
 */
class ModelTableModel : AbstractTableModel() {
    private val columns = arrayOf(
        I18nManager.Settings.Table.name(),
        I18nManager.Settings.Table.provider(),
        I18nManager.Settings.Table.model(),
        I18nManager.Settings.Table.apiUrl(),
        I18nManager.Settings.Table.format(),
        I18nManager.Settings.Table.enabled()
    )
    private var models = mutableListOf<ModelConfig>()
    private var originalOrder = mutableListOf<ModelConfig>() // 保存原始顺序（不使用排序功能）
    private var isModified = false

    fun setModels(newModels: MutableList<ModelConfig>) {
        models = newModels.toMutableList()
        originalOrder = newModels.toMutableList() // 保存原始顺序
        fireTableDataChanged()
        isModified = false
    }

    fun getAllModels(): List<ModelConfig> = models.toList()

    fun getChatModels(): List<ModelConfig> = models.filter { it.enabled }

    fun addModel(model: ModelConfig) {
        models.add(model)
        originalOrder.add(model) // 更新原始顺序
        fireTableRowsInserted(models.size - 1, models.size - 1)
        isModified = true
    }

    fun removeModelAt(index: Int) {
        if (index >= 0 && index < models.size) {
            val removedModel = models[index]
            models.removeAt(index)
            originalOrder.remove(removedModel) // 更新原始顺序
            fireTableRowsDeleted(index, index)
            isModified = true
        }
    }

    fun getModelAt(index: Int): ModelConfig? {
        return if (index >= 0 && index < models.size) models[index] else null
    }

    fun getModelIndex(model: ModelConfig): Int = models.indexOfFirst { it.id == model.id }

    fun updateModel(index: Int, model: ModelConfig) {
        if (index >= 0 && index < models.size) {
            models[index] = model
            fireTableRowsUpdated(index, index)
            isModified = true
        }
    }

    fun isModified(): Boolean = isModified

    fun resetModified() {
        isModified = false
    }

    override fun getRowCount(): Int = models.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex >= models.size) return null
        val model = models[rowIndex]

        return when (columnIndex) {
            0 -> model.name
            1 -> model.provider.displayName
            2 -> model.modelName
            3 -> model.apiUrl
            4 -> model.responseFormat.displayName
            5 -> model.enabled // 返回布尔值用于图标渲染
            else -> null
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            5 -> java.lang.Boolean::class.java // 启用列使用布尔类型
            else -> String::class.java
        }
    }
}

/**
 * 启用列的自定义渲染器，用于显示启用/禁用状态
 */
class EnabledCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        (component as JLabel).horizontalAlignment = JLabel.CENTER

        if (value is Boolean) {
            // 使用SVG图标显示启用/禁用状态
            text = ""
            val iconPath = if (value) "/setting-icons/check.svg" else "/setting-icons/cross.svg"
            val iconUrl = javaClass.getResource(iconPath)
            if (iconUrl != null) {
                val icon = IconLoader.getIcon(iconPath, javaClass)
                val scaledIcon = IconUtil.scale(icon, component, 16.0f / icon.iconWidth)
                (component as JLabel).icon = scaledIcon
            } else {
                (component as JLabel).icon = null
            }
        }

        return component
    }
}

/**
 * 供应商列的自定义渲染器，用于显示图标
 */
class ProviderCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

        if (value is String) {
            try {
                // 根据供应商名称加载对应图标
                val providerName = when (value) {
                    "ChatGPT" -> "chatgpt"
                    "Claude" -> "claude"
                    "Gemini" -> "gemini"
                    "DeepSeek" -> "deepseek"
                    "通义千问" -> "qwen"
                    "Qwen" -> "qwen"
                    "Kimi" -> "kimi"
                    "MiniMax" -> "minimax"
                    "ModelScope" -> "modelscope"
                    "Grok" -> "grok"
                    "智谱AI" -> "zhipu"
                    "Ollama" -> "ollama"
                    "LM Studio" -> "lmstudio"
                    "OpenRouter" -> "openrouter"
                    else -> "custom"
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
            } catch (e: Exception) {
                // 如果加载图标失败，只显示文本
                (component as JLabel).icon = null
            }
        }

        return component
    }
}

/**
 * 模型导入对话框
 * 包含三个模型检测选项：Ollama、vLLM、LM Studio
 */
class ModelImportDialog : DialogWrapper(true) {
    private val ollamaUrlField = JBTextField("http://localhost:11434", 30)
    private val vllmUrlField = JBTextField("http://localhost:8000/v1", 30)
    private val lmStudioUrlField = JBTextField("http://localhost:1234/v1", 30)

    private val ollamaDetectedModels = mutableListOf<String>()
    private val vllmDetectedModels = mutableListOf<String>()
    private val lmStudioDetectedModels = mutableListOf<String>()

    private var ollamaStatusLabel: JLabel? = null
    private var vllmStatusLabel: JLabel? = null
    private var lmStudioStatusLabel: JLabel? = null
    private var selectionStatusLabel: JLabel? = null

    init {
        title = I18nManager.getMessage("settings.model.import.title")
        init()
        setSize(600, 300)
        setResizable(true)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        panel.preferredSize = Dimension(520, 210)

        // Ollama检测面板
        val ollamaPanel = createDetectionPanel(
            I18nManager.getMessage("settings.model.detection.ollama"),
            ollamaUrlField,
            { detectOllamaModels() },
            "ollama"
        )
        panel.add(ollamaPanel)

        // vLLM检测面板
        val vllmPanel = createDetectionPanel(
            I18nManager.getMessage("settings.model.detection.vllm"),
            vllmUrlField,
            { detectVllmModels() },
            "vllm"
        )
        panel.add(vllmPanel)

        // LM Studio检测面板
        val lmStudioPanel = createDetectionPanel(
            I18nManager.getMessage("settings.model.detection.lmstudio"),
            lmStudioUrlField,
            { detectLmStudioModels() },
            "lmstudio"
        )
        panel.add(lmStudioPanel)

        return panel
    }

    private fun createDetectionPanel(
        title: String,
        urlField: JBTextField,
        detectAction: () -> Unit,
        type: String
    ): JPanel {
        val panel = JPanel(BorderLayout(5, 3))
        panel.preferredSize = Dimension(520, 60)
        panel.maximumSize = Dimension(520, 60)

        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        titlePanel.preferredSize = Dimension(70, 50)
        val titleLabel = JBLabel(title)
        titleLabel.preferredSize = Dimension(65, 30)
        titleLabel.verticalAlignment = JBLabel.CENTER
        titlePanel.add(titleLabel)

        val urlFieldPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        urlFieldPanel.preferredSize = Dimension(350, 50)
        urlField.preferredSize = Dimension(340, 30)
        urlField.maximumSize = Dimension(340, 30)
        urlFieldPanel.add(urlField)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        buttonPanel.preferredSize = Dimension(100, 50)
        val detectButton = JButton(I18nManager.getMessage("settings.model.detection.button"))
        detectButton.preferredSize = Dimension(90, 30)
        detectButton.maximumSize = Dimension(90, 30)
        detectButton.addActionListener { detectAction() }
        buttonPanel.add(detectButton)

        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        statusPanel.preferredSize = Dimension(520, 25)
        statusPanel.maximumSize = Dimension(520, 25)
        val statusLabel = JLabel("")
        statusLabel.preferredSize = Dimension(520, 25)
        statusLabel.maximumSize = Dimension(520, 25)
        when (type) {
            "ollama" -> ollamaStatusLabel = statusLabel
            "vllm" -> vllmStatusLabel = statusLabel
            "lmstudio" -> lmStudioStatusLabel = statusLabel
        }
        statusPanel.add(statusLabel)

        val mainPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        mainPanel.add(titlePanel)
        mainPanel.add(urlFieldPanel)
        mainPanel.add(buttonPanel)

        panel.add(mainPanel, BorderLayout.CENTER)
        panel.add(statusPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun detectOllamaModels() {
        val url = ollamaUrlField.text.trim().removeSuffix("/") + "/api/tags"
        ollamaStatusLabel!!.text = I18nManager.getMessage("settings.model.detecting")
        ollamaStatusLabel!!.foreground = Color.GRAY

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.failed.message", "Ollama", code),
                    I18nManager.Settings.error(),
                    JOptionPane.ERROR_MESSAGE
                )
                ollamaStatusLabel!!.text = ""
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val models = resp["models"] ?: resp["tags"]
            if (models == null || !models.isArray || models.size() == 0) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.none.message", "Ollama"),
                    I18nManager.getMessage("settings.title"),
                    JOptionPane.INFORMATION_MESSAGE
                )
                ollamaStatusLabel!!.text = ""
                return
            }
            val detectedModelNames = mutableListOf<String>()
            for (node in models) {
                val name = node["name"]?.asText() ?: node["tag"]?.asText()
                if (!name.isNullOrBlank()) detectedModelNames.add(name)
            }

            // 弹出多选对话框
            val selectedModels = showModelSelectionDialog(detectedModelNames, "Ollama")
            ollamaDetectedModels.clear()
            ollamaDetectedModels.addAll(selectedModels)

            if (selectedModels.isNotEmpty()) {
                ollamaStatusLabel!!.text = I18nManager.getMessage("settings.model.selected.count", selectedModels.size)
                ollamaStatusLabel!!.foreground = Color.GREEN
            } else {
                ollamaStatusLabel!!.text = ""
            }
            updateSelectionStatus()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                null,
                "连接 Ollama 时发生错误: ${e.message ?: ""}",
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
            ollamaStatusLabel!!.text = ""
        }
    }

    private fun detectVllmModels() {
        val url = vllmUrlField.text.trim().removeSuffix("/") + "/models"
        vllmStatusLabel!!.text = I18nManager.getMessage("settings.model.detecting")
        vllmStatusLabel!!.foreground = Color.GRAY

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.failed.message", "vLLM", code),
                    I18nManager.Settings.error(),
                    JOptionPane.ERROR_MESSAGE
                )
                vllmStatusLabel!!.text = ""
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val modelsArray = resp["data"]
            if (modelsArray == null || !modelsArray.isArray || modelsArray.size() == 0) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.none.message", "vLLM"),
                    I18nManager.getMessage("settings.title"),
                    JOptionPane.INFORMATION_MESSAGE
                )
                vllmStatusLabel!!.text = ""
                return
            }
            val detectedModelNames = mutableListOf<String>()
            for (node in modelsArray) {
                val modelName = node["id"]?.asText()
                if (!modelName.isNullOrBlank()) detectedModelNames.add(modelName)
            }

            // 弹出多选对话框
            val selectedModels = showModelSelectionDialog(detectedModelNames, "vLLM")
            vllmDetectedModels.clear()
            vllmDetectedModels.addAll(selectedModels)

            if (selectedModels.isNotEmpty()) {
                vllmStatusLabel!!.text = I18nManager.getMessage("settings.model.selected.count", selectedModels.size)
                vllmStatusLabel!!.foreground = Color.GREEN
            } else {
                vllmStatusLabel!!.text = ""
            }
            updateSelectionStatus()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                null,
                "连接 vLLM 时发生错误: ${e.message ?: ""}",
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
            vllmStatusLabel!!.text = ""
        }
    }

    private fun detectLmStudioModels() {
        val url = lmStudioUrlField.text.trim().removeSuffix("/") + "/models"
        lmStudioStatusLabel!!.text = I18nManager.getMessage("settings.model.detecting")
        lmStudioStatusLabel!!.foreground = Color.GRAY

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.failed.message", "LM Studio", code),
                    I18nManager.Settings.error(),
                    JOptionPane.ERROR_MESSAGE
                )
                lmStudioStatusLabel!!.text = ""
                return
            }
            val text = conn.inputStream.bufferedReader().readText()
            val mapper = jacksonObjectMapper()
            val resp = mapper.readTree(text)
            val modelsArray = resp["data"]
            if (modelsArray == null || !modelsArray.isArray || modelsArray.size() == 0) {
                JOptionPane.showMessageDialog(
                    null,
                    I18nManager.getMessage("settings.model.detection.none.message", "LM Studio"),
                    I18nManager.getMessage("settings.title"),
                    JOptionPane.INFORMATION_MESSAGE
                )
                lmStudioStatusLabel!!.text = ""
                return
            }
            val detectedModelNames = mutableListOf<String>()
            for (node in modelsArray) {
                val modelName = node["id"]?.asText()
                if (!modelName.isNullOrBlank()) detectedModelNames.add(modelName)
            }

            // 弹出多选对话框
            val selectedModels = showModelSelectionDialog(detectedModelNames, "LM Studio")
            lmStudioDetectedModels.clear()
            lmStudioDetectedModels.addAll(selectedModels)

            if (selectedModels.isNotEmpty()) {
                lmStudioStatusLabel!!.text = I18nManager.getMessage("settings.model.selected.count", selectedModels.size)
                lmStudioStatusLabel!!.foreground = Color.GREEN
            } else {
                lmStudioStatusLabel!!.text = ""
            }
            updateSelectionStatus()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                null,
                "连接 LM Studio 时发生错误: ${e.message ?: ""}",
                I18nManager.Settings.error(),
                JOptionPane.ERROR_MESSAGE
            )
            lmStudioStatusLabel!!.text = ""
        }
    }

    private fun showModelSelectionDialog(modelNames: List<String>, provider: String): List<String> {
        val listModel = DefaultListModel<String>()
        modelNames.forEach { listModel.addElement(it) }
        val jList = JList(listModel)
        jList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        val result = JOptionPane.showConfirmDialog(
            null,
            JScrollPane(jList),
            I18nManager.getMessage("settings.model.import.select.title", provider),
            JOptionPane.OK_CANCEL_OPTION
        )

        return if (result == JOptionPane.OK_OPTION) {
            jList.selectedValuesList
        } else {
            emptyList()
        }
    }

    fun getImportedModels(): List<ModelConfig> {
        val models = mutableListOf<ModelConfig>()
        val existingModels = getExistingModels() // 获取已存在的模型

        // 添加Ollama模型
        for (modelName in ollamaDetectedModels) {
            if (!isModelExists(existingModels, modelName, ModelProvider.OLLAMA)) {
                models.add(ModelConfig(
                    id = "ollama-" + UUID.randomUUID().toString().take(8),
                    name = modelName,
                    provider = ModelProvider.OLLAMA,
                    modelName = modelName,
                    apiUrl = ollamaUrlField.text.trim(),
                    enabled = true
                ))
            }
        }

        // 添加vLLM模型
        for (modelName in vllmDetectedModels) {
            if (!isModelExists(existingModels, modelName, ModelProvider.VLLM)) {
                models.add(ModelConfig(
                    id = "vllm-" + UUID.randomUUID().toString().take(8),
                    name = modelName,
                    provider = ModelProvider.VLLM,
                    modelName = modelName,
                    apiUrl = vllmUrlField.text.trim(),
                    enabled = true,
                    responseFormat = ApiResponseFormat.OPENAI
                ))
            }
        }

        // 添加LM Studio模型
        for (modelName in lmStudioDetectedModels) {
            if (!isModelExists(existingModels, modelName, ModelProvider.LMSTUDIO)) {
                models.add(ModelConfig(
                    id = "lmstudio-" + UUID.randomUUID().toString().take(8),
                    name = modelName,
                    provider = ModelProvider.LMSTUDIO,
                    modelName = modelName,
                    apiUrl = lmStudioUrlField.text.trim(),
                    enabled = true,
                    responseFormat = ApiResponseFormat.OPENAI
                ))
            }
        }

        return models
    }

    private fun getExistingModels(): List<ModelConfig> {
        val settings = AppSettings.instance.state
        return settings.models.toList()
    }

    private fun isModelExists(existingModels: List<ModelConfig>, modelName: String, provider: ModelProvider): Boolean {
        return existingModels.any { it.modelName == modelName && it.provider == provider }
    }

    private fun updateSelectionStatus() {
        // 已移除底部状态显示，此方法不再需要
    }
} 