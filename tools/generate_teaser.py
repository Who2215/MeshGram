#!/usr/bin/env python3
"""Render a realistic MeshGram ad using a phone-in-hands reference scene."""

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
CYAN = (73, 235, 255)
PINK = (244, 91, 216)
LIME = (180, 255, 215)
TEXT = (248, 249, 255)
MUTED = (168, 178, 202)
MESSAGE = "Wi-Fi ушёл за хлебом. MeshGram донёс :)"
MESSAGE_LINES = ("Wi-Fi ушёл за хлебом.", "MeshGram донёс :)")
FONT_FALLBACK = r"H:\mesh-workspace\github-meshgram\site\assets\Manrope.ttf"


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def smooth(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def rgba(color: tuple[int, int, int], alpha: float) -> tuple[int, int, int, int]:
    return (*color, int(clamp(alpha) * 255))


def fade(t: float, start: float, end: float, edge: float = 0.45) -> float:
    return clamp(min((t - start) / edge, (end - t) / edge))


def fnt(path: str | Path, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), size=size)


def draw_text(draw: ImageDraw.ImageDraw, xy: tuple[float, float], value: str,
              face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int],
              anchor: str | None = None) -> None:
    draw.text(xy, value, font=face, fill=fill, anchor=anchor)


def center(draw: ImageDraw.ImageDraw, x: float, y: float, value: str,
           face: ImageFont.FreeTypeFont, fill: tuple[int, int, int, int]) -> None:
    draw_text(draw, (x, y), value, face, fill, "mm")


def panel(draw: ImageDraw.ImageDraw, box: tuple[float, float, float, float],
          fill: tuple[int, int, int, int], outline: tuple[int, int, int, int],
          radius: int = 16, width: int = 1) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def glow(image: Image.Image, x: float, y: float, color: tuple[int, int, int],
         radius: float, strength: float = 1.0) -> None:
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                 fill=rgba(color, 0.22 * strength))
    draw.ellipse((x - radius * 0.38, y - radius * 0.38,
                  x + radius * 0.38, y + radius * 0.38),
                 fill=rgba(color, 0.48 * strength))
    image.alpha_composite(layer.filter(ImageFilter.GaussianBlur(max(2, int(radius * 0.24)))))


def fit_scene(path: Path) -> Image.Image:
    source = Image.open(path).convert("RGBA")
    scale = max(WIDTH / source.width, HEIGHT / source.height)
    resized = source.resize((int(source.width * scale), int(source.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (WIDTH, HEIGHT), (3, 5, 16, 255))
    left = (resized.width - WIDTH) // 2
    top = (resized.height - HEIGHT) // 2
    canvas.alpha_composite(resized.crop((left, top, left + WIDTH, top + HEIGHT)))
    return canvas


def draw_light_motion(image: Image.Image, t: float) -> None:
    # Light pulses and moving dust sell the footage as a live camera scene.
    for index, (x, y, color, radius) in enumerate([
        (195, 165, CYAN, 78),
        (1072, 170, PINK, 100),
        (636, 615, CYAN, 110),
        (640, 170, PINK, 72),
    ]):
        px = x + math.sin(t * 0.35 + index) * 19
        py = y + math.cos(t * 0.27 + index * 1.4) * 14
        glow(image, px, py, color, radius, 0.17 + 0.06 * math.sin(t * 0.5 + index))

    dust = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    draw = ImageDraw.Draw(dust, "RGBA")
    for index in range(42):
        phase = index * 1.47
        x = (index * 173 + 42 + math.sin(t * 0.12 + phase) * 8) % WIDTH
        y = (index * 97 + 35 + math.cos(t * 0.10 + phase) * 6) % HEIGHT
        pulse = 0.18 + 0.22 * (0.5 + 0.5 * math.sin(t * 0.46 + phase))
        radius = 0.7 + (index % 3) * 0.45
        draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                     fill=rgba(TEXT if index % 5 else CYAN, pulse))
    image.alpha_composite(dust)


def solve(matrix: list[list[float]], values: list[float]) -> list[float]:
    size = len(values)
    augmented = [row[:] + [values[index]] for index, row in enumerate(matrix)]
    for column in range(size):
        pivot = max(range(column, size), key=lambda row: abs(augmented[row][column]))
        augmented[column], augmented[pivot] = augmented[pivot], augmented[column]
        divisor = augmented[column][column]
        if abs(divisor) < 1e-10:
            raise ValueError("Degenerate perspective transform")
        for item in range(column, size + 1):
            augmented[column][item] /= divisor
        for row in range(size):
            if row == column:
                continue
            factor = augmented[row][column]
            for item in range(column, size + 1):
                augmented[row][item] -= factor * augmented[column][item]
    return [augmented[index][size] for index in range(size)]


def perspective_coefficients(source_size: tuple[int, int],
                             quad: list[tuple[float, float]]) -> list[float]:
    width, height = source_size
    source_points = [(0, 0), (width, 0), (width, height), (0, height)]
    matrix: list[list[float]] = []
    values: list[float] = []
    for (u, v), (x, y) in zip(source_points, quad):
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        values.append(float(u))
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        values.append(float(v))
    return solve(matrix, values)


def project_screen(base: Image.Image, screen: Image.Image,
                   quad: list[tuple[float, float]]) -> None:
    coeffs = perspective_coefficients(screen.size, quad)
    warped = screen.transform(base.size, Image.Transform.PERSPECTIVE, coeffs,
                              resample=Image.Resampling.BICUBIC)
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).polygon(quad, fill=255)
    warped.putalpha(mask)
    base.alpha_composite(warped)


