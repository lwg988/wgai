package com.wenguang.ai.assistant.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.wenguang.ai.assistant.completion.TabbyClient
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.AppSettings
import com.wenguang.ai.assistant.settings.LanguageChangeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent

class CodeCompletionStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "WingCode.CodeCompletion"

    override fun getDisplayName(): String = I18nManager.getMessage("statusbar.code.completion")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return CodeCompletionStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        // 清理资源
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class CodeCompletionStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation, LanguageChangeNotifier {

    companion object {
        const val ID = "WingCode.CodeCompletion"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var lastConnectionTime = 0L
    private var lastConnectionResult = false
    private var messageBusConnection: com.intellij.util.messages.MessageBusConnection? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // 启动时检查连接状态，如果开启了但连接失败则自动关闭
        if (AppSettings.instance.state.codeCompletionEnabled) {
            checkConnectionOnStartup()
        }

        // 订阅语言切换事件
        messageBusConnection = ApplicationManager.getApplication().messageBus.connect()
        messageBusConnection?.subscribe(LanguageChangeNotifier.TOPIC, this)
    }

    override fun dispose() {
        // 断开消息总线连接
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }

    /**
     * 语言切换时的回调
     */
    override fun onLanguageChanged(oldLanguage: String, newLanguage: String) {
        // 语言切换后刷新状态栏文本
        refreshStatusBar()
    }

    override fun getText(): String {
        val enabled = AppSettings.instance.state.codeCompletionEnabled
        return if (enabled) {
            I18nManager.getMessage("statusbar.code.completion.enabled")
        } else {
            I18nManager.getMessage("statusbar.code.completion.disabled")
        }
    }

    override fun getTooltipText(): String {
        return I18nManager.getMessage("statusbar.code.completion.tooltip")
    }

    override fun getAlignment(): Float {
        return 0.5f
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer {
            toggleCodeCompletion()
        }
    }

    private fun toggleCodeCompletion() {
        val settings = AppSettings.instance.state
        val wasEnabled = settings.codeCompletionEnabled
        
        if (!wasEnabled) {
            // 尝试开启：先测试连接
            testConnectionAndToggle()
        } else {
            // 直接关闭
            settings.codeCompletionEnabled = false
            refreshStatusBar()
        }
    }

    private fun testConnectionAndToggle() {
        val settings = AppSettings.instance.state
        
        if (settings.tabbyEndpoint.isBlank() || settings.tabbyToken.isBlank()) {
            showErrorMessage(I18nManager.getMessage("error.tabby.config.missing"))
            return
        }

        coroutineScope.launch {
            try {
                val client = TabbyClient(settings.tabbyEndpoint, settings.tabbyToken)
                val isConnected = client.testConnection()
                
                withContext(Dispatchers.Main) {
                    if (isConnected) {
                        settings.codeCompletionEnabled = true
                        refreshStatusBar()
                    } else {
                        showErrorMessage(I18nManager.getMessage("error.tabby.connection.failed"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showErrorMessage(I18nManager.getMessage("error.tabby.connection.error", e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun checkConnectionOnStartup() {
        val currentTime = System.currentTimeMillis()
        // 避免频繁检查：如果上次检查在30秒内且成功，则跳过
        if (currentTime - lastConnectionTime < 30000 && lastConnectionResult) {
            return
        }

        val settings = AppSettings.instance.state
        if (settings.tabbyEndpoint.isBlank() || settings.tabbyToken.isBlank()) {
            // 配置缺失，静默关闭
            settings.codeCompletionEnabled = false
            refreshStatusBar()
            return
        }

        coroutineScope.launch {
            try {
                val client = TabbyClient(settings.tabbyEndpoint, settings.tabbyToken)
                val isConnected = client.testConnection()
                
                lastConnectionTime = currentTime
                lastConnectionResult = isConnected
                
                withContext(Dispatchers.Main) {
                    if (!isConnected) {
                        // 连接失败，静默关闭
                        settings.codeCompletionEnabled = false
                        refreshStatusBar()
                    }
                }
            } catch (e: Exception) {
                lastConnectionTime = currentTime
                lastConnectionResult = false
                
                withContext(Dispatchers.Main) {
                    // 连接出错，静默关闭
                    settings.codeCompletionEnabled = false
                    refreshStatusBar()
                }
            }
        }
    }

    private fun showErrorMessage(message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(
                project,
                message,
                I18nManager.getMessage("dialog.title.connection.test")
            )
        }
    }

    private fun refreshStatusBar() {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.updateWidget(ID)
        }
    }
}