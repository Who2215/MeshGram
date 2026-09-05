#!/usr/bin/env python3
"""Render a small MeshGram product teaser for the site and Telegram channel."""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


WIDTH = 960
HEIGHT = 540
FPS = 24
DURATION = 16
CYAN = (82, 231, 255)
PINK = (243, 91, 216)
LIME = (184, 255, 221)
TEXT = (241, 245, 255)
MUTED = (157, 169, 198)


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def fade_for(t: float, start: float, end: float) -> float:
    return clamp(min((t - start) / 0.55, (end - t) / 0.55))


def rgba(color: tuple[int, int, int], alpha: float) -> tuple[int, int, int, int]:
    return (*color, int(clamp(alpha) * 255))


def font(path: Path, size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    # Manrope is bundled with the site and supports the Cyrillic teaser copy.
    return ImageFont.truetype(str(path), size=size)


def centered(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str,
             face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int]) -> None:
    box = draw.textbbox((0, 0), text, font=face)
    draw.text((xy[0] - (box[2] - box[0]) / 2, xy[1]), text, font=face, fill=fill)


def base_background() -> Image.Image:
    image = Image.new("RGBA", (WIDTH, HEIGHT))
    pixels = image.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            top = y / HEIGHT
            side = x / WIDTH
            pixels[x, y] = (
                int(7 + 10 * top + 5 * side),
                int(10 + 8 * top + 5 * (1 - side)),
                int(24 + 25 * (1 - top) + 16 * side),
                255,
            )
    return image


def add_glow(image: Image.Image, x: float, y: float, color: tuple[int, int, int],
             radius: float, strength: float = 1.0) -> None:
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for scale, alpha in ((1.0, 0.10), (0.60, 0.18), (0.25, 0.45)):
        current = radius * scale
        draw.ellipse(
            (x - current, y - current, x + current, y + current),
            fill=rgba(color, alpha * strength),
        )
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(max(2, int(radius * 0.22)))))


def add_stars(image: Image.Image, t: float) -> None:
    draw = ImageDraw.Draw(image, "RGBA")
    for index in range(88):
        phase = index * 1.731
        x = (index * 137.0 + 32.0 + math.sin(t * 0.07 + phase) * 12.0) % WIDTH
        y = (index * 71.0 + 24.0 + math.cos(t * 0.05 + phase) * 8.0) % HEIGHT
        radius = 0.7 + (index % 4) * 0.35
        alpha = 0.22 + 0.26 * (0.5 + 0.5 * math.sin(t * 0.45 + phase))
        tint = CYAN if index % 7 == 0 else PINK if index % 11 == 0 else TEXT
        if radius > 1.5:
            add_glow(image, x, y, tint, 14, 0.25)
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(tint, alpha))


def draw_brand(draw: ImageDraw.ImageDraw, regular: ImageFont.FreeTypeFont, t: float) -> None:
    draw.text((52, 34), "Mesh", font=regular, fill=TEXT)
    brand_box = draw.textbbox((52, 34), "Mesh", font=regular)
    draw.text((brand_box[2] + 2, 34), "Gram", font=regular, fill=CYAN)
    draw.text((52, 75), "BLE-FIRST  /  HYBRID ROUTING", font=font(regular.path, 12), fill=rgba(MUTED, 0.78))
    draw.ellipse((872, 37, 878, 43), fill=rgba(LIME, 0.9))
    draw.text((890, 32), "LIVE", font=font(regular.path, 11), fill=rgba(LIME, 0.85))


NODES = [(96, 368), (267, 263), (472, 352), (672, 224), (858, 322)]
EDGES = [(0, 1), (1, 2), (2, 3), (3, 4), (1, 3)]


