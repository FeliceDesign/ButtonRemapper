package com.felicedesign.buttonremapper.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.felicedesign.buttonremapper.data.ScreenPoint

/**
 * The floating UI for aiming taps: a draggable crosshair for calibration, and numbered
 * markers for checking where saved points actually are.
 *
 * All windows are `TYPE_ACCESSIBILITY_OVERLAY`:
 *
 *  - that type is granted to accessibility services directly, so none of this needs
 *    SYSTEM_ALERT_WINDOW - unlike the "Launch app" action.
 *  - `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL` lets every touch that is not on our
 *    own views fall through, so you can drive the camera while aiming.
 *  - `FLAG_LAYOUT_NO_LIMITS` *plus* `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` is what puts
 *    the layout params in true display coordinates - the same space `dispatchGesture`
 *    reads. Without the cutout mode the frame starts below the cutout in portrait, so
 *    every calibrated point comes out shifted up by the inset: big targets still get
 *    hit, small ones like a zoom chip do not.
 */
class CalibrationOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var crosshair: CrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var markers = mutableListOf<View>()
    private var listener: ((ScreenPoint?, Boolean) -> Unit)? = null

    // --- calibration -----------------------------------------------------------

    fun show(
        prompt: String,
        allowMore: Boolean,
        cancelLabel: String,
        onResult: (ScreenPoint?, Boolean) -> Unit
    ) {
        hide()
        listener = onResult

        val size = dp(88)
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = overlayParams(size, size).apply {
            x = (bounds.width() - size) / 2
            // Roughly where a shutter button lives, so the first drag is a short one.
            y = bounds.height() - bounds.height() / 5 - size / 2
        }

        val view = CrosshairView(service)
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - downX).toInt()
                    params.y = originY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, params)
                }
            }
            true
        }

        crosshair = view
        crosshairParams = params
        windowManager.addView(view, params)
        addPanel(buildPanel(prompt, allowMore, cancelLabel))
    }

    /** Centre of the crosshair in display coordinates - what the tap will actually hit. */
    private fun currentPoint(): ScreenPoint? {
        val params = crosshairParams ?: return null
        val size = dp(88)
        return ScreenPoint(params.x + size / 2, params.y + size / 2)
    }

    // --- verification ----------------------------------------------------------

    /**
     * Drops a numbered marker on every saved point and offers to fire each one.
     *
     * This is the answer to "the tap does nothing and I cannot see why": look at where
     * the dot sits relative to the button, and press its button to fire a real tap.
     *
     * The markers are `FLAG_NOT_TOUCHABLE` so they never intercept the test tap - but a
     * non-touchable overlay still marks touches below it as obscured, and an app using
     * `setFilterTouchesWhenObscured` would then reject the tap and give a false
     * negative. So a test pulls every window of ours off screen first, fires into a
     * clean stack, and puts them back.
     */
    fun showMarkers(points: List<ScreenPoint>, onTest: (ScreenPoint) -> Unit) {
        hide()
        if (points.isEmpty()) return

        addMarkers(points)
        addPanel(buildMarkerPanel(points, onTest))
    }

    private fun addMarkers(points: List<ScreenPoint>) {
        val size = dp(56)
        points.forEachIndexed { index, point ->
            val params = overlayParams(size, size).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                x = point.x - size / 2
                y = point.y - size / 2
            }
            val view = MarkerView(service, (index + 1).toString())
            markers.add(view)
            windowManager.addView(view, params)
        }
    }

    private fun buildMarkerPanel(points: List<ScreenPoint>, onTest: (ScreenPoint) -> Unit): View =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = panelBackground()

            addView(TextView(service).apply {
                text = "Each dot is a saved point. If a dot is not sitting on the control " +
                    "you meant, re-calibrate. Press a number to fire a real tap there — " +
                    "the overlay hides itself first so the tap arrives unobscured."
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })

            addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                points.forEachIndexed { index, point ->
                    addView(Button(service).apply {
                        text = "${index + 1}"
                        setTextColor(Color.WHITE)
                        setOnClickListener { fireUnobscured(points) { onTest(point) } }
                    })
                }
                addView(Button(service).apply {
                    text = "Close"
                    setTextColor(Color.WHITE)
                    setOnClickListener { hide() }
                })
            })
        }

    /** Clears our windows, fires, then restores them. */
    private fun fireUnobscured(points: List<ScreenPoint>, fire: () -> Unit) {
        val restorePanel = panel
        removeMarkers()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null

        handler.postDelayed({
            fire()
            handler.postDelayed({
                // Only restore if nothing else took over the overlay in the meantime.
                if (panel == null && markers.isEmpty()) {
                    addMarkers(points)
                    restorePanel?.let { addPanel(it) }
                }
            }, RESTORE_DELAY_MS)
        }, SETTLE_DELAY_MS)
    }

    // --- shared ----------------------------------------------------------------

    fun hide(notifyWith: ScreenPoint? = null, more: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        crosshair?.let { runCatching { windowManager.removeView(it) } }
        panel?.let { runCatching { windowManager.removeView(it) } }
        removeMarkers()
        crosshair = null
        crosshairParams = null
        panel = null

        val callback = listener
        listener = null
        callback?.invoke(notifyWith, more)
    }

    private fun removeMarkers() {
        markers.forEach { runCatching { windowManager.removeView(it) } }
        markers.clear()
    }

    private fun addPanel(view: View) {
        panel = view
        windowManager.addView(view, panelParams())
    }

    private fun buildPanel(
        prompt: String,
        allowMore: Boolean,
        cancelLabel: String
    ): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = panelBackground()

        addView(TextView(service).apply {
            text = prompt
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })

        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Button(service).apply {
                text = cancelLabel
                setTextColor(Color.WHITE)
                setOnClickListener { hide() }
            })
            if (allowMore) {
                addView(Button(service).apply {
                    text = "Save + add"
                    setTextColor(Color.WHITE)
                    setOnClickListener { hide(notifyWith = currentPoint(), more = true) }
                })
            }
            addView(Button(service).apply {
                text = if (allowMore) "Save + done" else "Save"
                setTextColor(Color.WHITE)
                setOnClickListener { hide(notifyWith = currentPoint(), more = false) }
            })
        })
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(0xF01B1B1F.toInt())
    }

    /**
     * Anchored to the top, because the shutter is almost always at the other end - and
     * inset from both edges, because a full-width window would swallow touches across
     * the whole top strip, which is exactly where camera apps put flash and settings.
     */
    private fun panelParams() = overlayParams(
        windowManager.currentWindowMetrics.bounds.width() - dp(48),
        WindowManager.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(48)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Without this the frame starts below the cutout and every coordinate we read
        // back is short by the inset. See the class comment.
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        service.resources.displayMetrics
    ).toInt()

    private companion object {
        /** Long enough for our windows to actually leave the screen before we fire. */
        const val SETTLE_DELAY_MS = 250L
        const val RESTORE_DELAY_MS = 900L
    }

    /**
     * Drawn rather than themed: this floats over a live camera preview, so it needs a
     * dark halo under a light stroke to stay visible against both a white sky and a
     * black cave.
     */
    private class CrosshairView(context: android.content.Context) : View(context) {

        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xCC000000.toInt()
            strokeWidth = 7f
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f
        }
        private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - 6f

            canvas.drawCircle(cx, cy, radius * 0.22f, centre)
            for (paint in arrayOf(halo, stroke)) {
                canvas.drawCircle(cx, cy, radius, paint)
                canvas.drawLine(cx - radius, cy, cx - radius * 0.35f, cy, paint)
                canvas.drawLine(cx + radius * 0.35f, cy, cx + radius, cy, paint)
                canvas.drawLine(cx, cy - radius, cx, cy - radius * 0.35f, paint)
                canvas.drawLine(cx, cy + radius * 0.35f, cx, cy + radius, paint)
            }
        }
    }

    /** A numbered dot marking exactly where a saved point will be tapped. */
    private class MarkerView(
        context: android.content.Context,
        private val label: String
    ) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCE53935.toInt()
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 4f
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - 4f

            // A hollow centre so the control underneath stays readable while aiming.
            canvas.drawCircle(cx, cy, radius, ring)
            canvas.drawCircle(cx, cy, radius * 0.45f, fill)
            text.textSize = radius * 0.7f
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }
    }
}
