from pathlib import Path
import subprocess, sys, re, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[2] / 'qa/results/ui'; ROOT.mkdir(parents=True, exist_ok=True)
ADB = [r'C:\Software\MuMu Player 12\shell\adb.exe', '-s', '127.0.0.1:16384']
def shell(*args):
    return subprocess.check_output(ADB + ['shell', *map(str,args)], stderr=subprocess.STDOUT)
def nodes():
    shell('uiautomator','dump','/sdcard/window.xml')
    return ET.fromstring(shell('cat','/sdcard/window.xml'))
if len(sys.argv)>1 and sys.argv[1]=='tap':
    label=sys.argv[2]
    matches=[n for n in nodes().iter('node') if n.get('text')==label or n.get('content-desc')==label]
    if not matches: raise ValueError('Missing '+label)
    b=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
    print(label,b); shell('input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2)
elif len(sys.argv)>1 and sys.argv[1]=='shot':
    tag=sys.argv[2]; shell('screencap','-p','/sdcard/current.png')
    subprocess.run(ADB+['pull','/sdcard/current.png',str(ROOT/(tag+'.png'))],check=True,capture_output=True)
else:
    for n in nodes().iter('node'):
        if n.get('text') or n.get('content-desc'):
            print(n.get('text') or n.get('content-desc'),n.get('bounds'),n.get('enabled'))
