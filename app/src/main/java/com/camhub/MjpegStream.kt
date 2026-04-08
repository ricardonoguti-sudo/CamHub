package com.camhub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class MjpegStream(private val url: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Continuously reads MJPEG frames. Cancellation stops the loop. */
    suspend fun stream(onFrame: suspend (Bitmap) -> Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    connectAndStream(onFrame)
                } catch (_: Exception) { /* retry */ }
                if (isActive) delay(3000)
            }
        }
    }

    private suspend fun connectAndStream(onFrame: suspend (Bitmap) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) { delay(3000); return }
        val inputStream = response.body?.byteStream() ?: run { delay(3000); return }
        readFrames(inputStream, onFrame)
    }

    /** Detects JPEG frames via SOI (0xFF 0xD8) and EOI (0xFF 0xD9) markers. */
    private suspend fun readFrames(inputStream: InputStream, onFrame: suspend (Bitmap) -> Unit) {
        val buffer = ByteArrayOutputStream(65536)
        var prev = -1
        var inFrame = false

        withContext(Dispatchers.IO) {
            while (isActive) {
                val b = inputStream.read()
                if (b == -1) return@withContext
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        buffer.reset()
                        buffer.write(0xFF)
                        buffer.write(0xD8)
                    }
                } else {
                    buffer.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        val bytes = buffer.toByteArray()
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) { onFrame(bitmap) }
                        }
                        buffer.reset()
                        inFrame = false
                    }
                }
                prev = b
            }
        }
    }
}
