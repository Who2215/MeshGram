#!/usr/bin/env python3
"""Build a MeshGram demo from native Android screen recordings."""

from __future__ import annotations

import argparse
import math
import random
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS, DURATION = 720, 1280, 20, 27
CYAN, PINK, VIOLET = (63, 230, 242), (240, 78, 211), (119, 79, 224)
GREEN, WHITE, MUTED, INK = (83, 245, 174), (244, 248, 255), (154, 168, 194), (5, 8, 24)


def clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def smooth(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def fade(t: float, start: float, end: float, edge: float = 0.45) -> float:
    return clamp(min((t - start) / edge, (end - t) / edge))


def rgba(color: tuple[int, int, int], opacity: float) -> tuple[int, int, int, int]:
    return (*color, int(255 * clamp(opacity)))


def fnt(path: Path, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), size=size)


def center(draw: ImageDraw.ImageDraw, xy: tuple[float, float], value: str,
           face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int]) -> None:
    draw.text(xy, value, font=face, fill=fill, anchor="mm")


def lines(draw: ImageDraw.ImageDraw, x: int, y: int, values: tuple[str, ...],
          face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int], step: int) -> None:
    for index, value in enumerate(values):
        center(draw, (x, y + index * step), value, face, fill)


def glow(image: Image.Image, x: float, y: float, color: tuple[int, int, int],
         radius: float, opacity: float) -> None:
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(color, opacity))
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(max(2, int(radius * 0.32)))))


def background(image: Image.Image, t: float, stars: list[tuple[float, ...]]) -> None:
    draw = ImageDraw.Draw(image, "RGBA")
    for y in range(H):
        ratio = y / (H - 1)
        color = (int(4 + 6 * ratio), int(8 + 3 * ratio), int(24 + 19 * ratio), 255)
        draw.line((0, y, W, y), fill=color)
    glow(image, 95 + math.sin(t * 0.15) * 42, 390, CYAN, 180, 0.12)
    glow(image, 640 + math.cos(t * 0.11) * 55, 830, PINK, 230, 0.13)
    glow(image, 330 + math.sin(t * 0.08) * 80, 1190, VIOLET, 260, 0.09)
    draw = ImageDraw.Draw(image, "RGBA")
    for index, (base_x, base_y, radius, phase, tone) in enumerate(stars):
        x = (base_x + math.sin(t * (0.035 + (index % 4) * 0.009) + phase) * 13) % W
        y = (base_y + t * (1.0 + index % 3) + math.cos(t * 0.07 + phase) * 6) % H
        pulse = 0.25 + 0.42 * (0.5 + 0.5 * math.sin(t * 0.47 + phase))
        color = CYAN if tone == 0 else PINK if tone == 1 else WHITE
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(color, pulse))


def mark(draw: ImageDraw.ImageDraw, x: int, y: int, size: int) -> None:
    points = [(x, y - size), (x + 23, y - 13), (x + 23, y + 13),
              (x, y + size), (x - 23, y + 13), (x - 23, y - 13)]
    draw.line(points + [points[0]], fill=rgba(CYAN, 0.9), width=2)
    for index, point in enumerate(points):
        draw.line((x, y, *point), fill=rgba(CYAN if index % 2 == 0 else PINK, 0.72), width=2)


def phone_slots(draw: ImageDraw.ImageDraw, opacity: float) -> None:
    for left, color in ((30, CYAN), (390, PINK)):
        draw.rounded_rectangle((left, 282, left + 300, 949), radius=28,
                               fill=rgba((8, 13, 35), 0.65 * opacity),
                               outline=rgba(color, 0.55 * opacity), width=2)


def path_position(points: list[tuple[int, int]], progress: float) -> tuple[float, float]:
    scaled = clamp(progress) * (len(points) - 1)
    index = min(len(points) - 2, int(scaled))
    local = smooth(scaled - index)
    x1, y1 = points[index]
    x2, y2 = points[index + 1]
    return x1 + (x2 - x1) * local, y1 + (y2 - y1) * local


