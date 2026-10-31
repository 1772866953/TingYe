package com.tingye.app.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.tingye.app.LocalAppHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable internal fun ReaderFastScrollBar(state: LazyListState,count: Int,modifier: Modifier=Modifier) {
    val scope=rememberCoroutineScope()
    val haptics=LocalAppHaptics.current
    var job by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var chosen by remember { mutableIntStateOf(0) }
    val visible=state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val fraction=if(dragging) chosen.toFloat()/(count-1).coerceAtLeast(1)
        else state.firstVisibleItemIndex.toFloat()/(count-visible).coerceAtLeast(1)
    val color=MaterialTheme.colorScheme.onSurface
    fun jump(index: Int) {
        if(count<=0) return
        chosen=index.coerceIn(0,count-1)
        job?.cancel();job=scope.launch { state.scrollToItem(chosen) }
    }
    Box(modifier.semantics {
        contentDescription="快速定位小说章节"
        progressBarRangeInfo=ProgressBarRangeInfo(state.firstVisibleItemIndex.toFloat(),0f..(count-1).coerceAtLeast(1).toFloat())
        setProgress { jump(it.roundToInt());true }
    }.pointerInput(count) {
        awaitEachGesture {
            val down=awaitFirstDown();down.consume();dragging=true;haptics.selection()
            fun at(y: Float) { jump(((y/size.height).coerceIn(0f,1f)*(count-1)).roundToInt()) }
            at(down.position.y)
            do {
                val event=awaitPointerEvent()
                val change=event.changes.firstOrNull { it.id==down.id } ?: break
                change.consume();at(change.position.y)
            } while(change.pressed)
            dragging=false
        }
    }) {
        Canvas(Modifier.fillMaxSize().padding(vertical=8.dp)) {
            val track=3.dp.toPx();val thumb=5.dp.toPx()
            val thumbHeight=(size.height*visible/count.coerceAtLeast(1)).coerceIn(36.dp.toPx().coerceAtMost(size.height),size.height)
            drawRoundRect(color.copy(alpha=.12f),Offset((size.width-track)/2,0f),Size(track,size.height),CornerRadius(track))
            drawRoundRect(color.copy(alpha=if(dragging).85f else .5f),Offset((size.width-thumb)/2,(size.height-thumbHeight)*fraction.coerceIn(0f,1f)),
                Size(thumb,thumbHeight),CornerRadius(thumb))
        }
        if(dragging) Text("${chosen+1} / $count",Modifier.align(Alignment.CenterEnd).offset(x=(-32).dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest,RoundedCornerShape(10.dp)).padding(10.dp),
            color=MaterialTheme.colorScheme.onSurface,softWrap=false)
    }
}
