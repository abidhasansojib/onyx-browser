package com.onyx.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground Service that handles persistent background audio/video playback
 * and presents a rich MediaStyle notification (mini music player) with lockscreen
 * controls, play/pause, scrubbable seekbar, and app relaunch.
 */
class MediaPlaybackService : Service() {

    interface MediaActionListener {
        fun onPlayMedia()
        fun onPauseMedia()
        fun onSeekMedia(deltaSeconds: Int)
        fun onSeekToMedia(positionMs: Long)
        fun onStopMedia()
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var artworkJob: Job? = null
    private var currentArtworkBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OnyxBrowser:MediaWakeLock")
        createNotificationChannel()
        setupMediaSession()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows controls for web audio and video playback"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "OnyxMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    isMediaPlaying = true
                    acquireWakeLock()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    mediaActionListener?.onPlayMedia()
                    updateNotification()
                }

                override fun onPause() {
                    isMediaPlaying = false
                    releaseWakeLock()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    mediaActionListener?.onPauseMedia()
                    updateNotification()
                }

                override fun onSkipToNext() {
                    onFastForward()
                }

                override fun onSkipToPrevious() {
                    onRewind()
                }

                override fun onFastForward() {
                    mediaActionListener?.onSeekMedia(10)
                }

                override fun onRewind() {
                    mediaActionListener?.onSeekMedia(-10)
                }

