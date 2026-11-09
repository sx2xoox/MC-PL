"""
Attack animation GIFs of the 공성전 v2 creatures, drawn from the very poses the game shows: ModelCreatureLook's
display transformations of every tick of each kind's attack (CreatureModels.Attack), exported by
`./gradlew updateCreatureAttacks` to models/creature_attacks.json (CreatureAttacksExportTest fails while it is stale).

    python3 -I tools/assetgen/attack_preview.py <out dir> [kind ...] [--motions] [--hits]

Writes <kind>.gif (the basic attack, one tick per frame, 20 fps like the game, a player silhouette in front as the
target) and all.gif (every kind side by side, striking together). With --motions also every skill
(<kind>_<motion>.gif: the warning area marked on the ground filling up, as the game's particles do, then the motion)
and the walk cycle (<kind>_walk.gif: the toy's own gait, travelling forward as its body walks). The attack particles
(the cannon's smoke, the dragon's breath) are drawn as little cubes drifting the way the game's puffs drift. With
--hits only the hit reactions instead (<kind>_hit.gif and hits.gif: a normal hit, then a strong one - a critical hit
or finisher - from the player in front: the hitstop holding the impact pose, the lean away from the blow, the white
then red flash, the springy rebound; the material's sparks as little cubes flying back towards the attacker). Needs
node + playwright + three (see render/) and Pillow (else the frame sheets <kind>.png are left as they are).
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
    "soldier": ("태엽 병정 · 총검 찌르기", 1.5, 0.8, 4.4, toy_preview.RED),
    "archer": ("장난감 궁수 · 활 당겼다 놓기", 3.0, 0.8, 6.4, toy_preview.RED),
    "cannon": ("장난감 대포 · 발사 반동", 3.6, 0.8, 7.2, toy_preview.BLUE),
    "jelly": ("젤리 · 몸통 박치기", 1.4, 0.7, 4.8, None),
    "mouse": ("태엽 쥐 · 튀어나가 깨물기", 1.2, 0.6, 4.2, None),
    "teddy": ("곰인형 대장 · 앞발 내려치기", 1.9, 1.1, 6.4, None),
    "dragon": ("장난감 드래곤 · 깨물기", 5.0, 1.9, 15.0, None),
    "rabbit": ("시계 토끼 · 시곗바늘 던지기", 4.0, 0.8, 6.0, None),
    "merchant": ("태엽 상인 · 인사 (상점 열 때)", 1.5, 0.9, 4.8, None),
}
PUFF_COLOURS = {"cloud": (250, 250, 250), "smoke": (110, 110, 118), "item_slime": (255, 140, 190),
                "end_rod": (255, 250, 215)}
MOTION_TITLES = {"goop": "끈적 젤리 웅덩이", "squeak": "흩어지기 (찍찍)", "shockwave": "솜뭉치 충격파",
                 "wind": "태엽 감기 (순간이동)", "breath": "숨결", "tail": "꼬리 휩쓸기", "gust": "날개 돌풍",
                 "roar": "격노", "recoil": "발사 반동 (포탄 착탄 예고)", "slam": "앞발 내려치기 (예고)"}
AREA_COLOURS = {"danger": (255, 80, 100), "sticky": (255, 140, 190), "push": (158, 230, 255)}
WARN = 10  # frames of warning (the area filling up) before a skill's motion
WALK_SPEED = 0.12  # blocks a tick the body walks under the gait in the walk previews


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


def frames_of(table, area=None):
    """Rest, the motion's ticks, rest: (frame poses, motion tick or None, warning fill or None, travel). A motion
    with an area first shows WARN frames of its mark filling up; the mark is full as the blow lands."""
    rest = table["frames"][0]
    impact = max(1, table.get("impact", 1))
    seq = [(rest, None, None, 0.0)] * BEFORE
    if area:
        seq += [(rest, None, 0.6 * i / WARN, 0.0) for i in range(WARN)]
    for t, f in enumerate(table["frames"]):
        fill = 0.6 + 0.4 * min(1.0, t / impact) if area and t <= impact else None
        seq.append((f, t, fill, 0.0))
    seq += [(rest, None, None, 0.0)] * AFTER
    return seq


def walk_frames(walk):
    """Two walk cycles, the creature travelling forward (-z) as its body walks."""
    cycle = walk + walk
    return [(f, None, None, WALK_SPEED * i) for i, f in enumerate(cycle)]


def _dot(x, z, size, colour):
    return _cube((x, 0.04, z), size, colour)


def area_elements(area, fill, at):
    """The game's warning mark (Telegraphs): the outline and a second line growing out to it."""
    out = []
    colour = AREA_COLOURS.get(area["colour"], (255, 80, 100))
    cx, cz = at[0], at[2] - area["forward"]
    r = area["radius"]

    def ring(radius, size):
        n = max(10, int(2 * math.pi * radius / 0.45))
        for i in range(n):
            a = 2 * math.pi * i / n
            out.append(_dot(cx + math.cos(a) * radius, cz + math.sin(a) * radius, size, colour))

    def arc(radius, half, size):
        n = max(3, int(2 * half * radius / 0.45))
        for i in range(n + 1):
            a = -math.pi / 2 - half + 2 * half * i / n
            out.append(_dot(at[0] + math.cos(a) * radius, at[2] + math.sin(a) * radius, size, colour))

    if area["shape"] == "circle":
        ring(r, 0.14)
        if r * fill > 0.2:
            ring(r * fill, 0.1)
    else:
        half = math.radians(area["angle"] / 2)
        for side in (-half, half):
            a = -math.pi / 2 + side
            d = 0.6
            while d <= r:
                out.append(_dot(at[0] + math.cos(a) * d, at[2] + math.sin(a) * d, 0.14, colour))
                d += 0.45
        arc(r, half, 0.14)
        if r * fill > 0.6:
            arc(r * fill, half, 0.1)
    return out


