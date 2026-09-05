#!/usr/bin/env python3
"""Render a realistic, playful MeshGram message-routing teaser."""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


WIDTH = 1280
HEIGHT = 720
FPS = 20
DURATION = 18

BG = (7, 11, 29)
PANEL = (18, 26, 49)
CYAN = (72, 235, 255)
PINK = (242, 91, 215)
PURPLE = (157, 88, 255)
LIME = (185, 255, 220)
TEXT = (244, 247, 255)
MUTED = (154, 169, 199)
MESSAGE = "Я уже рядом. Повернись :)"
FONT_PATH = r"H:\mesh-workspace\github-meshgram\site\assets\Manrope.ttf"


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def smooth(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def rgba(color: tuple[int, int, int], alpha: float) -> tuple[int, int, int, int]:
    return (*color, int(clamp(alpha) * 255))


def fade(t: float, start: float, end: float, edge: float = 0.45) -> float:
    return clamp(min((t - start) / edge, (end - t) / edge))


def load_font(path: str | Path, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), size=size)


def draw_text(draw: ImageDraw.ImageDraw, xy: tuple[float, float], value: str,
              face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int],
              anchor: str | None = None) -> None:
    draw.text(xy, value, font=face, fill=fill, anchor=anchor)


def center_text(draw: ImageDraw.ImageDraw, x: float, y: float, value: str,
                face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int]) -> None:
    draw_text(draw, (x, y), value, face, fill, "mm")


def glow(image: Image.Image, x: float, y: float, color: tuple[int, int, int],
         radius: float, strength: float = 1.0) -> None:
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                 fill=rgba(color, 0.22 * strength))
    draw.ellipse((x - radius * 0.42, y - radius * 0.42,
                  x + radius * 0.42, y + radius * 0.42),
                 fill=rgba(color, 0.50 * strength))
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(max(3, int(radius * 0.30)))))


def make_background() -> Image.Image:
    image = Image.new("RGBA", (WIDTH, HEIGHT))
    pixels = image.load()
    for y in range(HEIGHT):
        vertical = y / HEIGHT
        for x in range(WIDTH):
            horizontal = x / WIDTH
            pixels[x, y] = (
                int(5 + 8 * vertical + 6 * horizontal),
                int(9 + 8 * vertical + 5 * (1 - horizontal)),
                int(24 + 27 * (1 - vertical) + 17 * horizontal),
                255,
            )
    return image


def draw_ambient(image: Image.Image, t: float) -> None:
    # Slowly moving color clouds keep the scene alive without fast flashing.
    for index, (base_x, base_y, color, radius) in enumerate([
        (185, 610, PINK, 250),
        (1080, 180, CYAN, 270),
        (655, 650, PURPLE, 230),
    ]):
        x = base_x + math.sin(t * 0.16 + index * 2.2) * 38
        y = base_y + math.cos(t * 0.13 + index) * 32
        glow(image, x, y, color, radius, 0.62)

    stars = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(stars, "RGBA")
    for index in range(84):
        phase = index * 1.79
        x = (index * 151 + 37) % WIDTH + math.sin(t * 0.08 + phase) * 10
        y = (index * 83 + 29) % HEIGHT + math.cos(t * 0.06 + phase) * 7
        pulse = 0.32 + 0.28 * (0.5 + 0.5 * math.sin(t * 0.42 + phase))
        radius = 0.8 + (index % 4) * 0.28
        color = CYAN if index % 9 == 0 else PINK if index % 13 == 0 else TEXT
        draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                     fill=rgba(color, pulse))
    image.alpha_composite(stars.filter(ImageFilter.GaussianBlur(0.25)))