def draw_avatar(screen: Image.Image, x: int, y: int, radius: int,
                letter: str, color: tuple[int, int, int],
                face: ImageFont.FreeTypeFont) -> None:
    glow(screen, x, y, color, radius * 2.0, 0.20)
    draw = ImageDraw.Draw(screen, "RGBA")
    draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                 fill=rgba(color, 0.94), outline=rgba(CYAN, 0.86), width=2)
    center(draw, x, y + 1, letter, face, rgba(TEXT, 0.98))


def screen_panel(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int],
                 fill: tuple[int, int, int, int],
                 outline: tuple[int, int, int, int], radius: int = 14) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=2)


def make_phone_screen(font_path: str, width: int, height: int, t: float,
                      *, sender: bool) -> Image.Image:
    screen = Image.new("RGBA", (width, height), (7, 14, 29, 255))
    draw = ImageDraw.Draw(screen, "RGBA")
    regular = fnt(font_path, max(14, int(width * 0.056)))
    tiny = fnt(font_path, max(10, int(width * 0.040)))
    micro = fnt(font_path, max(9, int(width * 0.032)))
    message_face = fnt(font_path, max(11, int(width * 0.042)))
    accent = CYAN if sender else PINK
    person = "Алекс" if sender else "Роман"
    handle = "@alex" if sender else "@roman"

    draw.rectangle((0, 0, width, int(height * 0.065)), fill=rgba((4, 8, 19), 0.98))
    draw_text(draw, (int(width * 0.06), int(height * 0.026)), "9:41", micro, rgba(TEXT, 0.92))
    draw_text(draw, (int(width * 0.73), int(height * 0.026)), "▴▴  ▰", micro, rgba(TEXT, 0.76))
    header_y = int(height * 0.105)
    draw.line((int(width * 0.04), header_y + int(height * 0.075),
               int(width * 0.96), header_y + int(height * 0.075)),
              fill=rgba((111, 140, 181), 0.42), width=1)
    draw_avatar(screen, int(width * 0.13), header_y + int(height * 0.015),
                int(width * 0.065), person[0], accent, tiny)
    draw_text(draw, (int(width * 0.23), header_y - int(height * 0.014)), person, tiny, rgba(TEXT, 0.98))
    status = "в сети" if sender else ("получено" if t >= 12.4 else "ожидает")
    draw_text(draw, (int(width * 0.23), header_y + int(height * 0.022)), f"{handle}  •  {status}",
              micro, rgba(LIME if sender or t >= 12.4 else MUTED, 0.9))

    content_y = int(height * 0.20)
    if sender:
        typed_progress = smooth(clamp((t - 1.6) / 3.0))
        typed_len = int(len(MESSAGE) * typed_progress)
        typed = MESSAGE[:typed_len]
        if t < 5.5:
            if typed:
                bubble = (int(width * 0.26), content_y, int(width * 0.94), content_y + int(height * 0.105))
                screen_panel(draw, bubble, rgba(accent, 0.34), rgba(accent, 0.88))
                draw_text(draw, (bubble[0] + int(width * 0.04), bubble[1] + int(height * 0.022)),
                          typed, micro, rgba(TEXT, 0.98))
                draw_text(draw, (bubble[2] - int(width * 0.06), bubble[3] - int(height * 0.033)),
                          "|", micro, rgba(CYAN, 0.94))
        else:
            bubble = (int(width * 0.19), content_y, int(width * 0.94), content_y + int(height * 0.105))
            screen_panel(draw, bubble, rgba((33, 89, 119), 0.94), rgba(CYAN, 0.82))
            draw.multiline_text((bubble[0] + int(width * 0.04), bubble[1] + int(height * 0.016)),
                                "\n".join(MESSAGE_LINES), font=message_face,
                                fill=rgba(TEXT, 0.98), spacing=max(2, int(height * 0.008)))
            draw_text(draw, (bubble[2] - int(width * 0.16), bubble[3] - int(height * 0.033)),
                      "✓✓" if t >= 12.3 else "✓", micro, rgba(CYAN, 0.96))
    else:
        if t >= 12.1:
            progress = smooth(clamp((t - 12.1) / 0.9))
            visible = MESSAGE[:int(len(MESSAGE) * progress)]
            bubble = (int(width * 0.06), content_y, int(width * 0.84), content_y + int(height * 0.14))
            screen_panel(draw, bubble, rgba((57, 39, 81), 0.96), rgba(PINK, 0.90))
            if progress >= 0.98:
                draw.multiline_text((bubble[0] + int(width * 0.04), bubble[1] + int(height * 0.016)),
                                    "\n".join(MESSAGE_LINES), font=message_face,
                                    fill=rgba(TEXT, 0.98), spacing=max(2, int(height * 0.008)))
            else:
                draw_text(draw, (bubble[0] + int(width * 0.04), bubble[1] + int(height * 0.022)),
                          visible, micro, rgba(TEXT, 0.98))
            draw_text(draw, (bubble[0] + int(width * 0.04), bubble[3] - int(height * 0.037)),
                      "доставлено  •  сейчас", micro, rgba(LIME, 0.90))

    if sender and t < 6.2:
        keyboard_y = int(height * 0.70)
        draw.line((int(width * 0.04), keyboard_y, int(width * 0.96), keyboard_y),
                  fill=rgba((105, 127, 163), 0.38), width=1)
        input_box = (int(width * 0.05), keyboard_y - int(height * 0.095),
                     int(width * 0.95), keyboard_y - int(height * 0.015))
        screen_panel(draw, input_box, rgba((23, 34, 59), 0.98), rgba((100, 124, 164), 0.62), 12)
        typed = MESSAGE[:int(len(MESSAGE) * smooth(clamp((t - 1.6) / 3.0)))]
        draw_text(draw, (input_box[0] + int(width * 0.05), input_box[1] + int(height * 0.022)),
                  typed or "Сообщение", micro, rgba(TEXT if typed else MUTED, 0.9))
        draw.ellipse((input_box[2] - int(width * 0.13), input_box[1] + int(height * 0.010),
                      input_box[2] - int(width * 0.03), input_box[1] + int(height * 0.090)),
                     fill=rgba(accent, 0.96))
        draw.polygon(((input_box[2] - int(width * 0.10), input_box[1] + int(height * 0.025)),
                      (input_box[2] - int(width * 0.055), input_box[1] + int(height * 0.050)),
                      (input_box[2] - int(width * 0.10), input_box[1] + int(height * 0.075))),
                     fill=rgba((4, 15, 28), 0.98))
        keys = ("QWERTYUI", "ASDFGHJK", "ZXCVBNM")
        for row, labels in enumerate(keys):
            y = keyboard_y + int(height * 0.040) + row * int(height * 0.060)
            key_w = int(width * 0.105)
            gap = int(width * 0.016)
            start = int((width - (len(labels) * key_w + (len(labels) - 1) * gap)) / 2)
            for index, label in enumerate(labels):
                x = start + index * (key_w + gap)
                screen_panel(draw, (x, y, x + key_w, y + int(height * 0.045)),
                             rgba((30, 43, 70), 0.98), rgba((106, 126, 165), 0.48), 5)
                center(draw, x + key_w / 2, y + int(height * 0.022), label, micro, rgba(TEXT, 0.82))
    else:
        footer_y = int(height * 0.85)
        center(draw, width / 2, footer_y, "MeshGram  •  BLE relay", micro, rgba(MUTED, 0.80))
    return screen


