"""Dependency-free PNG renderer of the approved OFFSET logo's green O glyph."""
from functools import lru_cache
from pathlib import Path
import math
import re
import struct
import zlib

DARK = (11, 13, 15)
LOGO_GREEN = (87, 182, 47)

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

@lru_cache(maxsize=1)
def _logo_polygons():
    # Use the approved logo as the single source of truth, not a redrawn mark.
    svg = (Path(__file__).resolve().parent / "static" / "offset-logo.svg").read_text(encoding="utf-8")
    match = re.search(r'<path fill="#57B62F" fill-rule="evenodd" d="([^"]+)"', svg)
    if not match:
        raise RuntimeError("Approved OFFSET icon path is missing")
    tokens = re.findall(r"[MLZ]|-?\d+", match.group(1))
    polygons = []
    polygon = []
    index = 0
    while index < len(tokens):
        command = tokens[index]
        index += 1
        if command in ("M", "L"):
            polygon.append((int(tokens[index]), int(tokens[index + 1])))
            index += 2
        elif command == "Z":
            if polygon and max(x for x, _ in polygon) < 230:
                polygons.append(polygon)
            polygon = []
        else:
            raise RuntimeError("Unsupported logo path command")
    # The two nested original contours make an even-odd filled O.
    if len(polygons) != 2:
        raise RuntimeError("Expected outer and inner OFFSET O outlines")
    return polygons

@lru_cache(maxsize=3)
def icon_png(size):
    if size not in (180, 192, 512):
        raise ValueError("unsupported size")
    scale = size / 277.0
    # Original approved logo viewBox: first glyph occupies x=18..212,y=21..214
    # Use generous safe area around the logo on a square dark icon.
    left = (size - 230 * scale) / 2
    top = (size - 235 * scale) / 2
    rows = []
    polys = _logo_polygons()
    for y in range(size):
        scan_y = (y + 0.5 - top) / scale
        crossings = []
        for polygon in polys:
            for index, (x1, y1) in enumerate(polygon):
                x2, y2 = polygon[(index + 1) % len(polygon)]
                if (y1 <= scan_y < y2) or (y2 <= scan_y < y1):
                    crossings.append(left + (x1 + (scan_y - y1) * (x2 - x1) / (y2 - y1)) * scale)
        crossings.sort()
        row = bytearray(b"\x00" + bytes(DARK) * size)
        for x1, x2 in zip(crossings[::2], crossings[1::2]):
            begin = max(0, math.ceil(x1 - 0.5))
            end = min(size, math.ceil(x2 - 0.5))
            if end > begin:
                row[1 + 3 * begin:1 + 3 * end] = bytes(LOGO_GREEN) * (end - begin)
        rows.append(row)
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return bytes([137, 80, 78, 71, 13, 10, 26, 10]) + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
