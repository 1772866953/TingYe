@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tingye.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingye.app.*
import com.tingye.app.data.Book
import java.util.concurrent.TimeUnit

@Composable internal fun ReadingLibrarySheet(books: List<ReadingBook>, busy: Boolean, audioTarget: Long?,
    onDismiss: () -> Unit, onImport: () -> Unit, onOpen: (ReadingBook) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ReadingBook?>(null) }
    var reassigning by remember { mutableStateOf<ReadingBook?>(null) }
    var confirmReassign by remember { mutableStateOf<(() -> Unit)?>(null) }
    AppSheet(onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("阅读书架", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (audioTarget != null) "选择原文，与这本有声书关联" else "TXT · EPUB · HTML · Markdown", style = MaterialTheme.typography.bodySmall)
                }
                MotionFilledTonalButton(onClick = onImport) { Icon(Icons.Default.Add,null); Text("导入") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(query, { query=it }, singleLine=true, label={Text("搜索小说")},
                leadingIcon={Icon(Icons.Default.Search,null)}, shape=RoundedCornerShape(18.dp), modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f,fill=false), verticalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(bottom=12.dp)) {
                if (books.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical=28.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook,null,Modifier.size(44.dp),tint=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp)); Text("导入一本小说，随时切换听与读")
                    }
                }
                itemsIndexed(books.filter { it.title.contains(query,true) || it.author.contains(query,true) }, key={_,b->b.id}) { _,b ->
                    GlassCard(onClick={
                        if(audioTarget!=null && b.audioBookId!=null && b.audioBookId!=audioTarget){
                            reassigning=b; confirmReassign={dismiss{onOpen(b)}}
                        }else dismiss { onOpen(b) }
                    },shape=RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook,null,tint=MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text("${b.format} · ${b.chapterIndex+1}/${b.chapterCount} 章" + if(b.audioBookId!=null) " · 已关联听书" else "",
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            MotionIconButton(onClick={removing=b}) {Icon(Icons.Default.DeleteOutline,"移除小说：${b.title}")}
                        }
                    }
                }
            }
        }
    }
    removing?.let { b -> GlassAlertDialog(onDismissRequest={removing=null},title={Text("移除小说？")},
        text={Text("移除导入的正文、阅读进度和听读关联。原始文件与有声书不受影响。")},
        confirmButton={MotionTextButton(onClick={onDelete(b.id);removing=null}){Text("移除")}},
        dismissButton={MotionTextButton(onClick={removing=null}){Text("取消")}}) }
    reassigning?.let { b -> GlassAlertDialog(onDismissRequest={reassigning=null;confirmReassign=null},title={Text("更换听书关联？")},
        text={Text("《${b.title}》已关联另一本有声书。更换后会清除旧的对应关系与校准，阅读进度保留。")},
        confirmButton={MotionTextButton(onClick={confirmReassign?.invoke();reassigning=null;confirmReassign=null}){Text("更换关联")}},
        dismissButton={MotionTextButton(onClick={reassigning=null;confirmReassign=null}){Text("取消")}}) }
}

