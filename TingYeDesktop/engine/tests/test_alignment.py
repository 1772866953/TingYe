import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from types import SimpleNamespace

from tingye_align.book import parse, normalized, Book, Sentence
from tingye_align.alignment import Matcher, Word, timed_characters
from tingye_align.format import fingerprint, export


class AlignmentTests(unittest.TestCase):
    def test_fragmented_m4a_duration_falls_back_to_ffmpeg(self):
        from tingye_align.audio import inspect
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'fragmented.m4a';file.write_bytes(b'fragmented MP4 fixture')
            with patch('tingye_align.audio.MediaFile',return_value=SimpleNamespace(info=SimpleNamespace(length=0))), \
                 patch('tingye_align.audio.subprocess.run',return_value=SimpleNamespace(returncode=0,stderr=b'Duration: 00:54:48.90, start: 0.0')):
                self.assertEqual(inspect(str(file),'ffmpeg')['duration_ms'],3288900)
    def test_canonical_offsets_and_sentence_boundaries(self):
        with tempfile.TemporaryDirectory() as folder:
            novel=Path(folder)/"小说.txt"
            novel.write_text('第一章 山海\n“孟浩回来了！”他走进山中。\n第二章\n灯火还亮着。',encoding='utf-8')
            book=parse(novel)
            self.assertEqual(book.text,'孟浩回来了他走进山中灯火还亮着')
            self.assertEqual([s.text for s in book.sentences],['“孟浩回来了！”','他走进山中。','灯火还亮着。'])
            self.assertEqual(book.sentences[1].start,5)
            self.assertEqual(normalized('Ａ B12，İ𠀀😀'),'ａb12i')

    def test_unique_matching_has_real_times_not_proportional_times(self):
        text='清晨的山路还带着昨夜的雨意他把那封信收进书里沿着溪流继续向前'
        book=Book('小说',text,'',[Sentence(0,15,'清晨的山路还带着昨夜的雨意。'),Sentence(15,len(text),'后文')])
        matcher=Matcher(book)
        words=[Word(c,1000+i*200,1100+i*200) for i,c in enumerate(text)]
        matched=matcher.match(words)
        self.assertIsNotNone(matched)
        score,pairs=matched
        anchors=[(n,w.start,w.end,score) for n,w in pairs]
        rows=matcher.sentence_rows(anchors,'audio')
        self.assertEqual(rows[0]['t0'],1000)
        self.assertEqual(rows[0]['t1'],words[14].end)
        self.assertEqual(rows[0]['n1'],15)

    def test_repeated_passages_and_wrong_order_are_rejected(self):
        phrase='孟浩站在山峰之上望着远处的天空心里渐渐平静下来他终于知道自己应该去往何方'
        text=phrase+'与此同时'+phrase
        matcher=Matcher(Book('重复',text,'',[]))
        words=[Word(c,i*200,i*200+100) for i,c in enumerate(phrase)]
        self.assertIsNone(matcher.match(words))
        unique=Matcher(Book('唯一',phrase,'',[]))
        self.assertIsNone(unique.match(words,(10000,0)))

    def test_both_sentence_boundaries_are_required(self):
        book=Book('小说','孟浩来到山中',[].__str__(),[Sentence(0,6,'孟浩来到山中')])
        matcher=Matcher(book)
        self.assertEqual(matcher.sentence_rows([(1,100,200,1),(5,500,600,1)],'a'),[])

    def test_timestamp_formats_and_invalid_time(self):
        words=timed_characters({'words':['孟','浩'],'timestamp':[[0,120],[180,300]]},10000,1000)
        self.assertEqual((words[1].start,words[1].end),(10180,10300))
        self.assertEqual(timed_characters({'timestamp':[['孟',.1,.2]]},0,1000)[0].start,100)
        with self.assertRaises(ValueError):timed_characters({'text':'孟浩'},0,1000)
        with self.assertRaises(ValueError):timed_characters({'words':['孟'],'timestamp':[[1000,2000]]},0,1000)

    def test_export_is_small_portable_and_atomic(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'audio.m4a';file.write_bytes(bytes(range(256))*1000)
            signature=fingerprint(file)
            copy=Path(folder)/'改名.m4a';copy.write_bytes(file.read_bytes())
            self.assertEqual(signature,fingerprint(copy))
            book=Book('小说','孟浩',hashlib.sha256('孟浩'.encode()).hexdigest(),[Sentence(0,2,'孟浩')])
            target=Path(folder)/'对应.tysync'
            manifest=export(target,book,[dict(id=signature,path=str(file),name=file.name,size=file.stat().st_size,duration_ms=10000)],
                [dict(n0=0,n1=2,audio=signature,t0=100,t1=1000,confidence=.99)],False,1)
            with zipfile.ZipFile(target) as archive:
                self.assertEqual(set(archive.namelist()),{'manifest.json','sentences.jsonl'})
                self.assertNotIn('path',json.loads(archive.read('manifest.json'))['tracks'][0])
            self.assertEqual(manifest['sentence_count'],1)
            self.assertFalse(target.with_suffix('.tysync.tmp').exists())


if __name__=='__main__':unittest.main()