def draw_brand(draw: ImageDraw.ImageDraw, regular: ImageFont.FreeTypeFont,
               small: ImageFont.FreeTypeFont) -> None:
    draw.ellipse((42, 32, 78, 68), fill=rgba((25, 34, 62), 0.95),
                 outline=rgba(CYAN, 0.8), width=2)
    for x, y in ((60, 40), (49, 58), (71, 58), (60, 52)):
        draw.line((60, 50, x, y), fill=rgba(CYAN, 0.82), width=2)
        draw.ellipse((x - 3, y - 3, x + 3, y + 3),
                     fill=rgba(PINK if x > 60 else CYAN, 0.95))
    draw_text(draw, (92, 34), "Mesh", regular, rgba(TEXT, 0.98))
    draw_text(draw, (178, 34), "Gram", regular, rgba(CYAN, 0.98))
    draw_text(draw, (92, 71), "BLE-FIRST  /  MESSAGE ROUTING", small, rgba(MUTED, 0.82))
    draw.ellipse((1150, 43, 1158, 51), fill=rgba(LIME, 0.95))
    draw_text(draw, (1170, 38), "LIVE DEMO", small, rgba(LIME, 0.9))


def panel(draw: ImageDraw.ImageDraw, box: tuple[float, float, float, float],
          fill: tuple[int, int, int, int], outline: tuple[int, int, int, int],
          radius: float = 18, width: int = 1) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def avatar(image: Image.Image, center: tuple[float, float], radius: float,
           letter: str, color: tuple[int, int, int], face: ImageFont.FreeTypeFont) -> None:
    x, y = center
    glow(image, x, y, color, radius * 1.7, 0.40)
    draw = ImageDraw.Draw(image, "RGBA")
    draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                 fill=rgba(color, 0.94), outline=rgba(CYAN, 0.88), width=max(1, int(radius * 0.06)))
    center_text(draw, x, y + 1, letter, face, rgba(TEXT, 0.98))