@Composable internal fun ReaderScreen(session: ReadingSession, reader: ReaderViewModel, playerVm: MainViewModel,
    audioBooks: List<Book>, playback: PlayerUi, pickFont: () -> Unit, pickSubtitles: (Long) -> Unit, pickSentenceSync: () -> Unit,
    listen: () -> Unit, close: () -> Unit) {
    val style by reader.style.collectAsStateWithLifecycle()
    val readerHaptics=LocalAppHaptics.current
    var menu by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    val message by reader.message.collectAsStateWithLifecycle()
    val density=LocalDensity.current
    val safeTop=WindowInsets.displayCutout.getTop(density)
    val safeBottom=WindowInsets.displayCutout.getBottom(density)
    val palette = readerPalettes.firstOrNull { it.id==style.theme } ?: readerPalettes.first()
    val dark = palette.id=="night"
    val ink=Color(palette.ink)
    val paper=Color(palette.paper)
    val selected=androidx.compose.ui.graphics.lerp(paper,ink,if(dark) .22f else .14f)
    val muted=androidx.compose.ui.graphics.lerp(paper,ink,.07f)
    // Specify container roles too: Material defaults otherwise introduce purple selections.
    val colors = (if(dark) darkColorScheme() else lightColorScheme()).copy(
        primary=ink, onPrimary=paper, primaryContainer=selected,onPrimaryContainer=ink,
        secondary=ink,onSecondary=paper,secondaryContainer=selected,onSecondaryContainer=ink,
        tertiary=ink,onTertiary=paper,tertiaryContainer=selected,onTertiaryContainer=ink,
        background=paper,onBackground=ink,surface=paper,onSurface=ink,
        surfaceVariant=muted,onSurfaceVariant=ink.copy(alpha=.7f),
        surfaceContainer=muted,surfaceContainerHigh=muted,surfaceContainerHighest=selected,
        surfaceContainerLow=paper,surfaceContainerLowest=paper,surfaceTint=Color.Transparent,
        outline=ink.copy(alpha=.35f),outlineVariant=ink.copy(alpha=.15f))
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event-> if(event==Lifecycle.Event.ON_STOP) reader.flush() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); reader.flush() }
    }
    BackHandler { if(panel.isNotEmpty()) panel="" else close() }
    fun pauseForNavigation() { reader.beginReadingInteraction(); playerVm.pauseForReading() }
    MaterialTheme(colorScheme=colors) {
        GlassPaper { CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        val paperBackdrop=LocalGlassBackdrop.current.layers.lastOrNull()
        Box(Modifier.fillMaxSize().background(Color(palette.paper)).pointerInput(Unit) {
            awaitPointerEventScope { while(true) awaitPointerEvent() }
        }) {
            // A fixed viewport keeps text and page boundaries still while chrome moves over it.
                AndroidView(factory={context->ReaderCanvas(context)}, modifier=Modifier.fillMaxSize().readerGlassSource(),
                    update={canvas->
                        canvas.onBackdropInvalidated={paperBackdrop?.let{it.paperRevision++}}
                        canvas.onMenu={menu=!menu}
                        canvas.onListenAtOffset={offset->
                            val target=reader.audioTargetAt(offset)
                            if(target!=null && session.book.audioBookId!=null){readerHaptics.action();reader.setFollowing(true);playerVm.playBook(session.book.audioBookId,target.index,true,target.positionMs)}
                            else panel="sync"
                        }
                        canvas.onUserTouch={pauseForNavigation();menu=false}
                        canvas.onVisiblePosition={offset,end,user->reader.position(offset,user,end)}
                        canvas.onBoundary={direction->
                            pauseForNavigation()
                            reader.goTo(session.location.chapter+direction,if(direction<0) Int.MAX_VALUE else 0)
                        }
                        canvas.bind(session.location.chapter,session.texts,style,session.location.offset,session.navigation,
                            session.chapters.size,safeTop,safeBottom)
                    })
            val easing=CubicBezierEasing(.22f,.7f,.25f,1f)
            AnimatedVisibility(menu,Modifier.align(Alignment.TopCenter),
                enter=slideInVertically(tween(320,easing=easing)){-it},
                exit=slideOutVertically(tween(260,easing=easing)){-it}) {
                GlassPanel(edgeEmphasis=true) {
                    Row(Modifier.fillMaxWidth().padding(WindowInsets.displayCutout.asPaddingValues()).height(62.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        MotionIconButton(onClick=close){Icon(Icons.AutoMirrored.Filled.ArrowBack,"退出阅读")}
                        Column(Modifier.weight(1f)) {
                            Text(session.book.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(session.chapters[session.location.chapter].title,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        if(session.book.audioBookId!=null) MotionIconButton(onClick=listen){Icon(Icons.Default.Headphones,"返回听书")}
                        MotionIconButton(onClick={panel="style"}){Icon(Icons.Default.TextFields,"阅读设置")}
                    }
                }
            }
            AnimatedVisibility(menu,Modifier.align(Alignment.BottomCenter),
                enter=slideInVertically(tween(320,easing=easing)){it},
                exit=slideOutVertically(tween(260,easing=easing)){it}) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal=8.dp,vertical=6.dp)){
                    GlassSegments(listOf(
                        GlassSegment("目录",Icons.AutoMirrored.Filled.List),
                        GlassSegment(if(playback.isPlaying && playback.bookId==session.book.audioBookId)"暂停收听" else "从这里听",
                            if(playback.isPlaying && playback.bookId==session.book.audioBookId)Icons.Default.Pause else Icons.Default.Headphones,weight=1.2f),
                        GlassSegment("听读同步",Icons.Default.Sync,weight=1.2f)),null,{choice->when(choice){
                        0->panel="chapters"
                        1->{
                            val target=reader.audioTarget()
                            if(playback.bookId==session.book.audioBookId && playback.isPlaying){pauseForNavigation()}
                            else if(target!=null && session.book.audioBookId!=null){
                                reader.setFollowing(true)
                                playerVm.playBook(session.book.audioBookId,target.index,true,target.positionMs)
                            } else panel="sync"
                        }
                        2->panel="sync"
                    }},compact=true,selectionMode=false,panelMaterial=true)
                }
            }
            when(panel){
                "style" -> ReaderStyleSheet(style,reader::saveStyle,pickFont){panel=""}
                "chapters" -> ReaderChaptersSheet(session,{chapter->pauseForNavigation();reader.goTo(chapter)}){panel=""}
                "sync" -> ReaderSyncSheet(session,reader,playerVm,audioBooks,playback,pickSubtitles,pickSentenceSync){panel=""}
            }
            message?.let { text -> ReaderSheet(reader::dismissMessage) { dismiss ->
                Column(Modifier.fillMaxWidth().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("听读同步",style=MaterialTheme.typography.titleLarge)
                    Text(text)
                    MotionTextButton(onClick={dismiss { }},modifier=Modifier.align(Alignment.End)) { Text("知道了") }
                }
            } }
        }
        }
    }
}
}

