package com.camhub

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.camhub.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var dvrConfig: DVRConfig
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dvrConfig = DVRConfig.getInstance(this)
        prefs = AppPreferences(this)

        loadValues()
        setupListeners()
    }

    private fun loadValues() {
        // DVR section
        binding.dvrHostInput.setText(dvrConfig.host)
        binding.dvrPortInput.setText(dvrConfig.port.toString())
        binding.dvrUserInput.setText(dvrConfig.user)
        binding.dvrPasswordInput.setText(dvrConfig.password)
        binding.substreamSwitch.isChecked = dvrConfig.useSubstream
        updateSubstreamDescription()
        updateDvrUrlPreview()

        // Klipper section
        binding.klipperIpInput.setText(prefs.klipperIp)
        binding.klipperPortInput.setText(prefs.moonrakerPort.toString())
        binding.cameraAutoDetectSwitch.isChecked = prefs.cameraAutoDetect
        binding.klipperCameraUrlInput.setText(prefs.cameraCustomUrl)
        updateKlipperCameraSection(prefs.cameraAutoDetect)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        // DVR
        binding.substreamSwitch.setOnCheckedChangeListener { _, _ ->
            updateSubstreamDescription()
            updateDvrUrlPreview()
        }
        binding.restoreDefaultsButton.setOnClickListener { confirmRestoreDefaults() }

        // Klipper
        binding.cameraAutoDetectSwitch.setOnCheckedChangeListener { _, checked ->
            updateKlipperCameraSection(checked)
        }
        binding.klipperIpInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateKlipperCameraSection(binding.cameraAutoDetectSwitch.isChecked)
        }

        binding.saveButton.setOnClickListener { saveAndClose() }
    }

    private fun saveAndClose() {
        // Validate DVR
        val host = binding.dvrHostInput.text?.toString()?.trim() ?: ""
        val portStr = binding.dvrPortInput.text?.toString()?.trim() ?: ""
        val user = binding.dvrUserInput.text?.toString()?.trim() ?: ""
        val password = binding.dvrPasswordInput.text?.toString() ?: ""

        if (host.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val dvrPort = portStr.toIntOrNull() ?: run {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return
        }

        // Validate Klipper
        val klipperIp = binding.klipperIpInput.text?.toString()?.trim() ?: ""
        val klipperPortStr = binding.klipperPortInput.text?.toString()?.trim() ?: ""
        val autoDetect = binding.cameraAutoDetectSwitch.isChecked
        val customUrl = binding.klipperCameraUrlInput.text?.toString()?.trim() ?: ""

        if (klipperIp.isEmpty()) {
            binding.klipperIpLayout.error = getString(R.string.error_enter_klipper_ip)
            return
        }
        binding.klipperIpLayout.error = null

        val klipperPort = klipperPortStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            binding.klipperPortLayout.error = getString(R.string.invalid_port)
            return
        }
        binding.klipperPortLayout.error = null

        if (!autoDetect && customUrl.isEmpty()) {
            binding.klipperCameraUrlLayout.error = getString(R.string.error_enter_camera_url)
            return
        }
        binding.klipperCameraUrlLayout.error = null

        // Persist DVR
        dvrConfig.host = host
        dvrConfig.port = dvrPort
        dvrConfig.user = user
        dvrConfig.password = password
        dvrConfig.useSubstream = binding.substreamSwitch.isChecked

        // Persist Klipper
        prefs.klipperIp = klipperIp
        prefs.moonrakerPort = klipperPort
        prefs.cameraAutoDetect = autoDetect
        prefs.cameraCustomUrl = customUrl

        // Dismiss keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun confirmRestoreDefaults() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_defaults)
            .setMessage(R.string.restore_defaults_confirm)
            .setPositiveButton(R.string.restore) { _, _ ->
                dvrConfig.restoreDefaults()
                loadValues()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSubstreamDescription() {
        binding.substreamDescription.text =
            if (binding.substreamSwitch.isChecked) getString(R.string.substream_desc)
            else getString(R.string.mainstream_desc)
    }

    private fun updateDvrUrlPreview() {
        val sb = StringBuilder()
        (1..4).forEach { i ->
            val url = dvrConfig.buildURL(i)
            val masked = url.replace(dvrConfig.password, "***")
            sb.appendLine("Câmera A$i:")
            sb.appendLine("  $masked")
            if (i < 4) sb.appendLine()
        }
        binding.dvrUrlPreview.text = sb.toString().trimEnd()
    }

    private fun updateKlipperCameraSection(autoDetect: Boolean) {
        if (autoDetect) {
            binding.klipperCameraUrlLayout.visibility = View.GONE
            binding.klipperAutoUrls.visibility = View.VISIBLE
            val ip = binding.klipperIpInput.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: prefs.klipperIp
            binding.klipperAutoUrls.text =
                "URLs tentadas (nesta ordem):\n" +
                "• http://$ip/webcam/?action=stream\n" +
                "• http://$ip:8080/?action=stream\n" +
                "• http://$ip:1984/api/stream.mjpeg?src=0"
        } else {
            binding.klipperCameraUrlLayout.visibility = View.VISIBLE
            binding.klipperAutoUrls.visibility = View.GONE
        }
    }
}