def draw_phone(image: Image.Image, box: tuple[int, int, int, int],
               regular: ImageFont.FreeTypeFont, tiny: ImageFont.FreeTypeFont,
               *, person: str, handle: str, outgoing: str | None,
               incoming: str | None, typed: str, online: bool,
               accent: tuple[int, int, int], show_keyboard: bool,
               delivery: str | None = None) -> None:
    x, y, w, h = box
    draw = ImageDraw.Draw(image, "RGBA")
    panel(draw, (x - 8, y - 8, x + w + 8, y + h + 8), rgba((3, 5, 15), 0.96),
          rgba(accent, 0.64), 28, 2)
    panel(draw, (x, y, x + w, y + h), rgba((13, 18, 35), 0.98),
          rgba((100, 127, 165), 0.7), 22, 1)
    draw.rounded_rectangle((x + w * 0.35, y + 8, x + w * 0.65, y + 26),
                           radius=10, fill=rgba((3, 5, 12), 0.96))
    draw.line((x + w * 0.12, y + 38, x + w * 0.38, y + 38), fill=rgba(TEXT, 0.18), width=2)

    scale = w / 300.0
    left = x + int(16 * scale)
    top = y + int(42 * scale)
    draw_text(draw, (left, top), "9:41", tiny, rgba(TEXT, 0.92))
    draw_text(draw, (x + w - int(62 * scale), top), "▴▴  ▰", tiny, rgba(TEXT, 0.75))

    header_y = y + int(70 * scale)
    draw.line((x + 12, header_y + int(39 * scale), x + w - 12, header_y + int(39 * scale)),
              fill=rgba((91, 113, 155), 0.35), width=1)
    avatar(image, (left + int(18 * scale), header_y + int(14 * scale)),
           int(16 * scale), person[0].upper(), accent, tiny)
    draw_text(draw, (left + int(42 * scale), header_y + int(1 * scale)), person,
              tiny, rgba(TEXT, 0.98))
    draw_text(draw, (left + int(42 * scale), header_y + int(19 * scale)), handle,
              tiny, rgba(LIME if online else MUTED, 0.9))

    content_top = header_y + int(54 * scale)
    if outgoing:
        bubble_w = int(min(w * 0.82, 250 * scale))
        bubble_h = int(48 * scale)
        bx = x + w - bubble_w - int(13 * scale)
        by = content_top + int(30 * scale)
        panel(draw, (bx, by, bx + bubble_w, by + bubble_h), rgba((40, 79, 108), 0.92),
              rgba(accent, 0.62), 13, 1)
        draw_text(draw, (bx + int(13 * scale), by + int(10 * scale)), outgoing,
                  tiny, rgba(TEXT, 0.98))
        if delivery:
            draw_text(draw, (bx + bubble_w - int(38 * scale), by + bubble_h - int(16 * scale)),
                      delivery, tiny, rgba(CYAN, 0.96))
    elif typed:
        bubble_w = int(min(w * 0.82, 250 * scale))
        bubble_h = int(48 * scale)
        bx = x + w - bubble_w - int(13 * scale)
        by = content_top + int(30 * scale)
        panel(draw, (bx, by, bx + bubble_w, by + bubble_h), rgba(accent, 0.38),
              rgba(accent, 0.80), 13, 1)
        draw_text(draw, (bx + int(13 * scale), by + int(10 * scale)), typed,
                  tiny, rgba(TEXT, 0.98))
        draw_text(draw, (bx + bubble_w - int(17 * scale), by + bubble_h - int(15 * scale)),
                  "|", tiny, rgba(CYAN, 0.9))
    if incoming:
        bubble_w = int(min(w * 0.86, 270 * scale))
        bubble_h = int(60 * scale)
        bx = x + int(13 * scale)
        by = content_top + int(30 * scale)
        panel(draw, (bx, by, bx + bubble_w, by + bubble_h), rgba((40, 38, 68), 0.95),
              rgba(PINK, 0.75), 13, 1)
        draw_text(draw, (bx + int(13 * scale), by + int(9 * scale)), incoming,
                  tiny, rgba(TEXT, 0.98))
        draw_text(draw, (bx + int(13 * scale), by + bubble_h - int(16 * scale)),
                  "доставлено  •  сейчас", tiny, rgba(LIME, 0.84))

    if show_keyboard:
        keyboard_top = y + h - int(160 * scale)
        draw.line((x + 10, keyboard_top, x + w - 10, keyboard_top),
                  fill=rgba((91, 113, 155), 0.38), width=1)
        for row_index, labels in enumerate((
            ("Q", "W", "E", "R", "T", "Y", "U"),
            ("A", "S", "D", "F", "G", "H", "J"),
            ("Z", "X", "C", "V", "B", "N", "M"),
        )):
            key_y = keyboard_top + int(18 * scale) + row_index * int(34 * scale)
            key_w = int(31 * scale)
            gap = int(5 * scale)
            start_x = x + int((w - (len(labels) * key_w + (len(labels) - 1) * gap)) / 2)
            for col, label in enumerate(labels):
                key_x = start_x + col * (key_w + gap)
                panel(draw, (key_x, key_y, key_x + key_w, key_y + int(25 * scale)),
                      rgba((33, 42, 67), 0.97), rgba((92, 110, 150), 0.45), 5, 1)
                center_text(draw, key_x + key_w / 2, key_y + int(12 * scale), label,
                            tiny, rgba(TEXT, 0.78))
        input_y = keyboard_top - int(43 * scale)
        panel(draw, (x + int(13 * scale), input_y, x + w - int(13 * scale),
                     input_y + int(32 * scale)), rgba((24, 32, 55), 0.98),
              rgba((97, 118, 157), 0.50), 12, 1)
        draw_text(draw, (x + int(26 * scale), input_y + int(7 * scale)),
                  typed or "Сообщение", tiny, rgba(TEXT if typed else MUTED, 0.9))
        draw.ellipse((x + w - int(49 * scale), input_y + int(3 * scale),
                      x + w - int(17 * scale), input_y + int(35 * scale)),
                     fill=rgba(accent, 0.95))
        draw.polygon(((x + w - int(38 * scale), input_y + int(11 * scale)),
                      (x + w - int(23 * scale), input_y + int(18 * scale)),
                      (x + w - int(38 * scale), input_y + int(25 * scale))),
                     fill=rgba((6, 17, 30), 0.96))


NODES = [(492, 365), (590, 250), (686, 412), (777, 285), (870, 365)]
EDGES = [(0, 1), (1, 2), (2, 3), (3, 4), (1, 3)]


def point_on_route(progress: float) -> tuple[float, float]:
    route = [NODES[0], NODES[1], NODES[2], NODES[3], NODES[4]]
    distance = clamp(progress) * (len(route) - 1)
    segment = min(int(distance), len(route) - 2)
    local = smooth(distance - segment)
    x = route[segment][0] + (route[segment + 1][0] - route[segment][0]) * local
    y = route[segment][1] + (route[segment + 1][1] - route[segment][1]) * local
    return x, y


