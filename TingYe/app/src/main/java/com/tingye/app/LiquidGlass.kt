package com.tingye.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.*

private class GlassTouch { var dragged=false;var samplingOffset:()->Offset={Offset.Zero} }
private val glassTouches=java.util.WeakHashMap<MutableInteractionSource,GlassTouch>()
internal fun acceptLiquidClick(interaction:MutableInteractionSource)=glassTouches[interaction]?.dragged!=true
internal fun glassSamplingOffset(interaction:MutableInteractionSource)=glassTouches[interaction]?.samplingOffset?.invoke()?:Offset.Zero

@Composable internal fun liquidPress(modifier:Modifier,interaction:MutableInteractionSource,
    pressedScale:Float=.93f,reboundDamping:Float=.68f,speed:Float=1f,captureDrag:Boolean=true,thumb:Boolean=false,externalPull:State<Offset>?=null):Modifier{
    var pressed by remember(interaction){mutableStateOf(false)}
    var held by remember(interaction){mutableStateOf(false)}
    var delta by remember(interaction){mutableStateOf(Offset.Zero)}
    val density=LocalDensity.current.density
    val active=pressed||held
    val drag=if(active)(externalPull?.value?:delta)/density else Offset.Zero
    val distance=hypot(drag.x,drag.y)
    val amount=1f-exp(-distance/32f)
    val ux=if(distance>0)drag.x/distance else 0f;val uy=if(distance>0)drag.y/distance else 0f
    // Desktop's symmetric strain matrix and 80% drag strength. Touch loading
    // and release have their own timing; no whole-control rotation.
    val base=if(thumb)1.10f else if(pressedScale<.9f).96f else .98f
    val along=base+(if(thumb).26f else .19f)*amount;val across=base-(if(thumb).075f else .055f)*amount
    val targetA=if(active)1f+((if(distance>0)across+(along-across)*ux*ux else base)-1f)*.8f else 1f
    val targetD=if(active)1f+((if(distance>0)across+(along-across)*uy*uy else base)-1f)*.8f else 1f
    val targetB=if(active)(along-across)*ux*uy*.8f else 0f
    // Load more gradually than the old 646-stiffness response. Release retains
    // velocity and uses the control's own rebound damping; targets and the
    // original shapes of independent round buttons remain unchanged.
    val physics=spring<Float>(dampingRatio=if(thumb){if(active).48f else .60f}else if(active).78f else reboundDamping.coerceIn(.58f,.86f),
        stiffness=(if(thumb){if(active)280f else 380f}else if(active)360f else 480f)*speed*speed,visibilityThreshold=.0001f)
    val a=animateFloatAsState(targetA,physics,label="玻璃纵向应变")
    val d=animateFloatAsState(targetD,physics,label="玻璃横向应变")
    val b=animateFloatAsState(targetB,physics,label="玻璃切向应变")
    val x=animateFloatAsState(if(active)9.6f*tanh(drag.x/42f)*density else 0f,physics,label="玻璃牵引横移")
    val y=animateFloatAsState(if(active)8f*tanh(drag.y/36f)*density else 0f,physics,label="玻璃牵引纵移")
    val matrix=remember{android.graphics.Matrix()};val values=remember{FloatArray(9)}
    val touch=remember(interaction){glassTouches.getOrPut(interaction){GlassTouch()}}
    touch.samplingOffset={Offset(x.value,y.value)}
    LaunchedEffect(interaction){
        var began=0L
        interaction.interactions.collectLatest{event->
            when(event){
                is PressInteraction.Press->{began=android.os.SystemClock.uptimeMillis();if(!held)touch.dragged=false;pressed=true}
                is PressInteraction.Release->{val remaining=105L-(android.os.SystemClock.uptimeMillis()-began);if(remaining>0)delay(remaining);pressed=false}
                is PressInteraction.Cancel->pressed=false
                is DragInteraction.Start->pressed=true
                is DragInteraction.Stop,is DragInteraction.Cancel->pressed=false
            }
        }
    }
    val gesture=if(externalPull!=null)Modifier else Modifier.pointerInput(interaction,thumb,captureDrag){
        awaitEachGesture{
            // Observe before scrolling consumes movement. Non-capturing rows
            // still yield to the list, but keep their visual finger tracking.
            val pass=PointerEventPass.Initial
            val down=awaitFirstDown(requireUnconsumed=false,pass=pass)
            delta=Offset.Zero;touch.dragged=false;held=true
            try{
                do{
                    val event=awaitPointerEvent(pass)
                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                    if(change.pressed){
                        delta=change.position-down.position
                        if(delta.getDistance()>5f*density)touch.dragged=true
                    }
                    if(captureDrag && touch.dragged)change.consume()
                }while(event.changes.any{it.pressed})
            }finally{held=false}
        }
    }
    // Keep the animated strain outside the parent's drawing boundary. Without
    // this layer, every small-button spring invalidates its whole glass sheet,
    // re-recording the panel backdrop and all sibling controls at each frame.
    return modifier.then(gesture).graphicsLayer().drawWithContent{
        values[0]=a.value;values[1]=b.value;values[2]=x.value
        values[3]=b.value;values[4]=d.value;values[5]=y.value
        values[6]=0f;values[7]=0f;values[8]=1f;matrix.setValues(values)
        drawIntoCanvas{canvas->
            val native=canvas.nativeCanvas;val saved=native.save()
            native.translate(size.width/2,size.height/2);native.concat(matrix);native.translate(-size.width/2,-size.height/2)
            this@drawWithContent.drawContent();native.restoreToCount(saved)
        }
    }
}


