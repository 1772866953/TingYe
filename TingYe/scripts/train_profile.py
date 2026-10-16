from pathlib import Path
import subprocess,time,sys,re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]; out=root/'qa/results/profiles'; out.mkdir(parents=True,exist_ok=True); adb=[r'C:\Software\MuMu Player 12\shell\adb.exe','-s','127.0.0.1:16384']
def sh(*a):return subprocess.check_output(adb+['shell',*map(str,a)])
def tap(x,y,wait=.8):sh('input','tap',x,y);time.sleep(wait)
def back(wait=.8):sh('input','keyevent',4);time.sleep(wait)
def label(text):
    sh('uiautomator','dump','/sdcard/profile-ui.xml');nodes=ET.fromstring(sh('cat','/sdcard/profile-ui.xml'))
    n=next(n for n in nodes.iter('node') if n.get('text')==text or n.get('content-desc')==text)
    a=list(map(int,re.findall(r'\d+',n.get('bounds'))));tap((a[0]+a[2])//2,(a[1]+a[3])//2)
sh('am','force-stop','com.tingye.app');sh('su','0','cmd','package','compile','--reset','com.tingye.app')
sh('am','start','-W','-n','com.tingye.app/.MainActivity');time.sleep(1)
for i in range(2):
    tap(480,1220);tap(270,1824);back();tap(590,1824);back();tap(900,1824);back()
    tap(954,174);label('书籍设置')
    for _ in range(3):sh('input','swipe',500,1680,500,650,250)
    back();back();tap(810,174);label('触觉反馈');back()
    tap(395,840);back()
    print('training pass',i+1,flush=True)
# Request ART to flush the current process's sampled methods before dumping.
sh('su','0','cmd','package','dump-profiles','com.tingye.app')
raw=sh('su','0','cat','/data/misc/profman/com.tingye.app-primary.prof.txt')
(out/'critical-path-profile.txt').write_bytes(raw)
print(raw[:500].decode(errors='replace'))
