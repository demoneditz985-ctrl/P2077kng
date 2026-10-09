package com.shadow.injector

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tiny draggable "SHADOW ● <target>" chip drawn on top of the game after a successful
 * injection. This is what the SYSTEM_ALERT_WINDOW permission is used for.
 * Tap it to dismiss.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_PACKAGE = "extra_package"
    }

    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "INJECTED"
        val existing = overlay
        if (existing == null) {
            show(label)
        } else {
            existing.findViewById<TextView>(R.id.overlayText)?.text = "SHADOW ● $label"
        }
        return START_STICKY
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun show(label: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_status, null)
        view.findViewById<TextView>(R.id.overlayText).text = "SHADOW ● $label"

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 110
        }

        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).roundToInt()
                    val dy = (event.rawY - startRawY).roundToInt()
                    if (abs(dx) > 6 || abs(dy) > 6) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { overlay?.let { wm.updateViewLayout(it, params) } }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) v.performClick()
                    true
                }

                else -> false
            }
        }
        view.setOnClickListener { stopSelf() }

        runCatching {
            wm.addView(view, params)
            windowManager = wm
            overlay = view
        }.onFailure { stopSelf() }
    }

    override fun onDestroy() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        windowManager = null
        super.onDestroy()
    }
}
