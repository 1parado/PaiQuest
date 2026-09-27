package com.paradox.snapsort

import android.content.Context

/**
 * 本地设置：LLM 配置 + 用户自定义分类（标签集合存储，零依赖）。
 */
class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("snapsort_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value).apply()

    /** 用户自建分类的名称列表（去重、按名称排序） */
    fun customCategoryLabels(): List<String> =
        sp.getStringSet(KEY_CUSTOM_CATS, emptySet())?.sorted() ?: emptyList()

    /** 新建分类，重名（忽略大小写）或空名返回 false */
    fun addCustomCategory(label: String): Boolean {
        val l = label.trim()
        if (l.isEmpty()) return false
        val cur = sp.getStringSet(KEY_CUSTOM_CATS, emptySet()) ?: emptySet()
        if (cur.any { it.equals(l, ignoreCase = true) }) return false
        sp.edit().putStringSet(KEY_CUSTOM_CATS, cur + l).apply()
        return true
    }

    companion object {
        const val DEFAULT_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        private const val KEY_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_CUSTOM_CATS = "custom_categories"
    }
}
