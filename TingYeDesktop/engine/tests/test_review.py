import hashlib,sqlite3,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import numpy as np
import wave
from tingye_align.book import parse,normalized,Book,Sentence
from tingye_align.alignment import Matcher,Word
from tingye_align import review


def book_fixture():
    pieces=['夜色渐深孟浩沿着山间小路走到河边，','他将刚刚拾到的书信仔细收进衣袖，','远处的灯火仍然亮着他转身朝小镇走去。']
    text=''.join(normalized(p) for p in pieces);at=0;units=[]
    for piece in pieces:
        value=normalized(piece);units.append(Sentence(at,at+len(value),piece));at+=len(value)
    return Book('测试',text,hashlib.sha256(text.encode()).hexdigest(),units)


def spoken(text,start,step=160):return [Word(c,start+i*step,start+i*step+100) for i,c in enumerate(normalized(text))]


class ReviewTests(unittest.TestCase):
    def test_decoder_reuses_continuous_audio_without_shifting_overlaps(self):
        ffmpeg=Path(__file__).resolve().parents[2]/'resources/bin/ffmpeg.exe'
        if not ffmpeg.exists():self.skipTest('FFmpeg not present')
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'tone.wav'
            samples=(np.sin(np.arange(16000*7)*.06)*15000).astype('<i2')
            with wave.open(str(file),'wb') as output:output.setnchannels(1);output.setsampwidth(2);output.setframerate(16000);output.writeframes(samples.tobytes())
            track=dict(path=str(file));windows=[review.Window(0,a,b,0,0,()) for a,b in [(1000,3500),(2000,4000),(4500,6000)]]
            actual=list(review.stream_audio(str(ffmpeg),[track],windows,lambda:False))
            self.assertEqual(len(actual),3)
            for window,audio in actual:np.testing.assert_array_equal(audio,review.decode(str(ffmpeg),track,window.start,window.end))
    def test_punctuation_units_preserve_the_full_canonical_book(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'书.txt'
            text='他说：“价格是3.14元，别着急；天亮后：先过河、再进城。”\n雨停了！'
            path.write_text(text,encoding='utf-8');book=parse(path)
            self.assertEqual([s.text for s in book.sentences],['他说：','“价格是3.14元，','别着急；','天亮后：','先过河、','再进城。”','雨停了！'])
            self.assertEqual(book.text,normalized(text));self.assertEqual(book.sentences[-1].end,len(book.text))
            self.assertEqual(''.join(normalized(s.text) for s in book.sentences),book.text)

    def test_missing_middle_clause_after_ad_is_found_at_its_actual_audio_time(self):
        book=book_fixture();matcher=Matcher(book);unit=book.sentences[1]
        words=spoken(book.sentences[0].text,500)+spoken('欢迎收听平台节目点击关注点赞每天更新精彩内容',4000)+spoken(unit.text,13000)+spoken(book.sentences[2].text,20000)
        window=review.Window(0,0,30000,0,len(book.text),(unit,))
        found=review.evidence(matcher,window,words)
        self.assertEqual(len(found),1);self.assertEqual(found[0].start,12760)
        self.assertLess(found[0].end,18000)

    def test_advertisement_cannot_fill_a_missing_novel_clause(self):
        book=book_fixture();matcher=Matcher(book)
        words=spoken(book.sentences[0].text,500)+spoken('欢迎收听平台节目点击关注点赞每天更新精彩内容',4000)+spoken(book.sentences[2].text,20000)
        window=review.Window(0,0,30000,0,len(book.text),(book.sentences[1],))
        self.assertEqual(review.evidence(matcher,window,words),[])

    def test_missing_asr_boundary_can_be_proposed_only_with_story_context(self):
        book=book_fixture();matcher=Matcher(book);unit=book.sentences[1]
        # The first character is omitted by ASR, but the following novel passage
        # supplies independent context. Acoustic verification is still required.
        core=book.text[unit.start+1:unit.end]
        words=spoken(book.sentences[0].text,500)+spoken('欢迎收听平台节目点击关注点赞每天更新精彩内容',4000)+spoken(core,13000)+spoken(book.sentences[2].text,17000)
        window=review.Window(0,0,30000,0,len(book.text),(unit,))
        candidates=review.evidence(matcher,window,words)
        self.assertEqual(len(candidates),1)
        self.assertGreaterEqual(candidates[0].score,.9)

    def test_tiny_or_repeated_ad_like_phrase_is_not_guessed(self):
        text='山间的风吹过树林他回过头看了一眼好的随后他沿着小路继续向前走去好的'
        start=text.index('好的');unit=Sentence(start,start+2,'好的，')
        matcher=Matcher(Book('书',text,'',[unit]))
        window=review.Window(0,0,30000,0,len(text),(unit,))
        self.assertEqual(review.evidence(matcher,window,spoken('欢迎收听好的请关注本平台',1000)),[])

    def test_discontinuity_inside_a_clause_is_not_stretched_over_advertising(self):
        book=book_fixture();matcher=Matcher(book);unit=book.sentences[1]
        anchors=[(n,1000+(n-unit.start)*200,1100+(n-unit.start)*200,1.) for n in range(unit.start,unit.end)]
        anchors=[(n,a+(20000 if n>=unit.start+5 else 0),b+(20000 if n>=unit.start+5 else 0),c) for n,a,b,c in anchors]
        self.assertEqual(matcher.sentence_rows(anchors,'a'),[])

    def test_forced_original_text_with_no_acoustic_support_is_rejected(self):
        book=book_fixture();matcher=Matcher(book);unit=book.sentences[1]
        window=review.Window(0,0,30000,0,len(book.text),(unit,))
        candidate=review.Evidence(unit,0,1000,4000,1.,window)
        self.assertIsNone(review.verified_row(matcher,candidate,dict(alignment_score=.1,token_scores=[.01]),dict(id='a')))

    def test_rebuild_reuses_character_cache_and_keeps_punctuation_units(self):
        book=book_fixture();matcher=Matcher(book);database=sqlite3.connect(':memory:')
        database.execute('CREATE TABLE anchors (audio TEXT,n INTEGER,t0 INTEGER,t1 INTEGER,score REAL,PRIMARY KEY(audio,n))')
        database.execute('CREATE TABLE sentences (n0 INTEGER PRIMARY KEY,n1 INTEGER,audio TEXT,t0 INTEGER,t1 INTEGER,confidence REAL,text TEXT)')
        database.executemany('INSERT INTO anchors VALUES (?,?,?,?,?)',[('a',n,100+n*150,200+n*150,1.) for n in range(len(book.text))])
        tracks=[dict(id='a',duration_ms=30000)]
        self.assertTrue(review.rebuild(database,matcher,tracks))
        self.assertEqual(database.execute('SELECT n0,n1 FROM sentences ORDER BY n0').fetchall(),[(s.start,s.end) for s in book.sentences])
        self.assertFalse(review.rebuild(database,matcher,tracks))

    def test_review_checkpoints_resume_without_forcing_ads_or_repeating_done_work(self):
        book=book_fixture();matcher=Matcher(book);database=sqlite3.connect(':memory:')
        database.execute('CREATE TABLE anchors (audio TEXT,n INTEGER,t0 INTEGER,t1 INTEGER,score REAL,PRIMARY KEY(audio,n))')
        database.execute('CREATE TABLE sentences (n0 INTEGER PRIMARY KEY,n1 INTEGER,audio TEXT,t0 INTEGER,t1 INTEGER,confidence REAL,text TEXT)')
        first,last=book.sentences[0],book.sentences[2]
        for unit,begin in [(first,500),(last,20000)]:
            anchors=[(unit.start+i,w.start,w.end,1.) for i,w in enumerate(spoken(unit.text,begin))]
            database.executemany('INSERT INTO anchors VALUES (?,?,?,?,?)',[('a',*a) for a in anchors])
        tracks=[dict(id='a',duration_ms=30000,path='unused')];review.rebuild(database,matcher,tracks)
        words=spoken(first.text,500)+spoken('欢迎收听平台节目点击关注点赞每天更新精彩内容',4000)+spoken(book.sentences[1].text,13000)+spoken(last.text,20000)
        class Recognizer:
            batch_size=4
            calls=0
            def __call__(self,samples):
                self.calls+=1
                return [dict(words=[w.character for w in words],timestamp=[[w.start,w.end] for w in words]) for _ in samples]
            def verify(self,samples,texts):
                outputs=[]
                for sample,text in zip(samples,texts):
                    length=len(sample)/16;step=(length-480)/len(text)
                    outputs.append(dict(words=list(text),timestamp=[[round(240+i*step),round(240+(i+1)*step)] for i in range(len(text))],alignment_score=.99,token_scores=[.99]*len(text)))
                return outputs
        recognizer=Recognizer()
        with patch('tingye_align.review.decode',side_effect=lambda ff,t,a,b:np.zeros((b-a)*16,dtype=np.float32)):
            self.assertTrue(review.run_review(database,matcher,tracks,recognizer,'unused',lambda:False,lambda *_:None))
            self.assertEqual(database.execute('SELECT COUNT(*) FROM sentences').fetchone()[0],3)
            calls=recognizer.calls
            self.assertTrue(review.run_review(database,matcher,tracks,recognizer,'unused',lambda:False,lambda *_:None))
            self.assertEqual(recognizer.calls,calls)


if __name__=='__main__':unittest.main()
