"""
Preview GIFs of the 공성전 basic attacks (평타): the display entities BasicFx gives the game, three attacks in a row
(plain, critical, finisher) at an enemy in front, exported tick by tick by `./gradlew updateBasicAttackFx` to
models/basic_attack_fx.json (BasicAttackFxExportTest fails while it is stale).

    python3 -I tools/assetgen/basic_attack_preview.py <out dir> [job ...]

Writes <job>.gif (one tick per frame, 20 fps like the game) and all.gif (every job side by side). Vanilla blocks are
drawn as flat-colored boxes (stained glass translucent, cores glowing), items as a diagonal blade or a plate, text
(“딱!”, notes) as a small glowing tile. Needs node + playwright + three (see render/) and Pillow.
"""

import base64
import io
import json
import math
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import toy_preview  # noqa: E402

RENDER = HERE / "render" / "render.mjs"
FX = HERE / "models" / "basic_attack_fx.json"
WORK = HERE.parent.parent / "build" / "assetgen" / "basic-fx"

FRAME_W, FRAME_H, COLUMNS = 360, 300, 6
BEFORE, AFTER = 3, 3  # rest frames around the attacks
SPACING = 80.0  # copies of the scene, one per frame, out of each other's view
AZ, EL = -32.0, 38.0  # behind the attacker's right shoulder, from above (slash arcs read best)

GLASS = {
    "white": (238, 240, 246), "light_blue": (110, 185, 255), "blue": (60, 100, 230), "light_gray": (175, 178, 186),
    "gray": (110, 112, 120), "orange": (250, 145, 50), "yellow": (250, 215, 60), "lime": (135, 225, 60),
    "green": (70, 150, 55), "red": (230, 55, 55), "cyan": (50, 195, 205), "purple": (155, 75, 215),
    "pink": (245, 135, 185), "brown": (145, 95, 55), "black": (40, 40, 46), "magenta": (205, 85, 205),
}
SOLID = {
    "white_concrete": (250, 250, 252), "shroomlight": (255, 165, 75), "redstone_block": (215, 30, 25),
    "gold_block": (255, 212, 65), "sea_lantern": (205, 238, 240), "snow_block": (244, 250, 255),
    "dripstone_block": (150, 118, 92), "pointed_dripstone": (160, 125, 98), "black_concrete": (24, 24, 30),
    "verdant_froglight": (200, 236, 155), "pearlescent_froglight": (240, 198, 238), "lime_concrete": (105, 195, 35),
    "magenta_concrete": (190, 70, 190), "light_blue_concrete": (60, 170, 220), "orange_concrete": (235, 115, 20),
    "magma_block": (200, 80, 30), "tnt": (215, 55, 45), "blue_ice": (120, 175, 250), "crying_obsidian": (60, 20, 110),
}
ITEMS = {
    "iron_sword": (220, 225, 235), "diamond_sword": (100, 225, 225), "netherite_sword": (75, 65, 75),
    "iron_axe": (215, 218, 228), "iron_pickaxe": (205, 208, 218), "iron_hoe": (210, 214, 222),
    "netherite_hoe": (80, 70, 82), "iron_spear": (210, 215, 225), "stick": (150, 108, 58), "breeze_rod": (170, 190, 255),
    "mace": (120, 118, 125), "shield": (165, 125, 75), "fishing_rod": (140, 100, 55), "tripwire_hook": (200, 200, 205),
    "arrow": (205, 195, 170), "feather": (245, 240, 230), "flint": (90, 90, 95), "nether_star": (245, 245, 215),
    "fire_charge": (250, 120, 40), "skeleton_skull": (230, 230, 220), "splash_potion": (120, 220, 80),
    "clock": (240, 205, 70), "cobweb": (240, 240, 245), "tnt": (215, 55, 45), "magma_block": (200, 80, 30),
    "blue_ice": (120, 175, 250), "lime_concrete": (105, 195, 35), "crying_obsidian": (90, 35, 150),
}
BLADES = {"iron_sword", "diamond_sword", "netherite_sword", "iron_axe", "iron_pickaxe", "iron_hoe", "netherite_hoe",
          "iron_spear", "stick", "breeze_rod", "mace", "fishing_rod", "arrow", "feather", "flint", "tripwire_hook"}
