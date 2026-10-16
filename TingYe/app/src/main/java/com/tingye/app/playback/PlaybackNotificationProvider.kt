package com.tingye.app.playback

import android.app.Notification
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Preserve Media3's session token/actions, including asynchronous cover updates. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackNotificationProvider(private val delegate: MediaNotification.Provider) : MediaNotification.Provider by delegate {
    private fun publicPlayback(media: MediaNotification): MediaNotification {
        val notification = media.notification
        notification.category = Notification.CATEGORY_TRANSPORT
        notification.visibility = Notification.VISIBILITY_PUBLIC
        notification.publicVersion = notification.clone().apply { publicVersion = null }
        return media
    }
    override fun createNotification(session: MediaSession, buttons: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification =
        publicPlayback(delegate.createNotification(session, buttons, actionFactory) {
            callback.onNotificationChanged(publicPlayback(it))
        })
}
