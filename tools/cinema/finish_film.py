"""Compose original sound design and encode the checked Blender frame sequence."""

import argparse
import json
import subprocess
import wave
from pathlib import Path

import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument('--work', required=True, type=Path)
parser.add_argument('--ffmpeg', required=True, type=Path)
parser.add_argument('--frames-dir', type=Path)
parser.add_argument('--output', type=Path)
parser.add_argument('--poster', type=Path)
args = parser.parse_args()
RATE, SECONDS, FPS = 48000, 30, 24
frames = args.frames_dir or args.work / 'renders/film'
missing = [i for i in range(1, FPS * SECONDS + 1) if not (frames / f'{i:04d}.png').is_file()]
if missing:
    raise SystemExit(f'Missing rendered frames: {missing[:12]}')

sound = np.zeros((RATE * SECONDS, 2), dtype=np.float64)
rng = np.random.default_rng(2215)


def add(start, duration, frequencies, gain=.06, pan=0, pluck=False):
    n = int(duration * RATE)
    t = np.arange(n) / RATE
    env = np.minimum(t / .04, 1) * np.minimum((duration - t) / .35, 1)
    if pluck:
        env *= np.exp(-t * 4)
    signal = sum(np.sin(2 * np.pi * f * t + .12 * np.sin(t * .5)) for f in frequencies) / len(frequencies)
    signal *= env * gain
    offset = int(start * RATE)
    keep = min(n, len(sound) - offset)
    sound[offset:offset + keep, 0] += signal[:keep] * np.sqrt((1 - pan) / 2)
    sound[offset:offset + keep, 1] += signal[:keep] * np.sqrt((1 + pan) / 2)


# An original restrained ambient score; no stock music or sampled recordings.
chords = [(146.83, 174.61, 220, 329.63), (130.81, 164.81, 196, 293.66),
          (116.54, 146.83, 174.61, 261.63), (130.81, 164.81, 196, 293.66),
          (146.83, 174.61, 220, 329.63)]
for bar, chord in enumerate(chords):
    add(bar * 6, 6.4, chord, .085)
    for beat in range(8):
        add(bar * 6 + beat * .75, .6, [chord[beat % 4] * 2], .035, pan=(-.25 if beat % 2 else .25), pluck=True)
    add(bar * 6, 5.5, [chord[0] / 2], .07)
for i, moment in enumerate([7.6, 8.1, 10.3, 12.65, 15, 23, 24.2]):
    pan = [-.6, -.5, -.15, .25, .6, .3, 0][i]
    add(moment, .8, [523.25, 783.99] if i in [4, 5] else [392 + i * 35], .12, pan=pan, pluck=True)
    if i in [4, 5]:
        add(moment + .14, .7, [659.25, 987.77], .08, pan=pan, pluck=True)
fade = np.minimum(np.arange(len(sound)) / RATE / 1.2, 1)
fade *= np.minimum((len(sound) - np.arange(len(sound))) / RATE / 1.2, 1)
sound *= fade[:, None]
peak = float(np.max(np.abs(sound)))
sound *= .65 / max(peak, .65)
pcm = (np.clip(sound, -1, 1) * 32767).astype('<i2')
wav = args.work / 'original-score.wav'
with wave.open(str(wav), 'wb') as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes(pcm.tobytes())

output = args.output or args.work / 'MeshGram-Connections-3D.mp4'
poster = args.poster or args.work / 'cinema-poster.png'
subprocess.run([str(args.ffmpeg), '-hide_banner', '-y', '-framerate', str(FPS),
                '-i', str(frames / '%04d.png'), '-i', str(wav),
                '-vf', 'fade=t=in:st=0:d=0.3,fade=t=out:st=29.5:d=0.5',
                '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
                '-c:a', 'aac', '-b:a', '160k', '-af', 'loudnorm=I=-18:TP=-2:LRA=8',
                '-movflags', '+faststart', '-t', str(SECONDS),
                '-metadata', 'title=MeshGram | Connections | 3D commercial',
                '-metadata', 'comment=Original Blender CGI. Illustrative interface and route, not a live BLE test.',
                str(output)], check=True)
subprocess.run([str(args.ffmpeg), '-hide_banner', '-y', '-ss', '27', '-i', str(output),
                '-frames:v', '1', '-update', '1', str(poster)], check=True)
subprocess.run([str(args.ffmpeg), '-v', 'error', '-i', str(output), '-f', 'null', '-'], check=True)
print(json.dumps({'video': str(output), 'bytes': output.stat().st_size, 'frames': FPS * SECONDS, 'audioPeak': peak}))
