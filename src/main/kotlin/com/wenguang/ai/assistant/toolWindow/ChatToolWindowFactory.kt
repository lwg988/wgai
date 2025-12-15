package com.wenguang.ai.assistant.toolWindow

import com.google.gson.Gson
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import com.wenguang.ai.assistant.api.AIClientManager
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.AppSettings
import com.wenguang.ai.assistant.settings.LanguageChangeNotifier
import com.wenguang.ai.assistant.settings.ModelConfigChangeNotifier
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.io.InputStreamReader
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 确保在创建工具窗口之前，语言设置已经加载
        val settings = AppSettings.instance.state
        val chatView = ChatView(project)
        val content = ContentFactory.SERVICE.getInstance().createContent(chatView, "", false)
        content.setDisposer(chatView)
        toolWindow.contentManager.addContent(content)

        // 添加Toolbar
        createToolbar(toolWindow, chatView)
    }

    private fun createToolbar(toolWindow: ToolWindow, chatView: ChatView) {
        // 创建Action列表
        val actions = listOf<com.intellij.openapi.actionSystem.AnAction>(
            com.wenguang.ai.assistant.toolWindow.actions.ChatHistoryAction(),
            com.wenguang.ai.assistant.toolWindow.actions.NewChatAction(),
            com.wenguang.ai.assistant.toolWindow.actions.ClearHistoryAction(),
            com.wenguang.ai.assistant.toolWindow.actions.ToggleThemeAction(),
            com.wenguang.ai.assistant.toolWindow.actions.SettingsAction()
        )

        // 使用setTitleActions来设置工具栏按钮
        toolWindow.setTitleActions(actions)
    }
}