STEP_LABELS = ["1타", "2타 · 치명타!", "3타 · 피니시"]

_TEXTURES = None


def textures():
    global _TEXTURES
    if _TEXTURES is None:
        from PIL import Image

        def png(alpha):
            buf = io.BytesIO()
            Image.new("RGBA", (16, 16), (255, 255, 255, alpha)).save(buf, "PNG")
            return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()

        _TEXTURES = {"solid": png(255), "glass": png(120)}
    return _TEXTURES


def _quat_matrix(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def _box(matrix_rows, translation, colour, translucent, glow, frm=(0, 0, 0), to=(16, 16, 16)):
    faces = {f: {"uv": [0, 0, 16, 16], "texture": "glass" if translucent else "solid"}
             for f in ("north", "south", "east", "west", "up", "down")}
    m = matrix_rows
    mat = [m[0][0], m[1][0], m[2][0], 0, m[0][1], m[1][1], m[2][1], 0, m[0][2], m[1][2], m[2][2], 0,
           translation[0], translation[1], translation[2], 1]
    return {"from": list(frm), "to": list(to), "glow": glow, "tint": list(colour), "translucent": translucent,
            "rotation": None, "faces": faces, "matrix": mat}


def element(kind, what, colour, pose, offset):
    """One display in one frame: [cx, cy, cz, qx, qy, qz, qw, sx, sy, sz] placed `offset` away."""
    c = [pose[0] + offset[0], pose[1] + offset[1], pose[2] + offset[2]]
    r = _quat_matrix(pose[3:7])
    s = pose[7:10]
    if max(s) < 0.01:
        return []
    if kind == "block":
        glass = what.endswith("_stained_glass")
        rgb = GLASS.get(what[:-len("_stained_glass")], (200, 200, 200)) if glass else SOLID.get(what, (200, 200, 200))
        # world = c + R * (s * p / 16 - s / 2)
        if what == "pointed_dripstone":  # the block's model is a thin spike, not a cube
            s = [s[0] * 0.4, s[1], s[2] * 0.4]
        m = [[r[i][j] * s[j] / 16 for j in range(3)] for i in range(3)]
        t = [c[i] - sum(r[i][j] * s[j] / 2 for j in range(3)) for i in range(3)]
        return [_box(m, t, rgb, glass, not glass)]
    if kind == "text":
        k = 0.17 * s[0]
        m = [[k / 16, 0, 0], [0, k / 16, 0], [0, 0, k / 16]]
        rgb = ((colour >> 16) & 255, (colour >> 8) & 255, colour & 255)
        return [_box(m, [c[0] - k / 2, c[1] - k / 2, c[2] - k / 2], rgb, False, True)]
    rgb = ITEMS.get(what, SOLID.get(what, (210, 210, 210)))
    # an item model: one block wide, centred; blades as a diagonal bar, the rest as a plate
    m = [[r[i][j] * s[j] / 16 for j in range(3)] for i in range(3)]
    if what in BLADES:
        # the bar from (-0.45, -0.45) to (0.45, 0.45) in the item's plane: a box along its own diagonal
        diag = [[math.cos(math.pi / 4), -math.sin(math.pi / 4), 0], [math.sin(math.pi / 4), math.cos(math.pi / 4), 0],
                [0, 0, 1]]
        md = [[sum(m[i][k] * diag[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
        length, width = 1.1 * 16, 0.16 * 16
        frm = (-length / 2, -width / 2, -0.5)
        to = (length / 2, width / 2, 0.5)
        return [_box(md, c, rgb, False, False, frm, to)]
    return [_box(m, c, rgb, False, False, (-6, -6, -0.6), (6, 6, 0.6))]


def frames_of(job):
    return list(range(-BEFORE, job["ticks"] + AFTER))


def sheet(job, out_png, count):
    ticks = frames_of(job)
    ticks += [ticks[-1]] * (count - len(ticks))
    rows = math.ceil(len(ticks) / COLUMNS)
    perp = (math.cos(math.radians(AZ + 90)), 0.0, math.sin(math.radians(AZ + 90)))
    tex, elements, views = dict(textures()), [], []
    far = max(t[1] for t in job["targets"])
    for i, tick in enumerate(ticks):
        at = (perp[0] * SPACING * i, 0.0, perp[2] * SPACING * i)
        for sprite in job["sprites"]:
            frame = tick - sprite["t"]
            if 0 <= frame < len(sprite["p"]):
                elements.extend(element(sprite["k"], sprite["m"], sprite["c"], sprite["p"][frame], at))
        t, e = toy_preview.figure(at[0], at[2], 0)
        tex.update(t)
        elements.extend(e)
        for x, z in job["targets"]:
            t, e = toy_preview.figure(at[0] + x, at[2] + z, 180)
            tex.update(t)
            elements.extend(e)
        views.append({"x": (i % COLUMNS) * FRAME_W, "y": (i // COLUMNS) * FRAME_H, "w": FRAME_W, "h": FRAME_H,
                      "az": AZ, "el": EL, "target": [at[0], 1.0, at[2] + far / 2], "dist": max(6.5, far * 1.15 + 4),
                      "background": toy_preview.SKY})
    reach = SPACING * len(ticks) + 60
    model = {"width": COLUMNS * FRAME_W, "height": rows * FRAME_H, "textures": tex, "elements": elements,
             "grid": False, "views": views, "groundColor": toy_preview.GRASS,
             "ground": [-reach, 0, -reach, reach, reach]}
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / f"fx_{job['id']}.preview.json"
    path.write_text(json.dumps(model))
    subprocess.run(["node", str(RENDER), str(path), str(out_png)], check=True)
    return ticks


def _font(size):
    from PIL import ImageFont
    for path in ("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
                 "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def cut(png, job, ticks):
    from PIL import Image, ImageDraw
    image = Image.open(png).convert("RGB")
    font, small = _font(18), _font(15)
    frames = []
    for i, tick in enumerate(ticks):
        x, y = (i % COLUMNS) * FRAME_W, (i // COLUMNS) * FRAME_H
        frame = image.crop((x, y, x + FRAME_W, y + FRAME_H))
        draw = ImageDraw.Draw(frame)
        draw.rectangle((0, 0, FRAME_W, 26), fill=(255, 255, 255))
        draw.text((8, 3), job["title"], fill=(60, 50, 80), font=font)
        step = min(2, max(0, tick) // job["cooldown"]) if tick >= 0 else 0
        draw.text((8, FRAME_H - 22), STEP_LABELS[step], fill=(40, 40, 60), font=small)
        frames.append(frame)
    return frames


def save_gif(frames, path):
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=50, loop=0, optimize=False, disposal=1)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    out = Path(args[0])
    out.mkdir(parents=True, exist_ok=True)
    jobs = {job["id"]: job for job in json.loads(FX.read_text())["jobs"]}
    chosen = args[1:] or list(jobs)
    count = max(len(frames_of(jobs[j])) for j in chosen)
    cut_frames = {}
    for name in chosen:
        job = jobs[name]
        png = WORK / f"fx_{name}.png"
        WORK.mkdir(parents=True, exist_ok=True)
        ticks = sheet(job, png, count)
        cut_frames[name] = cut(png, job, ticks)
        own = len(frames_of(job))
        save_gif(cut_frames[name][:own], out / f"{name}.gif")
        print(f"gif     {out / (name + '.gif')}: {own} frames")
    if len(chosen) > 1:
        from PIL import Image
        columns = 5
        rows = math.ceil(len(chosen) / columns)
        combined = []
        for i in range(count):
            canvas = Image.new("RGB", (columns * FRAME_W, rows * FRAME_H), (255, 255, 255))
            for n, name in enumerate(chosen):
                canvas.paste(cut_frames[name][i], ((n % columns) * FRAME_W, (n // columns) * FRAME_H))
            combined.append(canvas)
        save_gif(combined, out / "all.gif")
        combined[min(len(combined) - 1, BEFORE + 4)].save(out / "all.png")
        print(f"gif     {out / 'all.gif'}: {count} frames")


if __name__ == "__main__":
    main()
