package com.tingye.app.playback

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.tingye.app.MainActivity
import com.tingye.app.R
import com.tingye.app.TingYeApp
import com.tingye.app.data.ListeningPreferences
import com.tingye.app.data.ListeningSettings
import com.tingye.app.data.ListeningTimeTracker
import com.tingye.app.data.sleepVolumeFraction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import com.tingye.app.widget.TingYeWidget
import com.tingye.app.widget.WidgetSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TimerUi(val mode: String = "none", val remainingMs: Long = 0,
    val bookId: Long? = null, val chapterId: Long? = null, val notice: String? = null,
    val chaptersRemaining: Int = 0, val fadeSeconds: Int = 30) {
    val active get() = mode != "none"
}

/** The service owns both the real player and timer. This flow is only a view of service state. */
object PlaybackStatus {
    val timer = MutableStateFlow(TimerUi())
    val progressUndo = MutableStateFlow<ProgressUndo?>(null)
    val chapterProgress = ChapterProgressMemory()
    val widget = MutableStateFlow(WidgetSnapshot())
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()
    private var pendingSave: Job? = null
    private val listeningClock = ListeningTimeTracker()
    private val listeningPending = mutableMapOf<String, Long>()
    private var listeningSettings = ListeningSettings()
    private val leveling = LevelingAudioProcessor()
    private val progressUndo = ProgressUndoMemory(SystemClock::elapsedRealtime) { PlaybackStatus.progressUndo.value = it }
    private var timerDeadline: Long? = null
    private var timerChaptersRemaining = 0
    private var timerFadeMs = 30_000L
    private var timerBaseVolume = 1f
    private var timerBookId: Long? = null
    private var timerChapterId: Long? = null
    private var introMs = 0L
    private var outroMs = 0L
    private var globalIntroMs = 0L
    private var globalOutroMs = 0L
    private var introAppliedMediaId: String? = null
    private var pendingIntroMediaId: String? = null
    private var completedByOutro = false
    private var completedChapterId: String? = null
    private var suppressManualEnd = false
    private val wakeChecks = Channel<Unit>(Channel.CONFLATED)
    private val recoveryStore by lazy { getSharedPreferences("playback_recovery", MODE_PRIVATE) }
    private var restartSnapshot: Map<String, *> = emptyMap<String, Any>()
    private var restartHandled = false
    private var lastAccounting = 0L
    private var lastSave = 0L
    private var lastTimerPublish = 0L
    private fun bootCount() = android.provider.Settings.Global.getInt(contentResolver,
        android.provider.Settings.Global.BOOT_COUNT, -1)

    private fun checkpoint(active: Boolean = player.playWhenReady && player.playerError == null &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        player.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
        val pair = ids(player.currentMediaItem?.mediaId) ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val stamp = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val deadline = timerDeadline ?: -1L
        val chapters = timerChaptersRemaining
        val fade = timerFadeMs
        queueWrite {
            recoveryStore.edit().putBoolean("active", active).putLong("book", pair.first)
                .putLong("chapter", pair.second).putLong("position", position)
                .putLong("elapsed", stamp).putInt("boot", boot).putLong("deadline", deadline)
                .putInt("chapters", chapters).putLong("fade", fade).commit()
        }
    }

    private suspend fun resumption(snapshot: Map<String, *>? = null, applySpeed: Boolean = true): MediaSession.MediaItemsWithStartPosition {
        val settings = ListeningPreferences(this).values.first()
        val (book, chapters) = withContext(Dispatchers.IO) {
            val dao = (application as TingYeApp).library.dao
            val book = if (snapshot != null) dao.book(snapshot["book"] as? Long ?: error("无效的恢复记录"))
                else dao.latestListening()
            checkNotNull(book) { "没有可恢复的书籍" }
            book to dao.chapters(book.id)
        }
        check(chapters.isNotEmpty()) { "没有可恢复的章节" }
        val chapterId = (snapshot?.get("chapter") as? Long) ?: book.chapterId
        val index = chapters.indexOfFirst { it.id == chapterId }.takeIf { it >= 0 }
            ?: book.chapterIndex.coerceIn(0, chapters.lastIndex)
        val position = ((snapshot?.get("position") as? Long) ?: book.positionMs).coerceAtLeast(0)
            .let { if ((chapters[index].durationMs ?: 0) > 0) it.coerceAtMost(chapters[index].durationMs!!) else it }
        chapters.forEach { PlaybackStatus.chapterProgress.seed("${book.id}:${it.id}", it.positionMs) }
        if (applySpeed && player.mediaItemCount == 0) player.setPlaybackSpeed(book.speedOverride ?: settings.defaultSpeed)
        return MediaSession.MediaItemsWithStartPosition(libraryMediaItems(book, chapters), index, position)
    }

