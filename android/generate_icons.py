#!/usr/bin/env python3
"""Generate VidFlow launcher PNGs for mdpi–xxxhdpi. No third-party deps."""
from __future__ import annotations

import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def encode_png(width: int, pixels: list[list[int]]) -> bytes:
    raw = b"".join(b"\x00" + bytes(row) for row in pixels)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, width, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def lerp(a: int, b: int, t: float) -> int:
    return int(a + (b - a) * t)


def fill_rect(px: list[list[int]], size: int, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
    for y in range(max(0, y0), min(size, y1)):
        row = px[y]
        for x in range(max(0, x0), min(size, x1)):
            i = x * 4
            row[i : i + 4] = color


def fill_triangle(px: list[list[int]], size: int, p1, p2, p3, color) -> None:
    minx = max(0, int(min(p1[0], p2[0], p3[0])))
    maxx = min(size - 1, int(max(p1[0], p2[0], p3[0])))
    miny = max(0, int(min(p1[1], p2[1], p3[1])))
    maxy = min(size - 1, int(max(p1[1], p2[1], p3[1])))

    def edge(a, b, p):
        return (p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0])

    area = edge(p1, p2, p3)
    if area == 0:
        return
    for y in range(miny, maxy + 1):
        row = px[y]
        for x in range(minx, maxx + 1):
            p = (x + 0.5, y + 0.5)
            w1 = edge(p2, p3, p)
            w2 = edge(p3, p1, p)
            w3 = edge(p1, p2, p)
            if (w1 >= 0 and w2 >= 0 and w3 >= 0) or (w1 <= 0 and w2 <= 0 and w3 <= 0):
                i = x * 4
                row[i : i + 4] = color


def draw_icon(size: int) -> bytes:
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            t = (x + y) / (2 * max(size - 1, 1))
            row.extend([lerp(0x0F, 0x1E, t), lerp(0x17, 0x1B, t), lerp(0x2A, 0x4B, t), 255])
        px.append(row)

    s = size / 108.0
    violet = (0x8B, 0x5C, 0xF6, 255)
    cyan = (0x06, 0xB6, 0xD4, 255)
    # Stem 47,22 – 61,50
    fill_rect(px, size, round(47 * s), round(22 * s), round(61 * s), round(50 * s), violet)
    # Chevron 28,48 / 54,78 / 80,48 plus inner cut approximated as two triangles
    fill_triangle(px, size, (28 * s, 48 * s), (54 * s, 78 * s), (80 * s, 48 * s), cyan)
    fill_triangle(px, size, (40 * s, 40 * s), (54 * s, 58 * s), (68 * s, 40 * s), (0x0F, 0x17, 0x2A, 255))
    # Tray 30,82 – 78,88
    fill_rect(px, size, round(30 * s), round(82 * s), round(78 * s), round(88 * s), violet)
    return encode_png(size, px)


def main() -> None:
    for folder, size in SIZES.items():
        out_dir = os.path.join(ROOT, folder)
        os.makedirs(out_dir, exist_ok=True)
        data = draw_icon(size)
        for name in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_fg.png"):
            path = os.path.join(out_dir, name)
            with open(path, "wb") as f:
                f.write(data)
            print(f"wrote {path} ({size}x{size})")


if __name__ == "__main__":
    main()
