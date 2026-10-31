package com.tingye.app

import kotlin.math.*

/** One physical pose for both the raised lens and its resting selection fill.
 * Changing the selected value acknowledges a gesture; it never starts a second
 * indicator journey. The dock has its own slower spring, including its labels.
 * All distances are device pixels; dimensionless springs are density independent.
 */
internal class GlassSegmentMotion(x:Float,width:Float,private val density:Float){
    data class Frame(val x:Float,val width:Float,val y:Float,val lift:Float,
        val dock:Float,val dockX:Float,val dockY:Float,val strain:Float,
        val contact:Float,val stretchX:Float,val stretchY:Float,val shear:Float)

    private class Spring(var value:Float,val epsilon:Float){
        var target=value
        var velocity=0f
        fun step(dt:Float,stiffness:Float,damping:Float){
            val omega=sqrt(stiffness)
            val decay=damping*omega
            val frequency=omega*sqrt(1f-damping*damping)
            val displacement=value-target
            val b=(velocity+decay*displacement)/frequency
            val c=cos(frequency*dt);val s=sin(frequency*dt);val e=exp(-decay*dt)
            value=target+e*(displacement*c+b*s)
            velocity=e*((b*frequency-decay*displacement)*c-(displacement*frequency+decay*b)*s)
            if(abs(value-target)<epsilon && abs(velocity)<epsilon*12){value=target;velocity=0f}
        }
        fun settled()=value==target && velocity==0f
    }
    private val position=Spring(x,.025f*density)
    private val span=Spring(width,.025f*density)
    private val vertical=Spring(0f,.025f*density)
    private val contact=Spring(0f,.0005f)
    private val elevation=Spring(0f,.0005f)
    private val dock=Spring(0f,.0005f)
    private val dockX=Spring(0f,.025f*density)
    private val dockY=Spring(0f,.025f*density)
    private val strain=Spring(0f,.0005f)
    private val stretchX=Spring(0f,.0002f)
    private val stretchY=Spring(0f,.0002f)
    private val shear=Spring(0f,.0002f)
    private var horizontalPull=0f
    private var verticalPull=0f
    private var pressAge=1f
    private var retractPending=false
    var holding=false
        private set

    fun press(x:Float,width:Float){
        holding=true;pressAge=0f;retractPending=false
        position.target=x;span.target=width
        vertical.target=0f;contact.target=1f
        dockX.target=0f;dockY.target=0f;horizontalPull=0f;verticalPull=0f
    }
    fun drag(x:Float,width:Float,y:Float,railWidth:Float,overpull:Float,pullY:Float=0f){
        position.target=x;span.target=width;vertical.target=y
        // The complete dock yields slightly toward the contact. Its 6% press
        // expansion is measured from the reference, not applied just to a rim.
        dockX.target=3f*density*tanh((x-railWidth/2)/(railWidth*.45f))+overpull*.18f
        dockY.target=y*.5f
        horizontalPull=overpull/(14f*density)
        verticalPull=tanh(pullY/(40f*density))
    }
    fun release(x:Float,width:Float,minimumPress:Boolean=true){
        holding=false
        position.target=x;span.target=width
        position.velocity=position.velocity.coerceIn(-900f*density,900f*density)
        vertical.target=0f
        // DOWN and UP may both arrive before the next vsync. Preserve one
        // brief press response instead of losing the entire tap animation.
        retractPending=minimumPress && pressAge<.105f
        if(!retractPending)contact.target=0f
        dockX.target=0f;dockY.target=0f;horizontalPull=0f;verticalPull=0f
    }
    fun selection(x:Float,width:Float){
        if(!holding){position.target=x;span.target=width}
    }
    fun frame():Frame{
        val a=1f+stretchX.value;val d=1f+stretchY.value;val b=shear.value
        // Pulling a soft lens in one direction narrows the perpendicular axis.
        // Preserve area while the springs lag/reverse; don't rotate the body.
        val area=sqrt((a*d-b*b).coerceAtLeast(.5f))
        return Frame(position.value,span.value,vertical.value,elevation.value,
            dock.value,dockX.value,dockY.value,strain.value,contact.value,a/area,d/area,b/area)
    }
    /** Exact damped-oscillator integration avoids Euler instability at 60/120Hz
     * and preserves position and momentum on interruption or direction change. */
    fun step(seconds:Float):Boolean{
        val dt=seconds.coerceIn(.001f,.05f)
        // A fixed upper integration interval keeps the coupled contact/body
        // response equivalent at 60/120Hz without an extra timer or frame loop.
        val steps=ceil(dt*240f).toInt().coerceAtLeast(1)
        repeat(steps){integrate(dt/steps)}
        return retractPending || !(position.settled() && span.settled() && vertical.settled() && contact.settled() && elevation.settled() &&
            dock.settled() && dockX.settled() && dockY.settled() && strain.settled() && stretchX.settled() && stretchY.settled() && shear.settled())
    }
    private fun integrate(dt:Float){
        pressAge+=dt
        if(retractPending && pressAge>=.105f){
            retractPending=false;contact.target=0f
        }
        position.step(dt,if(holding)3000f else 620f,if(holding).82f else .74f)
        span.step(dt,900f,.78f)
        vertical.step(dt,800f,.74f)
        val raised=holding || retractPending
        // Contact responds first. The heavier lens and dock then load up with
        // increasing speed, brake, and retain their momentum on release. This
        // avoids the old hard, nearly instant 560/1000-stiffness press/retract.
        contact.step(dt,if(raised)900f else 700f,.90f)
        elevation.target=contact.value;dock.target=contact.value
        elevation.step(dt,if(raised)300f else 420f,if(raised).48f else .64f)
        dock.step(dt,if(raised)280f else 300f,if(raised).64f else .70f)
        dockX.step(dt,if(holding)700f else 450f,.68f)
        dockY.step(dt,if(holding)700f else 450f,.68f)
        val fx=if(holding)(position.target-position.value)/(18f*density)+position.velocity/(1000f*density)+horizontalPull*.80f else 0f
        val fy=if(holding)verticalPull else 0f
        val distance=hypot(fx,fy)
        val amount=1f-exp(-distance)
        val ux=if(distance>.0001f)fx/distance else 0f
        val uy=if(distance>.0001f)fy/distance else 0f
        val along=exp(.22f*amount);val across=1f/along
        stretchX.target=across+(along-across)*ux*ux-1f
        stretchY.target=across+(along-across)*uy*uy-1f
        shear.target=((along-across)*ux*uy*.35f).coerceIn(-.04f,.04f)
        if(distance<=.0001f){stretchX.target=0f;stretchY.target=0f}
        strain.target=amount
        strain.step(dt,300f,.72f)
        stretchX.step(dt,300f,.62f);stretchY.step(dt,300f,.62f);shear.step(dt,300f,.62f)
    }
}
