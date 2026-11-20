"""
Block-space modelling kit for the 공성전 v2 toy kingdom (towers, structures, creatures).

Everything is designed in BLOCK coordinates relative to the thing's base: x across (left -, right +), y up from the
ground (the creature's feet), z depth with the FRONT facing -z (north). The game turns the front where it should look.

A Minecraft model only spans -16..32 units per axis (3 blocks at display scale 1), so:
  - `centred(name, scale, centre)` makes one model shown at display scale `scale` whose centre (model 8, 8, 8) sits
    `centre` blocks above the base: it may use x/z within +-1.5*scale and y within centre +- 1.5*scale blocks;
  - `slabs(prefix, count, scale, height)` cuts a tall building into horizontal slab models (slab k starts k*height
    blocks above the ground; boxes crossing a border are split), each shown by its own item display.

`rbox` is the toy look: a box with chamfered (rounded) edges made of three boxes, the way moulded toys and plush
read in voxels. Sub-boxes never share a visible face plane (the model checks would refuse z-fighting).
"""

from .model import Model

EPS = 1 / 64          # block-space stagger that keeps overlapping sub-boxes off each other's face planes


class Build:
    """Boxes in block coordinates."""

    def __init__(self):
        self.boxes = []

    def box(self, name, frm, to, tex, **kw):
        frm = tuple(float(v) for v in frm)
        to = tuple(float(v) for v in to)
        assert all(a <= b for a, b in zip(frm, to)), f"{name}: from > to"
        self.boxes.append((name, frm, to, tex, kw))
        return self

    def mirror(self, name, frm, to, tex, **kw):
        """The box (drawn on the left, -x) and its mirror image across x = 0 (rotations flip their angle)."""
        self.box(name + "_l", frm, to, tex, **kw)
        kw2 = dict(kw)
        if kw.get("rotation"):
            axis, angle, (ox, oy, oz) = kw["rotation"]
            kw2["rotation"] = (axis, angle if axis == "x" else -angle, (-ox, oy, oz))
        if kw.get("faces"):
            swap = {"east": "west", "west": "east"}
            kw2["faces"] = {swap.get(f, f): t for f, t in kw["faces"].items()}
        self.box(name + "_r", (-to[0], frm[1], frm[2]), (-frm[0], to[1], to[2]), tex, **kw2)

    def rbox(self, name, frm, to, tex, r, top=True, bottom=True, **kw):
        """A box with chamfered edges `r` blocks deep: a core inset in x/z at full height plus an x arm and a z arm
        inset in y (only at the top / bottom when asked)."""
        x0, y0, z0 = frm
        x1, y1, z1 = to
        r = min(r, (x1 - x0) / 2 - 3 * EPS, (z1 - z0) / 2 - 3 * EPS, (y1 - y0) / 2 - 3 * EPS)
        if r <= EPS:
            self.box(name, frm, to, tex, **kw)
            return
        ty = r if top else 0
        by = r if bottom else 0
        if (y1 - by) - (y0 + ty) <= EPS * 4:
            ty = by = 0
        self.box(name, (x0 + r, y0, z0 + r), (x1 - r, y1, z1 - r), tex, **kw)
        # the arms stagger their y faces off the core's when not inset
        self.box(name + "_x", (x0, y0 + by + (0 if by else EPS), z0 + r + EPS),
                 (x1, y1 - ty - (0 if ty else EPS), z1 - r - EPS), tex, **kw)
        self.box(name + "_z", (x0 + r + EPS * 2, y0 + by + (0 if by else EPS * 2), z0),
                 (x1 - r - EPS * 2, y1 - ty - (0 if ty else EPS * 2), z1), tex, **kw)

    def mirror_rbox(self, name, frm, to, tex, r, **kw):
        self.rbox(name + "_l", frm, to, tex, r, **kw)
        self.rbox(name + "_r", (-to[0], frm[1], frm[2]), (-frm[0], to[1], to[2]), tex, r, **kw)

    def ring(self, name, half, y0, y1, thick, tex, **kw):
        """A square frame (four bars) of outer half width `half` around the y axis."""
        h, t = half, thick
        self.box(name + "_n", (-h, y0, -h), (h, y1, -h + t), tex, **kw)
        self.box(name + "_s", (-h, y0, h - t), (h, y1, h), tex, **kw)
        self.box(name + "_w", (-h, y0 + EPS, -h + t), (-h + t, y1 - EPS, h - t), tex, **kw)
        self.box(name + "_e", (h - t, y0 + EPS, -h + t), (h, y1 - EPS, h - t), tex, **kw)

    def eyes(self, name, y, z, gap, size=0.09, depth=0.03, tex="t_ink", shine="t_white"):
        """Two dot eyes on a face whose surface is at `z` (front, -z), `gap` blocks apart, with a white glint."""
        s = size
        self.mirror(name, (-gap / 2 - s / 2, y - s / 2, z - depth), (-gap / 2 + s / 2, y + s / 2, z), tex)
        g = s * 0.4
        self.mirror(name + "_glint", (-gap / 2 - s / 2 + s * 0.12, y + s / 2 - g - s * 0.1, z - depth - 0.01),
                    (-gap / 2 - s / 2 + s * 0.12 + g, y + s / 2 - s * 0.1, z - depth), shine)

    def cheeks(self, name, y, z, gap, w=0.1, h=0.05, depth=0.015, tex="t_rose"):
        self.mirror(name, (-gap / 2 - w / 2, y - h / 2, z - depth), (-gap / 2 + w / 2, y + h / 2, z), tex)

    def scaled(self, k):
        """A copy with every coordinate (and rotation origin) multiplied by k: design small, show it bigger."""
        out = Build()
        for name, frm, to, tex, kw in self.boxes:
            kw2 = dict(kw)
            if kw.get("rotation"):
                axis, angle, origin = kw["rotation"]
                kw2["rotation"] = (axis, angle, tuple(v * k for v in origin))
            out.box(name, tuple(v * k for v in frm), tuple(v * k for v in to), tex, **kw2)
        return out

    # --------------------------------------------------------------------------------------------- output

    def centred(self, name, scale, centre):
        """One model shown at display scale `scale`, its centre `centre` blocks above the base."""
        m = Model(name)
        for bname, frm, to, tex, kw in self.boxes:
            _add(m, bname, frm, to, tex, kw, centre - 1.5 * scale, scale)
        return m

    def slabs(self, prefix, count, scale, height):
        """Slab models: slab k covers y k*height .. (k+1)*height (unused slabs are None)."""
        models = [Model(f"{prefix}_{k}") for k in range(count)]
        used = [False] * count
        for name, frm, to, tex, kw in self.boxes:
            if kw.get("rotation"):
                k = min(count - 1, int(frm[1] // height + 1e-6))
                _add(models[k], name, frm, to, tex, kw, k * height - 0 * scale, scale, slab=True)
                used[k] = True
                continue
            for k in range(count):
                lo, hi = k * height, (k + 1) * height
                if to[1] <= lo + 1e-6 or frm[1] >= hi - 1e-6:
                    continue
                f = (frm[0], max(frm[1], lo), frm[2])
                t = (to[0], min(to[1], hi), to[2])
                _add(models[k], name if (f[1], t[1]) == (frm[1], to[1]) else f"{name}_s{k}", f, t, tex, kw,
                     k * height, scale, slab=True)
                used[k] = True
        return [m if u else None for m, u in zip(models, used)]


def _add(model, name, frm, to, tex, kw, y0, scale, slab=False):
    """Adds a block-space box to a model whose y = -16 sits at block height y0 (x/z centred on 8)."""
    u = 16 / scale

    def conv(p):
        return (8 + p[0] * u, -16 + (p[1] - y0) * u, 8 + p[2] * u)

    kw = dict(kw)
    if kw.get("rotation"):
        axis, angle, origin = kw["rotation"]
        kw["rotation"] = (axis, angle, conv(origin))
    model.box(name, conv(frm), conv(to), tex, **kw)


def slab_centre(k, scale, height):
    """Display height (blocks above the base) of slab k's model centre: the slab's y = -16 is at k*height."""
    return k * height + 1.5 * scale
