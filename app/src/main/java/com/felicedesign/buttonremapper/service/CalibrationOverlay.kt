package com.felicedesign.buttonremapper.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 * A draggable crosshair you park on the button you want the key to press.
 *
 * Two windows, both `TYPE_ACCESSIBILITY_OVERLAY`:
 *
 *  - that type is granted to accessibility services directly, so calibration needs no
 *    SYSTEM_ALERT_WINDOW - unlike the "Launch app" action.
 *  - both are `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`, so every touch that is not
 *    on the crosshair or the panel falls through to the app underneath. You can open
 *    the camera, switch to video, and frame a shot with the crosshair still floating.
 *  - `FLAG_LAYOUT_NO_LIMITS` puts the layout params in raw display coordinates, which
 *    is the same space `dispatchGesture` reads. Without it the status bar inset would
 *    silently shift every calibrated point upward.
 */
class CalibrationOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)

    private var crosshair: CrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var listener: ((ScreenPoint?) -> Unit)? = null

    fun show(onResult: (ScreenPoint?) -> Unit) {
        if (crosshair != null) hide(notifyWith = null)
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
        windowManager.addView(buildPanel().also { panel = it }, panelParams())
    }

    fun hide(notifyWith: ScreenPoint? = null) {
        crosshair?.let { runCatching { windowManager.removeView(it) } }
        panel?.let { runCatching { windowManager.removeView(it) } }
        crosshair = null
        crosshairParams = null
        panel = null

        val callback = listener
        listener = null
        callback?.invoke(notifyWith)
    }

    /** Centre of the crosshair in display coordinates - what the tap will actually hit. */
    private fun currentPoint(): ScreenPoint? {
        val params = crosshairParams ?: return null
        val size = dp(88)
        return ScreenPoint(params.x + size / 2, params.y + size / 2)
    }

    private fun buildPanel(): View = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xF01B1B1F.toInt())
        }

        addView(TextView(service).apply {
            text = "Open the app you want to control, drag the crosshair onto the " +
                "button, then Save. Everything outside the crosshair still works."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })

        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Button(service).apply {
                text = "Cancel"
                setTextColor(Color.WHITE)
                setOnClickListener { hide(notifyWith = null) }
            })
            addView(Button(service).apply {
                text = "Save"
                setTextColor(Color.WHITE)
                setOnClickListener { hide(notifyWith = currentPoint()) }
            })
        })
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
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        service.resources.displayMetrics
    ).toInt()

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
}
