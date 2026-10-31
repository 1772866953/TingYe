package com.tingye.app

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.tingye.app.data.GlassAppearance

/** Live GPU display lists; reader optics may resolve a versioned output texture.
 * Sources form a DAG: ambient -> shelf -> scene -> popup, never self-sampling. */
@Stable internal class LiveGlassBackdrop(val layer:GraphicsLayer,val flattenOnUse:Boolean=false,
    val paperBlur:GraphicsLayer?=null,val paperFilter:GraphicsLayer?=null){
    val origin=mutableStateOf(Offset.Zero)
    var consumers by mutableIntStateOf(0)
    var blurReady=false
    var blurDensity=0f
    var paperRevision by mutableIntStateOf(0)
    var recordedPaperRevision=-1
    var blurredPaperRevision=-1
    var paperImage by mutableStateOf<ImageBitmap?>(null)
    var paperImageRevision=-1
    var requestedPaperRevision=-1
    var paperCaptureJob:Job?=null
    var capturePaper:(()->Unit)?=null
}
@Immutable internal data class GlassSource(val layers:List<LiveGlassBackdrop>)
internal val LocalGlassBackdrop=staticCompositionLocalOf{GlassSource(emptyList())}
internal val LocalGlassPopupBackdrop=staticCompositionLocalOf{GlassSource(emptyList())}
internal data class WallpaperStyle(val uri:String?=null,val blur:Int=28)
internal val LocalWallpaperStyle=compositionLocalOf{WallpaperStyle()}
// Read in draw scope: a live customization drag invalidates optics, not layouts.
internal val LocalGlassAppearance=staticCompositionLocalOf { mutableStateOf(GlassAppearance()) }
private val LocalGlassAmbient=staticCompositionLocalOf<LiveGlassBackdrop?>{null}
private val LocalGlassScene=staticCompositionLocalOf<LiveGlassBackdrop?>{null}
private val LocalGlassShelf=staticCompositionLocalOf<LiveGlassBackdrop?>{null}
internal class GlassOverlayEntry(val backdrop:LiveGlassBackdrop,val underlay:GlassSource,val content:@Composable ()->Unit)
internal class GlassOverlays{val entries=mutableStateListOf<GlassOverlayEntry>()}
internal val LocalGlassOverlays=staticCompositionLocalOf<GlassOverlays?>{null}

private data class GlassConsumerElement(val sources:List<LiveGlassBackdrop>):ModifierNodeElement<GlassConsumerNode>(){
    override fun create()=GlassConsumerNode(sources)
    override fun update(node:GlassConsumerNode){node.update(sources)}
    override fun InspectorInfo.inspectableProperties(){name="liveGlassSource"}
}
private class GlassConsumerNode(private var sources:List<LiveGlassBackdrop>):Modifier.Node(){
    private fun change(amount:Int){sources.forEach{it.consumers=(it.consumers+amount).coerceAtLeast(0)}}
    override fun onAttach(){change(1)}
    override fun onDetach(){change(-1)}
    fun update(next:List<LiveGlassBackdrop>){
        if(sources==next)return
        if(isAttached)change(-1)
        sources=next
        if(isAttached)change(1)
    }
}

