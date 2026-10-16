"""Targeted checks using the disposable MuMu library; never connects to a physical phone."""
from pathlib import Path
import subprocess, sqlite3, time, json, re, sys, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'qa/results/listening-features'
OUT.mkdir(parents=True, exist_ok=True)
ADB = [r'C:/Software/MuMu Player 12/shell/adb.exe', '-s', '127.0.0.1:16384']

def sh(*args):
    return subprocess.check_output(ADB + ['shell', *map(str, args)], stderr=subprocess.STDOUT).decode('utf-8')

def nodes():
    sh('uiautomator', 'dump', '/sdcard/tingye-features.xml')
    return list(ET.fromstring(sh('cat', '/sdcard/tingye-features.xml')).iter('node'))

def tap(label):
    node = next(n for n in nodes() if n.get('text') == label or n.get('content-desc') == label)
    b = list(map(int, re.findall(r'\d+', node.get('bounds'))))
    sh('input', 'tap', (b[0]+b[2])//2, (b[1]+b[3])//2)
    time.sleep(.35)

def screenshot(name):
    (OUT / (name + '.png')).write_bytes(subprocess.check_output(ADB + ['exec-out', 'screencap', '-p']))

def snapshot(name='live'):
    p = OUT / (name + '.db')
    p.write_bytes(subprocess.check_output(ADB + ['exec-out', 'su 0 cat /data/data/com.tingye.app/databases/tingye.db']))
    for ext in ['-wal', '-shm']:
        data = subprocess.run(ADB + ['exec-out', f'su 0 cat /data/data/com.tingye.app/databases/tingye.db{ext}'], capture_output=True)
        if data.returncode == 0 and data.stdout: Path(str(p)+ext).write_bytes(data.stdout)
    return sqlite3.connect(p)

def session():
    text = sh('dumpsys', 'media_session')
    pos = re.search(r'state=(\d+), position=(\d+)', text)
    chapter = re.search(r'description=([^\r\n]+)', text)
    return (int(pos.group(1)), int(pos.group(2)), chapter.group(1).strip() if chapter else '') if pos else None

def show():
    for n in nodes():
        if n.get('text') or n.get('content-desc'): print(n.get('text') or n.get('content-desc'), n.get('bounds'))

if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'show'
    if mode == 'upgrade':
        sh('am', 'force-stop', 'com.tingye.app')
        db = snapshot('before')
        assert db.execute('PRAGMA user_version').fetchone()[0] == 3
        before = db.execute('SELECT id,positionMs FROM chapters ORDER BY id').fetchall()
        db.close()
        subprocess.run(ADB + ['install', '-r', str(ROOT/'releases/0.1.4/TingYe-0.1.4-release.apk')], check=True)
        sh('am','start','-W','-n','com.tingye.app/.MainActivity'); time.sleep(1)
        db = snapshot('after')
        assert db.execute('PRAGMA user_version').fetchone()[0] == 4
        assert db.execute('SELECT id,positionMs FROM chapters ORDER BY id').fetchall() == before
        assert db.execute('SELECT COUNT(*) FROM listening_days').fetchone()[0] == 0
        db.close(); print('3 -> 4 migration: chapter positions unchanged; statistics starts empty')
    elif mode == 'mark':
        chapter = sys.argv[2]
        db = snapshot(); before = db.execute('SELECT positionMs FROM chapters WHERE title=? ORDER BY id', (chapter,)).fetchall(); db.close()
        tap('标为已听完：' + chapter)
        assert any(n.get('content-desc') == '标为未听：' + chapter for n in nodes())
        db = snapshot(); assert db.execute('SELECT positionMs FROM chapters WHERE title=? ORDER BY id', (chapter,)).fetchall() == before; db.close()
        screenshot('chapters-marked')
        print('Manual mark: updates immediately and preserves chapter positions')
    elif mode == 'stats':
        def total():
            db = snapshot(); value = db.execute('SELECT COALESCE(SUM(durationMs),0) FROM listening_days').fetchone()[0]; db.close(); return value
        initial = total()
        sh('input', 'keyevent', 126); time.sleep(5)
        sh('input', 'keyevent', 127); time.sleep(.5)
        elapsed = total() - initial
        assert 4500 <= elapsed <= 6500, elapsed
        paused = total(); time.sleep(2)
        assert total() == paused
        print(json.dumps({'actualListeningMs': elapsed, 'pausedTimeAdded': 0}))
    elif mode == 'tap': tap(sys.argv[2])
    elif mode == 'shot': screenshot(sys.argv[2])
    elif mode == 'session': print(session())
    else: show()
