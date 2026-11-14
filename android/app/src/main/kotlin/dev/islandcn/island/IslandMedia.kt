package dev.islandcn.island

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService

/** The live media session. Never substitutes a placeholder title. */
object IslandMedia {
    data class Track(val title: String, val artist: String, val playing: Boolean)

    fun current(context: Context): Track? {
        val fromListener = IslandNotificationListener.instance?.let { sessionsFrom(it) }
        if (fromListener != null) return fromListener
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val component = ComponentName(context, IslandNotificationListener::class.java)
            pick(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            null
        }
    }

    private fun sessionsFrom(service: NotificationListenerService): Track? {
        val controllers = mutableListOf<MediaController>()
        val notes = service.activeNotifications ?: emptyArray()
        for (sbn in notes) {
            val extras = sbn.notification.extras
            val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION)
            }
            if (token is android.media.session.MediaSession.Token) {
                controllers.add(MediaController(service, token))
            }
        }
        if (controllers.isEmpty()) {
            return try {
                val manager = service.getSystemService(MediaSessionManager::class.java) ?: return null
                pick(manager.getActiveSessions(ComponentName(service, IslandNotificationListener::class.java)))
            } catch (_: SecurityException) {
                null
            }
        }
        return pick(controllers)
    }

    private fun pick(controllers: List<MediaController>): Track? {
        data class Hit(val track: Track, val controls: MediaController.TransportControls)
        val tracks = controllers.mapNotNull { controller ->
            val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            val paused = controller.playbackState?.state == PlaybackState.STATE_PAUSED
            if (!playing && !paused) return@mapNotNull null
            Hit(Track(title, artist, playing), controller.transportControls)
        }
        val chosen = tracks.firstOrNull { it.track.playing } ?: tracks.firstOrNull() ?: return null
        IslandNotificationListener.controls = chosen.controls
        return chosen.track
    }
}
