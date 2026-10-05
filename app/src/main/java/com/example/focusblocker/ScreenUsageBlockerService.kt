package com.example.focusblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class ScreenUsageBlockerService : Service() {

    private var screenOnTime: Long = 0L
    private val BLOCK_THRESHOLD_MS = 15 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())

    private fun getBlockedApps(): Set<String> =
        getSharedPreferences("FocusBlockerPrefs", MODE_PRIVATE)
            .getStringSet("blocked_apps", emptySet()) ?: emptySet()

    private var isOverlayShowing = false
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnTime = SystemClock.elapsedRealtime()
                    handler.post(checkUsageRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOnTime = 0L
                    handler.removeCallbacks(checkUsageRunnable)
                    removeOverlay()
                }
            }
        }
    }

    private val checkUsageRunnable = object : Runnable {
        override fun run() {
            if (screenOnTime > 0) {
                val elapsedTime = SystemClock.elapsedRealtime() - screenOnTime
                if (elapsedTime >= BLOCK_THRESHOLD_MS) {
                    val foregroundApp = getForegroundPackageName()
                    if (getBlockedApps().contains(foregroundApp)) {
                        showBlockOverlay("Take a break! You've been on your phone for 15+ minutes.")
                    } else {
                        removeOverlay()
                    }
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        screenOnTime = SystemClock.elapsedRealtime()
        handler.post(checkUsageRunnable)
    }

    private fun startForegroundService() {
        val channelId = "FocusBlockerChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Focus Blocker Active",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Focus Blocker")
            .setContentText("Monitoring screen time...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1, notification)
    }

    private fun getForegroundPackageName(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 5000, time)
        val event = UsageEvents.Event()
        var currentApp: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentApp = event.packageName
            }
        }
        return currentApp
    }

    private fun showBlockOverlay(message: String) {
        if (isOverlayShowing) return

        overlayView = TextView(this).apply {
            text = message
            textSize = 22f
            setBackgroundColor(0xFF000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(overlayView, params)
        isOverlayShowing = true
    }

    private fun removeOverlay() {
        if (isOverlayShowing && overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
            isOverlayShowing = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        handler.removeCallbacks(checkUsageRunnable)
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
