package com.camhub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camhub.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.roundToInt

/**
 * Main dashboard: 1 large camera (left) + 4 small cameras (right panel).
 * Tap any small camera to swap it into the main view.
 *
 * Camera indices:
 *   0–3  →  RTSP streams from Hikvision DVR (cameras A1–A4)
 *   4    →  MJPEG stream from Klipper printer camera
 *
 * Slot indices:
 *   0    →  large main view
 *   1–4  →  small side panels (top to bottom)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── camera players ────────────────────────────────────────────────────────
    private val rtspPlayers = mutableListOf<RtspCameraPlayer>()   // indices 0–3
    private lateinit var mjpegPlayer: MjpegCameraPlayer            // index 4

    private var moonrakerClient: MoonrakerClient? = null
    private var statsJob: Job? = null

    // ── slot assignment ───────────────────────────────────────────────────────
    // slotToCamera[slotIdx] = cameraIdx
    // slot 0 = main, slots 1–4 = side panels
    private val slotToCamera = intArrayOf(0, 1, 2, 3, 4)

    companion object {
        private const val KLIPPER_INDEX = 4
    }

    // ── view slot data ────────────────────────────────────────────────────────
    private data class ViewSlot(
        val vlcLayout: VLCVideoLayout,
        val imageView: ImageView,
        val statusOverlay: View,
        val label: TextView
    )

    private lateinit var allSlots: List<ViewSlot>

    // ── settings launcher ─────────────────────────────────────────────────────
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) restartAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildSlotList()
        setupClickListeners()

        // Wait for layout before starting VLC surfaces
        binding.root.post { setupCameras() }
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    private fun buildSlotList() {
        allSlots = listOf(
            ViewSlot(binding.mainVlcLayout, binding.mainImageView,
                     binding.mainStatusOverlay, binding.mainCameraLabel),
            ViewSlot(binding.side1VlcLayout, binding.side1ImageView,
                     binding.side1StatusOverlay, binding.side1Label),
            ViewSlot(binding.side2VlcLayout, binding.side2ImageView,
                     binding.side2StatusOverlay, binding.side2Label),
            ViewSlot(binding.side3VlcLayout, binding.side3ImageView,
                     binding.side3StatusOverlay, binding.side3Label),
            ViewSlot(binding.side4VlcLayout, binding.side4ImageView,
                     binding.side4StatusOverlay, binding.side4Label),
        )
    }

    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.btnPower.setOnClickListener { confirmShutdown() }

        // Side slot tap → swap to main
        listOf(binding.sideSlot1, binding.sideSlot2,
               binding.sideSlot3, binding.sideSlot4)
            .forEachIndexed { i, view ->
                view.setOnClickListener { swapToMain(sideSlotIndex = i + 1) }
            }
    }

    private fun setupCameras() {
        val dvrConfig = DVRConfig.getInstance(this)
        val prefs = AppPreferences(this)

        // Create 4 RTSP players
        for (i in 1..4) {
            val player = RtspCameraPlayer(
                context = this,
                cameraIndex = i - 1,
                cameraName = "Câmera A$i",
                url = dvrConfig.buildURL(i)
            )
            rtspPlayers.add(player)
        }

        // Create MJPEG player
        mjpegPlayer = MjpegCameraPlayer(
            cameraIndex = KLIPPER_INDEX,
            cameraName = "Klipper",
            urls = prefs.getCameraUrls(),
            scope = lifecycleScope
        )

        // Moonraker client
        moonrakerClient = MoonrakerClient(prefs.moonrakerUrl)

        // Wire status callbacks (dynamically look up current slot)
        wireStatusCallbacks()

        // Start all players in their initial slots
        for (i in 0..3) {
            val slot = allSlots[i]
            slot.vlcLayout.visibility = View.VISIBLE
            slot.imageView.visibility = View.GONE
            slot.label.text = rtspPlayers[i].cameraName
            rtspPlayers[i].start(slot.vlcLayout)
        }
        val klipperSlot = allSlots[4]
        klipperSlot.vlcLayout.visibility = View.GONE
        klipperSlot.imageView.visibility = View.VISIBLE
        klipperSlot.label.text = mjpegPlayer.cameraName
        mjpegPlayer.start(klipperSlot.imageView)

        // Initial main-slot controls
        updateMainSlotControls(slotToCamera[0])
        updateKlipperSpecificUI()
        startStatsPolling()
    }

    private fun wireStatusCallbacks() {
        for (i in 0..3) {
            val camIdx = i
            rtspPlayers[i].onStatusChanged = { status ->
                val slotIdx = slotToCamera.indexOf(camIdx)
                updateRtspSlotStatus(slotIdx, status)
            }
        }
        mjpegPlayer.onStatusChanged = { status ->
            val slotIdx = slotToCamera.indexOf(KLIPPER_INDEX)
            updateMjpegSlotStatus(slotIdx, status)
        }
    }

    // ── slot swap ─────────────────────────────────────────────────────────────

    /**
     * Swaps the camera in [sideSlotIndex] (1–4) into the main view (slot 0).
     */
    private fun swapToMain(sideSlotIndex: Int) {
        val prevMainCam = slotToCamera[0]
        val newMainCam = slotToCamera[sideSlotIndex]

        if (prevMainCam == newMainCam) return  // already main

        slotToCamera[0] = newMainCam
        slotToCamera[sideSlotIndex] = prevMainCam

        attachCameraToSlot(newMainCam, 0)
        attachCameraToSlot(prevMainCam, sideSlotIndex)

        updateMainSlotControls(newMainCam)
        updateKlipperSpecificUI()
    }

    private fun attachCameraToSlot(camIdx: Int, slotIdx: Int) {
        val slot = allSlots[slotIdx]
        if (camIdx < KLIPPER_INDEX) {
            val player = rtspPlayers[camIdx]
            slot.vlcLayout.visibility = View.VISIBLE
            slot.imageView.visibility = View.GONE
            slot.label.text = player.cameraName
            player.attachToLayout(slot.vlcLayout)
            // Restore last known status in the new slot
            updateRtspSlotStatus(slotIdx, player.lastStatus)
        } else {
            slot.vlcLayout.visibility = View.GONE
            slot.imageView.visibility = View.VISIBLE
            slot.label.text = mjpegPlayer.cameraName
            mjpegPlayer.attachToImageView(slot.imageView)
            updateMjpegSlotStatus(slotIdx, mjpegPlayer.lastStatus)
        }
    }

    // ── status UI helpers ─────────────────────────────────────────────────────

    private fun updateRtspSlotStatus(slotIdx: Int, status: RtspCameraPlayer.Status) {
        val slot = allSlots[slotIdx]
        when (status) {
            RtspCameraPlayer.Status.PLAYING -> {
                slot.statusOverlay.visibility = View.GONE
            }
            else -> {
                slot.statusOverlay.visibility = View.VISIBLE
                if (slotIdx == 0) {
                    binding.mainStatusText.text = when (status) {
                        RtspCameraPlayer.Status.CONNECTING -> getString(R.string.status_connecting)
                        RtspCameraPlayer.Status.BUFFERING  -> getString(R.string.status_buffering)
                        RtspCameraPlayer.Status.ERROR      -> getString(R.string.status_error)
                        RtspCameraPlayer.Status.STOPPED    -> getString(R.string.status_stopped)
                        else -> ""
                    }
                    binding.mainProgressSpinner.visibility =
                        if (status == RtspCameraPlayer.Status.ERROR ||
                            status == RtspCameraPlayer.Status.STOPPED) View.GONE
                        else View.VISIBLE
                }
            }
        }
    }

    private fun updateMjpegSlotStatus(slotIdx: Int, status: MjpegCameraPlayer.Status) {
        val slot = allSlots[slotIdx]
        when (status) {
            MjpegCameraPlayer.Status.PLAYING -> {
                slot.statusOverlay.visibility = View.GONE
            }
            MjpegCameraPlayer.Status.CONNECTING -> {
                slot.statusOverlay.visibility = View.VISIBLE
                if (slotIdx == 0) {
                    binding.mainStatusText.text = getString(R.string.status_connecting)
                    binding.mainProgressSpinner.visibility = View.VISIBLE
                }
            }
            MjpegCameraPlayer.Status.NOT_FOUND -> {
                slot.statusOverlay.visibility = View.VISIBLE
                if (slotIdx == 0) {
                    binding.mainStatusText.text = getString(R.string.status_camera_not_found)
                    binding.mainProgressSpinner.visibility = View.GONE
                }
            }
        }
    }

    // ── main-slot control bar ─────────────────────────────────────────────────

    private fun updateMainSlotControls(camIdx: Int) {
        if (camIdx < KLIPPER_INDEX) {
            val player = rtspPlayers[camIdx]
            binding.mainControlsBar.visibility = View.VISIBLE
            updateMuteIcon(player.isMuted)
            binding.mainMuteButton.setOnClickListener {
                updateMuteIcon(player.toggleMute())
            }
            binding.mainReloadButton.setOnClickListener {
                player.reload()
            }
        } else {
            binding.mainControlsBar.visibility = View.GONE
        }
    }

    private fun updateMuteIcon(muted: Boolean) {
        binding.mainMuteButton.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    // ── Klipper-specific UI ───────────────────────────────────────────────────

    private fun updateKlipperSpecificUI() {
        val klipperIsMain = slotToCamera[0] == KLIPPER_INDEX
        binding.btnPower.visibility = if (klipperIsMain) View.VISIBLE else View.GONE
        if (!klipperIsMain) {
            binding.klipperStatsBar.visibility = View.GONE
            binding.klipperProgressBar.visibility = View.GONE
        }
    }

    // ── Klipper stats polling ─────────────────────────────────────────────────

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (true) {
                val client = moonrakerClient ?: break
                val info = withContext(Dispatchers.IO) { client.getPrintInfo() }
                // Only update UI if Klipper is in the main slot
                if (slotToCamera[0] == KLIPPER_INDEX) updateStatsUI(info)
                delay(3000)
            }
        }
    }

    private fun updateStatsUI(info: PrintInfo?) {
        if (info == null || info.state == "standby" || info.filename.isEmpty()) {
            binding.klipperStatsBar.visibility = View.GONE
            binding.klipperProgressBar.visibility = View.GONE
            return
        }

        binding.klipperStatsBar.visibility = View.VISIBLE
        binding.klipperProgressBar.visibility = View.VISIBLE

        val pct = (info.progress * 100).roundToInt()
        binding.klipperProgressText.text = "$pct%"
        binding.klipperProgressBar.progress = (info.progress * 1000).roundToInt()
        binding.klipperElapsedText.text = formatTime(info.printDuration)
        binding.klipperSlicerText.text =
            if (info.slicerTime != null && info.slicerTime > 0) formatTime(info.slicerTime)
            else "--:--"
        binding.klipperRemainingText.text =
            if (info.estimatedRemaining > 0) formatTime(info.estimatedRemaining) else "--:--"
        binding.klipperFilenameText.text = info.filename.substringAfterLast("/")

        binding.klipperProgressText.setTextColor(
            when (info.state) {
                "paused"   -> 0xFFFFB74D.toInt()
                "error"    -> 0xFFEF5350.toInt()
                "complete" -> 0xFF81C784.toInt()
                else       -> 0xFFFFFFFF.toInt()
            }
        )
    }

    // ── Klipper shutdown ──────────────────────────────────────────────────────

    private fun confirmShutdown() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shutdown_title)
            .setMessage(R.string.shutdown_message)
            .setPositiveButton(R.string.shutdown_confirm) { _, _ -> sendShutdown() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendShutdown() {
        val client = moonrakerClient ?: return
        binding.btnPower.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { client.shutdown() }
            if (ok) {
                binding.mainStatusText.text = getString(R.string.status_shutting_down)
                binding.mainStatusOverlay.visibility = View.VISIBLE
                binding.klipperStatsBar.visibility = View.GONE
            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    R.string.shutdown_failed,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                binding.btnPower.isEnabled = true
            }
        }
    }

    // ── restart after settings change ─────────────────────────────────────────

    private fun restartAll() {
        statsJob?.cancel()
        rtspPlayers.forEach { it.release() }
        rtspPlayers.clear()
        mjpegPlayer.release()

        // Reset to default slot assignments
        for (i in 0..4) slotToCamera[i] = i

        // Reset all slot views
        allSlots.forEach { slot ->
            slot.statusOverlay.visibility = View.VISIBLE
            slot.vlcLayout.visibility = View.VISIBLE
            slot.imageView.visibility = View.GONE
        }

        binding.root.post { setupCameras() }
    }

    // ── system UI ────────────────────────────────────────────────────────────

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        statsJob?.cancel()
        rtspPlayers.forEach { it.release() }
        if (::mjpegPlayer.isInitialized) mjpegPlayer.release()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun formatTime(seconds: Double): String {
        val t = seconds.toLong()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m"
        else "${m}m ${s.toString().padStart(2, '0')}s"
    }
}
