package com.wenguang.ai.assistant.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.wenguang.ai.assistant.i18n.I18nManager

/**
 * AI助手功能统一Action类
 * 包含优化代码、补全注释、解释代码、定位缺陷、代码评审等功能
 */
sealed class AIAssistantAction(private val actionKey: String, private val promptKey: String) : AnAction() {

    init {
        templatePresentation.text = I18nManager.getMessage(actionKey)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return

        try {
            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText

            if (selectedText.isNullOrEmpty()) {
                println(I18nManager.getMessage("action.no.selection"))
                return
            }

            // 获取文件信息和行号
            val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
            val fileName = virtualFile?.name ?: "unknown"
            val language = virtualFile?.extension ?: "text"

            // 获取选中文本的行号范围
            val document = editor.document
            val startOffset = selectionModel.selectionStart
            val endOffset = selectionModel.selectionEnd
            val startLine = document.getLineNumber(startOffset) + 1
            val endLine = document.getLineNumber(endOffset) + 1

            val lineRange = if (startLine == endLine) {
                startLine.toString()
            } else {
                "$startLine-$endLine"
            }

            // 查找聊天工具窗口
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("WingCode")

            if (toolWindow != null) {
                // 激活工具窗口
                toolWindow.activate {
                    // 发送带有预设提示的代码到聊天窗口
                    sendCodeToChatWithPrompt(project, selectedText, fileName, language, lineRange, getPrompt())
                }
            } else {
                println(I18nManager.getMessage("action.tool.window.not.found"))
            }

        } catch (e: Exception) {
            println("${I18nManager.getMessage("action.error.adding.code")}: $e")
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true

        event.presentation.isEnabled = hasSelection
        event.presentation.isVisible = hasSelection
    }

    /**
     * 获取该功能的提示
     */
    private fun getPrompt(): String = I18nManager.getMessage(promptKey)

    /**
     * 发送代码到聊天窗口，并附带特定的提示
     */
    private fun sendCodeToChatWithPrompt(project: Project, code: String, fileName: String, language: String, lineRange: String, prompt: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow("WingCode")

                toolWindow?.contentManager?.contents?.forEach { content ->
                    val component = content.component
                    if (component.javaClass.simpleName.contains("ChatView")) {
                        try {
                            // 先添加代码引用
                            val addCodeMethod = component.javaClass.getMethod(
                                "addSelectedCodeToChat",
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java
                            )
                            addCodeMethod.invoke(component, code, fileName, language, lineRange)

                            // 然后发送预设的提示消息
                            sendPromptMessage(component, prompt)
                            
                            println("${I18nManager.getMessage("action.code.sent.success", code.length, lineRange)} with prompt: $prompt")
                            return@invokeLater
                        } catch (e: Exception) {
                            println("${I18nManager.getMessage("action.error.invoke.method")}: $e")
                        }
                    }
                }

                println(I18nManager.getMessage("action.chatview.not.found"))
            }
        } catch (e: Exception) {
            println("${I18nManager.getMessage("action.error.sending.code")}: $e")
        }
    }

    /**
     * 发送提示消息到聊天窗口
     */
    private fun sendPromptMessage(chatViewComponent: Any, prompt: String) {
        try {
            // 通过反射调用JavaScript方法来发送消息
            val browserField = chatViewComponent.javaClass.getDeclaredField("browser")
            browserField.isAccessible = true
            val browser = browserField.get(chatViewComponent)

            if (browser != null) {
                val cefBrowserMethod = browser.javaClass.getMethod("getCefBrowser")
                val cefBrowser = cefBrowserMethod.invoke(browser)

                if (cefBrowser != null) {
                    val executeJavaScriptMethod = cefBrowser.javaClass.getMethod(
                        "executeJavaScript",
                        String::class.java,
                        String::class.java,
                        Int::class.java
                    )

                    // 转义提示文本
                    val escapedPrompt = prompt.replace("\"", "\\\"").replace("\n", "\\n")
                    
                    // 调用JavaScript函数来设置输入框内容并自动发送
                    val jsCode = """
                        if (window.setInputAndSend) {
                            window.setInputAndSend("$escapedPrompt");
                        } else {
                            // 备用方案：直接设置输入框内容
                            const userInput = document.getElementById('userInput');
                            if (userInput) {
                                userInput.value = "$escapedPrompt";
                                userInput.dispatchEvent(new Event('input'));
                                // 延迟发送，确保内容已设置
                                setTimeout(() => {
                                    if (window.sendMessage) {
                                        window.sendMessage();
                                    }
                                }, 100);
                            }
                        }
                    """.trimIndent()

                    executeJavaScriptMethod.invoke(cefBrowser, jsCode, "", 0)
                }
            }
        } catch (e: Exception) {
            println("Error sending prompt message: $e")
        }
    }
}

/**
 * 优化代码Action
 */
class OptimizeCodeAction : AIAssistantAction("action.optimize.code", "ai.prompt.optimize")

/**
 * 补全注释Action
 */
class AddCommentsAction : AIAssistantAction("action.add.comments", "ai.prompt.comments")

/**
 * 解释代码Action
 */
class ExplainCodeAction : AIAssistantAction("action.explain.code", "ai.prompt.explain")

/**
 * 定位代码缺陷Action
 */
class FindBugsAction : AIAssistantAction("action.find.bugs", "ai.prompt.find.bugs")

/**
 * 代码评审Action
 */
class CodeReviewAction : AIAssistantAction("action.code.review", "ai.prompt.review")