def draw_network(image: Image.Image, t: float, progress: float, alpha: float,
                 tiny: ImageFont.FreeTypeFont) -> None:
    if alpha <= 0:
        return
    draw = ImageDraw.Draw(image, "RGBA")
    for start, end in EDGES:
        x1, y1 = NODES[start]
        x2, y2 = NODES[end]
        draw.line((x1, y1, x2, y2), fill=rgba((111, 139, 182), 0.44 * alpha), width=2)

    route = [NODES[0], NODES[1], NODES[2], NODES[3], NODES[4]]
    for index in range(4):
        segment_start = index / 4
        if progress <= segment_start:
            continue
        amount = clamp((progress - segment_start) * 4)
        x1, y1 = route[index]
        x2, y2 = route[index + 1]
        draw.line((x1, y1, x1 + (x2 - x1) * amount, y1 + (y2 - y1) * amount),
                  fill=rgba(CYAN, 0.92 * alpha), width=4)
    for index, (x, y) in enumerate(NODES):
        pulse = 1.0 + 0.16 * math.sin(t * 0.75 + index * 1.5)
        color = CYAN if index in (0, 4) else PINK if index == 2 else LIME
        glow(image, x, y, color, 36 * pulse, 0.52 * alpha)
        draw.ellipse((x - 11 * pulse, y - 11 * pulse, x + 11 * pulse, y + 11 * pulse),
                     fill=rgba(color, 0.95 * alpha), outline=rgba(TEXT, 0.65 * alpha), width=2)
        draw.ellipse((x - 24, y - 24, x + 24, y + 24),
                     outline=rgba(color, 0.30 * alpha), width=1)
        label = "отправитель" if index == 0 else "адресат" if index == 4 else f"BLE-узел {index}"
        center_text(draw, x, y + 43, label, tiny, rgba(MUTED, 0.9 * alpha))
    if 0 < progress < 1:
        x, y = point_on_route(progress)
        glow(image, x, y, CYAN, 45, 0.92 * alpha)
        panel(draw, (x - 46, y - 18, x + 46, y + 18), rgba((11, 36, 58), 0.97 * alpha),
              rgba(CYAN, 0.96 * alpha), 13, 2)
        center_text(draw, x, y - 1, "E2E  •  1.2 KB", tiny, rgba(TEXT, alpha))


def draw_steps(draw: ImageDraw.ImageDraw, small: ImageFont.FreeTypeFont,
               active: int, alpha: float) -> None:
    labels = ("НАБОР", "ОТПРАВКА", "BLE-МАРШРУТ", "ДОСТАВКА")
    start_x = 450
    for index, label in enumerate(labels):
        x = start_x + index * 190
        color = CYAN if index <= active else (106, 122, 157)
        draw.ellipse((x, 92, x + 12, 104), fill=rgba(color, alpha))
        draw_text(draw, (x + 23, 88), label, small, rgba(color, alpha))
        if index < len(labels) - 1:
            draw.line((x + 92, 98, x + 174, 98),
                      fill=rgba((96, 111, 143), 0.42 * alpha), width=1)


