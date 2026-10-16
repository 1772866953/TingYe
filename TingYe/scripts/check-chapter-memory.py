"""Targeted release check on the disposable MuMu test library (never a physical phone)."""
from pathlib import Path
import subprocess, sqlite3, time, json, re, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'qa/results/chapter-memory'
OUT.mkdir(parents=True, exist_ok=True)
ADB = [r'C:/Software/MuMu Player 12/shell/adb.exe', '-s', '127.0.0.1:16384']
def sh(command): return subprocess.check_output(ADB + ['shell', command]).decode('utf-8')
def snapshot():
    p = OUT / 'live.db'
    p.write_bytes(subprocess.check_output(ADB + ['exec-out', 'su 0 cat /data/data/com.tingye.app/databases/tingye.db']))
    for ext in ['-wal', '-shm']:
        data = subprocess.run(ADB + ['exec-out', f'su 0 cat /data/data/com.tingye.app/databases/tingye.db{ext}'], capture_output=True)
        if data.returncode == 0: Path(str(p)+ext).write_bytes(data.stdout)
    return sqlite3.connect(p)
def nodes():
    sh('uiautomator dump /sdcard/tingye-check.xml')
    return list(ET.fromstring(sh('cat /sdcard/tingye-check.xml')).iter('node'))
def tap(label):
    node = next(n for n in nodes() if n.get('text') == label or n.get('content-desc') == label)
    b = list(map(int, re.findall(r'\d+', node.get('bounds'))))
    sh(f'input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}')
    time.sleep(.25)
def position():
    text = sh('dumpsys media_session')
    chapter = re.search(r'Chapter ([123])', text)
    pos = re.search(r'state=\d+, position=(\d+)', text)
    assert pos, text
    return int(pos.group(1))

if '--prepare' in __import__('sys').argv:
    assert 'MuMu' in subprocess.check_output([r'C:/Software/MuMu Player 12/nx_main/MuMuManager.exe', 'info', '-v', '0']).decode()
    source = ROOT/'qa/results/migration/v2.db'
    fixture = OUT/'fixture-v2.db'
    db = sqlite3.connect(source); db.backup(sqlite3.connect(fixture)); db.close()
    db = sqlite3.connect(fixture)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('DELETE FROM books WHERE id NOT IN (4,5)')
    db.execute('DELETE FROM bookmarks')
    db.execute('UPDATE books SET updatedAt=100000,chapterId=10,chapterIndex=0,positionMs=5000,status=1 WHERE id=4')
    db.execute('UPDATE books SET updatedAt=1 WHERE id=5')
    for chapter, book, order in db.execute('SELECT id,bookId,orderIndex FROM chapters').fetchall():
        file = f'chapter-{chapter}.wav'
        db.execute('UPDATE chapters SET uri=?,durationMs=120000 WHERE id=?', (f'file:///data/data/com.tingye.app/files/{file}', chapter))
    db.commit(); db.close()
    subprocess.run(ADB+['uninstall', 'com.tingye.app'], check=True)
    subprocess.run(ADB+['install', str(ROOT/'releases/0.1.3/TingYe-0.1.3-release.apk')], check=True)
    sh('am start -W -n com.tingye.app/.MainActivity'); time.sleep(.5); sh('am force-stop com.tingye.app')
    subprocess.run(ADB+['push', str(fixture), '/sdcard/tingye-v2.db'], check=True)
    subprocess.run(ADB+['push', str(ROOT/'qa/fixtures/long_audio/Chapter 1.wav'), '/sdcard/tingye-test.wav'], check=True)
    uid = re.search(r'userId=(\d+)', sh('dumpsys package com.tingye.app')).group(1)
    sh('su 0 rm -f /data/data/com.tingye.app/databases/tingye.db-wal /data/data/com.tingye.app/databases/tingye.db-shm')
    sh('su 0 cp /sdcard/tingye-v2.db /data/data/com.tingye.app/databases/tingye.db')
    sh(f'su 0 chown {uid}:{uid} /data/data/com.tingye.app/databases/tingye.db')
    for chapter in range(10,16):
        sh(f'su 0 cp /sdcard/tingye-test.wav /data/data/com.tingye.app/files/chapter-{chapter}.wav')
        sh(f'su 0 chown {uid}:{uid} /data/data/com.tingye.app/files/chapter-{chapter}.wav')
    sh('am start -W -n com.tingye.app/.MainActivity'); time.sleep(1)
    db = snapshot()
    assert db.execute('PRAGMA user_version').fetchone()[0] == 3
    assert db.execute('SELECT positionMs FROM chapters WHERE id=10').fetchone()[0] == 5000
    assert db.execute('SELECT positionMs FROM chapters WHERE id=13').fetchone()[0] == 12200
    db.close()
    print('v2 -> v3 migration preserved both existing book positions')
else:
    sh('am force-stop com.tingye.app')
    sh('am start -W -n com.tingye.app/.MainActivity'); time.sleep(.5)
    # Select the large shelf card without autoplay, then play and immediately pause.
    sh('input tap 480 1220'); time.sleep(.8)
    tap('播放'); tap('暂停')
    tap('上一章')
    tap('上一章')
    slider = next(n for n in nodes() if n.get('class') == 'android.widget.SeekBar')
    bounds = list(map(int, re.findall(r'\d+', slider.get('bounds'))))
    y = (bounds[1]+bounds[3])//2
    sh(f'input tap 310 {y}'); time.sleep(.3)
    a = position()
    tap('下一章'); sh(f'input tap 710 {y}'); time.sleep(.3)
    b = position()
    assert a > 15000 and b > 60000 and abs(a-b) > 20000, (a,b)
    tap('上一章'); restored_a = position()
    assert abs(a-restored_a) < 1000, (a, restored_a)
    tap('下一章'); restored_b = position()
    assert abs(b-restored_b) < 1000, (b, restored_b)
    sh('input tap 126 174'); time.sleep(.6)
    sh('input keyevent 4'); time.sleep(.3)
    # API32's am can report "Service stopped" with exit 255; inspect actual session state.
    subprocess.run(ADB+['shell','am stopservice -n com.tingye.app/.playback.PlaybackService'],capture_output=True)
    time.sleep(.4)
    # Root back keeps Android 12 activities bound in the background; stopservice
    # cannot guarantee onDestroy here. Force-stop below tests durable positions.
    sh('am force-stop com.tingye.app')
    db = snapshot()
    saved = dict(db.execute('SELECT id,positionMs FROM chapters').fetchall()); db.close()
    assert abs(saved[10]-a) < 1000 and abs(saved[11]-b) < 1000, saved
    sh('am start -W -n com.tingye.app/.MainActivity'); time.sleep(.5)
    sh('input tap 480 1220'); time.sleep(.8)
    tap('播放'); tap('暂停')
    tap('上一章'); restarted_a = position()
    tap('下一章'); restarted_b = position()
    assert abs(a-restarted_a) < 1000 and abs(b-restarted_b) < 1000, (a,b,restarted_a,restarted_b)
    print(json.dumps({'chapter1':a, 'chapter2':b, 'restored1':restored_a, 'restored2':restored_b, 'disk':saved}))
