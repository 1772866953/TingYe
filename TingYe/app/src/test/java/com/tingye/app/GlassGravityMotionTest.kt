package com.tingye.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlassGravityMotionTest {
    private fun settle(m:GlassGravityMotion,x:Float,y:Float,z:Float=0f,rotation:Int=0){
        repeat(100){m.sample(x,y,z,rotation,(it+1)*50_000_000L)}
    }
    @Test fun oppositeTiltsMoveOnlyTheLightingInOppositeDirections(){
        val left=GlassGravityMotion();val right=GlassGravityMotion()
        settle(left,-6.9f,6.9f);settle(right,6.9f,6.9f)
        assertTrue(left.direction.x>.4f);assertTrue(right.direction.x< -.4f)
        assertEquals(left.direction.y,right.direction.y,.005f)
        assertEquals(1f,hypot(left.direction.x,left.direction.y),.0001f)
    }
    @Test fun screenRotationKeepsTheSamePhysicalDownDirection(){
        val portrait=GlassGravityMotion();val landscape=GlassGravityMotion()
        settle(portrait,0f,9.81f);settle(landscape,-9.81f,0f,rotation=1)
        assertEquals(portrait.direction.x,landscape.direction.x,.0001f)
        assertEquals(portrait.direction.y,landscape.direction.y,.0001f)
    }
    @Test fun flatPhoneAndStationaryNoiseDoNotKeepInvalidatingGlass(){
        val flat=GlassGravityMotion()
        repeat(100){assertFalse(flat.sample(0f,0f,9.81f,0,(it+1)*50_000_000L))}
        val noisy=GlassGravityMotion();var changes=0
        repeat(150){
            if(noisy.sample(if(it%2==0).01f else -.01f,9.81f,0f,0,(it+1)*50_000_000L))changes++
        }
        assertTrue(changes<4)
    }
    @Test fun flipsAreRateLimitedAndInvalidSensorValuesAreIgnored(){
        val motion=GlassGravityMotion();var angle=(PI/2).toFloat()
        repeat(80){
            motion.sample(2f,-9.6f,0f,0,(it+1)*50_000_000L)
            val next=atan2(motion.direction.y,motion.direction.x)
            var delta=abs(next-angle)
            if(delta>PI)delta=2*PI.toFloat()-delta
            assertTrue(delta<=.205f);angle=next
        }
        val previous=motion.direction
        assertFalse(motion.sample(Float.NaN,1f,1f,0,5_000_000_000L))
        assertFalse(motion.sample(80f,0f,0f,0,5_100_000_000L))
        assertEquals(previous,motion.direction)
    }
}
