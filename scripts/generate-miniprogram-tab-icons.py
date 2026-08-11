#!/usr/bin/env python3
"""Generate the native miniprogram tab icons from the pinned FirstUI font.

Requires Pillow. The source font stays embedded in the unmodified upstream
fui-icon stylesheet, so this script does not depend on a machine-local font.
"""

from __future__ import annotations

import base64
import io
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ICON_STYLE = ROOT / "miniprogram/components/firstui/fui-icon/fui-icon.wxss"
OUTPUTS = (
    ROOT / "miniprogram/assets/tab",
    ROOT / "docs/design/miniprogram/icons",
)
CANVAS_SIZE = 81
FONT_SIZE = 80
NORMAL_COLOR = "#6F656B"
ACTIVE_COLOR = "#7A284F"
ICONS = {
    "tab-home.png": 0xE7ED,
    "tab-home-active.png": 0xE7EC,
    "tab-category.png": 0xE7FE,
    "tab-category-active.png": 0xE7FF,
    "tab-cart.png": 0xE801,
    "tab-cart-active.png": 0xE800,
    "tab-profile.png": 0xE7D5,
    "tab-profile-active.png": 0xE7D4,
}


def load_font() -> ImageFont.FreeTypeFont:
    source = ICON_STYLE.read_text(encoding="utf-8")
    match = re.search(r"base64,([^\"')]+)", source)
    if match is None:
        raise RuntimeError(f"No embedded FirstUI font found in {ICON_STYLE}")
    return ImageFont.truetype(io.BytesIO(base64.b64decode(match.group(1))), FONT_SIZE)


def render(font: ImageFont.FreeTypeFont, codepoint: int, color: str) -> Image.Image:
    scratch = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(scratch).text((40, 30), chr(codepoint), font=font, fill=color)
    bounds = scratch.getchannel("A").getbbox()
    if bounds is None:
        raise RuntimeError(f"FirstUI glyph U+{codepoint:04X} rendered empty")

    glyph = scratch.crop(bounds)
    canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(
        glyph,
        ((CANVAS_SIZE - glyph.width) // 2, (CANVAS_SIZE - glyph.height) // 2),
    )
    return canvas


def main() -> None:
    font = load_font()
    for filename, codepoint in ICONS.items():
        color = ACTIVE_COLOR if filename.endswith("-active.png") else NORMAL_COLOR
        image = render(font, codepoint, color)
        for directory in OUTPUTS:
            directory.mkdir(parents=True, exist_ok=True)
            image.save(directory / filename, optimize=True)


if __name__ == "__main__":
    main()
