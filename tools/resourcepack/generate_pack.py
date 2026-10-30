#!/usr/bin/env python3
"""Generates the MCPL resource pack (src/main/resourcepack) for the 증강 야생전 augment pick screen.

The pick screen is a 6-row chest whose title is a string of custom-font glyphs: a background drawn over the top part
of the chest, one card frame per offered augment, the augment icons drawn big, and text lines placed inside the cards
by fonts with different ascents. Everything is drawn at 2x the GUI pixel size (bitmap glyphs are scaled by 1/2), so it
stays sharp at GUI scale 2 and above. The Java side (game/augment/runtime/PackScreen) mirrors the geometry below:
change both together.

Usage (from the repository root):
    python3 -I tools/resourcepack/generate_pack.py [--client-jar <26.x client.jar>] [--preview <png>]

The client jar is only read to measure (never copy) vanilla textures: the width of the ASCII glyphs and of the icon
textures listed in tools/resourcepack/icons.txt (written by ./gradlew updateAugmentDocs). Measured icon widths are
cached in tools/resourcepack/vanilla-metrics.json so the pack can be regenerated without the jar. Hangul glyphs come from
GNU Unifont (SIL OFL 1.1, the same font the vanilla client uses for Hangul), restricted to the 2350 KS X 1001 syllables.
"""

import argparse
import colorsys
import io
import json
import math
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACK = os.path.join(ROOT, "src", "main", "resourcepack")
TOOLS = os.path.join(ROOT, "tools", "resourcepack")
ICON_TABLE = os.path.join(ROOT, "src", "main", "resources", "augment-pack-icons.tsv")
UNIFONT = "/usr/share/fonts/opentype/unifont/unifont.otf"

S = 2  # source pixels per GUI pixel
PACK_FORMAT = 97  # Minecraft 26.3 (version.json pack_version.resource_major = 97)

# ── geometry (GUI pixels, relative to the chest's top-left corner; the title starts at (8, 6)) ──────────────────────
TITLE_Y = 6
BG_X, BG_Y, BG_W, BG_H = -8, -22, 192, 148  # background: x -8..184, y -22..126
CARD_Y, CARD_W, CARD_H = 17, 52, 90  # card c: x 8 + 54c
STRIP_X, STRIP_W, STRIP_H = 8, 160, 18  # compact row r: y 17 + 18r
BUTTON_Y, BUTTON_W, BUTTON_H = 108, 52, 16  # reroll x 8, later x 116
ICON_TOP, ICON_SIZE = 33, 32
LINE_TOPS = [22, 40, 58, 67, 72, 76, 94, 112]  # text fonts mcpl:line/<top>

# private use characters (the Java side uses the same)
SPACE_NEG = 0xF001  # F001 = -1, F002 = -2, ..., F00A = -512
SPACE_POS = 0xF011  # F011 = +1, ..., F01A = +512
BG_TILES = 0xE000  # E000 top-left, E001 top-right, E002 bottom-left, E003 bottom-right
CARD = 0xE010  # + tier (silver, gold, prism)
BUTTON = 0xE020  # E020 reroll, E021 reroll (none left), E022 later
STRIP = 0xE100  # + row * 8 + tier * 2 + half
ICONS = 0xE400  # + index in icons.txt

TIERS = ["silver", "gold", "prism"]
TIER_COLORS = {
    "silver": [(0xF4, 0xFA, 0xFF), (0xC9, 0xD6, 0xDF), (0x6F, 0x80, 0x92), (0x3A, 0x45, 0x55)],
    "gold": [(0xFF, 0xF2, 0xB0), (0xFF, 0xC8, 0x3D), (0xB8, 0x7A, 0x12), (0x5E, 0x3A, 0x08)],
    "prism": [(0xFF, 0xD6, 0xFF), (0xD5, 0x8C, 0xFF), (0x7A, 0x3C, 0xC8), (0x35, 0x16, 0x63)],
}
TIER_BG = {"silver": (0x1A, 0x22, 0x30), "gold": (0x2A, 0x1E, 0x0C), "prism": (0x24, 0x12, 0x38)}


# ── drawing helpers (coordinates in source pixels) ────────────────────────────────────────────────────────────────

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def put(im, x, y, color):
    if 0 <= x < im.width and 0 <= y < im.height:
        if len(color) == 3:
            color = color + (255,)
        if color[3] >= 255:
            im.putpixel((x, y), color)
        else:
            base = im.getpixel((x, y))
            a = color[3] / 255
            out_a = a + base[3] / 255 * (1 - a)
            if out_a <= 0:
                return
            rgb = tuple(int(round((color[i] * a + base[i] * base[3] / 255 * (1 - a)) / out_a)) for i in range(3))
            im.putpixel((x, y), rgb + (int(round(out_a * 255)),))


def rect(im, x0, y0, x1, y1, color):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(im, x, y, color)