def draw_network(image: Image.Image, t: float, alpha: float = 1.0) -> None:
    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow, "RGBA")
    line_color = rgba(CYAN, 0.34 * alpha)
    for start, end in EDGES:
        glow_draw.line((NODES[start], NODES[end]), fill=line_color, width=2)
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(9)))
    draw = ImageDraw.Draw(image, "RGBA")
    for start, end in EDGES:
        draw.line((NODES[start], NODES[end]), fill=rgba((138, 187, 220), 0.46 * alpha), width=1)
    for index, (x, y) in enumerate(NODES):
        pulse = 1 + 0.18 * math.sin(t * 1.3 + index)
        color = CYAN if index in (0, 4) else PINK if index == 2 else LIME
        add_glow(image, x, y, color, 29 * pulse, 0.48 * alpha)
        radius = 6 * pulse
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(color, alpha))
        draw.ellipse((x - 18, y - 18, x + 18, y + 18), outline=rgba(color, 0.28 * alpha), width=1)
    progress = clamp((t - 4.0) / 4.3)
    if 0 < progress < 1:
        path = [NODES[0], NODES[1], NODES[2], NODES[3], NODES[4]]
        distance = progress * (len(path) - 1)
        segment = min(int(distance), len(path) - 2)
        local = distance - segment
        x = path[segment][0] + (path[segment + 1][0] - path[segment][0]) * local
        y = path[segment][1] + (path[segment + 1][1] - path[segment][1]) * local
        add_glow(image, x, y, CYAN, 34, 0.9 * alpha)
        draw.ellipse((x - 8, y - 8, x + 8, y + 8), fill=rgba(TEXT, alpha))
        draw.ellipse((x - 4, y - 4, x + 4, y + 4), fill=rgba(CYAN, alpha))


def draw_card(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], title: str,
              body: str, accent: tuple[int, int, int], regular: ImageFont.FreeTypeFont,
              alpha: float) -> None:
    draw.rounded_rectangle(box, radius=18, fill=rgba((17, 27, 49), 0.80 * alpha), outline=rgba(accent, 0.46 * alpha), width=1)
    x, y, _, _ = box
    draw.ellipse((x + 18, y + 22, x + 38, y + 42), fill=rgba(accent, 0.92 * alpha))
    draw.text((x + 53, y + 16), title, font=font(regular.path, 18), fill=rgba(TEXT, alpha))
    draw.text((x + 53, y + 43), body, font=font(regular.path, 13), fill=rgba(MUTED, 0.92 * alpha))


