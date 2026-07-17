package com.rishabh.gyrocapture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class HighSpeedActivity : ComponentActivity() {

    private val camId = "0"
    private val width = 1920
    private val height = 1080
    private val fps = 240

    private lateinit var texture: TextureView
    private lateinit var btn: Button
    private var camera: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var videoUri: Uri? = null
    private var isRecording = false
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) maybeOpen() else toast("Camera permission required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        texture = TextureView(this)
        btn = Button(this).apply {
            text = "REC 1080p @ 240fps"
            setBackgroundColor(0xFFFF3B30.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { if (isRecording) stopRec() else startRec() }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM; setMargins(40, 0, 40, 60) }
        root.addView(texture)
        root.addView(btn, lp)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) maybeOpen()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onPause() {
        super.onPause()
        try { if (isRecording) stopRec() } catch (_: Exception) {}
        session?.close(); session = null
        camera?.close(); camera = null
        bgThread.quitSafely()
    }

    private fun maybeOpen() {
        if (texture.isAvailable) openCamera()
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        cm.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                camera = dev
                prepareSession()
            }
            override fun onDisconnected(dev: CameraDevice) { dev.close(); camera = null }
            override fun onError(dev: CameraDevice, e: Int) {
                dev.close(); camera = null
                runOnUiThread { toast("Camera error $e") }
            }
        }, bgHandler)
    }

    private fun newRecorder(): MediaRecorder {
        val name = "hs240_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GyroCapture")
        }
        videoUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )
        val pfd = contentResolver.openFileDescriptor(videoUri!!, "rw")!!
        @Suppress("DEPRECATION")
        return MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncodingBitRate(50_000_000)
            setVideoFrameRate(fps)
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(90)
            prepare()
        }
    }

    private fun prepareSession() {
        val dev = camera ?: return
        try {
            recorder = newRecorder()
            val st = texture.surfaceTexture!!
            st.setDefaultBufferSize(width, height)
            val previewSurface = Surface(st)
            val recSurface = recorder!!.surface

            @Suppress("DEPRECATION")
            dev.createConstrainedHighSpeedCaptureSession(
                listOf(previewSurface, recSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s as CameraConstrainedHighSpeedCaptureSession
                        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface)
                            addTarget(recSurface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        }
                        val burst = session!!.createHighSpeedRequestList(req.build())
                        session!!.setRepeatingBurst(burst, null, bgHandler)
                        runOnUiThread { toast("High-speed session live: ${width}x$height @ $fps") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runOnUiThread { toast("HIGH-SPEED SESSION REFUSED by HAL") }
                    }
                }, bgHandler
            )
        } catch (e: Exception) {
            runOnUiThread { toast("Setup failed: ${e.message}") }
        }
    }

    private fun startRec() {
        try {
            recorder?.start()
            isRecording = true
            btn.text = "STOP (recording 240fps)"
        } catch (e: Exception) { toast("start failed: ${e.message}") }
    }

    private fun stopRec() {
        try {
            recorder?.stop()
            toast("Saved to Movies/GyroCapture — check gallery FPS!")
        } catch (e: Exception) {
            toast("stop failed: ${e.message}")
        } finally {
            recorder?.release(); recorder = null
            isRecording = false
            btn.text = "REC 1080p @ 240fps"
            session?.close(); session = null
            // re-arm for the next take
            prepareSession()
        }
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