def vgradient(im, x0, y0, x1, y1, top, bottom, cut=0):
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0 - 1)
        c = lerp(top, bottom, t)
        for x in range(x0, x1):
            if cut and corner_cut(x - x0, y - y0, x1 - x0, y1 - y0, cut):
                continue
            put(im, x, y, c)


def corner_cut(x, y, w, h, cut):
    dx = min(x, w - 1 - x)
    dy = min(y, h - 1 - y)
    return dx + dy < cut


def frame(im, x0, y0, x1, y1, colors, width, cut):
    """A bevelled metallic frame: colors = [highlight, light, mid, dark]; light from the top-left."""
    w, h = x1 - x0, y1 - y0
    for y in range(h):
        for x in range(w):
            if corner_cut(x, y, w, h, cut):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d >= width:
                continue
            t = (x + y) / (w + h)
            if d == 0:
                c = colors[3]
            elif d == width - 1:
                c = lerp(colors[2], colors[3], 0.5)
            else:
                c = lerp(colors[0], colors[2], min(1.0, t * 1.6))
            put(im, x0 + x, y0 + y, c)


def prism_frame(im, x0, y0, x1, y1, width, cut):
    w, h = x1 - x0, y1 - y0
    for y in range(h):
        for x in range(w):
            if corner_cut(x, y, w, h, cut):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d >= width:
                continue
            hue = ((x + y * 0.6) / (w + h) * 1.4 + 0.75) % 1.0
            r, g, b = colorsys.hsv_to_rgb(hue, 0.45 if d not in (0, width - 1) else 0.6, 1.0 if d == 1 else 0.75)
            c = (int(r * 255), int(g * 255), int(b * 255))
            if d == 0:
                c = lerp(c, (0x20, 0x08, 0x30), 0.6)
            put(im, x0 + x, y0 + y, c)


def glow(im, cx, cy, radius, color, strength):
    for y in range(int(cy - radius), int(cy + radius) + 1):
        for x in range(int(cx - radius), int(cx + radius) + 1):
            d = math.hypot(x - cx, y - cy) / radius
            if d < 1:
                a = int(255 * strength * (1 - d) ** 2)
                if a > 0:
                    put(im, x, y, color + (a,))


def gem(im, cx, cy, r, colors):
    """A small diamond gem."""
    for y in range(-r, r + 1):
        for x in range(-r, r + 1):
            d = abs(x) + abs(y)
            if d > r:
                continue
            if d == r:
                c = colors[3]
            elif x + y < 0:
                c = colors[0]
            elif x - y < 0:
                c = colors[1]
            else:
                c = colors[2]
            put(im, cx + x, cy + y, c)


def hline(im, x0, x1, y, color):
    for x in range(x0, x1):
        put(im, x, y, color)


def ensure_right_edge(im):
    """Bitmap glyph advances come from the rightmost non-transparent column: keep the last column non-empty."""
    last = im.width - 1
    if all(im.getpixel((last, y))[3] == 0 for y in range(im.height)):
        im.putpixel((last, 0), (0, 0, 0, 1))
    return im


# ── text rendering for the art (unifont, 1 unifont pixel = 1 GUI pixel) ─────────────────────────────────────────────

def unifont():
    return ImageFont.truetype(UNIFONT, 16)


def render_text_mask(text, font):
    w = int(font.getlength(text)) + 2
    mask = Image.new("L", (w, 16), 0)
    d = ImageDraw.Draw(mask)
    d.fontmode = "1"
    d.text((0, 14), text, font=font, fill=255, anchor="ls")
    return mask


def draw_big_text(im, text, cx, top, top_color, bottom_color, outline):
    """Draws unifont text at 1 unifont px = 1 GUI px (2 source px), centred on cx, with a gradient and an outline."""
    mask = render_text_mask(text, unifont())
    box = mask.getbbox()
    mask = mask.crop((box[0], 0, box[2], 16))
    w = mask.width * S
    x0 = cx - w // 2
    big = mask.resize((mask.width * S, 16 * S), Image.NEAREST)
    # outline + drop shadow
    for dy in (-S, 0, S, 2 * S):
        for dx in (-S, 0, S):
            if dx == 0 and dy == 0:
                continue
            col = outline if dy < 2 * S else (0, 0, 0)
            for y in range(big.height):
                for x in range(big.width):
                    if big.getpixel((x, y)):
                        put(im, x0 + x + dx, top + y + dy, col + (255,))
    for y in range(big.height):
        t = y / (big.height - 1)
        c = lerp(top_color, bottom_color, t)
        for x in range(big.width):
            if big.getpixel((x, y)):
                put(im, x0 + x, top + y, c)


# ── art ──────────────────────────────────────────────────────────────────────────────────────────────────────────

def g(v):
    return v * S


