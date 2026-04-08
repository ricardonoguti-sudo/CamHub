package com.camhub

import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages an MJPEG stream for the Klipper camera.
 * Supports re-targeting to a different ImageView when the slot changes.
 *
 * All frame callbacks arrive on the Main thread (MjpegStream dispatches to Main),
 * so updating [targetImageView] on the Main thread is race-condition-free.
 */
class MjpegCameraPlayer(
    val cameraIndex: Int,
    val cameraName: String,
    private val urls: List<String>,
    private val scope: CoroutineScope
) {
    enum class Status { CONNECTING, PLAYING, NOT_FOUND }

    var onStatusChanged: ((Status) -> Unit)? = null

    var lastStatus: Status = Status.CONNECTING
        private set

    /** The ImageView that receives decoded frames. Updated on every slot swap. */
    @Volatile var targetImageView: ImageView? = null
        private set

    private var streamJob: Job? = null
    private var connected = false

    /** Starts streaming and routes frames to [imageView]. */
    fun start(imageView: ImageView) {
        targetImageView = imageView
        startStream()
    }

    /**
     * Re-targets frame delivery to a new ImageView without restarting the stream.
     * The ongoing stream will automatically write to the new view from the next frame.
     */
    fun attachToImageView(imageView: ImageView) {
        targetImageView = imageView
    }

    private fun startStream() {
        streamJob?.cancel()
        connected = false
        emit(Status.CONNECTING)

        streamJob = scope.launch {
            for (url in urls) {
                if (tryStream(url)) return@launch
            }
            emit(Status.NOT_FOUND)
        }
    }

    private suspend fun tryStream(url: String): Boolean {
        return try {
            MjpegStream(url).stream { bitmap ->
                // Runs on Main thread
                if (!connected) {
                    connected = true
                    emit(Status.PLAYING)
                }
                targetImageView?.setImageBitmap(bitmap)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun emit(status: Status) {
        lastStatus = status
        onStatusChanged?.invoke(status)
    }

    /** Restarts the stream (e.g. after the Klipper host came back online). */
    fun restart() {
        startStream()
    }

    fun release() {
        streamJob?.cancel()
        targetImageView = null
    }
}
