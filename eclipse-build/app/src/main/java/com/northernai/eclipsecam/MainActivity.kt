package com.northernai.eclipsecam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private data class Shot(val uri: Uri, val name: String, val mime: String)

    private lateinit var preview: PreviewView
    private lateinit var grid: GridOverlay
    private lateinit var status: TextView
    private lateinit var driveBadge: TextView
    private lateinit var focusBadge: TextView
    private lateinit var evLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: Button
    private lateinit var flipButton: Button
    private lateinit var flashButton: Button
    private lateinit var timerButton: Button
    private lateinit var gridButton: Button
    private lateinit var rawButton: Button
    private lateinit var driveButton: Button
    private lateinit var zoom1: Button
    private lateinit var zoom2: Button
    private lateinit var zoomMaxButton: Button

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var starting = false

    private var lensBack = true
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var gridOn = false
    private var rawWanted = false
    private var rawAvailable = false
    private var rawActive = false
    private var autoUpload = true
    private var driveConnected = false

    private var busy = false
    private var baseEv = 0f
    private var zoomRatio = 1f
    private var maxZoomRatio = 1f
    private var focusLocked = false

    private var captureToken = 0
    private var watchdog: Runnable? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var gestureHadScale = false
    private var gestureMaxPointers = 0

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camera", Context.MODE_PRIVATE) }

    private lateinit var authLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all(::hasPermission)) startCamera()
        else status.text = "Camera permission required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        autoUpload = prefs.getBoolean(PREF_AUTO_UPLOAD, true)
        rawWanted = prefs.getBoolean(PREF_RAW, false)
        gridOn = prefs.getBoolean(PREF_GRID, false)
        timerSeconds = prefs.getInt(PREF_TIMER, 0)
        flashMode = prefs.getInt(PREF_FLASH, ImageCapture.FLASH_MODE_OFF)

        authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val granted = runCatching {
                Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.data)
            }.getOrNull()?.accessToken != null
            setDriveConnected(granted)
            status.text = if (granted) {
                "Google Drive connected • new photos upload to Drive/${Drive.FOLDER_NAME}"
            } else {
                "Google Drive not connected • photos still save to the phone"
            }
        }

        buildUi()
        observeUploads()
        if (requiredPermissions().all(::hasPermission)) startCamera()
        else permissions.launch(requiredPermissions().toTypedArray())
        refreshDriveSilently()
    }

    override fun onResume() {
        super.onResume()
        if (camera == null && requiredPermissions().all(::hasPermission)) startCamera()
    }

    override fun onStop() {
        cancelSequence("Capture cancelled • app left the foreground")
        super.onStop()
    }

    override fun onDestroy() {
        captureToken++
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        super.onDestroy()
    }

    private fun requiredPermissions() = listOf(Manifest.permission.CAMERA)

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---- ui ----------------------------------------------------------------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        grid = GridOverlay(this).apply { visibility = if (gridOn) View.VISIBLE else View.GONE }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.argb(200, 5, 8, 12))
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        titleRow.addView(
            label("NorthernCam", 20f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        driveBadge = label("DRIVE: CHECKING…", 10f, MUTED, true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setBackgroundColor(PANEL)
        }
        titleRow.addView(driveBadge)

        focusBadge = label("FOCUS: TAP", 10f, GOLD, true).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setBackgroundColor(PANEL)
        }
        top.addView(focusBadge, LinearLayout.LayoutParams(-2, -2))
        status = label("Starting camera…", 12f, MUTED, false).apply { setPadding(0, dp(6), 0, 0) }
        top.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(12))
            setBackgroundColor(Color.argb(230, 7, 11, 16))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // zoom presets + readout
        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(zoomRow, LinearLayout.LayoutParams(-1, -2))
        zoom1 = smallButton("1×") { setZoom(1f) }
        zoom2 = smallButton("2×") { setZoom(2f) }
        zoomMaxButton = smallButton("MAX") { setZoom(maxZoomRatio) }
        zoomRow.addView(zoom1)
        zoomRow.addView(zoom2)
        zoomRow.addView(zoomMaxButton)
        zoomLabel = label("1×", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(0, -2, 1f))

        zoomSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !busy) setZoom(progressToZoom(progress))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        bottom.addView(zoomSeek, LinearLayout.LayoutParams(-1, dp(34)))

        // mode row
        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(modeRow, LinearLayout.LayoutParams(-1, -2))
        flashButton = smallButton(flashText()) { if (!busy) cycleFlash() }
        modeRow.addView(flashButton)
        timerButton = smallButton(timerText()) { if (!busy) cycleTimer() }
        modeRow.addView(timerButton)
        gridButton = smallButton("GRID") { if (!busy) toggleGrid() }
        modeRow.addView(gridButton)
        rawButton = smallButton("RAW") { if (!busy) toggleRaw() }
        modeRow.addView(rawButton)
        driveButton = smallButton("DRIVE") { if (!busy) onDriveButton() }
        modeRow.addView(driveButton)

        // exposure
        val evRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(evRow, LinearLayout.LayoutParams(-1, -2))
        evRow.addView(label("EV", 13f, GOLD, true))
        val evSeek = SeekBar(this).apply {
            max = 12
            progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    baseEv = (progress - 6) * 0.5f
                    evLabel.text = formatEv(baseEv)
                    if (fromUser && !busy) camera?.let { applyEvNow(it, baseEv) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        evRow.addView(evSeek, LinearLayout.LayoutParams(0, dp(40), 1f))
        evLabel = label(formatEv(baseEv), 13f, Color.WHITE, true).apply { gravity = Gravity.END }
        evRow.addView(evLabel, LinearLayout.LayoutParams(dp(52), -2))

        // shutter row
        val shutterRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))

        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(PANEL)
        }
        shutterRow.addView(thumbnail, LinearLayout.LayoutParams(dp(52), dp(52)))

        shutterRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shutter = Button(this).apply {
            text = "●"
            textSize = 42f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { beginCapture() }
        }
        shutterRow.addView(shutter, LinearLayout.LayoutParams(dp(92), dp(76)))
        shutterRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        flipButton = smallButton("FLIP") { if (!busy) flipCamera() }
        shutterRow.addView(flipButton, LinearLayout.LayoutParams(dp(52), dp(44)))

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureHadScale = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!busy) setZoom(zoomRatio * detector.scaleFactor)
                return true
            }
        })

        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        preview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    gestureHadScale = false
                    gestureMaxPointers = 1
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    gestureMaxPointers = maxOf(gestureMaxPointers, event.pointerCount)
                MotionEvent.ACTION_UP -> {
                    // Only a real single-finger tap re-meters; a pinch or a drag must not.
                    val moved = hypot(event.x - downX, event.y - downY)
                    if (!busy && !gestureHadScale && gestureMaxPointers == 1 &&
                        moved <= slop && event.eventTime - downTime <= TAP_MAX_MS
                    ) {
                        tapMeter(event.x, event.y)
                    }
                }
            }
            true
        }

        styleToggle(gridButton, gridOn)
        styleToggle(rawButton, rawWanted)
        styleToggle(flashButton, flashMode != ImageCapture.FLASH_MODE_OFF)
        styleToggle(timerButton, timerSeconds > 0)
        refreshZoomButtons()
    }

    // ---- modes -------------------------------------------------------------

    private fun flashText() = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> "FLASH ON"
        ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"
        else -> "FLASH OFF"
    }

    private fun timerText() = if (timerSeconds == 0) "TIMER OFF" else "${timerSeconds}s"

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        prefs.edit().putInt(PREF_FLASH, flashMode).apply()
        capture?.flashMode = flashMode
        flashButton.text = flashText()
        styleToggle(flashButton, flashMode != ImageCapture.FLASH_MODE_OFF)
        if (camera?.cameraInfo?.hasFlashUnit() == false && flashMode != ImageCapture.FLASH_MODE_OFF) {
            status.text = "This camera has no flash"
        }
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        prefs.edit().putInt(PREF_TIMER, timerSeconds).apply()
        timerButton.text = timerText()
        styleToggle(timerButton, timerSeconds > 0)
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        prefs.edit().putBoolean(PREF_GRID, gridOn).apply()
        grid.visibility = if (gridOn) View.VISIBLE else View.GONE
        styleToggle(gridButton, gridOn)
    }

    private fun toggleRaw() {
        if (!rawAvailable) {
            status.text = "This camera does not offer RAW"
            return
        }
        rawWanted = !rawWanted
        prefs.edit().putBoolean(PREF_RAW, rawWanted).apply()
        styleToggle(rawButton, rawWanted)
        provider?.let { bindCamera(it) }
    }

    private fun flipCamera() {
        lensBack = !lensBack
        focusLocked = false
        provider?.let { bindCamera(it) }
    }

    // ---- camera ------------------------------------------------------------

    private fun startCamera() {
        if (starting) return
        starting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            starting = false
            try {
                provider = future.get()
                bindCamera(provider!!)
            } catch (t: Throwable) {
                camera = null
                capture = null
                status.text = "Camera failed: ${t.message ?: "unknown"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        val selector =
            if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val previewUseCase = Preview.Builder().build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }

        try {
            p.unbindAll()
            var ic = buildCapture(false)
            var bound = p.bindToLifecycle(this, selector, previewUseCase, ic)

            rawAvailable = runCatching {
                ImageCapture.getImageCaptureCapabilities(bound.cameraInfo)
                    .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            }.getOrDefault(false)

            rawActive = false
            if (rawAvailable && rawWanted) {
                try {
                    p.unbindAll()
                    ic = buildCapture(true)
                    bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
                    rawActive = true
                } catch (_: Throwable) {
                    p.unbindAll()
                    ic = buildCapture(false)
                    bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
                }
            }

            camera = bound
            capture = ic
            focusLocked = false
            focusBadge.text = "FOCUS: TAP"
            focusBadge.setTextColor(GOLD)
            rawButton.isEnabled = rawAvailable
            styleToggle(rawButton, rawActive)
            styleToggle(flashButton, flashMode != ImageCapture.FLASH_MODE_OFF)
            styleToggle(timerButton, timerSeconds > 0)
            maxZoomRatio = (bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
            zoomMaxButton.text = if (maxZoomRatio >= 9.5f) "MAX" else "${formatZoom(maxZoomRatio)}×"
            setZoom(1f)
            applyEvNow(bound, baseEv)
            setControls(true)
            status.text = buildString {
                append(if (lensBack) "Rear camera" else "Front camera")
                append(if (rawActive) " • RAW+JPEG" else " • JPEG")
                append(if (autoUpload && driveConnected) " • uploading to Drive" else " • saving to phone")
            }
        } catch (t: Throwable) {
            camera = null
            capture = null
            setControls(false)
            status.text = "Could not open that camera: ${t.message ?: "unknown"}"
        }
    }

    private fun buildCapture(raw: Boolean) = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .also { if (raw) it.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
        .build()
        .also { it.flashMode = flashMode }

    private fun tapMeter(x: Float, y: Float) {
        val c = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        // No auto-cancel: a lock the user set stays until they set another one.
        val action = FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()
        focusBadge.text = "FOCUSING…"
        focusBadge.setTextColor(GOLD)
        focusLocked = false
        val result = runCatching { c.cameraControl.startFocusAndMetering(action) }.getOrNull()
        if (result == null) {
            focusBadge.text = "FOCUS: RETAP"
            return
        }
        result.addListener({
            runCatching { result.get() }.onSuccess {
                if (it.isFocusSuccessful) {
                    focusLocked = true
                    focusBadge.text = "FOCUS ✓ LOCKED"
                    focusBadge.setTextColor(GREEN)
                } else {
                    focusBadge.text = "FOCUS: RETAP"
                    focusBadge.setTextColor(GOLD)
                }
            }.onFailure {
                focusBadge.text = "FOCUS: RETAP"
                focusBadge.setTextColor(GOLD)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun invalidateFocus() {
        if (!focusLocked) return
        focusLocked = false
        focusBadge.text = "FOCUS: RETAP"
        focusBadge.setTextColor(GOLD)
    }

    // ---- zoom --------------------------------------------------------------

    private fun setZoom(requested: Float) {
        val c = camera ?: run {
            zoomRatio = requested.coerceAtLeast(1f)
            refreshZoomButtons()
            return
        }
        maxZoomRatio = (c.cameraInfo.zoomState.value?.maxZoomRatio ?: maxZoomRatio).coerceAtLeast(1f)
        val next = requested.coerceIn(1f, maxZoomRatio)
        val changed = abs(next - zoomRatio) > 0.02f
        zoomRatio = next
        runCatching { c.cameraControl.setZoomRatio(zoomRatio) }
        refreshZoomButtons()
        zoomLabel.text = "${formatZoom(zoomRatio)}× / ${formatZoom(maxZoomRatio)}×"
        val target = zoomToProgress(zoomRatio)
        if (zoomSeek.progress != target) zoomSeek.progress = target
        if (changed) invalidateFocus()
    }

    // Logarithmic: the low end, where most framing happens, gets proportionate travel.
    private fun progressToZoom(progress: Int): Float {
        if (maxZoomRatio <= 1f) return 1f
        val t = progress.coerceIn(0, 1000) / 1000f
        return exp(ln(maxZoomRatio.toDouble()) * t).toFloat().coerceIn(1f, maxZoomRatio)
    }

    private fun zoomToProgress(ratio: Float): Int {
        if (maxZoomRatio <= 1f) return 0
        val t = ln(ratio.coerceIn(1f, maxZoomRatio).toDouble()) / ln(maxZoomRatio.toDouble())
        return (t * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    private fun refreshZoomButtons() {
        if (!::zoom1.isInitialized) return
        styleToggle(zoom1, abs(zoomRatio - 1f) < .25f)
        styleToggle(zoom2, abs(zoomRatio - 2f) < .35f)
        styleToggle(zoomMaxButton, maxZoomRatio > 1.1f && abs(zoomRatio - maxZoomRatio) < .45f)
    }

    // ---- exposure ----------------------------------------------------------

    private fun evIndex(c: Camera, ev: Float): Int? {
        val s = c.cameraInfo.exposureState
        if (!s.isExposureCompensationSupported) return null
        val step = s.exposureCompensationStep.toFloat()
        if (step <= 0f) return null
        return (ev / step).roundToInt().coerceIn(
            s.exposureCompensationRange.lower,
            s.exposureCompensationRange.upper
        )
    }

    private fun applyEvNow(c: Camera, ev: Float) {
        val index = evIndex(c, ev) ?: return
        runCatching { c.cameraControl.setExposureCompensationIndex(index) }
    }

    private fun applyEv(c: Camera, ev: Float, token: Int, then: () -> Unit) {
        val proceed = Runnable { if (token == captureToken) then() }
        val index = evIndex(c, ev)
        val future = index?.let {
            runCatching { c.cameraControl.setExposureCompensationIndex(it) }.getOrNull()
        }
        if (future == null) {
            handler.postDelayed(proceed, EV_SETTLE_MS)
            return
        }
        var fired = false
        val once = Runnable {
            if (fired) return@Runnable
            fired = true
            handler.postDelayed(proceed, EV_SETTLE_MS)
        }
        future.addListener({ once.run() }, ContextCompat.getMainExecutor(this))
        handler.postDelayed(once, EV_CONVERGE_TIMEOUT_MS)
    }

    // ---- capture -----------------------------------------------------------

    private fun beginCapture() {
        if (busy) return
        if (capture == null || camera == null) {
            status.text = "Camera not ready"
            return
        }
        busy = true
        setControls(false)
        val token = captureToken
        val go = Runnable {
            if (token != captureToken) return@Runnable
            val ic = capture
            val c = camera
            if (ic == null || c == null) {
                failCapture("Camera went away before capture")
                return@Runnable
            }
            status.text = "Capturing…"
            applyEv(c, baseEv, token) { captureOne(ic, token) }
        }
        if (timerSeconds > 0) {
            countdown(timerSeconds, token, go)
        } else {
            go.run()
        }
    }

    private fun countdown(remaining: Int, token: Int, then: Runnable) {
        if (token != captureToken) return
        if (remaining <= 0) {
            then.run()
            return
        }
        status.text = "Hold still • $remaining…"
        handler.postDelayed({ countdown(remaining - 1, token, then) }, 1000)
    }

    private fun captureOne(ic: ImageCapture, token: Int) {
        if (token != captureToken) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var settled = false
        // Single exit point: no callback ordering or missing second file can strand the shutter.
        fun resolve(block: () -> Unit) {
            if (settled || token != captureToken) return
            settled = true
            cancelWatchdog()
            block()
        }
        armWatchdog(token)
        try {
            if (rawActive) {
                val dngName = "${NAME_PREFIX}_$stamp.dng"
                val jpgName = "${NAME_PREFIX}_$stamp.jpg"
                val shots = mutableListOf<Shot>()
                ic.takePicture(
                    outputOptions(dngName, MIME_DNG),
                    outputOptions(jpgName, MIME_JPEG),
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            if (settled || token != captureToken) return
                            val uri = result.savedUri ?: return
                            // RAW+JPEG delivers one callback per file, in no guaranteed order,
                            // so the saved name is what tells us which one this is.
                            shots.add(shotFor(uri, jpgName))
                            if (shots.size >= 2) resolve { finishCapture(shots.toList()) }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            val partial = shots.toList()
                            resolve {
                                if (partial.isEmpty()) {
                                    failCapture("Capture failed: ${exception.message ?: "camera error"}")
                                } else {
                                    finishCapture(partial)
                                }
                            }
                        }
                    }
                )
            } else {
                val jpgName = "${NAME_PREFIX}_$stamp.jpg"
                ic.takePicture(
                    outputOptions(jpgName, MIME_JPEG),
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            val uri = result.savedUri
                            resolve {
                                if (uri == null) failCapture("Saved, but the file could not be located")
                                else finishCapture(listOf(Shot(uri, jpgName, MIME_JPEG)))
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            resolve { failCapture("Capture failed: ${exception.message ?: "camera error"}") }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            resolve { failCapture("Capture rejected: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    /** MediaStore may adjust the name it stores, so read back what it actually used. */
    private fun shotFor(uri: Uri, fallbackName: String): Shot {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: fallbackName
        return Shot(uri, name, if (name.endsWith(".dng", true)) MIME_DNG else MIME_JPEG)
    }

    private fun armWatchdog(token: Int) {
        cancelWatchdog()
        val r = Runnable {
            if (token != captureToken || !busy) return@Runnable
            captureToken++
            busy = false
            setControls(true)
            status.text = "Capture timed out • controls released"
            Toast.makeText(this, "Capture timed out", Toast.LENGTH_LONG).show()
        }
        watchdog = r
        handler.postDelayed(r, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
    }

    private fun cancelSequence(message: String) {
        captureToken++
        cancelWatchdog()
        handler.removeCallbacksAndMessages(null)
        if (busy) {
            busy = false
            setControls(true)
            status.text = message
        }
    }

    private fun outputOptions(name: String, mime: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$NAME_PREFIX")
        }
        return ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()
    }

    private fun finishCapture(shots: List<Shot>) {
        cancelWatchdog()
        busy = false
        setControls(true)
        shots.firstOrNull { it.mime == MIME_JPEG }?.let { showThumbnail(it.uri) }

        val queued = if (autoUpload && driveConnected) {
            shots.forEach { UploadWorker.enqueue(this, it.uri, it.name, it.mime) }
            shots.size
        } else {
            0
        }
        status.text = buildString {
            append("Saved ${shots.size} file${if (shots.size == 1) "" else "s"}")
            when {
                queued > 0 -> append(" • uploading to Drive")
                !driveConnected -> append(" • Drive not connected")
                else -> append(" • auto-upload off")
            }
        }
    }

    private fun failCapture(message: String) {
        cancelWatchdog()
        busy = false
        setControls(true)
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showThumbnail(uri: Uri) {
        val size = Size(dp(96), dp(96))
        Thread {
            val bitmap = runCatching { contentResolver.loadThumbnail(uri, size, null) }.getOrNull()
            if (bitmap != null) runOnUiThread { thumbnail.setImageBitmap(bitmap) }
        }.start()
    }

    // ---- drive -------------------------------------------------------------

    private fun setDriveConnected(connected: Boolean) {
        driveConnected = connected
        refreshDriveBadge()
    }

    private fun refreshDriveBadge(extra: String? = null) {
        driveBadge.text = extra ?: when {
            !driveConnected -> "DRIVE: TAP TO CONNECT"
            autoUpload -> "DRIVE: AUTO ✓"
            else -> "DRIVE: MANUAL"
        }
        driveBadge.setTextColor(
            when {
                extra != null -> GOLD
                driveConnected && autoUpload -> GREEN
                driveConnected -> MUTED
                else -> GOLD
            }
        )
        styleToggle(driveButton, driveConnected && autoUpload)
    }

    /** Checks for an existing grant without showing anything, so a returning user is just connected. */
    private fun refreshDriveSilently() {
        Identity.getAuthorizationClient(this)
            .authorize(Drive.authorizationRequest())
            .addOnSuccessListener { result -> setDriveConnected(!result.hasResolution()) }
            .addOnFailureListener {
                setDriveConnected(false)
                driveBadge.text = "DRIVE: UNAVAILABLE"
            }
    }

    private fun onDriveButton() {
        if (!driveConnected) {
            connectDrive()
            return
        }
        autoUpload = !autoUpload
        prefs.edit().putBoolean(PREF_AUTO_UPLOAD, autoUpload).apply()
        refreshDriveBadge()
        status.text = if (autoUpload) {
            "Auto-upload on • every shot goes to Drive/${Drive.FOLDER_NAME}"
        } else {
            "Auto-upload off • photos stay on the phone"
        }
    }

    private fun connectDrive() {
        status.text = "Connecting to Google Drive…"
        Identity.getAuthorizationClient(this)
            .authorize(Drive.authorizationRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    runCatching {
                        authLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }.onFailure {
                        status.text = "Could not open the Google consent screen: ${it.message}"
                    }
                } else {
                    setDriveConnected(true)
                    status.text = "Google Drive connected • uploads go to Drive/${Drive.FOLDER_NAME}"
                }
            }
            .addOnFailureListener {
                setDriveConnected(false)
                status.text = "Google sign-in unavailable: ${it.message ?: "unknown"}"
            }
    }

    private fun observeUploads() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.TAG)
            .observe(this) { infos ->
                if (infos == null) return@observe
                val active = infos.count { !it.state.isFinished }
                val failed = infos.filter { it.state == WorkInfo.State.FAILED }
                when {
                    active > 0 -> refreshDriveBadge("DRIVE: ↑ $active")
                    failed.isNotEmpty() -> {
                        refreshDriveBadge("DRIVE: ${failed.size} FAILED")
                        failed.last().outputData.getString(UploadWorker.KEY_ERROR)
                            ?.let { status.text = it }
                    }
                    else -> refreshDriveBadge()
                }
            }
    }

    // ---- helpers -----------------------------------------------------------

    private fun setControls(enabled: Boolean) {
        val ready = camera != null
        shutter.isEnabled = enabled && ready
        flipButton.isEnabled = enabled
        flashButton.isEnabled = enabled
        timerButton.isEnabled = enabled
        gridButton.isEnabled = enabled
        rawButton.isEnabled = enabled && rawAvailable
        driveButton.isEnabled = enabled
        zoomSeek.isEnabled = enabled && ready
        zoom1.isEnabled = enabled && ready
        zoom2.isEnabled = enabled && ready && maxZoomRatio >= 1.9f
        zoomMaxButton.isEnabled = enabled && ready && maxZoomRatio > 1.1f
    }

    private fun smallButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 10f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(PANEL)
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(2), 0, dp(2), 0) }
    }

    private fun styleToggle(button: Button, active: Boolean) {
        button.setBackgroundColor(if (active) GOLD else PANEL)
        button.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun formatEv(ev: Float) =
        if (ev > 0) "+%.1f".format(Locale.US, ev) else "%.1f".format(Locale.US, ev)

    private fun formatZoom(z: Float) =
        if (abs(z - z.roundToInt()) < .05f) z.roundToInt().toString() else "%.1f".format(Locale.US, z)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Rule-of-thirds guides. */
    private class GridOverlay(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.argb(90, 255, 255, 255)
            strokeWidth = 1f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 1..2) {
                val x = w * i / 3f
                val y = h * i / 3f
                canvas.drawLine(x, 0f, x, h, paint)
                canvas.drawLine(0f, y, w, y, paint)
            }
        }
    }

    companion object {
        private const val GOLD = 0xFFFFD878.toInt()
        private const val GREEN = 0xFF8CE3AF.toInt()
        private const val MUTED = 0xFFBAC3CD.toInt()
        private const val PANEL = 0xFF192028.toInt()

        private const val NAME_PREFIX = "NorthernCam"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_DNG = "image/x-adobe-dng"

        private const val PREF_AUTO_UPLOAD = "autoUpload"
        private const val PREF_RAW = "raw"
        private const val PREF_GRID = "grid"
        private const val PREF_TIMER = "timer"
        private const val PREF_FLASH = "flash"

        private const val TAP_MAX_MS = 500L
        private const val EV_SETTLE_MS = 200L
        private const val EV_CONVERGE_TIMEOUT_MS = 2000L
        private const val CAPTURE_TIMEOUT_MS = 20000L
    }
}
