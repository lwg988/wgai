package com.wenguang.ai.assistant.git

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.ui.EditorTextField
import com.intellij.vcs.commit.CommitWorkflowUi
import com.wenguang.ai.assistant.i18n.I18nManager
import javax.swing.JComponent

/**
 * 生成AI提交注释的Action
 */
class GenerateCommitMessageAction : AnAction(I18nManager.getMessage("action.generate.commit.message")) {
    private val commitMessageGenerator = GitCommitMessageGenerator.getInstance()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        try {
            // 获取当前的变更列表
            val changeListManager = ChangeListManager.getInstance(project)
            val changes = changeListManager.defaultChangeList.changes.toList()

            if (changes.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    I18nManager.getMessage("git.no.changes"),
                    I18nManager.getMessage("git.tip")
                )
                return
            }

            // 获取提交消息框
            val commitMessageComponent = findCommitMessageComponent(event)
            if (commitMessageComponent == null) {
                Messages.showErrorDialog(
                    project,
                    I18nManager.getMessage("git.commit.message.box.not.found"),
                    I18nManager.getMessage("git.error")
                )
                return
            }

            // 显示加载状态
            setCommitMessageText(commitMessageComponent, I18nManager.getMessage("git.generating.message"))

            // 生成提交注释
            commitMessageGenerator.generateCommitMessage(
                project = project,
                changes = changes,
                onResult = { message ->
                    ApplicationManager.getApplication().invokeLater {
                        setCommitMessageText(commitMessageComponent, message)
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, error, I18nManager.getMessage("git.generate.failed"))
                    }
                }
            )

        } catch (e: Exception) {
            println("${I18nManager.getMessage("git.action.execute.failed")}: $e")
            Messages.showErrorDialog(
                project,
                I18nManager.getMessage("git.generate.failed.with.message", e.message ?: ""),
                I18nManager.getMessage("git.error")
            )
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val hasChanges = project?.let {
            ChangeListManager.getInstance(it).defaultChangeList.changes.isNotEmpty()
        } ?: false

        event.presentation.isEnabled = hasChanges
    }

    /**
     * 查找提交消息组件
     */
    private fun findCommitMessageComponent(event: AnActionEvent): JComponent? {
        // 尝试从VCS数据中获取提交消息
        val commitMessage = event.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (commitMessage is JComponent) {
            return commitMessage
        }

        // 尝试从CommitWorkflowUi获取
        val commitWorkflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (commitWorkflowUi is CommitWorkflowUi) {
            val commitMessageUi = commitWorkflowUi.commitMessageUi
            if (commitMessageUi is CommitMessage) {
                return commitMessageUi.editorField
            }
        }

        return null
    }

    /**
     * 设置提交消息文本
     */
    private fun setCommitMessageText(component: JComponent, text: String) {
        when (component) {
            is EditorTextField -> {
                component.text = text
                if (text.isNotEmpty()) {
                    component.caretModel?.moveToOffset(text.length)
                }
            }

            is javax.swing.JTextArea -> {
                component.text = text
                if (text.isNotEmpty()) {
                    component.caretPosition = text.length
                }
            }

            else -> {
                // 尝试通过反射设置文本
                try {
                    val method = component.javaClass.getMethod("setText", String::class.java)
                    method.invoke(component, text)
                } catch (e: Exception) {
                    println("${I18nManager.getMessage("git.unable.to.set.message")}: $e")
                }
            }
        }
    }
} 