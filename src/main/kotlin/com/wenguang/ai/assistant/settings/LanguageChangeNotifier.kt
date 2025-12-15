package com.wenguang.ai.assistant.settings

import com.intellij.util.messages.Topic

/**
 * 语言配置变更通知接口
 */
interface LanguageChangeNotifier {
    /**
     * 当语言配置发生变更时调用
     * @param oldLanguage 之前的语言代码
     * @param newLanguage 新的语言代码
     */
    fun onLanguageChanged(oldLanguage: String, newLanguage: String)

    companion object {
        val TOPIC = Topic.create("LanguageChanged", LanguageChangeNotifier::class.java)
    }
}
