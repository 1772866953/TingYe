"""Targeted MuMu-only checks. Requires the disposable MorningBook three-chapter fixture."""
from pathlib import Path
from PIL import Image, ImageDraw
import io, json, re, runpy, subprocess, sys, time

ROOT = Path(__file__).resolve().parents[2]
q = runpy.run_path(str(Path(__file__).with_name('check-listening-features.py')))
sh, nodes, tap, session = (q[k] for k in ('sh', 'nodes', 'tap', 'session'))
ADB = q['ADB']
OUT = ROOT / 'qa/results/caption-window'
OUT.mkdir(parents=True, exist_ok=True)

def label(n): return n.get('text') or n.get('content-desc') or ''
def bounds(n): return list(map(int, re.findall(r'\d+', n.get('bounds'))))
def center(n):
    b = bounds(n); return ((b[0]+b[2])//2, (b[1]+b[3])//2)
def shot(name):
    data = subprocess.check_output(ADB + ['exec-out', 'screencap', '-p'])
    (OUT / (name+'.png')).write_bytes(data)
    return Image.open(io.BytesIO(data)).convert('RGB')
def first_chapter():
    sh('input', 'keyevent', 127)
    for _ in range(2):
        if 'Chapter 1' in session()[2]: break
        tap('上一章')
    assert 'MorningBook, Chapter 1' in session()[2], session()
    sh('input', 'keyevent', 127)
    time.sleep(.5)
def open_player():
    tap('返回书架'); tap('MorningBook'); time.sleep(1.0)
    sh('input', 'keyevent', 127); time.sleep(.25)
def set_window(seconds):
    if any(label(n)=='收起播放器' for n in nodes()): tap('收起播放器')
    tap('设置'); tap('播放控制')
    sh('input', 'swipe', 540, 1650, 540, 650, 350); time.sleep(.3)
    listing = nodes()
    title = next(n for n in listing if label(n).startswith('进度恢复提示'))
    y = bounds(title)[3]
    slider = min((n for n in listing if n.get('class')=='android.widget.SeekBar' and bounds(n)[1]>=y), key=lambda n: bounds(n)[1])
    b = bounds(slider)
    x = b[0]+45 if seconds==1 else b[2]-45
    sh('input', 'tap', x, (b[1]+b[3])//2); time.sleep(.3)
    assert any(label(n)==f'进度恢复提示 {seconds}s' for n in nodes())
    shot(f'setting-{seconds}s')
    open_player()

if '--record' in sys.argv:
    first_chapter()
    controls = {label(n): center(n) for n in nodes() if label(n) in ('上一章','下一章')}
    sys.path.insert(0, str(ROOT/'tools/video-tools'))
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    remote = '/sdcard/tingye-caption.mp4'
    recorder = subprocess.Popen(ADB+['shell','screenrecord','--size','720x1280','--bit-rate','8000000','--time-limit','8',remote], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    start = time.monotonic(); events = []; time.sleep(.4)
    for name, wait in [('下一章',1.0),('下一章',1.0),('上一章',1.0),('上一章',1.0),('下一章',.13),('下一章',.13),('上一章',.13),('上一章',1.0)]:
        events.append({'action':name,'time':round(time.monotonic()-start,3)})
        sh('input','tap',*controls[name]); time.sleep(wait)
    recorder.communicate(timeout=15)
    video = OUT/'chapter-caption.mp4'
    subprocess.run(ADB+['pull',remote,str(video)],check=True,capture_output=True)
    reader = imageio_ffmpeg.read_frames(str(video)); metadata = next(reader); reader.close()
    subprocess.run([ffmpeg,'-y','-i',str(video),'-filter_complex','crop=720:160:0:585,split[a][b];[a]palettegen[p];[b][p]paletteuse','-loop','0',str(OUT/'chapter-caption.gif')],check=True,capture_output=True)
    # Inspect actual captured frames through the first forward and first backward transition.
    for name, event in [('next',events[0]),('previous',events[2]),('rapid',events[4])]:
        reader = imageio_ffmpeg.read_frames(str(video),pix_fmt='rgb24', input_params=['-ss',str(max(0,event['time']-.08))],output_params=['-t','0.8','-vf','crop=720:200:0:580,scale=360:100'])
        next(reader)
        frames = [Image.frombytes('RGB',(360,100),data) for data in reader]
        sheet = Image.new('RGB',(360*6,124*((len(frames)+5)//6)),'white'); draw=ImageDraw.Draw(sheet)
        for i,frame in enumerate(frames):
            x=i%6*360; y=i//6*124; sheet.paste(frame,(x,y+24))
            draw.text((x+5,y+5),f'{i/metadata["fps"]:.3f}s',fill='black')
        sheet.save(OUT/f'{name}-frames.png')
    result={'events':events,'capturedFps':metadata['fps'],'duration':metadata['duration'],'finalChapter':session()[2]}
    assert 'Chapter 1' in result['finalChapter'], result
    (OUT/'recording.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
else:
    checks=[]
    for seconds in (1,12):
        set_window(seconds)
        if seconds==1: first_chapter()
        # The 1s pass leaves Chapter 2 selected. Do not prepare Chapter 1 here:
        # that navigation itself would create a live 12s offer in the baseline.
        time.sleep(1.7)
        baseline=shot(f'{seconds}s-before').getpixel((80,300))
        controls={label(n):center(n) for n in nodes() if label(n)=='下一章'}
        start=time.monotonic(); sh('input','tap',*controls['下一章']); time.sleep(.3)
        during=shot(f'{seconds}s-visible').getpixel((80,300))
        difference=lambda pixel:sum(abs(a-b) for a,b in zip(baseline,pixel))
        assert difference(during)>20, (seconds,baseline,during)
        if seconds==12:
            time.sleep(1.0)
            assert difference(shot('12s-after-1s').getpixel((80,300)))>20
        time.sleep(max(0,start+seconds+.35-time.monotonic()))
        after=shot(f'{seconds}s-expired').getpixel((80,300))
        assert difference(after)<5, (seconds,baseline,after)
        assert not any(label(n)=='返回刚才位置' for n in nodes())
        checks.append({'windowSeconds':seconds,'visibleAfter300ms':True,'goneAfterDeadline':True})
    set_window(1)
    tap('收起播放器'); sh('am','force-stop','com.tingye.app'); sh('am','start','-W','-n','com.tingye.app/.MainActivity')
    tap('设置'); tap('播放控制'); sh('input','swipe',540,1650,540,650,350)
    assert any(label(n)=='进度恢复提示 1s' for n in nodes())
    open_player()
    set_window(12)
    (OUT/'window-checks.json').write_text(json.dumps({'checks':checks,'restartPreservesSetting':True,'restartValueVerified':1},indent=2),encoding='utf-8')
    print(json.dumps(checks,indent=2))
