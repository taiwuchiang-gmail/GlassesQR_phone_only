package com.example.glassesqr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DAT + MockDeviceKit QR activity.
 *
 * Fix for black preview / no frames:
 * - MockDeviceKit is enabled in GlassesApp BEFORE Wearables.initialize().
 * - After the Android CAMERA permission is granted (required for
 *   setCameraFeed(BACK) which reads the handset camera), we force the mock
 *   device to powerOn + don + setCameraFeed(CameraFacing.BACK).
 * - Then we create a DeviceSession, wait for STARTED, addCamera(), and
 *   collect videoStream. Every frame increments [framesReceived] which is
 *   shown in the debug TextView (txtStatus), proving frames flow.
 * - Each frame is rendered into imgPreview; the most recent QR detection is
 *   drawn on top as a green bounding box.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var txtResult: TextView
    private lateinit var txtStatus: TextView
    private lateinit var imgPreview: ImageView

    @Volatile private var framesReceived = 0
    @Volatile private var lastFrameAt = 0L
    private var mockGlasses: MockGlasses? = null

    private val qrInFlight = AtomicBoolean(false)
    @Volatile private var latestNv21: ByteArray? = null
    @Volatile private var latestWidth = 0
    @Volatile private var latestHeight = 0
    @Volatile private var latestFrameSeq = 0L
    @Volatile private var scannedFrameSeq = 0L
    @Volatile private var lastQrCorners: FloatArray? = null
    @Volatile private var lastQrBoxAt = 0L
    private var lastQrValue: String? = null
    private val qrExecutor = Executors.newSingleThreadExecutor()

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val scanner = BarcodeScanning.getClient(
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .setExecutor(qrExecutor)
            .build()
    )

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updateStatus("Camera permission granted — configuring mock…")
                forceMockReadyAndStream()
            } else {
                updateStatus("Camera permission DENIED — BACK feed needs it. Grant + restart.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtResult = findViewById(R.id.txtResult)
        txtStatus = findViewById(R.id.txtStatus)
        imgPreview = findViewById(R.id.imgPreview)
        updateStatus("DAT init… checking camera permission")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            forceMockReadyAndStream()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        qrExecutor.shutdown()
        scanner.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Android revokes the handset camera while the app isn't visible (screen
        // off / locked), which silently kills the mock BACK feed. Reopen it.
        val glasses = mockGlasses ?: return
        if (framesReceived == 0 || SystemClock.elapsedRealtime() - lastFrameAt < FEED_STALL_MS) return
        try {
            glasses.services.camera.setCameraFeed(CameraFacing.BACK)
            updateStatus("Camera feed stalled — reopened BACK feed. Frames: $framesReceived")
        } catch (e: Exception) {
            updateStatus("Reopen BACK feed failed: ${e.message}")
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { txtStatus.text = msg }
        Log.d("GlassesQR", msg)
    }

    private fun forceMockReadyAndStream() {
        // Must run after permission granted: setCameraFeed(BACK) reads the
        // handset camera and MockDeviceKit rejects stream start without it.
        try {
            val mockKit = MockDeviceKit.getInstance(applicationContext)
            try {
                mockKit.enable()
            } catch (_: Exception) {
                // Already enabled (GlassesApp) — continue.
            }

            val paired = try {
                mockKit.pairedDevices
            } catch (_: Exception) {
                emptyList()
            }
            val existing = paired.filterIsInstance<MockGlasses>().firstOrNull()

            if (existing != null) {
                applyMockState(existing)
                updateStatus("Mock reused — powerOn/don/BACK set. Frames: $framesReceived")
                startDatSession()
            } else {
                mockKit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { glasses ->
                        applyMockState(glasses)
                        updateStatus("Mock paired + powerOn/don/BACK. Starting session…")
                        startDatSession()
                    },
                    onFailure = { error, _ ->
                        updateStatus("pairGlasses failed: ${error.description}")
                    }
                )
            }
        } catch (e: Exception) {
            updateStatus("Mock setup exception: ${e.message}")
        }
    }

    private fun applyMockState(glasses: MockGlasses) {
        mockGlasses = glasses
        // Order matters per docs: powered on + worn (donned) before streaming.
        try {
            glasses.powerOn()
        } catch (e: Exception) {
            Log.w("GlassesQR", "powerOn failed: ${e.message}")
        }
        try {
            glasses.don()
        } catch (e: Exception) {
            Log.w("GlassesQR", "don failed: ${e.message}")
        }
        try {
            glasses.services.camera.setCameraFeed(CameraFacing.BACK)
            Log.d("GlassesQR", "setCameraFeed(BACK) applied")
        } catch (e: Exception) {
            updateStatus("setCameraFeed(BACK) failed: ${e.message}")
        }
    }

    private fun startDatSession() {
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { session ->
                lifecycleScope.launch {
                    session.errors.collect { error ->
                        updateStatus("Session error: ${error.description} | Frames: $framesReceived")
                    }
                }
                lifecycleScope.launch {
                    session.state.collect { state ->
                        updateStatus("Session: $state | Frames: $framesReceived")
                        if (state == DeviceSessionState.STARTED) {
                            session.addCamera(
                                StreamConfiguration(
                                    videoQuality = VideoQuality.MEDIUM,
                                    frameRate = 30
                                )
                            ).fold(
                                onSuccess = { camera ->
                                    val stream = camera.stream
                                    // Count frames -> proves mock feed flows (black-preview fix).
                                    // Off the main thread: YUV conversion + JPEG round trip per frame.
                                    lifecycleScope.launch(Dispatchers.Default) {
                                        stream.videoStream.collect { frame ->
                                            framesReceived++
                                            lastFrameAt = SystemClock.elapsedRealtime()
                                            if (framesReceived % 10 == 0 || framesReceived < 5) {
                                                updateStatus(
                                                    "Streaming… Frames: $framesReceived " +
                                                        "${frame.width}x${frame.height}"
                                                )
                                            }
                                            // Safe ByteBuffer -> ByteArray copy (direct buffers
                                            // throw on .array()).
                                            val bytes = try {
                                                val dup = frame.buffer.duplicate()
                                                val arr = ByteArray(dup.remaining())
                                                dup.get(arr)
                                                arr
                                            } catch (_: Exception) {
                                                null
                                            }
                                            val nv21 = bytes?.let {
                                                i420ToNv21(it, frame.width, frame.height)
                                            }
                                            if (nv21 != null) {
                                                latestNv21 = nv21
                                                latestWidth = frame.width
                                                latestHeight = frame.height
                                                latestFrameSeq++
                                                tryQrOnLatest()
                                                renderPreview(nv21, frame.width, frame.height)
                                            }
                                        }
                                    }
                                    lifecycleScope.launch {
                                        stream.state.collect { s ->
                                            updateStatus("Stream: $s | Frames: $framesReceived")
                                        }
                                    }
                                    stream.start().onFailure { error, _ ->
                                        updateStatus(
                                            "stream.start failed: ${error.description} " +
                                                "| Frames: $framesReceived"
                                        )
                                    }
                                },
                                onFailure = { error, _ ->
                                    updateStatus("addCamera failed: ${error.description}")
                                }
                            )
                        }
                    }
                }
                // Fire-and-forget per DAT conventions.
                session.start()
            },
            onFailure = { error, _ ->
                updateStatus("createSession failed: ${error.description}")
            }
        )
    }

    /**
     * Scan the newest unread frame as soon as the previous ML Kit job
     * finishes, instead of waiting for the next camera callback. A half-size
     * copy keeps each decode short so the green outline can track motion.
     */
    private fun tryQrOnLatest() {
        if (latestFrameSeq == scannedFrameSeq) return
        if (!qrInFlight.compareAndSet(false, true)) return
        val seq = latestFrameSeq
        val nv21 = latestNv21
        val width = latestWidth
        val height = latestHeight
        if (nv21 == null || width <= 0 || height <= 0 || seq == scannedFrameSeq) {
            qrInFlight.set(false)
            return
        }
        scannedFrameSeq = seq
        try {
            val scaled = downsampleNv21(nv21, width, height, QR_DOWNSCALE)
            val image = InputImage.fromByteBuffer(
                java.nio.ByteBuffer.wrap(scaled.bytes),
                scaled.width,
                scaled.height,
                0,
                InputImage.IMAGE_FORMAT_NV21
            )
            val scaleX = width.toFloat() / scaled.width
            val scaleY = height.toFloat() / scaled.height
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull { !it.cornerPoints.isNullOrEmpty() }
                    if (qr != null) {
                        lastQrCorners = qr.cornerPoints?.let { points ->
                            FloatArray(points.size * 2) { i ->
                                if (i % 2 == 0) points[i / 2].x * scaleX
                                else points[i / 2].y * scaleY
                            }
                        }
                        lastQrBoxAt = SystemClock.elapsedRealtime()
                        if (qr.rawValue != lastQrValue) {
                            lastQrValue = qr.rawValue
                            txtResult.text = "QR: ${qr.rawValue}"
                            updateStatus("FOUND QR! Frames: $framesReceived")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.d("GlassesQR", "QR decode miss (frames=$framesReceived): ${e.message}")
                }
                .addOnCompleteListener {
                    qrInFlight.set(false)
                    tryQrOnLatest()
                }
        } catch (e: Exception) {
            qrInFlight.set(false)
            Log.d("GlassesQR", "QR convert failed: ${e.message}")
        }
    }

    private fun renderPreview(nv21: ByteArray, width: Int, height: Int) {
        val jpeg = ByteArrayOutputStream()
        try {
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 80, jpeg)
        } catch (e: Exception) {
            Log.d("GlassesQR", "Preview encode failed: ${e.message}")
            return
        }
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg.toByteArray(), 0, jpeg.size(),
            BitmapFactory.Options().apply { inMutable = true }
        ) ?: return

        // Detection runs slower than the stream, so hold the last box briefly
        // instead of letting it flicker between decodes.
        val corners = lastQrCorners
        if (corners != null &&
            corners.size >= 6 &&
            SystemClock.elapsedRealtime() - lastQrBoxAt < BOX_HOLD_MS
        ) {
            boxPaint.strokeWidth = maxOf(4f, width / 100f)
            val outline = Path().apply {
                moveTo(corners[0], corners[1])
                var i = 2
                while (i + 1 < corners.size) {
                    lineTo(corners[i], corners[i + 1])
                    i += 2
                }
                close()
            }
            Canvas(bitmap).drawPath(outline, boxPaint)
        }
        runOnUiThread { imgPreview.setImageBitmap(bitmap) }
    }

    /** DAT raw frames are I420 (Y, U, V planes); ML Kit and YuvImage want NV21 (Y, interleaved VU). */
    private fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray? {
        val ySize = width * height
        val chromaSize = ySize / 4
        if (width <= 0 || height <= 0 || i420.size < ySize + 2 * chromaSize) return null
        val nv21 = ByteArray(ySize + 2 * chromaSize)
        System.arraycopy(i420, 0, nv21, 0, ySize)
        val uStart = ySize
        val vStart = ySize + chromaSize
        for (i in 0 until chromaSize) {
            nv21[ySize + 2 * i] = i420[vStart + i]
            nv21[ySize + 2 * i + 1] = i420[uStart + i]
        }
        return nv21
    }

    private data class Nv21Frame(val bytes: ByteArray, val width: Int, val height: Int)

    private fun downsampleNv21(
        src: ByteArray,
        width: Int,
        height: Int,
        factor: Int,
    ): Nv21Frame {
        if (factor <= 1) return Nv21Frame(src, width, height)
        val outW = (width / factor) and 1.inv()
        val outH = (height / factor) and 1.inv()
        if (outW < 16 || outH < 16) return Nv21Frame(src, width, height)
        val dst = ByteArray(outW * outH * 3 / 2)
        var di = 0
        var y = 0
        while (y < outH) {
            val srcRow = y * factor * width
            var x = 0
            while (x < outW) {
                dst[di++] = src[srcRow + x * factor]
                x++
            }
            y++
        }
        val srcYSize = width * height
        var cy = 0
        while (cy < outH / 2) {
            val srcRow = srcYSize + cy * factor * width
            var cx = 0
            while (cx < outW / 2) {
                val srcX = cx * factor * 2
                dst[di++] = src[srcRow + srcX]
                dst[di++] = src[srcRow + srcX + 1]
                cx++
            }
            cy++
        }
        return Nv21Frame(dst, outW, outH)
    }

    private companion object {
        const val BOX_HOLD_MS = 250L
        const val FEED_STALL_MS = 1000L
        const val QR_DOWNSCALE = 2
    }
}
