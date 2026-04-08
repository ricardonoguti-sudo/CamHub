package com.camhub

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PrintInfo(
    val state: String,           // printing, standby, paused, complete, error
    val filename: String,
    val progress: Double,        // 0.0 – 1.0
    val printDuration: Double,   // seconds elapsed
    val slicerTime: Double?,     // slicer estimated total (null if unknown)
    val estimatedRemaining: Double
)

class MoonrakerClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun getPrintInfo(): PrintInfo? {
        return try {
            val statsJson = fetchJson(
                "$baseUrl/printer/objects/query?print_stats&display_status&virtual_sdcard"
            ) ?: return null

            val status = statsJson
                .getAsJsonObject("result")
                ?.getAsJsonObject("status") ?: return null

            val printStats = status.getAsJsonObject("print_stats") ?: return null
            val displayStatus = status.getAsJsonObject("display_status")
            val virtualSdcard = status.getAsJsonObject("virtual_sdcard")

            val state = printStats.get("state")?.asString ?: "standby"
            val filename = printStats.get("filename")?.asString ?: ""
            val printDuration = printStats.get("print_duration")?.asDouble ?: 0.0

            val progress = displayStatus?.get("progress")?.asDouble
                ?: virtualSdcard?.get("progress")?.asDouble
                ?: 0.0

            val slicerTime = if (filename.isNotEmpty()) fetchSlicerTime(filename) else null
            val estimatedRemaining = calculateRemaining(progress, printDuration, slicerTime)

            PrintInfo(state, filename, progress, printDuration, slicerTime, estimatedRemaining)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSlicerTime(filename: String): Double? {
        return try {
            val encoded = java.net.URLEncoder.encode(filename, "UTF-8")
            val json = fetchJson("$baseUrl/server/files/metadata?filename=$encoded") ?: return null
            json.getAsJsonObject("result")?.get("estimated_time")?.asDouble
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateRemaining(
        progress: Double,
        printDuration: Double,
        slicerTime: Double?
    ): Double = when {
        slicerTime != null && slicerTime > 0 ->
            (slicerTime - slicerTime * progress).coerceAtLeast(0.0)
        progress > 0.01 ->
            (printDuration / progress - printDuration).coerceAtLeast(0.0)
        else -> 0.0
    }

    /** POST /machine/shutdown — returns true on success. */
    fun shutdown(): Boolean {
        return try {
            val body = "".toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseUrl/machine/shutdown").post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchJson(url: String): JsonObject? {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        return gson.fromJson(body, JsonObject::class.java)
    }
}
