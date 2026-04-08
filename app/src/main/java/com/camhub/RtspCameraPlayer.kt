package com.camhub

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Manages a single LibVLC player for one RTSP camera stream.
 * Supports re-attaching to a different VLCVideoLayout (used when swapping slots).
 */
class RtspCameraPlayer(
    private val context: Context,
    val cameraIndex: Int,
    val cameraName: String,
    private val url: String
) {
    enum class Status { CONNECTING, BUFFERING, PLAYING, ERROR, STOPPED }

    var onStatusChanged: ((Status) -> Unit)? = null

    var isMuted = true
        private set

    var lastStatus: Status = Status.STOPPED
        private set

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { if (libVLC != null) loadMedia() }

    private val eventListener = MediaPlayer.EventListener { event ->
        handler.post {
            when (event.type) {
                MediaPlayer.Event.Opening -> emit(Status.CONNECTING)
                MediaPlayer.Event.Buffering -> {
                    if (!isPlaying && event.buffering < 100f) emit(Status.BUFFERING)
                }
                MediaPlayer.Event.Playing -> {
                    handler.removeCallbacks(reconnectRunnable)
                    isPlaying = true
                    emit(Status.PLAYING)
                }
                MediaPlayer.Event.Paused,
                MediaPlayer.Event.Stopped -> {
                    isPlaying = false
                    emit(Status.STOPPED)
                }
                MediaPlayer.Event.EndReached,
                MediaPlayer.Event.EncounteredError -> {
                    isPlaying = false
                    emit(Status.ERROR)
                    scheduleReconnect()
                }
            }
        }
    }

    private fun emit(status: Status) {
        lastStatus = status
        onStatusChanged?.invoke(status)
    }

    /** Initialises LibVLC and starts streaming to the given layout. */
    fun start(videoLayout: VLCVideoLayout) {
        val options = ArrayList<String>().apply {
            add("--network-caching=1000")
            add("--rtsp-tcp")
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--clock-jitter=0")
            add("--clock-synchro=0")
        }
        libVLC = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVLC!!).apply {
            setEventListener(eventListener)
            attachViews(videoLayout, null, false, true)
        }
        loadMedia()
    }

    /**
     * Moves the player to a different VLCVideoLayout.
     * Called when this camera is swapped to a different slot.
     */
    fun attachToLayout(videoLayout: VLCVideoLayout) {
        mediaPlayer?.detachViews()
        mediaPlayer?.attachViews(videoLayout, null, false, true)
    }

    private fun loadMedia() {
        handler.removeCallbacks(reconnectRunnable)
        isPlaying = false
        emit(Status.CONNECTING)
        val media = Media(libVLC!!, Uri.parse(url)).apply {
            addOption(":network-caching=1000")
            addOption(":rtsp-tcp")
        }
        mediaPlayer?.media = media
        media.release()
        mediaPlayer?.volume = if (isMuted) 0 else 100
        mediaPlayer?.play()
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 5000)
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        mediaPlayer?.volume = if (isMuted) 0 else 100
        return isMuted
    }

    fun reload() = loadMedia()

    fun release() {
        handler.removeCallbacks(reconnectRunnable)
        mediaPlayer?.stop()
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }
}
