"""Reusable tagged audio with embedded art and a distinct folder cover, generated locally."""
from pathlib import Path
import sys, subprocess
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'tools/video-tools'))
import imageio_ffmpeg
folder = root / 'qa/fixtures/metadata/TaggedBook'
folder.mkdir(parents=True, exist_ok=True)
image = Image.new('RGB', (256,256), '#607F74')
ImageDraw.Draw(image).rectangle((64,40,192,216), fill='#F0DAB3')
image.save(folder/'embedded.jpg')
image = Image.new('RGB', (256,256), '#9A6770')
ImageDraw.Draw(image).rectangle((64,40,192,216), fill='#ECDCC1')
image.save(folder/'cover.png')
subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', '-i',
    'sine=frequency=220:duration=6', '-i', str(folder/'embedded.jpg'), '-map', '0:a', '-map', '1:v',
    '-c:a', 'libmp3lame', '-q:a', '6', '-c:v', 'mjpeg', '-id3v2_version', '3',
    '-metadata', 'album=Morning Metadata Book', '-metadata', 'title=Tagged Chapter 1',
    '-metadata:s:v', 'title=Album cover', '-metadata:s:v', 'comment=Cover (front)',
    str(folder/'01-tagged.mp3')], check=True, capture_output=True)
print(folder)
