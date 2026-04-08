package com.camhub

import android.content.Context

/**
 * Unified preferences: wraps both DVRConfig and Klipper/Moonraker settings.
 * Klipper settings are stored here; DVR settings delegate to DVRConfig.
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("camhub_prefs", Context.MODE_PRIVATE)

    // --- Klipper / Moonraker ---

    var klipperIp: String
        get() = prefs.getString(KEY_KLIPPER_IP, DEFAULT_KLIPPER_IP) ?: DEFAULT_KLIPPER_IP
        set(value) = prefs.edit().putString(KEY_KLIPPER_IP, value.trim()).apply()

    var moonrakerPort: Int
        get() = prefs.getInt(KEY_MOONRAKER_PORT, DEFAULT_MOONRAKER_PORT)
        set(value) = prefs.edit().putInt(KEY_MOONRAKER_PORT, value).apply()

    val moonrakerUrl: String
        get() = "http://$klipperIp:$moonrakerPort"

    var cameraAutoDetect: Boolean
        get() = prefs.getBoolean(KEY_CAM_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_CAM_AUTO, value).apply()

    var cameraCustomUrl: String
        get() = prefs.getString(KEY_CAM_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CAM_URL, value.trim()).apply()

    /** Returns the ordered list of MJPEG URLs to try for the Klipper camera. */
    fun getCameraUrls(): List<String> {
        return if (cameraAutoDetect) {
            listOf(
                "http://$klipperIp/webcam/?action=stream",
                "http://$klipperIp:8080/?action=stream",
                "http://$klipperIp:1984/api/stream.mjpeg?src=0",
            )
        } else {
            val url = cameraCustomUrl
            if (url.isNotEmpty()) listOf(url) else emptyList()
        }
    }

    companion object {
        const val DEFAULT_KLIPPER_IP = "192.168.1.115"
        const val DEFAULT_MOONRAKER_PORT = 7125

        private const val KEY_KLIPPER_IP = "klipper_ip"
        private const val KEY_MOONRAKER_PORT = "moonraker_port"
        private const val KEY_CAM_AUTO = "camera_auto_detect"
        private const val KEY_CAM_URL = "camera_url"
    }
}