@Composable private fun ReaderChaptersSheet(session:ReadingSession, choose:(Int)->Unit, close:()->Unit){
    var query by remember{mutableStateOf("")}
    val list=androidx.compose.foundation.lazy.rememberLazyListState(session.location.chapter)
    ReaderSheet(close){dismiss->
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("小说目录",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("章节名或序号")},singleLine=true,shape=RoundedCornerShape(18.dp))
            val filtered=remember(session.chapters,query) { session.chapters.mapIndexed{index,item->index to item}.filter{(i,t)->query.isBlank()||t.title.contains(query,true)||(i+1).toString()==query} }
            val listHeight=(filtered.size*60+12).coerceIn(72,440).dp
            Box(Modifier.fillMaxWidth().height(listHeight)) {
            LazyColumn(Modifier.fillMaxSize().padding(end=28.dp),state=list,contentPadding=PaddingValues(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(filtered.isEmpty())item{Text("没有匹配的章节",Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                itemsIndexed(filtered,key={_,pair->pair.first}){_,pair->
                    MotionTextButton(onClick={dismiss{choose(pair.first)}},modifier=Modifier.fillMaxWidth()){
                        Text("${pair.first+1}  ${pair.second.title}",Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,
                            color=if(pair.first==session.location.chapter)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if(pair.first==session.location.chapter)Icon(Icons.Default.Bookmark,null,Modifier.size(18.dp))
                    }
                }
            }
            if(filtered.size>8) ReaderFastScrollBar(list,filtered.size,Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight())
            }
        }
    }
}

@Composable private fun ReaderStyleSheet(style:ReaderStyle, save:(ReaderStyle)->Unit, pickFont:()->Unit, close:()->Unit){
    var draft by remember(style){mutableStateOf(style)}
    ReaderSheet(close){_->
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState(),overscrollEffect=null).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("阅读设置",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            GlassSectionLabel("翻页方式")
            GlassOptions(listOf("slide" to "平移","curl" to "仿真","scroll" to "上下滚动"),draft.animation,
                {draft=draft.copy(animation=it);save(draft)})
            GlassSectionLabel("阅读背景")
            GlassSegments(readerPalettes.map{GlassSegment(it.label,swatch=Color(it.paper))},
                readerPalettes.indexOfFirst{it.id==draft.theme}.takeIf{it>=0},
                {draft=draft.copy(theme=readerPalettes[it].id);save(draft)},height=76.dp)
            GlassSectionLabel("字体")
            val fonts=listOf("sans" to "系统黑体","serif" to "系统宋体","mono" to "等宽")+
                if(style.fontPath!=null)listOf("custom" to "自定义") else emptyList()
            GlassOptions(fonts,draft.font,{draft=draft.copy(font=it);save(draft)})
            GlassActionRow("导入字体",pickFont,detail="TTF / OTF")
            HorizontalDivider(Modifier.padding(vertical=4.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("字号");Text("${draft.fontSize.toInt()}",style=MaterialTheme.typography.labelLarge)}
            AppSlider(draft.fontSize,{draft=draft.copy(fontSize=it)},valueRange=14f..36f,steps=21,onValueChangeFinished={save(draft)})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("行距");Text("${"%.2f".format(draft.spacing)}",style=MaterialTheme.typography.labelLarge)}
            AppSlider(draft.spacing,{draft=draft.copy(spacing=it)},valueRange=1.2f..2.2f,onValueChangeFinished={save(draft)})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("页边距");Text("${draft.margin.toInt()}",style=MaterialTheme.typography.labelLarge)}
            AppSlider(draft.margin,{draft=draft.copy(margin=it)},valueRange=12f..42f,onValueChangeFinished={save(draft)})
        }
    }
}

