"""The 장난감 왕국 sidebar panel: font glyphs drawn behind the 공성전 sidebar of players who have the pack.

    python3 -I tools/assetgen/sidebar_panel.py                     # writes the font + its PNGs into the pack sources
    python3 -I tools/assetgen/sidebar_panel.py --preview <out dir> # + sidebar_preview.png (vanilla vs panel)

The vanilla sidebar is a translucent grey box (title row + one row per line) drawn before its text. The plugin
(core/ui/SidebarPanel.java) puts glyphs of the font `mcpl:sidebar` in front of the title and of every line: a row
piece exactly as wide as the box (left cap + fill tiles + right cap) and negative spaces back to where the text
starts, so the pieces cover the grey box and the text is drawn on top. Pieces are 1 image pixel = 1 GUI pixel.

Keep the numbers below in sync with SidebarPanel.java (SidebarPanelTest reads this font and checks them):
  cap width 8, fill tiles 8/16/32/64, rows: banner 12 high (ascent 11), body 9 (ascent 8), bottom 12 (ascent 8),
  key 11x15 (ascent 15); code points: negative spaces U+E000+i = -2^i, positive U+E010+i = +2^i (i = 0..8),
  row pieces U+E100 + 16*style + k (style 0 banner, 1 body, 2 bottom; k 0 left cap, 1 right cap, 2..5 tiles 8..64),
  the wind-up key U+E140.
"""

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets import toy_textures  # noqa: E402,F401  (registers the t_* pastel ramps)
from mcpl_assets.palette import ramp  # noqa: E402

REPO = HERE.parent.parent
ASSETS = REPO / "src" / "main" / "resourcepack-src" / "assets" / "mcpl"
FONT_JSON = ASSETS / "font" / "sidebar.json"
TEXTURES = ASSETS / "textures" / "font" / "sidebar"

CAP = 8
TILES = (8, 16, 32, 64)
STYLES = {  # name: (index, height, ascent)
    "banner": (0, 12, 11),
    "body": (1, 9, 8),
    "bottom": (2, 12, 8),
}
KEY = (11, 15, 15)       # width, height, ascent
NEG, POS, ROW, KEY_CP = 0xE000, 0xE010, 0xE100, 0xE140
BANNER_ABOVE = 3         # banner rows above the grey title row

# colours (palette ramps only)
FILL = ramp("t_ink", 1, 236)
DOT = ramp("t_ink", 2, 236)
EDGE = ramp("t_pink", 0)
BORDER = ramp("t_pink", 2)
GLINT = ramp("t_cream", 3)
LACE = ramp("t_pink", 3)
RIBBON = ramp("t_rose", 0)
RIBBON_LIGHT = ramp("t_rose", 2)
RIBBON_DARK = ramp("t_grape", 0)
GOLD_DARK, GOLD, GOLD_LIGHT = ramp("gold", 0), ramp("gold", 2), ramp("gold", 3)
CLEAR = (0, 0, 0, 0)


def _fill_px(x, y):
    """The panel inside: dark toy-box lacquer with a faint polka dot every 8 px."""
    return DOT if x % 8 == 4 and y % 9 == 4 else FILL


# ------------------------------------------------------------------ row pieces
# Each piece is drawn as a function of (x in the whole row, y in the row) so caps and tiles join seamlessly; a piece
# at row offset `ox` and width `w` samples x = ox..ox+w-1. Tiles are drawn at offset 8 (all tile widths are multiples
# of the 8 px pattern period), the right cap at a row whose width is a multiple of 8.

def body_px(x, y, width, height=9):
    edge = min(x, width - 1 - x)
    if edge == 0:
        return EDGE
    if edge == 1:
        return BORDER
    if edge == 2 and y % 3 == 1:
        return GLINT if x < width / 2 else BORDER   # stitched seam
    return _fill_px(x, y)