def render_frame(background: Image.Image, regular: ImageFont.FreeTypeFont,
                 small: ImageFont.FreeTypeFont, medium: ImageFont.FreeTypeFont,
                 frame: int) -> Image.Image:
    t = frame / FPS
    image = background.copy()
    draw_ambient(image, t)
    draw = ImageDraw.Draw(image, "RGBA")
    draw_brand(draw, regular, small)
    active_step = 0 if t < 3.6 else 1 if t < 6.2 else 2 if t < 12.0 else 3
    overall = fade(t, 0.0, DURATION, 0.5)
    draw_steps(draw, small, active_step, overall)

    if t < 3.6:
        title = "Напиши. Нажми. MeshGram найдёт путь."
        subtitle = "Сообщение идёт к точному адресату, а не просто «куда-то рядом»."
    elif t < 6.2:
        title = "Сообщение готово к отправке"
        subtitle = "Кнопка отправки превращает его в зашифрованный пакет."
    elif t < 12.0:
        title = "Пакет едет по BLE-маршруту"
        subtitle = "Каждый узел передаёт дальше, пока пакет не увидит адресата."
    else:
        title = "Доставлено. И да, он уже рядом."
        subtitle = "MeshGram: сначала ближайшая связь, затем проверенный резерв."
    draw_text(draw, (52, 112), title, medium, rgba(TEXT, 0.98 * overall))
    draw_text(draw, (52, 145), subtitle, regular, rgba(MUTED, 0.95 * overall))

    typed_progress = smooth(clamp((t - 1.0) / 3.4))
    typed_count = int(len(MESSAGE) * typed_progress) if t < 5.0 else len(MESSAGE)
    typed = MESSAGE[:typed_count]
    sender_outgoing = MESSAGE if t >= 5.0 else None
    delivery = "✓✓" if t >= 12.0 else "✓" if t >= 6.1 else None
    incoming = None
    if t >= 11.3:
        incoming_progress = smooth(clamp((t - 11.3) / 1.0))
        reaction = "ХА-ХА, вижу тебя!"
        incoming = reaction[:int(len(reaction) * incoming_progress)]

    draw_phone(image, (55, 191, 310, 485), regular, small,
               person="Роман", handle="@roman  •  в сети", outgoing=sender_outgoing,
               incoming=None, typed=typed, online=True, accent=CYAN,
               show_keyboard=t < 6.1, delivery=delivery)
    draw_phone(image, (915, 191, 310, 485), regular, small,
               person="Алекс", handle="@alex  •  ожидает", outgoing=None,
               incoming=incoming, typed="", online=False, accent=PINK,
               show_keyboard=False)

    network_alpha = smooth(clamp((t - 5.4) / 1.0)) * fade(t, 5.2, 14.7, 0.7)
    route_progress = smooth(clamp((t - 6.4) / 5.0))
    draw_network(image, t, route_progress, network_alpha, small)
    if 5.3 <= t < 6.4:
        panel(draw, (405, 285, 875, 347), rgba((10, 26, 48), 0.94),
              rgba(CYAN, 0.74), 18, 2)
        center_text(draw, 640, 316, "Шифруем сообщение  •  ищем ближайший маршрут",
                    small, rgba(TEXT, 0.97))
    elif 6.4 <= t < 11.8:
        panel(draw, (405, 285, 875, 347), rgba((10, 26, 48), 0.90),
              rgba(CYAN, 0.56), 18, 2)
        hop = min(4, int(route_progress * 4) + 1)
        center_text(draw, 640, 316, f"Прыжок {hop} из 4  •  BLE relay  •  адресат: Алекс",
                    small, rgba(TEXT, 0.97))
    elif t >= 12.0:
        panel(draw, (405, 285, 875, 347), rgba((10, 38, 40), 0.92),
              rgba(LIME, 0.74), 18, 2)
        center_text(draw, 640, 316, "Доставлено точно адресату  •  маршрут завершён",
                    small, rgba(LIME, 0.97))

    if t >= 14.6:
        end_alpha = smooth(clamp((t - 14.6) / 1.2))
        panel(draw, (412, 525, 868, 595), rgba((15, 23, 47), 0.94 * end_alpha),
              rgba(PINK, 0.72 * end_alpha), 20, 2)
        center_text(draw, 640, 548, "Получатель: обернулся и засмеялся",
                    small, rgba(TEXT, end_alpha))
        center_text(draw, 640, 575, "MeshGram  •  связь без лишних посредников",
                    small, rgba(CYAN, end_alpha))
    return image


def find_ffmpeg(value: str | None) -> str:
    candidates = [value, os.environ.get("FFMPEG_EXE"), shutil.which("ffmpeg"),
                  r"H:\mesh-workspace\tools\ffmpeg\ffmpeg-n8.0.1-66-g27b8d1a017-win64-gpl-8.0\bin\ffmpeg.exe"]
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
    font_path = str(args.font if args.font.is_file() else FONT_PATH)
    regular = load_font(font_path, 24)
    medium = load_font(font_path, 32)
    small = load_font(font_path, 15)
    background = make_background()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.poster.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}",
        "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "medium",
        "-crf", "22", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                               stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame in range(FPS * DURATION):
            rendered = render_frame(background, regular, small, medium, frame)
            if frame == int(FPS * 12.8):
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
