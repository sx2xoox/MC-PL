"""증강 야생전's floating augment cards (`augments.ui: CARDS`): font glyphs the plugin shows on text displays riding
the player, in front of the camera (game/augment/runtime/OfferCards.java).

    python3 -I tools/assetgen/augment_cards.py                     # writes the font, its PNGs and the icon table
    python3 -I tools/assetgen/augment_cards.py --preview <out dir> # + screen previews (1920 x 1080, GUI scale 2 / 3)

Outputs
  src/main/resourcepack-src/assets/mcpl/font/augment_cards.json      font `mcpl:augment_cards`
  src/main/resourcepack-src/assets/mcpl/textures/font/augment_cards/ its glyph PNGs
  src/main/resources/augment-card-icons.tsv                          augment id -> icon code point (read by CardGlyphs)

Keep the code points and glyph sizes in sync with CardGlyphs.java (AugmentCardsTest reads this font and checks them):
  negative spaces U+E000+i = -2^i, positive U+E010+i = +2^i (i = 0..8)
  card frames U+E100 + tier (silver 0, gold 1, prism 2), lit (highlighted) U+E108 + tier: 112 x 160, ascent 78
  key caps U+E110 + n - 1 (n = 1..5), lit U+E118 + n - 1: 16 x 16, ascent 6; reroll mark U+E11E, lit U+E11F
  title ribbons U+E120 + tier: 176 x 18, ascent 7; timer bar U+E130 + level (0..20): 122 x 8, ascent 2
  small inline key caps U+E150.. for 1 2 3 4 5 F Q ^(shift): 13 x 11, ascent 9; augment icons U+E200 + i: 32 x 32,
  ascent 14 (i = position in the icon table, sorted by augment id)
Every centred glyph has 2 + ascent - height / 2 = 0 (the plugin centres a glyph on its text display that way) and a
nearly invisible pixel (alpha 1) in its last column, so the client measures every glyph of a kind as wide as its PNG.
"""

import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets import card_art  # noqa: E402
from mcpl_assets.palette import ramp  # noqa: E402

REPO = HERE.parent.parent
ASSETS = REPO / "src" / "main" / "resourcepack-src" / "assets" / "mcpl"
FONT_JSON = ASSETS / "font" / "augment_cards.json"
TEXTURES = ASSETS / "textures" / "font" / "augment_cards"
ICON_TABLE = REPO / "src" / "main" / "resources" / "augment-card-icons.tsv"
DOCS = REPO / "docs" / "augments.md"

TIERS = ("silver", "gold", "prism")
NEG, POS = 0xE000, 0xE010
FRAME, FRAME_LIT = 0xE100, 0xE108
KEY, KEY_LIT, REROLL, REROLL_LIT = 0xE110, 0xE118, 0xE11E, 0xE11F
RIBBON, BAR, SMALL_KEY, ICON = 0xE120, 0xE130, 0xE150, 0xE200
SMALL_KEYS = ("1", "2", "3", "4", "5", "F", "Q", "^")
KIND = {"능력치": "STAT", "자원": "RESOURCE", "전투": "COMBAT", "기동": "MOBILITY", "양날의 검": "TRADEOFF", "특수": "SPECIAL"}
TIER_KO = {"실버": "silver", "골드": "gold", "프리즘": "prism"}


def augments():
    """[(id, name, tier, kind, description)] from the generated docs/augments.md (the Java AugmentInfos)."""
    out = []
    tier = None
    for line in DOCS.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            tier = TIER_KO.get(line[3:].strip())
        m = re.match(r"\| \*\*(.+?)\*\* `([a-z0-9_]+)` \| (.+?) \| .*? \| (.+) \|$", line)
        if m and tier:
            out.append((m.group(2), m.group(1), tier, KIND[m.group(3)], m.group(4)))
    return out


def pin(im):
    """A nearly invisible pixel in the last column: the client sizes a glyph by its rightmost visible column."""
    im = im.copy()
    x = im.width - 1
    if not any(im.getpixel((x, y))[3] for y in range(im.height)):
        im.putpixel((x, im.height - 1), (0, 0, 0, 1))
    return im


