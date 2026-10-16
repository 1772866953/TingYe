"""Record rapid mini-player reopen timing on the development emulator."""
from pathlib import Path
import subprocess, time, json, sys
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools/video-tools'))
import imageio_ffmpeg
OUT = ROOT/'qa/results/animations'
OUT.mkdir(parents=True, exist_ok=True)
ADB = [r'C:/Software/MuMu Player 12/shell/adb.exe', '-s', '127.0.0.1:16384']
def sh(command): return subprocess.check_output(ADB+['shell', command])
sh('input tap 126 174'); time.sleep(1.2)
remote = '/sdcard/tingye-reopen.mp4'
record = subprocess.Popen(ADB+['shell', 'screenrecord', '--size', '720x1280', '--bit-rate', '10000000', '--time-limit', '150', remote], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
time.sleep(.5)
start = time.monotonic(); events = []
intervals = [.0,.08,.16,.24,.32,.4,.48,.56,.64,.72,.8,1.0]*5
for index, delay in enumerate(intervals):
    events.append({'cycle':index+1,'open':time.monotonic()-start,'closeDelay':delay})
    sh('input tap 500 1760')
    time.sleep(.85)
    events[-1]['close'] = time.monotonic()-start
    sh('input tap 126 174')
    time.sleep(delay)
    if index % 12 == 11: print(f'{index+1} cycles recorded', flush=True)
time.sleep(1)
sh('pkill -2 screenrecord')
record.communicate(timeout=10)
subprocess.run(ADB+['pull', remote, str(OUT/'reopen.mp4')], check=True, capture_output=True)
(OUT/'reopen-events.json').write_text(json.dumps({'recordStartOffset':.5,'events':events},indent=2))
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
subprocess.run([ffmpeg,'-y','-i',str(OUT/'reopen.mp4'),'-t','18','-filter_complex',
    'fps=50,scale=270:480:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse','-loop','0',str(OUT/'reopen.gif')],check=True,capture_output=True)
print('Complete: 60 cycles at 12 different close-to-reopen delays')