def render_frame(background: Image.Image, regular: ImageFont.FreeTypeFont,
                 bold: ImageFont.FreeTypeFont, frame: int) -> Image.Image:
    t = frame / FPS
    image = background.copy()
    add_stars(image, t)
    draw = ImageDraw.Draw(image, "RGBA")
    draw_brand(draw, regular, t)

    if t < 4:
        alpha = fade_for(t, 0, 4)
        add_glow(image, 746 + math.sin(t * 0.35) * 32, 276 + math.cos(t * 0.28) * 24, CYAN, 130, 0.42)
        draw.ellipse((646, 170, 846, 370), outline=rgba(CYAN, 0.30 * alpha), width=1)
        draw.ellipse((683, 207, 809, 333), outline=rgba(PINK, 0.34 * alpha), width=1)
        draw_network(image, t, 0.20 * alpha)
        draw.text((54, 164), "01  MESH CONNECTION", font=font(regular.path, 13), fill=rgba(CYAN, alpha))
        draw.text((52, 205), "Связь не обязана", font=bold, fill=rgba(TEXT, alpha))
        draw.text((52, 270), "быть рядом.", font=bold, fill=rgba(CYAN, alpha))
        draw.text((55, 358), "MeshGram находит маршрут сам.", font=font(regular.path, 21), fill=rgba(MUTED, alpha))
    elif t < 8:
        alpha = fade_for(t, 4, 8)
        draw.text((54, 145), "02  ROUTE ENGINE", font=font(regular.path, 13), fill=rgba(PINK, alpha))
        draw.text((52, 184), "Сначала BLE.", font=bold, fill=rgba(TEXT, alpha))
        draw.text((52, 246), "Потом резерв.", font=bold, fill=rgba(PINK, alpha))
        draw.text((55, 333), "Пакет движется по доступному пути", font=font(regular.path, 18), fill=rgba(MUTED, alpha))
        draw.text((55, 365), "между участниками MeshGram.", font=font(regular.path, 18), fill=rgba(MUTED, alpha))
        draw_network(image, t, alpha)
        draw.text((76, 420), "BLE", font=font(regular.path, 12), fill=rgba(CYAN, alpha))
        draw.text((170, 420), "encrypted relay", font=font(regular.path, 12), fill=rgba(PINK, alpha))
    elif t < 12:
        alpha = fade_for(t, 8, 12)
        draw.text((54, 145), "03  RECIPIENT-FIRST", font=font(regular.path, 13), fill=rgba(LIME, alpha))
        draw.text((52, 184), "Только адресат", font=bold, fill=rgba(TEXT, alpha))
        draw.text((52, 246), "читает сообщение.", font=bold, fill=rgba(LIME, alpha))
        draw_card(draw, (54, 345, 390, 438), "Зашифрованный пакет", "содержимое скрыто от маршрута", CYAN, regular, alpha)
        draw_network(image, t, 0.74 * alpha)
        draw.rounded_rectangle((500, 395, 820, 448), radius=13, fill=rgba((20, 37, 59), 0.88 * alpha), outline=rgba(CYAN, 0.46 * alpha), width=1)
        draw.text((523, 412), "доставлено адресату", font=font(regular.path, 14), fill=rgba(TEXT, alpha))
    else:
        alpha = fade_for(t, 12, 16)
        add_glow(image, 750, 300, CYAN, 160, 0.44)
        add_glow(image, 290, 392, PINK, 180, 0.34)
        draw.text((54, 154), "04  JOIN THE NETWORK", font=font(regular.path, 13), fill=rgba(CYAN, alpha))
        draw.text((52, 195), "Mesh", font=bold, fill=rgba(TEXT, alpha))
        brand_box = draw.textbbox((52, 195), "Mesh", font=bold)
        draw.text((brand_box[2] + 5, 195), "Gram", font=bold, fill=rgba(CYAN, alpha))
        draw.text((55, 285), "Скачай приложение и присоединяйся", font=font(regular.path, 21), fill=rgba(TEXT, alpha))
        draw.text((55, 319), "к связи нового поколения.", font=font(regular.path, 21), fill=rgba(MUTED, alpha))
        draw.rounded_rectangle((55, 390, 420, 438), radius=14, fill=rgba(CYAN, 0.88 * alpha))
        draw.text((79, 405), "who2215.github.io/MeshGram", font=font(regular.path, 14), fill=rgba((4, 19, 28), alpha))
        draw_network(image, t, 0.45 * alpha)
    return image


def find_ffmpeg(value: str | None) -> str:
    candidates = [value, os.environ.get("FFMPEG_EXE"), shutil.which("ffmpeg"), r"H:\mesh-workspace\tools\ffmpeg\ffmpeg-n8.0.1-66-g27b8d1a017-win64-gpl-8.0\bin\ffmpeg.exe"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise SystemExit("FFmpeg was not found; pass --ffmpeg with its executable path")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--ffmpeg")
    parser.add_argument("--output", type=Path, default=root / "site/assets/meshgram-teaser.mp4")
    parser.add_argument("--poster", type=Path, default=root / "site/assets/meshgram-teaser-poster.png")
    parser.add_argument("--font", type=Path, default=root / "site/assets/Manrope.ttf")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    ffmpeg = find_ffmpeg(args.ffmpeg)
    regular = font(args.font, 28)
    bold = font(args.font, 58)
    background = base_background()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.poster.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}",
        "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "medium",
        "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame in range(FPS * DURATION):
            rendered = render_frame(background, regular, bold, frame)
            if frame == 0:
                rendered.convert("RGB").save(args.poster, format="PNG", optimize=True)
            process.stdin.write(rendered.convert("RGB").tobytes())
        process.stdin.close()
        stderr = process.stderr.read().decode("utf-8", errors="replace")
        return_code = process.wait()
    except Exception:
        process.kill()
        process.wait()
        raise
    if return_code != 0:
        raise SystemExit(f"FFmpeg failed:\n{stderr[-4000:]}")
    print(f"Created {args.output} ({args.output.stat().st_size} bytes)")
    print(f"Created {args.poster} ({args.poster.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
