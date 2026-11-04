"""Before/after previews of the 장난감 왕국 colour grade (the pack's include/mcpl_grade.glsl, docs/siege-v2.md §7.2).

    python3 -I tools/assetgen/grade_preview.py <out dir> [image.png ...]

Writes <out dir>/grade_<name>.png (vanilla left, graded right) for every image (e.g. ./gradlew renderMaps
-Pmap=toykingdom output in build/map-previews/, or model sheets from build.py --render) and grade_swatches.png:
typical Minecraft colours before/after. The formula below is the shader's, line for line: change both together.
Needs Pillow and numpy.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# include/mcpl_grade.glsl
SATURATION = 0.95
CONTRAST = 0.12
LIFT = 0.05
SHADOW_TINT = np.array([0.016, 0.002, 0.014])
HIGHLIGHT = np.array([1.04, 1.01, 0.96])
BRIGHTEN = 0.07
LUMA = np.array([0.2126, 0.7152, 0.0722])


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def grade(rgb):
    """rgb: float array (..., 3) in 0..1 (the shader's lit colour); returns the graded colour."""
    original = rgb
    c = np.maximum(original, 0.0)
    luma = (np.minimum(c, 1.0) * LUMA).sum(axis=-1, keepdims=True)
    c = luma + (c - luma) * SATURATION
    unit = np.minimum(c, 1.0)
    c = c + CONTRAST * (unit * unit * (3.0 - 2.0 * unit) - unit)
    dark = 1.0 - np.minimum(c, 1.0)
    c = c + LIFT * dark * dark
    c = c + BRIGHTEN * np.minimum(c, 1.0) * dark
    c = c + SHADOW_TINT * (1.0 - smoothstep(0.0, 0.5, luma))
    c = c * (1.0 + (HIGHLIGHT - 1.0) * smoothstep(0.45, 1.0, luma))
    return np.minimum(c, np.maximum(1.0, original))


def grade_image(image):
    rgba = np.asarray(image.convert("RGBA")).astype(np.float64) / 255.0
    rgba[..., :3] = grade(rgba[..., :3])
    return Image.fromarray(np.round(np.clip(rgba, 0.0, 1.0) * 255.0).astype(np.uint8), "RGBA")


def side_by_side(image, label_left="vanilla", label_right="MCPL toy-kingdom grade"):
    before = image.convert("RGBA")
    after = grade_image(before)
    gap, bar = 12, 22
    out = Image.new("RGBA", (before.width * 2 + gap, before.height + bar), (40, 40, 46, 255))
    out.paste(before, (0, bar), before)
    out.paste(after, (before.width + gap, bar), after)
    draw = ImageDraw.Draw(out)
    draw.text((6, 5), label_left, fill=(235, 235, 235, 255))
    draw.text((before.width + gap + 6, 5), label_right, fill=(235, 235, 235, 255))
    return out


SWATCHES = [
    ("grass", (0x7c, 0xbd, 0x6b)), ("leaves", (0x4a, 0x8f, 0x3a)), ("dirt", (0x86, 0x60, 0x43)),
    ("stone", (0x7d, 0x7d, 0x7d)), ("sand", (0xdb, 0xd3, 0xa0)), ("water", (0x3f, 0x76, 0xe4)),
    ("day sky", (0x78, 0xa7, 0xff)), ("oak", (0xa2, 0x82, 0x4e)), ("red team", (0xb0, 0x2e, 0x26)),
    ("blue team", (0x3c, 0x44, 0xaa)), ("skin", (0xc6, 0x8e, 0x6b)), ("gold", (0xf5, 0xc5, 0x2c)),
    ("pastel pink", (0xf4, 0xb6, 0xc8)), ("cream", (0xf6, 0xec, 0xd4)), ("snow", (0xf8, 0xfd, 0xfd)),
    ("mint mat", (0x9f, 0xe2, 0xc6)), ("quartz", (0xec, 0xe6, 0xdc)), ("crimson", (0x8c, 0x2a, 0x3a)),
    ("deep purple", (0x4a, 0x2a, 0x5e)), ("lantern lit", (0xe8, 0xa8, 0x5a)), ("amber glass", (0xd0, 0x8a, 0x30)),
    ("shade", (0x3a, 0x3a, 0x40)), ("cave", (0x14, 0x14, 0x18)), ("night", (0x02, 0x03, 0x08)),
]


def swatches(path):
    cell, label = 72, 16
    cols = 6
    rows = (len(SWATCHES) + cols - 1) // cols
    out = Image.new("RGB", (cols * cell * 2 + (cols - 1) * 8, rows * (cell + label + 8)), (40, 40, 46))
    draw = ImageDraw.Draw(out)
    for i, (name, rgb) in enumerate(SWATCHES):
        x = (i % cols) * (cell * 2 + 8)
        y = (i // cols) * (cell + label + 8)
        before = np.array(rgb, dtype=np.float64) / 255.0
        after = grade(before[None, :])[0]
        draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=tuple(int(v) for v in rgb))
        draw.rectangle([x + cell, y, x + 2 * cell - 1, y + cell - 1],
                       fill=tuple(int(round(min(1.0, v) * 255)) for v in after))
        draw.text((x + 2, y + cell + 2), name + " (before | after)", fill=(220, 220, 220))
    out.save(path)


def main(args):
    if not args:
        print(__doc__)
        return 2
    out_dir = Path(args[0])
    out_dir.mkdir(parents=True, exist_ok=True)
    swatches(out_dir / "grade_swatches.png")
    for name in args[1:]:
        source = Path(name)
        side_by_side(Image.open(source)).save(out_dir / f"grade_{source.stem}.png")
    print(f"wrote {len(args) - 1} comparisons + grade_swatches.png to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