def background():
    im = Image.new("RGBA", (g(BG_W), g(BG_H)), (0, 0, 0, 0))
    W, H = im.size
    gold = TIER_COLORS["gold"]
    # panel
    vgradient(im, 0, 0, W, H, (0x1C, 0x1A, 0x33), (0x0B, 0x0C, 0x18), cut=8)
    # header band (y -22..15 → source 0..74)
    header_h = g(15 - BG_Y)
    vgradient(im, g(4), g(3), W - g(4), header_h, (0x2C, 0x22, 0x4C), (0x16, 0x14, 0x2A), cut=6)
    glow(im, W // 2, g(3), g(70), (0x8C, 0x6C, 0xFF), 0.35)
    hline(im, g(6), W - g(6), header_h - 1, gold[2] + (255,))
    hline(im, g(6), W - g(6), header_h, (0x07, 0x07, 0x10, 255))
    # title "증강 선택" and side ornaments
    title_top = g(-19 - BG_Y)
    draw_big_text(im, "증강 선택", W // 2, title_top, (0xFF, 0xF4, 0xC8), (0xE0, 0xA2, 0x30), (0x2A, 0x14, 0x04))
    orn_y = title_top + g(8)
    for side in (-1, 1):
        x_inner = W // 2 + side * g(40)
        x_outer = W // 2 + side * g(84)
        for x in range(min(x_inner, x_outer), max(x_inner, x_outer)):
            t = abs(x - x_inner) / abs(x_outer - x_inner)
            put(im, x, orn_y, lerp(gold[1], gold[3], t) + (255,))
            put(im, x, orn_y + 1, lerp(gold[2], (0x10, 0x10, 0x20), t) + (255,))
        gem(im, x_inner + side * g(3), orn_y, 5, gold)
        gem(im, x_outer, orn_y, 3, gold)
    # card wells
    for c in range(3):
        x0 = g(8 + 54 * c - BG_X)
        y0 = g(CARD_Y - BG_Y)
        vgradient(im, x0 + 2, y0 + 2, x0 + g(CARD_W) - 2, y0 + g(CARD_H) - 2, (0x14, 0x13, 0x26), (0x0E, 0x0E, 0x1C),
                  cut=6)
    # button bar: timer socket (slot column 4: x 79..97, y 107..125)
    sx, sy = g(79 - BG_X), g(107 - BG_Y)
    frame(im, sx - 4, sy - 4, sx + g(18) + 4, sy + g(18) + 4, gold, 4, 4)
    rect(im, sx, sy, sx + g(18), sy + g(18), (0x8B, 0x8B, 0x8B, 255))
    hline(im, sx, sx + g(18), sy, (0x37, 0x37, 0x37, 255))
    hline(im, sx, sx + g(18), sy + 1, (0x37, 0x37, 0x37, 255))
    for y in range(sy, sy + g(18)):
        put(im, sx, y, (0x37, 0x37, 0x37, 255))
        put(im, sx + 1, y, (0x37, 0x37, 0x37, 255))
    gem(im, sx - g(6), sy + g(9), 4, gold)
    gem(im, sx + g(18) + g(6), sy + g(9), 4, gold)
    # outer frame
    frame(im, 0, 0, W, H, gold, 4, 8)
    for (x, y) in ((g(3), g(3)), (W - g(3), g(3)), (g(3), H - g(3)), (W - g(3), H - g(3))):
        gem(im, x, y, 7, gold)
    tiles = []
    half_w, half_h = W // 2, H // 2
    for ty in range(2):
        for tx in range(2):
            tile = im.crop((tx * half_w, ty * half_h, (tx + 1) * half_w, (ty + 1) * half_h))
            tiles.append(ensure_right_edge(tile))
    return im, tiles


def card(tier):
    im = Image.new("RGBA", (g(CARD_W), g(CARD_H)), (0, 0, 0, 0))
    W, H = im.size
    colors = TIER_COLORS[tier]
    bg = TIER_BG[tier]
    vgradient(im, 2, 2, W - 2, H - 2, lerp(bg, (255, 255, 255), 0.06), (0x08, 0x08, 0x10), cut=6)
    # light rays behind the icon
    cx, cy = W // 2, g(ICON_TOP - CARD_Y + ICON_SIZE // 2)
    glow(im, cx, cy, g(26), colors[1], 0.55)
    for i in range(12):
        ang = i / 12 * math.tau
        for r in range(g(10), g(25)):
            x = int(cx + math.cos(ang) * r)
            y = int(cy + math.sin(ang) * r)
            put(im, x, y, colors[0] + (int(70 * (1 - (r - g(10)) / g(15))),))
    # tier ribbon (text line top 22 → card offset 5)
    ry0, ry1 = g(20 - CARD_Y), g(31 - CARD_Y)
    vgradient(im, g(6), ry0, W - g(6), ry1, lerp(colors[2], (0, 0, 0), 0.25), lerp(colors[3], (0, 0, 0), 0.4), cut=3)
    hline(im, g(6), W - g(6), ry0, colors[1] + (255,))
    hline(im, g(6), W - g(6), ry1 - 1, colors[3] + (255,))
    # name area separator (y 66)
    sep = g(66 - CARD_Y)
    for x in range(g(6), W - g(6)):
        t = abs(x - W / 2) / (W / 2 - g(6))
        put(im, x, sep, lerp(colors[1], bg, t) + (255,))
    gem(im, W // 2, sep, 4, colors)
    # kind plate (text top 94)
    ky0, ky1 = g(92 - CARD_Y), g(103 - CARD_Y)
    vgradient(im, g(10), ky0, W - g(10), ky1, (0x05, 0x05, 0x0A), (0x12, 0x12, 0x1E), cut=3)
    hline(im, g(12), W - g(12), ky0, lerp(colors[2], (0, 0, 0), 0.3) + (255,))
    # frame
    if tier == "prism":
        prism_frame(im, 0, 0, W, H, 5, 6)
    else:
        frame(im, 0, 0, W, H, colors, 5 if tier == "gold" else 4, 6)
    # corner gems
    for (x, y) in ((g(4), g(4)), (W - g(4) - 1, g(4)), (g(4), H - g(4) - 1), (W - g(4) - 1, H - g(4) - 1)):
        gem(im, x, y, 4 if tier != "silver" else 3, colors)
    if tier != "silver":
        gem(im, W // 2, 2, 6, colors)
    if tier == "prism":
        for (x, y) in ((g(9), g(20)), (W - g(9), g(40)), (g(10), g(44)), (W - g(11), g(18))):
            sparkle(im, x, y)
    return ensure_right_edge(im)


def sparkle(im, x, y):
    for d in range(-5, 6):
        a = int(220 * (1 - abs(d) / 6))
        put(im, x + d, y, (255, 255, 255, a))
        put(im, x, y + d, (255, 255, 255, a))


def button(kind):
    im = Image.new("RGBA", (g(BUTTON_W), g(BUTTON_H)), (0, 0, 0, 0))
    W, H = im.size
    if kind == "reroll":
        top, bottom, edge = (0x3A, 0x8C, 0xC8), (0x14, 0x3C, 0x66), [(0xC8, 0xF0, 0xFF), (0x7C, 0xC8, 0xF0),
                                                                     (0x2A, 0x6A, 0x9C), (0x0A, 0x1C, 0x30)]
    elif kind == "reroll_none":
        top, bottom, edge = (0x4A, 0x4A, 0x52), (0x26, 0x26, 0x2C), [(0x9A, 0x9A, 0xA2), (0x70, 0x70, 0x78),
                                                                     (0x40, 0x40, 0x48), (0x14, 0x14, 0x18)]
    else:
        top, bottom, edge = (0x5A, 0x3A, 0x4A), (0x2A, 0x16, 0x22), [(0xF0, 0xC8, 0xD8), (0xC0, 0x8A, 0xA0),
                                                                     (0x70, 0x40, 0x56), (0x1C, 0x0A, 0x12)]
    vgradient(im, 0, 0, W, H, top, bottom, cut=5)
    hline(im, 4, W - 4, 3, lerp(top, (255, 255, 255), 0.35) + (255,))
    frame(im, 0, 0, W, H, edge, 3, 5)
    if kind == "reroll":
        arrows(im, g(6), H // 2, edge[0])
    return ensure_right_edge(im)


def arrows(im, cx, cy, color):
    """A small circular arrow (reroll) at the left of the button."""
    r = 7
    for i in range(0, 300, 6):
        a = math.radians(i - 60)
        put(im, int(cx + math.cos(a) * r), int(cy + math.sin(a) * r), color + (255,))
        put(im, int(cx + math.cos(a) * (r - 1)), int(cy + math.sin(a) * (r - 1)), color + (255,))
    tip = (cx + int(math.cos(math.radians(-60)) * r), cy + int(math.sin(math.radians(-60)) * r))
    for d in range(4):
        put(im, tip[0] - d, tip[1] - 3 + d, color + (255,))
        put(im, tip[0] + 3 - d, tip[1] + d, color + (255,))


def strip(tier):
    im = Image.new("RGBA", (g(STRIP_W), g(STRIP_H)), (0, 0, 0, 0))
    W, H = im.size
    colors = TIER_COLORS[tier]
    bg = TIER_BG[tier]
    vgradient(im, 0, 0, W, H, lerp(bg, (255, 255, 255), 0.05), (0x0A, 0x0A, 0x12), cut=3)
    # icon socket (slot column 0)
    rect(im, 0, 0, g(18), H, (0, 0, 0, 0))
    vgradient(im, 2, 2, g(18) - 2, H - 2, lerp(colors[3], (0, 0, 0), 0.2), (0x06, 0x06, 0x0C))
    glow(im, g(9), H // 2, g(9), colors[1], 0.5)
    for x in range(g(19), W - 4):
        t = (x - g(19)) / (W - g(19))
        put(im, x, H - 3, lerp(colors[1], bg, t) + (255,))
    if tier == "prism":
        prism_frame(im, 0, 0, W, H, 3, 3)
    else:
        frame(im, 0, 0, W, H, colors, 3, 3)
    gem(im, g(18), H // 2, 4, colors)
    halves = [ensure_right_edge(im.crop((0, 0, W // 2, H))), ensure_right_edge(im.crop((W // 2, 0, W, H)))]
    return im, halves


def tooltip(tier):
    """Tooltip background and frame sprites in the vanilla geometry (100x100 nine-slice)."""
    colors = TIER_COLORS[tier]
    bg = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    base = TIER_BG[tier]
    for y in range(8, 92):
        for x in range(8, 92):
            if (x in (8, 91)) and (y in (8, 91)):
                continue
            t = (y - 8) / 83
            c = lerp(lerp(base, (0, 0, 0), 0.25), (0x06, 0x06, 0x0C), t)
            bg.putpixel((x, y), c + (242,))
    fr = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    for i in range(9, 91):
        t = (i - 9) / 81
        top = lerp(colors[1], colors[2], t)
        if tier == "prism":
            r, g_, b = colorsys.hsv_to_rgb((0.75 + t * 0.5) % 1, 0.45, 1.0)
            top = (int(r * 255), int(g_ * 255), int(b * 255))
        fr.putpixel((i, 9), lerp(colors[0], colors[1], abs(t - 0.5) * 2) + (255,))
        fr.putpixel((i, 90), colors[3] + (255,))
        fr.putpixel((9, i), top + (255,))
        fr.putpixel((90, i), lerp(top, colors[3], 0.5) + (255,))
    for (x, y) in ((9, 9), (90, 9), (9, 90), (90, 90)):
        fr.putpixel((x, y), colors[0] + (255,))
    for d in range(-2, 3):
        fr.putpixel((50 + d, 9 - (2 - abs(d)) if abs(d) < 2 else 9), colors[0] + (255,))
    return bg, fr


def pack_icon():
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    vgradient(im, 0, 0, 128, 128, (0x2C, 0x22, 0x4C), (0x0B, 0x0C, 0x18), cut=10)
    for i, tier in enumerate(TIERS):
        c = card(tier).resize((g(CARD_W) // 3, g(CARD_H) // 3), Image.LANCZOS)
        im.alpha_composite(c, (10 + i * 38, 34 + (0 if i == 1 else 6)))
    frame(im, 0, 0, 128, 128, TIER_COLORS["gold"], 4, 10)
    return im


# ── fonts ────────────────────────────────────────────────────────────────────────────────────────────────────────

def ascent_for(top):
    """Ascent that puts the top of a glyph at GUI y = top (glyph top = title y + 7 - ascent)."""
    return TITLE_Y + 7 - top


def hangul_sheet():
    syllables = [chr(c) for c in range(0xAC00, 0xD7A4) if len(chr(c).encode("euc-kr", "ignore")) == 2]
    per_row = 64
    rows = [syllables[i:i + per_row] for i in range(0, len(syllables), per_row)]
    rows[-1] += ["\u0000"] * (per_row - len(rows[-1]))
    font = unifont()
    im = Image.new("RGBA", (16 * per_row, 16 * len(rows)), (0, 0, 0, 0))
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == "\u0000":
                continue
            mask = render_text_mask(ch, font).crop((0, 0, 16, 16))
            cell = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            cell.paste((255, 255, 255, 255), (0, 0), mask)
            cell.putpixel((15, 0), (255, 255, 255, 1) if cell.getpixel((15, 0))[3] == 0 else cell.getpixel((15, 0)))
            im.paste(cell, (c * 16, r * 16))
    return im, ["".join(row) for row in rows]


SYMBOLS_HALF = "·…×"
SYMBOLS_FULL = "◆★♥▶→"


def symbol_sheet(chars, cell_w):
    font = unifont()
    im = Image.new("RGBA", (cell_w * len(chars), 16), (0, 0, 0, 0))
    for i, ch in enumerate(chars):
        mask = render_text_mask(ch, font).crop((0, 0, cell_w, 16))
        cell = Image.new("RGBA", (cell_w, 16), (0, 0, 0, 0))
        cell.paste((255, 255, 255, 255), (0, 0), mask)
        if cell.getpixel((cell_w - 1, 0))[3] == 0:
            cell.putpixel((cell_w - 1, 0), (255, 255, 255, 1))
        im.paste(cell, (i * cell_w, 0))
    return im


def space_provider():
    advances = {}
    for i in range(10):
        advances[chr(SPACE_NEG + i)] = -(1 << i)
        advances[chr(SPACE_POS + i)] = 1 << i
    return {"type": "space", "advances": advances}


def bitmap(file, ascent, height, chars):
    return {"type": "bitmap", "file": file, "ascent": ascent, "height": height, "chars": chars}


# ── vanilla measurements ─────────────────────────────────────────────────────────────────────────────────────────

def glyph_width(im, x0, y0, w, h):
    for x in range(w - 1, -1, -1):
        for y in range(h):
            if im.getpixel((x0 + x, y0 + y))[3] != 0:
                return x + 1
    return 0


def measure(client_jar, textures):
    widths = {}
    ascii_widths = {}
    with zipfile.ZipFile(client_jar) as jar:
        default = json.loads(jar.read("assets/minecraft/font/include/default.json"))
        ascii_provider = next(p for p in default["providers"] if p["file"] == "minecraft:font/ascii.png")
        ascii_png = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")
        rows = ascii_provider["chars"]
        cw, ch = ascii_png.width // len(rows[0]), ascii_png.height // len(rows)
        for r, row in enumerate(rows):
            for c, char in enumerate(row):
                if 0x21 <= ord(char) <= 0x7E:
                    w = glyph_width(ascii_png, c * cw, r * ch, cw, ch)
                    ascii_widths[char] = int(0.5 + w * (8 / ch)) + 1
        for texture in textures:
            path = "assets/minecraft/textures/" + texture + ".png"
            try:
                im = Image.open(io.BytesIO(jar.read(path))).convert("RGBA")
            except KeyError:
                print("missing texture " + texture, file=sys.stderr)
                continue
            if im.height != im.width:
                print("animated/non-square texture " + texture + " " + str(im.size), file=sys.stderr)
            widths[texture] = [glyph_width(im, 0, 0, im.width, im.height), im.width, im.height]
    return ascii_widths, widths, ascii_provider["chars"]


# ── output ───────────────────────────────────────────────────────────────────────────────────────────────────────

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=True)
        f.write("\n")


def save(im, *parts):
    path = os.path.join(PACK, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-jar")
    parser.add_argument("--preview")
    args = parser.parse_args()

    with open(os.path.join(TOOLS, "icons.txt"), encoding="utf-8") as f:
        textures = [line.strip() for line in f if line.strip() and not line.startswith("#")]
    cache_path = os.path.join(TOOLS, "vanilla-metrics.json")
    cache = json.load(open(cache_path, encoding="utf-8")) if os.path.exists(cache_path) else {}
    if args.client_jar:
        ascii_widths, widths, ascii_rows = measure(args.client_jar, textures)
        cache = {"ascii_rows": ascii_rows, "ascii_widths": ascii_widths,
                 "icons": {**cache.get("icons", {}), **widths}}
        write_json(cache_path, cache)
    if "ascii_rows" not in cache:
        sys.exit("no vanilla metrics yet: pass --client-jar <26.x client.jar> once")

    T = "mcpl:"
    # background, cards, buttons, strips
    full, tiles = background()
    for i, tile in enumerate(tiles):
        save(tile, "assets", "mcpl", "textures", "gui", "offer", "background_%d.png" % i)
    cards = {t: card(t) for t in TIERS}
    for t, im in cards.items():
        save(im, "assets", "mcpl", "textures", "gui", "offer", "card_%s.png" % t)
    buttons = {k: button(k) for k in ("reroll", "reroll_none", "later")}
    for k, im in buttons.items():
        save(im, "assets", "mcpl", "textures", "gui", "offer", "button_%s.png" % k)
    strips = {}
    for t in TIERS:
        whole, halves = strip(t)
        strips[t] = whole
        for h, im in enumerate(halves):
            save(im, "assets", "mcpl", "textures", "gui", "offer", "strip_%s_%d.png" % (t, h))

    providers = [space_provider()]
    tile_h = BG_H // 2
    for i in range(4):
        top = BG_Y + (i // 2) * tile_h
        providers.append(bitmap(T + "gui/offer/background_%d.png" % i, ascent_for(top), tile_h, [chr(BG_TILES + i)]))
    for i, t in enumerate(TIERS):
        providers.append(bitmap(T + "gui/offer/card_%s.png" % t, ascent_for(CARD_Y), CARD_H, [chr(CARD + i)]))
    for i, k in enumerate(("reroll", "reroll_none", "later")):
        providers.append(bitmap(T + "gui/offer/button_%s.png" % k, ascent_for(BUTTON_Y), BUTTON_H, [chr(BUTTON + i)]))
    for row in range(5):
        for i, t in enumerate(TIERS):
            for h in range(2):
                providers.append(bitmap(T + "gui/offer/strip_%s_%d.png" % (t, h), ascent_for(CARD_Y + 18 * row),
                                        STRIP_H, [chr(STRIP + row * 8 + i * 2 + h)]))
    write_json(os.path.join(PACK, "assets", "mcpl", "font", "offer.json"), {"providers": providers})

    # big icons: vanilla textures, measured widths
    icon_providers = []
    table = ["# texture\tchar\tadvance (generated by tools/resourcepack/generate_pack.py)"]
    for i, texture in enumerate(textures):
        measured = cache["icons"].get(texture)
        if measured is None:
            print("no measured width for %s: assuming a full-width texture" % texture, file=sys.stderr)
            measured = [16, 16, 16]
        width, tex_w, tex_h = measured
        scale = ICON_SIZE / tex_h
        advance = int(0.5 + width * scale) + 1
        icon_providers.append(bitmap("minecraft:" + texture + ".png", ascent_for(ICON_TOP), ICON_SIZE,
                                     [chr(ICONS + i)]))
        table.append("%s\t%04X\t%d" % (texture, ICONS + i, advance))
    write_json(os.path.join(PACK, "assets", "mcpl", "font", "offer_icon.json"), {"providers": icon_providers})
    with open(ICON_TABLE, "w", encoding="utf-8") as f:
        f.write("\n".join(table) + "\n")

    # text lines
    sheet, hangul_rows = hangul_sheet()
    save(sheet, "assets", "mcpl", "textures", "font", "hangul.png")
    save(symbol_sheet(SYMBOLS_HALF, 8), "assets", "mcpl", "textures", "font", "symbols_half.png")
    save(symbol_sheet(SYMBOLS_FULL, 16), "assets", "mcpl", "textures", "font", "symbols_full.png")
    for top in LINE_TOPS:
        a = ascent_for(top)
        write_json(os.path.join(PACK, "assets", "mcpl", "font", "line", "%d.json" % top), {"providers": [
            {"type": "space", "advances": {" ": 4}},
            bitmap("minecraft:font/ascii.png", a, 8, cache["ascii_rows"]),
            bitmap(T + "font/hangul.png", a, 8, hangul_rows),
            bitmap(T + "font/symbols_half.png", a, 8, [SYMBOLS_HALF]),
            bitmap(T + "font/symbols_full.png", a, 8, [SYMBOLS_FULL]),
        ]})

    # tooltips, hitbox model, pack meta
    for t in TIERS:
        bg, fr = tooltip(t)
        save(bg, "assets", "mcpl", "textures", "gui", "sprites", "tooltip", "%s_background.png" % t)
        save(fr, "assets", "mcpl", "textures", "gui", "sprites", "tooltip", "%s_frame.png" % t)
        write_json(os.path.join(PACK, "assets", "mcpl", "textures", "gui", "sprites", "tooltip",
                                "%s_background.png.mcmeta" % t),
                   {"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 9}}})
        write_json(os.path.join(PACK, "assets", "mcpl", "textures", "gui", "sprites", "tooltip",
                                "%s_frame.png.mcmeta" % t),
                   {"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 10,
                                        "stretch_inner": True}}})
    write_json(os.path.join(PACK, "assets", "mcpl", "items", "blank.json"), {"model": {"type": "minecraft:empty"}})
    write_json(os.path.join(PACK, "pack.mcmeta"), {"pack": {
        "description": "MCPL — 증강 선택 화면",
        "min_format": PACK_FORMAT,
        "max_format": PACK_FORMAT}})
    pack_icon().save(os.path.join(PACK, "pack.png"), optimize=True)

    # the measured ASCII advances for the Java side (PackText)
    print("ASCII advances (! to ~): " + "".join(str(cache["ascii_widths"].get(chr(c), 6) - 1)
                                               for c in range(0x21, 0x7F)))
    if args.preview:
        if not args.client_jar:
            sys.exit("--preview needs --client-jar (the vanilla chest, font and icons)")
        render_preview(args.preview, full, cards, buttons, strips, sheet, hangul_rows, cache, args.client_jar)


# ── preview (what the client should show; uses vanilla textures from the jar, never written to the pack) ─────────

PREVIEW_CARDS = [
    ("silver", "날카로운 칼날", "전투", "item/iron_sword"),
    ("gold", "광전사의 계약", "양날의 검", "item/golden_axe"),
    ("prism", "불사조의 깃털", "특수", "item/feather"),
]
PREVIEW_STRIPS = PREVIEW_CARDS + [("gold", "행운의 동전", "특수", "item/gold_ingot"),
                                  ("silver", "추적 나침반", "특수", "item/compass_16")]


def render_preview(path, full, cards, buttons, strips, sheet, hangul_rows, cache, client_jar):
    jar = zipfile.ZipFile(client_jar)

    def tex(name):
        return Image.open(io.BytesIO(jar.read("assets/minecraft/textures/" + name + ".png"))).convert("RGBA")

    ascii_png = tex("font/ascii")
    rows = cache["ascii_rows"]
    hangul = {ch: (c, r) for r, row in enumerate(hangul_rows) for c, ch in enumerate(row)}
    ascii_pos = {ch: (c, r) for r, row in enumerate(rows) for c, ch in enumerate(row)}

    def text_width(text):
        w = 0
        for ch in text:
            if ch == " ":
                w += 4
            elif ch in hangul:
                w += 9
            elif ch in SYMBOLS_HALF:
                w += 5
            else:
                w += cache["ascii_widths"].get(ch, 6)
        return w

    def draw_text(canvas, text, x, top, color, ox, oy):
        for ch in text:
            if ch == " ":
                x += 4
                continue
            if ch in hangul:
                c, r = hangul[ch]
                cell = sheet.crop((c * 16, r * 16, c * 16 + 16, r * 16 + 16))
                adv = 9
            elif ch in SYMBOLS_HALF:
                cell = symbol_sheet(ch, 8)
                adv = 5
            else:
                c, r = ascii_pos[ch]
                cell = ascii_png.crop((c * 8, r * 8, c * 8 + 8, r * 8 + 8)).resize((16, 16), Image.NEAREST)
                adv = cache["ascii_widths"].get(ch, 6)
            tinted = Image.new("RGBA", cell.size, color + (255,))
            tinted.putalpha(cell.getchannel("A").point(lambda a: 255 if a > 1 else 0))
            canvas.alpha_composite(tinted, (g(x + ox), g(top + oy)))
            x += adv

    def wrap(name, width):
        words, lines, line = name.split(" "), [], ""
        for word in words:
            cand = (line + " " + word).strip()
            if text_width(cand) - 1 <= width:
                line = cand
            else:
                lines.append(line)
                line = word
        lines.append(line)
        return lines

    tier_names = {"silver": ("실버", (0xC9, 0xD6, 0xDF)), "gold": ("골드", (0xFF, 0xC8, 0x3D)),
                  "prism": ("프리즘", (0xD5, 0x8C, 0xFF))}
    shots = []
    for mode in ("cards", "strips"):
        ox, oy = 24, 30  # chest origin inside the preview canvas (GUI px)
        canvas = Image.new("RGBA", (g(176 + 48), g(222 + 40)), (0x10, 0x10, 0x14, 255))
        chest = tex("gui/container/generic_54").crop((0, 0, 176, 222))
        canvas.alpha_composite(chest.resize((g(176), g(222)), Image.NEAREST), (g(ox), g(oy)))
        canvas.alpha_composite(full, (g(ox + BG_X), g(oy + BG_Y)))
        header = "4번째 라운드 · 다음 증강 1개"
        draw_text(canvas, header, 88 - text_width(header) // 2, 6, (0xD8, 0xD0, 0xF0), ox, oy)
        if mode == "cards":
            for c, (tier, name, kind, icon) in enumerate(PREVIEW_CARDS):
                x = 8 + 54 * c
                canvas.alpha_composite(cards[tier], (g(ox + x), g(oy + CARD_Y)))
                tn, tc = tier_names[tier]
                draw_text(canvas, tn, x + 26 - text_width(tn) // 2, 22, tc, ox, oy)
                ic = tex(icon).resize((g(ICON_SIZE), g(ICON_SIZE)), Image.NEAREST)
                canvas.alpha_composite(ic, (g(ox + x + 10), g(oy + ICON_TOP)))
                lines = wrap(name, 46)
                tops = [72] if len(lines) == 1 else [67, 76]
                for line, top in zip(lines, tops):
                    draw_text(canvas, line, x + 26 - text_width(line) // 2, top, tc, ox, oy)
                draw_text(canvas, kind, x + 26 - text_width(kind) // 2, 94, (0xA8, 0xA8, 0xB8), ox, oy)
        else:
            for r, (tier, name, kind, icon) in enumerate(PREVIEW_STRIPS):
                canvas.alpha_composite(strips[tier], (g(ox + STRIP_X), g(oy + CARD_Y + 18 * r)))
                ic = tex(icon).resize((g(16), g(16)), Image.NEAREST)
                canvas.alpha_composite(ic, (g(ox + 8), g(oy + 18 + 18 * r)))
                tn, tc = tier_names[tier]
                draw_text(canvas, name, 30, 22 + 18 * r, tc, ox, oy)
                draw_text(canvas, kind, 165 - text_width(kind), 22 + 18 * r, (0xA8, 0xA8, 0xB8), ox, oy)
        canvas.alpha_composite(buttons["reroll"], (g(ox + 8), g(oy + BUTTON_Y)))
        canvas.alpha_composite(buttons["later"], (g(ox + 116), g(oy + BUTTON_Y)))
        draw_text(canvas, "리롤 2", 37 - text_width("리롤 2") // 2, 112, (0xFF, 0xFF, 0xFF), ox, oy)
        draw_text(canvas, "나중에", 142 - text_width("나중에") // 2, 112, (0xFF, 0xFF, 0xFF), ox, oy)
        clock = tex("item/clock_00").resize((g(16), g(16)), Image.NEAREST)
        canvas.alpha_composite(clock, (g(ox + 80), g(oy + 108)))
        draw_text(canvas, "23", 80 + 17 - text_width("23"), 108 + 9, (0xFF, 0xFF, 0xFF), ox, oy)
        shots.append(canvas)
    out = Image.new("RGBA", (shots[0].width * 2 + 8, shots[0].height), (0x10, 0x10, 0x14, 255))
    out.alpha_composite(shots[0], (0, 0))
    out.alpha_composite(shots[1], (shots[0].width + 8, 0))
    out = out.resize((out.width * 2, out.height * 2), Image.NEAREST)
    out.save(path)
    print("preview: " + path)


if __name__ == "__main__":
    main()