@Composable internal fun rememberLiveGlassBackdrop(offscreen:Boolean=true,softenPaper:Boolean=false):LiveGlassBackdrop{
    val layer=rememberGraphicsLayer()
    val paperBlur=if(softenPaper)rememberGraphicsLayer() else null
    val paperFilter=if(softenPaper)rememberGraphicsLayer() else null
    val captureScope=rememberCoroutineScope()
    // Reused optical sources render once, including shelf content beneath
    // multiple controls. The final scene itself stays direct: it is only a
    // source while a popup is visible, not a mandatory extra screen texture.
    val source=remember(layer,offscreen,paperBlur,paperFilter){LiveGlassBackdrop(layer.apply{compositingStrategy=if(offscreen)
        androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.layer.CompositingStrategy.Auto},flattenOnUse=!offscreen,
        paperBlur=paperBlur?.apply{compositingStrategy=androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen;clip=true},
        paperFilter=paperFilter?.apply{clip=true})}
    if(softenPaper)source.capturePaper={
        val revision=source.paperRevision
        if(source.requestedPaperRevision!=revision){
            source.requestedPaperRevision=revision
            source.paperCaptureJob?.cancel()
            source.paperCaptureJob=captureScope.launch{
                try{
                    // A completed image breaks the RenderNode reference graph.
                    // Refresh on real page changes, never on modal movement.
                    val image=source.paperBlur!!.toImageBitmap()
                    if(source.paperRevision==revision){source.paperImageRevision=revision;source.paperImage=image}
                }catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){android.util.Log.w("TingYeGlass","Reader texture capture unavailable",error)}
            }
        }
    }
    return source
}
@Composable internal fun GlassEnvironment(dark:Boolean,wallpaper:String?=null,blur:Int=28,appearance:GlassAppearance=GlassAppearance(),content:@Composable ()->Unit){
    val ambient=rememberLiveGlassBackdrop();val scene=rememberLiveGlassBackdrop(false);val shelf=rememberLiveGlassBackdrop()
    val overlays=remember{GlassOverlays()}
    val optics=remember{mutableStateOf(appearance.normalized())}
    val lighting=rememberGravityLighting()
    LaunchedEffect(appearance){optics.value=appearance.normalized()}
    CompositionLocalProvider(LocalGlassLighting provides lighting,LocalGlassAppearance provides optics,LocalGlassOverlays provides overlays,LocalWallpaperStyle provides WallpaperStyle(wallpaper,blur),LocalGlassAmbient provides ambient,LocalGlassScene provides scene,LocalGlassShelf provides shelf,
        LocalGlassBackdrop provides GlassSource(listOf(ambient)),LocalGlassPopupBackdrop provides GlassSource(listOf(scene))){
        Box(Modifier.fillMaxSize()){
            Box(Modifier.fillMaxSize().then(if(overlays.entries.isNotEmpty())Modifier.clearAndSetSemantics{} else Modifier)){content()}
            // Outside the scene's recording boundary: glass cannot sample its
            // own output, and the entire modal stays in the Activity window.
            overlays.entries.forEachIndexed{index,entry->key(entry){
                Box(Modifier.fillMaxSize().then(if(index<overlays.entries.lastIndex)Modifier.clearAndSetSemantics{} else Modifier)){entry.content()}
            }}
        }
    }
}
@Composable internal fun Modifier.recordGlassBackdrop(source:LiveGlassBackdrop):Modifier=
    graphicsLayer().onGloballyPositioned{source.origin.value=it.positionOnScreen()}.drawWithContent{
        // A popup samples the whole scene. Flatten that live scene once while
        // it has an attached consumer, instead of replaying every nested glass
        // filter through each popup lens and its child buttons. Closed popups
        // keep no consumer and add no full-screen texture during page travel.
        if(source.flattenOnUse)source.layer.compositingStrategy=if(source.consumers>0)
            androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
        val revision=source.paperRevision
        val paperChanged=source.paperBlur==null || source.recordedPaperRevision!=revision ||
            source.layer.size!=IntSize(size.width.toInt(),size.height.toInt())
        if(paperChanged){
        source.recordedPaperRevision=revision
        source.layer.record{this@drawWithContent.drawContent()}
        }
        source.paperBlur?.let{blurred->if(Build.VERSION.SDK_INT>=31 && source.consumers>0 &&
            (source.blurredPaperRevision!=revision || blurred.size!=source.layer.size || source.blurDensity!=density)){
            // Only the reader has this shared texture. Its paper is stable
            // under a modal, unlike the animated controls of the player scene.
            // Actual page/style changes invalidate the live node normally.
            val filter=source.paperFilter!!
            if(source.blurDensity!=density){
                source.blurDensity=density
                filter.renderEffect=RenderEffect.createBlurEffect(12f*density,12f*density,Shader.TileMode.CLAMP).asComposeRenderEffect()
            }
            filter.record(density=Density(density,fontScale),layoutDirection=layoutDirection,size=source.layer.size){drawLayer(source.layer)}
            // Cache the FILTERED output. A layer with renderEffect caches its
            // input; sampling it directly can execute the Gaussian again for
            // every reader bar/panel. This outer effect-free GPU layer resolves
            // the blur once and stays live through the source revision above.
            blurred.record(density=Density(density,fontScale),layoutDirection=layoutDirection,size=source.layer.size){drawLayer(filter)}
            source.blurredPaperRevision=revision
            source.blurReady=true
        }}
        if(source.blurReady && source.consumers>0)source.capturePaper?.invoke()
        drawLayer(source.layer)
    }
