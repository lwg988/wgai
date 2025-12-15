package com.wenguang.ai.assistant.i18n

import java.util.*

/**
 * 语言环境检测工具
 */
object LocaleDetector {

    /**
     * 获取当前应该使用的语言环境
     * 优先级：Windows显示语言 > 系统语言 > 默认英文
     * 注意：为了避免循环依赖，这里不直接读取AppSettings
     * AppSettings会在初始化完成后自行更新语言设置
     */
    fun getCurrentLocale(): Locale {

        // 1. 检查系统语言环境属性
        try {
            val userLanguage = System.getProperty("user.language")
            val userRegion = System.getProperty("user.region")
            val userCountry = System.getProperty("user.country")

            println("LocaleDetector: user.language = $userLanguage")
            println("LocaleDetector: user.region = $userRegion")
            println("LocaleDetector: user.country = $userCountry")

            // 根据系统语言返回对应的locale
            val locale = when (userLanguage) {
                "zh" -> { // 中文
                    when {
                        userCountry != null -> Locale(userLanguage, userCountry)
                        userRegion != null -> Locale(userLanguage, userRegion)
                        else -> Locale(userLanguage)
                    }
                }
                "en" -> Locale.ENGLISH // 英语
                "es" -> Locale("es") // 西班牙语
                "fr" -> Locale.FRENCH // 法语
                "ar" -> Locale("ar") // 阿拉伯语
                "hi" -> Locale("hi") // 印地语
                "ja" -> Locale.JAPANESE // 日语
                "bn" -> Locale("bn") // 孟加拉语
                "ru" -> Locale("ru") // 俄语
                "de" -> Locale.GERMAN // 德语
                "ko" -> Locale.KOREAN // 韩语
                else -> {
                    // 2. 检查系统默认语言环境
                    val systemLocale = Locale.getDefault()
                    val systemLang = systemLocale.language
                    when (systemLang) {
                        "zh" -> systemLocale
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
                        else -> Locale.ENGLISH // 默认英文
                    }
                }
            }
            return locale
        } catch (e: Exception) {
            // 静默处理
        }

        // 3. 默认返回英文
        return Locale.ENGLISH
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
     * 判断是否为中文用户
     */
    fun isChineseUser(): Boolean {
        val locale = getCurrentLocale()
        val isChineseLocale = locale.language == "zh" || locale.country == "CN"
        println("LocaleDetector: isChineseUser = $isChineseLocale (locale: $locale)")
        return isChineseLocale
    }
}