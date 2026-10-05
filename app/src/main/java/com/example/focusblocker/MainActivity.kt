package com.example.focusblocker

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "FocusBlockerPrefs"
        const val KEY_BLOCKED_APPS = "blocked_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val btnUsage = Button(this).apply {
            text = "1. Grant Usage Access Permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        val btnOverlay = Button(this).apply {
            text = "2. Grant Display Over Apps Permission"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        val btnStart = Button(this).apply {
            text = "3. Start Focus Service"
            setOnClickListener {
                if (hasUsagePermission() && Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(this@MainActivity, ScreenUsageBlockerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this@MainActivity, "Focus Service Started!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Please grant permissions first!", Toast.LENGTH_LONG).show()
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "4. Stop Focus Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenUsageBlockerService::class.java))
                Toast.makeText(this@MainActivity, "Focus Service Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        val pickerLabel = TextView(this).apply {
            text = "Apps to block:"
            textSize = 18f
            setPadding(0, 40, 0, 20)
        }

        val appListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        for (app in apps) {
            val pkg = app.packageName
            val label = pm.getApplicationLabel(app).toString()
            val checkBox = CheckBox(this).apply {
                text = label
                isChecked = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet())?.contains(pkg) == true
                setOnCheckedChangeListener { _, isChecked ->
                    val current = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet())?.toMutableSet()
                        ?: mutableSetOf()
                    if (isChecked) current.add(pkg) else current.remove(pkg)
                    prefs.edit().putStringSet(KEY_BLOCKED_APPS, current).apply()
                }
            }
            appListLayout.addView(checkBox)
        }

        val appListScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(appListLayout)
        }

        layout.addView(btnUsage)
        layout.addView(btnOverlay)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(pickerLabel)
        layout.addView(appListScroll)

        setContentView(layout)
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
