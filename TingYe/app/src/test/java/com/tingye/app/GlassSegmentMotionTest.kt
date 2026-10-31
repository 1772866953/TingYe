package com.tingye.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassSegmentMotionTest{
    private fun advance(m:GlassSegmentMotion,seconds:Float,hz:Int=60){
        repeat((seconds*hz).toInt()){m.step(1f/hz)}
    }
    @Test fun selectionAcknowledgementCannotReplayTheOldIndicator(){
        val m=GlassSegmentMotion(40f,70f,1f)
        m.press(40f,70f);advance(m,.3f)
        m.drag(260f,70f,0f,320f,0f);advance(m,.3f)
        m.release(280f,70f)
        repeat(30){
            val before=m.frame()
            m.selection(280f,70f)
            assertEquals(before,m.frame()) // no snap, reset, or second body
            m.step(1f/60f)
            assertTrue(m.frame().x>250f)
        }
        advance(m,1f);assertEquals(280f,m.frame().x,.001f)
    }
    @Test fun entireDockHasExpansionAndARealDampedReturn(){
        val m=GlassSegmentMotion(40f,70f,1f)
        m.press(40f,70f);advance(m,.40f)
        assertTrue(1f+.06f*m.frame().dock>1.055f)
        m.release(40f,70f)
        var minimum=1f
        repeat(90){m.step(1f/60f);minimum=minOf(minimum,m.frame().dock)}
        assertTrue("dock should retain a small inertial return",minimum<0f)
        assertTrue("return must remain restrained",minimum>-.08f)
        assertEquals(0f,m.frame().dock,0f)
        assertFalse(m.step(1f/60f)) // no permanent frame loop after settling
    }
    @Test fun reversingAndRepressingPreservesTheVisiblePose(){
        val m=GlassSegmentMotion(40f,70f,1f)
        m.press(280f,70f);advance(m,.08f)
        m.drag(250f,70f,4f,320f,8f);advance(m,.04f)
        val before=m.frame();m.release(280f,70f);assertEquals(before,m.frame())
        m.step(1f/60f)
        val returning=m.frame();m.press(40f,70f);assertEquals(returning,m.frame())
        advance(m,.3f);assertTrue(m.frame().x<45f)
        m.release(40f,70f);advance(m,2f)
        assertEquals(40f,m.frame().x,0f)
        assertEquals(0f,m.frame().dockX,0f)
        assertEquals(0f,m.frame().y,0f)
    }
    @Test fun sameMotionAt60And120Hz(){
        fun sample(hz:Int):GlassSegmentMotion.Frame{
            val m=GlassSegmentMotion(40f,70f,1f)
            m.press(280f,100f);advance(m,.2f,hz)
            m.release(280f,100f);advance(m,.1f,hz)
            return m.frame()
        }
        val a=sample(60);val b=sample(120)
        assertEquals(a.x,b.x,.01f);assertEquals(a.width,b.width,.01f)
        assertEquals(a.dock,b.dock,.001f);assertEquals(a.lift,b.lift,.001f)
    }
    @Test fun cancellationResetsPullWithoutLeavingInvisibleWork(){
        val m=GlassSegmentMotion(40f,70f,3.85f)
        m.press(40f,70f);m.drag(-10f,70f,20f,320f,-45f);advance(m,.4f)
        assertTrue(abs(m.frame().dockX)>1f)
        m.release(40f,70f);advance(m,2f)
        assertEquals(40f,m.frame().x,0f)
        assertEquals(0f,m.frame().strain,0f)
        assertFalse(m.holding);assertFalse(m.step(1f/60f))
    }
    @Test fun tapWithinOneFrameStillHasOneVisiblePressResponse(){
        val m=GlassSegmentMotion(40f,70f,1f)
        m.press(280f,70f);m.release(280f,70f)
        var dockPeak=0f;var lensPeak=0f
        repeat(90){
            m.step(1f/60f);dockPeak=maxOf(dockPeak,m.frame().dock);lensPeak=maxOf(lensPeak,m.frame().lift)
        }
        assertTrue(dockPeak>.4f);assertTrue(lensPeak>.4f)
        assertEquals(280f,m.frame().x,0f)
        assertFalse(m.step(1f/60f))
    }
    @Test fun contactRespondsBeforeTheBodyAndLoadingAcceleratesThenBrakes(){
        val m=GlassSegmentMotion(40f,70f,1f);m.press(40f,70f)
        m.step(1f/60f)
        assertTrue(m.frame().contact>.05f)
        assertTrue("body must not jump down with the contact",m.frame().lift<.02f)
        val samples=FloatArray(5)
        val n=GlassSegmentMotion(40f,70f,1f);n.press(40f,70f)
        for(i in samples.indices){advance(n,.05f);samples[i]=n.frame().dock}
        assertTrue(samples[1]<.4f)
        assertTrue(samples[2]-samples[1]>samples[1]-samples[0])
        assertTrue(samples[4]-samples[3]<samples[3]-samples[2])
        advance(n,.3f);assertEquals(1f,n.frame().dock,.01f)
    }
    @Test fun horizontalAndVerticalLoadsChangeDifferentAxes(){
        val horizontal=GlassSegmentMotion(40f,70f,1f)
        horizontal.press(40f,70f);advance(horizontal,.5f)
        horizontal.drag(40f,70f,0f,320f,-14f);advance(horizontal,.4f)
        assertTrue(horizontal.frame().stretchX>1.04f)
        assertTrue(horizontal.frame().stretchY<.97f)
        val vertical=GlassSegmentMotion(40f,70f,1f)
        vertical.press(40f,70f);advance(vertical,.5f)
        vertical.drag(40f,70f,6f,320f,0f,120f);advance(vertical,.4f)
        assertTrue(vertical.frame().stretchY>1.04f)
        assertTrue(vertical.frame().stretchX<.97f)
    }
    @Test fun obliqueLoadingPreservesAreaAndNeverRotatesOrExplodes(){
        val m=GlassSegmentMotion(40f,70f,3.85f)
        m.press(40f,70f)
        for(i in 0..100){
            val direction=if(i<50)1 else -1
            m.drag(40f+direction*50f,70f,direction*18f,320f,direction*54f,direction*160f)
            m.step(1f/120f)
            val f=m.frame()
            assertEquals(1f,f.stretchX*f.stretchY-f.shear*f.shear,.0001f)
            assertTrue(f.stretchX in .78f..1.28f && f.stretchY in .78f..1.28f)
            assertTrue(abs(f.shear)<.045f)
        }
        val pulled=m.frame();m.release(40f,70f);assertEquals(pulled,m.frame())
        advance(m,2f)
        assertEquals(1f,m.frame().stretchX,0f);assertEquals(1f,m.frame().stretchY,0f)
        assertEquals(0f,m.frame().shear,0f);assertFalse(m.step(1f/60f))
    }
    @Test fun scrollCancellationDoesNotFinishAFalsePressPulse(){
        val m=GlassSegmentMotion(40f,70f,1f)
        m.press(40f,70f);m.step(1f/120f)
        m.release(40f,70f,minimumPress=false)
        var peak=0f
        repeat(120){m.step(1f/120f);peak=maxOf(peak,m.frame().dock)}
        assertTrue("yielding to a scroll must not inflate the button",peak<.08f)
        advance(m,1f);assertFalse(m.step(1f/60f))
    }
    @Test fun liftingHasABoundedTremorWhileStillHeld(){
        val m=GlassSegmentMotion(40f,70f,1f);m.press(40f,70f)
        var peak=0f;var returnedBelow=false
        repeat(180){
            m.step(1f/120f);val lift=m.frame().lift
            peak=maxOf(peak,lift)
            if(peak>1.02f && lift<1f)returnedBelow=true
        }
        assertTrue("lift should overshoot gently",peak>1.02f)
        assertTrue("lift must remain bounded",peak<1.16f)
        assertTrue("a held lens should visibly settle after the initial lift",returnedBelow)
        assertEquals(1f,m.frame().lift,.001f)
        assertFalse(m.step(1f/60f))
    }
}