@Composable internal fun GlassSwitch(checked:Boolean,onCheckedChange:((Boolean)->Unit)?,modifier:Modifier=Modifier,enabled:Boolean=true){
    val interaction=remember{MutableInteractionSource()};val colors=MaterialTheme.colorScheme
    Switch(checked,onCheckedChange,liquidPress(modifier,interaction,.96f,captureDrag=false),enabled=enabled,interactionSource=interaction,
        colors=SwitchDefaults.colors(checkedTrackColor=colors.primary.copy(alpha=.45f),uncheckedTrackColor=colors.onSurface.copy(alpha=.10f),
            checkedBorderColor=Color.White.copy(alpha=.35f),uncheckedBorderColor=colors.outlineVariant,
            checkedThumbColor=Color.Transparent,uncheckedThumbColor=Color.Transparent),
        thumbContent={Box(Modifier.size(22.dp).liquidGlass(11.dp,interaction=interaction))})
}

@Composable internal fun GlassMenu(expanded:Boolean,onDismissRequest:()->Unit,modifier:Modifier=Modifier,
    content:@Composable ColumnScope.()->Unit){
    val overlays=LocalGlassOverlays.current
    val parent=remember(expanded){if(expanded)overlays?.entries?.lastOrNull() else null}
    val source=parent?.let{GlassSource(it.underlay.layers+it.backdrop)}?:LocalGlassPopupBackdrop.current
    CompositionLocalProvider(LocalGlassPopupBackdrop provides source){
    PopupGlass {
        val shape=RoundedCornerShape(22.dp)
        DropdownMenu(expanded,onDismissRequest,modifier.clip(shape).liquidGlass(shape=shape,panel=true),shape=shape,
            containerColor=Color.Transparent,tonalElevation=0.dp,shadowElevation=0.dp,content=content)
    }
    }
}

@Composable internal fun GlassAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,
    dismissButton:(@Composable ()->Unit)?=null,title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null){
    val overlays=LocalGlassOverlays.current
    val parent=remember{overlays?.entries?.lastOrNull()}
    val source=parent?.let{GlassSource(it.underlay.layers+it.backdrop)}?:LocalGlassPopupBackdrop.current
    CompositionLocalProvider(LocalGlassPopupBackdrop provides source){
    PopupGlass {
        val shape=RoundedCornerShape(28.dp);val material=rememberLiveGlassBackdrop()
        val surface=Modifier.clip(shape).liquidGlass(shape=shape,panel=true,exported=material)
        CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(listOf(material))){
            AlertDialog(onDismissRequest,confirmButton,modifier=surface,dismissButton=dismissButton,title=title,text=text,
                shape=shape,containerColor=Color.Transparent,tonalElevation=0.dp)
        }
    }
    }
}

@Composable internal fun GlassCard(modifier:Modifier=Modifier,shape:Shape=RoundedCornerShape(22.dp),
    colors:CardColors=CardDefaults.cardColors(containerColor=Color.Transparent),border:BorderStroke?=null,
    content:@Composable ColumnScope.()->Unit){
    val glass=LocalGlassAppearance.current.value
    Card(modifier.clip(shape).background(Color.White.copy(alpha=.055f*glass.veil)).then(if(border==null)Modifier.glassCardEdge(shape)else Modifier),shape=shape,colors=colors,
        border=border,content=content)
}
@Composable internal fun GlassCard(onClick:()->Unit,modifier:Modifier=Modifier,shape:Shape=RoundedCornerShape(22.dp),
    colors:CardColors=CardDefaults.cardColors(containerColor=Color.Transparent),border:BorderStroke?=null,
    interactionSource:MutableInteractionSource?=null,content:@Composable ColumnScope.()->Unit){
    val glass=LocalGlassAppearance.current.value
    Card(onClick=onClick,modifier=modifier.clip(shape).background(Color.White.copy(alpha=.055f*glass.veil)).then(if(border==null)Modifier.glassCardEdge(shape)else Modifier),shape=shape,colors=colors,
        border=border,interactionSource=interactionSource,content=content)
}