def glyphs():
    """{file name: (image, code point, ascent)}."""
    out = {}
    for i, tier in enumerate(TIERS):
        out[f"frame_{tier}.png"] = (card_art.frame(tier), FRAME + i, 78)
        out[f"frame_{tier}_lit.png"] = (card_art.frame(tier, lit=True), FRAME_LIT + i, 78)
        out[f"ribbon_{tier}.png"] = (card_art.ribbon(tier), RIBBON + i, 7)
    for n in range(1, 6):
        out[f"key_{n}.png"] = (card_art.keycap(str(n)), KEY + n - 1, 6)
        out[f"key_{n}_lit.png"] = (card_art.keycap(str(n), lit=True), KEY_LIT + n - 1, 6)
    out["reroll.png"] = (card_art.reroll_mark(), REROLL, 6)
    out["reroll_lit.png"] = (card_art.reroll_mark(lit=True), REROLL_LIT, 6)
    for level in range(card_art.BAR_STEPS + 1):
        out[f"bar_{level:02d}.png"] = (card_art.timer_bar(level), BAR + level, 2)
    for i, label in enumerate(SMALL_KEYS):
        name = {"^": "shift"}.get(label, label.lower())
        out[f"small_key_{name}.png"] = (card_art.small_key(label), SMALL_KEY + i, 9)
    for i, (aid, _, _, kind, _) in enumerate(sorted(augments())):
        out[f"icon_{aid}.png"] = (card_art.icon(aid, kind), ICON + i, 14)
    return {name: (pin(im), cp, ascent) for name, (im, cp, ascent) in out.items()}


def font_json(table):
    advances = {}
    for i in range(9):
        advances[chr(NEG + i)] = -(1 << i)
        advances[chr(POS + i)] = 1 << i
    providers = [{"type": "space", "advances": advances}]
    for name, (im, cp, ascent) in sorted(table.items(), key=lambda e: e[1][1]):
        providers.append({"type": "bitmap", "file": f"mcpl:font/augment_cards/{name}", "height": im.height,
                          "ascent": ascent, "chars": [chr(cp)]})
    return {"providers": providers}


def write():
    table = glyphs()
    missing = sorted(set(a[0] for a in augments()) - set(card_art.ICONS))
    assert not missing, f"augments without an icon recipe in card_art.ICONS: {missing}"
    TEXTURES.mkdir(parents=True, exist_ok=True)
    for stale in TEXTURES.glob("*.png"):
        if stale.name not in table:
            stale.unlink()
    for name, (im, _, _) in table.items():
        im.save(TEXTURES / name, optimize=True)
    FONT_JSON.parent.mkdir(parents=True, exist_ok=True)
    FONT_JSON.write_text(json.dumps(font_json(table), ensure_ascii=True, indent=2) + "\n")
    lines = ["# augment id -> icon code point of the font mcpl:augment_cards (tools/assetgen/augment_cards.py)"]
    for i, aid in enumerate(sorted(a[0] for a in augments())):
        lines.append(f"{aid}\t{ICON + i:04X}")
    ICON_TABLE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"augment cards font: {len(table)} glyphs -> {FONT_JSON.relative_to(REPO)}")
    return table


# ====================================================================== preview (same layout as OfferCards.java)

