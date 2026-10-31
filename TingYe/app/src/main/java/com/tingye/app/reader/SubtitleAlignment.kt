package com.tingye.app.reader

data class SubtitleCue(val timeMs: Long, val text: String)
data class SubtitleMatch(val offset: Int, val timeMs: Long)

object SubtitleAlignment {
    fun parse(source: String): List<SubtitleCue> {
        val timestamp=Regex("(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})")
        val tags=Regex("<[^>]*>")
        return source.replace("\r\n","\n").replace('\r','\n').split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines=block.lines()
            val index=lines.indexOfFirst { "-->" in it }
            if(index<0) return@mapNotNull null
            val match=timestamp.find(lines[index].substringBefore("-->")) ?: return@mapNotNull null
            val (hours,minutes,seconds,millis)=match.destructured
            val time=((hours.toLongOrNull() ?: 0)*3600+minutes.toLong()*60+seconds.toLong())*1000+millis.toLong()
            val body=lines.drop(index+1).joinToString(" ").replace(tags,"").trim()
            body.takeIf { it.isNotBlank() }?.let { SubtitleCue(time,it) }
        }.sortedBy { it.timeMs }
    }
    private fun normalize(value: String)=value.filter { it.isLetterOrDigit() }.lowercase()
    fun match(text: String, cues: List<SubtitleCue>): List<SubtitleMatch> = matchSections(sequenceOf(text),cues)

    /** Scan bounded windows with overlap, keeping original offsets and checking
     * uniqueness across the whole novel, including internal chapter boundaries. */
    fun matchSections(sections: Sequence<String>, cues: List<SubtitleCue>): List<SubtitleMatch> {
        val candidates=cues.map { it to normalize(it.text) }.filter { it.second.length>=8 }
        if(candidates.isEmpty()) return emptyList()
        val overlap=candidates.maxOf { it.second.length }-1
        val limit=maxOf(65536,overlap*2+1)
        val text=StringBuilder()
        val offsets=ArrayList<Int>()
        val occurrences=Array(candidates.size) { linkedSetOf<Int>() }
        fun scan() {
            val window=text.toString()
            candidates.forEachIndexed { index,(_,needle) ->
                if(occurrences[index].size<2) {
                    var at=window.indexOf(needle)
                    while(at>=0 && occurrences[index].size<2) {
                        occurrences[index].add(offsets[at])
                        at=window.indexOf(needle,at+1)
                    }
                }
            }
        }
        var global=0
        sections.forEach { section ->
            section.forEach { char ->
                if(char.isLetterOrDigit()) {
                    text.append(char.lowercaseChar());offsets.add(global)
                    if(text.length>=limit) {
                        scan()
                        val discard=text.length-overlap
                        text.delete(0,discard);offsets.subList(0,discard).clear()
                    }
                }
                global++
            }
        }
        scan()
        var lastOffset=-1
        var lastTime=-1L
        return candidates.mapIndexedNotNull { index,(cue,_) ->
            val offset=occurrences[index].singleOrNull() ?: return@mapIndexedNotNull null
            if(offset<=lastOffset || cue.timeMs<=lastTime) return@mapIndexedNotNull null
            lastOffset=offset;lastTime=cue.timeMs
            SubtitleMatch(offset,cue.timeMs)
        }
    }
}