def route_scene(image: Image.Image, draw: ImageDraw.ImageDraw, t: float,
                regular: ImageFont.FreeTypeFont, medium: ImageFont.FreeTypeFont,
                small: ImageFont.FreeTypeFont) -> None:
    opacity = fade(t, 16.0, 21.5)
    if opacity <= 0:
        return
    center(draw, (360, 110), "КАК ИДЁТ ПАКЕТ", small, rgba(CYAN, opacity))
    center(draw, (360, 175), "Шифрованный маршрут", medium, rgba(WHITE, opacity))
    center(draw, (360, 218), "в сети MeshGram", regular, rgba(PINK, opacity))
    points = [(96, 390), (236, 525), (474, 488), (590, 665), (360, 845)]
    labels = ("Алекс", "Mesh-узел", "Mesh-узел", "Mesh-узел", "Роман")
    for start, end in zip(points, points[1:]):
        draw.line((*start, *end), fill=rgba((96, 130, 176), 0.45 * opacity), width=4)
        draw.line((*start, *end), fill=rgba(CYAN, 0.18 * opacity), width=1)
    for index, ((x, y), label) in enumerate(zip(points, labels)):
        color = CYAN if index in (0, 4) else VIOLET
        glow(image, x, y, color, 38 if index in (0, 4) else 28, 0.22 * opacity)
        draw.ellipse((x - 20, y - 20, x + 20, y + 20), fill=rgba((12, 21, 49), opacity),
                     outline=rgba(color, opacity), width=3)
        draw.ellipse((x - 5, y - 5, x + 5, y + 5), fill=rgba(GREEN if index == 4 else color, opacity))
        center(draw, (x, y + 48), label, small, rgba(WHITE, opacity * 0.9))
    px, py = path_position(points, ((t - 16.35) / 4.5) % 1.0)
    glow(image, px, py, GREEN, 38, 0.48 * opacity)
    draw.ellipse((px - 10, py - 10, px + 10, py + 10), fill=rgba(GREEN, opacity),
                 outline=rgba(WHITE, opacity), width=2)
    draw.rounded_rectangle((px - 18, py - 44, px + 18, py - 17), radius=7,
                           fill=rgba((9, 18, 36), opacity), outline=rgba(GREEN, opacity), width=2)
    draw.arc((px - 10, py - 58, px + 10, py - 34), 180, 360, fill=rgba(GREEN, opacity), width=3)
    draw.rounded_rectangle((42, 981, 678, 1187), radius=28,
                           fill=rgba((12, 20, 47), 0.9 * opacity),
                           outline=rgba((76, 118, 163), 0.38 * opacity), width=2)
    center(draw, (360, 1027), "Проверено сейчас: прямой BLE-маршрут", regular, rgba(WHITE, opacity))
    lines(draw, 360, 1081, (
        "Если прямой связи нет, устройства с MeshGram",
        "могут передавать пакет дальше до адресата.",
        "Промежуточные узлы не получают текст сообщения.",
    ), small, rgba(MUTED, opacity), 32)