def sheet(built, kind, seq, puffs, out_png, total=None, area=None, name="attack"):
    """Every frame of a sequence as one view each, on one canvas (one browser start)."""
    _title, gap, target_y, dist, tint = KINDS[kind]
    if total and len(seq) < total:
        last = seq[-1]
        seq = seq + [(last[0], None, None, last[3])] * (total - len(seq))
    rows = math.ceil(len(seq) / COLUMNS)
    perp = (math.cos(math.radians(AZ)), 0.0, math.sin(math.radians(AZ)))
    wide = area is not None and area["radius"] + area["forward"] > 3
    textures, elements, views = {"puff": _puff_texture()}, [], []
    for i, (frame, tick, fill, travel) in enumerate(seq):
        base = (perp[0] * SPACING * i, 0.0, perp[2] * SPACING * i)
        at = (base[0], 0.0, base[2] - travel)
        t, e = posed(built, kind, frame, tint, at)
        textures.update(t)
        elements.extend(e)
        if tick is not None and puffs:
            elements.extend(particles(puffs, tick, at, seed=7))
        if fill is not None and area:
            elements.extend(area_elements(area, fill, at))
        if name != "walk":
            t, e = toy_preview.figure(at[0], at[2] - gap - 0.3, 180)
            textures.update(t)
            elements.extend(e)
        views.append({"x": (i % COLUMNS) * FRAME_W, "y": (i // COLUMNS) * FRAME_H, "w": FRAME_W, "h": FRAME_H,
                      "az": AZ, "el": EL + (14 if area else 0), "target": [at[0], target_y, at[2] - gap / 2],
                      "dist": dist * (1.8 if wide else 1), "background": toy_preview.SKY})
    reach = SPACING * len(seq) + 40
    model = {"width": COLUMNS * FRAME_W, "height": rows * FRAME_H, "textures": textures, "elements": elements,
             "grid": False, "views": views, "groundColor": toy_preview.GRASS, "ground": [-reach, 0, -reach, reach, reach]}
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / f"{name}_{kind}.preview.json"
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


# the hit feel (HitMaterial / CreatureModels.TOPS / BODY_SCALES): spark colour, model top, half the body's width
HIT_LOOK = {
    "soldier": ((255, 255, 255), 2.09, 0.52), "archer": ((255, 255, 255), 1.86, 0.47),
    "cannon": ((255, 196, 80), 1.36, 0.45), "jelly": ((255, 140, 190), 0.82, 0.31),
    "mouse": ((255, 196, 80), 0.68, 0.25), "teddy": ((248, 248, 240), 2.74, 0.77),
    "rabbit": ((248, 248, 240), 2.51, 0.56), "dragon": ((255, 255, 255), 4.14, 1.17),
    "merchant": ((255, 255, 255), 2.22, 0.54),
}
WHITE_FLASH = (255, 255, 255)
RED_FLASH = (255, 120, 120)


def hit_frames(entry, meta):
    """Rest, a normal hit, rest, a strong hit, rest: (frame poses, hit tick, strong, caption)."""
    rest = entry["frames"][0]
    seq = [(rest, None, False, "") for _ in range(BEFORE)]
    for name, strong in (("hit", False), ("hitStrong", True)):
        hold = meta["hitstop"]["strong" if strong else "normal"]
        label = "강한 타격 (치명타·마무리)" if strong else "일반 타격"
        for t, frame in enumerate(entry[name]):
            if t == 0:
                caption = label
            elif t <= 1 + hold:
                caption = f"{label} · 히트스톱 {t}/{1 + hold}"
            else:
                caption = f"{label} · 휘청 복원"
            seq.append((frame, t, strong, caption))
        seq += [(rest, None, False, "") for _ in range(AFTER + 2)]
    return seq


def flash_of(tick, meta):
    white, red = meta["flash"]["white"], meta["flash"]["red"]
    if tick is None or tick < 1:
        return None
    if tick <= white:
        return WHITE_FLASH
    if tick <= white + red:
        return RED_FLASH
    return None


def hit_sparks(kind, tick, strong, at):
    """The material's sparks thrown from the struck side back towards the attacker (-z), falling and shrinking."""
    if tick is None or tick < 1:
        return []
    colour, top, half = HIT_LOOK[kind]
    age = tick - 1
    life = 7
    if age >= life:
        return []
    rng = random.Random(11 + (5 if strong else 0))
    out = []
    origin = (at[0], at[1] + top * 0.55, at[2] - half)
    for _ in range(14 if strong else 8):
        v = [rng.gauss(0, 0.6), rng.gauss(0, 0.42) + 0.35, rng.gauss(0, 0.6) - 1.0]
        n = math.sqrt(sum(c * c for c in v)) or 1
        speed = (0.35 if strong else 0.25) * (0.6 + rng.random() * 0.6)
        travelled = speed * (1 - 0.8 ** age) / 0.2
        x = origin[0] + v[0] / n * travelled
        y = origin[1] + v[1] / n * travelled - 0.02 * age * age
        z = origin[2] + v[2] / n * travelled
        size = (0.13 if strong else 0.1) * (1 - age / life) + 0.03
        out.append(_cube((x, y, z), size, colour))
    if strong and age < 2:
        out.append(_cube(origin, 0.5 - 0.2 * age, (255, 255, 230)))
    return out


def hit_sheet(built, kind, seq, meta, out_png, total=None):
    """Every frame of a hit sequence, the flash tinting the toy, its sparks flying."""
    _title, gap, target_y, dist, tint = KINDS[kind]
    if total and len(seq) < total:
        seq = seq + [(seq[-1][0], None, False, "")] * (total - len(seq))
    rows = math.ceil(len(seq) / COLUMNS)
    perp = (math.cos(math.radians(AZ)), 0.0, math.sin(math.radians(AZ)))
    textures, elements, views = {"puff": _puff_texture()}, [], []
    for i, (frame, tick, strong, _caption) in enumerate(seq):
        at = (perp[0] * SPACING * i, 0.0, perp[2] * SPACING * i)
        t, e = posed(built, kind, frame, tint, at)
        flash = flash_of(tick, meta)
        if flash:
            for el in e:
                base = el.get("tint") or [255, 255, 255]
                el["tint"] = [min(255, int(b * f / 255)) for b, f in zip(base, flash)]
                el["glow"] = flash == WHITE_FLASH or el.get("glow", False)
        textures.update(t)
        elements.extend(e)
        elements.extend(hit_sparks(kind, tick, strong, at))
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
    path = WORK / f"hit_{kind}.preview.json"
    path.write_text(json.dumps(model))
    render(path, out_png)
    return seq


def cut_hits(png, seq, title):
    from PIL import ImageDraw
    frames = cut(png, len(seq), title)
    font = _font(16)
    for frame, (_f, _tick, strong, caption) in zip(frames, seq):
        if caption:
            draw = ImageDraw.Draw(frame)
            draw.rectangle((0, FRAME_H - 26, FRAME_W, FRAME_H), fill=(255, 255, 255))
            draw.text((10, FRAME_H - 24), caption, fill=(200, 60, 60) if strong else (60, 50, 80), font=font)
    return frames


def hits(built, kinds, table, meta, out, have_pil):
    """<kind>_hit.gif per kind and hits.gif with all of them side by side."""
    seqs = {kind: hit_frames(table[kind], meta) for kind in kinds}
    total = max(len(s) for s in seqs.values())
    cut_frames = {}
    for kind in kinds:
        png = out / f"{kind}_hit.png"
        seq = hit_sheet(built, kind, seqs[kind], meta, png, total)
        if have_pil:
            creature = KINDS[kind][0].split(" · ")[0]
            cut_frames[kind] = cut_hits(png, seq, f"{creature} · 피격")
            save_gif(cut_frames[kind], out / f"{kind}_hit.gif")
            print(f"gif     {out / (kind + '_hit.gif')}: {len(seq)} frames")
    if have_pil and len(kinds) > 1:
        from PIL import Image
        columns = 2
        rows = math.ceil(len(kinds) / columns)
        combined = []
        for i in range(total):
            canvas = Image.new("RGB", (columns * FRAME_W, rows * FRAME_H), (255, 255, 255))
            for n, kind in enumerate(kinds):
                canvas.paste(cut_frames[kind][i], ((n % columns) * FRAME_W, (n // columns) * FRAME_H))
            combined.append(canvas)
        save_gif(combined, out / "hits.gif")
        print(f"gif     {out / 'hits.gif'}: {total} frames")


def save_gif(frames, path):
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=50, loop=0, optimize=False,
                   disposal=1)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    motions = "--motions" in sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    out = Path(args[0])
    out.mkdir(parents=True, exist_ok=True)
    kinds = args[1:] or list(KINDS)
    meta = json.loads(ATTACKS.read_text())
    table = meta["creatures"]
    built = {name: fn() for name, fn in creatures.ALL.items()}
    if "--hits" in sys.argv[1:]:
        try:
            import PIL  # noqa: F401
            have = True
        except ImportError:
            have = False
        hits(built, kinds, table, meta, out, have)
        return
    total = max(len(frames_of(table[k])) for k in kinds)
    try:
        import PIL  # noqa: F401
        have_pil = True
    except ImportError:
        have_pil = False
    cut_frames = {}
    for kind in kinds:
        png = out / f"{kind}.png"
        count = sheet(built, kind, frames_of(table[kind]), table[kind]["puffs"], png, total)
        if have_pil:
            cut_frames[kind] = cut(png, count, KINDS[kind][0])
            own = len(frames_of(table[kind]))
            save_gif(cut_frames[kind][:own], out / f"{kind}.gif")
            print(f"gif     {out / (kind + '.gif')}: {own} frames")
        if motions:
            previews(built, kind, table[kind], out, have_pil)
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


def previews(built, kind, entry, out, have_pil):
    """A kind's skills (each with its warning mark) and its walk cycle."""
    creature = KINDS[kind][0].split(" · ")[0]
    for name, motion in entry.get("motions", {}).items():
        area = motion.get("area")
        if name == entry["attack"] and not area:
            continue  # the basic attack's GIF is <kind>.gif
        seq = frames_of(motion, area)
        png = out / f"{kind}_{name}.png"
        count = sheet(built, kind, seq, motion["puffs"], png, area=area, name=name)
        if have_pil:
            save_gif(cut(png, count, f"{creature} · {MOTION_TITLES.get(name, name)}"), out / f"{kind}_{name}.gif")
            print(f"gif     {out / (kind + '_' + name + '.gif')}: {count} frames")
    seq = walk_frames(entry["walk"])
    png = out / f"{kind}_walk.png"
    count = sheet(built, kind, seq, [], png, name="walk")
    if have_pil:
        save_gif(cut(png, count, f"{creature} · 걸음 ({entry['gait']})"), out / f"{kind}_walk.gif")
        print(f"gif     {out / (kind + '_walk.gif')}: {count} frames")


if __name__ == "__main__":
    main()
