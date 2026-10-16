from pathlib import Path
import sys, json, subprocess, re
import numpy as np
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/video-tools'))
import imageio_ffmpeg
OUT=ROOT/'qa/results/animations'
ffmpeg=imageio_ffmpeg.get_ffmpeg_exe()
log=subprocess.run([ffmpeg,'-i',str(OUT/'reopen.mp4'),'-vf','showinfo','-vsync','0','-f','null','-'],capture_output=True).stderr.decode()
times=np.array([float(t) for t in re.findall(r'\bn:\s*\d+\s+pts:\s*\d+\s+pts_time:([\d.]+)',log)])
reader=imageio_ffmpeg.read_frames(str(OUT/'reopen.mp4'),pix_fmt='rgb24',output_params=['-vf','scale=108:192','-vsync','0'])
meta=next(reader); frames=[]; areas=[]
for raw in reader:
    frame=np.frombuffer(raw,dtype=np.uint8).reshape(192,108,3)
    match=np.all(abs(frame.astype(int)-np.array([143,124,80]))<14,axis=2)
    areas.append(int(match.sum())); frames.append(Image.fromarray(frame))
assert len(times)==len(frames),(len(times),len(frames))
areas=np.array(areas)
events=json.loads((OUT/'reopen-events.json').read_text())['events']
results=[]; contact=Image.new('RGB',(108*9,214*len(events)),'white');draw=ImageDraw.Draw(contact)
for n,event in enumerate(events):
    t=event['open']+.5
    indexes=[int(np.argmin(abs(times-(t+offset)))) for offset in [0,.035,.07,.105,.14,.2,.3,.5,.7]]
    vals=areas[indexes].tolist()
    # A direct full-screen jump lacks these intervening cover sizes.
    window=(times>=t-.04)&(times<t+.55)
    intermediate=int(((areas[window]>550)&(areas[window]<1900)).sum())
    results.append({'cycle':n+1,'delay':event['closeDelay'],'coverAreas':vals,'intermediateFrames':intermediate})
    for col,i in enumerate(indexes):
        x=col*108;y=n*214
        contact.paste(frames[i],(x,y+22));draw.text((x+3,y+3),f'{n+1}: {times[i]-t:.2f}',fill='black')
contact.save(OUT/'reopen-all-cycles.png')
assert all(r['intermediateFrames']>=3 for r in results),[r for r in results if r['intermediateFrames']<3]
gaps=np.diff(times)
moving=(areas[:-1]>550)&(areas[:-1]<1900)&(areas[1:]>550)&(areas[1:]<1900)&(abs(np.diff(areas))>2)
motion_gaps=gaps[moving]
report={'cycles':len(results),'allHaveIntermediateFrames':True,'encodedFrameRate':meta['fps'],
    'medianFrameIntervalMs':float(np.median(gaps)*1000),'p95FrameIntervalMs':float(np.percentile(gaps,95)*1000),
    'largestFrameIntervalMs':float(gaps.max()*1000),
    'movingCoverP95IntervalMs':float(np.percentile(motion_gaps,95)*1000),
    'movingCoverMaxIntervalMs':float(motion_gaps.max()*1000),'results':results}
(OUT/'reopen-analysis.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='results'},indent=2))
