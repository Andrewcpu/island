package dev.islandcn.island

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        IslandMedia.current(this)?.let { IslandOverlayService.onMedia(it) }
    }

    override fun onListenerDisconnected() {
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        val extras = notification.extras
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }
        if (token is MediaSession.Token) {
            publishMedia(MediaController(this, token))
            return
        }
        val category = notification.category
        val messaging = extras.getParcelableArray(Notification.EXTRA_MESSAGES) != null || category == Notification.CATEGORY_MESSAGE
        if (!messaging) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: sbn.packageName
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isEmpty() && title.isEmpty()) return
        IslandBus.emit(
            mapOf(
                "type" to "message",
                "sender" to title,
                "body" to text,
                "app" to sbn.packageName,
            ),
        )
    }

    private fun publishMedia(controller: MediaController) {
        controls = controller.transportControls
        controller.registerCallback(object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                emitMedia(controller)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emitMedia(controller)
            }
        })
        emitMedia(controller)
    }

    private fun emitMedia(controller: MediaController) {
        controls = controller.transportControls
        val track = IslandMedia.describe(this, controller)
        IslandBus.emit(
            mapOf(
                "type" to "media",
                "title" to track?.title.orEmpty(),
                "artist" to track?.artist.orEmpty(),
                "duration" to (track?.duration ?: 0L),
                "position" to (track?.let { IslandMedia.livePosition(it) } ?: 0L),
                "playing" to (track?.playing == true),
            ),
        )
        IslandOverlayService.onMedia(track)
    }

    companion object {
        var controls: MediaController.TransportControls? = null
        @Volatile var instance: IslandNotificationListener? = null
    }
}