                override fun onSeekTo(pos: Long) {
                    currentPositionMs = pos
                    updatePlaybackState(
                        if (isMediaPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                    )
                    mediaActionListener?.onSeekToMedia(pos)
                    updateNotification()
                }

                override fun onStop() {
                    isMediaPlaying = false
                    releaseWakeLock()
                    mediaActionListener?.onStopMedia()
                    stopForegroundCompat()
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val artist = intent?.getStringExtra(EXTRA_ARTIST)
        val artworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL)
        val position = intent?.getLongExtra(EXTRA_POSITION, currentPositionMs) ?: currentPositionMs
        val duration = intent?.getLongExtra(EXTRA_DURATION, currentDurationMs) ?: currentDurationMs

        currentPositionMs = position
        currentDurationMs = duration

        if (!title.isNullOrBlank()) currentTitle = title
        if (!artist.isNullOrBlank()) currentArtist = artist

        if (!artworkUrl.isNullOrBlank() && artworkUrl != currentArtworkUrl) {
            currentArtworkUrl = artworkUrl
            loadArtworkAsync(artworkUrl)
        }

        when (action) {
            ACTION_PLAY -> {
                isMediaPlaying = true
                acquireWakeLock()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                mediaActionListener?.onPlayMedia()
                updateNotification()
            }
            ACTION_PAUSE -> {
                isMediaPlaying = false
                releaseWakeLock()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                mediaActionListener?.onPauseMedia()
                updateNotification()
            }
            ACTION_REWIND -> {
                mediaActionListener?.onSeekMedia(-10)
            }
            ACTION_FORWARD -> {
                mediaActionListener?.onSeekMedia(10)
            }
            ACTION_STOP -> {
                isMediaPlaying = false
                releaseWakeLock()
                mediaActionListener?.onStopMedia()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATE -> {
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
                isMediaPlaying = playing
                if (playing) acquireWakeLock() else releaseWakeLock()
                updatePlaybackState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                )
                startForegroundCompat(buildNotification())
            }
            ACTION_UPDATE_PROGRESS -> {
                updatePlaybackState(
                    if (isMediaPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                )
            }
            else -> {
                // Initial start
                if (isMediaPlaying) acquireWakeLock() else releaseWakeLock()
                updatePlaybackState(
                    if (isMediaPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                )
                startForegroundCompat(buildNotification())
            }
        }

        return START_STICKY
    }

    private fun loadArtworkAsync(urlStr: String) {
        artworkJob?.cancel()
        artworkJob = serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val url = URL(urlStr)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        doInput = true
                    }
                    conn.connect()
                    val input = conn.inputStream
                    val original = BitmapFactory.decodeStream(input)
                    input.close()
                    conn.disconnect()

                    // Scale down to max 512x512 to preserve memory
                    if (original != null && (original.width > 512 || original.height > 512)) {
                        val maxDim = maxOf(original.width, original.height)
                        val factor = 512f / maxDim
                        Bitmap.createScaledBitmap(
                            original,
                            (original.width * factor).toInt(),
                            (original.height * factor).toInt(),
                            true
                        )
                    } else {
                        original
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                currentArtworkBitmap = bitmap
                updatePlaybackState(
                    if (isMediaPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                )
                updateNotification()
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, speed, SystemClock.elapsedRealtime())
                .build()
        )

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)

        if (currentDurationMs > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        }

        val art = currentArtworkBitmap ?: try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        if (art != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_REWIND },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isMediaPlaying) {
            val pauseIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(R.drawable.ic_pause, "Pause", pauseIntent).build()
        } else {
            val playIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PLAY },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(R.drawable.ic_play_arrow, "Play", playIntent).build()
        }

        val forwardIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_FORWARD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = currentArtworkBitmap ?: try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_circle)
            .setLargeIcon(largeIcon)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isMediaPlaying)
            .addAction(R.drawable.ic_fast_rewind, "Rewind 10s", rewindIntent)
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_fast_forward, "Forward 10s", forwardIntent)
            .addAction(R.drawable.ic_close, "Close", stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update media notification", e)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min safety timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkJob?.cancel()
        releaseWakeLock()
        isMediaPlaying = false
        mediaSession.isActive = false
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "onyx_media_playback_channel"
        const val NOTIFICATION_ID = 4040

        const val ACTION_PLAY = "com.onyx.browser.action.MEDIA_PLAY"
        const val ACTION_PAUSE = "com.onyx.browser.action.MEDIA_PAUSE"
        const val ACTION_REWIND = "com.onyx.browser.action.MEDIA_REWIND"
        const val ACTION_FORWARD = "com.onyx.browser.action.MEDIA_FORWARD"
        const val ACTION_STOP = "com.onyx.browser.action.MEDIA_STOP"
        const val ACTION_UPDATE_STATE = "com.onyx.browser.action.MEDIA_UPDATE_STATE"
        const val ACTION_UPDATE_PROGRESS = "com.onyx.browser.action.MEDIA_UPDATE_PROGRESS"

        const val EXTRA_TITLE = "extra_media_title"
        const val EXTRA_ARTIST = "extra_media_artist"
        const val EXTRA_ARTWORK_URL = "extra_media_artwork_url"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION = "extra_media_position"
        const val EXTRA_DURATION = "extra_media_duration"

        @Volatile var isMediaPlaying: Boolean = false
        @Volatile var currentTitle: String = "Web Media"
        @Volatile var currentArtist: String = "Onyx Browser"
        @Volatile var currentArtworkUrl: String? = null
        @Volatile var currentPositionMs: Long = 0L
        @Volatile var currentDurationMs: Long = 0L

        var mediaActionListener: MediaActionListener? = null

        fun start(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String? = null,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            isPlaying: Boolean = true
        ) {
            currentTitle = title.ifBlank { "Web Media" }
            currentArtist = artist.ifBlank { "Onyx Browser" }
            currentArtworkUrl = artworkUrl
            currentPositionMs = positionMs
            currentDurationMs = durationMs
            isMediaPlaying = isPlaying

            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                putExtra(EXTRA_TITLE, currentTitle)
                putExtra(EXTRA_ARTIST, currentArtist)
                putExtra(EXTRA_ARTWORK_URL, currentArtworkUrl)
                putExtra(EXTRA_POSITION, currentPositionMs)
                putExtra(EXTRA_DURATION, currentDurationMs)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MediaPlaybackService", e)
            }
        }

        fun updateState(
            context: Context,
            isPlaying: Boolean,
            positionMs: Long = currentPositionMs,
            durationMs: Long = currentDurationMs,
            title: String? = null,
            artist: String? = null,
            artworkUrl: String? = null
        ) {
            isMediaPlaying = isPlaying
            currentPositionMs = positionMs
            currentDurationMs = durationMs
            if (!title.isNullOrBlank()) currentTitle = title
            if (!artist.isNullOrBlank()) currentArtist = artist
            if (!artworkUrl.isNullOrBlank()) currentArtworkUrl = artworkUrl

            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_TITLE, currentTitle)
                putExtra(EXTRA_ARTIST, currentArtist)
                putExtra(EXTRA_ARTWORK_URL, currentArtworkUrl)
                putExtra(EXTRA_POSITION, currentPositionMs)
                putExtra(EXTRA_DURATION, currentDurationMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating MediaPlaybackService state", e)
            }
        }

        fun updateProgress(context: Context, positionMs: Long, durationMs: Long) {
            currentPositionMs = positionMs
            currentDurationMs = durationMs

            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_POSITION, positionMs)
                putExtra(EXTRA_DURATION, durationMs)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updateMetadata(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String?,
            positionMs: Long,
            durationMs: Long
        ) {
            currentTitle = title
            currentArtist = artist
            currentArtworkUrl = artworkUrl
            currentPositionMs = positionMs
            currentDurationMs = durationMs

            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_IS_PLAYING, isMediaPlaying)
                putExtra(EXTRA_TITLE, currentTitle)
                putExtra(EXTRA_ARTIST, currentArtist)
                putExtra(EXTRA_ARTWORK_URL, currentArtworkUrl)
                putExtra(EXTRA_POSITION, currentPositionMs)
                putExtra(EXTRA_DURATION, currentDurationMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            isMediaPlaying = false
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
