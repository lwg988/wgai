package com.wenguang.ai.assistant.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.wenguang.ai.assistant.i18n.I18nManager

/**
 * AI助手菜单组
 */
class AIAssistantActionGroup : ActionGroup() {

    init {
        isPopup = true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        return arrayOf(
            OptimizeCodeAction(),
            AddCommentsAction(),
            ExplainCodeAction(),
            FindBugsAction(),
            CodeReviewAction()
        )
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        // 动态更新菜单组文本
        event.presentation.text = I18nManager.getMessage("action.ai.assistant")
    }

    private class SeparatorAction : AnAction() {
        init {
            templatePresentation.text = "---"
        }

        override fun actionPerformed(e: AnActionEvent) {
            // Separator不执行任何操作
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
            e.presentation.isVisible = true
        }
    }
}