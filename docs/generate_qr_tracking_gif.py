"""Generate docs/qr-tracking.gif — a looping demo of QR detection and tracking."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 720, 420
FPS_DELAY_MS = 70
FRAMES = 56
QR_MODULES = 21
MODULE = 7
QR_PX = QR_MODULES * MODULE


def finder(grid: list[list[int]], ox: int, oy: int) -> None:
    for y in range(7):
        for x in range(7):
            outer = x in (0, 6) or y in (0, 6)
            inner = 2 <= x <= 4 and 2 <= y <= 4
            grid[oy + y][ox + x] = 1 if outer or inner else 0


def make_qr_grid() -> list[list[int]]:
    grid = [[0] * QR_MODULES for _ in range(QR_MODULES)]
    finder(grid, 0, 0)
    finder(grid, QR_MODULES - 7, 0)
    finder(grid, 0, QR_MODULES - 7)
    # Timing patterns
    for i in range(QR_MODULES):
        bit = 1 if i % 2 == 0 else 0
        if grid[6][i] == 0:
            grid[6][i] = bit
        if grid[i][6] == 0:
            grid[i][6] = bit
    # Fake data modules so it reads as a QR, not a blank square
    seed = 0xA5
    for y in range(QR_MODULES):
        for x in range(QR_MODULES):
            in_finder = (
                (x < 8 and y < 8)
                or (x >= QR_MODULES - 8 and y < 8)
                or (x < 8 and y >= QR_MODULES - 8)
                or x == 6
                or y == 6
            )
            if in_finder:
                continue
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            grid[y][x] = 1 if (seed >> 16) & 1 else 0
    return grid


QR_GRID = make_qr_grid()


def qr_bitmap() -> Image.Image:
    img = Image.new("RGBA", (QR_PX, QR_PX), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img)
    for y, row in enumerate(QR_GRID):
        for x, bit in enumerate(row):
            if bit:
                draw.rectangle(
                    [x * MODULE, y * MODULE, (x + 1) * MODULE - 1, (y + 1) * MODULE - 1],
                    fill=(18, 18, 18, 255),
                )
    return img


QR_IMG = qr_bitmap()


def pose(t: float) -> tuple[float, float, float]:
    """Center x, y and rotation (degrees) for a looping path."""
    cx = W * 0.50 + math.sin(t * math.tau) * 118
    cy = H * 0.42 + math.cos(t * math.tau * 2) * 48
    angle = math.sin(t * math.tau) * 28
    return cx, cy, angle


def rotated_corners(cx: float, cy: float, angle_deg: float, pad: float = 10) -> list[tuple[float, float]]:
    rad = math.radians(angle_deg)
    cos_a, sin_a = math.cos(rad), math.sin(rad)
    half = QR_PX / 2 + pad
    local = [(-half, -half), (half, -half), (half, half), (-half, half)]
    out = []
    for x, y in local:
        out.append((cx + x * cos_a - y * sin_a, cy + x * sin_a + y * cos_a))
    return out


def lerp_corners(
    a: list[tuple[float, float]],
    b: list[tuple[float, float]],
    u: float,
) -> list[tuple[float, float]]:
    return [(ax + (bx - ax) * u, ay + (by - ay) * u) for (ax, ay), (bx, by) in zip(a, b)]


def font(size: int) -> ImageFont.ImageFont:
    for name in ("segoeui.ttf", "arial.ttf", "C:\\Windows\\Fonts\\segoeui.ttf", "C:\\Windows\\Fonts\\arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


TITLE = font(18)
BODY = font(16)
SMALL = font(13)


def draw_frame(i: int) -> Image.Image:
    t = i / FRAMES
    img = Image.new("RGB", (W, H), (8, 10, 14))
    draw = ImageDraw.Draw(img)

    # Soft camera grain / vignette
    overlay = Image.new("RGB", (W, H), (22, 28, 36))
    img = Image.blend(img, overlay, 0.35)
    draw = ImageDraw.Draw(img)
    draw.ellipse([40, 8, W - 40, H - 70], outline=(40, 48, 58), width=1)

    cx, cy, angle = pose(t)
    qr = QR_IMG.rotate(-angle, resample=Image.Resampling.BICUBIC, expand=True)
    qx = int(cx - qr.width / 2)
    qy = int(cy - qr.height / 2)
    img.paste(qr, (qx, qy), qr)
    draw = ImageDraw.Draw(img)

    detect_from = 8
    lock_frames = 6
    detected = i >= detect_from
    if detected:
        true_box = rotated_corners(cx, cy, angle)
        # First few frames: box snaps in from an upright lag so tracking is visible
        if i < detect_from + lock_frames:
            u = (i - detect_from) / lock_frames
            prev_cx, prev_cy, prev_ang = pose((i - 3) / FRAMES)
            lagged = rotated_corners(prev_cx, prev_cy, 0, pad=18)
            box = lerp_corners(lagged, true_box, min(1.0, u * u * (3 - 2 * u)))
            width = 3
        else:
            box = true_box
            width = 4
        pts = [(round(x), round(y)) for x, y in box]
        draw.line(pts + [pts[0]], fill=(48, 220, 90), width=width)
        # Corner ticks
        for x, y in pts:
            draw.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(48, 220, 90))

    # Bottom HUD matching the app
    draw.rectangle([0, H - 78, W, H], fill=(12, 14, 18))
    draw.rounded_rectangle([16, H - 70, W - 16, H - 44], radius=6, fill=(255, 255, 255))
    status = (
        "FOUND QR!  Frames: 186  504x896"
        if detected
        else "Streaming… searching for QR"
    )
    draw.text((28, H - 66), status, fill=(0, 0, 0), font=SMALL)
    result_fill = (0, 210, 70) if detected else (90, 96, 104)
    draw.rounded_rectangle([16, H - 36, W - 16, H - 8], radius=6, fill=result_fill)
    result = "QR: https://meta.com/glasses" if detected else "No QR yet"
    draw.text((28, H - 32), result, fill=(0, 0, 0) if detected else (230, 230, 230), font=BODY)

    caption = "Detect" if detect_from <= i < detect_from + lock_frames else ("Track" if detected else "Search")
    draw.rounded_rectangle([16, 14, 108, 40], radius=8, fill=(20, 24, 30))
    draw.text((28, 18), caption, fill=(48, 220, 90) if detected else (180, 188, 198), font=TITLE)

    return img.filter(ImageFilter.SMOOTH)


def main() -> None:
    frames = [draw_frame(i) for i in range(FRAMES)]
    out = Path(__file__).resolve().parent / "qr-tracking.gif"
    frames[0].save(
        out,
        save_all=True,
        append_images=frames[1:],
        duration=FPS_DELAY_MS,
        loop=0,
        optimize=True,
    )
    print(f"Wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
