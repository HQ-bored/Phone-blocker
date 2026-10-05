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
    private val NAG_RESHOW_MS = 10 * 1000L
    private val handler = Handler(Looper.getMainLooper())

    private var lastForegroundApp: String? = null
    private var nagSessionActive = false
    private var lastOverlayRemovedAt: Long = 0L

    private fun getBlockedApps(): Set<String> =
        getSharedPreferences("FocusBlockerPrefs", MODE_PRIVATE)
            .getStringSet("blocked_apps", emptySet()) ?: emptySet()

    private fun updateForegroundApp() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 10000, time)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundApp = event.packageName
            }
        }
    }

    private var isOverlayShowing = false
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnTime = SystemClock.elapsedRealtime()
                    nagSessionActive = false
                    lastOverlayRemovedAt = 0L
                    lastForegroundApp = null
                    handler.post(checkUsageRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOnTime = 0L
                    nagSessionActive = false
                    lastOverlayRemovedAt = 0L
                    handler.removeCallbacks(checkUsageRunnable)
                    removeOverlay()
                    updateNotification()
                }
            }
        }
    }

    private val checkUsageRunnable = object : Runnable {
        override fun run() {
            if (screenOnTime > 0) {
                updateForegroundApp()
                val elapsedTime = SystemClock.elapsedRealtime() - screenOnTime
                if (!nagSessionActive && elapsedTime >= BLOCK_THRESHOLD_MS &&
                    getBlockedApps().contains(lastForegroundApp)) {
                    nagSessionActive = true
                }
                if (nagSessionActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (isOverlayShowing) {
                        if (lastForegroundApp != null && !getBlockedApps().contains(lastForegroundApp)) {
                            removeOverlay()
                        }
                    } else if (lastOverlayRemovedAt == 0L || now - lastOverlayRemovedAt >= NAG_RESHOW_MS) {
                        showBlockOverlay("Take a break! You've been on your phone for 15+ minutes.")
                    }
                }
                updateNotification()
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

    private fun updateNotification() {
        val now = SystemClock.elapsedRealtime()
        val text = when {
            nagSessionActive -> "Break time! Put the phone down."
            screenOnTime > 0 -> {
                val remainingMs = (BLOCK_THRESHOLD_MS - (now - screenOnTime)).coerceAtLeast(0L)
                val minutes = remainingMs / 60000
                val seconds = (remainingMs % 60000) / 1000
                "Pop-up in %02d:%02d".format(minutes, seconds)
            }
            else -> "Monitoring screen time..."
        }
        val notification = NotificationCompat.Builder(this, "FocusBlockerChannel")
            .setContentTitle("Focus Blocker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
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
            lastOverlayRemovedAt = SystemClock.elapsedRealtime()
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
