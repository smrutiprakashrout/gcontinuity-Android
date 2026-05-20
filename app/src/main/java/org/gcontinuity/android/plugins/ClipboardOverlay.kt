package org.gcontinuity.android.plugins

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floating overlay button shown above all apps when clipboard content changes.
 *
 * Displays "📋 Send to Linux" in the bottom-right corner.
 * Auto-dismisses after [AUTO_DISMISS_MS] (3 seconds) if not tapped.
 * If tapped, calls [onSend] immediately and dismisses.
 *
 * Requires [android.provider.Settings.canDrawOverlays] to be true — checked before showing.
 * Falls back silently (no crash) if permission is not granted.
 *
 * All view operations are posted to the main thread so this class is safe
 * to call from any coroutine or background thread.
 */
class ClipboardOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null

    companion object {
        private const val TAG = "ClipboardOverlay"
        private const val AUTO_DISMISS_MS = 3000L
    }

    /**
     * Show the "Send to Linux" overlay button.
     *
     * @param onSend Called when the user taps the button. Overlay is dismissed
     * before [onSend] is invoked.
     */
    fun show(onSend: () -> Unit) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay skipped")
                return@post
            }

            // Dismiss any existing overlay before showing a new one.
            dismissInternal()

            val view = createOverlayView(onSend)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = 24.dpToPx()
                y = 120.dpToPx()   // above navigation bar / gesture area
            }

            try {
                windowManager.addView(view, params)
                overlayView = view
                Log.d(TAG, "Overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view: ${e.message}")
                return@post
            }

            // Schedule auto-dismiss after 3 s.
            val runnable = Runnable { dismissInternal() }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
        }
    }

    /** Dismiss the overlay immediately. Safe to call when not showing. */
    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    /** Called when the service is stopping — clean up any lingering view. */
    fun destroy() {
        mainHandler.post {
            dismissInternal()
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun dismissInternal() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
        }
        overlayView = null
    }

    private fun createOverlayView(onSend: () -> Unit): View {
        // Use a pure framework FrameLayout with GradientDrawable to avoid CardView dependencies.
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(0xFF1A1A2E.toInt())
                cornerRadius = 24f.dpToPx()
            }
            elevation = 8f.dpToPx()

            val pad = 12.dpToPx()
            setPadding(pad * 2, pad, pad * 2, pad)

            setOnClickListener {
                dismissInternal()
                onSend()
            }
        }

        val label = TextView(context).apply {
            text = "📋  Send to Linux"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 0)
        }

        container.addView(label)
        return container
    }

    // Utility extensions for handling UI scaling
    private fun Int.dpToPx(): Int =
        (this * context.resources.displayMetrics.density).toInt()

    private fun Float.dpToPx(): Float =
        this * context.resources.displayMetrics.density
}