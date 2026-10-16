package com.tingye.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tingye.app.data.*
import com.tingye.app.playback.PlaybackService
import com.tingye.app.playback.PlaybackStatus
import com.tingye.app.playback.seekAndPlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUi(val ready: Boolean = false, val isPlaying: Boolean = false, val bookId: Long? = null,
    val chapterId: Long? = null, val index: Int = 0, val position: Long = 0, val duration: Long = 0,
    val speed: Float = 1f, val error: String? = null, val loadingBookId: Long? = null,
    val autoplayRequested: Boolean = false, val buffering: Boolean = false)
data class ImportUi(val busy: Boolean = false, val found: Int = 0, val message: String? = null,
    val completedBooks: Int = 0)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TingYeApp
    private val _libraryReady = MutableStateFlow(false)
    val libraryReady = _libraryReady.asStateFlow()
    val books = app.library.books.onEach { _libraryReady.value = true; com.tingye.app.widget.TingYeWidget.refresh(app) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chapterCounts = app.library.dao.chapterCounts().map { rows -> rows.associate { it.bookId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val chaptersByBook = app.library.dao.allChaptersFlow().map { it.groupBy(Chapter::bookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val preferences = ListeningPreferences(application)
    val settings = preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, ListeningSettings())
    val listeningDays = app.library.dao.listeningDays().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val completedBooks = app.library.dao.completedBookCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    private val _selected = MutableStateFlow<Long?>(null)
    val selected = _selected.asStateFlow()
    val chapters = selected.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else app.library.dao.chaptersFlow(id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks = selected.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else app.library.dao.bookmarksFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val timerUi = PlaybackStatus.timer.asStateFlow()
    val progressUndo = PlaybackStatus.progressUndo.asStateFlow()
    fun undoProgress(token: Long) {
        getApplication<Application>().startService(Intent(getApplication(), PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_UNDO_PROGRESS).putExtra(PlaybackService.EXTRA_UNDO_TOKEN, token))
    }
    fun volumeLeveling(value: Boolean) = viewModelScope.launch { preferences.volumeLeveling(value) }
    fun progressUndoSeconds(value: Int) = viewModelScope.launch { preferences.progressUndoSeconds(value) }
    private val _playerUi = MutableStateFlow(PlayerUi())
    val playerUi = _playerUi.asStateFlow()
    val playbackChapters = playerUi.map { it.bookId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else app.library.dao.chaptersFlow(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _importUi = MutableStateFlow(ImportUi())
    val importUi = _importUi.asStateFlow()
    private val _batchPreview = MutableStateFlow<BatchImportPreview?>(null)
    val batchPreview = _batchPreview.asStateFlow()
    private val _singlePreview = MutableStateFlow<SingleImportPreview?>(null)
    val singlePreview = _singlePreview.asStateFlow()
    private val _backupPreview = MutableStateFlow<BackupInspection?>(null)
    val backupPreview = _backupPreview.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage = _backupMessage.asStateFlow()
    private val _missingChapter = MutableStateFlow<Long?>(null)
    val missingChapter = _missingChapter.asStateFlow()
    private var importJob: Job? = null
    private var playRequestJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private data class PendingPlay(val bookId: Long, val chapterIndex: Int?, val autoplay: Boolean,
        val exactPositionMs: Long?)
    private var pendingPlay: PendingPlay? = null
    private var playSerial = 0L
    private var desiredAutoplay = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _missingChapter.value = controller?.currentMediaItem?.mediaId?.substringAfter(':')?.toLongOrNull()
            _playerUi.value = _playerUi.value.copy(error = "音频无法播放，请检查文件是否损坏或格式是否受支持")
        }
    }

    init {
        viewModelScope.launch {
            app.library.dao.allChaptersFlow().collect { app.library.indexDurations(it) }
        }
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, token).buildAsync().also { future ->
            future.addListener({
                runCatching {
                    controller = future.get().also { it.addListener(listener) }
                    refresh()
                    pendingPlay?.let { request ->
                        pendingPlay = null
                        playBook(request.bookId, request.chapterIndex, request.autoplay, request.exactPositionMs)
                    }
                }.onFailure { _playerUi.value = _playerUi.value.copy(error = "播放器连接失败") }
            }, ContextCompat.getMainExecutor(application))
        }
        viewModelScope.launch {
            playerUi.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                if (playing) while (true) { delay(500); refresh() }
            }
        }
        viewModelScope.launch { app.library.dao.latest()?.let { if (_selected.value == null) _selected.value = it.id } }
    }

    private fun refresh() {
        val c = controller ?: return
        val bookId = c.currentMediaItem?.mediaId?.substringBefore(':')?.toLongOrNull()
        _playerUi.value = PlayerUi(true, c.isPlaying, bookId,
            c.currentMediaItem?.mediaId?.substringAfter(':')?.toLongOrNull(),
            c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition.coerceAtLeast(0),
            c.duration.coerceAtLeast(0), c.playbackParameters.speed, _playerUi.value.error,
            _playerUi.value.loadingBookId, _playerUi.value.autoplayRequested,
            c.playbackState == Player.STATE_BUFFERING)
        if (bookId != null && _selected.value == null) _selected.value = bookId
    }

    fun select(id: Long) { _selected.value = id }

    fun playBook(id: Long, chapterIndex: Int? = null, autoplay: Boolean = true,
        exactPositionMs: Long? = null) {
        playRequestJob?.cancel()
        val serial = ++playSerial
        desiredAutoplay = autoplay
        _selected.value = id
        _missingChapter.value = null
        clearError()
        _playerUi.value = _playerUi.value.copy(loadingBookId = id, autoplayRequested = autoplay)
        playRequestJob = viewModelScope.launch {
            try {
                val c = controller ?: run {
                    pendingPlay = PendingPlay(id, chapterIndex, desiredAutoplay, exactPositionMs)
                    return@launch
                }
                val currentBook = c.currentMediaItem?.mediaId?.substringBefore(':')?.toLongOrNull()
                if (currentBook == id) {
                    if (exactPositionMs != null) c.seekTo(chapterIndex ?: c.currentMediaItemIndex, exactPositionMs)
                    else if (chapterIndex != null && chapterIndex != c.currentMediaItemIndex) c.seekToDefaultPosition(chapterIndex)
                    if (desiredAutoplay) c.play()
                    else if (c.playWhenReady) c.pause()
                    if (desiredAutoplay) app.library.dao.reopenCompleted(id)
                    return@launch
                }
                val book = app.library.dao.book(id) ?: return@launch
                val list = app.library.dao.chapters(id)
                if (list.isEmpty()) return@launch
                list.forEach { PlaybackStatus.chapterProgress.seed("$id:${it.id}", it.positionMs) }
                val playable = list.map { chapter ->
                    MediaItem.Builder().setUri(chapter.uri).setMediaId("$id:${chapter.id}")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(chapter.title)
                            .setAlbumTitle(book.title).setDisplayTitle(book.title)
                            .setSubtitle(chapter.title).setIsPlayable(true).setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                            .setArtworkUri(book.coverUri?.let(Uri::parse)).build()).build()
                }
                val savedIndex = list.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex
                val index = (chapterIndex ?: savedIndex).coerceIn(0, list.lastIndex)
                // SAF providers may block; stale requests are cancelled before touching Media3.
                if (!withContext(Dispatchers.IO) { app.library.canOpen(list[index].uri) }) {
                    if (serial == playSerial) {
                        _missingChapter.value = list[index].id
                        _playerUi.value = _playerUi.value.copy(error = "源文件不可访问，请重新定位这一章")
                    }
                    return@launch
                }
                if (serial != playSerial) return@launch
                val position = exactPositionMs ?: if (chapterIndex == null)
                    (book.positionMs - settings.value.resumeBack * 1000L).coerceAtLeast(0)
                    else PlaybackStatus.chapterProgress.position("$id:${list[index].id}", list[index].durationMs)
                c.setMediaItems(playable, index, position)
                c.setPlaybackSpeed(book.speedOverride ?: settings.value.defaultSpeed)
                c.prepare()
                if (desiredAutoplay) { app.library.dao.reopenCompleted(id); c.play() }
                refresh()
            } finally {
                if (serial == playSerial && pendingPlay == null)
                    _playerUi.value = _playerUi.value.copy(loadingBookId = null)
            }
        }
    }

    fun toggle() {
        if (_playerUi.value.loadingBookId != null) {
            desiredAutoplay = !desiredAutoplay
            pendingPlay = pendingPlay?.copy(autoplay = desiredAutoplay)
            _playerUi.value = _playerUi.value.copy(autoplayRequested = desiredAutoplay)
            if (!desiredAutoplay) controller?.pause()
            return
        }
        val c = controller ?: return
        if (c.mediaItemCount == 0) { selected.value?.let { playBook(it) }; return }
        if (c.playWhenReady) c.pause() else c.play()
        _playerUi.value = _playerUi.value.copy(autoplayRequested = c.playWhenReady)
    }
    fun previous(bookId: Long? = null) {
        val c = controller
        if (bookId == null || c?.currentMediaItem?.mediaId?.startsWith("$bookId:") == true) c?.seekToPreviousMediaItem()
        else viewModelScope.launch {
            val book = app.library.dao.book(bookId) ?: return@launch
            val list = app.library.dao.chapters(bookId)
            val index = list.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex
            if (index > 0) playBook(bookId, index - 1)
        }
    }
    fun next(bookId: Long? = null) {
        val c = controller
        if (bookId == null || c?.currentMediaItem?.mediaId?.startsWith("$bookId:") == true) c?.seekToNextMediaItem()
        else viewModelScope.launch {
            val book = app.library.dao.book(bookId) ?: return@launch
            val list = app.library.dao.chapters(bookId)
            val index = list.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex
            if (index < list.lastIndex) playBook(bookId, index + 1)
        }
    }
    fun canPrevious(bookId: Long): Boolean = controller?.let { it.currentMediaItem?.mediaId?.startsWith("$bookId:") == true && it.hasPreviousMediaItem() } ?: false
    fun canNext(bookId: Long): Boolean = controller?.let { it.currentMediaItem?.mediaId?.startsWith("$bookId:") == true && it.hasNextMediaItem() } ?: false
    fun chooseChapter(bookId: Long, chapterId: Long) = viewModelScope.launch {
        val list = app.library.dao.chapters(bookId)
        val index = list.indexOfFirst { it.id == chapterId }
        if (index < 0) return@launch
        if (controller?.currentMediaItem?.mediaId == "$bookId:$chapterId") { _selected.value = bookId; return@launch }
        playBook(bookId, index)
    }
    fun restartChapter(bookId: Long, chapterId: Long) {
        if (controller?.currentMediaItem?.mediaId == "$bookId:$chapterId") {
            controller?.seekTo(0); controller?.play()
        } else viewModelScope.launch {
            val index = app.library.dao.chapters(bookId).indexOfFirst { it.id == chapterId }
            if (index >= 0) playBook(bookId, chapterIndex = index, autoplay = true, exactPositionMs = 0)
        }
    }
    fun seek(position: Long) { controller?.seekAndPlay(position); refresh() }
    fun skipBack() { controller?.let { it.seekAndPlay(it.currentPosition - settings.value.rewind * 1000L) } }
    fun skipForward() { controller?.let { it.seekAndPlay(it.currentPosition + settings.value.forward * 1000L) } }
    fun speed(value: Float) {
        if (selected.value == playerUi.value.bookId) controller?.setPlaybackSpeed(value)
        selected.value?.let { id -> viewModelScope.launch {
            app.library.dao.updateSpeedOverride(id, value, value)
        } }
        refresh()
    }
    fun timer(minutes: Int, chapterEnd: Boolean = false, chapters: Int = 0, fadeSeconds: Int = 30) {
        if ((minutes > 0 || chapterEnd || chapters > 0) && selected.value != playerUi.value.bookId) {
            _playerUi.value = _playerUi.value.copy(error = "请先播放本书，再设置定时停止")
            return
        }
        val intent = Intent(getApplication(), PlaybackService::class.java).setAction(PlaybackService.ACTION_TIMER)
            .putExtra(PlaybackService.EXTRA_MINUTES, minutes).putExtra(PlaybackService.EXTRA_CHAPTER_END, chapterEnd)
            .putExtra(PlaybackService.EXTRA_BOOK_ID, selected.value ?: -1L)
            .putExtra(PlaybackService.EXTRA_CHAPTER_COUNT, if (chapterEnd) 1 else chapters)
            .putExtra(PlaybackService.EXTRA_FADE_SECONDS, fadeSeconds)
        getApplication<Application>().startService(intent)
    }
    fun extendTimer(minutes: Int) {
        if (timerUi.value.bookId != selected.value) return
        getApplication<Application>().startService(Intent(getApplication(), PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_TIMER).putExtra(PlaybackService.EXTRA_EXTEND_MINUTES, minutes))
    }

    fun importFiles(uris: List<Uri>, name: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importUi.value = ImportUi(busy = true)
            runCatching { app.library.previewFiles(uris, name) }
                .onSuccess { preview ->
                    _singlePreview.value = preview.takeIf { it.files.isNotEmpty() }
                    _importUi.value = ImportUi(message = if (preview.files.isEmpty()) "没有找到音频文件" else null)
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) _importUi.value = ImportUi(message = "导入失败：${it.localizedMessage ?: "无法读取文件"}") }
        }
    }
    fun importFolder(uri: Uri, name: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importUi.value = ImportUi(busy = true)
            runCatching {
                app.library.previewFolder(uri, name,
                    { count -> _importUi.value = ImportUi(busy = true, found = count) },
                    { importJob?.isCancelled == true })
            }.onSuccess { preview ->
                _singlePreview.value = preview.takeIf { it.files.isNotEmpty() }
                _importUi.value = ImportUi(message = if (preview.files.isEmpty()) "文件夹中没有音频文件" else null)
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) _importUi.value = ImportUi(message = "导入失败：${it.localizedMessage ?: "无法读取文件夹"}") }
        }
    }
    fun discardSingleImport() {
        val preview = _singlePreview.value; _singlePreview.value = null
        viewModelScope.launch(Dispatchers.IO) { BookMetadata.discard(app, preview?.details?.coverUri) }
    }
    fun confirmSingleImport(preview: SingleImportPreview, title: String) {
        _singlePreview.value = null
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importUi.value = ImportUi(busy = true)
            try {
                val result = app.library.importCandidates(preview.files, title, preview.sourceTreeUri, preview.details)
                _importUi.value = ImportUi(message = result.error ?: "已导入 ${result.added} 章，跳过 ${result.skipped} 章" +
                    if (preview.failedDirectories > 0) "；${preview.failedDirectories} 个目录读取失败" else "")
                result.bookId?.let { _selected.value = it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { _importUi.value = ImportUi(message = "导入失败：${error.localizedMessage}") }
            finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { BookMetadata.discard(app, preview.details.coverUri) } }
        }
    }
    fun prepareBatchImport(uri: Uri, rootName: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importUi.value = ImportUi(busy = true)
            runCatching { app.library.previewBatch(uri, rootName,
                { _importUi.value = ImportUi(busy = true, found = it) },
                { importJob?.isCancelled == true }) }
                .onSuccess {
                    _batchPreview.value = it.takeIf { preview -> preview.groups.isNotEmpty() }
                    _importUi.value = ImportUi(message = if (it.groups.isEmpty()) "文件夹中没有可识别的音频" else null)
                }.onFailure { if (it !is kotlinx.coroutines.CancellationException)
                    _importUi.value = ImportUi(message = "扫描失败：${it.localizedMessage ?: "无法读取文件夹"}") }
        }
    }
    fun discardBatchImport() {
        val preview = _batchPreview.value; _batchPreview.value = null
        viewModelScope.launch(Dispatchers.IO) { preview?.groups?.map { it.details.coverUri }?.distinct()?.forEach { BookMetadata.discard(app, it) } }
    }
    fun confirmBatchImport(preview: BatchImportPreview, titles: Map<String, String>) {
        _batchPreview.value = null
        importJob?.cancel()
        importJob = viewModelScope.launch {
            var completed = 0; var added = 0; var skipped = 0
            _importUi.value = ImportUi(busy = true)
            try {
                for (group in preview.groups.filter { titles.containsKey(it.sourceTreeUri) }) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val result = app.library.importCandidates(group.files,
                        titles[group.sourceTreeUri].orEmpty().ifBlank { group.title }, group.sourceTreeUri, group.details)
                    if (result.bookId != null) completed++
                    added += result.added; skipped += result.skipped
                    _importUi.value = ImportUi(busy = true, found = added, completedBooks = completed)
                }
                _importUi.value = ImportUi(message = "已导入 $completed 本书、$added 章；跳过 $skipped 个重复音频" +
                    if (preview.failedDirectories > 0) "；${preview.failedDirectories} 个文件夹读取失败" else "")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (error: Exception) { _importUi.value = ImportUi(message =
                  "已导入 $completed 本书、$added 章；其余导入失败：${error.localizedMessage}") }
            finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                preview.groups.map { it.details.coverUri }.distinct().forEach { BookMetadata.discard(app, it) }
            } }
        }
    }
    fun markChapter(chapter: Chapter, listened: Boolean) = viewModelScope.launch {
        app.library.dao.markChapterListened(chapter.id, listened)
    }
    fun headphoneAction(previous: Boolean, value: String) = viewModelScope.launch { preferences.headphoneAction(previous, value) }
    fun cancelImport() {
        val completed = _importUi.value.completedBooks
        importJob?.cancel()
        _importUi.value = ImportUi(message = if (completed > 0) "已取消；已导入的 $completed 本书已保留" else "已取消导入")
    }
    fun clearImportMessage() { _importUi.value = ImportUi() }
    fun clearError() { _playerUi.value = _playerUi.value.copy(error = null) }
    fun relinkMissing(uri: Uri) = viewModelScope.launch {
        val id = _missingChapter.value ?: return@launch
        runCatching {
            if (!app.library.canOpen(uri.toString())) error("新文件不可访问")
            app.library.dao.relink(id, uri.toString())
            controller?.clearMediaItems()
            _missingChapter.value = null
            clearError()
            _importUi.value = ImportUi(message = "章节文件已重新定位，请再次播放")
        }.onFailure { _playerUi.value = _playerUi.value.copy(error = "重新定位失败，请选择可访问且未导入过的音频") }
    }
    fun renameBook(book: Book, title: String) = viewModelScope.launch {
        app.library.dao.updateBookTitle(book.id, title.trim().ifBlank { book.title })
    }
    fun cover(book: Book, uri: Uri) = viewModelScope.launch { app.library.dao.updateBookCover(book.id, uri.toString()) }
    fun deleteBook(id: Long) = viewModelScope.launch {
        if (playerUi.value.bookId == id) controller?.clearMediaItems()
        app.library.dao.deleteBook(id)
        if (_selected.value == id) _selected.value = null
    }
    fun moveChapter(chapter: Chapter, direction: Int) = viewModelScope.launch {
        val list = app.library.dao.chapters(chapter.bookId)
        val index = list.indexOfFirst { it.id == chapter.id }
        val other = list.getOrNull(index + direction) ?: return@launch
        val c = controller
        val activeBookId = c?.currentMediaItem?.mediaId?.substringBefore(':')?.toLongOrNull()
        if (activeBookId == chapter.bookId && c?.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) != true) return@launch
        app.library.dao.swapChapters(chapter, other)
        app.library.dao.markManualOrder(chapter.bookId)
        app.library.dao.refreshDisplayIndex(chapter.bookId)
        if (activeBookId == chapter.bookId && c?.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) == true) {
            // Media3 moves the existing item in place, preserving the current item's identity and position.
            c.moveMediaItem(index, index + direction)
            refresh()
        }
    }
    fun theme(value: String) = viewModelScope.launch { preferences.theme(value) }
    fun skips(back: Int, forward: Int) = viewModelScope.launch { preferences.skips(back, forward) }
    fun resumeBack(seconds: Int) = viewModelScope.launch { preferences.resumeBack(seconds) }
    fun skipEdges(intro: Int, outro: Int) = viewModelScope.launch { preferences.skipEdges(intro, outro) }
    fun defaultSpeed(value: Float) = viewModelScope.launch {
        preferences.defaultSpeed(value)
        playerUi.value.bookId?.let { id ->
            if (app.library.dao.book(id)?.speedOverride == null) controller?.setPlaybackSpeed(value)
        }
    }
    fun setBookSpeed(book: Book, speed: Float?) = viewModelScope.launch {
        app.library.dao.updateSpeedOverride(book.id, speed, speed ?: settings.value.defaultSpeed)
        if (playerUi.value.bookId == book.id) controller?.setPlaybackSpeed(speed ?: settings.value.defaultSpeed)
    }
    fun setBookEdges(book: Book, intro: Int?, outro: Int?) = viewModelScope.launch {
        app.library.dao.updateEdgeOverrides(book.id, intro, outro)
    }
    fun addBookmark(bookId: Long, note: String = "") = viewModelScope.launch {
        val book = app.library.dao.book(bookId) ?: return@launch
        val media = controller?.currentMediaItem?.mediaId
        val currentId = if (media?.startsWith("$bookId:") == true) media.substringAfter(':').toLongOrNull() else null
        val chapterId = currentId ?: book.chapterId ?: app.library.dao.chapters(bookId).getOrNull(book.chapterIndex)?.id ?: return@launch
        val position = if (currentId != null) controller?.currentPosition ?: book.positionMs else book.positionMs
        app.library.dao.addBookmark(Bookmark(bookId = bookId, chapterId = chapterId,
            positionMs = position.coerceAtLeast(0), note = note.trim()))
    }
    fun editBookmark(bookmark: Bookmark, note: String) = viewModelScope.launch {
        app.library.dao.updateBookmark(bookmark.copy(note = note.trim()))
    }
    fun deleteBookmark(bookmark: Bookmark) = viewModelScope.launch { app.library.dao.deleteBookmark(bookmark.id) }
    fun jumpToBookmark(bookmark: Bookmark) = viewModelScope.launch {
        val list = app.library.dao.chapters(bookmark.bookId)
        val index = list.indexOfFirst { it.id == bookmark.chapterId }
        if (index < 0) { _playerUi.value = _playerUi.value.copy(error = "书签对应章节已不存在"); return@launch }
        playBook(bookmark.bookId, index, controller?.isPlaying == true, bookmark.positionMs)
    }
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching { BackupCodec.export(getApplication(), app.database, settings.value, uri) }
            .onSuccess { _backupMessage.value = "已导出 $it 本书；备份不含音频，恢复后可能需要重新授权文件" }
            .onFailure { _backupMessage.value = "导出失败：${it.localizedMessage ?: "无法写入文件"}" }
    }
    fun inspectBackup(uri: Uri) = viewModelScope.launch {
        runCatching { BackupCodec.inspect(getApplication(), app.database, uri) }
            .onSuccess { _backupPreview.value = it }
            .onFailure { _backupMessage.value = "备份无法导入：${it.localizedMessage ?: "校验失败"}" }
    }
    fun confirmBackup(restoreSettings: Boolean) {
        val preview = _backupPreview.value ?: return
        _backupPreview.value = null
        viewModelScope.launch {
            runCatching { BackupCodec.import(app, app.database, preview) }.onSuccess { result ->
                val settingsResult = if (restoreSettings) runCatching { preferences.restore(preview.settings) } else null
                _backupMessage.value = "恢复 ${result.books} 本书、${result.chapters} 章、${result.bookmarks} 个书签；跳过同名书 ${result.skippedBooks} 本、重复章节 ${result.skippedChapters} 章。请检查音频授权。" +
                    if (settingsResult?.isFailure == true) "全局设置写入失败，请在设置页核对。" else ""
            }.onFailure { _backupMessage.value = "恢复失败，数据库事务已回滚：${it.localizedMessage ?: "导入失败"}" }
        }
    }
    fun dismissBackup() { _backupPreview.value = null; _backupMessage.value = null }
    fun transitionSpeed(value: Float) = viewModelScope.launch { preferences.transitionSpeed(value) }
    fun wallpaper(value: String) = viewModelScope.launch { preferences.wallpaper(value) }
    fun customWallpaper(uri: Uri) = viewModelScope.launch { preferences.customWallpaper(uri.toString()) }
    fun wallpaperBlur(value: Float) = viewModelScope.launch { preferences.wallpaperBlur(value) }
    fun hapticStrength(value: Int) = viewModelScope.launch { preferences.hapticStrength(value) }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
