package com.tamdecorators.scanner.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.tamdecorators.scanner.databinding.ActivityScanCameraBinding
import com.tamdecorators.scanner.util.ScanProcessor
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live camera screen. Each preview frame is analysed for a document outline,
 * which is drawn on top of the preview. When the outline is big enough and
 * stays still for a few frames, the photo is taken automatically.
 */
class ScanCameraActivity : ComponentActivity() {
    private lateinit var binding: ActivityScanCameraBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null

    private var lastViewQuad: FloatArray? = null

    // Written on the analysis thread, read on the main thread when capturing.
    @Volatile private var lastUprightQuad: FloatArray? = null
    @Volatile private var lastUprightW: Int = 0
    @Volatile private var lastUprightH: Int = 0

    private var stableCount = 0
    private var isCapturing = false
    private var lastCaptureAt = 0L

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.close.setOnClickListener { finish() }
        binding.captureFab.setOnClickListener { capture() }

        requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // Same 16:9 shape as the analysis frame, so corners found there line up with the photo.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
            imageCapture = capture

            // Analyse a small frame for speed; if analysis falls behind, skip old frames.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_RESOLUTION,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on [cameraExecutor] for every frame. */
    private fun analyze(image: ImageProxy) {
        try {
            val gray = luminanceToMat(image)

            val upright = Mat()
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(gray, upright, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(gray, upright, Core.ROTATE_180)
                270 -> Core.rotate(gray, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> gray.copyTo(upright)
            }

            val quad = ScanProcessor.detectLargestQuad(upright)
            lastUprightQuad = quad
            lastUprightW = upright.cols()
            lastUprightH = upright.rows()

            if (quad != null) {
                val viewQuad = mapImageQuadToPreviewView(
                    imgW = upright.cols(),
                    imgH = upright.rows(),
                    viewW = binding.previewView.width,
                    viewH = binding.previewView.height,
                    quad = quad,
                )
                runOnUiThread {
                    binding.overlay.updateQuad(viewQuad)
                    maybeAutoCapture(viewQuad)
                }
            } else {
                runOnUiThread {
                    stableCount = 0
                    binding.overlay.updateQuad(null)
                }
            }

            upright.release()
            gray.release()
        } catch (e: Exception) {
            // One bad frame shouldn't stop the camera; log it and wait for the next one.
            Log.w(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    /**
     * Copies the brightness (Y) plane of a camera frame into a greyscale Mat.
     *
     * Each row in the camera buffer can be longer than the image width
     * (rowStride > width) because of memory padding. Copying the buffer in one
     * go would shift every row and scramble the image, so rows are copied one
     * at a time and the padding is skipped.
     */
    private fun luminanceToMat(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        val pixels = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(pixels, 0, pixels.size)
        } else {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(pixels, row * width, width)
            }
        }

        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, pixels)
        return mat
    }

    /** Takes the photo once the outline has been big and steady for [STABLE_FRAMES_TO_CAPTURE] frames. */
    private fun maybeAutoCapture(viewQuad: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (isCapturing) return
        if (now - lastCaptureAt < CAPTURE_COOLDOWN_MS) return

        val area = polygonArea(viewQuad)
        val viewArea = binding.previewView.width.toFloat() * binding.previewView.height.toFloat()
        if (area < viewArea * MIN_DOCUMENT_AREA_FRACTION) {
            stableCount = 0
            lastViewQuad = viewQuad
            return
        }

        val prev = lastViewQuad
        if (prev != null && meanCornerDistance(prev, viewQuad) < STABLE_CORNER_MOVEMENT_PX) {
            stableCount++
        } else {
            stableCount = 0
        }
        lastViewQuad = viewQuad

        if (stableCount >= STABLE_FRAMES_TO_CAPTURE) capture()
    }

    private fun capture() {
        val capture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true
        stableCount = 0

        // Snapshot the detected corners now, so they match the moment the button was pressed.
        val quad = lastUprightQuad
        val quadW = lastUprightW
        val quadH = lastUprightH

        val outFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(outFile).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lastCaptureAt = SystemClock.elapsedRealtime()
                    val i = Intent(this@ScanCameraActivity, EditScanActivity::class.java).apply {
                        putExtra(EditScanActivity.EXTRA_IMAGE_PATH, outFile.absolutePath)
                        putExtra(EditScanActivity.EXTRA_UPRIGHT_QUAD, quad)
                        putExtra(EditScanActivity.EXTRA_UPRIGHT_W, quadW)
                        putExtra(EditScanActivity.EXTRA_UPRIGHT_H, quadH)
                    }
                    startActivity(i)
                    isCapturing = false
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    isCapturing = false
                }
            },
        )
    }

    /**
     * Converts corner positions from the analysed frame to screen positions.
     * The preview fills the screen and is centre-cropped, so scale by the
     * larger ratio and offset by the part that is cut off.
     */
    private fun mapImageQuadToPreviewView(
        imgW: Int,
        imgH: Int,
        viewW: Int,
        viewH: Int,
        quad: FloatArray,
    ): FloatArray {
        val scale = max(viewW.toFloat() / imgW.toFloat(), viewH.toFloat() / imgH.toFloat())
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f
        return FloatArray(quad.size) { i -> if (i % 2 == 0) quad[i] * scale + dx else quad[i] * scale + dy }
    }

    private fun meanCornerDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 8 step 2) {
            val dx = a[i] - b[i]
            val dy = a[i + 1] - b[i + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / 4f
    }

    /** Area of the four-corner outline (shoelace formula). */
    private fun polygonArea(q: FloatArray): Float {
        val x0 = q[0]; val y0 = q[1]
        val x1 = q[2]; val y1 = q[3]
        val x2 = q[4]; val y2 = q[5]
        val x3 = q[6]; val y3 = q[7]
        return abs(
            (x0 * y1 + x1 * y2 + x2 * y3 + x3 * y0) -
                (y0 * x1 + y1 * x2 + y2 * x3 + y3 * x0)
        ) / 2f
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "ScanCamera"
        val ANALYSIS_RESOLUTION = Size(1280, 720)

        /** The document must cover at least this share of the screen before auto-capture. */
        const val MIN_DOCUMENT_AREA_FRACTION = 0.18f

        /** Corners moving less than this (in screen pixels) between frames count as "steady". */
        const val STABLE_CORNER_MOVEMENT_PX = 10f
        const val STABLE_FRAMES_TO_CAPTURE = 12
        const val CAPTURE_COOLDOWN_MS = 1200L
    }
}
