package com.northernai.eclipsecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var rawBadge: TextView
    private lateinit var evLabel: TextView
    private lateinit var shutter: Button
    private lateinit var bracket: Button
    private lateinit var timer: Button
    private lateinit var zoom1: Button
    private lateinit var zoom2: Button
    private lateinit var zoom4: Button

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var rawJpeg = false
    private var busy = false
    private var bracketOn = true
    private var timerSeconds = 2
    private var baseEv = -1.5f
    private var zoomRatio = 2f
    private val handler = Handler(Looper.getMainLooper())

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all(::hasPermission)) startCamera()
        else status.text = "Camera permission required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        if (requiredPermissions().all(::hasPermission)) startCamera()
        else permissions.launch(requiredPermissions().toTypedArray())
    }

    override fun onDestroy() {
        provider?.unbindAll()
        super.onDestroy()
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.argb(215, 5, 8, 12))
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        titleBox.addView(label("EclipseCam", 23f, Color.WHITE, true))
        titleBox.addView(label("LUNAR ECLIPSE • FIELD MODE", 11f, GOLD, true))
        rawBadge = label("RAW CHECK…", 11f, MUTED, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(PANEL)
        }
        titleRow.addView(rawBadge)
        status = label("Starting camera…", 12f, MUTED, false).apply { setPadding(0, dp(7), 0, 0) }
        top.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.argb(235, 7, 11, 16))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(row1, LinearLayout.LayoutParams(-1, -2))
        val zooms = LinearLayout(this)
        row1.addView(zooms, LinearLayout.LayoutParams(0, -2, 1f))
        zoom1 = smallButton("1×") { setZoom(1f) }
        zoom2 = smallButton("2×") { setZoom(2f) }
        zoom4 = smallButton("4×") { setZoom(4f) }
        zooms.addView(zoom1); zooms.addView(zoom2); zooms.addView(zoom4)

        bracket = smallButton("BRACKET 3") {
            if (busy) return@smallButton
            bracketOn = !bracketOn
            bracket.text = if (bracketOn) "BRACKET 3" else "SINGLE"
            styleToggle(bracket, bracketOn)
            status.text = if (bracketOn) "3 exposures protect Moon + bright sign" else "Single exposure"
        }
        styleToggle(bracket, true)
        row1.addView(bracket)
        timer = smallButton("2s") {
            if (busy) return@smallButton
            timerSeconds = if (timerSeconds == 2) 0 else 2
            timer.text = "${timerSeconds}s"
        }
        row1.addView(timer)

        val evRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(evRow, LinearLayout.LayoutParams(-1, -2))
        evRow.addView(label("EV", 13f, GOLD, true))
        val seek = SeekBar(this).apply {
            max = 10
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    baseEv = -4f + progress * 0.5f
                    evLabel.text = formatEv(baseEv)
                    if (fromUser && !busy) camera?.let { setEv(it, baseEv) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        evRow.addView(seek, LinearLayout.LayoutParams(0, dp(42), 1f))
        evLabel = label(formatEv(baseEv), 13f, Color.WHITE, true).apply { gravity = Gravity.END }
        evRow.addView(evLabel, LinearLayout.LayoutParams(dp(58), -2))
        bottom.addView(label("Default: −3.0 • −1.5 • 0.0 EV", 11f, MUTED, false).apply { gravity = Gravity.CENTER })

        val shutterRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("ECLIPSE SAFE", 11f, GREEN, true))
        left.addView(label("Tap lit sign/building first", 10f, MUTED, false))
        shutterRow.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        shutter = Button(this).apply {
            text = "●"
            textSize = 42f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { beginCapture() }
        }
        shutterRow.addView(shutter, LinearLayout.LayoutParams(dp(92), dp(78)))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(label("FLASH OFF", 11f, GREEN, true).apply { gravity = Gravity.END })
        right.addView(label("Brace phone / tripod", 10f, MUTED, false).apply { gravity = Gravity.END })
        shutterRow.addView(right, LinearLayout.LayoutParams(0, -2, 1f))

        preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !busy) tapMeter(event.x, event.y)
            true
        }
        refreshZoomButtons()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindCamera(provider!!)
            } catch (t: Throwable) {
                status.text = "Camera failed: ${t.message ?: "unknown"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        p.unbindAll()

        var ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build().also { it.flashMode = ImageCapture.FLASH_MODE_OFF }
        var bound = p.bindToLifecycle(this, selector, previewUseCase, ic)

        val formats = ImageCapture.getImageCaptureCapabilities(bound.cameraInfo).supportedOutputFormats
        rawJpeg = formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        if (rawJpeg) {
            try {
                p.unbindAll()
                ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    .build().also { it.flashMode = ImageCapture.FLASH_MODE_OFF }
                bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
            } catch (_: Throwable) {
                rawJpeg = false
                p.unbindAll()
                ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build().also { it.flashMode = ImageCapture.FLASH_MODE_OFF }
                bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
            }
        }

        camera = bound
        capture = ic
        rawBadge.text = if (rawJpeg) "RAW + JPEG ✓" else "JPEG"
        rawBadge.setTextColor(if (rawJpeg) GREEN else MUTED)
        val maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        zoom2.isEnabled = maxZoom >= 1.9f
        zoom4.isEnabled = maxZoom >= 3.9f
        setZoom(if (maxZoom >= 1.9f) 2f else 1f)
        setEv(bound, baseEv)
        status.text = if (rawJpeg) "Ready • RAW+JPEG • tap distant lit sign/building" else "Ready • JPEG bracket • tap distant lit sign/building"
    }

    private fun tapMeter(x: Float, y: Float) {
        val c = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        val action = FocusMeteringAction.Builder(point, flags)
            .setAutoCancelDuration(8, TimeUnit.SECONDS)
            .build()
        c.cameraControl.startFocusAndMetering(action)
        status.text = "Focus + exposure metering set • shoot within 8 sec"
    }

    private fun setZoom(requested: Float) {
        val c = camera ?: run { zoomRatio = requested; refreshZoomButtons(); return }
        val max = c.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        zoomRatio = requested.coerceIn(1f, max)
        c.cameraControl.setZoomRatio(zoomRatio)
        refreshZoomButtons()
        status.text = if (zoomRatio >= 3.5f) "4× Moon detail" else if (zoomRatio >= 1.5f) "2× Moon + foreground" else "1× wide scene"
    }

    private fun refreshZoomButtons() {
        styleToggle(zoom1, abs(zoomRatio - 1f) < .35f)
        styleToggle(zoom2, abs(zoomRatio - 2f) < .35f)
        styleToggle(zoom4, abs(zoomRatio - 4f) < .6f)
    }

    private fun beginCapture() {
        val ic = capture ?: return
        val c = camera ?: return
        if (busy) return
        busy = true
        setControls(false)
        status.text = if (timerSeconds == 2) "Hold still • 2-second timer…" else "Hold still • capturing…"
        val go = Runnable {
            if (bracketOn) {
                val evs = listOf((baseEv - 1.5f).coerceAtLeast(-4f), baseEv, (baseEv + 1.5f).coerceAtMost(1f))
                captureBracket(ic, c, evs, 0, mutableListOf())
            } else {
                setEv(c, baseEv)
                handler.postDelayed({ captureOne(ic, baseEv, ::finishCapture, ::failCapture) }, 450)
            }
        }
        if (timerSeconds == 2) handler.postDelayed(go, 2000) else go.run()
    }

    private fun captureBracket(ic: ImageCapture, c: Camera, evs: List<Float>, index: Int, saved: MutableList<Uri>) {
        if (index >= evs.size) {
            setEv(c, baseEv)
            finishCapture(saved)
            return
        }
        val ev = evs[index]
        status.text = "Exposure ${index + 1}/3 • ${formatEv(ev)} EV"
        setEv(c, ev)
        handler.postDelayed({
            captureOne(ic, ev, { uris ->
                saved.addAll(uris)
                handler.postDelayed({ captureBracket(ic, c, evs, index + 1, saved) }, 180)
            }, ::failCapture)
        }, 500)
    }

    private fun captureOne(ic: ImageCapture, ev: Float, onSaved: (List<Uri>) -> Unit, onError: (String) -> Unit) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val tag = evTag(ev)
        if (rawJpeg) {
            val rawOpt = outputOptions("EclipseCam_${stamp}_${tag}.dng", "image/x-adobe-dng")
            val jpgOpt = outputOptions("EclipseCam_${stamp}_${tag}.jpg", "image/jpeg")
            val uris = mutableListOf<Uri>()
            var callbacks = 0
            var failed = false
            ic.takePicture(rawOpt, jpgOpt, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (failed) return
                    result.savedUri?.let(uris::add)
                    callbacks++
                    if (callbacks >= 2) onSaved(uris)
                }
                override fun onError(exception: ImageCaptureException) {
                    if (failed) return
                    failed = true
                    onError("RAW+JPEG capture failed: ${exception.message ?: "camera error"}")
                }
            })
        } else {
            val jpgOpt = outputOptions("EclipseCam_${stamp}_${tag}.jpg", "image/jpeg")
            ic.takePicture(jpgOpt, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) = onSaved(listOfNotNull(result.savedUri))
                override fun onError(exception: ImageCaptureException) = onError("Capture failed: ${exception.message ?: "camera error"}")
            })
        }
    }

    private fun outputOptions(name: String, mime: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EclipseCam")
        }
        return ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    }

    private fun setEv(c: Camera, ev: Float) {
        val s = c.cameraInfo.exposureState
        if (!s.isExposureCompensationSupported) return
        val step = s.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val index = (ev / step).roundToInt().coerceIn(s.exposureCompensationRange.lower, s.exposureCompensationRange.upper)
        c.cameraControl.setExposureCompensationIndex(index)
    }

    private fun finishCapture(uris: List<Uri>) {
        busy = false
        setControls(true)
        val exposures = if (bracketOn) 3 else 1
        val mode = if (rawJpeg) "RAW+JPEG" else "JPEG"
        status.text = "Saved $exposures exposure${if (exposures == 1) "" else "s"} • $mode • Pictures/EclipseCam"
        Toast.makeText(this, "Saved ${uris.size} file${if (uris.size == 1) "" else "s"}", Toast.LENGTH_LONG).show()
    }

    private fun failCapture(message: String) {
        busy = false
        setControls(true)
        camera?.let { setEv(it, baseEv) }
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setControls(enabled: Boolean) {
        shutter.isEnabled = enabled
        bracket.isEnabled = enabled
        timer.isEnabled = enabled
        zoom1.isEnabled = enabled
        zoom2.isEnabled = enabled && ((camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f) >= 1.9f)
        zoom4.isEnabled = enabled && ((camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f) >= 3.9f)
    }

    private fun smallButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(PANEL)
        minWidth = 0; minHeight = 0
        setPadding(dp(9), dp(5), dp(9), dp(5))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(2), 0, dp(2), 0) }
    }

    private fun styleToggle(button: Button, active: Boolean) {
        button.setBackgroundColor(if (active) GOLD else PANEL)
        button.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun formatEv(ev: Float) = if (ev > 0) "+%.1f".format(Locale.US, ev) else "%.1f".format(Locale.US, ev)
    private fun evTag(ev: Float) = "EV_${if (ev < 0) "m" else if (ev > 0) "p" else "z"}${"%.1f".format(Locale.US, abs(ev)).replace('.', '_')}"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val GOLD = 0xFFFFD878.toInt()
        private const val GREEN = 0xFF8CE3AF.toInt()
        private const val MUTED = 0xFFBAC3CD.toInt()
        private const val PANEL = 0xFF192028.toInt()
    }
}