W, H = 1920, 1080
CARD_H = 0.432             # card body height, in screen heights (FOV 70)
TX = CARD_H / 144          # one frame texel
CARD_GAP = 0.035
CARD_Y = 0.0               # the whole set (ribbon .. hints) is centred on the screen
MAX_ROW = 1.30
NAME_UNIT = 2.8 / 1080     # font unit sizes (px at 1080p)
DESC_UNIT = 2.6 / 1080
INFO_UNIT = 3.0 / 1080     # info line and seconds: on a dark plate
HINT_UNIT = 3.8 / 1080     # the controls: big, on a dark plate
PLATE = (16, 16, 24, 160)  # the text displays' background colour (ARGB 0xA0101018)
ICON_Y, NAME_Y, DESC_MID = 40, 7, -33        # texels from the body centre (up); the description is centred
DESC_W, DESC_LINES = 78, 6
HIGHLIGHT_SCALE = 1.10     # grows round the card centre
# above the cards: the ribbon and the info line; below: the key caps, the timer, the hints (same extent both ways)
RIBBON_Y = CARD_Y + CARD_H / 2 + 0.136
INFO_Y = CARD_Y + CARD_H / 2 + 0.066
FOOTER_Y = CARD_Y - CARD_H / 2 - 0.066
BAR_Y = CARD_Y - CARD_H / 2 - 0.105
HINT_Y = CARD_Y - CARD_H / 2 - 0.143

UNIFONT = "/usr/share/fonts/opentype/unifont/unifont.otf"


def text_width(text, bold=False):
    w = 0
    for ch in text:
        o = ord(ch)
        if 0x1100 <= o <= 0x11FF or 0x3000 <= o <= 0x9FFF or 0xAC00 <= o <= 0xD7A3:
            w += 9
        elif ch in "i.,:;!|'·":
            w += 2
        elif ch in "l`":
            w += 3
        elif ch in " It[](){}\"*":
            w += 4
        elif ch in "fk<>":
            w += 5
        else:
            w += 6
        w += 1 if bold else 0
    return w


def wrap(text, width, lines):
    out, rest = [], text.strip()
    while rest and len(out) < lines:
        used, cut = 0, len(rest)
        for i, ch in enumerate(rest):
            used += text_width(ch)
            if used > width:
                cut = i
                break
        if cut < len(rest):
            space = rest.rfind(" ", 0, cut)
            if space >= cut // 2:
                cut = space
        out.append(rest[:cut].rstrip())
        rest = rest[cut:].lstrip()
    if rest and out:
        out[-1] = out[-1][:-1] + "…"
    return out


def to_px(sx, sy):
    return W / 2 + sx * H, H / 2 - sy * H


def paste_scaled(screen, im, cx, cy, unit, factor=1.0):
    """Draws `im` (1 px = 1 font unit of `unit` screen heights) centred at screen units (cx, cy)."""
    s = unit * H * factor
    w, h = max(1, round(im.width * s)), max(1, round(im.height * s))
    big = im.resize((w, h), Image.NEAREST)
    x, y = to_px(cx, cy)
    screen.alpha_composite(big, (round(x - w / 2), round(y - h / 2)))


