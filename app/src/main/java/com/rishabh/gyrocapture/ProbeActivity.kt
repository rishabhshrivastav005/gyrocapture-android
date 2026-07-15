package com.rishabh.gyrocapture

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class ProbeActivity : ComponentActivity() {

    private lateinit var text: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runProbe()
            else text.text = "Camera permission is required for the probe."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFE8EDF2.toInt())
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E11.toInt())
            addView(text)
        }
        setContentView(scroll)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) runProbe()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun runProbe() {
        val sb = StringBuilder()
        sb.append("CAMERA CAPABILITY REPORT\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("=".repeat(46)).append('\n')
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager

            // --- concurrent camera support (the key question) ---
            sb.append("\n## CONCURRENT CAMERA SETS\n")
            if (Build.VERSION.SDK_INT >= 30) {
                val sets = cm.concurrentCameraIds
                if (sets.isEmpty()) {
                    sb.append("NONE — OS reports no camera pairs can stream at once\n")
                } else {
                    sets.forEach { sb.append("can run together: $it\n") }
                }
            } else sb.append("API < 30, cannot query\n")

            // --- per-camera details ---
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                sb.append("\n## CAMERA ID $id\n")

                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "BACK"
                    CameraMetadata.LENS_FACING_FRONT -> "FRONT"
                    else -> "EXTERNAL"
                }
                val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString() ?: "?"
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "?"
                }
                sb.append("facing=$facing  focal(mm)=$focals  hwlevel=$level\n")

                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null) {
                    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                        sb.append("LOGICAL MULTI-CAMERA, physical ids: ")
                        sb.append(c.physicalCameraIds.joinToString()).append('\n')
                    }
                    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps) {
                        sb.append("SUPPORTS CONSTRAINED HIGH-SPEED VIDEO\n")
                    }
                }

                val fpsRanges = c.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                )
                sb.append("AE fps ranges: ${fpsRanges?.joinToString() ?: "?"}\n")

                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val hs = map?.highSpeedVideoSizes
                if (hs != null && hs.isNotEmpty()) {
                    sb.append("HIGH-SPEED modes:\n")
                    for (size in hs) {
                        val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                        sb.append("  $size -> ${ranges.joinToString()}\n")
                    }
                } else {
                    sb.append("high-speed modes: none exposed\n")
                }
            }
        } catch (e: Exception) {
            sb.append("\nPROBE ERROR: ${e.message}\n")
        }

        val report = sb.toString()
        text.text = report
        saveReport(report)
    }

    private fun saveReport(report: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME,
                    "camera_report_${System.currentTimeMillis()}.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/GyroCapture")
            }
            val uri = contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            )
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(report.toByteArray())
                }
                Toast.makeText(this,
                    "Report saved to Documents/GyroCapture", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
