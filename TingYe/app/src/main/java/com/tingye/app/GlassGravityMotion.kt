package com.tingye.app

import kotlin.math.*

internal data class GlassLightDirection(val x:Float=0f,val y:Float=1f)

/** Gravity projection and damped angular response, independent of sensor rate. */
internal class GlassGravityMotion {
    private var gx=0f
    private var gy=1f
    private var angle=(PI/2).toFloat()
    private var emitted=angle
    private var previous=0L
    var direction=GlassLightDirection();private set
    fun sample(x:Float,y:Float,z:Float,rotation:Int,nanos:Long):Boolean {
        if(!x.isFinite() || !y.isFinite() || !z.isFinite())return false
        val magnitude=sqrt(x*x+y*y+z*z)
        if(magnitude !in 3f..20f)return false
        val dt=if(previous==0L)1f/60f else ((nanos-previous)/1e9f).coerceIn(.001f,.1f)
        previous=nanos
        val (sx,sy)=when(rotation){
            1->y to -x
            2->-x to -y
            3->-y to x
            else->x to y
        }
        val weight=1f-exp(-dt/.16f)
        gx+=(-sx/magnitude-gx)*weight
        gy+=(sy/magnitude-gy)*weight
        val dx=.95f*gx;val dy=.55f+.95f*gy
        if(hypot(dx,dy)>.12f){
            val target=atan2(dy,dx)
            var difference=(target-angle)%(2f*PI.toFloat())
            if(difference>PI)difference-=2f*PI.toFloat()
            if(difference< -PI)difference+=2f*PI.toFloat()
            angle+=(difference*(1f-exp(-dt/.12f))).coerceIn(-4f*dt,4f*dt)
        }
        // Static sensor noise must not keep every glass surface redrawing.
        if(abs(angle-emitted)<.0025f)return false
        emitted=angle;direction=GlassLightDirection(cos(angle),sin(angle))
        return true
    }
}