ROUTE = [(566, 378), (610, 282), (654, 414), (698, 296), (734, 378)]


def route_point(progress: float) -> tuple[float, float]:
    distance = clamp(progress) * (len(ROUTE) - 1)
    segment = min(int(distance), len(ROUTE) - 2)
    local = smooth(distance - segment)
    x1, y1 = ROUTE[segment]
    x2, y2 = ROUTE[segment + 1]
    return x1 + (x2 - x1) * local, y1 + (y2 - y1) * local


def draw_mini_device(image: Image.Image, x: float, y: float,
                     label: str, color: tuple[int, int, int],
                     small: ImageFont.FreeTypeFont, alpha: float) -> None:
    draw = ImageDraw.Draw(image, "RGBA")
    glow(image, x, y, color, 38, 0.48 * alpha)
    draw.rounded_rectangle((x - 14, y - 25, x + 14, y + 25), radius=6,
                           fill=rgba((10, 19, 38), 0.96 * alpha),
                           outline=rgba(color, 0.92 * alpha), width=2)
    draw.rounded_rectangle((x - 9, y - 16, x + 9, y + 12), radius=2,
                           fill=rgba(color, 0.22 * alpha),
                           outline=rgba((191, 233, 255), 0.55 * alpha), width=1)
    draw.ellipse((x - 2, y + 17, x + 2, y + 21), fill=rgba(TEXT, 0.78 * alpha))
    center(draw, x, y + 39, label, small, rgba(TEXT, 0.88 * alpha))