def render_background(output: Path, ffmpeg: str, font_path: Path) -> None:
    rng = random.Random(2215)
    stars = [(rng.uniform(0, W), rng.uniform(0, H), rng.uniform(0.7, 2.2),
              rng.uniform(0, math.tau), rng.randrange(3)) for _ in range(70)]
    regular, small = fnt(font_path, 25), fnt(font_path, 18)
    medium, large, brand = fnt(font_path, 42), fnt(font_path, 64), fnt(font_path, 30)
    command = [ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{W}x{H}",
               "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "veryfast",
               "-crf", "25", "-pix_fmt", "yuv420p", str(output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if process.stdin is None:
        raise RuntimeError("Unable to open FFmpeg input")
    try:
        for frame_index in range(DURATION * FPS):
            t = frame_index / FPS
            image = Image.new("RGBA", (W, H), (*INK, 255))
            background(image, t, stars)
            draw = ImageDraw.Draw(image, "RGBA")
            mark(draw, 64, 78, 27)
            draw.text((108, 55), "MeshGram", font=brand, fill=rgba(WHITE, 0.96))
            intro = fade(t, 0.0, 3.0)
            if intro:
                center(draw, (360, 390), "КОГДА WI-FI", regular, rgba(CYAN, intro))
                center(draw, (360, 472), "СДАЛСЯ", large, rgba(WHITE, intro))
                center(draw, (360, 548), "MeshGram продолжил путь.", medium, rgba(PINK, intro))
                lines(draw, 360, 642, ("Не макет. Ниже — реальная доставка",
                                      "между двумя Android-экземплярами."),
                      regular, rgba(MUTED, intro), 38)
            test = fade(t, 3.0, 16.0)
            if test:
                center(draw, (360, 112), "РЕАЛЬНЫЙ BLE-ТЕСТ", small, rgba(CYAN, test))
                center(draw, (360, 158), "Два телефона. Один диалог.", medium, rgba(WHITE, test))
                center(draw, (180, 242), "АЛЕКС • ОТПРАВИТЕЛЬ", small, rgba(CYAN, test))
                center(draw, (540, 242), "РОМАН • ПОЛУЧАТЕЛЬ", small, rgba(PINK, test))
                phone_slots(draw, test)
                caption = ("Алекс пишет: «Wi-Fi: I quit…»" if t < 8.5 else
                           "E2E-пакет уходит по BLE" if t < 13.0 else
                           "То же сообщение появляется у Романа")
                center(draw, (360, 1030), caption, regular, rgba(WHITE, test))
                center(draw, (360, 1080), "Оба экрана записаны с работающего приложения", small, rgba(MUTED, test))
            route_scene(image, draw, t, regular, medium, small)
            final = fade(t, 21.5, 27.0)
            if final:
                center(draw, (360, 110), "ДОСТАВКА В ОБЕ СТОРОНЫ", small, rgba(GREEN, final))
                center(draw, (360, 160), "Сообщение пришло. Ответ тоже.", medium, rgba(WHITE, final))
                center(draw, (180, 242), "ЭКРАН АЛЕКСА", small, rgba(CYAN, final))
                center(draw, (540, 242), "ЭКРАН РОМАНА", small, rgba(PINK, final))
                phone_slots(draw, final)
                center(draw, (360, 1024), "delivered • зашифровано • адресно", regular, rgba(GREEN, final))
                center(draw, (360, 1080), "MeshGram", medium, rgba(WHITE, final))
                center(draw, (360, 1126), "Связь находит путь.", regular, rgba(CYAN, final))
            process.stdin.write(image.tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("FFmpeg failed while rendering background")


def compose(background_video: Path, args: argparse.Namespace) -> None:
    graph = (
        "[1:v]trim=0:13,setpts=PTS-STARTPTS+3/TB,scale=300:667[alex];"
        "[2:v]trim=0:13,setpts=PTS-STARTPTS+3/TB,scale=300:667[roman];"
        "[3:v]trim=0:5.5,setpts=PTS-STARTPTS+21.5/TB,scale=300:667[af];"
        "[4:v]trim=0:5.5,setpts=PTS-STARTPTS+21.5/TB,scale=300:667[rf];"
        "[0:v][alex]overlay=30:282:enable='between(t,3,16)'[v1];"
        "[v1][roman]overlay=390:282:enable='between(t,3,16)'[v2];"
        "[v2][af]overlay=30:282:enable='between(t,21.5,27)'[v3];"
        "[v3][rf]overlay=390:282:enable='between(t,21.5,27)'[out]"
    )
    command = [args.ffmpeg, "-y", "-i", str(background_video),
               "-i", str(args.alex_recording), "-i", str(args.roman_recording),
               "-i", str(args.alex_final), "-i", str(args.roman_final),
               "-filter_complex", graph, "-map", "[out]", "-t", str(DURATION),
               "-r", str(FPS), "-c:v", "libx264", "-preset", "slow", "-crf", "23",
               "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output)]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([args.ffmpeg, "-y", "-ss", "22.5", "-i", str(args.output),
                    "-frames:v", "1", str(args.poster)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    for name in ("alex-recording", "roman-recording", "alex-final", "roman-final", "output", "poster"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--font", type=Path, default=Path("site/assets/Manrope.ttf"))
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "ffmpeg")
    parser.add_argument("--work-dir", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    for source in (args.alex_recording, args.roman_recording, args.alex_final,
                   args.roman_final, args.font):
        if not source.exists():
            raise FileNotFoundError(source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.poster.parent.mkdir(parents=True, exist_ok=True)
    if args.work_dir is not None:
        args.work_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix="meshgram-real-demo-",
        dir=str(args.work_dir) if args.work_dir is not None else None,
    ) as temp_dir:
        background_video = Path(temp_dir) / "background.mp4"
        render_background(background_video, args.ffmpeg, args.font)
        compose(background_video, args)
    print(args.output)
    print(args.poster)


if __name__ == "__main__":
    main()
