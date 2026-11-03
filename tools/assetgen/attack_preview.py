"""
Attack animation GIFs of the 공성전 v2 creatures, drawn from the very poses the game shows: ModelCreatureLook's
display transformations of every tick of each kind's attack (CreatureModels.Attack), exported by
`./gradlew updateCreatureAttacks` to models/creature_attacks.json (CreatureAttacksExportTest fails while it is stale).

    python3 -I tools/assetgen/attack_preview.py <out dir> [kind ...]

Writes <kind>.gif (one tick per frame, 20 fps like the game, a player silhouette in front as the target) and
all.gif (every kind side by side, striking together). The attack particles (the cannon's smoke, the dragon's breath)
are drawn as little cubes drifting the way the game's puffs drift. Needs node + playwright + three (see render/) and
Pillow (else the frame sheets <kind>.png are left as they are).
"""

import base64
import io
import json
import math
import random
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from models import creatures  # noqa: E402
import toy_preview  # noqa: E402

RENDER = HERE / "render" / "render.mjs"
ATTACKS = HERE / "models" / "creature_attacks.json"
WORK = HERE.parent.parent / "build" / "assetgen" / "attacks"

FRAME_W, FRAME_H, COLUMNS = 400, 340, 4
BEFORE, AFTER = 4, 5  # rest frames around the attack
SPACING = 60.0  # copies of the scene, one per frame, side by side out of each other's view
AZ, EL = 62.0, 12.0  # the camera: the creature's right, a little in front, a little above

# kind: (title, target distance in front, camera target height, camera distance, team tint)
KINDS = {
    "soldier": ("태엽 병정 · 칼 찌르기", 1.5, 0.8, 4.4, toy_preview.RED),
    "archer": ("장난감 궁수 · 활 당겼다 놓기", 3.0, 0.8, 6.4, toy_preview.RED),
    "cannon": ("장난감 대포 · 발사 반동", 3.6, 0.8, 7.2, toy_preview.BLUE),
    "jelly": ("젤리 · 통통 내려찍기", 1.4, 0.7, 4.8, None),
    "mouse": ("태엽 쥐 · 튀어나가 깨물기", 1.2, 0.6, 4.2, None),
    "teddy": ("곰인형 대장 · 두 팔 휘두르기", 1.9, 1.1, 6.4, None),
    "dragon": ("장난감 드래곤 · 숨결", 5.0, 1.9, 15.0, None),
    "rabbit": ("시계 토끼 · 깜짝 깡충 (맞을 때)", 1.8, 0.8, 5.0, None),
    "merchant": ("태엽 상인 · 인사 (상점 열 때)", 1.5, 0.9, 4.8, None),
}
PUFF_COLOURS = {"cloud": (250, 250, 250), "smoke": (110, 110, 118)}


def render(json_path, png_path):
    subprocess.run(["node", str(RENDER), str(json_path), str(png_path)], check=True)


