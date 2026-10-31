package com.tingye.app.reader

import com.tingye.app.data.Chapter

data class ReadingMatchPlan(val points: List<ReadingSyncPoint>,val method: String,val matches: Int)

/** Narration episode numbers must not be assumed to be novel chapter numbers. */
object ReaderMatching {
    private val number=Regex("第\\s*([0-9０-９零〇一二三四五六七八九十百千万两]+)\\s*[章回]")
    private val range=Regex("(?<![0-9])([0-9]{1,5})\\s*[-–—~～至到]\\s*([0-9]{1,5})(?![0-9])")
    private fun digits(value: String)=value.map { if(it in '０'..'９') '0'+(it-'０') else it }.joinToString("")
    fun chapterNumber(title: String): Int? {
        val value=number.find(title)?.groupValues?.get(1)?.let(::digits) ?: return null
        value.toIntOrNull()?.let { return it }
        val basic="零一二三四五六七八九"
        if(value.none { it in "十百千万" }) return value.map { if(it=='〇') 0 else basic.indexOf(it) }
            .takeIf { it.all { digit->digit>=0 } }?.joinToString("")?.toIntOrNull()
        var total=0;var section=0;var digit=0
        value.forEach { c -> when(c) {
            '十','百','千' -> { val unit=when(c){'十'->10;'百'->100;else->1000};section+=(if(digit==0)1 else digit)*unit;digit=0 }
            '万' -> { total+=(section+digit)*10_000;section=0;digit=0 }
            '两' -> digit=2
            '〇' -> digit=0
            else -> { digit=basic.indexOf(c);if(digit<0) return null }
        } }
        return total+section+digit
    }
    private fun audioRange(title: String): IntRange? = range.findAll(digits(title)).lastOrNull()?.let {
        val first=it.groupValues[1].toInt();val last=it.groupValues[2].toInt()
        if(first>0 && last>=first) first..last else null
    }
    fun plan(id: String,text: List<TextChapter>,audio: List<Chapter>,forceWhole: Boolean=false): ReadingMatchPlan {
        if(text.isEmpty() || audio.isEmpty()) return ReadingMatchPlan(emptyList(),"whole",0)
        val numbered=text.mapIndexedNotNull { index,t->chapterNumber(t.title)?.let { it to index } }
        val ranges=audio.map { audioRange(it.title) }
        val numberToIndex=numbered.groupBy({it.first},{it.second}).filterValues { it.size==1 }.mapValues { it.value.single() }
        val consistent=!forceWhole && ranges.size>=2 && ranges.all { it!=null } &&
            ranges.first()!!.first==numberToIndex.keys.minOrNull() &&
            ranges.last()!!.last==numberToIndex.keys.maxOrNull() &&
            ranges.filterNotNull().zipWithNext().all { (a,b)->b.first==a.last+1 }
        if(consistent) {
            val points=audio.flatMapIndexed { index,track ->
                val r=ranges[index]!!
                val indexes=r.map { numberToIndex[it] }
                val duration=track.durationMs ?: 0
                if(duration<=0 || indexes.any { it==null } || indexes.filterNotNull().zipWithNext().any { (a,b)->b!=a+1 }) emptyList()
                else listOf(ReadingSyncPoint(readerId=id,textChapter=indexes.first()!!,textOffset=0,audioChapterId=track.id,positionMs=0,kind="chapter"),
                    ReadingSyncPoint(readerId=id,textChapter=indexes.last()!!,textOffset=text[indexes.last()!!].length,
                        audioChapterId=track.id,positionMs=duration,kind="chapter"))
            }
            if(points.isNotEmpty()) return ReadingMatchPlan(points,"ranges",points.size/2)
        }
        val whole=ReadingAlignment.wholeBookPoints(id,text,audio)
        if(forceWhole) return ReadingMatchPlan(whole,"whole",0)
        val named=ReadingAlignment.chapterSuggestions(text,audio).map { (i,j)->
            ReadingSyncPoint(readerId=id,textChapter=i,textOffset=0,audioChapterId=audio[j].id,positionMs=0,kind="chapter")
        }
        val points=(whole+named).sortedBy { it.textChapter*1_000_000L+it.textOffset }.fold(emptyList<ReadingSyncPoint>()) { accepted,p ->
            if(ReadingAlignment.calibrationIsOrdered(text,audio,accepted.map { it.copy(kind="manual") },p.copy(kind="manual"))) accepted+p
            else accepted
        }
        return ReadingMatchPlan(points,if(named.isNotEmpty())"titles" else "whole",named.size)
    }
}
