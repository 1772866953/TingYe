package com.tingye.app

import com.tingye.app.reader.ReaderTurnCurve
import com.tingye.app.reader.ReaderTurnTiming
import com.tingye.app.reader.readerSwipeRelease
import org.junit.Assert.*
import org.junit.Test

class ReaderTurnMotionTest {
    @Test fun rapidInputHasNoFixedTailAndNormalReadingKeepsItsOwnPace(){
        val timing=ReaderTurnTiming()
        timing.input(1000);assertEquals(250L,timing.duration(0f,1f,false,1000))
        timing.input(1120)
        assertTrue(timing.duration(0f,1f,false,1120)<=80)
        assertTrue(timing.duration(.99f,1f,true,1120)<=2)
        assertTrue(timing.duration(0f,1f,true,1250)<=80)
        assertEquals(250L,timing.duration(0f,1f,false,1700))
    }
    @Test fun interruptionPreservesPositionAndVelocity(){
        val first=ReaderTurnCurve(0f,1f,7.2f,250)
        val x=first.position(.35f);val v=first.velocity(.35f)
        val resumed=ReaderTurnCurve(x,1f,v,100)
        assertEquals(x,resumed.position(0f),0f)
        assertEquals(v,resumed.velocity(0f),.0001f)
        assertEquals(1f,resumed.position(1f),.0001f)
        assertEquals(0f,resumed.velocity(1f),.0001f)
    }
    @Test fun bothDirectionsStayMonotoneEvenWithExcessiveInterruptedVelocity(){
        for((start,end,speed) in listOf(Triple(.2f,1f,100f),Triple(.8f,0f,-100f))){
            val curve=ReaderTurnCurve(start,end,speed,65)
            var previous=start
            repeat(101){
                val x=curve.position(it/100f)
                assertTrue(x>=minOf(start,end)-.0001f && x<=maxOf(start,end)+.0001f)
                assertTrue(if(end>start)x>=previous-.0001f else x<=previous+.0001f)
                previous=x
            }
        }
    }
    @Test fun reversingBeforeTheOldSheetReturnsCannotInventAnotherForwardPage(){
        val opposite=readerSwipeRelease(1,1,-1,true,null)
        assertFalse(opposite.commitVisible);assertEquals(0,opposite.defer)
        val crossed=readerSwipeRelease(1,-1,-1,true,null)
        assertFalse(crossed.commitVisible);assertEquals(0,crossed.defer)
        val queued=readerSwipeRelease(1,1,-1,true,1)
        assertTrue(queued.commitVisible);assertTrue(queued.cancelPending);assertEquals(0,queued.defer)
        val across=readerSwipeRelease(1,-1,-1,true,1)
        assertFalse(across.commitVisible);assertEquals(1,across.defer);assertTrue(across.deferFirst);assertTrue(across.cancelPending)
        val same=readerSwipeRelease(1,1,1,true,null)
        assertTrue(same.commitVisible);assertEquals(1,same.defer)
    }
    @Test fun catchingACancelledSheetUsesTheNewFingerDirection(){
        val release=readerSwipeRelease(null,1,-1,true,null)
        assertFalse(release.commitVisible);assertEquals(-1,release.defer)
        val small=readerSwipeRelease(1,-1,-1,false,null)
        assertFalse(small.commitVisible);assertEquals(1,small.defer);assertTrue(small.deferFirst)
    }
}
