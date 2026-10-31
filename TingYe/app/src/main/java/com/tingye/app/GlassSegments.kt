package com.tingye.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

@Immutable internal data class GlassSegment(val label:String,val icon:ImageVector?=null,
    val description:String=label,val weight:Float=1f,val enabled:Boolean=true,val swatch:Color?=null)

/** iOS-style shared rail. The pressed lens samples the actual label display
 * list, so text and background refract together. Selection/actions commit on
 * release, never on every crossed item. Independent icon buttons are untouched. */
@Composable internal fun GlassSegments(items:List<GlassSegment>,selected:Int?,onSelect:(Int)->Unit,
    modifier:Modifier=Modifier,height:Dp=56.dp,compact:Boolean=items.size>=4,selectionMode:Boolean=true,panelMaterial:Boolean=false){
    if(items.isEmpty())return
    val density=LocalDensity.current
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    val haptics=LocalAppHaptics.current
    val latestItems by rememberUpdatedState(items)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    val material=rememberLiveGlassBackdrop()
    val tint=rememberLiveGlassBackdrop(false)
    val labels=rememberLiveGlassBackdrop(false)
    val underlying=LocalGlassBackdrop.current
    val shape=remember{RoundedCornerShape(50)}
    val ink=MaterialTheme.colorScheme.onSurface
    val accent=MaterialTheme.colorScheme.primary
    var held by remember{mutableStateOf(false)}
    var hover by remember{mutableIntStateOf(selected?:0)}
    BoxWithConstraints(modifier.fillMaxWidth().height(height)){
        val widthPx=constraints.maxWidth.toFloat()
        val heightPx=with(density){height.toPx()}
        val padding=with(density){4.dp.toPx()}
        val contentWidth=(widthPx-2*padding).coerceAtLeast(1f)
        val contentHeight=(heightPx-2*padding).coerceAtLeast(1f)
        val weights=items.map{it.weight.coerceAtLeast(.1f)}
        val total=weights.sum()
        val widths=weights.map{contentWidth*it/total}
        val centers=widths.mapIndexed{i,w->
            val x=padding+widths.take(i).sum()+w/2
            if(rtl)widthPx-x else x
        }
        fun nearest(x:Float)=centers.indices.minByOrNull{abs(centers[it]-x)}?:0
        fun widthAt(x:Float):Float{
            val sorted=centers.indices.sortedBy{centers[it]}
            val right=sorted.firstOrNull{centers[it]>=x}?:return widths[sorted.last()]
            val position=sorted.indexOf(right)
            if(position==0)return widths[right]
            val left=sorted[position-1]
            val t=((x-centers[left])/(centers[right]-centers[left])).coerceIn(0f,1f)
            return widths[left]+(widths[right]-widths[left])*t
        }
        val leftEnd=centers.min();val rightEnd=centers.max()
        val selectedIndex=selected?.takeIf{it in items.indices}
        val motion=remember(widthPx,heightPx,weights,rtl){
            val index=selectedIndex?:0
            GlassSegmentMotion(centers[index],widths[index],density.density)
        }
        var pose by remember(motion){mutableStateOf(motion.frame())}
        var running by remember(motion){mutableStateOf(false)}
        val lensStrain=remember{android.graphics.Matrix()}
        val lensValues=remember{FloatArray(9)}
        val dockStrain=remember{android.graphics.Matrix()}
        val dockValues=remember{FloatArray(9)}
        fun lensMatrix(cx:Float,cy:Float):android.graphics.Matrix{
            val a=pose.stretchX;val d=pose.stretchY;val b=pose.shear
            lensValues[0]=a;lensValues[1]=b;lensValues[2]=(1f-a)*cx-b*cy
            lensValues[3]=b;lensValues[4]=d;lensValues[5]=(1f-d)*cy-b*cx
            lensValues[6]=0f;lensValues[7]=0f;lensValues[8]=1f
            lensStrain.setValues(lensValues)
            return lensStrain
        }
        fun wake(){pose=motion.frame();running=true}
        LaunchedEffect(motion,running){
            if(running){
                var previous=0L
                while(running){
                    withFrameNanos{now->
                        val dt=if(previous==0L)1f/60f else (now-previous)/1_000_000_000f
                        previous=now
                        val more=motion.step(dt)
                        pose=motion.frame()
                        if(!more)running=false
                    }
                }
            }
        }
        LaunchedEffect(motion,selectedIndex){
            selectedIndex?.let{motion.selection(centers[it],widths[it]);wake()}
        }
        fun lensRect():Rect{
            val t=pose.lift.coerceIn(-.08f,1.16f)
            val h=contentHeight*(1f+.40f*t)
            val expanded=pose.width.coerceAtLeast(1f)*(1f+.28f*t)
            // At an end stop, the soft body compresses instead of stretching
            // through the sheet's clipping edge. Keep the complete strained
            // silhouette inside its rail; central drags retain their full load.
            val span=2f*minOf(pose.x,widthPx-pose.x).coerceAtLeast(contentHeight*.45f)
            val limit=((span-abs(pose.shear)*h)/pose.stretchX.coerceAtLeast(.5f)).coerceAtLeast(1f)
            val w=minOf(expanded,limit)
            val y=heightPx/2+pose.y
            return Rect(pose.x-w/2,y-h/2,pose.x+w/2,y+h/2)
        }
        // A spring can cross zero while it is still relaxing. Keep the lens
        // and its label mask together until both deformation axes settle,
        // including the small negative return, to avoid a terminal glyph snap.
        val lensVisible=held || abs(pose.contact)>.001f || abs(pose.lift)>.001f ||
            abs(pose.stretchX-1f)>.0002f || abs(pose.stretchY-1f)>.0002f || abs(pose.shear)>.0002f
        // Transform the complete dock after recording its optical sources.
        // Keeping the input surface outside this drawing transform prevents
        // scaling under the finger from feeding back into gesture coordinates.
        // Recording stays in one logical coordinate system, so the real label
        // and material textures remain aligned while the whole dock stretches.
        Box(Modifier.matchParentSize().graphicsLayer().drawWithContent{
            val sx=1f+.06f*pose.dock+.18f*(pose.stretchX-1f)
            val sy=1f+.06f*pose.dock+.18f*(pose.stretchY-1f)
            val b=.18f*pose.shear
            dockValues[0]=sx;dockValues[1]=b;dockValues[2]=pose.dockX
            dockValues[3]=b;dockValues[4]=sy;dockValues[5]=pose.dockY
            dockValues[6]=0f;dockValues[7]=0f;dockValues[8]=1f;dockStrain.setValues(dockValues)
            drawIntoCanvas{canvas->
                val native=canvas.nativeCanvas;val saved=native.save()
                native.translate(size.width/2,size.height/2);native.concat(dockStrain)
                native.translate(-size.width/2,-size.height/2)
                this@drawWithContent.drawContent()
                native.restoreToCount(saved)
            }
        }){
        // One clear capsule replaces the separate tile behind every option.
        Box(Modifier.matchParentSize().liquidGlass(shape=shape,exported=material,panel=panelMaterial,edgeEmphasis=panelMaterial))
        Canvas(Modifier.matchParentSize().recordGlassBackdrop(tint)){
            if(selectedIndex!=null){
                val alpha=(1f-pose.lift).coerceIn(0f,1f)
                val w=pose.width
                // The resting fill is the same moving body as the raised lens.
                // No independent old-selection -> new-selection animation.
                drawIntoCanvas{canvas->
                    val native=canvas.nativeCanvas;val saved=native.save()
                    native.concat(lensMatrix(pose.x,heightPx/2+pose.y))
                    drawRoundRect(ink.copy(alpha=.105f*alpha),Offset(pose.x-w/2,padding+pose.y),
                        Size(w,contentHeight),CornerRadius(contentHeight/2))
                    native.restoreToCount(saved)
                }
            }
        }
        Box(Modifier.matchParentSize().drawWithContent{
            if(lensVisible){
                val r=lensRect();val hole=Path().apply{addRoundRect(RoundRect(r,r.height/2,r.height/2))}
                hole.asAndroidPath().transform(lensMatrix(r.center.x,r.center.y))
                clipPath(hole,ClipOp.Difference){this@drawWithContent.drawContent()}
            }else drawContent()
        }.recordGlassBackdrop(labels)){
            Row(Modifier.fillMaxSize().padding(4.dp),verticalAlignment=Alignment.CenterVertically){
                items.forEachIndexed{index,item->key(index){
                    val active=if(held)hover==index else selectedIndex==index
                    TextButton(onClick={if(item.enabled)haptics.click{latestSelect(index)}},enabled=item.enabled,
                        modifier=Modifier.weight(weights[index]).fillMaxHeight().semantics{
                            if(selectionMode){this.selected=selectedIndex==index;role=Role.RadioButton}
                            contentDescription=item.description
                        },shape=shape,contentPadding=PaddingValues(horizontal=3.dp,vertical=0.dp),
                        interactionSource=remember{MutableInteractionSource()},
                        colors=ButtonDefaults.textButtonColors(contentColor=if(active)accent else ink)){
                        if(item.swatch!=null){
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
                                Canvas(Modifier.size(22.dp)){
                                    drawRoundRect(item.swatch,cornerRadius=CornerRadius(6.dp.toPx()))
                                    drawRoundRect(ink.copy(alpha=.22f),cornerRadius=CornerRadius(6.dp.toPx()),
                                        style=androidx.compose.ui.graphics.drawscope.Stroke(.5.dp.toPx()))
                                }
                                Text(item.label,style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)
                            }
                        }else{
                            item.icon?.let{Icon(it,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp))}
                            Text(item.label,style=MaterialTheme.typography.labelLarge.copy(fontSize=if(compact)12.sp else 14.sp),
                                fontWeight=FontWeight.SemiBold,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }}
            }
        }
        if(lensVisible){
            val rect=lensRect()
            CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(underlying.layers+listOf(material,tint,labels))){
                Box(Modifier.offset{IntOffset(rect.left.roundToInt(),rect.top.roundToInt())}
                    // The raised lens deliberately protrudes beyond the rail.
                    // Clear the rail's height constraint without Compose's
                    // implicit centring of an oversized requiredSize child.
                    .wrapContentSize(Alignment.TopStart,unbounded=true)
                    .requiredSize(with(density){rect.width.toDp()},with(density){rect.height.toDp()})
                    .graphicsLayer().drawWithContent{
                        // Deform the rendered lens, including refracted labels
                        // and shadow, rather than replacing it with another
                        // circular-ended capsule at every width/height change.
                        drawIntoCanvas{canvas->
                            val native=canvas.nativeCanvas;val saved=native.save()
                            native.concat(lensMatrix(size.width/2,size.height/2))
                            this@drawWithContent.drawContent();native.restoreToCount(saved)
                        }
                    }
                    .graphicsLayer{
                        shadowElevation=3.dp.toPx()*pose.lift.coerceIn(0f,1f)
                        this.shape=shape;clip=false
                        ambientShadowColor=Color.Black.copy(alpha=.12f);spotShadowColor=Color.Black.copy(alpha=.16f)
                    }.liquidGlass(shape=shape,edgeEmphasis=true,magnification={1f+.13f*pose.lift.coerceIn(0f,1f)},
                        optics={maxOf(pose.lift,pose.contact*.65f).coerceIn(0f,1f)},blurOverride=0f)
                    .clearAndSetSemantics{})
            }
        }
        }
        // An invisible sibling owns touch only. Native buttons retain keyboard
        // and accessibility activation, and there is no duplicate click path.
        Box(Modifier.matchParentSize().pointerInput(motion,widthPx,heightPx,weights,rtl){
            awaitEachGesture{
                val down=awaitFirstDown(requireUnconsumed=true)
                val start=nearest(down.position.x)
                if(!latestItems[start].enabled)return@awaitEachGesture
                hover=start;held=true;motion.press(centers[start],widths[start]);wake()
                var horizontal=false;var cancelled=false;var released=false
                try{
                    while(true){
                        val event=awaitPointerEvent()
                        val change=event.changes.firstOrNull{it.id==down.id}?:break
                        val delta=change.position-down.position
                        if(change.isConsumed){cancelled=true;break}
                        if(!horizontal && delta.getDistance()>2f*density.density){
                            if(abs(delta.y)>abs(delta.x)){cancelled=true;break}
                            horizontal=true
                        }
                        if(horizontal){
                            val raw=centers[start]+delta.x
                            val clamped=raw.coerceIn(leftEnd,rightEnd)
                            val pull=14f*density.density*tanh((raw-clamped)/(40f*density.density))
                            val x=clamped+pull
                            val y=6f*density.density*tanh(delta.y/(32f*density.density))
                            motion.drag(x,widthAt(x),y,widthPx,pull,delta.y);wake();hover=nearest(x)
                            if(change.pressed)change.consume()
                        }
                        if(!change.pressed){
                            cancelled=change.position.y < -32f*density.density || change.position.y>heightPx+32f*density.density
                            if(!cancelled)change.consume()
                            released=true;break
                        }
                    }
                }finally{
                    val commit=released && !cancelled && latestItems[hover].enabled
                    val chosen=hover
                    val target=if(commit)chosen else latestSelected?.takeIf{it in centers.indices}?:start
                    held=false;motion.release(centers[target],widths[target],minimumPress=commit);wake()
                    if(commit && (latestSelected==null || latestSelected!=chosen))haptics.click{latestSelect(chosen)}
                }
            }
        })
    }
}

@Composable internal fun <T> GlassOptions(options:List<Pair<T,String>>,selected:T?,onSelect:(T)->Unit,
    modifier:Modifier=Modifier){
    GlassSegments(options.map{GlassSegment(it.second)},options.indexOfFirst{it.first==selected}.takeIf{it>=0},
        {onSelect(options[it].first)},modifier)
}
