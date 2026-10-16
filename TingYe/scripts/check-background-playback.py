"""Short background lifecycle checks on the disposable MuMu fixture, never a physical phone."""
from pathlib import Path
import json, re, runpy, time

ROOT = Path(__file__).resolve().parents[2]
q = runpy.run_path(str(Path(__file__).with_name('check-listening-features.py')))
sh, nodes, tap, session = (q[k] for k in ('sh', 'nodes', 'tap', 'session'))
OUT = ROOT/'qa/results/background-playback'
OUT.mkdir(parents=True, exist_ok=True)
checks = []

def wait_state(state, timeout=20):
    deadline = time.monotonic()+timeout
    while time.monotonic()<deadline:
        current = session()
        if current and current[0]==state: return current
        time.sleep(.4)
    raise AssertionError(('expected state', state, session()))

def pid(): return sh('pidof', 'com.tingye.app').strip()
def checkpoint(): return sh('su', '0', 'cat', '/data/data/com.tingye.app/shared_prefs/playback_recovery.xml')
def wakelock():
    return any('ExoPlayer:WakeLock' in line for line in sh('dumpsys','power').splitlines()
               if 'PARTIAL_WAKE_LOCK' in line)
def cpu_ticks(process):
    # utime and stime; the process name is enclosed in parentheses.
    fields = sh('su','0','cat', f'/proc/{process}/stat').split(') ',1)[1].split()
    return int(fields[11])+int(fields[12])
def record(name, **data):
    checks.append({'check': name, 'result': 'pass', **data})
    print(checks[-1], flush=True)

sh('am','start','-W','-n','com.tingye.app/.MainActivity')
time.sleep(.7)
if any(n.get('content-desc')=='收起播放器' for n in nodes()): tap('收起播放器')
tap('设置')
assert not any(n.get('text')=='显示主题' for n in nodes())
sh('input','swipe',540,1730,540,430,500); time.sleep(.3)
listing = nodes()
def y(label):
    n = next(n for n in listing if n.get('text')==label)
    return int(re.findall(r'\d+',n.get('bounds'))[1])
assert y('数据与隐私')>y('本地备份'), [(n.get('text'),n.get('bounds')) for n in listing]
tap('数据与隐私')
sh('input','swipe',540,1740,540,750,400)
assert any(n.get('text')=='备份与删除' for n in nodes())
q['screenshot']('settings-privacy')
record('Settings groups start collapsed; privacy is last and its rewritten contents are accessible')
tap('返回书架'); tap('MorningBook'); time.sleep(1)
sh('input','keyevent',126)
wait_state(3); time.sleep(.6)
assert wakelock(), sh('dumpsys','power')
record('Playback holds the local ExoPlayer wake lock')
sh('input','keyevent',127)
paused = wait_state(2)
time.sleep(.5)
assert not wakelock(), sh('dumpsys','power')
assert 'name="active" value="false"' in checkpoint(), checkpoint()
sh('input','keyevent',3); time.sleep(1)
process = pid(); before = cpu_ticks(process); time.sleep(3); after = cpu_ticks(process)
assert after-before < 30, (before,after)
record('Pause releases wake lock; brief idle CPU sample', idleCpuTicks=after-before, sampleSeconds=3)

# Remove only TingYe's activity task; its paused foreground service must survive.
stacks = sh('am','stack','list')
block = next((block for block in re.split(r'(?=(?:RootTask|Stack) id=)', stacks)
              if 'com.tingye.app/' in block), '')
match = re.search(r'(?:RootTask|Stack) id=(\d+)', block)
if not match:
    print(stacks, flush=True)
    raise AssertionError('Could not identify TingYe activity task')
sh('am','stack','remove',match.group(1)); time.sleep(.8)
assert pid()==process and session()[0]==2, (pid(),session())
record('Removing recent activity while paused retains the service')

# A reclaimed paused process must not emit sound by itself.
sh('su','0','kill','-9',process); time.sleep(2)
assert not session() or session()[0]!=3, session()
record('Paused process reclamation does not autoplay')
sh('input','keyevent',126)
resumed = wait_state(3)
assert resumed[2]==paused[2] and abs(resumed[1]-paused[1])<3000, (paused,resumed)
record('Media play restores the reclaimed paused session', restoredPositionMs=resumed[1])

# System sticky restart, without force-stop, should rebuild a recently playing queue.
time.sleep(1)
assert 'name="active" value="true"' in checkpoint(), checkpoint()
old = session(); process = pid()
sh('su','0','kill','-9',process)
resumed = wait_state(3)
assert resumed[2]==old[2] and abs(resumed[1]-old[1])<12_000, (old,resumed)
record('System restart restores ongoing playback and queue', oldPid=process, newPid=pid())
sh('am','startservice','-n','com.tingye.app/.playback.PlaybackService',
   '-a','com.tingye.app.TIMER','--ei','minutes',1,'--ei','fade_seconds',60)
time.sleep(.5)
process = pid(); before = cpu_ticks(process); time.sleep(2); after = cpu_ticks(process)
assert after-before < 50, (before,after)
assert 'name="fade" value="60000"' in checkpoint(), checkpoint()
record('Sleep fade keeps a bounded check cadence', cpuTicks=after-before, sampleSeconds=2)
sh('am','startservice','-n','com.tingye.app/.playback.PlaybackService',
   '-a','com.tingye.app.TIMER','--ei','minutes',0)
sh('input','keyevent',127); wait_state(2)
assert not wakelock()
(OUT/'checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(checks,ensure_ascii=False,indent=2))
