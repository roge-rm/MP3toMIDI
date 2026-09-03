package com.rm.mp3tomidi.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rm.mp3tomidi.MainActivity
import com.rm.mp3tomidi.player.PlaybackSource

/**
 * Hosts one persistent [PlaybackFacadePlayer] + [MediaSession] for the app's whole playback
 * lifetime, giving whichever of the MP3 preview or MIDI playback is currently active a real
 * system media notification (progress bar, transport controls, lock-screen row) for free -- see
 * [PlaybackFacadePlayer]'s doc for why this is one persistent facade rather than two sessions or
 * a swapped player.
 *
 * Started via a plain [Context.startService] (not `startForegroundService`) from
 * [setActiveSource], matching media3's own documented pattern: `MediaSessionService.
 * onStartCommand`'s doc explicitly describes this as "called when a component calls
 * Context#startService(Intent)". The framework promotes itself to a real foreground service (and
 * shows the notification) automatically once the player actually has a [MediaItem] -- calling
 * `startForegroundService` instead would risk an ANR-style crash if that took more than the
 * platform's ~5 second `startForeground()` deadline.
 */
class PlaybackNotificationService : MediaSessionService() {

    private lateinit var player: PlaybackFacadePlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = PlaybackFacadePlayer(mainLooper)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        // Building a MediaSession alone publishes a real platform-level session (discoverable by
        // Bluetooth/media-button routing) but does NOT wire up MediaSessionService's own
        // notification machinery -- addSession() is what registers the session with
        // MediaNotificationManager and attaches the listener that actually posts/updates the
        // notification. The framework only calls this automatically when a real external
        // MediaController binds through onBind()/onGetSession(); this service drives its session
        // directly without ever going through that path, so it must be called explicitly here.
        addSession(mediaSession)

        instance = this
        pendingSource?.let { player.setActiveSource(it) }
        pendingSource = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        mediaSession.run {
            player.release()
            release()
        }
        instance = null
        super.onDestroy()
    }

    companion object {
        // Set on the live instance once the service exists; stashed here in the (usually brief)
        // window between starting the service and its onCreate() actually running.
        private var instance: PlaybackNotificationService? = null
        private var pendingSource: PlaybackSource? = null

        fun setActiveSource(context: Context, source: PlaybackSource) {
            val running = instance
            if (running != null) {
                running.player.setActiveSource(source)
            } else {
                pendingSource = source
                context.startService(Intent(context, PlaybackNotificationService::class.java))
            }
        }
    }
}
