from __future__ import annotations

import subprocess
import re
import threading
import queue
from pathlib import Path
import numpy as np
from mutagen import File as MediaFile
from .format import fingerprint

RATE = 16000
WINDOW_MS = 30000
HOP_MS = 27000
NO_WINDOW = 0x08000000


class Prefetch:
    """Bounded one-batch decode overlap. The producer owns/closes FFmpeg."""
    def __init__(self,stream,batch_size,skip,stop):
        self.stream=stream;self.batch_size=batch_size;self.skip=skip;self.stop=stop
        self.queue=queue.Queue(1);self.cancelled=threading.Event()
        self.thread=threading.Thread(target=self._produce,daemon=True)
    def _put(self,item):
        while not self.cancelled.is_set():
            try:self.queue.put(item,timeout=.1);return
            except queue.Full:pass
    def _produce(self):
        try:
            batch=[]
            for start,samples in self.stream:
                if self.cancelled.is_set() or self.stop():break
                if start in self.skip:continue
                batch.append((start,samples))
                if len(batch)>=self.batch_size:self._put(batch);batch=[]
            if batch:self._put(batch)
        except Exception as error:self._put(error)
        finally:
            try:self.stream.close()
            except Exception as error:self._put(error)
            finally:self._put(None)
    def __enter__(self):self.thread.start();return self
    def __iter__(self):return self
    def __next__(self):
        value=self.queue.get()
        if value is None:raise StopIteration
        if isinstance(value,Exception):raise value
        return value
    def __exit__(self,*args):
        self.cancelled.set();self.thread.join(timeout=10)
        if self.thread.is_alive():raise RuntimeError('音频解码进程未及时退出')


def inspect(path: str, ffmpeg: str | None = None) -> dict:
    source = Path(path)
    try:
        media = MediaFile(path)
        duration=media.info.length if media else 0
    except Exception:
        duration=0
    # Fragmented M4A commonly stores no mvhd duration. FFmpeg reads fragment
    # timestamps, whereas mutagen reports zero for otherwise valid recordings.
    if duration<=0 and ffmpeg:
        probe=subprocess.run([ffmpeg,'-hide_banner','-nostdin','-i',path,'-t','0','-f','null','-'],
                             capture_output=True,timeout=90,creationflags=NO_WINDOW)
        match=re.search(r'Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)',probe.stderr.decode('utf-8',errors='replace'))
        if probe.returncode==0 and match:
            duration=int(match[1])*3600+int(match[2])*60+float(match[3])
    if duration <= 0:
        raise ValueError(f"无法读取音频时长：{source.name}")
    return dict(id=fingerprint(source), name=source.name, size=source.stat().st_size,
                duration_ms=round(duration*1000), path=str(source.resolve()))


def chunks(ffmpeg: str, track: dict, start_ms: int = 0):
    command = [ffmpeg,"-hide_banner","-loglevel","error","-nostdin","-ss",f"{start_ms/1000:.3f}",
               "-i",track["path"],"-vn","-ac","1","-ar",str(RATE),"-f","f32le","pipe:1"]
    process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=NO_WINDOW)
    keep = (WINDOW_MS-HOP_MS)*RATE//1000*4
    buffer = b""
    position = start_ms
    reached_eof = False
    try:
        while True:
            needed = WINDOW_MS*RATE//1000*4-len(buffer)
            while needed > 0:
                part = process.stdout.read(needed)
                if not part:
                    reached_eof = True
                    break
                buffer += part
                needed -= len(part)
            buffer = buffer[:len(buffer)//4*4]
            if len(buffer) < RATE*4:
                break
            yield position, np.frombuffer(buffer,dtype="<f4").copy()
            if needed > 0 or position+WINDOW_MS >= track["duration_ms"]:
                break
            buffer = buffer[-keep:]
            position += HOP_MS
    finally:
        process.stdout.close()
        cancelled = not reached_eof and process.poll() is None
        if cancelled:
            process.terminate()
        process.wait(timeout=10)
        error=process.stderr.read()
        process.stderr.close()
        if not cancelled and process.returncode != 0:
            raise ValueError(f"音频解码失败：{error.decode('utf-8',errors='replace')[-250:]}")