class ChatView(private val project: Project) : JPanel(BorderLayout()), Disposable {
    companion object {
        private val instances = mutableMapOf<String, ChatView>()

        fun getInstance(project: Project): ChatView? {
            return instances[project.name]
        }

        fun putInstance(project: Project, chatView: ChatView) {
            instances[project.name] = chatView
        }

        fun removeInstance(project: Project) {
            instances.remove(project.name)
        }

        // LRU HTML缓存：避免重复读取和内联资源文件
        // 使用LinkedHashMap实现LRU策略，限制最多缓存5个HTML版本
        private val htmlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
                // 当缓存超过5个条目时，移除最久未使用的
                return size > 5
            }
        }

        /**
         * 获取缓存的HTML，如果缓存失效则返回null
         */
        fun getCachedHtml(version: String): String? {
            synchronized(htmlCache) {
                return htmlCache[version]
            }
        }

        /**
         * 设置HTML缓存
         */
        fun setCachedHtml(html: String, version: String) {
            synchronized(htmlCache) {
                htmlCache[version] = html
            }
        }

        /**
         * 清除HTML缓存
         */
        fun clearHtmlCache() {
            synchronized(htmlCache) {
                htmlCache.clear()
            }
        }
    }

    // 资源文件LRU缓存 - 缓存读取的资源文件内容，避免重复I/O操作
    private val resourceCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
            // 当缓存超过50个资源文件时，移除最久未使用的
            return size > 50
        }
    }

    // 模型列表缓存 - 避免重复获取和序列化模型列表
    private var modelsCache: String? = null
    private var lastModelsCacheTime: Long = 0
    private val MODELS_CACHE_DURATION = 5000L // 缓存5秒，避免频繁更新

    // 刷新节流机制 - 避免短时间内频繁刷新模型列表
    private var lastRefreshTime: Long = 0
    private val REFRESH_THROTTLE_DURATION = 1000L // 1秒内最多刷新一次

    // 流式响应缓存 - 避免频繁JavaScript执行
    private var streamingBuffer: StringBuilder = StringBuilder()
    private var lastStreamUpdateTime: Long = 0
    private val STREAM_UPDATE_INTERVAL = 100L // 100ms批量更新一次
    private var currentRequestId: String? = null

    // 流式优化开关 - 如果有问题可以快速禁用
    private val ENABLE_STREAMING_OPTIMIZATION = true // 设置为false可快速回退到直接发送模式

    // 图标缓存 - 避免重复读取文件
    private val iconCache = mutableMapOf<String, String>()

    // 内联资源缓存 - 缓存内联后的JS和CSS内容
    private val inlineResourceCache = mutableMapOf<String, String>()

    private var browser: JBCefBrowser? = null
    private val aiClientManager = AIClientManager.getInstance()
    private var jsQuery: JBCefJSQuery? = null
    private val gson = Gson()

    init {
        // 注册实例
        putInstance(project, this)

        // 订阅模型配置变更通知
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(ModelConfigChangeNotifier.TOPIC, object : ModelConfigChangeNotifier {
                override fun onModelConfigChanged() {
                    // 清除模型列表缓存，强制重新获取
                    invalidateModelsCache()
                    refreshModelList()

                    // 同时更新配色方案
                    val selectedScheme = AppSettings.instance.state.codeColorScheme
                    browser?.cefBrowser?.executeJavaScript(
                        "if (window.applyColorScheme) { window.applyColorScheme('$selectedScheme'); }",
                        browser?.cefBrowser?.url, 0
                    )

                    // 同时更新聊天背景主题
                    val selectedChatTheme = AppSettings.instance.state.chatBackgroundTheme
                    browser?.cefBrowser?.executeJavaScript(
                        "if (window.applyChatBackgroundTheme) { window.applyChatBackgroundTheme('$selectedChatTheme'); }",
                        browser?.cefBrowser?.url, 0
                    )
                }
            })

        // 订阅语言配置变更通知
        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(LanguageChangeNotifier.TOPIC, object : LanguageChangeNotifier {
                override fun onLanguageChanged(oldLanguage: String, newLanguage: String) {
                    println("ChatView: Language changed from $oldLanguage to $newLanguage, reloading UI...")
                    // 语言更改时清除所有缓存并重新加载
                    clearHtmlCache()
                    clearIconCache()
                    invalidateModelsCache()
                    synchronized(resourceCache) {
                        resourceCache.clear()
                    }
                    synchronized(inlineResourceCache) {
                        inlineResourceCache.clear()
                    }

                    browser?.let {
                        ApplicationManager.getApplication().invokeLater {
                            UIUtil.invokeLaterIfNeeded {
                                // 重新加载HTML以应用新语言
                                loadAndInjectHtml()
                                println("ChatView: UI reloaded with new language: $newLanguage")
                            }
                        }
                    }
                }
            })

        if (!JBCefApp.isSupported()) {
            add(JBLabel(I18nManager.getMessage("error.jcef.not.supported"), SwingConstants.CENTER))
        } else {
            try {
                browser = JBCefBrowser()

                // 确保浏览器组件添加到面板
                val browserComponent = browser!!.component
                add(browserComponent, BorderLayout.CENTER)

                jsQuery = JBCefJSQuery.create(browser!!)
                jsQuery?.addHandler { message ->
                    if (message == "##CLEAR_HISTORY##") {
                        aiClientManager.clearChatHistory()
                        return@addHandler null
                    }

                    // 处理中断请求
                    if (message.startsWith("##STOP_REQUEST##")) {
                        val requestId = message.substring("##STOP_REQUEST##".length)
                        aiClientManager.stopCurrentRequest()
                        // 立即清空流式缓冲区，防止残留内容泄漏到新请求
                        forceClearStreamingBuffer()
                        return@addHandler null
                    }

                    // 处理插入代码
                    if (message.startsWith("##INSERT_CODE##")) {
                        val code = message.substring("##INSERT_CODE##".length)
                        insertCodeToEditor(code)
                        return@addHandler null
                    }

                    // 处理获取目录结构（用于自定义文件浏览器）
                    if (message.startsWith("##GET_DIRECTORY##")) {
                        val dirPath = message.substring("##GET_DIRECTORY##".length)
                        getDirectoryContents(dirPath)
                        return@addHandler null
                    }

                    // 处理获取最近打开的文件
                    if (message == "##GET_RECENT_FILES##") {
                        getRecentFiles()
                        return@addHandler null
                    }

                    // 处理文件选择（保留兼容）
                    if (message == "##CHOOSE_FILE##") {
                        // 改为返回项目根目录结构
                        getDirectoryContents("")
                        return@addHandler null
                    }

                    // 处理文件搜索
                    if (message.startsWith("##SEARCH_FILE##")) {
                        val fileName = message.substring("##SEARCH_FILE##".length)
                        searchFiles(fileName)
                        return@addHandler null
                    }

                    // 处理读取文件内容
                    if (message.startsWith("##READ_FILE##")) {
                        val filePath = message.substring("##READ_FILE##".length)
                        readFileContent(filePath)
                        return@addHandler null
                    }

                    // 处理模型切换
                    if (message.startsWith("##SWITCH_MODEL##")) {
                        val modelId = message.substring("##SWITCH_MODEL##".length)
                        AppSettings.instance.state.selectedChatModelId = modelId
                        return@addHandler null
                    }

                    // 处理配色方案切换
                    if (message.startsWith("##SWITCH_COLOR_SCHEME##")) {
                        val colorScheme = message.substring("##SWITCH_COLOR_SCHEME##".length)
                        AppSettings.instance.state.codeColorScheme = colorScheme
                        return@addHandler null
                    }

                    // 处理更新配色方案（包含应用）
                    if (message.startsWith("##UPDATE_COLOR_SCHEME##")) {
                        val colorScheme = message.substring("##UPDATE_COLOR_SCHEME##".length)
                        AppSettings.instance.state.codeColorScheme = colorScheme
                        browser?.cefBrowser?.executeJavaScript(
                            "if (window.applyColorScheme) { window.applyColorScheme('$colorScheme'); }",
                            browser?.cefBrowser?.url, 0
                        )
                        return@addHandler null
                    }

                    // 处理获取配色方案
                    if (message == "##GET_COLOR_SCHEMES##") {
                        val currentTheme = AppSettings.instance.state.chatBackgroundTheme

                        // 根据主题返回对应的配色方案
                        val schemes = when (currentTheme) {
                            "light" -> listOf(
                                mapOf("id" to "idea-light", "name" to I18nManager.getMessage("colorscheme.idea.light"))
                            )
                            "dark", "dark-old" -> listOf(
                                mapOf("id" to "idea-dark", "name" to I18nManager.getMessage("colorscheme.idea.dark")),
                                mapOf("id" to "vscode", "name" to I18nManager.getMessage("colorscheme.vscode")),
                                mapOf("id" to "sublime-text", "name" to I18nManager.getMessage("colorscheme.sublime.text"))
                            )
                            else -> listOf(
                                mapOf("id" to "idea-dark", "name" to I18nManager.getMessage("colorscheme.idea.dark")),
                                mapOf("id" to "vscode", "name" to I18nManager.getMessage("colorscheme.vscode")),
                                mapOf("id" to "sublime-text", "name" to I18nManager.getMessage("colorscheme.sublime.text"))
                            )
                        }

                        // 根据主题设置默认配色
                        val defaultScheme = when (currentTheme) {
                            "light" -> "idea-light"
                            "dark", "dark-old" -> "idea-dark"
                            else -> "idea-dark"
                        }

                        // 如果当前配色不兼容主题，自动切换
                        val selectedScheme = if (schemes.any { it["id"] == AppSettings.instance.state.codeColorScheme }) {
                            AppSettings.instance.state.codeColorScheme
                        } else {
                            defaultScheme.also {
                                AppSettings.instance.state.codeColorScheme = it
                            }
                        }

                        val schemesJson = gson.toJson(
                            mapOf(
                                "schemes" to schemes,
                                "selectedScheme" to selectedScheme
                            )
                        )
                        browser?.cefBrowser?.executeJavaScript(
                            "window.updateColorSchemes($schemesJson);",
                            browser?.cefBrowser?.url, 0
                        )
                        return@addHandler null
                    }

                    // 处理获取聊天背景主题
                    if (message == "##GET_CHAT_THEMES##") {
                        val selectedChatTheme = AppSettings.instance.state.chatBackgroundTheme
                        browser?.cefBrowser?.executeJavaScript(
                            "if (window.applyChatBackgroundTheme) { window.applyChatBackgroundTheme('$selectedChatTheme'); }",
                            browser?.cefBrowser?.url, 0
                        )
                        return@addHandler null
                    }

                    // 处理更新聊天背景主题
                    if (message.startsWith("##UPDATE_CHAT_THEME##")) {
                        val newTheme = message.substring("##UPDATE_CHAT_THEME##".length)
                        AppSettings.instance.state.chatBackgroundTheme = newTheme

                        // 根据主题自动切换配色方案
                        val defaultScheme = when (newTheme) {
                            "light" -> "idea-light"
                            "dark", "dark-old" -> "idea-dark"
                            else -> "idea-dark"
                        }
                        AppSettings.instance.state.codeColorScheme = defaultScheme

                        // 应用主题
                        browser?.cefBrowser?.executeJavaScript(
                            "if (window.applyChatBackgroundTheme) { window.applyChatBackgroundTheme('$newTheme'); }",
                            browser?.cefBrowser?.url, 0
                        )

                        // 更新配色方案
                        val schemes = when (newTheme) {
                            "light" -> listOf(mapOf("id" to "idea-light", "name" to I18nManager.getMessage("colorscheme.idea.light")))
                            "dark", "dark-old" -> listOf(
                                mapOf("id" to "idea-dark", "name" to I18nManager.getMessage("colorscheme.idea.dark")),
                                mapOf("id" to "vscode", "name" to I18nManager.getMessage("colorscheme.vscode")),
                                mapOf("id" to "sublime-text", "name" to I18nManager.getMessage("colorscheme.sublime.text"))
                            )
                            else -> listOf(
                                mapOf("id" to "idea-dark", "name" to I18nManager.getMessage("colorscheme.idea.dark")),
                                mapOf("id" to "vscode", "name" to I18nManager.getMessage("colorscheme.vscode")),
                                mapOf("id" to "sublime-text", "name" to I18nManager.getMessage("colorscheme.sublime.text"))
                            )
                        }

                        val schemesJson = gson.toJson(
                            mapOf(
                                "schemes" to schemes,
                                "selectedScheme" to defaultScheme
                            )
                        )
                        browser?.cefBrowser?.executeJavaScript(
                            "window.updateColorSchemes($schemesJson);",
                            browser?.cefBrowser?.url, 0
                        )

                        return@addHandler null
                    }

                    // 处理获取选中代码
                    if (message == "##GET_SELECTED_CODE##") {
                        getSelectedCode()
                        return@addHandler null
                    }

                    // 处理打开设置
                    if (message == "##OPEN_SETTINGS##") {
                        openSettings()
                        return@addHandler null
                    }

                    // 处理获取模型列表 - 使用缓存优化性能
                    if (message == "##GET_MODELS##") {
                        // 使用缓存的模型列表JSON，避免重复序列化
                        val modelsJson = getCachedModelsJson()
                        browser?.cefBrowser?.executeJavaScript(
                            "window.updateModels($modelsJson);",
                            browser?.cefBrowser?.url, 0
                        )
                        return@addHandler null
                    }

                    // 处理获取图标 - 使用缓存优化性能
                    if (message.startsWith("##GET_ICON##")) {
                        val iconName = message.substring("##GET_ICON##".length)

                        try {
                            // 使用缓存获取图标内容，避免重复文件读取和正则处理
                            val iconContent = getCachedIcon(iconName)

                            // 发送图标内容到前端
                            val iconData = gson.toJson(
                                mapOf(
                                    "iconName" to iconName,
                                    "iconContent" to iconContent
                                )
                            )

                            browser?.cefBrowser?.executeJavaScript(
                                "window.handleIcon($iconData);",
                                browser?.cefBrowser?.url, 0
                            )
                        } catch (e: Exception) {
                            println("Error loading icon: $iconName - ${e.message}")
                        }
                        return@addHandler null
                    }

                    // 获取当前选中的聊天模型
                    val selectedModel = AppSettings.instance.state.getSelectedChatModel()
                    if (selectedModel == null) {
                        browser?.cefBrowser?.executeJavaScript(
                            "window.addMessage({ type: 'ai_chunk', content: '${I18nManager.Message.noModel()}' });",
                            browser?.cefBrowser?.url, 0
                        )
                        return@addHandler null
                    }

                    browser?.cefBrowser?.executeJavaScript(
                        "window.addMessage({ type: 'ai_start' });",
                        browser?.cefBrowser?.url, 0
                    )

                    val messageData = try {
                        if (message.startsWith("{") && message.endsWith("}")) {
                            gson.fromJson(message, Map::class.java) as Map<String, Any>
                        } else {
                            mapOf(
                                "prompt" to message,
                                "history" to emptyList<Map<String, String>>(),
                                "files" to emptyList<Map<String, String>>(),
                                "requestId" to null
                            )
                        }
                    } catch (e: Exception) {
                        // 如果解析失败，认为是纯文本消息
                        mapOf(
                            "prompt" to message,
                            "history" to emptyList<Map<String, String>>(),
                            "files" to emptyList<Map<String, String>>(),
                            "requestId" to null
                        )
                    }

                    val userMessage = messageData["prompt"]?.toString() ?: message
                    val requestId = messageData["requestId"]?.toString()
                    val history = (messageData["history"] as? List<*>)?.mapNotNull { item ->
                        val historyItem = item as? Map<*, *>
                        val role = historyItem?.get("role")?.toString()
                        val content = historyItem?.get("content")?.toString()
                        if (role != null && content != null) {
                            Pair(role, content)
                        } else null
                    } ?: emptyList()

                    // 提取文件信息
                    val files = (messageData["files"] as? List<*>)?.mapNotNull { item ->
                        val fileItem = item as? Map<*, *>
                        val path = fileItem?.get("path")?.toString()
                        val content = fileItem?.get("content")?.toString()
                        if (path != null && content != null) {
                            mapOf("path" to path, "content" to content)
                        } else null
                    } ?: emptyList()

                    // 构建消息
                    val enhancedMessage = if (files.isNotEmpty()) {
                        val fileContexts = files.joinToString("\n\n") { file ->
                            val path = file["path"] as String
                            val content = file["content"] as String
                            "### 文件: $path\n```\n$content\n```"
                        }
                        "$fileContexts\n\n### 用户问题:\n$userMessage"
                    } else {
                        userMessage
                    }

                    // 普通聊天消息 - 使用批量更新优化性能
                    aiClientManager.sendChatMessage(
                        modelConfig = selectedModel,
                        userMessage = enhancedMessage,
                        historyMessages = history,
                        requestId = requestId,
                        onChunk = { chunk ->
                            // 使用批量更新机制，减少JavaScript执行频率
                            sendStreamingChunk(chunk, requestId)
                        },
                        onDone = {
                            // 发送剩余的累积内容
                            flushStreamingBuffer()
                            browser?.cefBrowser?.executeJavaScript(
                                "window.addMessage({ type: 'ai_done' });",
                                browser?.cefBrowser?.url, 0
                            )
                        }
                    )
                    null
                }

                browser?.jbCefClient?.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        frame?.executeJavaScript(
                            "window.sendMessageToKotlin = function(message) { ${jsQuery?.inject("message")} };",
                            frame.url, 0
                        )

                        // 延迟发送模型列表，确保页面完全加载 - 使用缓存优化性能
                        ApplicationManager.getApplication().invokeLater {
                            UIUtil.invokeLaterIfNeeded {
                                // 使用缓存的模型列表JSON，避免重复序列化
                                val modelsJson = getCachedModelsJson()
                                frame?.executeJavaScript(
                                    "window.updateModels && window.updateModels($modelsJson);",
                                    frame.url, 0
                                )
                            }
                        }
                    }
                }, browser!!.cefBrowser)

                loadAndInjectHtml()

            } catch (e: Exception) {
                add(JBLabel(I18nManager.getMessage("api.error", e.message ?: "Initialization failed"), SwingConstants.CENTER))
            }
        }
    }

    private fun loadAndInjectHtml() {
        try {
            // 检测IDEA主题并创建缓存版本标识
            val ideaTheme = detectIdeaTheme()
            val currentLocale = I18nManager.getCurrentLocale()
            val cacheVersion = "${ideaTheme}_${currentLocale}_${System.currentTimeMillis() / (1000 * 60 * 60 * 24)}" // 按天更新

            // 尝试从缓存获取HTML
            val cachedHtml = getCachedHtml(cacheVersion)
            if (cachedHtml != null) {
                browser?.loadHTML(cachedHtml, "file:///")
                return
            }

            // 缓存未命中，重新生成HTML
            val htmlContent = readResourceAsString("/webview/index.html")
            if (htmlContent == null) {
                browser?.loadURL("about:blank")
                return
            }

            var modifiedHtml: String? = htmlContent

            // 内联所有CSS
            val prismCss = readResourceAsString("/webview/lib/css/prism.min.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"lib/css/prism.min.css\">",
                prismCss
            )

            val prismTomorrowCss = readResourceAsString("/webview/lib/css/prism-tomorrow.min.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"lib/css/prism-tomorrow.min.css\" id=\"prism-theme\">",
                prismTomorrowCss,
                "prism-theme"
            )

            // 内联拆分后的CSS文件
            val coreCss = readResourceAsString("/webview/styles/core.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/core.css\">",
                coreCss
            )

            val componentsCss = readResourceAsString("/webview/styles/components.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/components.css\">",
                componentsCss
            )

            val lightDarkCss = readResourceAsString("/webview/styles/themes/light-dark.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/themes/light-dark.css\">",
                lightDarkCss
            )

            val colorSchemesCss = readResourceAsString("/webview/styles/themes/color-schemes.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/themes/color-schemes.css\">",
                colorSchemesCss
            )

            val chatBackgroundsCss = readResourceAsString("/webview/styles/themes/chat-backgrounds.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/themes/chat-backgrounds.css\">",
                chatBackgroundsCss
            )

            val utilitiesCss = readResourceAsString("/webview/styles/utilities.css")
            modifiedHtml = inlineResource(
                modifiedHtml,
                "<link rel=\"stylesheet\" href=\"styles/utilities.css\">",
                utilitiesCss
            )

            // 确保modifiedHtml不为空后再继续处理
            if (modifiedHtml == null) {
                browser?.loadURL("about:blank")
                return
            }

            // 从这里开始，我们使用一个新的非空变量
            var finalHtml = modifiedHtml

            // 注入国际化消息（仅在缓存未命中时计算）
            val i18nMap = mapOf(
                "title" to I18nManager.Settings.title(),
                "welcome" to I18nManager.Chat.welcome(),
                "loading" to I18nManager.Chat.loading(),
                "inputPlaceholder" to I18nManager.Chat.inputPlaceholder(),
                "send" to I18nManager.Chat.send(),
                "clear" to I18nManager.Chat.clear(),
                "theme" to I18nManager.Chat.theme(),
                "thinking" to I18nManager.Chat.thinking(),
                "modelSelect" to I18nManager.Chat.modelSelect(),
                "copy" to I18nManager.Button.copy(),
                "copied" to I18nManager.Button.copied(),
                "insert" to I18nManager.Button.insert(),
                "inserted" to I18nManager.Button.inserted(),
                "buttonDelete" to I18nManager.getMessage("button.delete"),
                "dialogTitle" to I18nManager.Dialog.clearTitle(),
                "confirm" to I18nManager.Dialog.clearConfirm(),
                "cancel" to I18nManager.Dialog.clearCancel(),
                "clearSuccess" to I18nManager.Message.clearSuccess(),
                "noModel" to I18nManager.Message.noModel(),
                "requestFailed" to I18nManager.Message.requestFailed(),
                // Frontend UI
                "historyBtn" to I18nManager.getMessage("historyBtn"),
                "newChatBtn" to I18nManager.getMessage("newChatBtn"),
                "settings" to I18nManager.getMessage("settings"),
                "selectFile" to I18nManager.getMessage("selectFile"),
                "searchFilePlaceholder" to I18nManager.getMessage("searchFilePlaceholder"),
                "browse" to I18nManager.getMessage("browse"),
                "fileSelectorHint" to I18nManager.getMessage("fileSelectorHint"),
                "historyTitle" to I18nManager.getMessage("historyTitle"),
                "historyEmpty" to I18nManager.getMessage("historyEmpty"),
                // Time Display
                "timeJustNow" to I18nManager.getMessage("time.just.now"),
                "timeMinutesAgo" to I18nManager.getMessage("time.minutes.ago"),
                "timeHoursAgo" to I18nManager.getMessage("time.hours.ago"),
                // File Selection
                "fileNoCodeSelected" to I18nManager.getMessage("file.no.code.selected"),
                "fileUnknown" to I18nManager.getMessage("file.unknown"),
                "fileLanguageText" to I18nManager.getMessage("file.language.text"),
                // Search
                "searchSearching" to I18nManager.getMessage("search.searching"),
                "searchNoFilesFound" to I18nManager.getMessage("search.no.files.found"),
                // UI Actions
                "actionStopReply" to I18nManager.getMessage("action.stop.reply"),
                "actionSendMessage" to I18nManager.getMessage("action.send.message"),
                "actionUserInterrupted" to I18nManager.getMessage("action.user.interrupted"),
                "actionSelectModel" to I18nManager.getMessage("action.select.model")
            )
            // 使用StringBuilder优化字符串拼接
            val i18nScript = buildString {
                append("<script>window.i18n = ")
                append(gson.toJson(i18nMap))
                append("; window.ideaTheme = '")
                append(ideaTheme)
                append("'; </script>")
            }

            finalHtml = finalHtml.replace(
                "<body>",
                "<body data-theme=\"$ideaTheme\">"
            ).replace(
                "</head>",
                "$i18nScript\n</head>"
            )

            // 内联marked.min.js
            val markedJsContent = readResourceAsString("/webview/lib/js/marked.min.js")
            if (markedJsContent != null) {
                finalHtml = finalHtml.replace(
                    "<!-- <script src=\"lib/js/marked.min.js\"></script> -->",
                    "<script>\n$markedJsContent\n</script>"
                )
            }

            // 内联其他JS文件
            val prismJsContent = readResourceAsString("/webview/lib/js/prism.min.js")
            if (prismJsContent != null) {
                finalHtml = finalHtml.replace(
                    "<script src=\"lib/js/prism.min.js\"></script>",
                    "<script>\n$prismJsContent\n</script>"
                )
            }

            // 内联Prism语言支持
            val prismLanguages = listOf(
                "java",
                "kotlin",
                "python",
                "javascript",
                "typescript",
                "css",
                "markup",
                "markup-templating",
                "bash",
                "sql",
                "json",
                "go",
                "rust",
                "c",
                "cpp",
                "csharp",
                "swift",
                "ruby",
                "scala",
                "dart",
                "lua",
                "php",
                "yaml",
                "toml",
                "docker",
                "nginx",
                "markdown",
                "graphql",
                "jsx",
                "tsx"
            )

            // 内联Prism语言文件 - 使用缓存优化性能
            for (lang in prismLanguages) {
                val path = "lib/js/prism-$lang.min.js"
                val inlinedContent = getInlinedResource("/webview/$path")
                if (inlinedContent != null && finalHtml != null) {
                    finalHtml = finalHtml.replace(
                        "<script src=\"$path\"></script>",
                        inlinedContent
                    )
                }
            }

            // 内联JS文件 - 使用缓存优化性能
            val jsFiles = listOf(
                "core" to "js/core.js",
                "theme" to "js/theme.js",
                "messages" to "js/messages.js",
                "history" to "js/history.js",
                "files" to "js/files.js",
                "models" to "js/models.js",
                "ui" to "js/ui.js",
                "code-selection" to "js/code-selection.js",
                "init" to "js/init.js"
            )

            for ((name, path) in jsFiles) {
                val inlinedContent = getInlinedResource("/webview/$path")
                if (inlinedContent != null && finalHtml != null) {
                    finalHtml = finalHtml.replace(
                        "<script src=\"$path\"></script>",
                        inlinedContent
                    )
                }
            }

            // 添加额外的字体渲染修复样式
            val fixFontStyle = """
                            <style>
                            /* 解决字体像素化问题的覆盖样式 */
                            code[class*="language-"],
                            pre[class*="language-"] {
                                text-shadow: none !important;
                                font-family: monospace !important;
                                font-smooth: always !important;
                                -webkit-font-smoothing: antialiased !important;
                            }

                            /* 修复特定token的灰色背景问题，但保留语法高亮颜色 */
                            .token.operator,
                            .token.entity,
                            .token.url,
                            .token.variable,
                            .token.constant,
                            .token.property,
                            .token.regex,
                            .token.inserted,
                            .token.important,
                            .token.bold,
                            .language-sql .token {
                                background: transparent !important;
                                /* 移除 color: inherit 以保持语法高亮 */
                                font-weight: normal !important;
                            }

                /* 添加流式光标样式 */
                .streaming-cursor {
                    display: inline-block;
                    width: 5px;
                    height: 15px;
                    background-color: var(--primary-color);
                    margin-left: 3px;
                    vertical-align: middle;
                    animation: blink 1s infinite;
                }

                @keyframes blink {
                    0%, 100% { opacity: 1; }
                    50% { opacity: 0; }
                }

                /* 添加思考详情样式 */
                .thinking-details {
                    margin: 10px 0;
                    border: 1px solid var(--think-border-color);
                    border-radius: 6px;
                }

                .thinking-details summary {
                    padding: 8px 12px;
                    background-color: var(--think-header-bg);
                    color: var(--think-header-color);
                    cursor: pointer;
                    user-select: none;
                }

                .thinking-content {
                    padding: 12px;
                    background-color: var(--think-bg-color);
                    color: var(--think-text-color);
                                    white-space: pre-wrap;
                                }
                            </style>
            """.trimIndent()

            // 插入到head结束标签前
            if (finalHtml != null) {
                finalHtml = finalHtml.replace("</head>", "$fixFontStyle\n</head>")

                // 确保HTML声明了正确的编码
                if (!finalHtml.contains("charset=UTF-8", ignoreCase = true)) {
                    finalHtml = finalHtml.replace(
                        "<meta charset=\"UTF-8\">",
                        "<meta charset=\"UTF-8\">\n    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">"
                    )
                }
            }

            if (finalHtml != null) {
                // 设置HTML缓存
                setCachedHtml(finalHtml, cacheVersion)
                // 使用loadHTML方法加载HTML内容，明确指定UTF-8编码
                browser?.loadHTML(finalHtml, "file:///")
            }

        } catch (e: Exception) {
            browser?.loadURL("about:blank")
        }
    }

    /**
     * 读取资源文件内容 - 带LRU缓存优化
     * 避免重复I/O操作，提高性能
     */
    private fun readResourceAsString(path: String): String? {
        // 先从缓存查找
        synchronized(resourceCache) {
            resourceCache[path]?.let { return it }
        }

        // 缓存未命中，读取文件
        val content = try {
            javaClass.getResourceAsStream(path)?.use { InputStreamReader(it, Charsets.UTF_8).readText() }
        } catch (e: Exception) {
            null
        }

        // 存入缓存（如果读取成功）
        content?.let {
            synchronized(resourceCache) {
                resourceCache[path] = it
            }
        }

        return content
    }

    /**
     * 内联资源文件到HTML中
     */
    private fun inlineResource(
        html: String?,
        placeholderTag: String,
        content: String?,
        id: String? = null
    ): String? {
        if (html == null) return null
        if (content == null) {
            return html
        }

        val replacement = if (id != null) {
            // 保留ID属性
            when {
                placeholderTag.contains("<link") -> "<style id=\"$id\">\n$content\n</style>"
                placeholderTag.contains("<script") -> "<script id=\"$id\">\n$content\n</script>"
                else -> "<div id=\"$id\">\n$content\n</div>"
            }
        } else {
            // 不保留ID
            when {
                placeholderTag.contains("<link") -> "<style>\n$content\n</style>"
                placeholderTag.contains("<script") -> "<script>\n$content\n</script>"
                else -> content
            }
        }

        return html.replace(placeholderTag, replacement)
    }

    private fun escapeJsString(value: String): String {
        // 确保字符串以UTF-8处理，然后转换为JSON
        val utf8String = String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        return gson.toJson(utf8String)
    }

    // 获取当前选中的代码
    private fun getSelectedCode() {
        try {
            ApplicationManager.getApplication().invokeLater {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val currentEditor = fileEditorManager.selectedTextEditor

                if (currentEditor != null) {
                    val selectionModel = currentEditor.selectionModel
                    val selectedText = selectionModel.selectedText

                    if (!selectedText.isNullOrEmpty()) {
                        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                        val fileName = virtualFile?.name ?: "unknown"
                        val language = virtualFile?.extension ?: "text"

                        val codeInfo = mapOf(
                            "text" to selectedText,
                            "fileName" to fileName,
                            "language" to language,
                            "hasSelection" to true
                        )

                        val resultJson = gson.toJson(
                            mapOf(
                                "type" to "selected_code",
                                "code" to codeInfo
                            )
                        )

                        browser?.cefBrowser?.executeJavaScript(
                            "window.handleSelectedCode && window.handleSelectedCode($resultJson);",
                            browser?.cefBrowser?.url, 0
                        )
                    } else {
                        // 没有选中文本
                        val noSelectionJson = gson.toJson(
                            mapOf(
                                "type" to "selected_code",
                                "code" to mapOf(
                                    "hasSelection" to false,
                                    "message" to I18nManager.getMessage("file.no.code.selected")
                                )
                            )
                        )

                        browser?.cefBrowser?.executeJavaScript(
                            "window.handleSelectedCode && window.handleSelectedCode($noSelectionJson);",
                            browser?.cefBrowser?.url, 0
                        )
                    }
                } else {
                    val errorJson = gson.toJson(
                        mapOf(
                            "type" to "selected_code",
                            "code" to mapOf(
                                "hasSelection" to false,
                                "message" to I18nManager.getMessage("action.no.selection")
                            )
                        )
                    )

                    browser?.cefBrowser?.executeJavaScript(
                        "window.handleSelectedCode && window.handleSelectedCode($errorJson);",
                        browser?.cefBrowser?.url, 0
                    )
                }
            }
        } catch (e: Exception) {
            // 静默处理错误
        }
    }

    // 插入代码到当前编辑器
    private fun insertCodeToEditor(code: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val currentEditor = fileEditorManager.selectedTextEditor

                if (currentEditor != null) {
                    val document = currentEditor.document
                    val caretModel = currentEditor.caretModel
                    val selectionModel = currentEditor.selectionModel

                    var insertOffset = caretModel.offset
                    var selectedLength = 0

                    // 检查是否有选中的代码
                    val selectedText = selectionModel.selectedText
                    if (!selectedText.isNullOrEmpty()) {
                        // 如果有选中内容，使用选区的起始位置和长度
                        insertOffset = selectionModel.selectionStart
                        selectedLength = selectionModel.selectionEnd - selectionModel.selectionStart
                    }

                    WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
                        // 如果有选中的代码，先删除选中的代码
                        if (selectedLength > 0) {
                            document.deleteString(insertOffset, insertOffset + selectedLength)
                        }

                        // 获取当前插入位置的缩进
                        val indent = getCurrentLineIndent(document, insertOffset)

                        // 对插入的代码进行缩进处理
                        val indentedCode = applyIndent(code, indent)

                        // 插入新代码
                        document.insertString(insertOffset, indentedCode)
                        // 移动光标到插入代码的末尾
                        caretModel.moveToOffset(insertOffset + indentedCode.length)
                    }
                } else {
                    browser?.cefBrowser?.executeJavaScript(
                        "console.warn('${I18nManager.getMessage("action.no.selection")}');",
                        browser?.cefBrowser?.url, 0
                    )
                }
            }
        } catch (e: Exception) {
            // 静默处理错误
        }
    }

    /**
     * 获取指定位置的当前行缩进
     */
    private fun getCurrentLineIndent(document: com.intellij.openapi.editor.Document, offset: Int): String {
        try {
            val lineNumber = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineNumber)
            val lineEnd = document.getLineEndOffset(lineNumber)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

            // 计算行首的空格或制表符数量
            var indentCount = 0
            for (char in lineText) {
                if (char == ' ') {
                    indentCount++
                } else if (char == '\t') {
                    indentCount += 4 // 假设一个制表符等于4个空格
                } else {
                    break
                }
            }

            // 返回缩进字符串
            return " ".repeat(indentCount)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 对代码应用缩进
     */
    private fun applyIndent(code: String, baseIndent: String): String {
        if (code.isEmpty()) return code

        val lines = code.split("\n")
        if (lines.isEmpty()) return code

        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            if (index == 0) {
                // 第一行保持原样（通常已经有正确的缩进）
                result.append(line)
            } else {
                // 非空行添加基础缩进
                if (line.isNotBlank()) {
                    result.append("\n").append(baseIndent).append(line)
                } else {
                    // 空行保持原样
                    result.append("\n")
                }
            }
        }

        return result.toString()
    }

    /**
     * 打开插件设置
     */
    private fun openSettings() {
        ApplicationManager.getApplication().invokeLater {
            try {
                // 使用IDEA的设置API打开插件设置页面
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, com.wenguang.ai.assistant.settings.AppSettingsConfigurable::class.java)
            } catch (e: Exception) {
                // 静默处理错误
            }
        }
    }

    /**
     * 获取缓存的模型列表JSON字符串
     * 如果缓存未命中或已过期，则重新生成
     */
    private fun getCachedModelsJson(): String {
        val currentTime = System.currentTimeMillis()

        // 检查缓存是否有效
        if (modelsCache != null && (currentTime - lastModelsCacheTime) < MODELS_CACHE_DURATION) {
            return modelsCache!!
        }

        // 缓存未命中，重新生成
        val models = AppSettings.instance.state.getChatModels()
        val selectedModelId = AppSettings.instance.state.selectedChatModelId

        val modelsJson = gson.toJson(
            mapOf(
                "models" to models.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.getDisplayName(),
                        "provider" to it.provider.name
                    )
                },
                "selectedModelId" to selectedModelId
            )
        )

        // 更新缓存
        modelsCache = modelsJson
        lastModelsCacheTime = currentTime

        return modelsJson
    }

    /**
     * 清除模型列表缓存（当模型配置变更时调用）
     */
    private fun invalidateModelsCache() {
        modelsCache = null
        lastModelsCacheTime = 0
    }

    /**
     * 发送流式响应 - 智能优化版本
     * 根据开关决定是批量优化还是直接发送
     */
    private fun sendStreamingChunk(chunk: String, requestId: String?) {
        if (!ENABLE_STREAMING_OPTIMIZATION) {
            // 快速回退模式：直接发送，不使用批量优化
            val escapedChunk = escapeJsString(chunk)
            browser?.cefBrowser?.executeJavaScript(
                "window.addMessage({ type: 'ai_chunk', content: $escapedChunk });",
                browser?.cefBrowser?.url, 0
            )
            return
        }

        // 批量优化模式
        val currentTime = System.currentTimeMillis()

        // 检查是否是新的请求
        if (currentRequestId != requestId) {
            // 发送之前的累积内容
            flushStreamingBuffer()
            currentRequestId = requestId
            streamingBuffer = StringBuilder()
            lastStreamUpdateTime = 0 // 重置时间，确保立即发送
        }

        // 累积内容到缓冲区
        streamingBuffer.append(chunk)

        // 第一次发送或间隔超过100ms时立即发送
        if (lastStreamUpdateTime == 0L || (currentTime - lastStreamUpdateTime) > STREAM_UPDATE_INTERVAL) {
            flushStreamingBuffer()
        }
    }

    /**
     * 刷新缓冲区内容到前端 - 修复版
     * 确保内容能正确发送到前端
     */
    private fun flushStreamingBuffer() {
        if (streamingBuffer.isNotEmpty()) {
            val content = streamingBuffer.toString()
            // 使用原来的转义函数，确保兼容性
            val escapedChunk = escapeJsString(content)
            browser?.cefBrowser?.executeJavaScript(
                "window.addMessage({ type: 'ai_chunk', content: $escapedChunk });",
                browser?.cefBrowser?.url, 0
            )
            streamingBuffer = StringBuilder()
            lastStreamUpdateTime = System.currentTimeMillis()
        }
    }

    /**
     * 强制清空所有流式缓冲区
     * 在停止请求时调用，确保没有残留内容
     */
    private fun forceClearStreamingBuffer() {
        streamingBuffer = StringBuilder()
        currentRequestId = null
    }

    /**
     * 获取缓存的图标内容 - 带预处理的SVG内容
     */
    private fun getCachedIcon(iconName: String): String {
        // 检查缓存
        iconCache[iconName]?.let { return it }

        // 缓存未命中，读取并处理图标
        val processedIcon = loadAndProcessIcon(iconName)
        // 存入缓存
        iconCache[iconName] = processedIcon
        return processedIcon
    }

    /**
     * 加载并预处理SVG图标
     */
    private fun loadAndProcessIcon(iconName: String): String {
        val iconPath = "/webview/lib/icon/$iconName.svg"

        return try {
            val iconStream = javaClass.getResourceAsStream(iconPath)
            if (iconStream != null) {
                val iconContent = iconStream.bufferedReader().use { it.readText() }

                // 预处理SVG，添加class属性
                val processedIconContent = if (iconContent.contains("class=")) {
                    iconContent.replace(Regex("class=\"[^\"]*\""), "class=\"provider-icon\"")
                } else {
                    iconContent.replace("<svg", "<svg class=\"provider-icon\"")
                }
                processedIconContent
            } else {
                // 返回默认图标
                "<svg class=\"provider-icon\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z\"/></svg>"
            }
        } catch (e: Exception) {
            println("Error loading icon: $iconName - ${e.message}")
            // 返回默认图标
            "<svg class=\"provider-icon\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z\"/></svg>"
        }
    }

    /**
     * 清除图标缓存（如果需要）
     */
    private fun clearIconCache() {
        iconCache.clear()
    }

    /**
     * 优化的字符串转义 - 避免重复创建对象
     * 使用StringBuilder预分配缓冲池
     */
    private val escapeBufferPool = ThreadLocal<StringBuilder>()

    private fun optimizedEscapeJsString(value: String): String {
        // 从缓冲池获取StringBuilder
        val sb = escapeBufferPool.get() ?: StringBuilder(256).also { escapeBufferPool.set(it) }
        sb.setLength(0) // 清空而非新建

        // 快速转义主要字符
        for (char in value) {
            when (char) {
                '\\' -> sb.append("\\\\")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(char)
            }
        }

        return sb.toString()
    }

    /**
     * 获取内联的资源内容 - 避免重复读取和字符串替换
     */
    private fun getInlinedResource(path: String): String? {
        val cacheKey = "inline_$path"

        // 检查缓存
        inlineResourceCache[cacheKey]?.let { return it }

        // 缓存未命中，读取并内联内容
        val content = readResourceAsString(path)
        if (content != null) {
            // 内联内容：包装在<script>标签中
            val inlinedContent = "<script>\n$content\n</script>"
            inlineResourceCache[cacheKey] = inlinedContent
            return inlinedContent
        }
        return null
    }

    /**
     * 刷新模型列表 - 使用缓存和节流优化性能
     */
    private fun refreshModelList() {
        browser?.cefBrowser?.let { cefBrowser ->
            val currentTime = System.currentTimeMillis()

            // 检查是否需要节流（避免频繁刷新）
            if (currentTime - lastRefreshTime < REFRESH_THROTTLE_DURATION) {
                return // 跳过刷新
            }
            lastRefreshTime = currentTime

            ApplicationManager.getApplication().invokeLater {
                try {
                    // 使用缓存的模型列表JSON，避免重复序列化
                    val modelsJson = getCachedModelsJson()

                    cefBrowser.executeJavaScript(
                        "window.updateModels && window.updateModels($modelsJson);",
                        cefBrowser.url, 0
                    )
                } catch (e: Exception) {
                    // 静默处理错误
                }
            }
        }
    }

    /**
     * 检测IDEA主题并返回对应的主题名称
     * 根据IDEA版本动态调整暗色主题的颜色方案
     */
    private fun detectIdeaTheme(): String {
        return try {
            val lafManager = LafManager.getInstance()
            val currentLaf = lafManager.currentLookAndFeel

            // 检查是否为暗色主题
            val isDarkTheme = when {
                currentLaf?.name?.contains("Dark", ignoreCase = true) == true -> true
                currentLaf?.name?.contains("Darcula", ignoreCase = true) == true -> true
                currentLaf?.name?.contains("High contrast", ignoreCase = true) == true -> true
                else -> {
                    // 通过UI颜色进一步判断
                    val backgroundColor = UIManager.getColor("Panel.background")
                    if (backgroundColor != null) {
                        val brightness = (backgroundColor.red + backgroundColor.green + backgroundColor.blue) / 3
                        brightness < 128
                    } else {
                        // 如果无法检测，默认为暗色（符合大多数开发者习惯）
                        true
                    }
                }
            }

            if (isDarkTheme) {
                // 根据IDE版本选择合适的暗色主题
                if (isOldIdeaUI()) "dark-old" else "dark"
            } else {
                "light"
            }
        } catch (e: Exception) {
            "dark"
        }
    }

    /**
     * 检测是否为旧版IDEA UI
     * 通过检查IDE版本和UI特征来判断
     */
    private fun isOldIdeaUI(): Boolean {
        return try {
            // 方法1: 检查ApplicationInfo版本
            val appInfo = com.intellij.openapi.application.ApplicationInfo.getInstance()
            val majorVersion = appInfo.majorVersion.toIntOrNull() ?: 0
            val minorVersion = appInfo.minorVersion.toIntOrNull() ?: 0

            // IntelliJ IDEA 2022.3 以下版本使用旧UI
            when {
                majorVersion < 2022 -> true
                majorVersion == 2022 && minorVersion < 3 -> true
                else -> {
                    // 方法2: 检查新UI特有的颜色或组件
                    checkOldUIFeatures()
                }
            }
        } catch (e: Exception) {
            // 方法3: 通过UI管理器检查特定颜色
            checkOldUIFeatures()
        }
    }

    /**
     * 通过UI特征检查是否为旧UI
     */
    private fun checkOldUIFeatures(): Boolean {
        return try {
            // 检查旧UI特有的颜色值
            val panelBackground = UIManager.getColor("Panel.background")

            // 旧UI通常使用#3c3f41这种颜色
            if (panelBackground != null) {
                val bgHex = String.format("#%06x", panelBackground.rgb and 0xFFFFFF)

                // 如果背景色接近#3c3f41，可能是旧UI
                bgHex.equals("#3c3f41", ignoreCase = true) ||
                        bgHex.equals("#3C3F41", ignoreCase = true)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 选择文件
     */
    private fun chooseFile() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val descriptor = FileChooserDescriptor(
                    true,   // 允许选择文件
                    false,  // 不允许选择文件夹
                    false,  // 不允许选择jar
                    false,  // 不允许选择jar内容
                    false,  // 不允许选择根目录
                    true    // 允许多选
                )
                descriptor.title = I18nManager.getMessage("dialog.select.files")
                descriptor.description = I18nManager.getMessage("dialog.select.files.description")

                // 设置默认路径为项目根目录
                val projectRoot = project.baseDir
                if (projectRoot != null) {
                    descriptor.roots = listOf(projectRoot)
                    descriptor.withRoots(projectRoot) // 确保根目录设置
                }

                // 使用回调版本的chooseFiles，这是推荐的方式，特别是在macOS上会显示原生对话框
                FileChooser.chooseFiles(descriptor, project, projectRoot) { selectedFiles ->
                    if (selectedFiles.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            sendSelectedFilesToJS(selectedFiles.toTypedArray())
                        }
                    }
                }
            } catch (e: Exception) {
                // 静默处理错误
            }
        }
    }

    /**
     * 获取目录内容（用于自定义文件浏览器）
     */
    private fun getDirectoryContents(relativePath: String) {
        ApplicationManager.getApplication().runReadAction {
            try {
                val baseDir = project.baseDir ?: return@runReadAction

                // 根据相对路径找到目标目录
                val targetDir = if (relativePath.isEmpty()) {
                    baseDir
                } else {
                    baseDir.findFileByRelativePath(relativePath)
                }

                if (targetDir == null || !targetDir.isDirectory) {
                    return@runReadAction
                }

                val items = mutableListOf<Map<String, Any>>()

                // 收集目录内容
                targetDir.children?.sortedWith(compareBy(
                    { !it.isDirectory }, // 目录在前
                    { it.name.lowercase() } // 按名称排序
                ))?.forEach { child ->
                    // 跳过隐藏文件和常见无关目录
                    if (child.name.startsWith(".")) return@forEach
                    if (child.isDirectory && child.name in setOf("node_modules", "build", "target", "out", "__pycache__", ".git", ".idea")) return@forEach

                    val childRelativePath = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"

                    if (child.isDirectory) {
                        items.add(mapOf(
                            "name" to child.name,
                            "path" to childRelativePath,
                            "isDirectory" to true,
                            "extension" to ""
                        ))
                    } else {
                        // 只显示代码文件
                        val ext = child.extension?.lowercase() ?: ""
                        if (isCodeFile(ext) || ext.isEmpty()) {
                            items.add(mapOf(
                                "name" to child.name,
                                "path" to childRelativePath,
                                "isDirectory" to false,
                                "extension" to ext,
                                "size" to child.length
                            ))
                        }
                    }
                }

                val resultJson = gson.toJson(mapOf(
                    "type" to "directory_contents",
                    "path" to relativePath,
                    "parentPath" to if (relativePath.contains("/")) relativePath.substringBeforeLast("/") else "",
                    "items" to items
                ))

                ApplicationManager.getApplication().invokeLater {
                    browser?.cefBrowser?.executeJavaScript(
                        "window.handleDirectoryContents && window.handleDirectoryContents($resultJson);",
                        browser?.cefBrowser?.url, 0
                    )
                }
            } catch (e: Exception) {
                // 静默处理错误
            }
        }
    }

    /**
     * 获取最近打开的文件（当前文件 + 最近4个）
     */
    private fun getRecentFiles() {
        ApplicationManager.getApplication().invokeLater {
            try {
                val baseDir = project.baseDir
                val recentFiles = mutableListOf<Map<String, Any>>()
                var currentFileInfo: Map<String, Any>? = null

                // 获取当前编辑器中打开的文件
                val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                val currentFile = fileEditorManager.selectedFiles.firstOrNull()

                if (currentFile != null && baseDir != null) {
                    val relativePath = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(currentFile, baseDir) ?: currentFile.name
                    currentFileInfo = mapOf(
                        "name" to currentFile.name,
                        "path" to relativePath,
                        "extension" to (currentFile.extension ?: "")
                    )
                }

                // 获取最近打开的文件（排除当前文件）
                val recentFilesManager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project)
                val allOpenFiles = recentFilesManager.openFiles.toList()

                // 收集最近4个文件（排除当前文件）
                allOpenFiles.filter { it != currentFile && baseDir != null }
                    .take(4)
                    .forEach { file ->
                        val relativePath = if (baseDir != null) {
                            com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, baseDir) ?: file.name
                        } else {
                            file.name
                        }
                        recentFiles.add(mapOf(
                            "name" to file.name,
                            "path" to relativePath,
                            "extension" to (file.extension ?: "")
                        ))
                    }

                val resultJson = gson.toJson(mapOf(
                    "type" to "recent_files",
                    "currentFile" to currentFileInfo,
                    "files" to recentFiles
                ))

                browser?.cefBrowser?.executeJavaScript(
                    "window.handleRecentFiles && window.handleRecentFiles($resultJson);",
                    browser?.cefBrowser?.url, 0
                )
            } catch (e: Exception) {
                // 发送空结果
                browser?.cefBrowser?.executeJavaScript(
                    "window.handleRecentFiles && window.handleRecentFiles({files: []});",
                    browser?.cefBrowser?.url, 0
                )
            }
        }
    }

    /**
     * 搜索文件
     */
    private fun searchFiles(fileName: String) {
        ApplicationManager.getApplication().runReadAction {
            try {
                val query = fileName.lowercase()

                // 获取所有文件并进行模糊搜索
                val allFiles = mutableListOf<VirtualFile>()

                // 遍历项目中的所有文件
                project.baseDir?.let { baseDir ->
                    collectFiles(baseDir, allFiles, 0, 1000) // 限制最多1000个文件
                }

                // 执行模糊搜索
                val matchedFiles = allFiles.filter { virtualFile ->
                    val fileName = virtualFile.name.lowercase()
                    val filePath = getRelativePath(virtualFile).lowercase()

                    // 支持多种匹配模式
                    fileName.contains(query) ||
                            filePath.contains(query) ||
                            fuzzyMatch(fileName, query) ||
                            fuzzyMatch(filePath, query)
                }.sortedBy { virtualFile ->
                    // 按匹配度排序：文件名完全匹配 > 文件名开头匹配 > 路径匹配
                    val fileName = virtualFile.name.lowercase()
                    val filePath = getRelativePath(virtualFile).lowercase()
                    when {
                        fileName == query -> 0
                        fileName.startsWith(query) -> 1
                        fileName.contains(query) -> 2
                        filePath.contains(query) -> 3
                        else -> 4
                    }
                }.take(20) // 限制返回20个结果

                val fileInfos = matchedFiles.map { virtualFile ->
                    val relativePath = getRelativePath(virtualFile)
                    mapOf(
                        "name" to virtualFile.name,
                        "path" to relativePath,
                        "fullPath" to virtualFile.path
                    )
                }

                val resultJson = gson.toJson(
                    mapOf(
                        "type" to "file_search_result",
                        "files" to fileInfos,
                        "query" to fileName
                    )
                )

                ApplicationManager.getApplication().invokeLater {
                    browser?.cefBrowser?.executeJavaScript(
                        "window.handleFileSearchResult && window.handleFileSearchResult($resultJson);",
                        browser?.cefBrowser?.url, 0
                    )
                }
            } catch (e: Exception) {
                // 静默处理错误
            }
        }
    }

    /**
     * 收集文件
     */
    private fun collectFiles(dir: VirtualFile, files: MutableList<VirtualFile>, depth: Int, maxFiles: Int) {
        if (depth > 10 || files.size >= maxFiles) return // 限制深度和文件数量

        try {
            dir.children?.forEach { child ->
                if (files.size >= maxFiles) return

                if (child.isDirectory) {
                    // 跳过常见的无关目录
                    if (!child.name.startsWith(".") &&
                        child.name != "node_modules" &&
                        child.name != "build" &&
                        child.name != "target" &&
                        child.name != "out"
                    ) {
                        collectFiles(child, files, depth + 1, maxFiles)
                    }
                } else {
                    // 只收集常见的代码文件
                    val ext = child.extension?.lowercase()
                    if (ext != null && isCodeFile(ext)) {
                        files.add(child)
                    }
                }
            }
        } catch (e: Exception) {
            // 静默处理错误
        }
    }

    /**
     * 判断是否为代码文件
     */
    private fun isCodeFile(extension: String): Boolean {
        return extension in setOf(
            "kt", "java", "js", "ts", "jsx", "tsx", "py", "cpp", "c", "h", "hpp",
            "cs", "php", "rb", "go", "rs", "swift", "dart", "scala", "groovy",
            "xml", "html", "css", "scss", "sass", "less", "json", "yaml", "yml",
            "md", "txt", "properties", "gradle", "sql", "sh", "bat", "ps1"
        )
    }

    /**
     * 模糊匹配
     */
    private fun fuzzyMatch(text: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        if (text.isEmpty()) return false

        var textIndex = 0
        var patternIndex = 0

        while (textIndex < text.length && patternIndex < pattern.length) {
            if (text[textIndex] == pattern[patternIndex]) {
                patternIndex++
            }
            textIndex++
        }

        return patternIndex == pattern.length
    }

    /**
     * 读取文件内容
     */
    private fun readFileContent(filePath: String) {
        ApplicationManager.getApplication().runReadAction {
            try {
                // 先尝试直接通过路径查找
                var virtualFile: VirtualFile? = null

                // 如果是绝对路径，直接使用
                if (filePath.startsWith("/") || filePath.contains(":")) {
                    virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                } else {
                    // 否则在项目中搜索
                    val projectRoot = project.baseDir
                    if (projectRoot != null) {
                        virtualFile = projectRoot.findFileByRelativePath(filePath)
                        if (virtualFile == null) {
                            // 如果找不到，尝试通过文件名搜索
                            val scope = GlobalSearchScope.projectScope(project)
                            val fileName = filePath.substringAfterLast("/")
                            val foundFiles = FilenameIndex.getFilesByName(project, fileName, scope)
                            virtualFile = foundFiles.find { it.virtualFile.path.endsWith(filePath) }?.virtualFile
                                ?: foundFiles.firstOrNull()?.virtualFile
                        }
                    }
                }

                if (virtualFile != null && virtualFile.exists()) {
                    val content = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
                    val fileInfo = mapOf(
                        "path" to getRelativePath(virtualFile),
                        "fullPath" to virtualFile.path,
                        "name" to virtualFile.name,
                        "content" to content,
                        "size" to virtualFile.length,
                        "extension" to virtualFile.extension
                    )

                    val resultJson = gson.toJson(
                        mapOf(
                            "type" to "file_content",
                            "file" to fileInfo
                        )
                    )


                    ApplicationManager.getApplication().invokeLater {
                        browser?.cefBrowser?.executeJavaScript(
                            "window.handleFileContent && window.handleFileContent($resultJson);",
                            browser?.cefBrowser?.url, 0
                        )
                    }
                } else {
                    val errorJson = gson.toJson(
                        mapOf(
                            "type" to "file_error",
                            "message" to "${I18nManager.getMessage("api.error", "File not found: $filePath")}"
                        )
                    )

                    ApplicationManager.getApplication().invokeLater {
                        browser?.cefBrowser?.executeJavaScript(
                            "window.handleFileError && window.handleFileError($errorJson);",
                            browser?.cefBrowser?.url, 0
                        )
                    }
                }
            } catch (e: Exception) {
                val errorJson = gson.toJson(
                    mapOf(
                        "type" to "file_error",
                        "message" to I18nManager.getMessage("api.error", "Error reading file: ${e.message}")
                    )
                )

                ApplicationManager.getApplication().invokeLater {
                    browser?.cefBrowser?.executeJavaScript(
                        "window.handleFileError && window.handleFileError($errorJson);",
                        browser?.cefBrowser?.url, 0
                    )
                }
            }
        }
    }

    /**
     * 发送选中的文件到JS
     */
    private fun sendSelectedFilesToJS(files: Array<VirtualFile>) {
        val fileInfos = files.map { virtualFile ->
            val relativePath = getRelativePath(virtualFile)
            mapOf(
                "name" to virtualFile.name,
                "path" to relativePath,
                "fullPath" to virtualFile.path,
                "size" to virtualFile.length,
                "extension" to (virtualFile.extension ?: "")
            )
        }

        val resultJson = gson.toJson(
            mapOf(
                "type" to "files_selected",
                "files" to fileInfos
            )
        )

        browser?.cefBrowser?.executeJavaScript(
            "window.handleFilesSelected && window.handleFilesSelected($resultJson);",
            browser?.cefBrowser?.url, 0
        )
    }

    /**
     * 获取文件的相对路径
     */
    private fun getRelativePath(virtualFile: VirtualFile): String {
        val projectRoot = project.baseDir
        return if (projectRoot != null && virtualFile.path.startsWith(projectRoot.path)) {
            virtualFile.path.substring(projectRoot.path.length + 1)
        } else {
            virtualFile.path
        }
    }

    /**
     * 从外部添加选中代码到聊天（由Action调用）
     */
    fun addSelectedCodeToChat(code: String, fileName: String, language: String, lineRange: String) {
        val codeInfo = mapOf(
            "text" to code,
            "fileName" to fileName,
            "language" to language,
            "lineRange" to lineRange,
            "hasSelection" to true
        )

        val resultJson = gson.toJson(
            mapOf(
                "type" to "selected_code",
                "code" to codeInfo
            )
        )

        browser?.cefBrowser?.executeJavaScript(
            "window.handleSelectedCode && window.handleSelectedCode($resultJson);",
            browser?.cefBrowser?.url, 0
        )
    }

    /**
     * Toolbar Action methods
     */
    fun showHistoryPanel() {
        browser?.cefBrowser?.executeJavaScript("if (window.showHistoryPanel) { window.showHistoryPanel(); }", browser?.cefBrowser?.url, 0)
    }

    fun newChat() {
        browser?.cefBrowser?.executeJavaScript("if (window.createNewChat) { window.createNewChat(); }", browser?.cefBrowser?.url, 0)
    }

    fun clearChatHistory() {
        browser?.cefBrowser?.executeJavaScript("if (window.clearHistory) { window.clearHistory(); }", browser?.cefBrowser?.url, 0)
    }

    fun toggleTheme() {
        browser?.cefBrowser?.executeJavaScript("if (window.toggleTheme) { window.toggleTheme(); }", browser?.cefBrowser?.url, 0)
    }

    override fun dispose() {
        if (browser != null) {
            Disposer.dispose(browser!!)
        }
        removeInstance(project)

        // 清理缓存资源
        synchronized(htmlCache) {
            htmlCache.clear()
        }
        synchronized(resourceCache) {
            resourceCache.clear()
        }
        modelsCache = null
        lastModelsCacheTime = 0
        iconCache.clear()
        inlineResourceCache.clear()
        // 清理流式缓冲区
        forceClearStreamingBuffer()

        // 清理静态实例
        if (instances.isEmpty()) {
            clearHtmlCache()
        }
    }
} 