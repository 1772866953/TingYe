from pathlib import Path
from PIL import Image,ImageDraw
import sys
root=Path(__file__).resolve().parents[2];sys.path.insert(0,str(root/'tools/video-tools'));import imageio_ffmpeg
for tag in sys.argv[1:]:
    reader=imageio_ffmpeg.read_frames(str(root/'qa/results/animations'/f'{tag}.mp4'),pix_fmt='rgb24',output_params=['-vf','fps=5,scale=216:384'])
    next(reader);frames=[Image.frombytes('RGB',(216,384),f) for f in reader]
    selected=[i for i in range(0,min(len(frames),65),2)]
    sheet=Image.new('RGB',(216*8,410*((len(selected)+7)//8)),'white');d=ImageDraw.Draw(sheet)
    for n,i in enumerate(selected):
        x=n%8*216;y=n//8*410;sheet.paste(frames[i],(x,y+26));d.text((x+5,y+5),f'{i/5:.1f}s',fill='black')
    sheet.save(root/'qa/results/animations'/f'{tag}-contact.png')
