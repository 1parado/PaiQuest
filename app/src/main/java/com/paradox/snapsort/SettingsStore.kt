package com.paradox.snapsort

import android.content.Context

/**
 * 本地设置。密钥只存本机 SharedPreferences，绝不入库、绝不上传。
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

    companion object {
        const val DEFAULT_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        private const val KEY_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
    }
}
