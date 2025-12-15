package com.wenguang.ai.assistant.completion

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.messages.MessageBusConnection

/**
 * 监听器：当用户按下 Tab 接受补全后，自动触发下一轮补全
 */
class TabbyAutoTriggerActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection: MessageBusConnection = project.messageBus.connect(project)

        connection.subscribe(AnActionListener.TOPIC, object : AnActionListener {

            // 【最终修正】覆盖 beforeActionPerformed，这是最稳定的回调点之一
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                // 如果不是当前项目，则返回
                if (event.project != project) return

                // 获取当前执行的动作 ID
                val actionId = event.actionManager.getId(action)

                // 检查是否是 "插入内联补全" 动作 (即用户按了 Tab)
                // 注意：在 beforeActionPerformed 触发，比 afterActionPerformed 稍早。
                actionId?.let { id ->
                    if (id.contains("InsertInlineCompletion", ignoreCase = true)) {
                        // 我们在 beforeActionPerformed 中检测到动作，但触发新的补全
                        // 必须延迟到动作完成之后，所以依然使用 triggerNextCompletion
                        triggerNextCompletion(project)
                    }
                }
            }
        })
    }

    private fun triggerNextCompletion(project: Project) {

        // 必须使用 invokeLater 等待当前 Tab 的字符插入到编辑器完毕后，再请求下一次
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater

            // 确保文档已提交
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)

            // 获取 Inline Completion 处理器
            val handler = InlineCompletion.getHandlerOrNull(editor) ?: return@invokeLater

            // 获取当前主光标
            val caret = editor.caretModel.primaryCaret

            // 构造 DirectCall 事件
            val event = InlineCompletionEvent.DirectCall(
                editor = editor,
                caret = caret
            )

            // 强制触发补全
            handler.invoke(event)
        }
    }
}