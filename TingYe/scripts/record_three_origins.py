from pathlib import Path
import subprocess,time,sys,json
root=Path(__file__).resolve().parents[2];sys.path.insert(0,str(root/'tools/video-tools'));import imageio_ffmpeg
adb=[r'C:\Software\MuMu Player 12\shell\adb.exe','-s','127.0.0.1:16384']
def sh(*a):return subprocess.check_output(adb+['shell',*map(str,a)])
sh('am','force-stop','com.tingye.app');sh('am','start','-W','-n','com.tingye.app/.MainActivity');time.sleep(.4)
remote='/sdcard/final-three-origins.mp4';out=root/'qa/results/animations';out.mkdir(parents=True,exist_ok=True)
r=subprocess.Popen(adb+['shell','screenrecord','--size','720x1280','--bit-rate','8000000','--time-limit','13',remote],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
start=time.monotonic();events=[]
def tap(label,x,y,wait):
    events.append([label,round(time.monotonic()-start,3)]);sh('input','tap',x,y);time.sleep(wait)
time.sleep(.6)
tap('continue open',500,570,1.4);tap('pause playback',540,1578,.3);tap('continue return',126,174,1)
tap('shelf open',480,1220,1);tap('shelf return',126,174,1)
tap('mini open',500,1760,1);tap('mini return',126,174,1)
r.communicate(timeout=20)
subprocess.run(adb+['pull',remote,str(out/'final-three-origins.mp4')],check=True,capture_output=True)
(out/'final-three-origins-events.json').write_text(json.dumps(events,indent=2))
f=imageio_ffmpeg.get_ffmpeg_exe();subprocess.run([f,'-y','-i',str(out/'final-three-origins.mp4'),'-filter_complex','fps=50,scale=360:640:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse','-loop','0',str(out/'final-three-origins.gif')],check=True,capture_output=True)
print(events)
