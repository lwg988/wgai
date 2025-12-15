package com.wenguang.ai.assistant.settings

import com.intellij.util.messages.Topic

/**
 * 模型配置变更通知接口
 */
interface ModelConfigChangeNotifier {
    /**
     * 当模型配置发生变更时调用
     */
    fun onModelConfigChanged()

    companion object {
        val TOPIC = Topic.create("ModelConfigChanged", ModelConfigChangeNotifier::class.java)
    }
}