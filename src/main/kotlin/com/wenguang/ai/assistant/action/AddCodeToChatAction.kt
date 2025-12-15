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
 * 添加选中代码到聊天对话的Action
 */
class AddCodeToChatAction : AnAction(I18nManager.getMessage("action.add.code.to.chat")) {

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
            val startLine = document.getLineNumber(startOffset) + 1 // +1 因为行号从0开始
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
                    // 发送选中的代码到聊天窗口
                    sendCodeToChat(project, selectedText, fileName, language, lineRange)
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
     * 发送代码到聊天窗口
     */
    private fun sendCodeToChat(project: Project, code: String, fileName: String, language: String, lineRange: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                // 查找ChatView实例并发送代码
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow("WingCode")

                toolWindow?.contentManager?.contents?.forEach { content ->
                    val component = content.component
                    // 直接检查是否包含ChatView
                    if (component.javaClass.simpleName.contains("ChatView")) {
                        try {
                            val method = component.javaClass.getMethod(
                                "addSelectedCodeToChat",
                                String::class.java,
                                String::class.java,
                                String::class.java,
                                String::class.java
                            )
                            method.invoke(component, code, fileName, language, lineRange)
                            println(I18nManager.getMessage("action.code.sent.success", code.length, lineRange))
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
}