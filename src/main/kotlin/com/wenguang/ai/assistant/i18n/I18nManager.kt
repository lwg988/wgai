package com.wenguang.ai.assistant.i18n

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 国际化消息管理器
 */
object I18nManager {

    @org.jetbrains.annotations.NonNls
    private const val BUNDLE_BASE_NAME = "messages/messages"

    private var currentLocale: Locale = Locale.getDefault()
    private var currentBundle: ResourceBundle? = null
    private var isInitialized = false  // 标记是否已经初始化

    // LRU消息缓存：限制缓存大小防止内存泄漏
    // 使用LinkedHashMap实现LRU策略
    private val messageCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
            // 当缓存大小超过1000时，移除最久未使用的条目
            return size > 1000
        }
    }
    private var cacheVersion: Long = 0
    private val cacheLock = Any()

    /**
     * 获取本地化消息（带缓存）
     */
    @Nls
    fun getMessage(@PropertyKey(resourceBundle = BUNDLE_BASE_NAME) key: String, vararg params: Any): String {
        val cacheKey = "${currentLocale}_${key}_${params.contentHashCode()}"

        // 快速路径：从缓存获取
        synchronized(cacheLock) {
            val cached = messageCache[cacheKey]
            if (cached != null) {
                return if (params.isEmpty()) cached else MessageFormat.format(cached, *params)
            }
        }

        // 缓存未命中，执行查找
        val message = loadMessage(key, params)
        // 更新缓存
        synchronized(cacheLock) {
            messageCache[cacheKey] = message
        }
        return message
    }

    /**
     * 从资源束加载消息
     */
    @Nls
    private fun loadMessage(key: String, params: Array<out Any>): String {
        return try {
            val bundle = getCurrentBundle()
            val message = bundle.getString(key)
            if (params.isEmpty()) {
                message
            } else {
                MessageFormat.format(message, *params)
            }
        } catch (e: Exception) {
            println("I18nManager: Failed to get message for key '$key': ${e.message}")
            // 如果当前语言的资源文件找不到，尝试使用默认的英文资源文件
            try {
                val fallbackBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH)
                val message = fallbackBundle.getString(key)
                if (params.isEmpty()) {
                    message
                } else {
                    MessageFormat.format(message, *params)
                }
            } catch (fallbackException: Exception) {
                println("I18nManager: Fallback also failed for key '$key': ${fallbackException.message}")
                // 最后的fallback：返回键名本身
                "!$key!"
            }
        }
    }

    /**
     * 获取本地化消息（带默认值）
     */
    @Nls
    fun getMessage(
        @PropertyKey(resourceBundle = BUNDLE_BASE_NAME) key: String,
        defaultValue: String,
        vararg params: Any
    ): String {
        return try {
            getMessage(key, *params)
        } catch (e: Exception) {
            defaultValue
        }
    }

    /**
     * 获取当前资源束
     */
    private fun getCurrentBundle(): ResourceBundle {
        if (currentBundle == null) {
            currentBundle = loadBundle(currentLocale)
        }
        return currentBundle!!
    }

    /**
     * 加载指定语言环境的资源束
     */
    private fun loadBundle(locale: Locale): ResourceBundle {
        return try {
            val bundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale)
            bundle
        } catch (e: Exception) {
            // 如果加载失败，尝试加载默认的英文资源束
            try {
                ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH)
            } catch (fallbackException: Exception) {
                // 尝试加载默认资源束（没有语言后缀的）
                ResourceBundle.getBundle(BUNDLE_BASE_NAME)
            }
        }
    }

    /**
     * 刷新语言环境
     * @param userLanguageCode 可选的用户语言代码，如果为null则自动检测系统语言
     */
    fun refreshLocale(userLanguageCode: String? = null) {
        // 如果是第一次调用且没有提供用户语言代码，则使用系统默认语言
        val newLocale = if (userLanguageCode != null && userLanguageCode != "auto") {
            getLocaleByCode(userLanguageCode)
        } else if (!isInitialized) {
            // 第一次初始化时，如果没有指定用户语言，使用系统语言
            LocaleDetector.getCurrentLocale()
        } else {
            // 如果已经初始化过，且没有提供新的语言代码，保持当前语言
            currentLocale
        }

        // 只有在语言真正改变时才清除缓存
        if (newLocale != currentLocale) {
            val oldLocale = currentLocale
            currentLocale = newLocale
            Locale.setDefault(currentLocale)
            currentBundle = null // 清空缓存，强制重新加载

            // 清除消息缓存
            synchronized(cacheLock) {
                messageCache.clear()
                cacheVersion++
            }

            // 标记为已初始化
            isInitialized = true

            println("I18nManager: Language changed from $oldLocale to $newLocale")
        } else if (!isInitialized) {
            // 第一次设置语言，标记为已初始化
            isInitialized = true
            println("I18nManager: Initialized with locale: $newLocale")
        }
    }

    /**
     * 根据语言代码获取对应的Locale对象
     */
    private fun getLocaleByCode(languageCode: String): Locale {
        return when (languageCode) {
            "zh" -> Locale.CHINESE
            "zh_TW" -> Locale.TAIWAN
            "en" -> Locale.ENGLISH
            "es" -> Locale("es")
            "fr" -> Locale.FRENCH
            "ar" -> Locale("ar")
            "hi" -> Locale("hi")
            "ja" -> Locale.JAPANESE
            "bn" -> Locale("bn")
            "ru" -> Locale("ru")
            "de" -> Locale.GERMAN
            "ko" -> Locale.KOREAN
            else -> Locale.ENGLISH
        }
    }

    /**
     * 获取当前语言环境
     */
    fun getCurrentLocale(): Locale = currentLocale

    /**
     * 检查是否为中文环境
     */
    fun isChineseLocale(): Boolean = LocaleDetector.isChineseUser()

    // 常用消息快捷方法
    object Chat {
        fun welcome() = getMessage("chat.welcome")
        fun loading() = getMessage("chat.loading")
        fun inputPlaceholder() = getMessage("chat.input.placeholder")
        fun send() = getMessage("chat.send")
        fun clear() = getMessage("chat.clear")
        fun theme() = getMessage("chat.theme")
        fun thinking() = getMessage("chat.thinking")
        fun modelSelect() = getMessage("chat.model.select")
    }

    object Button {
        fun copy() = getMessage("button.copy")
        fun copied() = getMessage("button.copied")
        fun insert() = getMessage("button.insert")
        fun inserted() = getMessage("button.inserted")
    }

    object Dialog {
        fun clearTitle() = getMessage("dialog.clear.title")
        fun clearConfirm() = getMessage("dialog.clear.confirm")
        fun clearCancel() = getMessage("dialog.clear.cancel")
    }

    object Message {
        fun clearSuccess() = getMessage("message.clear.success")
        fun noModel() = getMessage("message.no.model")
        fun requestFailed() = getMessage("message.request.failed")
    }

    object Settings {
        fun title() = getMessage("settings.title")
        fun defaultModel() = getMessage("settings.default.model")
        fun systemPrompt() = getMessage("settings.system.prompt")
        fun modelManage() = getMessage("settings.model.manage")
        fun modelAdd() = getMessage("settings.model.add")
        fun modelEdit() = getMessage("settings.model.edit")
        fun modelDelete() = getMessage("settings.model.delete")
        fun modelDeleteConfirm() = getMessage("settings.model.delete.confirm")
        fun modelDeleteTitle() = getMessage("settings.model.delete.title")
        fun modelSelectFirst() = getMessage("settings.model.select.first")
        fun ollamaAddress() = getMessage("settings.ollama.address")
        fun ollamaDetect() = getMessage("settings.ollama.detect")
        fun ollamaImportTitle() = getMessage("settings.ollama.import.title")
        fun ollamaImportSuccess(count: Int) = getMessage("settings.ollama.import.success", count)
        fun ollamaImportNone() = getMessage("settings.ollama.import.none")
        fun ollamaImportError(error: String) = getMessage("settings.ollama.import.error", error)
        fun confirm() = getMessage("settings.confirm")
        fun cancel() = getMessage("settings.cancel")
        fun success() = getMessage("settings.success")
        fun error() = getMessage("settings.error")
        fun prompt() = getMessage("settings.prompt")

        object Table {
            fun name() = getMessage("settings.model.table.name")
            fun provider() = getMessage("settings.model.table.provider")
            fun model() = getMessage("settings.model.table.model")
            fun apiUrl() = getMessage("settings.model.table.apiurl")
            fun format() = getMessage("settings.model.table.format")
            fun enabled() = getMessage("settings.model.table.enabled")
        }
    }
}