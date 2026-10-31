from __future__ import annotations

from bisect import bisect_right
from collections import Counter
from dataclasses import dataclass
import numpy as np
from rapidfuzz import fuzz
from rapidfuzz.distance import Levenshtein
from .book import Book, normalized


def speech_windows(size,length):
    # Include an end-aligned window. An ad followed by a short story fragment
    # often falls between the regular stride positions at the end of a clip.
    return sorted(set(range(0,size,length//2))|{max(0,size-length),max(0,size-24)})


@dataclass
class Word:
    character: str
    start: int
    end: int


def timed_characters(result: dict, start_ms: int, duration_ms: int) -> list[Word]:
    """Accept documented millisecond pairs or legacy [token, seconds, seconds].
    Never invent timings when an ASR backend returns text without alignment."""
    spans = result.get("timestamp") or []
    words = result.get("words") or []
    if spans and len(spans[0]) == 3 and isinstance(spans[0][0], str):
        words = [x[0] for x in spans]
        spans = [[round(x[1] * 1000), round(x[2] * 1000)] for x in spans]
    if not spans or len(words) != len(spans):
        raise ValueError("识别模型未返回可核对的逐字时间戳，已停止以避免生成错误对应")
    output: list[Word] = []
    previous = 0
    for token, span in zip(words, spans):
        if token.startswith("<|"):
            continue
        text = normalized(token)
        if not text:
            continue
        left, right = round(span[0]), round(span[1])
        if not 0 <= left <= right <= duration_ms + 120 or left < previous:
            raise ValueError("识别时间戳超出音频范围或发生倒序")
        # Chinese tokens are individual characters. Multi-letter words retain
        # a bounded subword interval; the final sentence includes both endpoints.
        for i, c in enumerate(text):
            output.append(Word(c, start_ms + left + (right-left)*i//len(text),
                               start_ms + left + (right-left)*(i+1)//len(text)))
        previous = left
    return output


class Matcher:
    K = 7

    def __init__(self, book: Book):
        self.book = book
        self.starts = [s.start for s in book.sentences]
        codes = np.frombuffer(book.text.encode("utf-32-le"), dtype="<u4").astype(np.uint64)
        length = max(0, len(codes) - self.K + 1)
        hashes = np.zeros(length, dtype=np.uint64)
        with np.errstate(over="ignore"):
            for i in range(self.K):
                hashes = hashes * np.uint64(257) + codes[i:i+length]
        order = hashes.argsort(kind="stable")
        self.hashes = hashes[order]
        self.positions = order.astype(np.uint32)

    def candidates(self, query: str) -> list[int]:
        votes: Counter[int] = Counter()
        for i in range(0, len(query)-self.K+1, self.K):
            key = 0
            for char in query[i:i+self.K]:
                key = (key*257+ord(char)) & ((1 << 64)-1)
            left = int(self.hashes.searchsorted(np.uint64(key), "left"))
            right = int(self.hashes.searchsorted(np.uint64(key), "right"))
            if right-left > 40:
                continue
            for hit in self.positions[left:right]:
                expected = int(hit)-i
                # Neighboring buckets are coalesced in the candidate pass below.
                votes[expected//12*12] += 1
        selected: list[int] = []
        for position, _ in votes.most_common(32):
            if all(abs(position-other) > 24 for other in selected):
                selected.append(position)
            if len(selected) == 10:
                break
        return selected

    def match(self, words: list[Word], previous: tuple[int, int] | None = None):
        query = "".join(w.character for w in words)
        if not 24 <= len(query) <= 800:
            return None
        first_exact=self.book.text.find(query)
        if previous is None and first_exact>=0 and self.book.text.find(query,first_exact+1)>=0:
            return None
        candidates = []
        for expected in self.candidates(query):
            start = max(0, expected-20)
            stop = min(len(self.book.text), expected+len(query)+40)
            aligned = fuzz.partial_ratio_alignment(query, self.book.text[start:stop], score_cutoff=87)
            if aligned is None or aligned.src_start != 0 or aligned.src_end != len(query):
                continue
            n0, n1 = start+aligned.dest_start, start+aligned.dest_end
            if previous:
                old_text, old_time = previous
                gap = max(0, words[0].start-old_time)/1000
                if n0 < old_text-180 or n0 > old_text+max(1800, gap*18):
                    continue
            if not any(abs(n0-old[1]) < 24 for old in candidates):
                candidates.append((aligned.score, n0, n1))
        candidates.sort(reverse=True)
        if not candidates:
            return None
        score, start, end = candidates[0]
        if len(candidates)>1 and candidates[1][0] > score-7:
            return None
        pairs = []
        for op in Levenshtein.opcodes(query, self.book.text[start:end]):
            if op.tag == "equal":
                pairs.extend((start+j, words[i]) for i, j in zip(range(op.src_start,op.src_end),range(op.dest_start,op.dest_end)))
        return score/100, pairs

    def match_parts(self, words: list[Word], previous=None):
        """An inserted announcement must not discard the story on both sides.
        Only independently unique, sufficiently long speech windows are accepted.
        Unmatched words never receive book positions.
        """
        whole = self.match(words, previous)
        if whole is not None:
            return whole
        found = {}
        scores = []
        for length in (64, 32):
            for start in speech_windows(len(words),length):
                clip = words[start:start+length]
                if len(clip) < 24:
                    continue
                match = self.match(clip, previous)
                if match is None:
                    continue
                score, pairs = match
                scores.append(score)
                for n, word in pairs:
                    if n not in found or score > found[n][0]:found[n] = (score, word)
        if not found:
            return None
        pairs = [(n, value[1]) for n, value in sorted(found.items())]
        # Overlapping windows may disagree; do not stitch a reversed timeline.
        if any(b[1].start < a[1].start for a,b in zip(pairs,pairs[1:])):
            return None
        return min(scores), pairs

    def local_pairs(self, words: list[Word], left: int, right: int):
        """Second-pass context is bounded by already confirmed novel positions."""
        text = self.book.text[left:right]
        found = {}
        for length in (64, 32):
            for start in speech_windows(len(words),length):
                clip = words[start:start+length]
                query = ''.join(w.character for w in clip)
                if len(query) < 24:continue
                hit = fuzz.partial_ratio_alignment(query,text,score_cutoff=92)
                if hit is None or hit.src_start or hit.src_end!=len(query):continue
                target=text[hit.dest_start:hit.dest_end]
                # Repeated phrases inside this bracket need stronger context.
                if text.find(target)!=text.rfind(target):continue
                for op in Levenshtein.opcodes(query,target):
                    if op.tag=='equal':
                        for i,j in zip(range(op.src_start,op.src_end),range(op.dest_start,op.dest_end)):
                            found[left+hit.dest_start+j]=clip[i]
        pairs=sorted(found.items())
        if any(b[1].start<a[1].start for a,b in zip(pairs,pairs[1:])):return []
        return pairs

    def sentence_rows(self, anchors: list[tuple[int, int, int, float]], audio: str):
        """Use the start and end of recognized speech, not character/time ratios.
        Anchors can come from overlapping neighboring chunks in the same track."""
        if not anchors:
            return []
        by_position = {n:(start,end,confidence) for n,start,end,confidence in anchors}
        low = max(0,bisect_right(self.starts,anchors[0][0])-1)
        high = bisect_right(self.starts,anchors[-1][0])
        rows = []
        for sentence in self.book.sentences[low:high]:
            if sentence.start not in by_position or sentence.end-1 not in by_position:
                continue
            coverage = sum(n in by_position for n in range(sentence.start,sentence.end))/max(1,sentence.end-sentence.start)
            if coverage < .88:
                continue
            begin, _, left_score = by_position[sentence.start]
            _, end, right_score = by_position[sentence.end-1]
            if end <= begin or end-begin > 120000:
                continue
            times = [by_position[n][0] for n in range(sentence.start,sentence.end) if n in by_position]
            if any(b<a for a,b in zip(times,times[1:])):
                continue
            spans=[by_position[n] for n in range(sentence.start,sentence.end) if n in by_position]
            if any(b[0]-a[1]>2500 for a,b in zip(spans,spans[1:])):
                continue # Do not stretch a novel clause across inserted speech.
            if end-begin>max(6000,(sentence.end-sentence.start)*1800+3000):
                continue
            confidence=min(left_score,right_score)*coverage
            if confidence<.85:
                continue
            rows.append(dict(n0=sentence.start,n1=sentence.end,audio=audio,t0=begin,t1=end,
                             confidence=round(confidence,4),text=sentence.text))
        return rows