@Composable internal fun Modifier.glassBackgroundSource():Modifier=LocalGlassAmbient.current?.let{recordGlassBackdrop(it)}?:this
@Composable internal fun Modifier.glassSceneSource():Modifier=LocalGlassScene.current?.let{recordGlassBackdrop(it)}?:this
@Composable internal fun Modifier.glassShelfSource():Modifier=LocalGlassShelf.current?.let{recordGlassBackdrop(it)}?:this
@Composable internal fun ShelfGlass(content:@Composable ()->Unit){
    val ambient=LocalGlassAmbient.current;val shelf=LocalGlassShelf.current
    CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(listOfNotNull(ambient,shelf)),content=content)
}
@Composable internal fun PopupGlass(content:@Composable ()->Unit){
    CompositionLocalProvider(LocalGlassBackdrop provides LocalGlassPopupBackdrop.current,content=content)
}
@Composable internal fun GlassPaper(content:@Composable ()->Unit){
    val paper=rememberLiveGlassBackdrop(softenPaper=true)
    CompositionLocalProvider(LocalGlassBackdrop provides GlassSource(listOf(paper)),
        LocalGlassPopupBackdrop provides GlassSource(listOf(paper)),content=content)
}
@Composable internal fun Modifier.readerGlassSource():Modifier=LocalGlassBackdrop.current.layers.lastOrNull()?.let{recordGlassBackdrop(it)}?:this

/** Pre-rendered static artwork is independent of the live scene sampling. */
@Composable internal fun GlassPageBackground(modifier:Modifier=Modifier){
    AmbientArtwork(modifier)
}
@Composable internal fun GlassTransitionSurface(frame:Rect,radius:Float,alpha:Float,material:Float,mini:LiveGlassBackdrop?=null){
    val ambient=LocalGlassAmbient.current;val base=MaterialTheme.colorScheme.background
    val ink=MaterialTheme.colorScheme.onSurface
    val appearance=LocalGlassAppearance.current
    val lighting=LocalGlassLighting.current
    val handoff=rememberGraphicsLayer()
    Canvas(Modifier.fillMaxSize().graphicsLayer{this.alpha=alpha}){
        val path=Path().apply{addRoundRect(RoundRect(frame,radius,radius))}
        clipPath(path){
            if(ambient!=null)drawLayer(ambient.layer)else drawRect(base)
            if(mini!=null && mini.layer.size.width>0 && mini.layer.size.height>0){
                val t=((material-.45f)/.55f).coerceIn(0f,1f)
                handoff.record(density=Density(density,fontScale),layoutDirection=layoutDirection,size=mini.layer.size){drawLayer(mini.layer)}
                handoff.alpha=t*t*(3f-2f*t)
                translate(frame.left,frame.top){scale(frame.width/mini.layer.size.width,frame.height/mini.layer.size.height,Offset.Zero){drawLayer(handoff)}}
            }else{
                // This is the same non-refracting material as GlassCard on the
                // shelf. Read its LIVE settings in draw scope, including when
                // a slider changes during an interrupted transition. The bottom
                // mini-player above already replays its actual optical layer.
                val glass=appearance.value
                drawRect(Color.White.copy(alpha=.055f*glass.veil*material))
                val stroke=.6.dp.toPx();val inset=stroke/2
                val border=Path().apply{addRoundRect(RoundRect(Rect(frame.left+inset,frame.top+inset,frame.right-inset,frame.bottom-inset),(radius-inset).coerceAtLeast(0f),(radius-inset).coerceAtLeast(0f)))}
                drawPath(border,glassCardEdgeBrush(ink,.12f*glass.shadow*material,frame.size,lighting.value,frame.topLeft),style=Stroke(stroke))
            }
        }
    }
}