def draw_route(image: Image.Image, t: float, small: ImageFont.FreeTypeFont) -> None:
    alpha = smooth(clamp((t - 5.3) / 0.9)) * fade(t, 5.1, 15.8, 0.6)
    if alpha <= 0:
        return
    draw = ImageDraw.Draw(image, "RGBA")
    for index in range(len(ROUTE) - 1):
        x1, y1 = ROUTE[index]
        x2, y2 = ROUTE[index + 1]
        draw.line((x1, y1, x2, y2), fill=rgba((139, 190, 226), 0.58 * alpha), width=2)
    progress = smooth(clamp((t - 6.2) / 5.2))
    for index in range(len(ROUTE) - 1):
        segment_start = index / (len(ROUTE) - 1)
        if progress <= segment_start:
            continue
        amount = clamp((progress - segment_start) * (len(ROUTE) - 1))
        x1, y1 = ROUTE[index]
        x2, y2 = ROUTE[index + 1]
        draw.line((x1, y1, x1 + (x2 - x1) * amount, y1 + (y2 - y1) * amount),
                  fill=rgba(CYAN, 0.95 * alpha), width=5)
    for index, (x, y) in enumerate(ROUTE):
        if index in (0, len(ROUTE) - 1):
            continue
        draw_mini_device(image, x, y, "MeshGram", PINK if index == 2 else CYAN, small, alpha)
    if 0 < progress < 1:
        x, y = route_point(progress)
        glow(image, x, y, CYAN, 48, 0.90 * alpha)
        draw.rounded_rectangle((x - 48, y - 17, x + 48, y + 17), radius=12,
                               fill=rgba((7, 35, 56), 0.98 * alpha),
                               outline=rgba(CYAN, 0.98 * alpha), width=2)
        center(draw, x, y - 1, "E2E  •  1.2 KB", small, rgba(TEXT, alpha))


