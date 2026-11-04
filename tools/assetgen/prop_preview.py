"""
Preview sheets of the 장난감 왕국 map props (models/props.py), placed the way the map shows them (item displays at
their display scale and centre height), next to a player silhouette:

  prop_<id>.png        one prop: front 3/4 (large), back 3/4, and the block prop it replaces (when a dump is given)
  props_lineup.png     every prop in a row, front 3/4 and from above
  props_compare.png    block props (top row) above their 3D models (bottom row), same scale

    python3 -I tools/assetgen/prop_preview.py <out dir> [--blocks <dump.json>] [--pending] [<id> ...]

--pending draws the props made but not placed yet (props.PENDING) instead of the placed ones (props.PROPS).

The block dump ({prop id: [[x, y, z, "#rrggbb", shape], ...]}) comes from the map canvas (MapPreview colours); the
blocks are drawn as flat-coloured boxes (stairs as full blocks).
"""

import base64
import io
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from PIL import Image  # noqa: E402

from models import props  # noqa: E402
import toy_preview  # noqa: E402
from toy_preview import figure, placed, scene  # noqa: E402

BUILD = HERE.parent.parent / "build" / "assetgen"
WATER = {"boat", "duck"}
# block props laid along x in the map: turned so they head -z like the models
TURN = {"train": True, "windup_car": True}
# props that stand in the sandbox: their base is the sand one block down
SANDBOX = {"sand_castle", "sand_spade"}
# moving parts shown with their prop (a separate model at the same place)
PARTS = {"windup_car": "windup_car_key", "cheese": "cheese_key", "rabbit_watch": "rabbit_watch_hands"}


def model_parts(built, pid, x=0.0, z=0.0, yaw=0.0):
    out = []
    for m in (pid, PARTS.get(pid)):
        if m:
            _, scale, centre = props.DISPLAY[m]
            out.append(placed(built[f"prop/{m}"], None, (x, centre, z), scale, yaw))
    return out


def _colour_png(hex_colour):
    im = Image.new("RGBA", (16, 16), hex_colour)
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


SHAPES = {"full": (0, 1, 0, 1), "stairs": (0, 1, 0, 1), "slab": (0, 0.5, 0, 1), "slab_top": (0.5, 1, 0, 1),
          "post": (0, 1, 0.375, 0.625), "small": (0, 0.4, 0.35, 0.65), "thin": (0, 0.1875, 0, 1)}


def block_parts(blocks, pid, x=0.0, z=0.0):
    """The dumped block prop, centred on (x, z), its ground top at y = 0 (the water's surface for floating props)."""
    if not blocks:
        return []
    if pid in ("lollipop", "spinning_top"):
        # the dump's box catches neighbouring props: keep the column with the most blocks and what is around it
        cols = {}
        for b in blocks:
            cols[(b[0], b[2])] = cols.get((b[0], b[2]), 0) + 1
        cx, cz = max(cols, key=cols.get)
        blocks = [b for b in blocks if abs(b[0] - cx) <= 3.5 and abs(b[2] - cz) <= 3.5
                  and (pid != "lollipop" or b[1] >= 69 or (b[0], b[2]) == (cx, cz))]
    xs = [b[0] for b in blocks]
    zs = [b[2] for b in blocks]
    mx, mz = (min(xs) + max(xs) + 1) / 2, (min(zs) + max(zs) + 1) / 2
    base = 64 if pid in SANDBOX else 65
    textures, elements = {}, []
    for bx, by, bz, colour, shape in blocks:
        y0, y1, h0, h1 = SHAPES.get(shape, SHAPES["full"])
        lx, lz = bx - mx, bz - mz
        if TURN.get(pid):
            lx, lz = -lz - 1, lx
        frm = [x + lx + h0, by - base + y0, z + lz + h0]
        to = [x + lx + h1, by - base + y1, z + lz + h1]
        key = "c" + colour[1:]
        textures[key] = _colour_png(colour)
        elements.append({"from": frm, "to": to, "glow": False, "tint": None, "translucent": False, "rotation": None,
                         "faces": {f: {"uv": [0, 0, 16, 16], "texture": key}
                                   for f in ("north", "south", "east", "west", "up", "down")}})
    return [(textures, elements)]


SIZE = {"lollipop": (7, 12), "candy_cane": (3, 8), "train": (12, 6), "windup_car": (7, 3.5), "spinning_top": (7, 9),
        "boat": (6, 8.5), "duck": (4, 3.5), "rainbow": (16, 8),
        "cotton_tree": (7, 8.5), "crayon_box": (4, 6), "marble_jar": (5, 5.2), "block_stack": (9, 7),
        "toy_box": (12, 7), "toy_box_pink": (4.5, 6), "toy_box_sky": (4.5, 6.5), "toy_box_yellow": (3.5, 8),
        "jelly": (4, 4.8), "cheese": (5, 8), "rabbit_watch": (14, 9.5), "sand_castle": (4, 4),
        "sand_bucket": (5, 3.5), "sand_spade": (2, 4.2)}