private fun DrawScope.drawGlassSources(source:GlassSource,screen:Offset,blurredPaper:Boolean=false){
    for(entry in source.layers){
        val offset=entry.origin.value-screen
        translate(offset.x,offset.y){
            val image=if(blurredPaper && entry.paperImageRevision==entry.paperRevision)entry.paperImage else null
            if(image!=null)drawImage(image) else drawLayer(if(blurredPaper)entry.paperBlur?:entry.layer else entry.layer)
        }
    }
}

/** Only the optical background is filtered. Content is drawn afterwards and
 * stays sharp. Both popup windows and the reader use screen-space origins. */
@Composable internal fun Modifier.liquidGlass(radius:Dp=22.dp,depth:Dp=0.dp,material:Float=1f,
    shape:Shape?=null,verticalInset:Dp=0.dp,horizontalInset:Dp=0.dp,panel:Boolean=false,
    interaction:MutableInteractionSource?=null,exported:LiveGlassBackdrop?=null,rim:Boolean=true,
    edgeEmphasis:Boolean=!panel,magnification:()->Float={1f},optics:()->Float={1f},blurOverride:Float?=null):Modifier{
    val source=LocalGlassBackdrop.current;val fallback=MaterialTheme.colorScheme.surface
    val ink=MaterialTheme.colorScheme.onSurface
    val edgeInk=if(ink.luminance()>.5f)Color.Black else ink
    val appearance=LocalGlassAppearance.current
    val lighting=LocalGlassLighting.current
    val screen=remember{mutableStateOf(Offset.Zero)}
    val lens=rememberGraphicsLayer()
    val effects=remember{GlassEffects()}
    val shapeValue=shape?:RoundedCornerShape(radius)
    // Isolate each small lens from unrelated parent animation invalidations.
    // Referenced GraphicsLayers remain live; a moving source still invalidates
    // its dependants, while an unchanged background is not recorded each frame.
    return then(GlassConsumerElement(source.layers)).graphicsLayer().onGloballyPositioned{screen.value=it.positionOnScreen();exported?.origin?.value=screen.value}.drawWithContent{
        // Nested DrawScope.record overloads can make LayoutNodeDrawScope its
        // own Density delegate. Use immutable values for optical-only layers.
        val opticalDensity=Density(density,fontScale)
        val opticalDirection=layoutDirection
        val opticalSize=IntSize(size.width.toInt(),size.height.toInt())
        val strength=optics().coerceIn(0f,1f)
        val glass=appearance.value
        val blurredPaper=panel && Build.VERSION.SDK_INT>=31 && source.layers.size==1 && source.layers[0].blurReady
        val paintMaterial:DrawScope.()->Unit={
        val x=horizontalInset.toPx().coerceAtMost(size.width/2)
        val y=verticalInset.toPx().coerceAtMost(size.height/2)
        val rect=Rect(x,y,size.width-x,size.height-y)
        if(rect.width>0 && rect.height>0){
            val outline=shapeValue.createOutline(rect.size,layoutDirection,this)
            val localPath=Path().apply{when(outline){
                is Outline.Rounded->addRoundRect(outline.roundRect)
                is Outline.Rectangle->addRect(outline.rect)
                is Outline.Generic->addPath(outline.path)
            }}
            val corners=(outline as? Outline.Rounded)?.roundRect
            val r=corners?.topLeftCornerRadius?.x?:0f
            // Small lenses need only the displacement plus the blur fringe;
            // keep the full panel padding for its wider blur kernel.
            val pad=(if(panel)24.dp else 12.dp).toPx()
            // Retain full-resolution live optics. Declaring a layer size does
            // not clip its recorded drawing: an unclipped full-screen source
            // expands RenderEffect's intermediate bounds for every tiny lens.
            val sampleScale=1f
            val dimensions=IntSize(((rect.width+pad*2)*sampleScale).toInt().coerceAtLeast(1),((rect.height+pad*2)*sampleScale).toInt().coerceAtLeast(1))
            val pull=interaction?.let{glassSamplingOffset(it)}?:Offset.Zero
            lens.clip=true
            lens.compositingStrategy=androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            lens.record(density=opticalDensity,layoutDirection=opticalDirection,size=dimensions){
                clipRect(0f,0f,dimensions.width.toFloat(),dimensions.height.toFloat()){
                scale(sampleScale,sampleScale,Offset.Zero){
                    drawRect(fallback,size=Size(dimensions.width/sampleScale,dimensions.height/sampleScale))
                    val zoom=magnification().coerceIn(1f,1.3f)
                    scale(zoom,zoom,Offset(pad+rect.width/2,pad+rect.height/2)){
                        drawGlassSources(source,screen.value+rect.topLeft-Offset(pad,pad)+pull,blurredPaper)
                    }
                }
                }
            }
            effects.update(lens,Size(rect.width*sampleScale,rect.height*sampleScale),r*sampleScale,pad*sampleScale,density*sampleScale,blurOverride?:if(blurredPaper)0f else if(panel)12f else .65f,!panel,strength*glass.bend)
            translate(rect.left,rect.top){
                clipPath(localPath){
                    translate(-pad,-pad){scale(1f/sampleScale,1f/sampleScale,Offset.Zero){drawLayer(lens)}}
                    if(Build.VERSION.SDK_INT<31 && !panel){
                        // Android 12 has no RuntimeShader. Re-sample the live GPU
                        // lens texture in edge bands; never capture CPU bitmaps.
                        val edge=minOf(9.dp.toPx(),r,rect.height/3,rect.width/3)
                        val bands=3
                        for(i in 0 until bands){
                            val low=edge*i/bands;val high=edge*(i+1)/bands
                            val bend=7.dp.toPx()*(1f-(i+.5f)/bands)*(1f-(i+.5f)/bands)*strength*glass.bend
                            clipRect(0f,low,rect.width,high){translate(-pad,-pad+bend){drawLayer(lens)}}
                            clipRect(0f,rect.height-high,rect.width,rect.height-low){translate(-pad,-pad-bend){drawLayer(lens)}}
                            clipRect(low,edge,high,rect.height-edge){translate(-pad+bend,-pad){drawLayer(lens)}}
                            clipRect(rect.width-high,edge,rect.width-low,rect.height-edge){translate(-pad-bend,-pad){drawLayer(lens)}}
                        }
                    }
                    // Neutral clear glass, no wallpaper tint or opaque inner tile.
                    drawRect(Color.White.copy(alpha=(if(panel).10f else if(edgeEmphasis).055f else .045f)*strength*glass.veil))
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha=(if(panel).045f else if(edgeEmphasis).15f else .12f)*strength*glass.veil),Color.Transparent,Color.Black.copy(alpha=.035f*strength*glass.veil))))
                }
                // One outline, inset into the very same shape as the backdrop.
                if(rim)clipPath(localPath){
                    val light=lighting.value
                    val endpoints=glassLightEndpoints(rect.size,light)
                    // A narrow inner dark edge separates clear glass from pale
                    // paper. Both strokes stay inside the original silhouette:
                    // no opaque backing, exterior ring, or large drop shadow.
                    if(edgeEmphasis)drawPath(localPath,Brush.linearGradient(listOf(
                        edgeInk.copy(alpha=.09f*strength*glass.shadow),edgeInk.copy(alpha=.05f*strength*glass.shadow),edgeInk.copy(alpha=.18f*strength*glass.shadow)),endpoints.first,endpoints.second),
                        style=Stroke((if(panel)2.2.dp else 1.8.dp).toPx()))
                    drawPath(localPath,Brush.linearGradient(listOf(
                        Color.White.copy(alpha=(if(edgeEmphasis).76f else .62f)*strength),
                        Color.White.copy(alpha=(if(edgeEmphasis).16f else .07f)*strength),
                        ink.copy(alpha=(if(edgeEmphasis).24f else .20f)*strength*glass.shadow),
                        Color.White.copy(alpha=(if(edgeEmphasis).46f else .35f)*strength)),endpoints.first,endpoints.second),style=Stroke(.8.dp.toPx()))
                }
            }
        }
        }
        if(exported!=null){
            exported.layer.record(density=opticalDensity,layoutDirection=opticalDirection,size=opticalSize){paintMaterial()}
            drawLayer(exported.layer)
        }else paintMaterial()
        drawContent()
    }
}

