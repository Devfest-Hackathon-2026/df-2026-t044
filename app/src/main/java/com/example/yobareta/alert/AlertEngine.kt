package com.example.yobareta.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.yobareta.MainActivity
import com.example.yobareta.R
import com.example.yobareta.model.RecognizeAlertResult

/**
 * Multi-tier haptic & smartwatch notification engine:
 * - "possible": 2 short pulses (surname-only match; never fires a full alert).
 * - "called": repeating long buzz + high-priority watch-mirrored notification ("You were called → Exam Room 3").
 * - "recall": strongest urgent repeating buzz + watch-mirrored notification.
 * - Network drop: loud distinct alert so the app never fails silently.
 */
class AlertEngine(private val context: Context) {

    companion object {
        const val ALERT_CHANNEL_ID = "yobareta_call_alerts_v1"
        const val NETWORK_WARN_CHANNEL_ID = "yobareta_network_warn_v1"
        private const val CALL_NOTIFICATION_ID = 2001
        private const val NETWORK_NOTIFICATION_ID = 2002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        val callChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Waiting Room Call Alerts (Watch Mirrored)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "High-priority alerts mirrored to smartwatch when your name or number is called"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 800)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val netChannel = NotificationChannel(
            NETWORK_WARN_CHANNEL_ID,
            "Network Drop Warnings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts immediately if network connectivity drops while listening"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 500)
        }

        notificationManager.createNotificationChannel(callChannel)
        notificationManager.createNotificationChannel(netChannel)
    }

    fun triggerAlert(result: RecognizeAlertResult) {
        when (result.tier) {
            "possible" -> {
                // 2 short pulses for surname-only / medium score
                vibrateWaveform(
                    timings = longArrayOf(0, 180, 120, 180),
                    amplitudes = intArrayOf(0, 180, 0, 180),
                    repeatIndex = -1
                )
                postWatchMirroredNotification(
                    title = "Possible Call (${result.matchedTrigger})",
                    body = result.instructionPlain.ifEmpty { "Surname matched — listen/check board" },
                    isFullCall = false
                )
            }

            "called" -> {
                // Repeating long buzz (3 cycles of long pulses)
                vibrateWaveform(
                    timings = longArrayOf(0, 650, 220, 650, 220, 900, 350, 650, 220, 900),
                    amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255),
                    repeatIndex = -1
                )
                val headline = result.instructionPlain.ifEmpty {
                    if (result.destination.isNotEmpty()) {
                        "You were called → ${result.destination}"
                    } else {
                        "You were called!"
                    }
                }
                postWatchMirroredNotification(
                    title = headline,
                    body = buildString {
                        if (result.destination.isNotEmpty()) append("Destination: ${result.destination}\n")
                        if (result.transcriptMasked.isNotEmpty()) append(result.transcriptMasked)
                    }.trim(),
                    isFullCall = true
                )
            }

            "recall" -> {
                // Strongest urgent pattern for re-call
                vibrateWaveform(
                    timings = longArrayOf(
                        0, 850, 150, 850, 150, 1000, 200,
                        850, 150, 850, 150, 1100
                    ),
                    amplitudes = intArrayOf(
                        0, 255, 0, 255, 0, 255, 0,
                        255, 0, 255, 0, 255
                    ),
                    repeatIndex = -1
                )
                val headline = result.instructionPlain.ifEmpty {
                    "RE-CALL: You were called → ${result.destination.ifEmpty { "Please check counter" }}"
                }
                postWatchMirroredNotification(
                    title = headline,
                    body = "URGENT RE-CALL • ${result.transcriptMasked}",
                    isFullCall = true
                )
            }
        }
    }

    fun triggerNetworkDropWarning() {
        vibrateWaveform(
            timings = longArrayOf(0, 350, 120, 350, 120, 600),
            amplitudes = intArrayOf(0, 255, 0, 255, 0, 255),
            repeatIndex = -1
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            102,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NETWORK_WARN_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WARNING: Network Connection Lost")
            .setContentText("Yobareta cannot verify announcements while offline!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NETWORK_NOTIFICATION_ID, notification)
    }

    fun stopVibration() {
        try {
            vibrator.cancel()
        } catch (_: Exception) {
        }
    }

    private fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeatIndex: Int) {
        try {
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(timings, amplitudes, repeatIndex)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else {
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }

    private fun postWatchMirroredNotification(title: String, body: String, isFullCall: Boolean) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isFullCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isFullCall) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (isFullCall) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        notificationManager.notify(CALL_NOTIFICATION_ID, builder.build())
    }
}
