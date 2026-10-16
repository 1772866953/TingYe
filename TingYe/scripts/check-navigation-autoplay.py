"""MuMu MorningBook checks; use chapter headphone mappings and a 12s undo window."""
from pathlib import Path
import json, re, runpy, time

ROOT = Path(__file__).resolve().parents[2]
q = runpy.run_path(str(Path(__file__).with_name('check-listening-features.py')))
sh, nodes, tap, session = (q[k] for k in ('sh', 'nodes', 'tap', 'session'))
OUT = ROOT / 'qa/results/navigation-autoplay'
OUT.mkdir(parents=True, exist_ok=True)
checks = []

def pause():
    sh('input', 'keyevent', 127)
    time.sleep(.2)
    assert session()[0] == 2, session()

def playing(name, chapter=None):
    time.sleep(.3)
    state = session()
    assert state[0] == 3, (name, state)
    if chapter: assert chapter in state[2], (name, state)
    checks.append({'action': name, 'state': 'playing', 'positionMs': state[1], 'chapter': state[2]})
    return state

def position(fraction):
    pause()
    slider = next(n for n in nodes() if n.get('class') == 'android.widget.SeekBar')
    b = list(map(int, re.findall(r'\d+', slider.get('bounds'))))
    # The track has 10dp padding and a 10dp thumb at each end.
    x = round(b[0] + 60 + (b[2]-b[0]-120)*fraction)
    sh('input', 'tap', x, (b[1]+b[3])//2)
    time.sleep(.3)
    playing('slider tap commits and starts playback')
    pause()
    time.sleep(1.7)  # Start a fresh undo group for the next command.
    return session()

if not any(n.get('content-desc') == '收起播放器' for n in nodes()):
    tap('MorningBook'); time.sleep(1)
pause()
for _ in range(2):
    if 'Chapter 1' in session()[2]: break
    tap('上一章')
pause()
assert 'MorningBook, Chapter 1' in session()[2], session()

origin = position(.25)
tap('快进 30 秒')
after = playing('UI fast forward')
assert 29_500 <= after[1]-origin[1] <= 32_000, (origin, after)
pause()
origin = session()
tap('快退 15 秒')
after = playing('UI rewind')
assert 13_000 <= origin[1]-after[1] <= 15_500, (origin, after)

chapter_one = position(.30)
tap('下一章'); playing('UI next chapter', 'Chapter 2')
chapter_two = position(.40)
tap('上一章')
after = playing('UI previous chapter', 'Chapter 1')
assert abs(after[1]-chapter_one[1]) < 2000, (chapter_one, after)
pause(); sh('input', 'keyevent', 87)
after = playing('headphone next', 'Chapter 2')
assert abs(after[1]-chapter_two[1]) < 2000, (chapter_two, after)
pause(); sh('input', 'keyevent', 88); playing('headphone previous', 'Chapter 1')
position(.30)
sh('input', 'keyevent', 90); playing('standard media fast forward')
pause(); sh('input', 'keyevent', 89); playing('standard media rewind')

origin = position(.20)
tap('快进 30 秒'); playing('forward before playing undo')
tap('返回刚才位置')
after = playing('undo preserves playing state', 'Chapter 1')
assert abs(after[1]-origin[1]) < 2000, (origin, after)
origin = position(.25)
tap('下一章'); playing('next before paused undo', 'Chapter 2')
pause(); tap('返回刚才位置')
after = session()
assert after[0] == 2 and after[2] == origin[2] and abs(after[1]-origin[1]) < 100, (origin, after)
checks.append({'action': 'undo preserves paused state', 'result': 'pass'})
tap('上一章')
assert session()[0] == 2 and 'Chapter 1' in session()[2], session()
checks.append({'action': 'previous at first chapter remains a no-op', 'result': 'pass'})
checks.append({'action': 'both chapter memories restored', 'result': 'pass'})
pause()
slider = next(n for n in nodes() if n.get('class') == 'android.widget.SeekBar')
b = list(map(int, re.findall(r'\d+', slider.get('bounds'))))
sh('input', 'swipe', b[0]+(b[2]-b[0])//3, (b[1]+b[3])//2,
   b[0]+(b[2]-b[0])//2, (b[1]+b[3])//2, 350)
playing('slider drag commits and starts playback')
pause()

(OUT/'checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(checks, ensure_ascii=False, indent=2))
