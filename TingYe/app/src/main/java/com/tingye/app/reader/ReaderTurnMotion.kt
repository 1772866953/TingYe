package com.tingye.app.reader

import kotlin.math.*

/** Match repeated input cadence instead of adding a fixed wait to every page. */
internal class ReaderTurnTiming {
    private var previous=Long.MIN_VALUE
    private var rapidUntil=Long.MIN_VALUE
    private var cadence=140f
    fun input(now:Long){
        if(previous!=Long.MIN_VALUE){
            val interval=now-previous
            if(interval in 1..260){cadence=interval.toFloat();rapidUntil=now+350}
        }
        previous=now
    }
    fun duration(start:Float,end:Float,backlog:Boolean,now:Long):Long {
        val full=if(backlog || now<=rapidUntil)(cadence*.65f).coerceIn(60f,110f)else 250f
        return (full*abs(end-start)).roundToLong().coerceIn(1,250)
    }
}

/** Monotone Hermite travel preserves velocity across a tap interruption. */
internal class ReaderTurnCurve(val start:Float,val end:Float,velocity:Float,val durationMs:Long){
    private val seconds=durationMs/1000f
    private val distance=end-start
    private val tangent=(velocity*seconds*sign(distance)).coerceIn(0f,3f*abs(distance))*sign(distance)
    fun position(fraction:Float):Float {
        val t=fraction.coerceIn(0f,1f)
        return start+distance*(3*t*t-2*t*t*t)+tangent*(t*t*t-2*t*t+t)
    }
    fun velocity(fraction:Float):Float {
        val t=fraction.coerceIn(0f,1f)
        return (distance*(6*t-6*t*t)+tangent*(3*t*t-4*t+1))/seconds
    }
}

internal data class ReaderSwipeRelease(val commitVisible:Boolean,val defer:Int=0,val deferFirst:Boolean=false,val cancelPending:Boolean=false)

/** An interrupted sheet's visible direction is not the new finger direction. */
internal fun readerSwipeRelease(accepted:Int?,visible:Int,intent:Int,commitInput:Boolean,pendingLast:Int?):ReaderSwipeRelease {
    if(accepted==null)return if(commitInput && visible==intent)ReaderSwipeRelease(true)
        else ReaderSwipeRelease(false,if(commitInput)intent else 0)
    if(!commitInput)return if(visible==accepted)ReaderSwipeRelease(true)else ReaderSwipeRelease(false,accepted,true)
    if(intent==accepted)return ReaderSwipeRelease(true,intent)
    // Opposite requests first cancel the newest queued sheet. With no such
    // queue, the accepted old turn and this reverse turn cancel each other.
    if(pendingLast==accepted)return if(visible==accepted)ReaderSwipeRelease(true,cancelPending=true)
        else ReaderSwipeRelease(false,accepted,true,true)
    return ReaderSwipeRelease(false)
}