private fun timeLabel(ms:Long)="%02d:%02d".format(TimeUnit.MILLISECONDS.toMinutes(ms),TimeUnit.MILLISECONDS.toSeconds(ms)%60)

@Composable private fun ReaderSyncSheet(session:ReadingSession, reader:ReaderViewModel, vm:MainViewModel,
    books:List<Book>, playback:PlayerUi, subtitles:(Long)->Unit, importSync:()->Unit, close:()->Unit){
    val haptics=LocalAppHaptics.current
    var choosingBook by remember{mutableStateOf(false)}
    var choosingChapter by remember{mutableStateOf(false)}
    var clearingManual by remember{mutableStateOf(false)}
    var clearingCorrespondence by remember{mutableStateOf(false)}
    var advanced by remember{mutableStateOf(false)}
    var audioIndex by remember(session.book.audioBookId,session.location.chapter){mutableIntStateOf(
        if(playback.bookId==session.book.audioBookId && playback.chapterId!=null) playback.index
        else session.audioReference?.index ?: 0)}
    val audio=session.audio.getOrNull(audioIndex)
    var draftTime by remember(audio?.id){mutableFloatStateOf(if(playback.chapterId==audio?.id)playback.position.toFloat()
        else session.audioReference?.takeIf { it.chapterId==audio?.id }?.positionMs?.toFloat() ?: 0f)}
    val target=reader.audioTarget(session)
    val importing by reader.busy.collectAsStateWithLifecycle()
    ReaderSheet(close){_->
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState(),overscrollEffect=null).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("听读同步",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            GlassActionRow(if(session.book.audioBookId==null)"关联有声书" else "更换关联",{choosingBook=!choosingBook},
                detail=books.firstOrNull{it.id==session.book.audioBookId}?.title?:"尚未关联有声书")
            if(choosingBook){
                Column(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState())){
                    books.forEach{book->GlassActionRow(book.title,{reader.link(book.id);choosingBook=false})}
                    if(books.isEmpty())Text("请先从首页导入有声书。")
                }
            }
            if(session.book.audioBookId!=null){
                Row(verticalAlignment=Alignment.CenterVertically){Text("联动进度",Modifier.weight(1f));GlassSwitch(session.book.syncEnabled,{haptics.selection();reader.syncOptions(it,session.book.allowApproximate)},modifier=Modifier.semantics{contentDescription="联动进度"})}
                Text("阅读时暂停听书；翻页停稳后保存对应收听位置。点“从这里听”可边听边读。",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically){Text("允许大致同步",Modifier.weight(1f));GlassSwitch(session.book.allowApproximate,{haptics.selection();reader.syncOptions(session.book.syncEnabled,it)},modifier=Modifier.semantics{contentDescription="允许大致同步"})}
                Text("按全书位置和音频总时长估算，不要求章节数量相同。讲述增删会产生偏差；关闭后仅使用电脑逐句对应、校准或字幕区间。",style=MaterialTheme.typography.bodySmall)
                Text(when{target==null->"当前页尚无可用对应位置";target.approximate->"当前页可定位到约 ${timeLabel(target.positionMs)}";else->"当前页已校准：${timeLabel(target.positionMs)}"},color=MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                GlassSectionLabel("电脑逐句对应")
                MotionFilledTonalButton(onClick={if(!importing)importSync()},modifier=Modifier.fillMaxWidth()){
                    Icon(Icons.Default.FileOpen,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(importing)"正在校验并建立索引" else "导入 .tysync 对应文件")}
                if(importing)LinearProgressIndicator(Modifier.fillMaxWidth())
                session.imported?.let { Text("已导入 ${it.sentenceCount} 句 · 正文覆盖 ${"%.1f".format(java.util.Locale.ROOT,it.coverage*100)}%"+if(it.complete)"" else " · 部分结果",style=MaterialTheme.typography.bodySmall) }
                Text("开启联动后，翻页停稳会定位本页首个已匹配句子；听书暂停会更新阅读位置。句间短暂停顿可使用相邻句定位。未覆盖内容仅在允许大致同步时估算，否则保留原位置。长按正文可从该句收听。",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                GlassActionRow(if(advanced)"收起校准选项" else "更多校准选项",{advanced=!advanced},detail="初始对应、位置校准与字幕")
                if(advanced){
                GlassSectionLabel("建立初始对应")
                GlassSegments(listOf(GlassSegment("自动建立对应"),GlassSegment("全书比例估算")),null,
                    {reader.matchChapters(it==1,playback)},selectionMode=false)
                Text("优先核对章节标题与编号范围；音频集数与原文目录不同时，使用全书比例估算。",style=MaterialTheme.typography.bodySmall)
                GlassActionRow("定位当前收听",{reader.locateAudio(playback)})
                Text("当前原文：${session.chapters[session.location.chapter].title}",style=MaterialTheme.typography.bodySmall)
                GlassActionRow("选择对应音频章节",{choosingChapter=true},detail=audio?.title)
                HorizontalDivider()
                Text("位置校准",fontWeight=FontWeight.SemiBold)
                Text("把当前页开头与音频中的同一段内容对应。在另一处再校准即可连接中间的进度。支持一集跨多章、多集讲一章，也可同时跨越双方章节边界。",style=MaterialTheme.typography.bodySmall)
                if(session.points.any { it.kind=="manual" }) MotionTextButton(onClick={clearingManual=true}) { Text("管理手动校准") }
                if(audio!=null){
                    val duration=(audio.durationMs?:0).toFloat()
                    Text("音频位置 ${timeLabel(draftTime.toLong())} / ${timeLabel(duration.toLong())}")
                    AppSlider(draftTime.coerceIn(0f,duration.coerceAtLeast(1f)),{draftTime=it},valueRange=0f..duration.coerceAtLeast(1f),enabled=duration>0)
                    GlassSegments(listOf(GlassSegment("试听此处"),GlassSegment("取播放位置",enabled=playback.chapterId==audio.id)),null,
                        {if(it==0)session.book.audioBookId?.let{vm.playBook(it,audioIndex,true,draftTime.toLong())}
                         else if(playback.chapterId==audio.id)draftTime=playback.position.toFloat()},selectionMode=false)
                    MotionFilledTonalButton(onClick={vm.pauseForReading();reader.calibrate(audio.id,draftTime.toLong())},modifier=Modifier.fillMaxWidth()){Text("校准当前页开头")}
                    GlassActionRow("导入此集字幕",{subtitles(audio.id)},detail="SRT / VTT")
                    Text("在整本小说中查找字幕短句，无需拆分小说文件。改写、插话和匹配不明的内容需要手动校准。",style=MaterialTheme.typography.bodySmall)
                }
                }
                Text("已保存 ${session.points.size} 处对应",style=MaterialTheme.typography.bodySmall)
                GlassActionRow("删除对应数据",{clearingCorrespondence=true},destructive=true)
                GlassActionRow("解除关联",{reader.link(null)},destructive=true)
            }
        }
    }
    if(clearingCorrespondence) ReaderSheet({clearingCorrespondence=false}) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("删除本书对应数据？",style=MaterialTheme.typography.titleLarge)
            Text("将删除《${session.book.title}》的 ${session.points.size} 个对应点（含手动校准、字幕、历史语音和估算对应），以及电脑导入的逐句索引。",style=MaterialTheme.typography.bodyMedium)
            Text("小说、音频和阅读/收听进度保留，听读联动会关闭。之后可重新建立对应。",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MotionTextButton(onClick={dismiss { clearingCorrespondence=false }}) { Text("取消") }
                MotionFilledTonalButton(onClick={reader.clearCorrespondence();dismiss { clearingCorrespondence=false }}) { Text("删除对应点和记录") }
            }
        }
    }
    if(clearingManual) ReaderSheet({clearingManual=false}) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("手动校准",style=MaterialTheme.typography.titleLarge)
            session.points.filter { it.kind=="manual" }.take(40).forEach { point ->
                Text("${session.chapters.getOrNull(point.textChapter)?.title.orEmpty()} · 第 ${session.audio.indexOfFirst { it.id==point.audioChapterId }+1} 集 ${timeLabel(point.positionMs)}",style=MaterialTheme.typography.bodyMedium)
            }
            Text("可清除本书的手动校准并重新设置。电脑逐句索引、字幕对应与阅读进度均保留。",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MotionTextButton(onClick={dismiss { clearingManual=false }}) { Text("保留") }
                MotionFilledTonalButton(onClick={reader.clearManualCalibration();dismiss { clearingManual=false }}) { Text("清除手动校准") }
            }
        }
    }
    if(choosingChapter)ReaderSheet({choosingChapter=false}){dismiss->
        var query by remember{mutableStateOf("")}
        val filtered=remember(session.audio,query){session.audio.mapIndexed{i,c->i to c}.filter{(i,c)->c.title.contains(query,true)||(i+1).toString()==query}}
        val list=androidx.compose.foundation.lazy.rememberLazyListState()
        Column(Modifier.fillMaxWidth().heightIn(max=580.dp).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("选择音频章节",style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("章节名或序号")},singleLine=true,shape=RoundedCornerShape(18.dp),leadingIcon={Icon(Icons.Default.Search,null)})
            Box(Modifier.fillMaxWidth().height((filtered.size*72+12).coerceIn(72,380).dp)){
            LazyColumn(Modifier.fillMaxSize().padding(end=if(filtered.size>5)28.dp else 0.dp),state=list,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=12.dp)){
                if(filtered.isEmpty())item{Text("没有匹配的章节",Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                itemsIndexed(filtered,key={_,pair->pair.first}){_,pair->
                    MotionTextButton(onClick={audioIndex=pair.first;dismiss{choosingChapter=false}},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){
                        Text("${pair.first+1}  ${pair.second.title}",Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(pair.first==audioIndex)Icon(Icons.Default.Check,null,Modifier.size(20.dp))
                    }
                }
            }
            if(filtered.size>5)ReaderFastScrollBar(list,filtered.size,Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight())
            }
        }
    }
}
