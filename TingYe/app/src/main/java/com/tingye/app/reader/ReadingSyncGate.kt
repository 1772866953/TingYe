package com.tingye.app.reader

/** Tracks navigation origin, so acknowledging a reader seek cannot move the
 * reader back to the top of the page or overwrite a newer gesture. */
class ReadingSyncGate {
    var revision=0L
        private set
    private var reading=false
    private var expected:Pair<Long,AudioLocation>?=null
    private var acknowledged=false
    fun readingStarted(){revision++;reading=true}
    fun readingEnded(){revision++;reading=false}
    fun reset(){readingEnded();expected=null;acknowledged=false}
    fun sentToAudio(book:Long,target:AudioLocation){
        readingEnded();expected=book to target;acknowledged=false
    }
    fun acceptsAudio(book:Long,chapter:Long,position:Long,playing:Boolean):Boolean {
        if(playing){expected=null;acknowledged=false;return !reading}
        if(reading)return false
        expected?.let { (id,target)->
            if(id==book && target.chapterId==chapter && kotlin.math.abs(position-target.positionMs)<=250){acknowledged=true;return false}
            if(!acknowledged)return false
            expected=null
        }
        return true
    }
}
