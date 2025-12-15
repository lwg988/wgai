package com.wenguang.ai.assistant.completion

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.wenguang.ai.assistant.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TabbyInlineCompletionProvider : InlineCompletionProvider {

    private val logger = Logger.getInstance(TabbyInlineCompletionProvider::class.java)
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("TabbyCompletion")

    // 缓存 Client，避免每次按键都创建连接池
    private var cachedClient: TabbyClient? = null
    private var cachedEndpoint: String = ""
    private var cachedToken: String = ""

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!AppSettings.instance.state.codeCompletionEnabled) return false

        return when (event) {
            is InlineCompletionEvent.DocumentChange -> true
            // 【关键】必须允许 DirectCall，这样我们的监听器才能手动触发补全
            is InlineCompletionEvent.DirectCall -> true
            else -> false
        }
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val project = editor.project ?: return InlineCompletionSuggestion.Empty
        val offset = request.endOffset
        val document = editor.document
        val text = document.text

        // 边界检查
        if (offset > text.length) return InlineCompletionSuggestion.Empty

        return try {
            val completionText = withContext(Dispatchers.IO) {
                val client = getSharedClient() ?: return@withContext null

                // 获取上下文 (安全截取)
                val prefix = text.substring((offset - 3000).coerceAtLeast(0), offset)
                val suffix = text.substring(offset, (offset + 1000).coerceAtMost(text.length))

                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                val language = if (psiFile != null) getLanguageFromFile(psiFile) else "text"

                // 调用 API
                client.requestCompletion(language, text, prefix, suffix)
            }

            if (completionText.isNullOrBlank()) {
                return InlineCompletionSuggestion.Empty
            }

            // 构建补全建议
            InlineCompletionSingleSuggestion.build {
                emit(InlineCompletionGrayTextElement(completionText))
            }

        } catch (e: Exception) {
            logger.warn("Tabby completion failed: ${e.message}")
            InlineCompletionSuggestion.Empty
        }
    }

    /**
     * 获取单例 Client，防止频繁创建销毁
     */
    private fun getSharedClient(): TabbyClient? {
        val settings = AppSettings.instance.state
        val currentEndpoint = settings.tabbyEndpoint
        val currentToken = settings.tabbyToken

        if (currentEndpoint.isBlank()) return null

        if (cachedClient == null || currentEndpoint != cachedEndpoint || currentToken != cachedToken) {
            cachedEndpoint = currentEndpoint
            cachedToken = currentToken
            cachedClient = TabbyClient(cachedEndpoint, currentToken)
        }
        return cachedClient
    }

    private fun getLanguageFromFile(psiFile: PsiFile): String {
        return when (psiFile.language.displayName.lowercase()) {
            "java" -> "java"
            "kotlin" -> "kotlin"
            "javascript" -> "javascript"
            "typescript" -> "typescript"
            "python" -> "python"
            "go" -> "go"
            "rust" -> "rust"
            "c" -> "c"
            "c++" -> "cpp"
            "c#" -> "csharp"
            "php" -> "php"
            "ruby" -> "ruby"
            "sql" -> "sql"
            "xml" -> "xml"
            "html" -> "html"
            "css" -> "css"
            else -> "text"
        }
    }
}