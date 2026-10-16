"""Quick checks on the disposable MuMu API32 instance; open MorningBook's player first."""
from pathlib import Path
import runpy, subprocess, json, time, re, sys

ROOT = Path(__file__).resolve().parents[2]
q = runpy.run_path(str(Path(__file__).with_name('check-listening-features.py')))
sh, nodes, tap, session = (q[k] for k in ('sh', 'nodes', 'tap', 'session'))
out = ROOT / 'qa/results/leveling-undo'
out.mkdir(parents=True, exist_ok=True)
results = []

def label(n):
    return n.get('text') or n.get('content-desc')

def point(n):
    b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
    return (b[0]+b[2])//2, (b[1]+b[3])//2

def available(text):
    return any(label(n) == text for n in nodes())

def same_location(before, after):
    assert before[0] == after[0] == 2, (before, after)
    # dumpsys is a cached media-session snapshot; allow < one audio frame of timestamp rounding.
    assert before[2] == after[2] and abs(before[1] - after[1]) <= 20, (before, after)

def restore(before, description):
    # Navigation now starts playback; explicitly pause to verify paused undo separately.
    assert session()[0] in (2, 3), session()
    sh('input', 'keyevent', 127); time.sleep(.2)
    assert available('返回刚才位置'), description
    tap('返回刚才位置')
    same_location(before, session())
    assert not available('返回刚才位置')
    results.append({'check': description, 'originalPositionMs': before[1], 'result': 'pass'})

if '--speed-boundary' in sys.argv:
    sh('input', 'keyevent', 127)
    slider = next(n for n in nodes() if n.get('class') == 'android.widget.SeekBar')
    b = list(map(int, re.findall(r'\d+', slider.get('bounds'))))
    sh('input', 'tap', b[0]+(b[2]-b[0])*20//100, (b[1]+b[3])//2)
    time.sleep(1.7)
    speed = next(label(n) for n in nodes() if label(n) in ('1.0×', '1.5×'))
    if speed != '1.5×':
        tap(speed); tap('1.5×'); sh('input', 'keyevent', 4); time.sleep(.5)
    for attempt in range(2):
        sh('input', 'keyevent', 126); time.sleep(.6)
        assert 'speed=1.5' in sh('dumpsys', 'media_session')
        sh('input', 'keyevent', 127); time.sleep(.2)
        before = session()
        assert before[1] < 85_000, before
        tap('快进 30 秒')
        restore(before, f'30-second skip after 1.5x playback handles timestamp rounding ({attempt+1})')
    tap('1.5×'); tap('1.0×'); sh('input', 'keyevent', 4)
    (out/'speed-boundary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(0)

sh('input', 'keyevent', 127); time.sleep(.3)
before = session()
assert before and before[0] == 2 and 'MorningBook' in before[2], before
tap('快进 30 秒')
restore(before, 'paused forward seek restores exact position once')

before = session()
tap('下一章')
restore(before, 'chapter change restores chapter and its exact progress')

before = session()
controls = {label(n): point(n) for n in nodes() if label(n) in ('快进 30 秒', '下一章')}
sh('input', 'tap', *controls['快进 30 秒']); time.sleep(.1)
sh('input', 'tap', *controls['下一章']); time.sleep(.3)
restore(before, 'rapid seek and chapter switch preserve first origin')

before = session()
slider = next(n for n in nodes() if n.get('class') == 'android.widget.SeekBar')
b = list(map(int, re.findall(r'\d+', slider.get('bounds'))))
sh('input', 'swipe', (b[0]+b[2])//2, (b[1]+b[3])//2, b[0]+(b[2]-b[0])*90//100, (b[1]+b[3])//2, 300)
time.sleep(.3)
restore(before, 'large slider drag restores exact progress')

before = session()
sh('input', 'keyevent', 87); time.sleep(.3)
restore(before, 'media headphone next uses the same undo path')

tap('下一章')
assert available('返回刚才位置')
time.sleep(12.2)
assert not available('返回刚才位置')
results.append({'check': 'undo expires and leaves no touch target', 'result': 'pass'})
tap('上一章')
tap('返回刚才位置')
sh('input', 'keyevent', 127)

(out/'checks.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