    private fun recoverRestart() {
        if (restartHandled) return
        restartHandled = true
        val saved = restartSnapshot
        val deadline = (saved["deadline"] as? Long)?.takeIf { it > 0 }
        if (!canRecoverPlayback(saved["active"] == true, saved["boot"] as? Int ?: -2,
                bootCount(), saved["elapsed"] as? Long ?: 0, SystemClock.elapsedRealtime(), deadline)) {
            if (player.mediaItemCount == 0) stopSelf()
            return
        }
        scope.launch {
            try {
                val restored = resumption(saved)
                if (player.mediaItemCount != 0) return@launch
                if (deadline != null && SystemClock.elapsedRealtime() >= deadline) { stopSelf(); return@launch }
                timerDeadline = deadline
                timerChaptersRemaining = saved["chapters"] as? Int ?: 0
                timerFadeMs = saved["fade"] as? Long ?: 30_000L
                timerBookId = if (deadline != null || timerChaptersRemaining > 0) saved["book"] as? Long else null
                timerChapterId = if (timerChaptersRemaining > 0) saved["chapter"] as? Long else null
                player.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
                player.prepare()
                player.play()
                publishTimer()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.w("TingYe", "Playback recovery failed", error)
                recoveryStore.edit().putBoolean("active", false).apply()
                stopSelf()
            }
        }
    }

    private fun ids(mediaId: String?): Pair<Long, Long>? {
        val parts = mediaId?.split(':') ?: return null
        if (parts.size != 2) return null
        return Pair(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null)
    }

    private fun save(mediaId: String?, index: Int, position: Long, complete: Boolean = false) {
        val (bookId, chapterId) = ids(mediaId) ?: return
        val safePosition = if (complete) 0L else position.coerceAtLeast(0)
        // Keep the completion boundary until ExoPlayer replaces the old item.
        // Otherwise its final duration position can overwrite the saved zero.
        if (complete) completedChapterId = mediaId
        PlaybackStatus.chapterProgress.record(mediaId!!, safePosition)
        // Preserve event order on IO. The mutex must never wait for a main-loop
        // continuation while onDestroy flushes positions synchronously.
        queueWrite {
                val dao = (application as TingYeApp).library.dao
                if (dao.book(bookId) != null) dao.saveProgress(bookId, chapterId, index.coerceAtLeast(0),
                    safePosition, if (complete) 2 else 1)
        }
    }

    private fun queueWrite(block: suspend () -> Unit) {
        val previous = pendingSave
        pendingSave = writer.launch { previous?.join(); saveMutex.withLock { block() } }
    }

    private fun accountListening(playing: Boolean = player.isPlaying, flush: Boolean = false) {
        listeningClock.update(System.currentTimeMillis(), SystemClock.elapsedRealtime(), playing).forEach {
            listeningPending[it.day] = (listeningPending[it.day] ?: 0L) + it.durationMs
        }
        if (flush && listeningPending.isNotEmpty()) {
            val batch = listeningPending.toMap()
            listeningPending.clear()
            queueWrite {
                val dao = (application as TingYeApp).library.dao
                batch.forEach { (day, duration) -> dao.addListeningTime(day, duration) }
            }
        }
    }