def draw_hud(image: Image.Image, t: float, regular: ImageFont.FreeTypeFont,
             small: ImageFont.FreeTypeFont) -> None:
    draw = ImageDraw.Draw(image, "RGBA")
    alpha = fade(t, 0.0, DURATION, 0.5)
    panel(draw, (382, 55, 898, 104), rgba((5, 10, 24), 0.76 * alpha),
          rgba(CYAN, 0.55 * alpha), 17, 1)
    center(draw, 640, 75, "MeshGram  •  сообщение Алекс → Роман", small, rgba(TEXT, alpha))
    step = "НАБОР" if t < 5.3 else "ШИФРОВАНИЕ" if t < 6.4 else "BLE-ПРЫЖКИ" if t < 12.1 else "ДОСТАВЛЕНО"
    color = LIME if step == "ДОСТАВЛЕНО" else CYAN
    center(draw, 640, 93, step, small, rgba(color, alpha))


def draw_caption(image: Image.Image, t: float, regular: ImageFont.FreeTypeFont,
                 small: ImageFont.FreeTypeFont) -> None:
    draw = ImageDraw.Draw(image, "RGBA")
    if t < 5.3:
        value = "Алекс пишет Роману"
    elif t < 12.1:
        value = "Пакет прыгает только через MeshGram"
    elif t < 14.7:
        value = "Роман получил то же сообщение"
    else:
        value = "Мем доставлен Роману"
    alpha = smooth(clamp((t % 0.7) / 0.25)) if t < 0.7 else 1.0
    panel(draw, (420, 640, 860, 684), rgba((6, 12, 28), 0.80 * alpha),
          rgba(PINK if t >= 14.7 else CYAN, 0.72 * alpha), 15, 1)
    center(draw, 640, 662, value, regular, rgba(TEXT, alpha))


def render_frame(scene: Image.Image, font_path: str, frame: int) -> Image.Image:
    t = frame / FPS
    image = scene.copy()
    draw_light_motion(image, t)
    draw = ImageDraw.Draw(image, "RGBA")
    regular = fnt(font_path, 22)
    small = fnt(font_path, 14)
    left_quad = [(309, 87), (588, 87), (582, 620), (319, 620)]
    right_quad = [(710, 87), (1022, 88), (1025, 620), (719, 620)]
    left_screen = make_phone_screen(font_path, 330, 690, t, sender=True)
    right_screen = make_phone_screen(font_path, 330, 690, t, sender=False)
    project_screen(image, left_screen, left_quad)
    project_screen(image, right_screen, right_quad)
    draw_hud(image, t, regular, small)
    draw_route(image, t, small)
    draw_caption(image, t, regular, small)
    return image


def find_ffmpeg(value: str | None) -> str:
    candidates = [value, os.environ.get("FFMPEG_EXE"), shutil.which("ffmpeg"),
                  r"H:\mesh-workspace\tools\ffmpeg\ffmpeg-n8.0.1-66-g27b8d1a017-win64-gpl-8.0\bin\ffmpeg.exe"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise SystemExit("FFmpeg was not found; pass --ffmpeg with its executable path")


def parser() -> argparse.ArgumentParser:
    root = Path(__file__).resolve().parents[1]
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--ffmpeg")
    result.add_argument("--output", type=Path, default=root / "site/assets/meshgram-teaser.mp4")
    result.add_argument("--poster", type=Path, default=root / "site/assets/meshgram-teaser-poster.png")
    result.add_argument("--font", type=Path, default=root / "site/assets/Manrope.ttf")
    result.add_argument("--scene", type=Path, default=root / "site/assets/meshgram-realistic-scene.jpg")
    return result


def main() -> int:
    args = parser().parse_args()
    ffmpeg = find_ffmpeg(args.ffmpeg)
    font_path = str(args.font if args.font.is_file() else FONT_FALLBACK)
    scene = fit_scene(args.scene)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.poster.parent.mkdir(parents=True, exist_ok=True)
    command = [
        ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}",
        "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264", "-preset", "medium",
        "-crf", "21", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                               stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame in range(FPS * DURATION):
            rendered = render_frame(scene, font_path, frame)
            if frame == int(FPS * 15.4):
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