def bottom_px(x, y, width):
    edge = min(x, width - 1 - x)
    if y >= 10:   # the lace hanging under the panel
        if edge < 2:
            return CLEAR
        phase = x % 4
        if y == 10:
            return LACE if phase != 3 else CLEAR
        return BORDER if phase == 1 else CLEAR
    if y == 9:
        return EDGE
    if y == 8:
        return BORDER if edge > 0 else EDGE
    if y == 7 and edge > 1:
        return GLINT if (x // 2) % 2 == 0 else BORDER
    return body_px(x, y, width)


def banner_px(x, y, width):
    """The ribbon across the title row: swallow-tail ends, a light top edge, folds near the caps."""
    a = BANNER_ABOVE
    edge = min(x, width - 1 - x)
    if y < a - 1:          # the curled ribbon tips above the panel
        return RIBBON_LIGHT if edge in (1, 2) and y == a - 2 else CLEAR
    if y == a - 1:
        if edge == 0:
            return CLEAR
        return RIBBON_LIGHT if edge < 4 else RIBBON
    # rows a .. a+8 are the grey title row: everything opaque
    mid = a + 4
    notch = max(0, 2 - abs(y - mid))           # the V cut of the tails (depth 0..2)
    if edge < notch:
        return EDGE if edge == 0 else FILL
    if edge == notch and edge < 3:
        return RIBBON_DARK
    if edge in (5, 6):
        return RIBBON_DARK if (y + edge) % 2 == 0 else RIBBON   # the fold
    if y == a:
        return RIBBON_LIGHT
    if y == a + 8:
        return RIBBON_DARK
    return RIBBON


def key_image():
    """The gold wind-up key standing on the ribbon (top 8 rows; the rest is clear to satisfy ascent <= height)."""
    w, h, _ = KEY
    shape = [
        ".OOO...OOO.",
        "OLLGO.OGLLO",
        "OLGGOOOGGGO",
        ".OOOGLGOOO.",
        "....OGO....",
        "....OLO....",
        "...OOGOO...",
        "...OGGGO...",
    ]
    im = Image.new("RGBA", (w, h), CLEAR)
    colours = {"O": GOLD_DARK, "G": GOLD, "L": GOLD_LIGHT}
    for y, row in enumerate(shape):
        for x, c in enumerate(row):
            if c in colours:
                im.putpixel((x, y), colours[c])
    return im


PIXELS = {"banner": banner_px, "body": body_px, "bottom": bottom_px}


def piece(style, ox, w, row_width):
    _, h, _ = STYLES[style]
    im = Image.new("RGBA", (w, h), CLEAR)
    fn = PIXELS[style]
    for y in range(h):
        for x in range(w):
            im.putpixel((x, y), fn(ox + x, y, row_width))
    return im


def pieces():
    """{file name: (image, code point, ascent)} for every glyph."""
    out = {}
    # caps are drawn on a 64 px wide row: left cap = its first 8 columns, right cap = its last 8
    for style, (index, h, ascent) in STYLES.items():
        base = ROW + 16 * index
        out[f"{style}_left.png"] = (piece(style, 0, CAP, 64), base + 0, ascent)
        right = piece(style, 64 - CAP, CAP, 64)
        out[f"{style}_right.png"] = (right, base + 1, ascent)
        for k, w in enumerate(TILES):
            out[f"{style}_fill_{w}.png"] = (piece(style, CAP, w, 1 << 20), base + 2 + k, ascent)
    out["key.png"] = (key_image(), KEY_CP, KEY[2])
    for name, (im, _, _) in out.items():
        assert _any_alpha_col(im, im.width - 1), \
            f"{name}: its last column must be drawn (the client measures a glyph by its rightmost pixel)"
    return out


def _any_alpha_col(im, x):
    return any(im.getpixel((x, y))[3] > 0 for y in range(im.height))


def font_json(glyphs):
    advances = {}
    for i in range(9):
        advances[chr(NEG + i)] = -(1 << i)
        advances[chr(POS + i)] = 1 << i
    providers = [{"type": "space", "advances": advances}]
    for name, (im, cp, ascent) in sorted(glyphs.items(), key=lambda e: e[1][1]):
        providers.append({"type": "bitmap", "file": f"mcpl:font/sidebar/{name}", "height": im.height,
                          "ascent": ascent, "chars": [chr(cp)]})
    return {"providers": providers}


def write():
    glyphs = pieces()
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for stale in TEXTURES.glob("*.png"):
        if stale.name not in glyphs:
            stale.unlink()
    for name, (im, _, _) in glyphs.items():
        im.save(TEXTURES / name, optimize=True)
    FONT_JSON.parent.mkdir(parents=True, exist_ok=True)
    FONT_JSON.write_text(json.dumps(font_json(glyphs), ensure_ascii=True, indent=2) + "\n")
    print(f"sidebar font: {len(glyphs)} glyphs -> {FONT_JSON.relative_to(REPO)}")
    return glyphs


# ------------------------------------------------------------------ preview

def row_image(style, row_width):
    """A whole row as the client composes it from the pieces (left cap, tiles, right cap)."""
    _, h, _ = STYLES[style]
    im = Image.new("RGBA", (row_width, h), CLEAR)
    im.alpha_composite(piece(style, 0, CAP, 64), (0, 0))
    x, rest = CAP, row_width - 2 * CAP
    for w in sorted(TILES, reverse=True):
        while rest >= w:
            im.alpha_composite(piece(style, CAP, w, 1 << 20), (x, 0))
            x += w
            rest -= w
    im.alpha_composite(piece(style, 64 - CAP, CAP, 64), (x, 0))
    return im


SAMPLE = [
    ("초능력 공성전", (255, 248, 234)),
    [("점수 ", (170, 170, 170)), ("블루", (85, 85, 255)), (" 12", (255, 255, 255)), (" : ", (170, 170, 170)),
     ("8", (255, 255, 255)), (" 레드", (255, 85, 85))],
    [("라운드 ", (170, 170, 170)), ("1", (255, 255, 255)), (" : ", (170, 170, 170)), ("0", (255, 255, 255))],
    [("Lv.", (255, 255, 85)), ("5 ", (255, 255, 255)), ("(32/180) ", (170, 170, 170)), ("1200G", (255, 170, 0))],
    [("6레벨: ", (170, 170, 170)), ("불바다 범위 +1블록", (255, 255, 255))],
    [("드래곤 ", (85, 255, 255)), ("2:00", (255, 255, 255))],
]


def _font(size):
    for path in ("/usr/share/fonts/opentype/unifont/unifont.otf", "/usr/share/fonts/opentype/unifont/unifont_jp.otf",
                 "/usr/share/fonts/truetype/unifont/unifont.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def _text_width(draw, font, parts):
    return sum(int(draw.textlength(t, font=font)) for t, _ in parts)


def preview(out_dir, scale=3):
    """sidebar_preview.png: the vanilla grey sidebar and the panel one, at GUI scale `scale`, on a sky backdrop."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    font = _font(8 * scale)   # unifont: what the client draws Hangul with (8 px at GUI scale 1)
    scratch = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    lines = SAMPLE[1:]
    widths = [_text_width(scratch, font, parts) // scale for parts in lines]
    title_w = _text_width(scratch, font, [SAMPLE[0]]) // scale + 5
    panel_w = max(max(widths) + 4, title_w + 14)
    panel_w += (4 - panel_w % 8) % 8          # W = 4 mod 8, like SidebarPanel.width
    shots = []
    for skinned in (False, True):
        j = panel_w if skinned else max(max(widths), title_w)
        screen_w, screen_h = j + 40, len(lines) * 9 + 60
        shot = Image.new("RGBA", (screen_w * scale, screen_h * scale))
        sky = ImageDraw.Draw(shot)
        for y in range(shot.height):
            t = y / shot.height
            c = tuple(int(a + (b - a) * t) for a, b in zip((151, 217, 252), (196, 232, 195)))
            sky.line([(0, y), (shot.width, y)], fill=(*c, 255))
        m = screen_h - 12
        s = m - len(lines) * 9
        o = screen_w - j - 3
        p = screen_w - 1
        layer = Image.new("RGBA", shot.size, CLEAR)
        d = ImageDraw.Draw(layer)
        d.rectangle([(o - 2) * scale, (s - 10) * scale, p * scale - 1, (s - 1) * scale - 1], fill=(0, 0, 0, 102))
        d.rectangle([(o - 2) * scale, (s - 1) * scale, p * scale - 1, m * scale - 1], fill=(0, 0, 0, 76))
        shot.alpha_composite(layer)
        if skinned:
            row_w = j + 4
            def put(im, x, y):
                big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
                shot.alpha_composite(big, (x * scale, y * scale))
            put(row_image("banner", row_w), o - 2, s - 10 - BANNER_ABOVE)
            put(key_image(), o - 2 + row_w // 2 - KEY[0] // 2, s - 17)
            for t in range(len(lines)):
                style = "bottom" if t == len(lines) - 1 else "body"
                put(row_image(style, row_w), o - 2, s + 9 * t - 1)
        d = ImageDraw.Draw(shot)
        title, colour = SAMPLE[0]
        tw = _text_width(d, font, [SAMPLE[0]]) // scale
        tx = o + j // 2 - tw // 2
        d.text((tx * scale, (s - 9) * scale - scale), title, font=font, fill=colour if skinned else (245, 158, 11))
        d.text((tx * scale + 1, (s - 9) * scale - scale), title, font=font, fill=colour if skinned else (245, 158, 11))
        for t, parts in enumerate(lines):
            x = o * scale
            for text, col in parts:
                d.text((x, (s + 9 * t) * scale - scale), text, font=font, fill=col)
                x += int(d.textlength(text, font=font))
        shots.append(shot)
    gap = 12 * scale
    sheet = Image.new("RGBA", (sum(s.width for s in shots) + gap, max(s.height for s in shots)), (40, 40, 48, 255))
    x = 0
    for shot in shots:
        sheet.alpha_composite(shot, (x, 0))
        x += shot.width + gap
    sheet.save(out_dir / "sidebar_preview.png")
    # the glyph sheet itself, 8x
    glyphs = pieces()
    sheet_w = sum(im.width + 2 for im, _, _ in glyphs.values())
    atlas = Image.new("RGBA", (sheet_w, 16), (90, 90, 100, 255))
    x = 0
    for im, _, _ in glyphs.values():
        atlas.alpha_composite(im, (x, 0))
        x += im.width + 2
    atlas.resize((atlas.width * 8, atlas.height * 8), Image.NEAREST).save(out_dir / "sidebar_glyphs.png")
    print(f"preview -> {out_dir / 'sidebar_preview.png'} (panel width {panel_w})")


def main():
    args = sys.argv[1:]
    write()
    if "--preview" in args:
        preview(args[args.index("--preview") + 1])


if __name__ == "__main__":
    main()