    private fun publishWidget() {
        val timer = PlaybackStatus.timer.value
        val snapshot = WidgetSnapshot(ids(player.currentMediaItem?.mediaId)?.first,
            player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty(), player.isPlaying,
            when (timer.mode) {
                "minutes" -> "${(timer.remainingMs + 59_999) / 60_000} 分钟后停止"
                "chapter" -> "${timer.chaptersRemaining} 章后停止"
                else -> ""
            })
        if (snapshot != PlaybackStatus.widget.value) {
            PlaybackStatus.widget.value = snapshot
            TingYeWidget.refresh(this)
        }
    }

    private fun saveCurrent(complete: Boolean = false) = save(player.currentMediaItem?.mediaId,
        player.currentMediaItemIndex, player.currentPosition, complete)

    private fun cancelTimer(notice: String? = null) {
        player.volume = timerBaseVolume
        timerDeadline = null
        timerChaptersRemaining = 0
        timerBookId = null
        timerChapterId = null
        PlaybackStatus.timer.value = TimerUi(notice = notice)
        publishWidget()
        wakeChecks.trySend(Unit)
    }

    private fun publishTimer() {
        val deadline = timerDeadline
        PlaybackStatus.timer.value = when {
            deadline != null -> TimerUi("minutes", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), timerBookId,
                fadeSeconds = (timerFadeMs / 1000).toInt())
            timerChaptersRemaining > 0 -> TimerUi("chapter", bookId = timerBookId, chapterId = timerChapterId,
                chaptersRemaining = timerChaptersRemaining, fadeSeconds = (timerFadeMs / 1000).toInt())
            else -> TimerUi()
        }
        publishWidget()
        wakeChecks.trySend(Unit)
    }

    private fun timerChapterFinished(): Boolean {
        if (timerChaptersRemaining <= 0) return false
        timerChaptersRemaining--
        if (timerChaptersRemaining == 0) {
            player.pause()
            cancelTimer()
            return true
        }
        timerChapterId = ids(player.currentMediaItem?.mediaId)?.second
        publishTimer()
        return false
    }

    private fun applyTimerFade(now: Long) {
        val remaining = timerDeadline?.let { it - now } ?: if (timerChaptersRemaining == 1 && player.duration > 0) {
            val end = player.duration - if (player.duration > introMs + outroMs + 5_000) outroMs else 0L
            ((end - player.currentPosition).coerceAtLeast(0) / player.playbackParameters.speed).toLong()
        } else Long.MAX_VALUE
        player.volume = if (player.isPlaying) timerBaseVolume * sleepVolumeFraction(remaining, timerFadeMs)
            else timerBaseVolume
    }

    private fun applyIntroIfNeeded() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState != Player.STATE_READY || introAppliedMediaId == mediaId) return
        introAppliedMediaId = mediaId
        val duration = player.duration
        if (introMs > 0 && duration > introMs + outroMs + 5_000 && player.currentPosition < introMs) {
            player.seekTo(introMs)
        }
        ids(mediaId)?.second?.let { chapterId ->
            if (duration > 0) scope.launch(Dispatchers.IO) {
                (application as TingYeApp).library.dao.saveDuration(chapterId, duration)
            }
        }
    }

    private fun loadSkipSettings(mediaId: String?, newBoundary: Boolean = false) {
        val bookId = ids(mediaId)?.first ?: return
        scope.launch {
            val book = withContext(Dispatchers.IO) { (application as TingYeApp).library.dao.book(bookId) }
            if (ids(player.currentMediaItem?.mediaId)?.first != bookId) return@launch
            introMs = (book?.introOverride?.toLong() ?: globalIntroMs / 1000) * 1000
            outroMs = (book?.outroOverride?.toLong() ?: globalOutroMs / 1000) * 1000
            if (newBoundary && pendingIntroMediaId == mediaId) {
                pendingIntroMediaId = null
                introAppliedMediaId = null
                applyIntroIfNeeded()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setForegroundServiceTimeoutMs(DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS)
        restartSnapshot = recoveryStore.all.toMap()
        PlaybackStatus.timer.value = TimerUi()
        progressUndo.clear()
        player = ExoPlayer.Builder(this, LevelingRenderersFactory(this, leveling)).setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA).build(), true)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        val restoreId = PlaybackStatus.progressUndo.value?.point?.mediaId ?: return
                        if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == restoreId }) progressUndo.clear()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        accountListening(isPlaying, flush = !isPlaying)
                        if (!isPlaying) saveCurrent(completedByOutro || (player.playbackState == Player.STATE_ENDED && !suppressManualEnd))
                    }
                    override fun onEvents(player: Player, events: Player.Events) {
                        // Our sleep fade changes volume every 100ms; it must not reawaken its own
                        // loop or cause a durable checkpoint for each volume step.
                        if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
                        publishWidget()
                        checkpoint()
                        wakeChecks.trySend(Unit)
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo, reason: Int) {
                        val oldId = oldPosition.mediaItem?.mediaId
                        val newId = newPosition.mediaItem?.mediaId
                        if (oldId != null) save(oldId, oldPosition.mediaItemIndex, oldPosition.positionMs,
                            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || completedChapterId == oldId)
                        completedChapterId = null
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            suppressManualEnd = player.duration > 0 &&
                                newPosition.positionMs >= player.duration - 1_500
                            introAppliedMediaId = newId
                            pendingIntroMediaId = null
                            if (timerChaptersRemaining > 0 && ids(newId)?.first == timerBookId) {
                                timerChapterId = ids(newId)?.second
                                timerChaptersRemaining = minOf(timerChaptersRemaining,
                                    player.mediaItemCount - newPosition.mediaItemIndex)
                            }
                        } else suppressManualEnd = false
                        if (newId != null) save(newId, newPosition.mediaItemIndex, newPosition.positionMs)
                        publishTimer()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) progressUndo.clear()
                        val oldBook = timerBookId
                        val current = ids(mediaItem?.mediaId)
                        if (oldBook != null && current != null && current.first != oldBook) cancelTimer("切换书籍后，旧书的定时已取消")
                        if (timerChaptersRemaining > 0 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            timerChapterFinished()
                        } else if (timerChaptersRemaining > 0) {
                            timerChapterId = current?.second
                            publishTimer()
                        }
                        completedByOutro = false
                        pendingIntroMediaId = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) null else mediaItem?.mediaId
                        introAppliedMediaId = mediaItem?.mediaId
                        loadSkipSettings(mediaItem?.mediaId, newBoundary = pendingIntroMediaId != null)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) applyIntroIfNeeded()
                        if (state == Player.STATE_ENDED) {
                            saveCurrent(!suppressManualEnd)
                            cancelTimer()
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { saveCurrent() }
                })
            }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A named, silent media channel with public lock-screen content.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(PLAYBACK_CHANNEL_ID, getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "正在收听的书籍与播放控制"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        session = MediaLibrarySession.Builder(this, RememberingPlayer(player, PlaybackStatus.chapterProgress, progressUndo), libraryCallback)
            .setSessionActivity(open).build()
        addSession(session!!)
        setMediaNotificationProvider(PlaybackNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PLAYBACK_CHANNEL_ID).setChannelName(R.string.playback_channel_name).build()
            .apply { setSmallIcon(R.drawable.ic_notification) }))
        scope.launch { ListeningPreferences(this@PlaybackService).values.collect { settings ->
            listeningSettings = settings
            leveling.enabled = settings.volumeLeveling
            progressUndo.setWindowSeconds(settings.progressUndoSeconds)
            globalIntroMs = settings.intro * 1000L
            globalOutroMs = settings.outro * 1000L
            loadSkipSettings(player.currentMediaItem?.mediaId)
        } }
        scope.launch {
            while (isActive) {
                val stamp = SystemClock.elapsedRealtime()
                val timeLeft = timerDeadline?.let { (it - stamp).coerceAtLeast(0) }
                val chapterLeft = if (player.duration > 0) player.duration - player.currentPosition else Long.MAX_VALUE
                val precise = player.isPlaying && (timeLeft?.let { it <= timerFadeMs + 1000 } == true ||
                    (timerChaptersRemaining == 1 && chapterLeft <= timerFadeMs + outroMs + 1000) ||
                    (outroMs > 0 && chapterLeft <= outroMs + 1000))
                val waitMs = playbackCheckDelay(player.isPlaying, precise, timeLeft,
                    PlaybackStatus.progressUndo.value?.let { (it.expiresAt - stamp).coerceAtLeast(0) })
                if (waitMs == null) wakeChecks.receive()
                else withTimeoutOrNull(waitMs) { wakeChecks.receive() }
                val now = SystemClock.elapsedRealtime()
                progressUndo.expire()
                if (now - lastAccounting >= 1000) {
                    accountListening(flush = now - lastSave >= 10_000)
                    lastAccounting = now
                }
                if (timerDeadline?.let { now >= it } == true) {
                    player.pause(); cancelTimer()
                }
                if (player.isPlaying) {
                    val duration = player.duration
                    if (player.isPlaying && timerChaptersRemaining == 1 && duration > 0 &&
                        ids(player.currentMediaItem?.mediaId)?.second == timerChapterId &&
                        duration - player.currentPosition <= 100) {
                        completedByOutro = !suppressManualEnd
                        saveCurrent(completedByOutro)
                        player.pause(); cancelTimer()
                    }
                    if (player.isPlaying && outroMs > 0 && duration > introMs + outroMs + 5_000 &&
                        duration - player.currentPosition <= outroMs) {
                        saveCurrent(true)
                        completedByOutro = true
                        if (timerChapterFinished()) continue
                        if (player.hasNextMediaItem()) {
                            completedChapterId = player.currentMediaItem?.mediaId
                            saveCurrent(true)
                            player.seekToNextMediaItem()
                        }
                        else { completedByOutro = true; saveCurrent(true); player.stop() }
                    }
                    if (now - lastSave >= 10_000) { saveCurrent(); checkpoint(); lastSave = now }
                }
                if (timerDeadline != null || timerChaptersRemaining > 0) applyTimerFade(now)
                if (now - lastTimerPublish >= 1000 && (timerDeadline != null || timerChaptersRemaining > 0)) {
                    publishTimer(); lastTimerPublish = now
                }
            }
        }
    }

    // Real browser support lets system media clients discover the active queue.
    private val libraryRoot = MediaItem.Builder().setMediaId("listening-root")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("正在收听")
            .setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val job = scope.launch {
                try {
                    val restored = resumption(applySpeed = isForPlayback)
                    // A new UI request may have populated the queue during the database read.
                    result.set(if (player.mediaItemCount == 0) restored else MediaSession.MediaItemsWithStartPosition(
                        (0 until player.mediaItemCount).map(player::getMediaItemAt), player.currentMediaItemIndex, player.currentPosition))
                } catch (error: Exception) { result.setException(error) }
            }
            result.addListener({ if (result.isCancelled) job.cancel() }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
            return result
        }
        @Suppress("DEPRECATION")
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
            intent: Intent): Boolean {
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            val previous = when (key.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
                KeyEvent.KEYCODE_MEDIA_NEXT -> false
                else -> return false
            }
            // One action per physical press; release and held-key repeats are consumed.
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return true
            when (if (previous) listeningSettings.headphonePrevious else listeningSettings.headphoneNext) {
                "chapter" -> if (previous) session.player.seekToPreviousMediaItem() else session.player.seekToNextMediaItem()
                "rewind" -> session.player.seekAndPlay(player.currentPosition - listeningSettings.rewind * 1000L)
                "forward" -> session.player.seekAndPlay(player.currentPosition + listeningSettings.forward * 1000L)
            }
            return true
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(if (params?.isRecent == true)
                libraryRoot.buildUpon().setMediaId("listening-recent").build() else libraryRoot, params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == "listening-recent" && page == 0 && pageSize > 0) {
                val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    try {
                        val restored = resumption(applySpeed = false)
                        result.set(LibraryResult.ofItemList(listOf(restored.mediaItems[restored.startIndex]), params))
                    } catch (error: Exception) { result.set(LibraryResult.ofItemList(emptyList(), params)) }
                }
                return result
            }
            if (parentId != libraryRoot.mediaId || page < 0 || pageSize <= 0)
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val start = (page.toLong() * pageSize).coerceAtMost(player.mediaItemCount.toLong()).toInt()
            val end = (start.toLong() + pageSize).coerceAtMost(player.mediaItemCount.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList((start until end).map(player::getMediaItemAt), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == libraryRoot.mediaId) libraryRoot else
                (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == mediaId }
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val result = super.onStartCommand(intent, flags, startId)
            recoverRestart()
            return result
        }
        if (intent?.action == ACTION_UNDO_PROGRESS) {
            progressUndo.take(intent.getLongExtra(EXTRA_UNDO_TOKEN, -1L))?.let { point ->
                val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == point.mediaId }
                // Use the real player to avoid capturing another undo; retain the current play/pause state.
                if (index != null) player.seekTo(index, point.positionMs)
            }
        }
        if (intent?.action == ACTION_TIMER) {
            val extension = intent.getIntExtra(EXTRA_EXTEND_MINUTES, 0).coerceIn(0, 180)
            if (extension > 0 && timerDeadline != null) {
                timerDeadline = timerDeadline!! + extension * 60_000L
                player.volume = timerBaseVolume
                publishTimer()
                checkpoint()
                return super.onStartCommand(intent, flags, startId)
            }
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
            val chapterEnd = intent.getBooleanExtra(EXTRA_CHAPTER_END, false)
            val chapterCount = intent.getIntExtra(EXTRA_CHAPTER_COUNT, if (chapterEnd) 1 else 0).coerceIn(0, 20)
            if (minutes <= 0 && chapterCount == 0) cancelTimer()
            else {
                cancelTimer()
                timerBaseVolume = player.volume
                timerFadeMs = intent.getIntExtra(EXTRA_FADE_SECONDS, 30).coerceIn(0, 60) * 1000L
                timerBookId = intent.getLongExtra(EXTRA_BOOK_ID, -1).takeIf { it > 0 }
                    ?: ids(player.currentMediaItem?.mediaId)?.first
                timerChapterId = ids(player.currentMediaItem?.mediaId)?.second
                timerChaptersRemaining = if (minutes > 0) 0 else minOf(chapterCount,
                    (player.mediaItemCount - player.currentMediaItemIndex).coerceAtLeast(1))
                timerDeadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else null
                publishTimer()
            }
        }
        if (intent.action == ACTION_TIMER) checkpoint()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrent()
        checkpoint()
        // Retain a paused session during the bounded foreground grace period, including buffering.
        if (!isPlaybackOngoing || player.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        val item = player.currentMediaItem?.mediaId
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val complete = completedByOutro || (player.playbackState == Player.STATE_ENDED && !suppressManualEnd)
        item?.let { PlaybackStatus.chapterProgress.record(it, if (complete) 0L else position) }
        val positions = PlaybackStatus.chapterProgress.snapshot()
        accountListening(playing = false, flush = true)
        checkpoint(active = false)
        scope.cancel()
        runBlocking(Dispatchers.IO) {
            pendingSave?.join()
            val pair = ids(item)
            saveMutex.withLock {
                val dao = (application as TingYeApp).library.dao
                for ((mediaId, saved) in positions) ids(mediaId)?.let { dao.saveChapterProgress(it.second, saved) }
                if (pair != null) dao.saveProgress(pair.first, pair.second,
                    index.coerceAtLeast(0), if (complete) 0L else position.coerceAtLeast(0),
                    if (complete) 2 else 1)
            }
        }
        cancelTimer()
        progressUndo.clear()
        PlaybackStatus.widget.value = WidgetSnapshot()
        TingYeWidget.refresh(this)
        session?.release()
        player.release()
        PlaybackStatus.chapterProgress.clear()
        writer.cancel()
        super.onDestroy()
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback"
        const val ACTION_OPEN_PLAYER = "com.tingye.app.OPEN_PLAYER"
        const val ACTION_TIMER = "com.tingye.app.TIMER"
        const val ACTION_UNDO_PROGRESS = "com.tingye.app.UNDO_PROGRESS"
        const val EXTRA_UNDO_TOKEN = "undo_token"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_CHAPTER_END = "chapter_end"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_COUNT = "chapter_count"
        const val EXTRA_FADE_SECONDS = "fade_seconds"
        const val EXTRA_EXTEND_MINUTES = "extend_minutes"
    }
}
