package com.tingye.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Exactly one clipped, live glass surface, composed outside the captured
 * scene while sharing the Activity's renderer and frame clock. */
@OptIn(ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable internal fun GlassSheet(onDismiss:()->Unit,inReader:Boolean=false,
    content:@Composable ColumnScope.(SheetDismiss)->Unit){
    val motion=remember{Animatable(0f)};val scope=rememberCoroutineScope()
    var preparing by remember{mutableStateOf(inReader)}
    val latestDismiss by rememberUpdatedState(onDismiss)
    var closing by remember{mutableStateOf(false)};var drag by remember{mutableFloatStateOf(0f)}
    val curve=remember{CubicBezierEasing(.22f,.7f,.25f,1f)}
    val overlays=LocalGlassOverlays.current
    // Freeze the parent at creation, before this entry is registered. A child
    // samples the visible parent, never itself or an unrelated later modal.
    val parent=remember(overlays){overlays?.entries?.lastOrNull()}
    val sheetScene=rememberLiveGlassBackdrop(false)
    val colors=MaterialTheme.colorScheme;val typography=MaterialTheme.typography;val shapes=MaterialTheme.shapes
    val popupSource=parent?.let{GlassSource(it.underlay.layers+it.backdrop)}?:LocalGlassPopupBackdrop.current
    val haptics=LocalAppHaptics.current;val speed=LocalMotionSpeed.current
    val dismiss:SheetDismiss={after->
        if(!closing){closing=true;scope.launch{motion.animateTo(0f,tween(250,easing=curve));latestDismiss();after()}}
    }
    val body:@Composable ()->Unit={
        // The reader's texture is already resolved. Prepare its controls at
        // sub-pixel opacity before travel so first-use glyph/filter work does
        // not consume visible movement. Player sheets retain their own path.
        LaunchedEffect(Unit){
            repeat(if(inReader)3 else 2){withFrameNanos{}}
            preparing=false
            if(inReader)withFrameNanos{}
            if(!closing)motion.animateTo(1f,tween(300,easing=curve))
        }
        BackHandler{dismiss{}}
        MaterialTheme(colorScheme=colors,typography=typography,shapes=shapes){
        CompositionLocalProvider(LocalGlassPopupBackdrop provides popupSource,LocalAppHaptics provides haptics,
            LocalMotionSpeed provides speed,LocalRippleConfiguration provides null){
        PopupGlass {
            BoxWithConstraints(Modifier.fillMaxSize().imePadding().graphicsLayer{alpha=if(preparing).001f else 1f}
                .pointerInput(preparing,closing){awaitPointerEventScope{while(true){
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(preparing || closing)event.changes.forEach{it.consume()}
                }}}){
                Box(Modifier.fillMaxSize().graphicsLayer{alpha=motion.value}.background(Color.Black.copy(alpha=.30f))
                    .clickable(remember{MutableInteractionSource()},indication=null){dismiss{}}
                    .semantics{contentDescription=if(inReader)"关闭阅读菜单" else "关闭菜单"})
                val threshold=with(LocalDensity.current){64.dp.toPx()}
                val shape=RoundedCornerShape(28.dp)
                val material=rememberLiveGlassBackdrop()
                val density=LocalDensity.current
                // Subtract in pixels rather than switching at the first IME
                // pixel; a boolean switch made the navigation gap jump.
                val imeBottom=WindowInsets.ime.getBottom(density)
                val navBottom=WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)
                val bottomGap=with(density){(navBottom-imeBottom).coerceAtLeast(0).toDp()}+8.dp
                val bottomGapPx=with(LocalDensity.current){bottomGap.toPx()}
                Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=10.dp)
                    .padding(bottom=bottomGap)
                    .fillMaxWidth().heightIn(max=maxHeight*.88f)
                    .graphicsLayer{translationY=if(preparing)0f else (size.height+bottomGapPx)*(1f-motion.value)+drag}
                    .recordGlassBackdrop(sheetScene)
                    .clip(shape).liquidGlass(shape=shape,panel=true,exported=material)
                    .pointerInput(Unit){awaitPointerEventScope{while(true)awaitPointerEvent()}},
                    color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,shape=shape,
                    tonalElevation=0.dp,shadowElevation=0.dp){
                    CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(listOf(material))){
                    Column(Modifier.fillMaxWidth().padding(bottom=16.dp)){
                        Box(Modifier.fillMaxWidth().height(32.dp).draggable(
                            rememberDraggableState{delta->if(!closing)drag=(drag+delta).coerceAtLeast(0f)},Orientation.Vertical,
                            enabled=!closing,onDragStopped={velocity->
                                if(drag>threshold || velocity>1000) dismiss{}
                                else animate(drag,0f,animationSpec=spring(dampingRatio=.92f,stiffness=420f)){value,_->drag=value}
                            }),contentAlignment=Alignment.Center){
                            Box(Modifier.size(32.dp,4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.28f),RoundedCornerShape(2.dp)))
                        }
                        Box(Modifier.weight(1f,fill=false)){Column{content(dismiss)}}
                    } }
                }
            }
        }
        } }
    }
    if(overlays==null)body() else{
        val latestBody by rememberUpdatedState(body)
        val entry=remember{GlassOverlayEntry(sheetScene,popupSource){latestBody()}}
        DisposableEffect(overlays,entry){
            overlays.entries.add(entry)
            onDispose{overlays.entries.remove(entry)}
        }
    }
}
