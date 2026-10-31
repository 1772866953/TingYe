"""Reader fixtures and targeted UI probes. MuMu only; no physical phone access."""
from pathlib import Path
import subprocess, sys, time, re, sqlite3, json, zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'qa/results/reader'
FIX=ROOT/'qa/fixtures/reader'
OUT.mkdir(parents=True,exist_ok=True)
ADB=[r'C:/Software/MuMu Player 12/shell/adb.exe','-s','127.0.0.1:16384']

def sh(*args):
    return subprocess.check_output(ADB+['shell',*map(str,args)],stderr=subprocess.STDOUT).decode('utf-8')

def nodes():
    for attempt in range(3):
        result=subprocess.run(ADB+['shell','uiautomator','dump','/sdcard/reader-check.xml'],capture_output=True)
        if result.returncode==0:
            return list(ET.fromstring(sh('cat','/sdcard/reader-check.xml')).iter('node'))
        time.sleep(.25)
    raise RuntimeError('UI hierarchy unavailable')

def tap(label):
    matches=[n for n in nodes() if n.get('text')==label or n.get('content-desc')==label]
    if not matches: raise ValueError('Missing '+label)
    b=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
    sh('input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2)
    time.sleep(.35)

def shot(tag):
    (OUT/(tag+'.png')).write_bytes(subprocess.check_output(ADB+['exec-out','screencap','-p']))

def snapshot(tag):
    sh('am','force-stop','com.tingye.app')
    path=OUT/(tag+'.db')
    path.write_bytes(subprocess.check_output(ADB+['exec-out','su 0 cat /data/data/com.tingye.app/databases/tingye.db']))
    for suffix in ['-wal','-shm']:
        data=subprocess.run(ADB+['exec-out','su 0 cat /data/data/com.tingye.app/databases/tingye.db'+suffix],capture_output=True)
        if data.returncode==0 and data.stdout:Path(str(path)+suffix).write_bytes(data.stdout)
    connection=sqlite3.connect(path)
    return connection

def fixtures():
    FIX.mkdir(parents=True,exist_ok=True)
    paragraphs=[f'第{i+1}段。清晨的山路还带着昨夜的雨意，林间传来鸟鸣。她把那封信收进书里，沿着溪流继续向前。远处的灯火一点点亮了起来，每个人都有自己的故事，也都有想要抵达的地方。' for i in range(90)]
    source='第一章 山中来信\n\n'+'\n\n'.join(paragraphs)+'\n\n第二章 沿途的风\n\n'+'\n\n'.join(paragraphs)+'\n\n第三章 故事未完\n\n'+'\n\n'.join(paragraphs)
    (FIX/'山海之间.txt').write_text(source,encoding='utf-8')
    (FIX/'山海之间-GBK.txt').write_bytes(source.encode('gb18030'))
    (FIX/'校准字幕.srt').write_text('1\n00:00:10,000 --> 00:00:15,000\n清晨的山路还带着昨夜的雨意\n\n2\n00:00:30,000 --> 00:00:35,000\n她把那封信收进书里沿着溪流继续向前\n',encoding='utf-8')
    for version in ['2.0','3.0']:
        with zipfile.ZipFile(FIX/f'山海之间-EPUB{version[0]}.epub','w') as z:
            z.writestr('mimetype','application/epub+zip')
            z.writestr('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>')
            z.writestr('OPS/book.opf',f'<package version="{version}" xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>山海之间 EPUB{version[0]}</dc:title><dc:creator>听页测试</dc:creator></metadata><manifest><item id="second" href="text/02.xhtml" media-type="application/xhtml+xml"/><item id="first" href="text/01.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>')
            for number,title in [(1,'第一章 山中来信'),(2,'第二章 沿途的风')]:
                z.writestr(f'OPS/text/0{number}.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><head><title>'+title+'</title></head><body><h1>'+title+'</h1>'+''.join('<p>'+p+'</p>' for p in paragraphs)+'</body></html>')
    sh('mkdir','-p','/sdcard/Download/TingYeReader')
    subprocess.run(ADB+['push',str(FIX)+'/','/sdcard/Download/TingYeReader/'],check=True,capture_output=True)
    print('Generated reusable TXT/GB18030/EPUB2/EPUB3/SRT fixtures')

def record(tag):
    remote='/sdcard/reader-'+tag+'.mp4'
    sh('dumpsys','gfxinfo','com.tingye.app','reset')
    recorder=subprocess.Popen(ADB+['shell','screenrecord','--size','720x1280','--bit-rate','8000000','--time-limit','7',remote],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    time.sleep(.6)
    for direction in [1,-1,1,-1]:
        if tag=='scroll':args=[540,1350,540,650,450] if direction==1 else [540,650,540,1350,450]
        else:args=[950,950,150,950,450] if direction==1 else [150,950,950,950,450]
        sh('input','swipe',*args);time.sleep(.45)
    recorder.communicate(timeout=15)
    subprocess.run(ADB+['pull',remote,str(OUT/(tag+'.mp4'))],check=True,capture_output=True)
    (OUT/(tag+'-frames.txt')).write_text(sh('dumpsys','gfxinfo','com.tingye.app','framestats'),encoding='utf-8')
    print('Recorded '+tag)

if __name__=='__main__':
    mode=sys.argv[1] if len(sys.argv)>1 else 'show'
    if mode=='fixtures':fixtures()
    elif mode=='tap':tap(sys.argv[2])
    elif mode=='shot':shot(sys.argv[2])
    elif mode=='record':record(sys.argv[2])
    elif mode=='snapshot':
        db=snapshot(sys.argv[2]);print(json.dumps({'version':db.execute('PRAGMA user_version').fetchone()[0], 'books':db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 'chapters':db.execute('SELECT COUNT(*) FROM chapters').fetchone()[0]}));db.close()
    else:
        for n in nodes():
            value=n.get('text') or n.get('content-desc')
            if value:print(value[:150].replace('\n',' / '),n.get('bounds'))
