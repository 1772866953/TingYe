package com.tingye.app.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tingye.app.PlayerUi
import com.tingye.app.TingYeApp
import com.tingye.app.data.Chapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class ReadingSession(val book: ReadingBook, val chapters: List<TextChapter>, val audio: List<Chapter>,
    val points: List<ReadingSyncPoint>, val location: ReadingLocation, val texts: Map<Int, String> = emptyMap(),
    val navigation: Int = 0, val following: Boolean = false, val changedByReading: Boolean = false,
    val audioReference: AudioLocation? = null, val sentences: List<ReadingSentence> = emptyList(),
    val imported: ReadingSyncImport? = null,val visibleEnd:Int?=null)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TingYeApp
    private val repository = ReaderRepository(app, app.database)
    val preferences = ReaderPreferences(app)
    val style = preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderStyle())
    val books = repository.dao.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _session = MutableStateFlow<ReadingSession?>(null)
    val session = _session.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var loading: Job? = null
    private var saving: Job? = null
    private var serial = 0
    private var pendingAudioSync = false
    private var followingJob: Job? = null
    private val syncGate=ReadingSyncGate()
    private val persistence=Mutex()
    private var boundPlayback:StateFlow<PlayerUi>?=null
    private var playbackJob:Job?=null
    var onAudioSync: ((Long, AudioLocation) -> Unit)? = null

    fun bindPlayback(playback:StateFlow<PlayerUi>){
        if(boundPlayback===playback)return
        boundPlayback=playback;playbackJob?.cancel()
        playbackJob=viewModelScope.launch {
            var previous:PlayerUi?=null
            playback.collectLatest { value->
                val old=previous;previous=value
                val bookId=value.bookId ?: return@collectLatest
                val chapter=value.chapterId ?: return@collectLatest
                if(!value.ready || value.loadingBookId!=null || value.buffering)return@collectLatest
                if(value.isPlaying){
                    if(old?.isPlaying!=true){saving?.cancel();pendingAudioSync=false;syncGate.reset()}
                    if(syncGate.acceptsAudio(bookId,chapter,value.position,true))follow(value)
                    return@collectLatest
                }
                if(old==null || (old.ready && old.loadingBookId==null && !old.buffering && !old.isPlaying && old.bookId==bookId && old.chapterId==chapter && old.position==value.position))return@collectLatest
                val revision=syncGate.revision
                delay(150)
                if(revision!=syncGate.revision || pendingAudioSync || !syncGate.acceptsAudio(bookId,chapter,value.position,false))return@collectLatest
                syncPausedAudio(value,revision)
            }
        }
    }
    fun beginReadingInteraction(){syncGate.readingStarted();setFollowing(false)}
    private suspend fun syncPausedAudio(playback:PlayerUi,revision:Long){
        val audioBook=playback.bookId ?: return
        val chapter=playback.chapterId ?: return
        for(book in repository.dao.linkedBooks(audioBook)){
            if(syncGate.revision!=revision || pendingAudioSync)return
            val current=_session.value?.takeIf { it.book.id==book.id }
            val chapters=current?.chapters ?: repository.chapters(book.id)
            val audio=current?.audio ?: app.library.dao.chapters(audioBook)
            val sentence=repository.dao.sentenceNearAudio(book.id,chapter,playback.position+120)
            val points=current?.points ?: repository.dao.points(book.id)
            val imported=current?.imported ?: repository.dao.imported(book.id)
            val target=sentence?.let { ReadingLocation(it.textChapter,it.textStart) }
                ?: ReadingAlignment.toText(chapter,playback.position,chapters,audio,
                    if(imported!=null)points.filter { it.kind!="recognition" } else points,book.allowApproximate)
                ?: continue
            if(syncGate.revision!=revision || pendingAudioSync)return
            repository.dao.progress(book.id,target.chapter,target.offset)
            if(_session.value?.book?.id==book.id){
                _session.value=_session.value?.copy(changedByReading=false)
                goTo(target.chapter,target.offset,user=false)
            }
        }
    }

    init {
        viewModelScope.launch {
            _session.map { it?.book?.id }.distinctUntilChanged().collectLatest { id ->
                if (id != null) repository.dao.pointsFlow(id).collect { points ->
                    _session.value?.takeIf { it.book.id == id }?.let { _session.value = it.copy(points = points) }
                }
            }
        }

    }

    fun dismissMessage() { _message.value = null }
    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { _message.value = e.localizedMessage ?: "操作未完成，请重试" }
    }
    fun import(uri: Uri, audioBookId: Long? = null) {
        if (_busy.value) return
        _busy.value = true
        operation { try { val book = repository.import(uri); if (audioBookId != null) repository.link(book, audioBookId)
            open(book.id) } finally { _busy.value = false } }
    }
    fun open(id: String, playback: PlayerUi? = null, associateWith: Long? = null) {
        syncGate.reset()
        val request = ++serial
        loading?.cancel()
        loading = operation {
            var book = repository.dao.book(id) ?: return@operation
            if (associateWith != null && book.audioBookId != associateWith) {
                repository.link(book, associateWith)
                book = repository.dao.book(id) ?: return@operation
            }
            val chapters = repository.chapters(id)
            val audio = book.audioBookId?.let { app.library.dao.chapters(it) }.orEmpty()
            val points = repository.dao.points(id)
            val imported = repository.dao.imported(id)
            val savedAudio = book.audioBookId?.let { app.library.dao.book(it) }
            val liveAudio = playback?.takeIf { it.bookId == book.audioBookId && it.chapterId != null }
            val referenceId = liveAudio?.chapterId ?: savedAudio?.chapterId
            val referenceIndex = audio.indexOfFirst { it.id == referenceId }
            val reference = if (referenceIndex >= 0) AudioLocation(audio[referenceIndex].id, referenceIndex,
                liveAudio?.position ?: savedAudio?.positionMs ?: 0, false) else null
            var location = ReadingLocation(book.chapterIndex.coerceIn(chapters.indices), book.offset)
            if (book.syncEnabled) {
                val current = playback?.takeIf { it.bookId == book.audioBookId && it.chapterId != null }
                val saved = book.audioBookId?.let { app.library.dao.book(it) }
                val chapterId = current?.chapterId ?: saved?.chapterId
                chapterId?.let { id ->
                    val position=current?.position ?: saved?.positionMs ?: 0
                    val sentence=repository.dao.sentenceNearAudio(book.id,id,position+120)
                    sentence?.let { ReadingLocation(it.textChapter,it.textStart) }
                        ?: ReadingAlignment.toText(id,position,chapters,audio,
                            if(imported!=null)points.filter { it.kind!="recognition" } else points,book.allowApproximate)
                }?.let { location = it }
            }
            val texts = neighborhood(book.id, location.chapter, chapters.size)
            if (request == serial) {
                pendingAudioSync = false
                _session.value = ReadingSession(book, chapters, audio, points,
                    location.copy(offset = location.offset.coerceIn(0, chapters[location.chapter].length)), texts, request,
                    audioReference = reference, imported=imported, sentences=sentenceNeighborhood(book.id,location.chapter,chapters.size))
            }
        }
    }
    private suspend fun neighborhood(id: String, chapter: Int, count: Int): Map<Int, String> =
        ((chapter-1).coerceAtLeast(0)..(chapter+1).coerceAtMost(count-1)).associateWith { repository.text(id, it) }

    private suspend fun sentenceNeighborhood(id:String,chapter:Int,count:Int)=repository.dao.sentences(id,(chapter-1).coerceAtLeast(0),(chapter+1).coerceAtMost(count-1))
    private fun fallbackPoints(current:ReadingSession)=if(current.imported!=null)current.points.filter { it.kind!="recognition" } else current.points

    fun goTo(chapter: Int, offset: Int = 0, user: Boolean = true) {
        val current = _session.value ?: return
        if (chapter !in current.chapters.indices) return
        if(user)syncGate.readingStarted()
        val request = ++serial
        loading?.cancel()
        loading = operation {
            val texts = if (chapter == current.location.chapter) current.texts else neighborhood(current.book.id, chapter, current.chapters.size)
            val sentences=if(chapter==current.location.chapter)current.sentences else sentenceNeighborhood(current.book.id,chapter,current.chapters.size)
            if (request == serial) {
                if (user) pendingAudioSync = true
                _session.value = current.copy(location = ReadingLocation(chapter, offset.coerceIn(0, current.chapters[chapter].length)),
                    texts = texts, sentences=sentences, navigation = request, following = !user && current.following,
                    changedByReading = user || current.changedByReading,visibleEnd=null)
                saveSoon()
            }
        }
    }
    fun position(offset: Int, user: Boolean,visibleEnd:Int?=null) {
        val current = _session.value ?: return
        val actual=offset.coerceIn(0,current.chapters[current.location.chapter].length)
        // Reflow reports the page's first line. Keep a programmatic sentence
        // anchor inside that page instead of replacing it with an earlier line.
        val location=if(!user && visibleEnd!=null && current.location.offset in actual until visibleEnd)current.location
            else current.location.copy(offset=actual)
        if (user && location != current.location) {pendingAudioSync = true;syncGate.readingStarted()}
        if(!user && current.changedByReading && current.visibleEnd==null && visibleEnd!=null)pendingAudioSync=true
        _session.value = current.copy(location = location, following = !user && current.following,
            changedByReading = (user && location != current.location) || current.changedByReading,visibleEnd=visibleEnd)
        saveSoon()
    }
    private fun saveSoon() { saving?.cancel(); saving = viewModelScope.launch { delay(350); persist() } }
    private suspend fun persist() { persistence.withLock {
        val current = _session.value ?: return
        repository.dao.progress(current.book.id, current.location.chapter, current.location.offset)
        if (!pendingAudioSync) return
        val latest = _session.value ?: return
        if (latest.book.id != current.book.id || latest.location != current.location) return
        val bookId = latest.book.audioBookId ?: run {pendingAudioSync=false;syncGate.readingEnded();return}
        if (!latest.book.syncEnabled || latest.following) {pendingAudioSync=false;syncGate.readingEnded();return}
        val target = audioTarget(latest) ?: run {pendingAudioSync=false;syncGate.readingEnded();return}
        // Save even if the UI's media controller is being recreated. The callback
        // also seeks the paused queue, so a later service checkpoint cannot restore an old position.
        app.library.dao.saveProgress(bookId,target.chapterId,target.index,target.positionMs,1)
        if (_session.value?.following != true && _session.value?.location == latest.location){
            pendingAudioSync=false
            syncGate.sentToAudio(bookId,target)
            onAudioSync?.invoke(bookId,target)
        }
    } }
    fun flush() = operation { saving?.cancel(); persist() }
    fun close(done: (ReadingSession?) -> Unit) = operation {
        saving?.cancel(); loading?.cancel(); serial++
        persist()
        val current = _session.value
        _session.value = null
        syncGate.readingEnded()
        done(current)
    }
    fun audioTarget(current: ReadingSession? = _session.value): AudioLocation? = current?.let {
        val body=it.texts[it.location.chapter].orEmpty()
        val offset=(it.location.offset until minOf(body.length,it.location.offset+16)).firstOrNull { position->body[position].isLetterOrDigit() }
            ?: it.location.offset
        SentenceAlignment.toAudio(it.location.copy(offset=offset),it.audio,it.sentences,it.visibleEnd)
            ?: ReadingAlignment.toAudio(it.location, it.chapters, it.audio, fallbackPoints(it), it.book.allowApproximate)
    }
    fun setFollowing(value: Boolean) { if(value)syncGate.reset();_session.value = _session.value?.copy(following = value) }
    fun audioTargetAt(offset:Int):AudioLocation? {
        val current=_session.value ?: return null
        return audioTarget(current.copy(location=current.location.copy(offset=offset),visibleEnd=null))
    }
    fun follow(playback: PlayerUi) {
        val current = _session.value ?: return
        if (!current.book.syncEnabled || !current.following || !playback.isPlaying || playback.bookId != current.book.audioBookId) return
        val chapter = playback.chapterId ?: return
        followingJob?.cancel()
        followingJob=operation {
            val sentence=repository.dao.sentenceNearAudio(current.book.id,chapter,playback.position+120)
            val target=sentence?.let { ReadingLocation(it.textChapter,it.textStart) }
                ?: ReadingAlignment.toText(chapter,playback.position,current.chapters,current.audio,fallbackPoints(current),current.book.allowApproximate)
                ?: return@operation
            val latest=_session.value?.takeIf { it.book.id==current.book.id && it.following } ?: return@operation
            if(target.chapter!=latest.location.chapter || kotlin.math.abs(target.offset-latest.location.offset)>32)
                goTo(target.chapter,target.offset,user=false)
        }
    }
    fun link(audioBookId: Long?) = operation {
        val current = _session.value ?: return@operation
        saving?.cancel(); pendingAudioSync = false
        repository.link(current.book, audioBookId)
        reload()
    }
    private suspend fun reload() {
        val current = _session.value ?: return
        val book = repository.dao.book(current.book.id) ?: return
        _session.value = current.copy(book = book, points = repository.dao.points(book.id),
            audio = book.audioBookId?.let { app.library.dao.chapters(it) }.orEmpty(),
            imported=repository.dao.imported(book.id),sentences=sentenceNeighborhood(book.id,current.location.chapter,current.chapters.size))
    }
    fun syncOptions(enabled: Boolean, approximate: Boolean) = operation {
        val current = _session.value ?: return@operation
        val fresh = repository.dao.book(current.book.id) ?: return@operation
        repository.dao.update(fresh.copy(syncEnabled = enabled, allowApproximate = approximate))
        reload()
    }
    fun mapCurrentChapter(audioIndex: Int) = operation {
        val current = _session.value ?: return@operation
        val audio = current.audio.getOrNull(audioIndex) ?: return@operation
        val points = ReadingAlignment.chapterPoints(current.book.id, current.location.chapter, current.chapters[current.location.chapter], audio)
        require(points.isNotEmpty()) { "音频时长尚未读取，请先播放一次再关联" }
        repository.mapChapter(current.book.id, current.location.chapter, points)
        reload()
    }
    fun matchChapters(byOrder: Boolean = false,playback: PlayerUi? = null) = operation {
        val current = _session.value ?: return@operation
        val plan=withContext(Dispatchers.Default) { ReaderMatching.plan(current.book.id,current.chapters,current.audio,byOrder) }
        val calibration=current.points.filter { it.kind!="chapter" }
        val points=plan.points.filter { ReadingAlignment.calibrationIsOrdered(current.chapters,current.audio,
            calibration,it.copy(kind="manual")) }
        require(points.isNotEmpty()) { "音频时长尚未补齐，或已有校准与估算冲突。请先播放缺少时长的音频，或直接校准当前内容。" }
        saving?.cancel();pendingAudioSync=false
        repository.replaceEstimates(current.book.id,points)
        val fresh=repository.dao.book(current.book.id) ?: return@operation
        repository.dao.update(fresh.copy(syncEnabled=true,allowApproximate=true))
        reload()
        locateAudio(playback)
        _message.value = when(plan.method) {
            "ranges" -> "已按音频标注的章节范围建立 ${plan.matches} 段估算对应。章节内部时间仍按字数估算，可继续校准。"
            "titles" -> "已找到 ${plan.matches} 个同名章节起点，其余位置按全书比例估算。已有校准保留。"
            else -> "音频集数与原文目录不同，已按全书字数和音频时长建立估算对应，并定位到当前收听位置附近。讲述增删会产生偏差，可在相同内容处继续校准。"
        }
    }
    fun clearManualCalibration() = operation {
        val current = _session.value ?: return@operation
        saving?.cancel(); pendingAudioSync = false
        repository.dao.clearManual(current.book.id)
        reload()
    }
    fun clearCorrespondence() = operation {
        val current = _session.value ?: return@operation
        saving?.cancel(); pendingAudioSync = false
        repository.clearCorrespondence(current.book.id)
        reload()
        _message.value = "已删除本书的对应点和识别记录。小说、音频及阅读和播放进度均保留。"
    }
    fun locateAudio(playback: PlayerUi? = null) = operation {
        val current=_session.value ?: return@operation
        val live=playback?.takeIf { it.bookId==current.book.audioBookId && it.chapterId!=null }
        val saved=current.book.audioBookId?.let { app.library.dao.book(it) }
        val chapter=live?.chapterId ?: saved?.chapterId ?: return@operation
        val position=live?.position ?: saved?.positionMs ?: 0
        val precise=repository.dao.sentenceNearAudio(current.book.id,chapter,position+120)
        val target=precise?.let { ReadingLocation(it.textChapter,it.textStart) }
            ?: ReadingAlignment.toText(chapter,position,current.chapters,current.audio,fallbackPoints(current),current.book.allowApproximate)
            ?: return@operation
        pendingAudioSync=false
        goTo(target.chapter,target.offset,user=false)
    }
    fun calibrate(audioChapterId: Long, positionMs: Long) = operation {
        val current = _session.value ?: return@operation
        val audio=current.audio.firstOrNull { it.id == audioChapterId } ?: error("请选择关联有声书中的章节")
        require((audio.durationMs ?: 0) > 0 && positionMs in 0..audio.durationMs!!) { "请先读取音频时长，并选择有效播放位置" }
        val point=ReadingSyncPoint(readerId = current.book.id, textChapter = current.location.chapter,
            textOffset = current.location.offset, audioChapterId = audioChapterId, positionMs = positionMs)
        require(ReadingAlignment.calibrationIsOrdered(current.chapters,current.audio,current.points,point)) {
            "这个位置与已有校准的先后顺序冲突，请检查音频集数和播放位置。"
        }
        repository.addCalibration(point)
        reload()
        _message.value = "已校准当前页开头。至少两处校准之间可联动；讲述与原文差异较大时请多校准几处。"
    }
    fun importSync(uri:Uri) {
        if(_busy.value)return
        _busy.value=true
        operation {
            try {
                val current=_session.value ?: return@operation
                val result=repository.importSync(uri,current.book)
                saving?.cancel();pendingAudioSync=false
                reload()
                _message.value="已导入 ${result.sentenceCount} 句对应，正文覆盖 ${"%.1f".format(java.util.Locale.ROOT,result.coverage*100)}%。原有校准与进度保留。"
            } finally { _busy.value=false }
        }
    }
    fun importSubtitles(uri: Uri, audioChapterId: Long) = operation {
        val current = _session.value ?: return@operation
        val audio = current.audio.firstOrNull { it.id == audioChapterId } ?: error("请先选择字幕对应的音频章节")
        val count = repository.importSubtitles(uri, current.book, audio.id, audio.durationMs ?: 0)
        reload(); _message.value = "已建立 $count 处对应点，点间位置按比例估算。讲述有改写、插话时，请继续校准。"
    }
    fun remove(id: String) = operation {
        repository.remove(id)
        if (_session.value?.book?.id == id) _session.value = null
    }
    fun saveStyle(value: ReaderStyle) = operation { preferences.save(value) }
    fun importFont(uri: Uri) = operation {
        val path = withContext(Dispatchers.IO) {
            val target = File(app.filesDir, "reader-font-${java.util.UUID.randomUUID()}.ttf")
            val temp = File(app.filesDir, "reader-font.tmp")
            try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { NovelParser.readLimited(it, 24 * 1024 * 1024) }
                    ?: error("无法读取字体")
                temp.writeBytes(bytes)
                android.graphics.Typeface.createFromFile(temp)
                check(temp.renameTo(target)) { "字体保存失败" }
                target.absolutePath
            } finally { temp.delete() }
        }
        val old = style.value.fontPath
        preferences.save(style.value.copy(font = "custom", fontPath = path))
        if (old != null && old != path && File(old).parentFile == app.filesDir) withContext(Dispatchers.IO) { File(old).delete() }
    }
}