def plate(screen, x, y, w, u):
    """The dark background of a text display: one unit round the text block."""
    layer = Image.new("RGBA", screen.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rectangle((x - u, y - u, x + w + u, y + 9 * u), fill=PLATE)
    screen.alpha_composite(layer)


def draw_text(screen, text, cx, cy, unit, colour=(255, 255, 255), bold=False, anchor="center", factor=1.0,
              background=False):
    u = unit * H * factor
    font = ImageFont.truetype(UNIFONT, max(6, round(8 * u * 2) // 2 * 1))
    d = ImageDraw.Draw(screen)
    x, y = to_px(cx, cy)
    w = text_width(text, bold) * u
    if anchor == "center":
        x -= w / 2
    y -= 4.5 * u
    if background:
        plate(screen, x, y, w, u)
    shadow = tuple(c // 4 for c in colour)
    for ox in ((0, 0.5) if bold else (0,)):
        d.text((x + u + ox * u, y + u), text, font=font, fill=shadow)
    for ox in ((0, 0.5) if bold else (0,)):
        d.text((x + ox * u, y), text, font=font, fill=colour)


def draw_rich(screen, parts, cy, unit):
    """One text line of plain text and inline small key caps (as the hint line of OfferCards)."""
    u = unit * H
    width = sum(text_width(t) if kind == "t" else glyph_image(f"small_key_{t}.png").width + 1 for kind, t in parts)
    x = W / 2 - width * u / 2
    _, y = to_px(0, cy)
    plate(screen, x, y - 4.5 * u, width * u, u)
    font = ImageFont.truetype(UNIFONT, max(6, round(8 * u)))
    d = ImageDraw.Draw(screen)
    for kind, t in parts:
        if kind == "t":
            colour = (255, 255, 255) if t.strip() not in ("+", "웅크리기+") else (190, 190, 190)
            d.text((x + u, y - 4.5 * u + u), t, font=font, fill=tuple(c // 4 for c in colour))
            d.text((x, y - 4.5 * u), t, font=font, fill=colour)
            x += text_width(t) * u
        else:
            im = glyph_image(f"small_key_{t}.png")
            big = im.resize((round(im.width * u), round(im.height * u)), Image.NEAREST)
            screen.alpha_composite(big, (round(x), round(y - 6.5 * u)))
            x += (im.width + 1) * u


def backdrop():
    """A daytime Minecraft-like view: sky, distant hills, a grassy field, a few trees."""
    im = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        sky = tuple(int(a + (b - a) * min(1, t * 1.6)) for a, b in zip((120, 167, 255), (190, 214, 255)))
        d.line([(0, y), (W, y)], fill=(*sky, 255))
    import random
    rnd = random.Random(7)
    block = 24
    for layer, (base, colour) in enumerate(((560, (110, 150, 120)), (610, (92, 140, 70)), (690, (100, 160, 60)))):
        h = base
        for x in range(0, W, block * (3 - layer)):
            h += rnd.choice((-1, 0, 0, 1)) * block // (layer + 1)
            h = max(base - 90, min(base + 60, h))
            d.rectangle((x, h, x + block * (3 - layer), H), fill=(*colour, 255))
            if layer == 2:
                d.rectangle((x, h + block // 2, x + block * 1, H), fill=(121, 85, 58, 255))
    for x, y in ((260, 520), (1500, 540), (1720, 600)):
        d.rectangle((x, y, x + 24, y + 120), fill=(102, 76, 51, 255))
        d.rectangle((x - 60, y - 90, x + 84, y + 10), fill=(60, 120, 45, 255))
    return im


def hud(screen, gui):
    """Crosshair, hotbar, hearts, food, XP bar, one boss bar and the sidebar at GUI scale `gui`."""
    d = ImageDraw.Draw(screen)
    g = gui
    cx, cy = W // 2, H // 2
    d.rectangle((cx - g // 2, cy - 7 * g // 2, cx + g // 2, cy + 7 * g // 2), fill=(230, 230, 230, 255))
    d.rectangle((cx - 7 * g // 2, cy - g // 2, cx + 7 * g // 2, cy + g // 2), fill=(230, 230, 230, 255))
    hb_w, hb_h = 182 * g, 22 * g
    x0, y0 = cx - hb_w // 2, H - hb_h
    d.rectangle((x0, y0, x0 + hb_w, H), fill=(40, 40, 40, 200), outline=(140, 140, 140, 255), width=g)
    for i in range(9):
        d.rectangle((x0 + (3 + i * 20) * g, y0 + 3 * g, x0 + (19 + i * 20) * g, y0 + 19 * g),
                    outline=(90, 90, 90, 255), width=g)
    d.rectangle((x0 + 1 * g, y0 + 1 * g, x0 + 21 * g, y0 + 21 * g), outline=(240, 240, 240, 255), width=g)
    d.rectangle((x0 + 5 * g, y0 + 5 * g, x0 + 17 * g, y0 + 17 * g), fill=(190, 190, 200, 255))   # a sword-ish item
    xp_y = y0 - 7 * g
    d.rectangle((x0, xp_y, x0 + hb_w, xp_y + 5 * g), fill=(20, 20, 20, 255))
    d.rectangle((x0, xp_y, x0 + hb_w * 2 // 5, xp_y + 5 * g), fill=(128, 255, 32, 255))
    for i in range(10):
        hx = x0 + i * 8 * g
        d.rectangle((hx, xp_y - 11 * g, hx + 7 * g, xp_y - 4 * g), fill=(220, 30, 30, 255))
        fx = x0 + hb_w - (i + 1) * 8 * g
        d.rectangle((fx, xp_y - 11 * g, fx + 7 * g, xp_y - 4 * g), fill=(170, 110, 50, 255))
    bb_w = 182 * g
    d.rectangle((cx - bb_w // 2, 12 * g, cx + bb_w // 2, 17 * g), fill=(60, 20, 20, 255))
    d.rectangle((cx - bb_w // 2, 12 * g, cx - bb_w // 2 + bb_w * 3 // 5, 17 * g), fill=(255, 85, 85, 255))
    font = ImageFont.truetype(UNIFONT, 8 * g)
    title = "PvP까지 12:41"
    d.text((cx - text_width(title) * g / 2, 2 * g), title, font=font, fill=(255, 255, 255, 255))
    lines = ["증강 야생전", "파밍 PvP까지 12:41", "다음 증강 3:20", "생존자 6명", "내 킬 0", "내 증강 1 (리롤 2)"]
    sw = 110 * g
    sx, sy = W - sw - 2 * g, H // 2 - len(lines) * 9 * g // 2
    d.rectangle((sx, sy, W - 2 * g, sy + len(lines) * 9 * g), fill=(0, 0, 0, 80))
    for i, line in enumerate(lines):
        d.text((sx + 2 * g, sy + i * 9 * g), line, font=font, fill=(255, 255, 255, 255))


def glyph_image(name):
    return Image.open(TEXTURES / name).convert("RGBA")


def card_row(n):
    body_w = 96 * TX
    total = n * body_w + (n - 1) * CARD_GAP
    k = min(1.0, MAX_ROW / total)
    step = (body_w + CARD_GAP) * k
    return [((i - (n - 1) / 2) * step, k) for i in range(n)]


def draw_cards(screen, tier, cards, highlight=-1, rerolls=2, seconds=23, total=30, source="1번째 라운드",
               queued=0):
    row = card_row(len(cards))
    for i, (aid, name, _, kind, description) in enumerate(cards):
        x, k = row[i]
        lit = i == highlight
        s = k * (HIGHLIGHT_SCALE if lit else 1.0)
        y = CARD_Y
        paste_scaled(screen, glyph_image(f"frame_{tier}{'_lit' if lit else ''}.png"), x, y, TX, s)
        paste_scaled(screen, glyph_image(f"icon_{aid}.png"), x, y + ICON_Y * TX * s, TX, s)
        draw_text(screen, name, x, y + NAME_Y * TX * s, NAME_UNIT, bold=True, factor=s)
        kind_ko = {v: k2 for k2, v in KIND.items()}[kind]
        tier_colour = {"silver": (201, 214, 223), "gold": (255, 200, 61), "prism": (213, 140, 255)}[tier]
        units = DESC_W * TX / DESC_UNIT
        lines = [("◆ " + kind_ko, tier_colour)] + [(l, (235, 235, 235)) for l in
                                                   wrap(re.sub(r"\(.*?\)", "", description), units, DESC_LINES - 1)]
        top = y + DESC_MID * TX * s + (10 * len(lines) - 1) / 2 * DESC_UNIT * s
        for j, (line, colour) in enumerate(lines):
            draw_text(screen, line, x, top - (j * 10 + 4.5) * DESC_UNIT * s, DESC_UNIT, colour, factor=s)
        key = glyph_image(f"key_{i + 1}{'_lit' if lit else ''}.png")
        fy = FOOTER_Y
        if rerolls > 0:
            paste_scaled(screen, key, x - 10 * TX * k, fy, TX, k)
            paste_scaled(screen, glyph_image(f"reroll{'_lit' if lit else ''}.png"), x + 10 * TX * k, fy, TX, k)
        else:
            paste_scaled(screen, key, x, fy, TX, k)
    tier_ko = {"silver": "실버", "gold": "골드", "prism": "프리즘"}[tier]
    paste_scaled(screen, glyph_image(f"ribbon_{tier}.png"), 0, RIBBON_Y, TX)
    draw_text(screen, f"증강 선택 · {tier_ko}", 0, RIBBON_Y, NAME_UNIT, bold=True)
    info = f"{source} · 리롤 {rerolls}회" + (f" · 다음 증강 {queued}개" if queued else "")
    draw_text(screen, info, 0, INFO_Y, INFO_UNIT, (225, 225, 225), background=True)
    level = round(card_art.BAR_STEPS * seconds / total)
    paste_scaled(screen, glyph_image(f"bar_{level:02d}.png"), 0, BAR_Y, TX)
    draw_text(screen, f"{seconds}초", (card_art.BAR_W / 2 + 4) * TX + 14 * INFO_UNIT, BAR_Y, INFO_UNIT,
              (255, 85, 85) if seconds <= 5 else (255, 255, 255), bold=True, background=True)
    keys = [("k", str(n)) for n in range(1, len(cards) + 1)]
    parts = keys + [("t", " 선택  ")] + [("k", "f"), ("t", " 확정  ")]
    if rerolls > 0:
        parts += [("t", "웅크리기+"), ("k", "1"), ("t", " 리롤  ")]
    parts += [("k", "q"), ("t", " 접기")]
    draw_rich(screen, parts, HINT_Y, HINT_UNIT)


def preview(out_dir):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    table = {a[0]: a for a in augments()}

    def pick(*ids):
        return [table[i] for i in ids]

    shots = [
        ("cards_silver_gui2.png", 2, "silver", pick("iron_body", "feather_fall", "sharp_edge"), -1, 2, 27, 0),
        ("cards_gold_highlight_gui2.png", 2, "gold", pick("bloodthirst", "double_jump", "smith_blessing"), 1, 2, 18,
         1),
        ("cards_prism_highlight_gui3.png", 3, "prism", pick("phoenix_feather", "wings", "war_god"), 0, 1, 4, 0),
        ("cards_gold_4_gui3.png", 3, "gold", pick("triple_shot", "executioner", "gambler", "still_cloak"), 3, 0, 12,
         0),
    ]
    for name, gui, tier, cards, hl, rerolls, seconds, queued in shots:
        screen = backdrop()
        hud(screen, gui)
        draw_cards(screen, tier, cards, hl, rerolls, seconds, 30, "1번째 라운드" if tier != "prism" else "보너스: 첫 번째 피",
                   queued)
        screen.convert("RGB").save(out_dir / name.replace(".png", "_v3.png"))
    # every icon, 4x, with its name
    items = sorted(augments())
    cols = 10
    cell = 150
    sheet = Image.new("RGBA", (cols * cell, ((len(items) + cols - 1) // cols) * cell), (34, 30, 44, 255))
    font = ImageFont.truetype(UNIFONT, 16)
    d = ImageDraw.Draw(sheet)
    for i, (aid, name, tier, kind, _) in enumerate(items):
        x, y = (i % cols) * cell, (i // cols) * cell
        ic = glyph_image(f"icon_{aid}.png").resize((128, 128), Image.NEAREST)
        sheet.alpha_composite(ic, (x + 11, y + 2))
        d.text((x + cell / 2 - text_width(name) * 1, y + 130), name, font=font, fill=(255, 255, 255, 255))
    sheet.save(out_dir / "icons_v3.png")
    # the frames, normal and lit, 3x
    fr = Image.new("RGBA", (6 * 112 * 3 + 50, 160 * 3 + 20), (34, 30, 44, 255))
    for i, tier in enumerate(TIERS):
        for j, lit in enumerate((False, True)):
            im = glyph_image(f"frame_{tier}{'_lit' if lit else ''}.png").resize((336, 480), Image.NEAREST)
            fr.alpha_composite(im, (10 + (i * 2 + j) * 345, 10))
    fr.save(out_dir / "frames_v3.png")
    print(f"previews -> {out_dir}")


def main():
    args = sys.argv[1:]
    write()
    if "--preview" in args:
        preview(args[args.index("--preview") + 1])


if __name__ == "__main__":
    main()
