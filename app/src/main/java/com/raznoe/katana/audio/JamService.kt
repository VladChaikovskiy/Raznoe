package com.raznoe.katana.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.raznoe.katana.MainActivity
import com.raznoe.katana.R

/**
 * Keeps the jam track playing when the app is not on screen.
 *
 * Without a foreground service Android is free to freeze or kill the process
 * the moment the Activity goes away — which is exactly what happens when you
 * put the phone down and pick up the guitar. The service owns no playback
 * state; [JamPlayer] does, and this only carries the notification that buys the
 * process the right to keep running (plus transport buttons on the lock screen).
 */
class JamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = Jam.peek()
        when (intent?.action) {
            ACTION_TOGGLE -> player?.togglePlayPause()
            ACTION_STOP -> {
                player?.stop()
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        // Becoming a foreground service is not optional: once something called
        // startForegroundService the system gives us a few seconds to post the
        // notification or it kills the process. If we cannot (Android 12+
        // refuses a background start), stand down at once — that is the
        // documented way out, and playback itself is unaffected.
        val promoted = startForegroundSafely(player)
        val wanted = player != null && (player.isPlaying || player.waitingForRoute)
        if (!promoted || !wanted) stopSelfSafely()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startForegroundSafely(player: JamPlayer?): Boolean = runCatching {
        ensureChannel(this)
        val n = buildNotification(this, player)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }.isSuccess

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    companion object {
        private const val CHANNEL_ID = "jam"
        private const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE = "com.raznoe.katana.JAM_TOGGLE"
        const val ACTION_STOP = "com.raznoe.katana.JAM_STOP"

        /**
         * Bring the notification in line with the player's state: run while
         * something is playing (or held waiting for a route to come back),
         * stand down otherwise.
         *
         * Every call is best-effort. Android 12+ refuses to let an app start a
         * foreground service from the background, and that refusal must never
         * take playback down with it — the track keeps playing, it just loses
         * the notification until the app is on screen again.
         */
        fun sync(context: Context, player: JamPlayer) {
            val wanted = player.isPlaying || player.waitingForRoute
            val intent = Intent(context, JamService::class.java)
            runCatching {
                if (wanted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID, "Минусовка", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Воспроизведение минусовки для джема"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, player: JamPlayer?): Notification {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), flags,
            )
            val toggle = PendingIntent.getService(
                context, 1, Intent(context, JamService::class.java).setAction(ACTION_TOGGLE), flags,
            )
            val stop = PendingIntent.getService(
                context, 2, Intent(context, JamService::class.java).setAction(ACTION_STOP), flags,
            )
            val playing = player?.isPlaying == true
            val title = player?.trackName?.takeIf { it.isNotBlank() } ?: "Минусовка"
            val text = player?.status?.takeIf { it.isNotBlank() }
                ?: if (playing) "Играет" else "Пауза"
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_jam)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(0, if (playing) "Пауза" else "Играть", toggle)
                .addAction(0, "Стоп", stop)
                .build()
        }
    }
}
