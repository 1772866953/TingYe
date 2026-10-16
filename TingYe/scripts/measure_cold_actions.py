from pathlib import Path
import subprocess,time,sys,json,re
root=Path(__file__).resolve().parents[2];adb=[r'C:\Software\MuMu Player 12\shell\adb.exe','-s','127.0.0.1:16384']
def sh(*a):return subprocess.check_output(adb+['shell',*map(str,a)])
tag=sys.argv[1];out=root/'qa/results/performance';out.mkdir(exist_ok=True)
sh('am','force-stop','com.tingye.app');sh('am','start','-W','-n','com.tingye.app/.MainActivity');time.sleep(.3)
results={}
for name,args in [('player-open',('input','tap',480,1220)),('player-close',('input','keyevent',4)),('settings-open',('input','tap',810,174)),('settings-close',('input','keyevent',4)),('second-player',('input','tap',480,1220)),('second-close',('input','keyevent',4))]:
    sh('dumpsys','gfxinfo','com.tingye.app','reset');sh(*args);time.sleep(.8)
    data=sh('dumpsys','gfxinfo','com.tingye.app','framestats').decode();(out/f'{tag}-{name}-frames.txt').write_text(data)
    header=None;durations=[];cpu=[];gaps=[];intended=[]
    for line in data.splitlines():
        if line.startswith('Flags,'):header=line.rstrip(',').split(',')
        elif header and re.match(r'^\d+,',line):
            row=dict(zip(header,map(int,line.rstrip(',').split(','))))
            if row['Flags']!=0:continue
            duration=(row['FrameCompleted']-row['IntendedVsync'])/1e6
            if 0<duration<1000:durations.append(duration)
            intended.append(row['IntendedVsync'])
            cpu.append((row['SyncQueued']-row['HandleInputStart'])/1e6)
    durations.sort();cpu.sort()
    results[name]={'frames':len(durations),'p50':round(durations[len(durations)//2],2) if durations else None,
        'p95':round(durations[min(len(durations)-1,int(len(durations)*.95))],2) if durations else None,'max':round(max(durations),2) if durations else None,
        'over16.7':sum(d>16.7 for d in durations),'over33.3':sum(d>33.3 for d in durations),'cpuMax':round(max(cpu),2) if cpu else None}
    time.sleep(.4)
(out/f'{tag}-summary.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
