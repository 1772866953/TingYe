"""Second pass: rebuild punctuation units, then acoustically verify holes.

Book positions and audio times are never interpolated over advertisements. A new
unit requires independent ASR evidence, fresh original-text CTC alignment, and
consistent neighboring positions. Unconfirmed material remains unindexed.
"""
from __future__ import annotations
from dataclasses import dataclass
from bisect import bisect_left,bisect_right
import hashlib,json,subprocess
import numpy as np
from rapidfuzz import fuzz
from rapidfuzz.distance import Levenshtein
from .alignment import timed_characters
from .audio import RATE,NO_WINDOW,Prefetch

REVISION='punctuation-review-v2'
KEYS=('n0','n1','audio','t0','t1','confidence','text')


def initialize(database):
    database.execute('CREATE TABLE IF NOT EXISTS pipeline_meta (key TEXT PRIMARY KEY,value TEXT)')
    database.execute('CREATE TABLE IF NOT EXISTS review_windows (key TEXT PRIMARY KEY,revision TEXT,added INTEGER)')


def insert_rows(database,rows,track_order,table='sentences'):
    assert table in ('sentences','rebuilt_sentences')
    accepted=[]
    for row in rows:
        old=database.execute(f'SELECT confidence FROM {table} WHERE n0=?',(row['n0'],)).fetchone()
        if old and old[0]>=row['confidence']:continue
        before=database.execute(f'SELECT n1,audio,t0,t1 FROM {table} WHERE n0<? ORDER BY n0 DESC LIMIT 1',(row['n0'],)).fetchone()
        after=database.execute(f'SELECT n0,audio,t0 FROM {table} WHERE n0>? ORDER BY n0 LIMIT 1',(row['n0'],)).fetchone()
        position=(track_order[row['audio']],row['t0'])
        if before and (before[0]>row['n0'] or (track_order[before[1]],before[2])>position):continue
        if after and (after[0]<row['n1'] or (track_order[after[1]],after[2])<position):continue
        if before and before[1]==row['audio'] and row['t0']<before[3]-240:continue
        if after and after[1]==row['audio'] and row['t1']>after[2]+240:continue
        database.execute(f'INSERT INTO {table} VALUES (?,?,?,?,?,?,?) ON CONFLICT(n0) DO UPDATE SET n1=excluded.n1,audio=excluded.audio,t0=excluded.t0,t1=excluded.t1,confidence=excluded.confidence,text=excluded.text',tuple(row[k] for k in KEYS))
        accepted.append(row)
    return accepted


def rebuild(database,matcher,tracks,on_progress=lambda *_:None,force=False):
    """All existing character evidence is checked against the new short units.
    Atomic: interrupted rebuilding leaves the original rows available.
    """
    initialize(database)
    state=database.execute("SELECT value FROM pipeline_meta WHERE key='segmentation'").fetchone()
    if state and state[0]==REVISION and not force:return False
    order={t['id']:i for i,t in enumerate(tracks)}
    with database:
        database.execute('DROP TABLE IF EXISTS temp.rebuilt_sentences')
        database.execute('CREATE TEMP TABLE rebuilt_sentences (n0 INTEGER PRIMARY KEY,n1 INTEGER,audio TEXT,t0 INTEGER,t1 INTEGER,confidence REAL,text TEXT)')
        for i,track in enumerate(tracks):
            anchors=database.execute('SELECT n,t0,t1,score FROM anchors WHERE audio=? ORDER BY n',(track['id'],)).fetchall()
            insert_rows(database,matcher.sentence_rows(anchors,track['id']),order,'rebuilt_sentences')
            on_progress(i+1,len(tracks))
        database.execute('DELETE FROM sentences')
        database.execute('INSERT INTO sentences SELECT * FROM rebuilt_sentences')
        database.execute('DROP TABLE rebuilt_sentences')
        database.execute("INSERT OR REPLACE INTO pipeline_meta VALUES ('segmentation',?)",(REVISION,))
    return True


@dataclass(frozen=True,eq=False)
class Window:
    track:int
    start:int
    end:int
    left:int
    right:int
    units:tuple

    def key(self,tracks):
        return hashlib.sha256(json.dumps([REVISION,tracks[self.track]['id'],self.start,self.end,self.left,self.right]).encode()).hexdigest()


