package com.northernai.eclipsecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var rawBadge: TextView
    private lateinit var focusBadge: TextView
    private lateinit var evLabel: TextView
    private lateinit var bracketLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var shutter: Button
    private lateinit var bracket: Button
    private lateinit var timer: Button
    private lateinit var farFocus: Button
    private lateinit var zoom1: Button
    private lateinit var zoom2: Button
    private lateinit var zoom4: Button
    private lateinit var zoom6: Button
    private lateinit var zoomMaxButton: Button

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var starting = false
    private var rawJpeg = false
    private var rawProven = false
    private var busy = false
    private var bracketOn = true
    private var timerSeconds = 2
    private var baseEv = -1.5f
    private var zoomRatio = 2f
    private var maxZoomRatio = 1f
    private var focusLocked = false
    private var farFocusSupported = false
    private var farFocusOn = false

    // Every scheduled capture stage carries the token it was created under. Cancelling a
    // sequence bumps the token, so late CameraX callbacks and pending Runnables become no-ops
    // instead of resuming a sequence that no longer exists.
    private var captureToken = 0
    private var watchdog: Runnable? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var gestureHadScale = false
    private var gestureMaxPointers = 0

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

    override fun onResume() {
        super.onResume()
        // Recover from a bind that failed earlier (camera busy, transient HAL error) instead of
        // forcing the user to kill the app mid-eclipse.
        if (camera == null && requiredPermissions().all(::hasPermission)) startCamera()
    }

    override fun onStop() {
        // Leaving the app unbinds CameraX. Tear the sequence down deterministically so the
        // shutter is never left disabled when the user comes back.
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
        titleBox.addView(label("LUNAR ECLIPSE • SHARPNESS MODE", 11f, GOLD, true))
        rawBadge = label("RAW CHECK…", 11f, MUTED, true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setBackgroundColor(PANEL)
        }
        titleRow.addView(rawBadge)
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
            setBackgroundColor(Color.argb(235, 7, 11, 16))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val zoomButtonRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(zoomButtonRow, LinearLayout.LayoutParams(-1, -2))
        zoom1 = smallButton("1×") { setZoom(1f) }
        zoom2 = smallButton("2×") { setZoom(2f) }
        zoom4 = smallButton("4×") { setZoom(4f) }
        zoom6 = smallButton("6×") { setZoom(6f) }
        zoomMaxButton = smallButton("MAX") { setZoom(maxZoomRatio) }
        zoomButtonRow.addView(zoom1)
        zoomButtonRow.addView(zoom2)
        zoomButtonRow.addView(zoom4)
        zoomButtonRow.addView(zoom6)
        zoomButtonRow.addView(zoomMaxButton)
        zoomLabel = label("2.0×", 12f, Color.WHITE, true).apply { gravity = Gravity.END }
        zoomButtonRow.addView(zoomLabel, LinearLayout.LayoutParams(0, -2, 1f))

        val zoomSliderRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(zoomSliderRow, LinearLayout.LayoutParams(-1, -2))
        zoomSliderRow.addView(label("ZOOM", 11f, GOLD, true))
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
        zoomSliderRow.addView(zoomSeek, LinearLayout.LayoutParams(0, dp(38), 1f))
        zoomSliderRow.addView(label("PINCH", 10f, MUTED, true))

        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(modeRow, LinearLayout.LayoutParams(-1, -2))
        bracket = smallButton("BRACKET 3") {
            if (busy) return@smallButton
            bracketOn = !bracketOn
            bracket.text = if (bracketOn) "BRACKET 3" else "SINGLE"
            styleToggle(bracket, bracketOn)
            refreshBracketLabel()
            status.text = if (bracketOn) "3 exposures protect Moon + bright sign" else "Single exposure"
        }
        styleToggle(bracket, true)
        modeRow.addView(bracket)
        timer = smallButton("2s") {
            if (busy) return@smallButton
            timerSeconds = if (timerSeconds == 2) 0 else 2
            timer.text = "${timerSeconds}s"
            refreshBracketLabel()
        }
        modeRow.addView(timer)
        farFocus = smallButton("∞ FAR") {
            if (busy) return@smallButton
            if (!farFocusSupported) {
                status.text = "Manual infinity focus not supported on this camera • use tap focus"
                return@smallButton
            }
            setFarFocus(!farFocusOn)
        }
        farFocus.isEnabled = false
        modeRow.addView(farFocus)
        modeRow.addView(label("MAX QUALITY • TAP LOCKS FOCUS", 10f, GREEN, true).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

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
                    refreshBracketLabel()
                    if (fromUser && !busy) camera?.let { applyEvNow(it, baseEv) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        evRow.addView(seek, LinearLayout.LayoutParams(0, dp(42), 1f))
        evLabel = label(formatEv(baseEv), 13f, Color.WHITE, true).apply { gravity = Gravity.END }
        evRow.addView(evLabel, LinearLayout.LayoutParams(dp(58), -2))
        bracketLabel = label("", 10f, MUTED, false).apply { gravity = Gravity.CENTER }
        bottom.addView(bracketLabel)
        refreshBracketLabel()

        val shutterRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        bottom.addView(shutterRow, LinearLayout.LayoutParams(-1, -2))
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("SHARPNESS FIRST", 11f, GREEN, true))
        left.addView(label("Tap a crisp distant edge", 10f, MUTED, false))
        shutterRow.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        shutter = Button(this).apply {
            text = "●"
            textSize = 42f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { beginCapture() }
        }
        shutterRow.addView(shutter, LinearLayout.LayoutParams(dp(92), dp(76)))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(label("FLASH OFF", 11f, GREEN, true).apply { gravity = Gravity.END })
        right.addView(label("4× = tele range, not proven optical", 10f, MUTED, false).apply { gravity = Gravity.END })
        shutterRow.addView(right, LinearLayout.LayoutParams(0, -2, 1f))

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
                    // Only a genuine single-finger tap re-meters. A pinch, a drag or a
                    // lingering finger must never silently throw away a focus lock.
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
        refreshZoomButtons()
    }

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
                status.text = "Camera failed: ${t.message ?: "unknown"} • reopen the app to retry"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        p.unbindAll()

        var ic = buildCapture(false)
        var bound = p.bindToLifecycle(this, selector, previewUseCase, ic)

        rawJpeg = runCatching {
            ImageCapture.getImageCaptureCapabilities(bound.cameraInfo)
                .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }.getOrDefault(false)
        if (rawJpeg) {
            try {
                p.unbindAll()
                ic = buildCapture(true)
                bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
            } catch (_: Throwable) {
                rawJpeg = false
                p.unbindAll()
                ic = buildCapture(false)
                bound = p.bindToLifecycle(this, selector, previewUseCase, ic)
            }
        }

        camera = bound
        capture = ic
        rawProven = false
        farFocusOn = false
        focusLocked = false
        focusBadge.text = "FOCUS: TAP"
        focusBadge.setTextColor(GOLD)
        refreshRawBadge()
        farFocusSupported = detectFarFocusSupport(bound.cameraInfo)
        farFocus.isEnabled = farFocusSupported
        styleToggle(farFocus, false)
        maxZoomRatio = (bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
        zoomMaxButton.text = if (maxZoomRatio >= 9.5f) "MAX" else "${formatZoom(maxZoomRatio)}×"
        setZoom(if (maxZoomRatio >= 3.9f) 4f else if (maxZoomRatio >= 1.9f) 2f else 1f)
        focusLocked = false
        applyEvNow(bound, baseEv)
        refreshBracketLabel()
        setControls(true)
        status.text = if (rawJpeg) {
            "Ready • MAX QUALITY • RAW+JPEG armed • tap a crisp distant edge to lock focus"
        } else {
            "Ready • MAX QUALITY • JPEG • tap a crisp distant edge to lock focus"
        }
    }

    private fun buildCapture(raw: Boolean) = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .also { if (raw) it.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
        .build()
        .also { it.flashMode = ImageCapture.FLASH_MODE_OFF }

    private fun refreshRawBadge() {
        when {
            !rawJpeg -> {
                rawBadge.text = "JPEG"
                rawBadge.setTextColor(MUTED)
            }
            rawProven -> {
                rawBadge.text = "RAW + JPEG ✓"
                rawBadge.setTextColor(GREEN)
            }
            else -> {
                // Capability + a successful bind are not proof that a DNG reaches storage.
                rawBadge.text = "RAW + JPEG ARMED"
                rawBadge.setTextColor(GOLD)
            }
        }
    }

    // ---- focus -------------------------------------------------------------

    private fun tapMeter(x: Float, y: Float) {
        val c = camera ?: return
        if (farFocusOn) setFarFocus(false)
        val point = preview.meteringPointFactory.createPoint(x, y)
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        // No auto-cancel: on a black sky, letting AF fall back to continuous mode after a
        // timeout will hunt off the Moon and the badge would keep claiming a lock it lost.
        val action = FocusMeteringAction.Builder(point, flags).disableAutoCancel().build()
        focusBadge.text = "FOCUSING…"
        focusBadge.setTextColor(GOLD)
        focusLocked = false
        val result = runCatching { c.cameraControl.startFocusAndMetering(action) }.getOrNull()
        if (result == null) {
            focusBadge.text = "FOCUS: RETAP"
            focusBadge.setTextColor(GOLD)
            return
        }
        result.addListener({
            runCatching { result.get() }.onSuccess { focusResult ->
                if (focusResult.isFocusSuccessful) {
                    focusLocked = true
                    focusBadge.text = "FOCUS ✓ LOCKED"
                    focusBadge.setTextColor(GREEN)
                    status.text = "Focus + metering held until you tap again"
                } else {
                    focusBadge.text = "FOCUS: RETAP"
                    focusBadge.setTextColor(GOLD)
                    status.text = "Focus uncertain • tap a crisp distant edge, or use ∞ FAR"
                }
            }.onFailure {
                focusBadge.text = "FOCUS: RETAP"
                focusBadge.setTextColor(GOLD)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun detectFarFocusSupport(info: CameraInfo): Boolean = runCatching {
        val c2 = Camera2CameraInfo.from(info)
        val modes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        modes != null && modes.contains(CameraMetadata.CONTROL_AF_MODE_OFF) && (minFocus ?: 0f) > 0f
    }.getOrDefault(false)

    /**
     * Drives the lens to the far end (focus distance 0 dioptres = infinity) with AF switched off.
     * For a Moon at optical infinity this is more dependable than asking AF to find contrast in a
     * near-black frame. Any tap-to-focus hands control back to AF.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun setFarFocus(on: Boolean) {
        val c = camera ?: return
        farFocusOn = on
        styleToggle(farFocus, on)
        focusLocked = on
        focusBadge.text = if (on) "FOCUS ∞ MANUAL" else "FOCUS: TAP"
        focusBadge.setTextColor(if (on) GREEN else GOLD)
        val future = runCatching {
            val control = Camera2CameraControl.from(c.cameraControl)
            if (on) {
                control.setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                        .build()
                )
            } else {
                control.clearCaptureRequestOptions()
            }
        }.getOrNull()
        if (future == null) {
            revertFarFocus()
            return
        }
        future.addListener({
            if (runCatching { future.get() }.isFailure && on) revertFarFocus()
            else if (on) status.text = "Manual infinity focus engaged • verify Moon sharpness in preview"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun revertFarFocus() {
        farFocusOn = false
        focusLocked = false
        styleToggle(farFocus, false)
        focusBadge.text = "FOCUS: TAP"
        focusBadge.setTextColor(GOLD)
        status.text = "Infinity focus not accepted by the camera • use tap focus"
    }

    private fun invalidateFocus(reason: String) {
        if (!focusLocked || farFocusOn) return
        focusLocked = false
        focusBadge.text = "FOCUS: RETAP"
        focusBadge.setTextColor(GOLD)
        status.text = reason
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
        val targetProgress = zoomToProgress(zoomRatio)
        if (zoomSeek.progress != targetProgress) zoomSeek.progress = targetProgress
        // Changing the crop moves the metering region under the lock, so the previous
        // focus/AE point is no longer the thing the user aimed at.
        if (changed) invalidateFocus("Zoom changed • retap to lock focus at this framing")
        if (focusLocked || farFocusOn) return
        status.text = when {
            zoomRatio >= TELE_RATIO + 0.4f -> "${formatZoom(zoomRatio)}× digital crop • no extra optical detail"
            zoomRatio >= TELE_RATIO - 0.3f -> "${formatZoom(zoomRatio)}× telephoto range • lens choice is the camera's"
            zoomRatio >= 1.5f -> "${formatZoom(zoomRatio)}× crop of the main sensor"
            else -> "1× wide scene"
        }
    }

    // Logarithmic so the 1×–4× framing range that actually matters gets half the slider
    // travel instead of the ~15% a linear map gives it on a 20× camera.
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
        styleToggle(zoom4, abs(zoomRatio - 4f) < .55f)
        styleToggle(zoom6, abs(zoomRatio - 6f) < .75f)
        styleToggle(zoomMaxButton, maxZoomRatio > 1.1f && abs(zoomRatio - maxZoomRatio) < .45f)
    }

    // ---- exposure ----------------------------------------------------------

    private fun evStep(c: Camera): Float {
        val s = c.cameraInfo.exposureState
        if (!s.isExposureCompensationSupported) return 0f
        val step = s.exposureCompensationStep.toFloat()
        return if (step > 0f) step else 0f
    }

    private fun evIndex(c: Camera, ev: Float): Int? {
        val step = evStep(c)
        if (step <= 0f) return null
        val range = c.cameraInfo.exposureState.exposureCompensationRange
        return (ev / step).roundToInt().coerceIn(range.lower, range.upper)
    }

    /** What the device will really deliver for [ev] after range and step-size clamping. */
    private fun achievedEv(c: Camera, ev: Float): Float {
        val step = evStep(c)
        val index = evIndex(c, ev) ?: return 0f
        return index * step
    }

    private fun applyEvNow(c: Camera, ev: Float) {
        val index = evIndex(c, ev) ?: return
        runCatching { c.cameraControl.setExposureCompensationIndex(index) }
    }

    /**
     * Applies [ev] and waits for CameraX to report the new compensation as actually in effect
     * before running [then]. A blind fixed delay was the previous behaviour and could fire a
     * bracket frame while the sensor was still on the old exposure.
     */
    private fun applyEv(c: Camera, ev: Float, token: Int, then: () -> Unit) {
        val proceed = Runnable { if (token == captureToken) then() }
        val index = evIndex(c, ev)
        if (index == null) {
            handler.postDelayed(proceed, EV_SETTLE_MS)
            return
        }
        val future = runCatching { c.cameraControl.setExposureCompensationIndex(index) }.getOrNull()
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
        // The future normally completes once AE has converged; cap the wait so a HAL that
        // never reports back cannot stall the sequence.
        handler.postDelayed(once, EV_CONVERGE_TIMEOUT_MS)
    }

    private fun bracketEvs(c: Camera?): List<Float> {
        val wanted = listOf(baseEv - 1.5f, baseEv, baseEv + 1.5f)
        if (c == null) return wanted
        val step = evStep(c)
        if (step <= 0f) return listOf(baseEv)
        val range = c.cameraInfo.exposureState.exposureCompensationRange
        return wanted.map { it.coerceIn(range.lower * step, range.upper * step) }
    }

    private fun refreshBracketLabel() {
        if (!::bracketLabel.isInitialized) return
        val c = camera
        if (!bracketOn) {
            bracketLabel.text = "Single exposure at ${formatEv(c?.let { achievedEv(it, baseEv) } ?: baseEv)} EV" +
                " • ${timerSeconds}s anti-shake timer"
            return
        }
        val evs = bracketEvs(c).map { c?.let { cam -> achievedEv(cam, it) } ?: it }
        val distinct = evs.distinct()
        val text = "Bracket: " + distinct.joinToString(" • ") { formatEv(it) } + " EV • ${timerSeconds}s anti-shake timer"
        bracketLabel.text = if (distinct.size < evs.size) {
            "$text  (device EV range limits this to ${distinct.size} distinct exposures)"
        } else {
            text
        }
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
        status.text = if (timerSeconds > 0) "Hold still • ${timerSeconds}s anti-shake timer…" else "Hold still • capturing…"
        val go = Runnable {
            if (token != captureToken) return@Runnable
            val ic = capture
            val c = camera
            if (ic == null || c == null) {
                failCapture("Camera went away before capture")
                return@Runnable
            }
            if (bracketOn) {
                captureBracket(ic, c, bracketEvs(c), 0, mutableListOf(), token)
            } else {
                applyEv(c, baseEv, token) {
                    captureOne(ic, achievedEv(c, baseEv), token, { uris -> finishCapture(uris, 1) }, ::failCapture)
                }
            }
        }
        if (timerSeconds > 0) handler.postDelayed(go, timerSeconds * 1000L) else go.run()
    }

    private fun captureBracket(
        ic: ImageCapture,
        c: Camera,
        evs: List<Float>,
        index: Int,
        saved: MutableList<Uri>,
        token: Int
    ) {
        if (token != captureToken) return
        if (index >= evs.size) {
            applyEvNow(c, baseEv)
            finishCapture(saved, evs.size)
            return
        }
        val ev = evs[index]
        val achieved = achievedEv(c, ev)
        status.text = "Exposure ${index + 1}/${evs.size} • ${formatEv(achieved)} EV"
        applyEv(c, ev, token) {
            captureOne(ic, achieved, token, { uris ->
                saved.addAll(uris)
                handler.postDelayed({ captureBracket(ic, c, evs, index + 1, saved, token) }, INTER_FRAME_MS)
            }, ::failCapture)
        }
    }

    private fun captureOne(
        ic: ImageCapture,
        ev: Float,
        token: Int,
        onSaved: (List<Uri>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (token != captureToken) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val tag = evTag(ev)
        var settled = false
        // Every exit from a frame goes through here exactly once, so no callback ordering,
        // duplicate delivery or missing second file can leave `busy` stuck true.
        fun resolve(block: () -> Unit) {
            if (settled || token != captureToken) return
            settled = true
            cancelWatchdog()
            block()
        }
        armWatchdog(token)
        try {
            if (rawJpeg) {
                val rawOpt = outputOptions("EclipseCam_${stamp}_$tag.dng", "image/x-adobe-dng")
                val jpgOpt = outputOptions("EclipseCam_${stamp}_$tag.jpg", "image/jpeg")
                val uris = mutableListOf<Uri>()
                ic.takePicture(rawOpt, jpgOpt, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        if (settled || token != captureToken) return
                        result.savedUri?.let(uris::add)
                        // RAW+JPEG reports one callback per file, in no guaranteed order.
                        if (uris.size >= 2) {
                            if (!rawProven) {
                                rawProven = true
                                refreshRawBadge()
                            }
                            resolve { onSaved(uris.toList()) }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        val partial = uris.toList()
                        resolve {
                            if (partial.isEmpty()) {
                                onError("RAW+JPEG capture failed: ${exception.message ?: "camera error"}")
                            } else {
                                // One of the two files landed. Keep it, tell the truth, and
                                // let the sequence continue rather than aborting the bracket.
                                rawBadge.text = "RAW PARTIAL"
                                rawBadge.setTextColor(GOLD)
                                onSaved(partial)
                            }
                        }
                    }
                })
            } else {
                val jpgOpt = outputOptions("EclipseCam_${stamp}_$tag.jpg", "image/jpeg")
                ic.takePicture(jpgOpt, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        resolve { onSaved(listOfNotNull(result.savedUri)) }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        resolve { onError("Capture failed: ${exception.message ?: "camera error"}") }
                    }
                })
            }
        } catch (t: Throwable) {
            resolve { onError("Capture rejected: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private fun armWatchdog(token: Int) {
        cancelWatchdog()
        val r = Runnable {
            if (token != captureToken || !busy) return@Runnable
            captureToken++
            busy = false
            setControls(true)
            camera?.let { applyEvNow(it, baseEv) }
            status.text = "Capture timed out • controls released • try again"
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
        camera?.let { applyEvNow(it, baseEv) }
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EclipseCam")
        }
        return ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    }

    private fun finishCapture(uris: List<Uri>, frames: Int) {
        cancelWatchdog()
        busy = false
        setControls(true)
        val expected = if (rawJpeg) frames * 2 else frames
        val mode = if (rawJpeg) "RAW+JPEG" else "JPEG"
        status.text = if (uris.size < expected) {
            "Saved ${uris.size}/$expected files • $mode • ${formatZoom(zoomRatio)}× • check Pictures/EclipseCam"
        } else {
            "Saved $frames exposure${if (frames == 1) "" else "s"} • $mode • ${formatZoom(zoomRatio)}×"
        }
        Toast.makeText(this, "Saved ${uris.size} file${if (uris.size == 1) "" else "s"}", Toast.LENGTH_LONG).show()
    }

    private fun failCapture(message: String) {
        cancelWatchdog()
        busy = false
        setControls(true)
        camera?.let { applyEvNow(it, baseEv) }
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setControls(enabled: Boolean) {
        val ready = camera != null
        shutter.isEnabled = enabled && ready
        bracket.isEnabled = enabled
        timer.isEnabled = enabled
        farFocus.isEnabled = enabled && ready && farFocusSupported
        zoomSeek.isEnabled = enabled && ready
        zoom1.isEnabled = enabled && ready
        zoom2.isEnabled = enabled && ready && maxZoomRatio >= 1.9f
        zoom4.isEnabled = enabled && ready && maxZoomRatio >= 3.9f
        zoom6.isEnabled = enabled && ready && maxZoomRatio >= 5.9f
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

    private fun formatEv(ev: Float) = if (ev > 0) "+%.1f".format(Locale.US, ev) else "%.1f".format(Locale.US, ev)
    private fun formatZoom(z: Float) = if (abs(z - z.roundToInt()) < .05f) z.roundToInt().toString() else "%.1f".format(Locale.US, z)
    private fun evTag(ev: Float) = "EV_${if (ev < 0) "m" else if (ev > 0) "p" else "z"}${"%.1f".format(Locale.US, abs(ev)).replace('.', '_')}"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val GOLD = 0xFFFFD878.toInt()
        private const val GREEN = 0xFF8CE3AF.toInt()
        private const val MUTED = 0xFFBAC3CD.toInt()
        private const val PANEL = 0xFF192028.toInt()

        /** Pixel 6 Pro telephoto native ratio; used only for honest labelling, not lens selection. */
        private const val TELE_RATIO = 4f
        private const val TAP_MAX_MS = 500L
        private const val EV_SETTLE_MS = 250L
        private const val EV_CONVERGE_TIMEOUT_MS = 2500L
        private const val INTER_FRAME_MS = 250L
        private const val CAPTURE_TIMEOUT_MS = 20000L
    }
}
