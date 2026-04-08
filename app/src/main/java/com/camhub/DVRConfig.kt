package com.camhub

import android.content.Context

/**
 * Persists Hikvision DVR connection settings in SharedPreferences.
 */
class DVRConfig private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) { prefs.edit().putString(KEY_HOST, value).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) { prefs.edit().putInt(KEY_PORT, value).apply() }

    var user: String
        get() = prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER
        set(value) { prefs.edit().putString(KEY_USER, value).apply() }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(value) { prefs.edit().putString(KEY_PASSWORD, value).apply() }

    var useSubstream: Boolean
        get() = prefs.getBoolean(KEY_SUBSTREAM, false)
        set(value) { prefs.edit().putBoolean(KEY_SUBSTREAM, value).apply() }

    /**
     * Maps camera index (1–4) to Hikvision channel number.
     * Camera 4 uses special numbering: 1701/1702 instead of 401/402.
     */
    fun channel(index: Int, substream: Boolean = useSubstream): Int {
        if (index == 4) return if (substream) 1702 else 1701
        return index * 100 + if (substream) 2 else 1
    }

    fun buildURL(cameraIndex: Int): String {
        val ch = channel(cameraIndex)
        return "rtsp://$user:$password@$host:$port/Streaming/Channels/$ch"
    }

    fun restoreDefaults() {
        host = DEFAULT_HOST
        port = DEFAULT_PORT
        user = DEFAULT_USER
        password = DEFAULT_PASSWORD
        useSubstream = false
    }

    companion object {
        private const val PREFS_NAME = "dvr_config"
        private const val KEY_HOST = "dvr_host"
        private const val KEY_PORT = "dvr_port"
        private const val KEY_USER = "dvr_user"
        private const val KEY_PASSWORD = "dvr_password"
        private const val KEY_SUBSTREAM = "dvr_substream"

        const val DEFAULT_HOST = "192.168.1.2"
        const val DEFAULT_PORT = 554
        const val DEFAULT_USER = "admin"
        const val DEFAULT_PASSWORD = "Rn88018366"

        @Volatile private var INSTANCE: DVRConfig? = null

        fun getInstance(context: Context): DVRConfig =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DVRConfig(context.applicationContext).also { INSTANCE = it }
            }
    }
}