def _quat_matrix(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def _display_matrix(pose, at):
    """Column-major 4x4 of an item display at `at` (yaw 0: the model's front -z) with a Transformation `pose`:
    world = at + t + R(left rotation) * scale * (p / 16 - 0.5)."""
    r = _quat_matrix(pose["r"])
    s = pose["s"]
    m = [[r[i][j] * s[j] / 16 for j in range(3)] for i in range(3)]
    t = [at[i] + pose["t"][i] - sum(m[i][j] * 8 for j in range(3)) for i in range(3)]
    return [m[0][0], m[1][0], m[2][0], 0, m[0][1], m[1][1], m[2][1], 0, m[0][2], m[1][2], m[2][2], 0,
            t[0], t[1], t[2], 1]


def posed(built, kind, frame, tint, at):
    """The creature's parts in one frame's poses (the game's display transformations)."""
    textures, elements = {}, []
    for (part, _fn, _scale, _centre, _motion), pose in zip(creatures.CREATURES[kind], frame):
        data = built[creatures.model_id(kind, part)].to_preview(tint)
        mat = _display_matrix(pose, at)
        for e in data["elements"]:
            e["matrix"] = mat
        textures.update(data["textures"])
        elements.extend(data["elements"])
    return textures, elements


_PUFF_TEXTURE = None


def _puff_texture():
    global _PUFF_TEXTURE
    if _PUFF_TEXTURE is None:
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGBA", (16, 16), (255, 255, 255, 255)).save(buf, "PNG")
        _PUFF_TEXTURE = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    return _PUFF_TEXTURE


def _cube(centre, size, colour):
    half = size / 2
    faces = {f: {"uv": [0, 0, 16, 16], "texture": "puff"} for f in ("north", "south", "east", "west", "up", "down")}
    k = size / 16
    mat = [k, 0, 0, 0, 0, k, 0, 0, 0, 0, k, 0, centre[0] - half, centre[1] - half, centre[2] - half, 1]
    return {"from": [0, 0, 0], "to": [16, 16, 16], "glow": False, "tint": list(colour), "translucent": False,
            "rotation": None, "faces": faces, "matrix": mat}


def particles(puffs, tick, at, seed):
    """The game's puffs (ModelCreatureLook.puff): count particles at forward / up with a gaussian spread, drifting
    ahead (-z) and a little up at `speed` blocks per tick, slowing down like vanilla clouds; drawn shrinking."""
    out = []
    rng = random.Random(seed)
    for p in puffs:
        for _ in range(p["count"]):
            jitter = [rng.gauss(0, p["spread"]) for _ in range(3)]
            life = rng.randint(7, 11)
            age = tick - p["tick"]
            if age < 0 or age >= life:
                continue
            travelled = p["speed"] * (1 - 0.86 ** age) / 0.14 if age else 0
            x = at[0] + jitter[0]
            y = at[1] + p["up"] + jitter[1] + 0.15 * travelled + 0.02 * age
            z = at[2] - p["forward"] + jitter[2] - travelled
            size = 0.22 * (1 - age / life) + 0.05
            out.append(_cube((x, y, z), size, PUFF_COLOURS.get(p["particle"], (240, 240, 240))))
    return out


def frames_of(table):
    """Rest, the attack's ticks, rest: (frame poses, attack tick or None)."""
    rest = table["frames"][0]
    seq = [(rest, None)] * BEFORE
    seq += [(f, t) for t, f in enumerate(table["frames"])]
    seq += [(rest, None)] * AFTER
    return seq


def sheet(built, kind, table, out_png, total=None):
    """Every frame of the kind's attack as one view each, on one canvas (one browser start)."""
    _title, gap, target_y, dist, tint = KINDS[kind]
    seq = frames_of(table)
    if total and len(seq) < total:
        seq += [(table["frames"][0], None)] * (total - len(seq))
    rows = math.ceil(len(seq) / COLUMNS)
    perp = (math.cos(math.radians(AZ)), 0.0, math.sin(math.radians(AZ)))
    textures, elements, views = {"puff": _puff_texture()}, [], []
    for i, (frame, tick) in enumerate(seq):
        at = (perp[0] * SPACING * i, 0.0, perp[2] * SPACING * i)
        t, e = posed(built, kind, frame, tint, at)
        textures.update(t)
        elements.extend(e)
        if tick is not None and table["puffs"]:
            elements.extend(particles(table["puffs"], tick, at, seed=7))
        t, e = toy_preview.figure(at[0], at[2] - gap - 0.3, 180)
        textures.update(t)
        elements.extend(e)
        views.append({"x": (i % COLUMNS) * FRAME_W, "y": (i // COLUMNS) * FRAME_H, "w": FRAME_W, "h": FRAME_H,
                      "az": AZ, "el": EL, "target": [at[0], target_y, at[2] - gap / 2], "dist": dist,
                      "background": toy_preview.SKY})
    reach = SPACING * len(seq) + 40
    model = {"width": COLUMNS * FRAME_W, "height": rows * FRAME_H, "textures": textures, "elements": elements,
             "grid": False, "views": views, "groundColor": toy_preview.GRASS, "ground": [-reach, 0, -reach, reach, reach]}
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / f"attack_{kind}.preview.json"
    path.write_text(json.dumps(model))
    render(path, out_png)
    return len(seq)


def _font(size):
    from PIL import ImageFont
    for path in ("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def cut(png, count, title):
    from PIL import Image, ImageDraw
    sheet_image = Image.open(png).convert("RGB")
    font = _font(20)
    frames = []
    for i in range(count):
        x, y = (i % COLUMNS) * FRAME_W, (i // COLUMNS) * FRAME_H
        frame = sheet_image.crop((x, y, x + FRAME_W, y + FRAME_H))
        draw = ImageDraw.Draw(frame)
        draw.rectangle((0, 0, FRAME_W, 30), fill=(255, 255, 255))
        draw.text((10, 4), title, fill=(60, 50, 80), font=font)
        frames.append(frame)
    return frames


def save_gif(frames, path):
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=50, loop=0, optimize=False,
                   disposal=1)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    out = Path(args[0])
    out.mkdir(parents=True, exist_ok=True)
    kinds = args[1:] or list(KINDS)
    table = json.loads(ATTACKS.read_text())["creatures"]
    built = {name: fn() for name, fn in creatures.ALL.items()}
    total = max(len(frames_of(table[k])) for k in kinds)
    try:
        import PIL  # noqa: F401
        have_pil = True
    except ImportError:
        have_pil = False
    cut_frames = {}
    for kind in kinds:
        png = out / f"{kind}.png"
        count = sheet(built, kind, table[kind], png, total)
        if have_pil:
            cut_frames[kind] = cut(png, count, KINDS[kind][0])
            own = len(frames_of(table[kind]))
            save_gif(cut_frames[kind][:own], out / f"{kind}.gif")
            print(f"gif     {out / (kind + '.gif')}: {own} frames")
    if have_pil and len(kinds) > 1:
        from PIL import Image
        columns = 3
        rows = math.ceil(len(kinds) / columns)
        combined = []
        for i in range(total):
            canvas = Image.new("RGB", (columns * FRAME_W, rows * FRAME_H), (255, 255, 255))
            for n, kind in enumerate(kinds):
                canvas.paste(cut_frames[kind][i], ((n % columns) * FRAME_W, (n // columns) * FRAME_H))
            combined.append(canvas)
        save_gif(combined, out / "all.gif")
        print(f"gif     {out / 'all.gif'}: {total} frames")


if __name__ == "__main__":
    main()
