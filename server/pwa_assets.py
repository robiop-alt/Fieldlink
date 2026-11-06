"""Exact OFFSET iPhone home-screen artwork supplied by the project owner.

The supplied square logo is kept as a compact source asset. Convert to PNG
for Safari's apple-touch-icon and for 192/512 PWA manifest icons.
"""
from functools import lru_cache
from io import BytesIO
from pathlib import Path
from PIL import Image

ICON_SOURCE = Path(__file__).resolve().parent / "static" / "offset-home-source.webp"

@lru_cache(maxsize=3)
def icon_png(size: int) -> bytes:
    if size not in (180, 192, 512):
        raise ValueError("unsupported size")
    with Image.open(ICON_SOURCE) as source:
        icon = source.convert("RGB").resize((size, size), Image.Resampling.LANCZOS)
        buffer = BytesIO()
        icon.save(buffer, format="PNG", optimize=True)
        return buffer.getvalue()