def plan(book,rows,tracks):
    """Missing units are bracketed by verified novel/audio positions. Long ad
    gaps are searched in overlapping clips, not squeezed into a novel sentence.
    """
    known={row['n0']:row for row in rows}
    order={t['id']:i for i,t in enumerate(tracks)}
    prefix=[0]
    for track in tracks:prefix.append(prefix[-1]+track['duration_ms'])
    windows=[];i=0;previous=None
    units=book.sentences
    while i<len(units):
        if units[i].start in known:previous=known[units[i].start];i+=1;continue
        start=i
        while i<len(units) and units[i].start not in known:i+=1
        following=known.get(units[i].start) if i<len(units) else None
        first=order[previous['audio']] if previous else 0
        last=order[following['audio']] if following else len(tracks)-1
        begin=prefix[first]+previous['t1'] if previous else 0
        end=prefix[last]+following['t0'] if following else prefix[-1]
        if first>last or end<begin:continue
        left=max(0,units[start].start-64)
        right=min(len(book.text),units[i-1].end+64)
        pending=tuple(units[start:i])
        for index in range(first,last+1):
            low=max(0,begin-prefix[index]-4000)
            high=min(tracks[index]['duration_ms'],end-prefix[index]+4000)
            at=int(low//1000*1000)
            while at<high:
                stop=min(int(high),at+30000)
                if stop-at>=500:windows.append(Window(index,at,stop,left,right,pending))
                if stop>=high:break
                at+=24000
    # Coalesce neighboring small holes so one decoder/inference call covers them.
    merged=[]
    for window in sorted(windows,key=lambda w:(w.track,w.start,w.end)):
        if merged and window.track==merged[-1].track and window.start<=merged[-1].end and max(window.end,merged[-1].end)-merged[-1].start<=30000:
            old=merged.pop();combined={s.start:s for s in (*old.units,*window.units)}
            merged.append(Window(old.track,old.start,max(old.end,window.end),min(old.left,window.left),max(old.right,window.right),tuple(combined[k] for k in sorted(combined))))
        else:merged.append(window)
    return merged


@dataclass
class Evidence:
    unit:object
    track:int
    start:int
    end:int
    score:float
    window:Window


def evidence(matcher,window,words):
    query=''.join(w.character for w in words)
    if not query:return []
    units=window.units
    if len(units)>250:
        # A wholly unmatched track must not test every novel phrase against every
        # ad clip. Reacquire unique story context first, then inspect its units.
        match=matcher.match_parts(words)
        if match is None:return []
        _,pairs=match
        pairs=[(n,w) for n,w in pairs if window.left<=n<window.right]
        if not pairs:return []
        low=max(window.left,pairs[0][0]-40);high=min(window.right,pairs[-1][0]+41)
        units=matcher.book.sentences[max(0,bisect_right(matcher.starts,low)-1):bisect_left(matcher.starts,high)]
        contextual=dict(pairs)
        local=matcher.book.text[low:high]
    else:
        contextual=dict(matcher.local_pairs(words,window.left,window.right))
        local=matcher.book.text[window.left:window.right]
    result=[]
    word_indices={id(word):i for i,word in enumerate(words)}
    for unit in units:
        text=matcher.book.text[unit.start:unit.end]
        fuzzy_boundary=False
        supported=[contextual.get(n) for n in range(unit.start,unit.end)]
        span=None
        if supported and all(word is not None for word in supported):
            positions=[word_indices[id(word)] for word in supported]
            if all(b==a+1 for a,b in zip(positions,positions[1:])):
                span=supported;score=1.
        first=query.find(text)
        if span is not None:
            pass # Context disambiguates common tiny clauses such as “是” or “道”.
        elif first>=0:
            if query.find(text,first+1)>=0:continue
            left=first;right=first+len(text);score=1.
        elif len(text)>=8:
            hit=fuzz.partial_ratio_alignment(text,query,score_cutoff=88 if len(text)<12 else 92)
            if hit is None or hit.src_end-hit.src_start<len(text)-max(1,len(text)//10):continue
            left=hit.dest_start;right=hit.dest_end
            observed=query[left:right]
            operations=Levenshtein.opcodes(text,observed)
            if not operations:continue
            fuzzy_boundary=operations[0].tag!='equal' or operations[-1].tag!='equal'
            score=sum(op.src_end-op.src_start for op in operations if op.tag=='equal')/len(text)
            if score<(.85 if len(text)<12 else .9):continue
            if any(op.tag=='insert' and 0<op.src_start<len(text) for op in operations):continue
            equal=[op for op in operations if op.tag=='equal']
            if not equal:continue
            # An unmatched boundary token may be the last word of an ad. Crop
            # around actual equal speech, then let acoustics confirm the edge.
            right=left+equal[-1].dest_end;left+=equal[0].dest_start
        else:continue
        if span is None:span=words[left:right]
        if not span or any(b.start-a.end>2500 for a,b in zip(span,span[1:])):continue
        begin,end=span[0].start,span[-1].end
        if end<=begin or end-begin>max(6000,len(text)*1800+3000):continue
        # Tiny or repeated phrases require independently recognized context.
        before=sum(unit.start-40<=n<unit.start and begin-12000<=w.start<=begin for n,w in contextual.items())
        after=sum(unit.end<=n<unit.end+40 and end<=w.end<=end+12000 for n,w in contextual.items())
        repeated=local.find(text)!=local.rfind(text)
        if len(text)<6 and (before<4 or after<4 or before+after<16):continue
        if (len(text)<12 or repeated) and before+after<8:continue
        if fuzzy_boundary and not (before>=4 and after>=4 and before+after>=12 or len(text)>=12 and max(before,after)>=16):continue
        padding=600 if fuzzy_boundary else 240
        result.append(Evidence(unit,window.track,max(window.start,begin-padding),min(window.end,end+padding),score,window))
    return result


def decode(ffmpeg,track,start,end):
    process=subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-ss',f'{start/1000:.3f}',
        '-i',track['path'],'-t',f'{(end-start)/1000:.3f}','-vn','-ac','1','-ar',str(RATE),'-f','f32le','pipe:1'],
        capture_output=True,timeout=90,creationflags=NO_WINDOW)
    if process.returncode:raise ValueError('核验片段解码失败：'+process.stderr.decode('utf-8',errors='replace')[-250:])
    return np.frombuffer(process.stdout[:len(process.stdout)//4*4],dtype='<f4').copy()


def stream_audio(ffmpeg,tracks,windows,stop):
    """Reuse one decoder for nearby review windows, preserving original times.
    Large already-verified gaps are skipped by starting a new decoder.
    """
    groups=[]
    for window in windows:
        if groups and window.track==groups[-1][-1].track and window.start-groups[-1][-1].end<=60000:
            groups[-1].append(window)
        else:groups.append([window])
    for group in groups:
        if stop():return
        if len(group)==1:
            window=group[0];yield window,decode(ffmpeg,tracks[window.track],window.start,window.end);continue
        base=group[0].start
        command=[ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-ss',f'{base/1000:.3f}',
            '-i',tracks[group[0].track]['path'],'-t',f'{(group[-1].end-base)/1000:.3f}',
            '-vn','-ac','1','-ar',str(RATE),'-f','f32le','pipe:1']
        process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=NO_WINDOW)
        buffer=b'';origin=0;eof=False;decode_error=b''
        try:
            for window in group:
                if stop():return
                if eof:
                    process.wait(timeout=10)
                    if process.returncode:
                        decode_error=process.stderr.read();raise ValueError('核验解码失败：'+decode_error.decode('utf-8',errors='replace')[-200:])
                    yield window,np.zeros(0,dtype=np.float32);continue
                left=(window.start-base)*RATE//1000*4;right=(window.end-base)*RATE//1000*4
                # Discard without retaining audio between requested windows.
                if left>=origin+len(buffer):
                    origin+=len(buffer);buffer=b''
                    while origin<left:
                        part=process.stdout.read(min(left-origin,1048576))
                        if not part:eof=True;break
                        origin+=len(part)
                else:
                    buffer=buffer[left-origin:];origin=left
                if eof:
                    process.wait(timeout=10)
                    if process.returncode:
                        decode_error=process.stderr.read();raise ValueError('核验解码失败：'+decode_error.decode('utf-8',errors='replace')[-200:])
                    yield window,np.zeros(0,dtype=np.float32);continue
                while origin+len(buffer)<right:
                    part=process.stdout.read(min(right-origin-len(buffer),1048576))
                    if not part:eof=True;break
                    buffer+=part
                data=buffer[:min(len(buffer),right-origin)]
                if eof:
                    process.wait(timeout=10)
                    if process.returncode:
                        decode_error=process.stderr.read();raise ValueError('核验解码失败：'+decode_error.decode('utf-8',errors='replace')[-200:])
                data=data[:len(data)//4*4]
                yield window,np.frombuffer(data,dtype='<f4').copy()
        finally:
            process.stdout.close()
            cancelled=not eof and process.poll() is None
            if cancelled:process.terminate()
            process.wait(timeout=10);error=decode_error or process.stderr.read();process.stderr.close()
            if not cancelled and process.returncode:raise ValueError('核验解码失败：'+error.decode('utf-8',errors='replace')[-200:])


def verified_row(matcher,candidate,response,track):
    scores=response.get('token_scores',[])
    if not scores or response.get('alignment_score',0)<.55 or min(scores)<.08:return None
    unit=candidate.unit
    try:words=timed_characters(response,candidate.start,candidate.end-candidate.start)
    except ValueError:return None
    text=matcher.book.text[unit.start:unit.end]
    if ''.join(w.character for w in words)!=text:return None
    if not words or words[0].start<candidate.start or words[-1].end>candidate.end+120:return None
    confidence=min(.99,candidate.score*.98)
    anchors=[(unit.start+i,w.start,w.end,confidence) for i,w in enumerate(words)]
    rows=matcher.sentence_rows(anchors,track['id'])
    row=next((row for row in rows if row['n0']==unit.start and row['n1']==unit.end),None)
    return (row,anchors) if row else None


def run_review(database,matcher,tracks,recognizer,ffmpeg,stop,on_progress):
    initialize(database)
    rows=[dict(zip(KEYS,row)) for row in database.execute('SELECT n0,n1,audio,t0,t1,confidence,text FROM sentences ORDER BY n0')]
    windows=plan(matcher.book,rows,tracks)
    done={row[0] for row in database.execute('SELECT key FROM review_windows WHERE revision=?',(REVISION,))}
    completed=sum(w.key(tracks) in done for w in windows)
    total=len(windows)
    added=database.execute('SELECT COALESCE(SUM(added),0) FROM review_windows WHERE revision=?',(REVISION,)).fetchone()[0]
    known={row['n0'] for row in rows};order={t['id']:i for i,t in enumerate(tracks)}
    on_progress(completed,total,added)
    def stream():
        pending=[w for w in windows if w.key(tracks) not in done]
        yield from stream_audio(ffmpeg,tracks,pending,stop)
    with Prefetch(stream(),max(1,recognizer.batch_size),set(),stop) as batches:
        for batch in batches:
            if stop():return False
            responses=[{} for _ in batch]
            usable=[i for i,(_,sample) in enumerate(batch) if len(sample)>=RATE//2]
            if usable:
                fresh=recognizer([batch[i][1] for i in usable])
                if len(fresh)!=len(usable):raise ValueError('核验识别结果数量不一致')
                for i,response in zip(usable,fresh):responses[i]=response
            candidates={}
            for (window,samples),response in zip(batch,responses):
                try:words=timed_characters(response,window.start,round(len(samples)/RATE*1000))
                except ValueError:words=[]
                for candidate in evidence(matcher,window,words):
                    if candidate.unit.start in known:continue
                    # The same phrase found at two different times is ambiguous.
                    old=candidates.get(candidate.unit.start)
                    if old is False:continue
                    if old and (old.track!=candidate.track or abs(old.start-candidate.start)>700):
                        candidates[candidate.unit.start]=False;continue
                    if not old or candidate.score>old.score:candidates[candidate.unit.start]=candidate
            candidates=[c for c in candidates.values() if c is not False]
            crops=[];usable_candidates=[]
            batch_samples={w:sample for w,sample in batch}
            for candidate in candidates:
                sample=batch_samples[candidate.window]
                left=max(0,(candidate.start-candidate.window.start)*RATE//1000)
                right=min(len(sample),(candidate.end-candidate.window.start)*RATE//1000)
                crop=sample[left:right]
                if len(crop)<RATE//5:continue
                crops.append(crop);usable_candidates.append(candidate)
            candidates=usable_candidates
            if stop():return False
            verified=recognizer.verify(crops,[matcher.book.text[c.unit.start:c.unit.end] for c in candidates]) if candidates else []
            if len(verified)!=len(candidates):raise ValueError('短句强制对齐结果数量不一致')
            new=0
            with database:
                for candidate,response in zip(candidates,verified):
                    result=verified_row(matcher,candidate,response,tracks[candidate.track])
                    if result is None:continue
                    row,anchors=result
                    if not insert_rows(database,[row],order):continue
                    database.executemany('INSERT INTO anchors VALUES (?,?,?,?,?) ON CONFLICT(audio,n) DO UPDATE SET t0=excluded.t0,t1=excluded.t1,score=excluded.score',
                        [(row['audio'],*anchor) for anchor in anchors])
                    known.add(row['n0']);new+=1
                for i,(window,_) in enumerate(batch):
                    database.execute('INSERT OR IGNORE INTO review_windows VALUES (?,?,?)',(window.key(tracks),REVISION,new if i==0 else 0))
            completed+=len(batch);added+=new
            on_progress(completed,total,added)
    return not stop()