private class GlassEffects{
    private var key:List<Float>?=null
    private var runtime:Any?=null
    fun update(layer:GraphicsLayer,size:Size,radius:Float,pad:Float,density:Float,blurDp:Float,refract:Boolean,strength:Float=1f){
        val next=listOf(size.width,size.height,radius,pad,density,blurDp,if(refract)strength else 0f)
        if(key==next)return
        key=next
        if(blurDp==0f && (!refract || strength<.001f)){layer.renderEffect=null;return}
        if(Build.VERSION.SDK_INT>=33){
            if(runtime==null)runtime=Api33Glass.shader()
            layer.renderEffect=Api33Glass.effect(runtime!!,size,radius,pad,density,blurDp,refract,strength).asComposeRenderEffect()
        }else if(Build.VERSION.SDK_INT>=31){
            layer.renderEffect=if(blurDp==0f && !refract)null else Api31Glass.effect(size,radius,pad,density,blurDp,refract,strength).asComposeRenderEffect()
        }
    }
}
/** One display-list reference per lens. Android 12 traverses referenced
 * RenderNodes even when their pixels are cached: replaying thirteen clipped
 * copies recursively expands every nested popup button's source tree. Native
 * filters reuse the same input pixels without multiplying that traversal. */
@RequiresApi(31) private object Api31Glass{
    fun effect(size:Size,radius:Float,pad:Float,density:Float,blur:Float,refract:Boolean,strength:Float=1f):RenderEffect{
        val base=if(blur>0f)RenderEffect.createBlurEffect(blur*density,blur*density,Shader.TileMode.CLAMP) else RenderEffect.createOffsetEffect(0f,0f)
        if(!refract)return base
        val edge=minOf(9f*density,radius,size.height/3,size.width/3)
        if(edge<=0f)return base
        var result=base
        fun band(left:Float,top:Float,right:Float,bottom:Float,dx:Float,dy:Float,vertical:Boolean,reverse:Boolean){
            if(right<=left || bottom<=top)return
            // A continuous quadratic edge replaces three hard displacement
            // steps. Four bounded, smoothly feathered inputs avoid twelve
            // separate filter surfaces on Android 12 while retaining blur,
            // live background movement, and the same maximum edge displacement.
            val fadeColors=IntArray(9){i->
                val t=if(reverse)i/8f else 1f-i/8f
                android.graphics.Color.argb((255*t*t).toInt(),255,255,255)
            }
            val bounds=android.graphics.Rect(kotlin.math.floor(left+pad).toInt(),kotlin.math.floor(top+pad).toInt(),
                kotlin.math.ceil(right+pad).toInt(),kotlin.math.ceil(bottom+pad).toInt())
            val fade=android.graphics.LinearGradient(0f,0f,
                if(vertical)0f else bounds.width().toFloat(),if(vertical)bounds.height().toFloat() else 0f,
                fadeColors,null,Shader.TileMode.CLAMP)
            // Only the immutable bevel mask is rasterized, at device pixels.
            // A shader effect has infinite bounds; a finite bitmap mask lets
            // Skia restrict each intermediate to its narrow optical edge.
            // Background pixels still come exclusively from the live GPU node.
            val mask=android.graphics.Bitmap.createBitmap(bounds.width(),bounds.height(),android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(mask).drawPaint(android.graphics.Paint().apply{shader=fade})
            val shifted=RenderEffect.createOffsetEffect(dx,dy,base)
            val clipped=RenderEffect.createBlendModeEffect(shifted,RenderEffect.createBitmapEffect(mask,null,bounds),android.graphics.BlendMode.DST_IN)
            result=RenderEffect.createBlendModeEffect(result,clipped,android.graphics.BlendMode.SRC_OVER)
        }
        val bend=7f*density*(5f/6f)*(5f/6f)*strength
        band(0f,0f,size.width,edge,0f,bend,true,false)
        band(0f,size.height-edge,size.width,size.height,0f,-bend,true,true)
        band(0f,edge,edge,size.height-edge,bend,0f,false,false)
        band(size.width-edge,edge,size.width,size.height-edge,-bend,0f,false,true)
        return result
    }
}
@RequiresApi(33) private object Api33Glass{
    fun shader():Any=RuntimeShader("""
        uniform shader scene;
        uniform float4 bounds;
        uniform float radius;
        uniform float edgeWidth;
        uniform float bend;
        uniform float2 sampleSize;
        half4 sampleScene(float2 p) {
            return scene.eval(clamp(p, float2(0.5), sampleSize-float2(0.5)));
        }
        half4 main(float2 p) {
            float2 center=(bounds.xy+bounds.zw)*0.5;
            float2 h=(bounds.zw-bounds.xy)*0.5;
            float2 q=abs(p-center)-(h-radius);
            float2 outside=max(q,float2(0.0));
            float lengthOutside=length(outside);
            float distance=-(min(max(q.x,q.y),0.0)+lengthOutside-radius);
            float2 n;
            if(lengthOutside>0.001) n=outside*sign(p-center)/lengthOutside;
            else if(q.x>q.y) n=float2(sign(p.x-center.x),0.0);
            else n=float2(0.0,sign(p.y-center.y));
            float edge=1.0-clamp(distance/edgeWidth,0.0,1.0);
            float2 shift=n*bend*edge*edge;
            // Contract toward the lens centre. Outward sampling can escape
            // Android's cropped RuntimeShader input despite a padded node,
            // exposing transparent/undefined edge pixels on Vulkan devices.
            half4 middle=sampleScene(p-shift);
            half4 red=sampleScene(p-shift*1.12);
            half4 blue=sampleScene(p-shift*0.88);
            // RenderEffect input outside its texture is transparent. Keep
            // chromatic sampling bounded and premultiplied on real GPUs too.
            // A scrolling ancestor may crop the effect input between the three
            // chromatic samples. Missing red/blue coverage is not dispersion:
            // fall back to the central sample instead of drawing a cyan rim.
            half r=abs(red.a-middle.a)<0.01 ? red.r : middle.r;
            half b=abs(blue.a-middle.a)<0.01 ? blue.b : middle.b;
            return half4(clamp(half3(r,middle.g,b),half3(0.0),half3(middle.a)),middle.a);
        }
    """.trimIndent())
    fun effect(instance:Any,size:Size,radius:Float,pad:Float,density:Float,blur:Float,refract:Boolean,strength:Float=1f):RenderEffect{
        if(!refract)return if(blur>0f)RenderEffect.createBlurEffect(blur*density,blur*density,Shader.TileMode.CLAMP)
            else RenderEffect.createOffsetEffect(0f,0f)
        val shader=instance as RuntimeShader
        shader.setFloatUniform("bounds",pad,pad,pad+size.width,pad+size.height)
        shader.setFloatUniform("radius",radius)
        shader.setFloatUniform("edgeWidth",10f*density)
        shader.setFloatUniform("bend",8f*density*strength)
        shader.setFloatUniform("sampleSize",size.width+pad*2,size.height+pad*2)
        val lens=RenderEffect.createRuntimeShaderEffect(shader,"scene")
        return if(blur>0f)RenderEffect.createChainEffect(lens,RenderEffect.createBlurEffect(blur*density,blur*density,Shader.TileMode.CLAMP))else lens
    }
}
