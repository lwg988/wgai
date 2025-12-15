package com.wenguang.ai.assistant.toolWindow.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader
import com.wenguang.ai.assistant.i18n.I18nManager
import org.jetbrains.annotations.NotNull

class NewChatAction : AnAction() {
    init {
        templatePresentation.icon = IconLoader.getIcon("/toolbar-icons/new-chat.svg", javaClass)
        templatePresentation.text = I18nManager.getMessage("newChatBtn")
    }

    override fun actionPerformed(@NotNull e: AnActionEvent) {
        val project = e.project ?: return
        val chatView = com.wenguang.ai.assistant.toolWindow.ChatView.getInstance(project)
        chatView?.newChat()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = I18nManager.getMessage("newChatBtn")
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
