package com.sweak.qralarm.alarm.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sweak.qralarm.BuildConfig
import com.sweak.qralarm.alarm.activity.AlarmActivity
import com.sweak.qralarm.alarm.service.AlarmService
import com.sweak.qralarm.alarm.util.setAlarmVolumeToMax

class QRAlarmAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPowerMenuGuardRunning = false
    private var powerGuardArmedUntilMillis = 0L
    private var powerGuardOverlayView: View? = null
    private val restoreAlarmScreenRunnable = Runnable {
        if (BuildConfig.DEBUG && AlarmService.isRunning) {
            bringAlarmScreenToFront()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!BuildConfig.DEBUG || !AlarmService.isRunning) {
            hidePowerGuardOverlay()
            return
        }

        val packageName = event?.packageName?.toString() ?: return
        if (isSystemUiPackage(packageName)) {
            if (isPowerMenuVisible()) {
                dismissPowerMenuIfVisible()
            } else if (isNotificationShadeVisible()) {
                dismissNotificationShadeAndRestoreAlarm()
            }
            return
        }

        if (packageName == applicationContext.packageName) {
            cancelAlarmScreenRestore()
        } else {
            scheduleAlarmScreenRestore(
                if (isSensitiveEscapePackage(packageName)) {
                    ALARM_SCREEN_FAST_RESTORE_DELAY_MS
                } else {
                    ALARM_SCREEN_RESTORE_DELAY_MS
                }
            )
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!BuildConfig.DEBUG || !AlarmService.isRunning) {
            return super.onKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                setAlarmVolumeToMax()
                armPowerMenuGuard()
                true
            }
            KeyEvent.KEYCODE_POWER -> {
                armPowerMenuGuard()
                true
            }
            else -> super.onKeyEvent(event)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hidePowerGuardOverlay()
        super.onDestroy()
    }

    private fun bringAlarmScreenToFront() {
        val alarmId = AlarmService.currentAlarmId ?: return
        startActivity(
            Intent(this, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun scheduleAlarmScreenRestore(delayMillis: Long) {
        mainHandler.removeCallbacks(restoreAlarmScreenRunnable)
        mainHandler.postDelayed(restoreAlarmScreenRunnable, delayMillis)
    }

    private fun cancelAlarmScreenRestore() {
        mainHandler.removeCallbacks(restoreAlarmScreenRunnable)
    }

    private fun setAlarmVolumeToMax() {
        (getSystemService(AUDIO_SERVICE) as AudioManager).setAlarmVolumeToMax()
    }

    private fun dismissPowerMenuIfVisible() {
        if (!isPowerMenuVisible()) {
            return
        }

        showPowerGuardOverlay()
        dismissSystemUiAndRestoreAlarm()
    }

    private fun dismissNotificationShadeAndRestoreAlarm() {
        repeat(NOTIFICATION_SHADE_DISMISS_ATTEMPTS) { attempt ->
            mainHandler.postDelayed(
                {
                    if (BuildConfig.DEBUG &&
                        AlarmService.isRunning &&
                        isNotificationShadeVisible()
                    ) {
                        dismissNotificationShade()
                    }

                    if (attempt == NOTIFICATION_SHADE_DISMISS_ATTEMPTS - 1 &&
                        !AlarmActivity.isAlarmSubScreenActive
                    ) {
                        bringAlarmScreenToFront()
                    }
                },
                attempt * NOTIFICATION_SHADE_DISMISS_INTERVAL_MS
            )
        }
    }

    private fun dismissNotificationShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else if (!AlarmActivity.isAlarmSubScreenActive) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun dismissSystemUiAndRestoreAlarm() {
        if (isPowerMenuGuardRunning) {
            return
        }

        isPowerMenuGuardRunning = true
        showPowerGuardOverlay()
        repeat(POWER_MENU_GUARD_ATTEMPTS) { attempt ->
            mainHandler.postDelayed(
                {
                    if (BuildConfig.DEBUG &&
                        AlarmService.isRunning &&
                        isPowerMenuVisible()
                    ) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }

                    if (attempt == POWER_MENU_GUARD_ATTEMPTS - 1) {
                        isPowerMenuGuardRunning = false
                        hidePowerGuardOverlay()
                        bringAlarmScreenToFront()
                    }
                },
                attempt * POWER_MENU_GUARD_INTERVAL_MS
            )
        }
    }

    private fun armPowerMenuGuard() {
        powerGuardArmedUntilMillis = System.currentTimeMillis() + POWER_GUARD_ARM_DURATION_MS
        showPowerGuardOverlay()
        mainHandler.postDelayed(
            { if (!isPowerMenuGuardRunning) hidePowerGuardOverlay() },
            POWER_GUARD_ARM_DURATION_MS
        )

        if (isPowerMenuGuardRunning) {
            return
        }

        repeat(POWER_MENU_GUARD_ATTEMPTS) { attempt ->
            mainHandler.postDelayed(
                {
                    if (BuildConfig.DEBUG && AlarmService.isRunning && isPowerGuardArmed()) {
                        dismissPowerMenuIfVisible()
                    }
                },
                POWER_MENU_CHECK_DELAY_MS + (attempt * POWER_MENU_GUARD_INTERVAL_MS)
            )
        }
    }

    private fun showPowerGuardOverlay() {
        if (powerGuardOverlayView != null) {
            return
        }

        val overlayView = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(overlayView, layoutParams)
            powerGuardOverlayView = overlayView
        } catch (_: Exception) { /* Overlay is best-effort; BACK guard still runs. */ }
    }

    private fun hidePowerGuardOverlay() {
        val overlayView = powerGuardOverlayView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlayView)
        } catch (_: Exception) { /* no-op */ }
        powerGuardOverlayView = null
    }

    private fun isPowerGuardArmed(): Boolean {
        return System.currentTimeMillis() <= powerGuardArmedUntilMillis
    }

    private fun isSystemUiPackage(packageName: String): Boolean {
        return packageName == "com.android.systemui" ||
            packageName.endsWith(".systemui")
    }

    private fun isSensitiveEscapePackage(packageName: String): Boolean {
        return packageName == "com.android.settings" ||
            packageName.contains("launcher", ignoreCase = true) ||
            packageName.contains("packageinstaller", ignoreCase = true)
    }

    private fun isPowerMenuVisible(): Boolean {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.containsAnyText(POWER_MENU_TEXT_MARKERS) == true) {
            return true
        }

        return windows.any { window ->
            window.root?.containsAnyText(POWER_MENU_TEXT_MARKERS) == true
        }
    }

    private fun isNotificationShadeVisible(): Boolean {
        val activeRoot = rootInActiveWindow
        if (activeRoot?.containsAnyText(NOTIFICATION_SHADE_TEXT_MARKERS) == true) {
            return true
        }

        return windows.any { window ->
            window.root?.containsAnyText(NOTIFICATION_SHADE_TEXT_MARKERS) == true
        }
    }

    private fun AccessibilityNodeInfo.containsAnyText(markers: List<String>): Boolean {
        val nodeText = listOfNotNull(text, contentDescription)
            .joinToString(separator = " ")
            .lowercase()

        if (markers.any { marker -> nodeText.contains(marker) }) {
            return true
        }

        for (index in 0 until childCount) {
            val child = getChild(index) ?: continue
            if (child.containsAnyText(markers)) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val POWER_GUARD_ARM_DURATION_MS = 3_000L
        private const val POWER_MENU_GUARD_ATTEMPTS = 80
        private const val POWER_MENU_GUARD_INTERVAL_MS = 20L
        private const val POWER_MENU_CHECK_DELAY_MS = 0L
        private const val NOTIFICATION_SHADE_DISMISS_ATTEMPTS = 8
        private const val NOTIFICATION_SHADE_DISMISS_INTERVAL_MS = 70L
        private const val ALARM_SCREEN_FAST_RESTORE_DELAY_MS = 150L
        private const val ALARM_SCREEN_RESTORE_DELAY_MS = 1_000L
        private val POWER_MENU_TEXT_MARKERS = listOf(
            "power off",
            "restart",
            "reboot",
            "lockdown",
            "shut down",
            "turn off"
        )
        private val NOTIFICATION_SHADE_TEXT_MARKERS = listOf(
            "silent notifications",
            "notification settings",
            "clear all",
            "quick settings",
            "internet",
            "bluetooth",
            "do not disturb",
            "screen record",
            "battery saver",
            "airplane mode"
        )

        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${QRAlarmAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)

            return splitter.any { it.equals(expectedServiceName, ignoreCase = true) }
        }
    }
}