PLACED = [pid for pid in SIZE if pid in props.PROPS]
PENDING = [pid for pid in SIZE if pid in props.PENDING]


def _ground(pid):
    return "#5d8fd6" if pid in WATER or pid == "rainbow" else toy_preview.GRASS


def prop_sheet(built, blocks, pid, render, work, png):
    w, h = SIZE[pid]
    d = max(w, h) * 1.9 + 4
    parts = model_parts(built, pid) + [figure(w / 2 + 1.0, -1.0)]
    far = 60.0
    parts += block_parts(blocks.get(pid), pid, x=far) + ([figure(far + w / 2 + 1.0, -1.0)] if blocks.get(pid) else [])
    target = [0, h / 2, 0]
    views = [{"x": 0, "y": 0, "w": 1000, "h": 1000, "az": -35, "el": 16, "target": target, "dist": d},
             {"x": 1000, "y": 0, "w": 600, "h": 500, "az": 140, "el": 22, "target": target, "dist": d}]
    if blocks.get(pid):
        views.append({"x": 1000, "y": 500, "w": 600, "h": 500, "az": -35, "el": 16, "target": [far, h / 2, 0],
                      "dist": d})
    else:
        views.append({"x": 1000, "y": 500, "w": 600, "h": 500, "az": 90, "el": 6, "target": target, "dist": d})
    s = max(w, h) + 4
    render(scene(parts, work / f"prop_{pid}.preview.json", views, ground=[-s, 0, -s, far + s, s]), png)


def lineup(built, render, work, png, ids):
    parts, x = [figure(0, 0)], 1.5
    for pid in ids:
        w = SIZE[pid][0]
        x += w / 2 + 0.8
        parts += model_parts(built, pid, x, 0)
        x += w / 2 + 0.8
    views = [{"x": 0, "y": 0, "w": 1600, "h": 600, "az": -18, "el": 12, "target": [x / 2, 3.5, 0], "dist": x * 0.78},
             {"x": 0, "y": 600, "w": 1600, "h": 400, "az": -18, "el": 55, "target": [x / 2, 1.0, 0], "dist": x * 0.7}]
    render(scene(parts, work / "props_lineup.preview.json", views, ground=[-3, 0, -8, x + 3, 8]), png)


def compare(built, blocks, render, work, png, ids):
    """A grid: four props per pair of rows, the block prop above its model, each cell aimed at its own prop."""
    parts, views, apart, gap = [], [], 300.0, 24.0
    for i, pid in enumerate(ids):
        w, h = SIZE[pid]
        x = i * gap
        parts += block_parts(blocks.get(pid), pid, x, 0)
        parts += model_parts(built, pid, x + apart, 0)
        parts += [figure(x + w / 2 + 1.0, -1.0), figure(x + apart + w / 2 + 1.0, -1.0)]
        col, pair = i % 4, i // 4
        for row, ox in ((0, 0.0), (1, apart)):
            views.append({"x": col * 400, "y": (pair * 2 + row) * 250, "w": 400, "h": 250, "az": -30, "el": 14,
                          "target": [x + ox, h / 2, 0], "dist": max(w, h) * 1.9 + 3})
    path = scene(parts, work / "props_compare.preview.json", views,
                 ground=[-12, 0, -12, apart + gap * len(ids), 12])
    data = json.loads(path.read_text())
    data["height"] = ((len(ids) + 3) // 4) * 500
    path.write_text(json.dumps(data))
    render(path, png)


def render_all(built, render, work, out, blocks=None, ids=None):
    blocks = blocks or {}
    ids = ids or PLACED
    for pid in ids:
        prop_sheet(built, blocks, pid, render, work, out / f"prop_{pid}.png")
    lineup(built, render, work, out / "props_lineup.png", ids)
    if blocks:
        compare(built, blocks, render, work, out / "props_compare.png", [pid for pid in ids if pid in blocks])


def main():
    import build
    args = sys.argv[1:]
    out = Path(args[0])
    blocks = json.loads(Path(args[args.index("--blocks") + 1]).read_text()) if "--blocks" in args else {}
    out.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)
    built = {name: fn() for name, fn in props.ALL.items()}
    for m in built.values():
        problems = m.validate()
        if problems:
            print(f"{m.name}: " + "; ".join(problems))
    only = [a for a in args[1:] if a in SIZE]
    if only:
        for pid in only:
            prop_sheet(built, blocks, pid, build.render, BUILD, out / f"prop_{pid}.png")
        return
    render_all(built, build.render, BUILD, out, blocks, PENDING if "--pending" in args else PLACED)


if __name__ == "__main__":
    main()
