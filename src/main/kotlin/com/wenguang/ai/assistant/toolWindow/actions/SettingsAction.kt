package com.wenguang.ai.assistant.toolWindow.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.wenguang.ai.assistant.i18n.I18nManager
import com.intellij.openapi.util.IconLoader
import org.jetbrains.annotations.NotNull
import javax.swing.Icon

class SettingsAction : AnAction() {
    init {
        templatePresentation.icon = IconLoader.getIcon("/toolbar-icons/settings.svg", javaClass)
        templatePresentation.text = I18nManager.getMessage("settings")
    }

    override fun actionPerformed(@NotNull e: AnActionEvent) {
        val project = e.project ?: return
        val settingsUtil = com.intellij.openapi.options.ShowSettingsUtil.getInstance()
        val configurable = com.wenguang.ai.assistant.settings.AppSettingsConfigurable()
        settingsUtil.editConfigurable(project, configurable)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = I18nManager.getMessage("settings")
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
