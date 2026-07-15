package com.sweak.qralarm.alarm.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.sweak.qralarm.BuildConfig
import com.sweak.qralarm.R
import com.sweak.qralarm.alarm.ALARM_NOTIFICATION_CHANNEL_ID
import com.sweak.qralarm.alarm.QRAlarmManager
import com.sweak.qralarm.alarm.activity.AlarmActivity
import com.sweak.qralarm.alarm.util.setAlarmVolume
import com.sweak.qralarm.alarm.util.setAlarmVolumeToMax
import com.sweak.qralarm.core.designsystem.theme.Jacarta
import com.sweak.qralarm.core.domain.alarm.Alarm
import com.sweak.qralarm.core.domain.alarm.AlarmsRepository
import com.sweak.qralarm.core.domain.alarm.DisableAlarm
import com.sweak.qralarm.core.domain.alarm.SetAlarm
import com.sweak.qralarm.core.domain.user.UserDataRepository
import com.sweak.qralarm.core.ui.sound.AlarmRingtonePlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AlarmService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Inject lateinit var alarmsRepository: AlarmsRepository
    @Inject lateinit var qrAlarmManager: QRAlarmManager
    @Inject lateinit var disableAlarm: DisableAlarm
    @Inject lateinit var setAlarm: SetAlarm
    @Inject lateinit var alarmRingtonePlayer: AlarmRingtonePlayer
    @Inject lateinit var audioManager: AudioManager
    @Inject lateinit var userDataRepository: UserDataRepository

    private lateinit var alarm: Alarm

    private var temporaryAlarmMuteJob: Job? = null
    private var emergencyTaskAlarmMuteJob: Job? = null
    private var hasAlarmBeenAlreadyTemporarilyMuted = false
    private var originalSystemAlarmVolume: Int? = null
    private var originalRingerMode: Int? = null
    private var protectedAlarmVolume: Int? = null
    private var specialSettingsGuardJob: Job? = null
    private var doNotLeaveAlarmGuardJob: Job? = null
    private var isFaceWakeMuted = false

    private val temporaryAlarmMuteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (hasAlarmBeenAlreadyTemporarilyMuted) {
                return
            } else {
                hasAlarmBeenAlreadyTemporarilyMuted = true
                emergencyTaskAlarmMuteJob?.cancel()
            }

            serviceScope.launch(Dispatchers.Main) {
                alarmRingtonePlayer.stop()

                val muteDurationSeconds = intent?.getIntExtra(
                    EXTRA_TEMPORARY_MUTE_DURATION_SECONDS,
                    DEFAULT_TEMPORARY_MUTE_DURATION_SECONDS
                ) ?: DEFAULT_TEMPORARY_MUTE_DURATION_SECONDS

                temporaryAlarmMuteJob = serviceScope.launch(Dispatchers.Main) {
                    delay(muteDurationSeconds.seconds)
                    startAlarm()
                }.also {
                    it.invokeOnCompletion { temporaryAlarmMuteJob = null }
                }
            }
        }
    }

    private val emergencyTaskAlarmMuteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            temporaryAlarmMuteJob?.cancel()
            emergencyTaskAlarmMuteJob?.cancel()

            serviceScope.launch(Dispatchers.Main) {
                alarmRingtonePlayer.stop()

                emergencyTaskAlarmMuteJob = serviceScope.launch(Dispatchers.Main) {
                    delay(EMERGENCY_TASK_ALARM_MUTE_DURATION_SECONDS.seconds)
                    startAlarm()
                }.also {
                    it.invokeOnCompletion { emergencyTaskAlarmMuteJob = null }
                }
            }
        }
    }

    private val faceWakeAlarmMuteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isFacePresent = intent?.getBooleanExtra(EXTRA_FACE_WAKE_FACE_PRESENT, false) == true

            serviceScope.launch(Dispatchers.Main) {
                if (isFacePresent && !isFaceWakeMuted) {
                    temporaryAlarmMuteJob?.cancel()
                    emergencyTaskAlarmMuteJob?.cancel()
                    alarmRingtonePlayer.stop()
                    isFaceWakeMuted = true
                } else if (!isFacePresent && isFaceWakeMuted) {
                    isFaceWakeMuted = false
                    startAlarm()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        var shouldStopService = false
        val alarmId = intent.extras?.getLong(EXTRA_ALARM_ID).run {
            if (this == null) {
                shouldStopService = true
                return@run 0
            } else {
                return@run this
            }
        }

        try {
            ServiceCompat.startForeground(
                this,
                alarmId.toInt(),
                createAlarmNotification(alarmId = alarmId),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0
            )
        } catch (exception: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                exception is ForegroundServiceStartNotAllowedException
            ) {
                shouldStopService = true
            } else {
                throw exception
            }
        }

        serviceScope.launch {
            val isSnoozeAlarm = intent.extras?.getBoolean(EXTRA_IS_SNOOZE_ALARM)

            if (shouldStopService) {
                userDataRepository.setAlarmMissedDetected(detected = true)
                stopForegroundAndCancelNotification(alarmId)
                return@launch
            }

            alarmsRepository.getAlarm(alarmId = alarmId)?.let {
                alarm = it
                currentAlarmId = alarmId
                if (isAbnormalLaunch(alarm, isSnoozeAlarm)) {
                    stopForegroundAndCancelNotification(alarmId)
                    return@launch
                }
            } ?: run {
                stopForegroundAndCancelNotification(alarmId)
                return@launch
            }

            // The service is a singleton, so if a previous alarm is still ringing tear down first.
            if (isRunning) {
                withContext(Dispatchers.Main) {
                    cleanUpPreviousAlarmState()
                }
            }

            isRunning = true
            resetFaceWakeProgress(alarmId)

            ContextCompat.registerReceiver(
                this@AlarmService,
                temporaryAlarmMuteReceiver,
                IntentFilter(ACTION_TEMPORARY_ALARM_MUTE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            ContextCompat.registerReceiver(
                this@AlarmService,
                emergencyTaskAlarmMuteReceiver,
                IntentFilter(ACTION_EMERGENCY_TASK_ALARM_MUTE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            ContextCompat.registerReceiver(
                this@AlarmService,
                faceWakeAlarmMuteReceiver,
                IntentFilter(ACTION_FACE_WAKE_ALARM_MUTE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            if (isSnoozeAlarm == false) {
                resetAvailableSnoozes()
            }

            alarmsRepository.setAlarmRunning(
                alarmId = alarmId,
                running = true
            )
            alarmsRepository.setAlarmSnoozed(
                alarmId = alarmId,
                snoozed = false
            )

            handleAlarmRescheduling()

            adjustAlarmVolume()
            startSpecialSettingsGuard()
            startDoNotLeaveAlarmGuard()

            withContext(Dispatchers.Main) {
                launchAlarmActivity(alarmId)
                startAlarm()
            }
        }

        return START_NOT_STICKY
    }

    private fun launchAlarmActivity(alarmId: Long) {
        startActivity(
            Intent(applicationContext, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    @SuppressLint("FullScreenIntentPolicy")
    private fun createAlarmNotification(alarmId: Long): Notification {
        val alarmNotificationPendingIntent = PendingIntent.getActivity(
            applicationContext,
            alarmId.toInt(),
            Intent(applicationContext, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmFullScreenPendingIntent = PendingIntent.getActivity(
            applicationContext,
            alarmId.toInt(),
            Intent(applicationContext, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(
            applicationContext,
            ALARM_NOTIFICATION_CHANNEL_ID
        ).apply {
            color = Jacarta.toArgb()
            priority = NotificationCompat.PRIORITY_MAX
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setOngoing(true)
            setColorized(true)
            setContentTitle(getString(R.string.alarm_notification_title))
            setSmallIcon(R.drawable.ic_qralarm)
            setContentIntent(alarmNotificationPendingIntent)
            setFullScreenIntent(alarmFullScreenPendingIntent, true)
            return build()
        }
    }

    private fun stopForegroundAndCancelNotification(alarmId: Long) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        qrAlarmManager.cancelUpcomingAlarmNotification(alarmId = alarmId)
    }

    private fun isAbnormalLaunch(alarm: Alarm, isSnoozeAlarm: Boolean?): Boolean {
        val scheduledTimeInMillis = when {
            isSnoozeAlarm == true -> alarm.snoozeConfig.nextSnoozedAlarmTimeInMillis
            else -> alarm.nextAlarmTimeInMillis
        }
        return scheduledTimeInMillis != null &&
            System.currentTimeMillis() - scheduledTimeInMillis > ABNORMAL_LAUNCH_TOLERANCE_MS
    }

    private suspend fun resetAvailableSnoozes() {
        alarmsRepository.setAvailableSnoozes(
            alarmId = alarm.alarmId,
            availableSnoozes = alarm.snoozeConfig.snoozeMode.numberOfSnoozes
        )
    }

    private suspend fun handleAlarmRescheduling() {
        if (alarm.repeatingMode is Alarm.RepeatingMode.Once) {
            disableAlarm(alarmId = alarm.alarmId)
        } else if (alarm.repeatingMode is Alarm.RepeatingMode.Days) {
            setAlarm(
                alarmId = alarm.alarmId,
                isReschedulingMissedAlarm = false
            )
        }
    }

    private fun adjustAlarmVolume() {
        val alarmVolumeMode = alarm.alarmVolumeMode
        originalSystemAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        if (alarmVolumeMode is Alarm.AlarmVolumeMode.Custom) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val minVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_ALARM)
            } else 0
            val volumeRange = maxVolume - minVolume
            val volumePercentage = alarmVolumeMode.volumePercentage
            val volumeLevel = (minVolume + (volumeRange * (volumePercentage / 100.0))).toInt()
                .coerceAtLeast(minVolume + 1)

            audioManager.setAlarmVolume(volumeLevel)
        }

        if (BuildConfig.DEBUG) {
            audioManager.setAlarmVolumeToMax()
        }

        protectedAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    }

    private fun startSpecialSettingsGuard() {
        if (!BuildConfig.DEBUG) {
            return
        }

        originalRingerMode = audioManager.ringerMode
        specialSettingsGuardJob?.cancel()
        specialSettingsGuardJob = serviceScope.launch {
            while (true) {
                enforceSpecialSettings()
                delay(SPECIAL_SETTINGS_GUARD_INTERVAL_MS)
            }
        }
    }

    private fun startDoNotLeaveAlarmGuard() {
        if (!BuildConfig.DEBUG) {
            return
        }

        doNotLeaveAlarmGuardJob?.cancel()
        doNotLeaveAlarmGuardJob = serviceScope.launch(Dispatchers.Main) {
            while (true) {
                delay(DO_NOT_LEAVE_ALARM_GUARD_INTERVAL_MS)
                if (isRunning && ::alarm.isInitialized) {
                    bringAlarmActivityToFront(alarm.alarmId)
                }
            }
        }
    }

    private fun bringAlarmActivityToFront(alarmId: Long) {
        startActivity(
            Intent(applicationContext, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun enforceSpecialSettings() {
        if (!BuildConfig.DEBUG) {
            return
        }

        try {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (_: SecurityException) { /* Do Not Disturb access may be required. */ }

        protectedAlarmVolume?.let { protectedVolume ->
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != protectedVolume) {
                audioManager.setAlarmVolume(protectedVolume)
            }
        }
    }

    private fun startAlarm() {
        if (alarm.ringtone == Alarm.Ringtone.CUSTOM_SOUND) {
            if (alarm.customRingtoneUriString != null) {
                alarmRingtonePlayer.playAlarmRingtone(
                    alarmRingtoneUri = alarm.customRingtoneUriString!!.toUri(),
                    volumeIncreaseSeconds = alarm.gentleWakeUpDurationInSeconds
                )
            } else {
                alarmRingtonePlayer.playAlarmRingtone(
                    ringtone = Alarm.Ringtone.GENTLE_GUITAR,
                    volumeIncreaseSeconds = alarm.gentleWakeUpDurationInSeconds
                )
            }
        } else {
            alarmRingtonePlayer.playAlarmRingtone(
                ringtone = alarm.ringtone,
                volumeIncreaseSeconds = alarm.gentleWakeUpDurationInSeconds
            )
        }

        if (alarm.areVibrationsEnabled) {
            alarmRingtonePlayer.startVibration(alarm.gentleWakeUpDurationInSeconds)
        }
    }

    private fun cleanUpPreviousAlarmState() {
        temporaryAlarmMuteJob?.cancel()
        temporaryAlarmMuteJob = null
        emergencyTaskAlarmMuteJob?.cancel()
        emergencyTaskAlarmMuteJob = null
        specialSettingsGuardJob?.cancel()
        specialSettingsGuardJob = null
        doNotLeaveAlarmGuardJob?.cancel()
        doNotLeaveAlarmGuardJob = null
        hasAlarmBeenAlreadyTemporarilyMuted = false
        isFaceWakeMuted = false
        clearFaceWakeProgress(alarm.alarmId)

        try {
            unregisterReceiver(temporaryAlarmMuteReceiver)
            unregisterReceiver(emergencyTaskAlarmMuteReceiver)
            unregisterReceiver(faceWakeAlarmMuteReceiver)
        } catch (_: IllegalArgumentException) { /* no-op */ }

        alarmRingtonePlayer.stop()

        originalSystemAlarmVolume?.let {
            audioManager.setAlarmVolume(it)
        }
        originalSystemAlarmVolume = null
        originalRingerMode?.let { originalMode ->
            try {
                audioManager.ringerMode = originalMode
            } catch (_: SecurityException) { /* Do Not Disturb access may be required. */ }
        }
        originalRingerMode = null
        protectedAlarmVolume = null
    }

    override fun onDestroy() {
        isRunning = false

        temporaryAlarmMuteJob?.cancel()
        emergencyTaskAlarmMuteJob?.cancel()
        specialSettingsGuardJob?.cancel()
        doNotLeaveAlarmGuardJob?.cancel()

        try {
            unregisterReceiver(temporaryAlarmMuteReceiver)
            unregisterReceiver(emergencyTaskAlarmMuteReceiver)
            unregisterReceiver(faceWakeAlarmMuteReceiver)
        } catch (_: IllegalArgumentException) { /* no-op */ }

        alarmRingtonePlayer.apply {
            stop()
            onDestroy()
        }

        originalSystemAlarmVolume?.let {
            audioManager.setAlarmVolume(it)
        }

        originalRingerMode?.let {
            try {
                audioManager.ringerMode = it
            } catch (_: SecurityException) { /* Do Not Disturb access may be required. */ }
        }

        serviceScope.cancel()

        currentAlarmId = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_IS_SNOOZE_ALARM = "isSnoozeAlarm"

        const val ACTION_TEMPORARY_ALARM_MUTE = "com.sweak.qralarm.TEMPORARY_ALARM_MUTE"
        const val EXTRA_TEMPORARY_MUTE_DURATION_SECONDS = "muteDurationSeconds"
        const val DEFAULT_TEMPORARY_MUTE_DURATION_SECONDS = 15

        const val ACTION_EMERGENCY_TASK_ALARM_MUTE = "com.sweak.qralarm.EMERGENCY_TASK_ALARM_MUTE"
        const val EMERGENCY_TASK_ALARM_MUTE_DURATION_SECONDS = 10

        const val ACTION_FACE_WAKE_ALARM_MUTE = "com.sweak.qralarm.FACE_WAKE_ALARM_MUTE"
        const val EXTRA_FACE_WAKE_FACE_PRESENT = "facePresent"

        var isRunning = false
        var currentAlarmId: Long? = null
        private val faceWakeProgressSecondsByAlarmId = mutableMapOf<Long, Int>()

        @Synchronized
        fun getFaceWakeProgressSeconds(alarmId: Long): Int {
            return faceWakeProgressSecondsByAlarmId[alarmId] ?: 0
        }

        @Synchronized
        fun setFaceWakeProgressSeconds(alarmId: Long, seconds: Int) {
            faceWakeProgressSecondsByAlarmId[alarmId] = seconds.coerceAtLeast(0)
        }

        @Synchronized
        fun resetFaceWakeProgress(alarmId: Long) {
            faceWakeProgressSecondsByAlarmId[alarmId] = 0
        }

        @Synchronized
        fun clearFaceWakeProgress(alarmId: Long) {
            faceWakeProgressSecondsByAlarmId.remove(alarmId)
        }

        private const val ABNORMAL_LAUNCH_TOLERANCE_MS = 10 * 60 * 1000L
        private const val SPECIAL_SETTINGS_GUARD_INTERVAL_MS = 500L
        private const val DO_NOT_LEAVE_ALARM_GUARD_INTERVAL_MS = 350L
    }
}
