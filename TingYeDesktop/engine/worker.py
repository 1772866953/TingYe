"""JSON-lines worker. Models run here, never in Electron's UI process."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import sqlite3
import sys
import time
import traceback

# Embedded Python's isolated _pth omits the invoked script's directory.
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8",errors="replace")


def emit(**event):
    print(json.dumps(event,ensure_ascii=False,separators=(",",":")),file=sys.__stdout__,flush=True)


def environment():
    required=("torch","funasr","bs4","rapidfuzz","mutagen")
    if any(importlib.util.find_spec(x) is None for x in required):
        return dict(ready=False,componentsReady=False,device="",message="尚未安装完整识别组件")
    import torch
    gpu=torch.cuda.is_available()
    return dict(ready=gpu,componentsReady=True,device=torch.cuda.get_device_name(0) if gpu else "CPU",
                message="GPU 识别环境已就绪" if gpu else "未检测到可用 CUDA GPU，请检查 NVIDIA 驱动")


def load_model(root: str, memory_gib: float = 6):
    os.environ["MODELSCOPE_CACHE"]=root
    os.environ["HF_HUB_DISABLE_TELEMETRY"]="1"
    os.environ["TOKENIZERS_PARALLELISM"]="false"
    import torch
    from funasr import AutoModel
    from tingye_align.tokenizer import enable_unicode_model_paths
    enable_unicode_model_paths()
    if not torch.cuda.is_available():
        raise ValueError("未检测到 CUDA GPU，请先准备识别组件或检查 NVIDIA 驱动")
    torch.set_num_threads(min(4,os.cpu_count() or 2))
    torch.set_float32_matmul_precision('high')
    cached=Path(root)/"models/iic--SenseVoiceSmall/snapshots/master"
    source=str(cached) if (cached/"model.pt").is_file() and (cached/"config.yaml").is_file() else "iic/SenseVoiceSmall"
    return AutoModel(model=source,device="cuda:0",hub="ms",trust_remote_code=False,bf16=memory_gib<2 and torch.cuda.is_bf16_supported(),
                     disable_update=True,disable_pbar=True)


def run(request: dict):
    from tingye_align.book import parse
    from tingye_align.audio import inspect,chunks,Prefetch,HOP_MS,WINDOW_MS,RATE
    from tingye_align.gpu import GpuRecognizer
    from tingye_align.alignment import Matcher,timed_characters
    from tingye_align.format import export
    from tingye_align import review
    novel=Path(request["novel"])
    audio=request["audio"]
    if not audio or len(audio)>5000:
        raise ValueError("请选择 1 至 5000 个音频文件")
    emit(status="preparing",message="正在读取小说和音频信息")
    book=parse(novel)
    metadata_file=Path(request['cache_root'])/'audio-metadata.json'
    metadata_file.parent.mkdir(parents=True,exist_ok=True)
    try:metadata=json.loads(metadata_file.read_text(encoding='utf-8'))
    except (OSError,ValueError):metadata={}
    # Existing exports already contain verified durations; reuse them by content
    # fingerprint so the first upgrade does not re-scan hundreds of hours of M4A.
    import zipfile
    known={}
    for exported in metadata_file.parent.glob('*/result.tysync'):
        try:
            with zipfile.ZipFile(exported) as archive:
                for track in json.loads(archive.read('manifest.json'))['tracks']:known[track['id']]=track
        except (OSError,ValueError,KeyError,zipfile.BadZipFile):pass
    from tingye_align.format import fingerprint
    tracks=[]
    for path in audio:
        if Path(request["stop_file"]).exists():
            emit(status="paused",message="已暂停，可继续处理");return
        file=Path(path);stat=file.stat();identity=str(file.resolve())
        cached=metadata.get(identity,{})
        signature=fingerprint(file)
        if cached.get('size')==stat.st_size and cached.get('modified')==stat.st_mtime_ns and cached.get('track',{}).get('id')==signature:
            track=cached['track']
        elif signature in known and known[signature]['size']==stat.st_size:
            track=dict(known[signature],name=file.name,path=identity)
        else:
            track=inspect(path,request['ffmpeg'])
            metadata[identity]=dict(size=stat.st_size,modified=stat.st_mtime_ns,track=track)
        tracks.append(track)
    temporary=metadata_file.with_suffix('.tmp')
    temporary.write_text(json.dumps(metadata,ensure_ascii=False),encoding='utf-8');os.replace(temporary,metadata_file)
    if len({t["id"] for t in tracks})!=len(tracks):
        raise ValueError("音频列表包含重复文件，请移除后再处理")
    key=hashlib.sha256(json.dumps(["ctc-sentence-v1",book.digest,[t["id"] for t in tracks]]).encode()).hexdigest()[:32]
    job=Path(request["cache_root"])/key
    job.mkdir(parents=True,exist_ok=True)
    database=sqlite3.connect(job/"alignment.sqlite3")
    database.execute("PRAGMA journal_mode=WAL")
    database.execute("CREATE TABLE IF NOT EXISTS chunks (audio TEXT,start INTEGER,PRIMARY KEY(audio,start))")
    database.execute("CREATE TABLE IF NOT EXISTS anchors (audio TEXT,n INTEGER,t0 INTEGER,t1 INTEGER,score REAL,PRIMARY KEY(audio,n))")
    database.execute("CREATE TABLE IF NOT EXISTS sentences (n0 INTEGER PRIMARY KEY,n1 INTEGER,audio TEXT,t0 INTEGER,t1 INTEGER,confidence REAL,text TEXT)")
    database.execute("CREATE INDEX IF NOT EXISTS sentence_audio_position ON sentences(audio,n0)")
    total=sum(math.ceil(t["duration_ms"]/HOP_MS) for t in tracks)
    # A final overlap-only chunk is unnecessary after the previous chunk reaches EOF.
    starts={t["id"]:list(range(0,max(1,t["duration_ms"]-WINDOW_MS+HOP_MS),HOP_MS)) for t in tracks}
    total=sum(len(v) for v in starts.values())
    completed_chunks=set(database.execute("SELECT audio,start FROM chunks"))
    completed=len(completed_chunks)
    seconds=sum(t["duration_ms"] for t in tracks)/1000
    processed=sum(min(HOP_MS,max(0,t["duration_ms"]-start))/1000 for t in tracks for start in starts[t["id"]]
                  if (t["id"],start) in completed_chunks)
    result=job/"result.tysync"
    latest=[]
    phase='recognition'
    verified_done=0;verified_total=0;verified_added=0
    def progress():
        return .9*min(1.,completed/max(1,total)) if phase=='recognition' else .9+.1*verified_done/max(1,verified_total)

    def rows():
        keys=("n0","n1","audio","t0","t1","confidence","text")
        for row in database.execute("SELECT n0,n1,audio,t0,t1,confidence,text FROM sentences ORDER BY n0"):
            yield dict(zip(keys,row))

    def finish(complete: bool):
        if complete and database.execute("SELECT COUNT(*) FROM sentences").fetchone()[0]==0:
            raise ValueError("音频已处理，但没有找到可确认的完整句子。请确认小说与有声书版本一致")
        manifest=export(result,book,tracks,rows(),complete,len(book.sentences),dict(
            revision=review.REVISION,reference_units=len(book.sentences),checked_units=database.execute('SELECT COUNT(*) FROM sentences').fetchone()[0],windows_done=verified_done,
            windows_total=verified_total,added_units=verified_added,complete=complete))
        emit(status="completed" if complete else "paused",message="逐句对应已建立" if complete else "已暂停，可继续处理",
             progress=1. if complete else progress(),completed=completed,total=total,phase=phase,
             processedSeconds=seconds if complete else min(seconds,processed),totalSeconds=seconds,
             matched=manifest["sentence_count"],coverage=manifest["coverage"],result=str(result),
             preview=latest,tracks=[dict(id=t["id"],path=t["path"]) for t in tracks])

    emit(status="preparing",message="正在建立原文检索索引",completed=completed,total=total,matched=database.execute("SELECT COUNT(*) FROM sentences").fetchone()[0],
         totalSeconds=seconds,processedSeconds=min(seconds,processed),progress=progress(),phase=phase,
         tracks=[dict(id=t["id"],path=t["path"]) for t in tracks])
    matcher=Matcher(book)
    review.rebuild(database,matcher,tracks,lambda done,count:emit(status='preparing',message=f'正在复查缓存并生成标点短句 {done}/{count}'))
    if Path(request['stop_file']).exists():finish(False);database.close();return
    emit(status="preparing",message="正在加载 GPU 识别模型")
    with contextlib.redirect_stdout(sys.stderr):
        model=load_model(request["models_root"],float(request.get('gpu_memory_gib',6)))
    import torch
    recognizer=GpuRecognizer(model,float(request.get('gpu_memory_gib',6)))
    began=time.perf_counter();initial_processed=processed
    emit(device=torch.cuda.get_device_name(0),batchSize=recognizer.batch_size)
    try:
        for track in tracks:
            if Path(request["stop_file"]).exists():finish(False);return
            done={r[0] for r in database.execute("SELECT start FROM chunks WHERE audio=?",(track["id"],))}
            remaining=[s for s in starts[track["id"]] if s not in done]
            if not remaining:
                continue
            previous=None
            saved=database.execute("SELECT n,t0 FROM anchors WHERE audio=? ORDER BY n DESC LIMIT 1",(track["id"],)).fetchone()
            if saved:
                previous=saved
            with Prefetch(chunks(request["ffmpeg"],track,remaining[0]),recognizer.batch_size,done,lambda:Path(request["stop_file"]).exists()) as batches:
                for batch in batches:
                    if Path(request["stop_file"]).exists():finish(False);return
                    emit(status="running",message="阶段 1/2：识别并对齐正文",track=track["name"],progress=progress(),completed=completed,total=total,phase=phase)
                    with contextlib.redirect_stdout(sys.stderr):
                        responses=recognizer([samples for _,samples in batch])
                    if len(responses)!=len(batch):raise ValueError('批量识别结果数量不一致')
                    for (start,samples),response in zip(batch,responses):
                        response=[response]
                        if response and (response[0].get("timestamp") or response[0].get("text","").replace("<|nospeech|>","").strip()):
                            try:
                                words=timed_characters(response[0],start,round(len(samples)/RATE*1000))
                            except ValueError:
                                # No timestamps is an integration error, never a silent text/time estimate.
                                if response[0].get("words") or response[0].get("timestamp"):
                                    raise
                                text=response[0].get("text","")
                                if any("\u4e00"<=c<="\u9fff" for c in text):
                                    raise
                                words=[]
                            matched=matcher.match_parts(words,previous)
                            if matched:
                                score,pairs=matched
                                with database:
                                    database.executemany("INSERT INTO anchors VALUES (?,?,?,?,?) ON CONFLICT(audio,n) DO UPDATE SET t0=excluded.t0,t1=excluded.t1,score=excluded.score WHERE excluded.score>anchors.score",
                                        [(track["id"],n,w.start,w.end,score) for n,w in pairs])
                                    if pairs:
                                        previous=(pairs[-1][0],pairs[-1][1].start)
                                        low=max(0,pairs[0][0]-1000)
                                        high=pairs[-1][0]+1000
                                        anchors=database.execute("SELECT n,t0,t1,score FROM anchors WHERE audio=? AND n BETWEEN ? AND ? ORDER BY n",(track["id"],low,high)).fetchall()
                                        sentences=matcher.sentence_rows(anchors,track["id"])
                                        for row in sentences:
                                            # Reject reverse audio order in the exported sentence timeline.
                                            before=database.execute("SELECT t0 FROM sentences WHERE audio=? AND n0<? ORDER BY n0 DESC LIMIT 1",(row["audio"],row["n0"])).fetchone()
                                            after=database.execute("SELECT t0 FROM sentences WHERE audio=? AND n0>? ORDER BY n0 LIMIT 1",(row["audio"],row["n0"])).fetchone()
                                            if (before and before[0]>row["t0"]) or (after and after[0]<row["t0"]):
                                                continue
                                            database.execute("INSERT INTO sentences VALUES (?,?,?,?,?,?,?) ON CONFLICT(n0) DO UPDATE SET n1=excluded.n1,audio=excluded.audio,t0=excluded.t0,t1=excluded.t1,confidence=excluded.confidence,text=excluded.text WHERE excluded.confidence>sentences.confidence",
                                                tuple(row[k] for k in ("n0","n1","audio","t0","t1","confidence","text")))
                                        if sentences:
                                            latest=sentences[-5:]
                        with database:
                            database.execute("INSERT OR IGNORE INTO chunks VALUES (?,?)",(track["id"],start))
                        completed+=1
                        processed+=min(HOP_MS,max(0,track["duration_ms"]-start))/1000
                        count=database.execute("SELECT COUNT(*) FROM sentences").fetchone()[0]
                        emit(status="running",message="正在识别并对齐句子",track=track["name"],
                             progress=progress(),completed=completed,total=total,phase=phase,
                             matched=count,processedSeconds=min(seconds,processed),totalSeconds=seconds,preview=latest,batchSize=recognizer.batch_size,speed=(processed-initial_processed)/max(.001,time.perf_counter()-began),vramGiB=torch.cuda.max_memory_reserved()/2**30)
        if Path(request['stop_file']).exists():finish(False);return
        if processed>initial_processed:
            review.rebuild(database,matcher,tracks,lambda done,count:emit(status='running',message=f'阶段 2/2：逐条复查已有短句 {done}/{count}'),force=True)
        phase='verification'
        def verification_progress(done,count,added):
            nonlocal verified_done,verified_total,verified_added
            verified_done,verified_total,verified_added=done,count,added
            matched=database.execute('SELECT COUNT(*) FROM sentences').fetchone()[0]
            emit(status='running',phase=phase,message=f'阶段 2/2：音频回查与短句补齐 {done}/{count}' if count else '阶段 2/2：短句复查完成',
                track='',speed=None,progress=progress(),matched=matched,verificationDone=done,verificationTotal=count,verificationAdded=added)
        complete=review.run_review(database,matcher,tracks,recognizer,request['ffmpeg'],lambda:Path(request['stop_file']).exists(),verification_progress)
        finish(complete)
    finally:
        database.close()


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--probe",action="store_true")
    parser.add_argument("--prepare",metavar="MODELS_ROOT")
    options=parser.parse_args()
    try:
        if options.probe:
            emit(**environment())
        elif options.prepare:
            with contextlib.redirect_stdout(sys.stderr):
                load_model(options.prepare)
            emit(**environment())
        else:
            run(json.loads(sys.stdin.readline()))
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        emit(status="error",message="处理未完成",error=str(error))
        sys.exit(1)
