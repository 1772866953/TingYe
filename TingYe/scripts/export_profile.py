from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]; out=root/'qa/results/profiles'; out.mkdir(parents=True,exist_ok=True); adb=[r'C:\Software\MuMu Player 12\shell\adb.exe','-s','127.0.0.1:16384']
def sh(*a):return subprocess.check_output(adb+['shell',*a])
apk=sh('pm','path','com.tingye.app').decode().strip().removeprefix('package:')
raw=sh('su','0','/apex/com.android.art/bin/profman','--dump-classes-and-methods','--profile-file=/data/misc/profiles/cur/0/com.tingye.app/primary.prof','--apk='+apk,'--dex-location='+apk)
(out/'critical-path-profile.txt').write_bytes(raw)
lines=raw.decode().splitlines(); print('Rules',len(lines)); print('\n'.join(lines[:12]))
rules=[line for line in lines if 'Lcom/tingye/' in line and line.startswith(('H','S','P','L','['))]
print('App rules',len(rules));print('\n'.join(rules[:10]))
# The libraries already ship their own profiles. Add only measured application paths.
(root/'TingYe'/'app'/'src'/'main'/'baseline-prof.txt').write_text('\n'.join(sorted(set(rules)))+'\n',encoding='utf-8')
