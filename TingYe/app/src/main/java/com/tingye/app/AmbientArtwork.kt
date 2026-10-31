package com.tingye.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Artwork is static: render it once on a worker, upload once, and share it
 * between shelf/settings/player. Live glass still samples the full actual UI.
 * Half resolution is sufficient for diffuse light; no full-screen GPU blur is
 * repeated by every lens or shared-element animation frame. */
private object ArtworkCache {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val entries=linkedMapOf<String,Deferred<ImageBitmap>>()
    @Synchronized fun get(context:android.content.Context,width:Int,height:Int,density:Float,base:Int,style:WallpaperStyle):Deferred<ImageBitmap>{
        val w=(width/2).coerceAtLeast(1);val h=(height/2).coerceAtLeast(1)
        val key="$w:$h:$density:$base:${style.uri}:${style.blur}"
        entries.remove(key)?.let{entries[key]=it;return it}
        val job=scope.async{
            val custom=style.uri?.let{runCatching{photo(context,it,w,h,base,style.blur,density/2)}.getOrNull()}
            (custom?:paint(w,h,density/2,base,style.blur)).also{it.prepareToDraw()}.asImageBitmap()
        }
        entries[key]=job
        while(entries.size>2)entries.remove(entries.keys.first())
        return job
    }
    private fun paint(width:Int,height:Int,density:Float,base:Int,blur:Int):Bitmap{
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val w=width.toFloat();val h=height.toFloat()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        fun color(rgb:Long,alpha:Float=1f)=((alpha*255).toInt().coerceIn(0,255) shl 24) or (rgb.toInt() and 0xFFFFFF)
        paint.shader=LinearGradient(0f,0f,w,h,intArrayOf(0xFF192D3B.toInt(),base,0xFF101823.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint)
        paint.shader=RadialGradient(w*.12f,h*.20f,h*.5f,intArrayOf(color(0x6F879A,.18f),color(0x6F879A,0f)),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint)
        val teal=Path().apply{
            moveTo(w*1.18f,-h*.12f)
            cubicTo(w*.02f,h*.02f,w*1.38f,h*.23f,w*.48f,h*.45f)
            cubicTo(-w*.12f,h*.59f,-w*.26f,h*.72f,-w*.20f,h*.92f)
            lineTo(-w*.38f,h*.92f)
            cubicTo(-w*.15f,h*.48f,w*.84f,h*.39f,w*.65f,h*.18f)
            cubicTo(w*.40f,h*.04f,w*.44f,-h*.05f,w*.88f,-h*.12f);close()
        }
        val pearl=Path().apply{
            moveTo(w*1.02f,-h*.04f)
            cubicTo(w*.10f,h*.11f,w*1.13f,h*.25f,w*.35f,h*.47f)
            cubicTo(w*.02f,h*.57f,-w*.10f,h*.65f,-w*.13f,h*.74f)
            cubicTo(w*.02f,h*.51f,w*.86f,h*.40f,w*.83f,h*.25f)
            cubicTo(w*.79f,h*.12f,w*.48f,h*.10f,w*1.06f,-h*.02f);close()
        }
        val warm=Path().apply{
            moveTo(w*1.15f,h*.49f)
            cubicTo(w*.60f,h*.66f,w*.95f,h*.79f,w*.33f,h*1.04f)
            lineTo(w*.65f,h*1.10f)
            cubicTo(w*1.45f,h*.83f,w*.56f,h*.76f,w*1.20f,h*.58f);close()
        }
        paint.maskFilter=if(blur>0)BlurMaskFilter(blur*.286f*density,BlurMaskFilter.Blur.NORMAL)else null
        paint.shader=LinearGradient(0f,0f,w,0f,intArrayOf(color(0x17333F,.1f),color(0x3E7278,.42f),color(0xB1C9BD,.70f),color(0x42666D,.60f),color(0x122633,.15f)),floatArrayOf(0f,.45f,.70f,.84f,1f),Shader.TileMode.CLAMP)
        canvas.drawPath(teal,paint)
        paint.shader=LinearGradient(w,h*.03f,0f,h*.58f,intArrayOf(color(0xE2E9DD,.50f),color(0x618B88,.15f),color(0x618B88,0f)),null,Shader.TileMode.CLAMP)
        canvas.drawPath(pearl,paint)
        paint.shader=LinearGradient(0f,0f,w,0f,intArrayOf(color(0x635E78,0f),color(0x635E78,.16f),color(0xC2A58B,.56f),color(0x514B5D,.12f)),floatArrayOf(0f,.5f,.83f,1f),Shader.TileMode.CLAMP)
        canvas.drawPath(warm,paint)
        return bitmap
    }
    private fun photo(context:android.content.Context,uri:String,w:Int,h:Int,base:Int,blur:Int,density:Float):Bitmap{
        require(uri.startsWith("content://"))
        val resolver=context.contentResolver;val location=android.net.Uri.parse(uri)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        resolver.openInputStream(location).use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0)
        var sample=1;val limit=maxOf(w,h)*2
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>limit)sample*=2
        val original=if(android.os.Build.VERSION.SDK_INT>=28)android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver,location)){decoder,info,_->
            decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            val factor=(maxOf(info.size.width,info.size.height).toFloat()/limit).coerceAtLeast(1f)
            decoder.setTargetSize((info.size.width/factor).roundToInt().coerceAtLeast(1),(info.size.height/factor).roundToInt().coerceAtLeast(1))
        }else resolver.openInputStream(location).use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}?:error("Image unavailable")
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try{
            val canvas=Canvas(bitmap);canvas.drawColor(base)
            val scale=maxOf(w.toFloat()/original.width,h.toFloat()/original.height)
            val left=(w-original.width*scale)/2;val top=(h-original.height*scale)/2
            canvas.drawBitmap(original,null,android.graphics.RectF(left,top,left+original.width*scale,top+original.height*scale),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            // The application uses light text. Bound custom-image luminance so
            // even a white photograph cannot erase labels or clear controls.
            canvas.drawColor(0x8C000000.toInt())
        }finally{original.recycle()}
        if(blur>0)soften(bitmap,(blur*.286f*density).roundToInt().coerceAtLeast(1))
        return bitmap
    }
    /** Three bounded box passes approximate a Gaussian. Only runs on the worker
     * after a wallpaper/blur change, never in draw or during pointer tracking. */
    private fun soften(bitmap:Bitmap,radius:Int){
        val w=bitmap.width;val h=bitmap.height;val source=IntArray(w*h);val temp=IntArray(w*h)
        bitmap.getPixels(source,0,w,0,0,w,h)
        val div=radius*2+1
        fun pass(input:IntArray,output:IntArray,horizontal:Boolean){
            val lines=if(horizontal)h else w;val length=if(horizontal)w else h
            fun index(line:Int,position:Int)=if(horizontal)line*w+position else position*w+line
            for(line in 0 until lines){
                var red=0;var green=0;var blue=0
                for(k in -radius..radius){val color=input[index(line,k.coerceIn(0,length-1))];red+=(color ushr 16)and 255;green+=(color ushr 8)and 255;blue+=color and 255}
                for(position in 0 until length){
                    output[index(line,position)]=(255 shl 24)or((red/div)shl 16)or((green/div)shl 8)or(blue/div)
                    val leaving=input[index(line,(position-radius).coerceAtLeast(0))]
                    val entering=input[index(line,(position+radius+1).coerceAtMost(length-1))]
                    red+=((entering ushr 16)and 255)-((leaving ushr 16)and 255)
                    green+=((entering ushr 8)and 255)-((leaving ushr 8)and 255)
                    blue+=(entering and 255)-(leaving and 255)
                }
            }
        }
        repeat(3){pass(source,temp,true);pass(temp,source,false)}
        bitmap.setPixels(source,0,w,0,0,w,h)
    }
}

@Composable internal fun AmbientArtwork(modifier:Modifier=Modifier){
    val base=MaterialTheme.colorScheme.background
    val density=LocalDensity.current.density
    val context=LocalContext.current.applicationContext;val style=LocalWallpaperStyle.current
    BoxWithConstraints(modifier.fillMaxSize()){
        val width=constraints.maxWidth;val height=constraints.maxHeight
        val image by produceState<ImageBitmap?>(null,width,height,density,base,style){value=ArtworkCache.get(context,width,height,density,base.toArgb(),style).await()}
        Canvas(Modifier.fillMaxSize()){
            drawRect(base)
            image?.let{drawImage(it,dstSize=IntSize(size.width.toInt(),size.height.toInt()),filterQuality=FilterQuality.Low)}
        }
    }
}
