package com.rishabh.gyrocapture

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var recordBtn: Button
    private lateinit var stats: TextView
    private lateinit var sensorManager: SensorManager

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var isLogging = false
    private var sessionName = ""
    private var recStartNs = 0L
    private val imuCsv = StringBuilder()
    private var gyroCount = 0

    // rolling Hz measurement
    private val hzWindow = ArrayDeque<Long>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        recordBtn = findViewById(R.id.recordBtn)
        stats = findViewById(R.id.stats)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        recordBtn.setOnClickListener { if (isLogging) stopSession() else startSession() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        super.onPause()
        if (!isLogging) sensorManager.unregisterListener(this)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val selector = QualitySelector.from(
                Quality.UHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
            )
            val recorder = Recorder.Builder().setQualitySelector(selector).build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSession() {
        val vc = videoCapture ?: return
        sessionName = "gyrocap_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

        imuCsv.setLength(0)
        imuCsv.append("t_ns,sensor,x,y,z\n")
        gyroCount = 0

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$sessionName.mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GyroCapture")
        }
        val options = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        activeRecording = vc.output.prepareRecording(this, options)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recStartNs = SystemClock.elapsedRealtimeNanos()
                        isLogging = true
                        recordBtn.text = "STOP  (recording…)"
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Toast.makeText(
                                this, "Video error: ${event.error}", Toast.LENGTH_LONG
                            ).show()
                        } else {
                            saveCsvAndMeta()
                            Toast.makeText(
                                this,
                                "Saved: Movies/GyroCapture + Documents/GyroCapture",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    else -> {}
                }
            }
    }

    private fun stopSession() {
        isLogging = false
        recordBtn.text = "START RECORDING"
        activeRecording?.stop()
        activeRecording = null
    }

    private fun saveCsvAndMeta() {
        writeDocument("$sessionName-imu.csv", "text/csv", imuCsv.toString())
        val meta = """
            {
              "app": "GyroCapture v0.2 (native)",
              "session": "$sessionName",
              "video_file": "Movies/GyroCapture/$sessionName.mp4",
              "rec_start_elapsedRealtimeNanos": $recStartNs,
              "imu_clock": "SensorEvent.timestamp (elapsedRealtimeNanos, same clock as rec start)",
              "gyro_samples": $gyroCount,
              "note": "frame_k_time_ns ~= rec_start + k * (1e9/fps). Subtract rec_start from t_ns for time since video start."
            }
        """.trimIndent()
        writeDocument("$sessionName-meta.json", "application/json", meta)
    }

    private fun writeDocument(name: String, mime: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/GyroCapture")
        }
        val uri = contentResolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val tag = when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> "G"
            Sensor.TYPE_ACCELEROMETER -> "A"
            else -> return
        }
        if (tag == "G") {
            gyroCount++
            val now = SystemClock.elapsedRealtime()
            hzWindow.addLast(now)
            while (hzWindow.isNotEmpty() && now - hzWindow.first() > 1000) hzWindow.removeFirst()
            if (gyroCount % 25 == 0) {
                stats.text = "GYRO ${hzWindow.size} Hz   " +
                    "x=%+.2f y=%+.2f z=%+.2f rad/s   %s".format(
                        e.values[0], e.values[1], e.values[2],
                        if (isLogging) "LOGGING ($gyroCount)" else "idle"
                    )
            }
        }
        if (isLogging) {
            imuCsv.append(e.timestamp).append(',').append(tag).append(',')
                .append(e.values[0]).append(',')
                .append(e.values[1]).append(',')
                .append(e.values[2]).append('\n')
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
