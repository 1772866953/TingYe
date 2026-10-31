package com.tingye.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlassThumbDragTest {
    @Test fun movingInsideTheTrackGeneratesDirectionalLoadAndRestSettles(){
        val motion=GlassThumbDrag(1f);motion.reset(0f)
        var x=0f
        repeat(30){x+=5f;motion.step(x,1f/60f)}
        assertTrue(motion.force>8f);assertTrue(motion.force<30f)
        repeat(18){x-=5f;motion.step(x,1f/60f)}
        assertTrue("reverse load must follow the new direction",motion.force< -8f)
        repeat(180){motion.step(x,1f/60f)}
        assertEquals(0f,motion.force,0f);assertFalse(motion.step(x,1f/60f))
    }
    @Test fun largeJumpsAreBoundedAndARepressDoesNotInheritOldLoad(){
        val motion=GlassThumbDrag(3.85f);motion.reset(0f)
        motion.step(20_000f,1f/120f)
        assertTrue(abs(motion.force)<=48f*3.85f)
        motion.reset(200f);assertEquals(0f,motion.force,0f)
        assertFalse(motion.step(200f,1f/120f))
    }
}
