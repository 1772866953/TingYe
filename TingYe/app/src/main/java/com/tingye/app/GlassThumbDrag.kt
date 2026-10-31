package com.tingye.app

import kotlin.math.*

/** Finger-to-glass inertia, in pixels. Settles when a held finger stops moving. */
internal class GlassThumbDrag(private val density:Float){
    private var position=0f
    private var velocity=0f
    var force=0f;private set
    fun reset(x:Float){position=x;velocity=0f;force=0f}
    fun step(target:Float,seconds:Float):Boolean{
        val dt=seconds.coerceIn(.001f,.05f)
        val omega=sqrt(1000f);val decay=.8f*omega;val frequency=.6f*omega
        val displacement=position-target
        val b=(velocity+decay*displacement)/frequency
        val c=cos(frequency*dt);val s=sin(frequency*dt);val e=exp(-decay*dt)
        position=target+e*(displacement*c+b*s)
        velocity=e*((b*frequency-decay*displacement)*c-(displacement*frequency+decay*b)*s)
        if(abs(position-target)<.025f*density && abs(velocity)<.3f*density){position=target;velocity=0f}
        force=48f*density*tanh((target-position)/(48f*density))
        return position!=target || velocity!=0f
    }
}
