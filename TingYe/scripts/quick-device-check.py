"""Short release smoke check on the existing API 32 emulator; preserves its library."""
from pathlib import Path
import subprocess, time, json, re, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[2]
out = root / 'qa/results/device-check'
out.mkdir(parents=True, exist_ok=True)
adb = [r'C:\Software\MuMu Player 12\shell\adb.exe', '-s', '127.0.0.1:16384']
def sh(*args):
    return subprocess.check_output(adb + ['shell', *map(str, args)], stderr=subprocess.STDOUT)
def nodes():
    sh('uiautomator', 'dump', '/sdcard/tingye-smoke.xml')
    return list(ET.fromstring(sh('cat', '/sdcard/tingye-smoke.xml')).iter('node'))
def tap(label):
    n = next(n for n in nodes() if n.get('text') == label or n.get('content-desc') == label)
    b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
    sh('input', 'tap', (b[0]+b[2])//2, (b[1]+b[3])//2)
    time.sleep(.4)
def vibrations():
    return {line.strip() for line in sh('dumpsys', 'vibrator_manager').decode().splitlines()
            if 'opPkg: com.tingye.app,' in line}
result = {'device': 'MuMu API32', 'hardwareHapticFeelVerified': False, 'vivoVerified': False}
sh('am', 'force-stop', 'com.tingye.app')
sh('am', 'start', '-W', '-n', 'com.tingye.app/.MainActivity')
time.sleep(.6)
sh('input', 'tap', 480, 1220)
time.sleep(.5)
# Test fixture is the three-chapter MorningBook previously used in animation QA.
tap('播放')
tap('上一章')
tap('上一章')
result['controlPulses'] = {}
for label in ['暂停', '播放', '快退 15 秒', '快进 30 秒', '下一章', '上一章']:
    before = vibrations()
    tap(label)
    added = vibrations() - before
    result['controlPulses'][label] = len(added)
    assert len(added) == 1, (label, added)
    assert all('duration=18' in line for line in added), (label, added)
notification = sh('dumpsys', 'notification', '--noredact').decode()
session = sh('dumpsys', 'media_session').decode()
(out/'notification.txt').write_text(notification, encoding='utf-8')
(out/'media-session.txt').write_text(session, encoding='utf-8')
assert 'com.tingye.app' in session and 'state=3' in session, 'No playing system media session'
assert 'channel=playback' in notification, 'No notification on the playback channel'
assert 'vis=PUBLIC' in notification, 'Notification not visible on lock screen'
assert 'android.mediaSession' in notification, 'Notification lacks media-session token'
result['playingMediaSession'] = True
result['publicPlaybackNotification'] = True
sh('input', 'keyevent', 3)
sh('cmd', 'statusbar', 'expand-notifications')
time.sleep(.4)
sh('screencap', '-p', '/sdcard/tingye-notification.png')
subprocess.run(adb + ['pull', '/sdcard/tingye-notification.png', str(out/'notification.png')], check=True, capture_output=True)
sh('cmd', 'statusbar', 'collapse')
sh('am', 'start', '-W', '-n', 'com.tingye.app/.MainActivity')
time.sleep(.4)
tap('更多操作')
tap('书籍设置')
for _ in range(3):
    sh('input', 'swipe', 520, 1660, 520, 530, 180)
time.sleep(.4)
labels = [n.get('text') for n in nodes() if n.get('text')]
assert not any('追加' in t or t == '检查来源文件夹中的新增章节' for t in labels), labels
assert '从文件夹重新关联现有章节' not in labels, labels
result['folderRelinkRemoved'] = True
result['appendRemoved'] = True
sh('input', 'keyevent', 4)
time.sleep(.3)
tap('暂停')
(out/'quick-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
