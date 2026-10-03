"""Package the supplied Auras symbol as transparent desktop application assets."""

from pathlib import Path
import sys

from PIL import Image


SOURCE = Path(sys.argv[1])
OUTPUT = Path(sys.argv[2])
OUTPUT.mkdir(parents=True, exist_ok=True)

original = Image.open(SOURCE).convert("RGBA")
original.save(OUTPUT / "brand_symbol.png", optimize=True)
symbol = original.crop(original.getbbox())


def icon(size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    max_width = int(size * 0.84)
    max_height = int(size * 0.86)
    scale = min(max_width / symbol.width, max_height / symbol.height)
    scaled = symbol.resize((round(symbol.width * scale), round(symbol.height * scale)), Image.Resampling.LANCZOS)
    canvas.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
    return canvas


icon(1024).save(OUTPUT / "app_icon.png", optimize=True)
icon(64).save(OUTPUT / "app_icon_small.png", optimize=True)
icon(1024).save(OUTPUT / "app_icon_light.png", optimize=True)
icon(512).save(
    OUTPUT / "app_icon.ico",
    format="ICO",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)
