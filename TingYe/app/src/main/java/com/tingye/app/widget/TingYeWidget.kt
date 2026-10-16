package com.tingye.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tingye.app.MainActivity
import com.tingye.app.R
import com.tingye.app.TingYeApp
import com.tingye.app.loadWidgetCover
import com.tingye.app.data.ListeningPreferences
import com.tingye.app.playback.PlaybackService
import com.tingye.app.playback.PlaybackStatus
import com.tingye.app.playback.libraryMediaItems
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WidgetSnapshot(val bookId: Long? = null, val chapter: String = "", val playing: Boolean = false,
    val timer: String = "")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TingYeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = renderAsync(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = renderAsync(context)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REFRESH -> renderAsync(context)
            TOGGLE, PREVIOUS, NEXT -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                    var controller: MediaController? = null
                    try {
                        withTimeout(8000) {
                            val app = context.applicationContext as TingYeApp
                            // ReceiverRestrictedContext cannot bind a media service.
                            val future = MediaController.Builder(app, SessionToken(app,
                                ComponentName(app, PlaybackService::class.java))).buildAsync()
                            controller = suspendCancellableCoroutine { continuation ->
                                future.addListener({
                                    try {
                                        val connected = future.get()
                                        if (continuation.isActive) continuation.resume(connected) else connected.release()
                                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                                }, ContextCompat.getMainExecutor(app))
                                continuation.invokeOnCancellation { future.cancel(false) }
                            }
                            val c = controller!!
                            if (c.mediaItemCount == 0) {
                                val book = app.library.dao.latestListening() ?: return@withTimeout
                                val chapters = app.library.dao.chapters(book.id)
                                if (chapters.isEmpty()) return@withTimeout
                                chapters.forEach { PlaybackStatus.chapterProgress.seed("${book.id}:${it.id}", it.positionMs) }
                                val settings = ListeningPreferences(app).values.first()
                                val index = chapters.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 }
                                    ?: book.chapterIndex.coerceIn(0, chapters.lastIndex)
                                c.setMediaItems(libraryMediaItems(book, chapters), index,
                                    (book.positionMs - settings.resumeBack * 1000L).coerceAtLeast(0))
                                c.setPlaybackSpeed(book.speedOverride ?: settings.defaultSpeed)
                                c.prepare()
                            }
                            when (intent.action) {
                                TOGGLE -> if (c.playWhenReady) c.pause() else c.play()
                                PREVIOUS -> if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
                                NEXT -> if (c.hasNextMediaItem()) c.seekToNextMediaItem()
                            }
                            // Keep the connection until asynchronous controller commands reach the session.
                            delay(450)
                        }
                    } catch (error: Exception) { android.util.Log.w("TingYeWidget", "Playback command failed", error) }
                    finally { controller?.release(); withContext(Dispatchers.IO) { render(context) }; pending.finish() }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
    private fun renderAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { render(context) } finally { pending.finish() }
        }
    }
    companion object {
        const val REFRESH = "com.tingye.app.widget.REFRESH"
        const val TOGGLE = "com.tingye.app.widget.TOGGLE"
        const val PREVIOUS = "com.tingye.app.widget.PREVIOUS"
        const val NEXT = "com.tingye.app.widget.NEXT"
        fun refresh(context: Context) {
            if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TingYeWidget::class.java)).isNotEmpty())
                context.sendBroadcast(Intent(context, TingYeWidget::class.java).setAction(REFRESH))
        }
        private suspend fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TingYeWidget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as TingYeApp
            val snapshot = PlaybackStatus.widget.value
            val book = snapshot.bookId?.let { app.library.dao.book(it) } ?: app.library.dao.latestListening()
            val chapter = if (snapshot.bookId == book?.id && snapshot.chapter.isNotEmpty()) snapshot.chapter
                else book?.let { app.library.dao.chapters(it.id).let { list ->
                    list.firstOrNull { it.id == book.chapterId }?.title ?: list.getOrNull(book.chapterIndex)?.title
                } } ?: "从书架导入一本书"
            val open = PendingIntent.getActivity(context, 70, Intent(context, MainActivity::class.java)
                .setAction(PlaybackService.ACTION_OPEN_PLAYER), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.listening_widget)
            views.setTextViewText(R.id.widget_title, book?.title ?: "听页 · 让故事继续")
            views.setTextViewText(R.id.widget_chapter, chapter)
            views.setTextViewText(R.id.widget_timer, snapshot.timer.ifEmpty { if (snapshot.playing) "正在收听" else "点按继续收听" })
            views.setImageViewResource(R.id.widget_play, if (snapshot.playing) R.drawable.widget_pause else R.drawable.widget_play)
            views.setContentDescription(R.id.widget_play, if (snapshot.playing) "暂停" else "播放")
            val cover = book?.coverUri?.let { loadWidgetCover(context, it) }
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover)
            else views.setImageViewResource(R.id.widget_cover, R.drawable.ic_notification)
            views.setOnClickPendingIntent(R.id.widget_header, open)
            listOf(R.id.widget_play to TOGGLE, R.id.widget_previous to PREVIOUS, R.id.widget_next to NEXT).forEachIndexed { index, (view, action) ->
                val command = if (book == null) open else PendingIntent.getBroadcast(context, 80 + index,
                    Intent(context, TingYeWidget::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(view, command)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
