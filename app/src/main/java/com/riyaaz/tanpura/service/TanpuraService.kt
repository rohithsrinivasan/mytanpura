package com.riyaaz.tanpura.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.riyaaz.tanpura.MainActivity
import com.riyaaz.tanpura.R
import com.riyaaz.tanpura.TanpuraApplication
import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the tanpura playing when the app is not in the foreground, and puts
 * play/pause on the lock screen and in the notification shade.
 *
 * The audio engine itself lives in [PlaybackController] at application scope, so
 * this service owns only the notification and the media session. That is what
 * makes rotating the phone or pressing back harmless to a running drone.
 */
class TanpuraService : Service() {

    companion object {
        private const val TAG = "TanpuraService"
        private const val CHANNEL_ID = "tanpura_playback"
        private const val NOTIFICATION_ID = 41

        const val ACTION_SYNC = "com.riyaaz.tanpura.SYNC"
        const val ACTION_TOGGLE = "com.riyaaz.tanpura.TOGGLE"
        const val ACTION_PLAY = "com.riyaaz.tanpura.PLAY"
        const val ACTION_PAUSE = "com.riyaaz.tanpura.PAUSE"
        const val ACTION_STOP = "com.riyaaz.tanpura.STOP"

        @Volatile
        private var running = false

        /** Called when playback starts; promotes the service to the foreground. */
        fun notifyPlaying(context: Context) {
            val intent = Intent(context, TanpuraService::class.java).setAction(ACTION_SYNC)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the foreground service", e)
            }
        }

        /** Called when playback stops; only touches the service if it is alive. */
        fun notifyPaused(context: Context) {
            if (!running) return
            val intent = Intent(context, TanpuraService::class.java).setAction(ACTION_SYNC)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Background-start restrictions. The notification catches up the
                // next time the service is legitimately started.
                Log.w(TAG, "Could not sync the service", e)
            }
        }
    }

    /** Everything the notification and media session actually display. */
    private data class Shown(
        val playing: Boolean,
        val title: String,
        val subtitle: String,
    )

    private lateinit var controller: PlaybackController
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isForeground = false

    /**
     * Whether [startForeground] has been called at least once. The system gives a
     * service started with `startForegroundService` a few seconds to go foreground
     * or it kills the process, and that deadline applies even if playback was
     * paused again in the meantime.
     */
    private var satisfiedForegroundContract = false

    override fun onCreate() {
        super.onCreate()
        running = true
        controller = (application as TanpuraApplication).controller
        createChannel()

        session = MediaSessionCompat(this, "TanpuraSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = controller.play()
                override fun onPause() = controller.pause()
                override fun onStop() {
                    controller.stop()
                    stopEverything()
                }
            })
            isActive = true
        }

        // Collapsed to just what is displayed, and de-duplicated: otherwise every
        // frame of a volume-slider drag would post a new notification.
        scope.launch {
            combine(controller.transport, controller.settings) { transport, settings ->
                Shown(transport.isPlaying, title(settings), subtitle(settings))
            }
                .distinctUntilChanged()
                .collect { shown ->
                    updateSession(shown)
                    pushNotification(shown)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hardware and headset buttons arrive here.
        MediaButtonReceiver.handleIntent(session, intent)

        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_PLAY -> controller.play()
            ACTION_PAUSE -> controller.pause()
            ACTION_STOP -> {
                controller.stop()
                stopEverything()
                return START_NOT_STICKY
            }
        }

        pushNotification(currentShown())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not cut off a practice session that is
        // deliberately running in the background, so playback is left alone.
        super.onTaskRemoved(rootIntent)
    }

    private fun currentShown(): Shown {
        val settings = controller.settings.value
        return Shown(
            playing = controller.transport.value.isPlaying,
            title = title(settings),
            subtitle = subtitle(settings),
        )
    }

    private fun stopEverything() {
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun createChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateSession(shown: Shown) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, shown.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, shown.subtitle)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (shown.playing) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    },
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build()
        )
    }

    private fun title(settings: TanpuraSettings): String {
        val sa = Pitch.noteName(settings.saMidi)
        val cents = if (settings.fineCents != 0f) " ${Pitch.formatCents(settings.fineCents)}" else ""
        return "Sa = $sa$cents"
    }

    private fun subtitle(settings: TanpuraSettings): String = when (settings.mode) {
        EngineMode.LOOP -> settings.loopName ?: "Imported recording"
        EngineMode.SAMPLES -> "${settings.voice.label} · your recordings"
        EngineMode.SYNTH -> "${settings.voice.label} · ${settings.pattern.label}"
    }

    private fun buildNotification(shown: Shown): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIntent = servicePendingIntent(ACTION_TOGGLE, 1)
        val stopIntent = servicePendingIntent(ACTION_STOP, 2)

        val toggleAction = NotificationCompat.Action(
            if (shown.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (shown.playing) R.string.action_pause else R.string.action_play),
            toggleIntent,
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_stop),
            stopIntent,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(shown.title)
            .setContentText(shown.subtitle)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(shown.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TanpuraService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pushNotification(shown: Shown) {
        val notification = buildNotification(shown)

        if (shown.playing) {
            if (isForeground) {
                notificationManager()?.notify(NOTIFICATION_ID, notification)
            } else {
                goForeground(notification)
            }
            return
        }

        // Paused. If playback stopped before we ever went foreground, we still owe
        // the system a startForeground() call, so make it and immediately step
        // back down rather than being killed for missing the deadline.
        if (!satisfiedForegroundContract) {
            goForeground(notification)
        }
        if (isForeground) {
            // Detach rather than remove: the notification stays so the drone can
            // be resumed without reopening the app.
            stopForegroundCompat(removeNotification = false)
        }
        notificationManager()?.notify(NOTIFICATION_ID, notification)
    }

    private fun goForeground(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            satisfiedForegroundContract = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground was rejected", e)
        }
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun stopForegroundCompat(removeNotification: Boolean) {
        stopForeground(
            if (removeNotification) Service.STOP_FOREGROUND_REMOVE else Service.STOP_FOREGROUND_DETACH
        )
        isForeground = false
        if (removeNotification) notificationManager()?.cancel(NOTIFICATION_ID)
    }
}