internal fun glassLightEndpoints(size:androidx.compose.ui.geometry.Size,light:GlassLightDirection):Pair<Offset,Offset>{
    val center=Offset(size.width/2,size.height/2)
    val extent=(abs(light.x)*size.width+abs(light.y)*size.height)/2
    val vector=Offset(light.x,light.y)*extent
    return (center-vector) to (center+vector)
}
internal fun glassCardEdgeBrush(ink:Color,alpha:Float,size:androidx.compose.ui.geometry.Size,light:GlassLightDirection,origin:Offset=Offset.Zero):Brush{
    val (start,end)=glassLightEndpoints(size,light)
    return Brush.linearGradient(listOf(ink.copy(alpha=(alpha*.70f).coerceIn(0f,1f)),ink.copy(alpha=alpha.coerceIn(0f,1f)),
        ink.copy(alpha=(alpha*1.2f).coerceIn(0f,1f))),start+origin,end+origin)
}
@Composable private fun Modifier.glassCardEdge(shape:Shape):Modifier{
    val lighting=LocalGlassLighting.current;val appearance=LocalGlassAppearance.current;val ink=MaterialTheme.colorScheme.onSurface
    return drawWithCache{
        val stroke=.6.dp.toPx()
        // Inset the same way as the shared player's card endpoint.
        val rect=androidx.compose.ui.geometry.Rect(stroke/2,stroke/2,size.width-stroke/2,size.height-stroke/2)
        val outline=shape.createOutline(size,layoutDirection,this)
        val path=Path().apply{when(outline){
            is Outline.Rounded->{
                val r=outline.roundRect
                fun inset(c:androidx.compose.ui.geometry.CornerRadius)=androidx.compose.ui.geometry.CornerRadius(
                    (c.x-stroke/2).coerceAtLeast(0f),(c.y-stroke/2).coerceAtLeast(0f))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(rect.left,rect.top,rect.right,rect.bottom,
                    inset(r.topLeftCornerRadius),inset(r.topRightCornerRadius),inset(r.bottomRightCornerRadius),inset(r.bottomLeftCornerRadius)))
            }
            is Outline.Rectangle->addRect(rect)
            is Outline.Generic->addPath(outline.path)
        }}
        onDrawWithContent{drawContent();drawPath(path,glassCardEdgeBrush(ink,.12f*appearance.value.shadow,size,lighting.value),style=Stroke(stroke))}
    }
}

@Composable internal fun GlassPanel(modifier:Modifier=Modifier,header:Boolean=false,edgeEmphasis:Boolean=false,content:@Composable ()->Unit){
    val shape=if(header)RectangleShape else RoundedCornerShape(24.dp);val material=rememberLiveGlassBackdrop()
    Surface(modifier.clip(shape).liquidGlass(shape=shape,panel=true,exported=material,rim=!header,edgeEmphasis=edgeEmphasis),color=Color.Transparent,shape=shape,tonalElevation=0.dp,shadowElevation=0.dp){
        CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(listOf(material)),content=content)
    }
}

@Composable internal fun GlassSliderThumb(interaction:MutableInteractionSource,enabled:Boolean,track:LiveGlassBackdrop,pull:State<Offset>){
    val background=LocalGlassBackdrop.current
    CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(background.layers+track)){
        Box(liquidPress(Modifier.size(30.dp),interaction,1.10f,thumb=true,captureDrag=false,externalPull=pull)
            .liquidGlass(15.dp,interaction=interaction).graphicsLayer{alpha=if(enabled)1f else .4f})
    }
}

@Composable internal fun Modifier.sliderGlassPull(pull:MutableState<Offset>):Modifier{
    val radius=with(LocalDensity.current){15.dp.toPx()}
    return pointerInput(pull,radius){awaitEachGesture{
        val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Final)
        try{do{
            val event=awaitPointerEvent(PointerEventPass.Final)
            val change=event.changes.firstOrNull{it.id==down.id}?:break
            if(change.pressed){
                val x=change.position.x;val y=change.position.y-size.height/2
                pull.value=Offset(x-x.coerceIn(radius,(size.width-radius).coerceAtLeast(radius)),y)
            }
        }while(event.changes.any{it.pressed})}finally{pull.value=Offset.Zero}
    }}
}

@Composable internal fun GlassSliderTrack(fraction:Float,enabled:Boolean,modifier:Modifier=Modifier){
    val primary=MaterialTheme.colorScheme.primary;val outline=MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(7.dp)){
        val r=androidx.compose.ui.geometry.CornerRadius(size.height/2)
        drawRoundRect(outline.copy(alpha=if(enabled).38f else .1f),cornerRadius=r)
        val width=size.width*fraction.coerceIn(0f,1f)
        if(width>0)drawRoundRect(Brush.horizontalGradient(listOf(primary.copy(alpha=.55f),primary.copy(alpha=.9f))),size=androidx.compose.ui.geometry.Size(width,size.height),cornerRadius=r)
    }
}
