"""A small painter for illustrated pixel-art icons (the 증강 card icons, `augment_icons.py`).

Shapes are drawn as vectors (polygons, ellipses, tapered strokes, Bezier curves) in design units (0..100 across the
icon) on an 8x supersampled canvas. Every painted shape gets a height field (its mask blurred by its `round`ness), so
it is lit like a rounded object by one sun from the top-left; the canvas is then reduced to the icon's pixels and
cleaned up the way a pixel artist would:

  * cel shading: the light is quantized into the material's 6-step ramp (OKLCH ladder, shadows lean cool and
    violet, lights warm), glossy materials get a near-white specular pixel;
  * selective outline (selout): one pixel round the silhouette, dark ink on the shadow side, the material's own dark
    colour on the lit side; shapes in front of others cut a dark line into the one behind;
  * manual anti-aliasing: half-transparent ink in the stair steps of curved outlines;
  * rim light: a cool reflected light along the shadow edge of metals and gems;
  * emissive materials (fire, magic, xp) are not shaded by the sun, keep a warm outline and glow round them.
"""

import math

import numpy as np
from PIL import Image, ImageDraw

from .color import oklch_to_rgb
from .palette import build_ramp

SS = 8                      # supersampling
LIGHT = np.array([-0.58, -0.68, 0.46])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
STEPS = 6

# name: (hue, chroma, centre lightness, span, accent) -> 6-step ramp; flags
MATERIALS = {
    "steel":    ((250, 0.016, 0.62, 0.62, False), "metal"),
    "iron":     ((240, 0.012, 0.66, 0.58, False), "metal"),
    "dark":     ((295, 0.030, 0.36, 0.34, False), "metal"),     # netherite
    "gold":     ((86, 0.150, 0.70, 0.52, True), "metal"),
    "copper":   ((50, 0.120, 0.58, 0.50, True), "metal"),
    "wood":     ((60, 0.075, 0.48, 0.42, False), ""),
    "wood_red": ((40, 0.080, 0.44, 0.40, False), ""),
    "leather":  ((52, 0.085, 0.48, 0.44, False), ""),
    "cloth":    ((285, 0.050, 0.36, 0.36, False), ""),           # dark violet cloak
    "red":      ((26, 0.180, 0.52, 0.52, True), "gloss"),
    "blood":    ((22, 0.170, 0.44, 0.46, True), "gloss"),
    "meat":     ((35, 0.120, 0.56, 0.44, False), ""),
    "bone":     ((85, 0.025, 0.84, 0.30, False), ""),
    "white":    ((250, 0.012, 0.88, 0.30, False), ""),
    "paper":    ((80, 0.035, 0.86, 0.26, False), ""),
    "green":    ((140, 0.140, 0.58, 0.48, True), ""),
    "leaf":     ((135, 0.120, 0.54, 0.44, False), ""),
    "cactus":   ((150, 0.120, 0.52, 0.44, False), ""),
    "slime":    ((130, 0.150, 0.72, 0.40, True), "gloss"),
    "emerald":  ((158, 0.150, 0.64, 0.50, True), "gem"),
    "diamond":  ((195, 0.110, 0.76, 0.46, True), "gem"),
    "ice":      ((215, 0.070, 0.82, 0.34, False), "gem"),
    "sky":      ((235, 0.120, 0.66, 0.46, True), ""),
    "water":    ((240, 0.130, 0.58, 0.46, True), "gloss"),
    "violet":   ((305, 0.140, 0.52, 0.48, True), "gloss"),
    "ender":    ((175, 0.110, 0.52, 0.48, True), "gem"),
    "orange":   ((55, 0.160, 0.68, 0.44, True), ""),
    "stone":    ((260, 0.012, 0.56, 0.40, False), ""),
    "deepslate": ((270, 0.018, 0.40, 0.30, False), ""),
    "ink":      ((285, 0.030, 0.26, 0.16, False), ""),
    "owl":      ((55, 0.075, 0.50, 0.44, False), ""),
    "fur":      ((70, 0.020, 0.78, 0.40, False), ""),
    # emissive
    "fire":     (((20, 92), 0.190, 0.64, 0.46, True), "emit"),
    "magic":    (((290, 320), 0.150, 0.70, 0.50, True), "emit"),
    "xp":       (((110, 135), 0.190, 0.80, 0.40, True), "emit"),
    "spark":    (((60, 95), 0.120, 0.86, 0.26, True), "emit"),
    "frost":    (((200, 225), 0.100, 0.86, 0.28, True), "emit"),
    "soul":     (((190, 200), 0.120, 0.74, 0.40, True), "emit"),
    "venom":    (((130, 115), 0.190, 0.74, 0.40, True), "emit"),
    "glowred":  (((15, 40), 0.190, 0.66, 0.40, True), "emit"),
}

INK = oklch_to_rgb(0.17, 0.035, 290)


def _ramp(spec):
    hue, chroma, centre, span, accent = spec
    return [tuple(int(v) for v in c) for c in build_ramp(hue, chroma, centre, span, STEPS, accent)]


def _mix(a, b, t):
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))


class Material:
    def __init__(self, name):
        spec, flags = MATERIALS[name]
        self.name = name
        self.ramp = _ramp(spec)
        hue = spec[0][0] if isinstance(spec[0], tuple) else spec[0]
        self.emit = "emit" in flags
        self.gloss = flags in ("gloss", "gem", "metal")
        self.rim = flags in ("metal", "gem")
        dark = self.ramp[0]
        self.ink = _mix(INK, dark, 0.35)                     # outline on the shadow side
        self.edge = _mix(dark, INK, 0.25)                    # outline on the lit side (coloured)
        self.spec = _mix(self.ramp[-1], (255, 255, 250), 0.55)
        if self.emit:
            self.ink = self.edge = _mix(self.ramp[1], self.ramp[0], 0.5)
            self.spec = _mix(self.ramp[-1], (255, 255, 255), 0.6)
        self.hue = hue


_MATS = {}


def mat(name):
    if name not in _MATS:
        _MATS[name] = Material(name)
    return _MATS[name]


# ====================================================================== geometry helpers (design units 0..100)

def rot(points, deg, c=(50, 50)):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return [(c[0] + (x - c[0]) * ca - (y - c[1]) * sa, c[1] + (x - c[0]) * sa + (y - c[1]) * ca) for x, y in points]


def move(points, dx, dy):
    return [(x + dx, y + dy) for x, y in points]


def scale(points, f, c=(50, 50)):
    fx, fy = (f, f) if not isinstance(f, tuple) else f
    return [(c[0] + (x - c[0]) * fx, c[1] + (y - c[1]) * fy) for x, y in points]


def mirror(points, cx=50):
    return [(2 * cx - x, y) for x, y in points]


def bez(*pts, n=24):
    """A Bezier curve through control points (quadratic or cubic or higher), n + 1 points."""
    out = []
    for i in range(n + 1):
        t = i / n
        p = list(pts)
        while len(p) > 1:
            p = [(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t) for a, b in zip(p, p[1:])]
        out.append(p[0])
    return out


def arc(cx, cy, rx, ry, a0, a1, n=32):
    """Points on an ellipse from angle a0 to a1 (degrees, 0 = right, 90 = down)."""
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def taper(path, widths):
    """A polygon round a centre line `path` whose width follows `widths` (a list sampled along it, or a function
    t -> width)."""
    n = len(path)
    if callable(widths):
        ws = [widths(i / (n - 1)) for i in range(n)]
    else:
        ws = [widths[min(len(widths) - 1, int(i / max(1, n - 1) * (len(widths) - 1) + 0.5))] if len(widths) < n
              else widths[i] for i in range(n)]
        if len(widths) != n:
            ws = [np.interp(i / (n - 1), np.linspace(0, 1, len(widths)), widths) for i in range(n)]
    left, right = [], []
    for i in range(n):
        a = path[max(0, i - 1)]
        b = path[min(n - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        d = math.hypot(dx, dy) or 1
        nx, ny = -dy / d, dx / d
        w = ws[i] / 2
        left.append((path[i][0] + nx * w, path[i][1] + ny * w))
        right.append((path[i][0] - nx * w, path[i][1] - ny * w))
    return left + right[::-1]


def star_pts(cx, cy, r_out, r_in, n=5, deg=-90):
    pts = []
    for i in range(2 * n):
        r = r_out if i % 2 == 0 else r_in
        a = math.radians(deg + i * 180 / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


# ====================================================================== the canvas

def _blur(a, r):
    """Box blur (three passes ~ Gaussian) of a 2D float array with radius r pixels."""
    r = max(1, int(r))
    out = a
    for _ in range(3):
        for axis in (0, 1):
            pad = [(0, 0), (0, 0)]
            pad[axis] = (r + 1, r)
            p = np.pad(out, pad, mode="constant")
            c = np.cumsum(p, axis=axis, dtype=np.float64)
            if axis == 0:
                out = (c[2 * r + 1:, :] - c[:-2 * r - 1, :]) / (2 * r + 1)
            else:
                out = (c[:, 2 * r + 1:] - c[:, :-2 * r - 1]) / (2 * r + 1)
    return out.astype(np.float32)


class Icon:
    """One icon. Paint shapes back to front; `render()` gives the RGBA pixel art."""

    def __init__(self, size=72):
        self.n = size
        self.hi = size * SS
        self.k = self.hi / 100.0
        self.mat = np.full((self.hi, self.hi), -1, np.int16)
        self.shade = np.zeros((self.hi, self.hi), np.float32)
        self.grp = np.full((self.hi, self.hi), -1, np.int16)
        self.fixed = np.full((self.hi, self.hi), -1, np.int8)   # fixed ramp level (details), -1 = lit
        self.mats = []
        self.groups = []          # outline group per paint id
        self.cut = []             # whether the paint cuts a line into what is behind
        self.glows = []
        self.sparkles = []
        self.overlays = []

    # ---------------------------------------------------------------- masks
    def _img(self):
        return Image.new("L", (self.hi, self.hi), 0)

    def _pts(self, pts):
        return [(x * self.k, y * self.k) for x, y in pts]

    def poly(self, pts):
        im = self._img()
        ImageDraw.Draw(im).polygon(self._pts(pts), fill=255)
        return np.asarray(im) > 127

    def ellipse(self, cx, cy, rx, ry=None):
        ry = rx if ry is None else ry
        im = self._img()
        k = self.k
        ImageDraw.Draw(im).ellipse(((cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k), fill=255)
        return np.asarray(im) > 127

    def rect(self, x0, y0, x1, y1, r=0):
        im = self._img()
        k = self.k
        ImageDraw.Draw(im).rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), radius=r * k, fill=255)
        return np.asarray(im) > 127

    def line(self, pts, w):
        """A stroke of width w with round caps and joins."""
        im = self._img()
        d = ImageDraw.Draw(im)
        p = self._pts(pts)
        d.line(p, fill=255, width=max(1, int(w * self.k)), joint="curve")
        rr = w * self.k / 2
        for x, y in (p[0], p[-1]):
            d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=255)
        return np.asarray(im) > 127

    def taper(self, path, widths):
        return self.poly(taper(path, widths))

    # ---------------------------------------------------------------- painting
    def _add(self, name, cut, group):
        self.mats.append(mat(name))
        pid = len(self.mats) - 1
        self.groups.append(pid if group is None else group)
        self.cut.append(cut)
        return pid

    def paint(self, mask, name, round=5.0, bias=0.0, flat=None, cut=True, group=None, tilt=0.25):
        """Paints `mask` with material `name`, lit as a rounded body (`round` in design units; larger = softer).
        `bias` brightens (+) or darkens (-) it; `flat` gives it one light value (0..1) instead."""
        pid = self._add(name, cut, group)
        m = mask
        if flat is not None:
            s = np.full(m.shape, flat, np.float32)
        else:
            r = max(1.0, round * self.k)
            h = _blur(m.astype(np.float32), r / 1.7)
            gy, gx = np.gradient(h)
            kk = r * 1.25
            nx, ny, nz = -gx * kk, -gy * kk, np.ones_like(h)
            norm = np.sqrt(nx * nx + ny * ny + nz * nz)
            diff = (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / norm
            s = 0.10 + np.clip(diff, -0.2, 1.0) * 1.0
            # a broad top-left to bottom-right gradient across the shape gives it volume
            ys, xs = np.nonzero(m)
            if len(xs):
                x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
                yy, xx = np.mgrid[0:self.hi, 0:self.hi]
                g = ((xx - x0) / max(1, x1 - x0) + (yy - y0) / max(1, y1 - y0)) / 2   # 0 top-left .. 1
                s = s + (0.5 - g) * tilt
        s = s + bias
        self.mat[m] = pid
        self.shade[m] = s[m]
        self.grp[m] = pid
        self.fixed[m] = -1
        return pid

    def emit(self, mask, name, round=6.0, bias=0.0, glow=True, cut=False):
        """An emissive shape: bright in its core, darker towards its rim (not lit by the sun)."""
        pid = self._add(name, cut, None)
        r = max(1.0, round * self.k)
        h = _blur(mask.astype(np.float32), r / 1.5)
        s = 0.08 + h * 0.8 + bias
        self.mat[mask] = pid
        self.shade[mask] = s[mask]
        self.grp[mask] = pid
        self.fixed[mask] = -1
        if glow:
            self.glows.append((mask, name))
        return pid

    def detail(self, mask, name, level, group=None):
        """A detail (stripe, rivet, grain line) at one fixed ramp level of `name`, part of the last paint's outline
        group (no outline between them)."""
        g = self.groups[-1] if group is None else group
        pid = self._add(name, False, g)
        self.mat[mask] = pid
        self.grp[mask] = pid
        self.fixed[mask] = level
        return pid

    def shade_mask(self, mask, delta, only=None):
        """Shifts the light of already painted pixels under `mask` (cast shadows, extra highlights)."""
        sel = mask & (self.mat >= 0)
        if only is not None:
            sel &= np.isin(self.mat, [only] if isinstance(only, int) else only)
        self.shade[sel] += delta

    def shine(self, mask, group=None):
        """A specular highlight (near white, tinted by the material below it)."""
        below = self.mat[mask]
        name = self.mats[int(np.bincount(below[below >= 0]).argmax())].name if (below >= 0).any() else "white"
        return self.detail(mask & (self.mat >= 0), name, STEPS, group)

    def erase(self, mask):
        self.mat[mask] = -1
        self.grp[mask] = -1

    def glow(self, cx, cy, r, name, strength=1.0):
        """A soft light behind everything (magic aura, fire light), drawn on the icon's transparent pixels."""
        self.overlays.append((cx, cy, r, name, strength))

    def sparkle(self, cx, cy, size=2, name="spark"):
        """A four-pointed twinkle on top (pixel sprite; size 1..3)."""
        self.sparkles.append((cx, cy, size, name))

    # ---------------------------------------------------------------- render
    def render(self):
        n, s = self.n, SS

        def blocks(a):
            return a.reshape(n, s, n, s).transpose(0, 2, 1, 3).reshape(n, n, s * s)

        bm, bs, bg, bf = blocks(self.mat), blocks(self.shade), blocks(self.grp), blocks(self.fixed)
        cover = (bm >= 0).mean(-1)
        top = bg.max(-1)
        best = np.full((n, n), -1, np.int32)
        best_w = np.zeros((n, n), np.float32)
        for g in np.unique(bg):
            if g < 0:
                continue
            cnt = (bg == g).sum(-1).astype(np.float32)
            w = cnt * np.where(top == g, 1.35, 1.0)
            upd = w > best_w
            best[upd] = g
            best_w[upd] = w[upd]
        opaque = (cover >= 0.42) & (best >= 0)
        sel = bg == best[..., None]
        cnt = np.maximum(1, sel.sum(-1))
        shade = (bs * sel).sum(-1) / cnt
        fixed = np.where(sel, bf, -1).max(-1)
        paint = np.where(opaque, best, -1)

        level = np.clip(np.floor(shade * STEPS), 0, STEPS - 1).astype(int)
        level = np.where(fixed >= 0, fixed, level)
        spec = shade > 1.0

        out = np.zeros((n, n, 4), np.uint8)
        grp = np.full((n, n), -1, np.int32)
        for y in range(n):
            for x in range(n):
                p = paint[y, x]
                if p < 0:
                    continue
                m = self.mats[p]
                grp[y, x] = self.groups[p]
                lv = level[y, x]
                c = m.spec if lv >= STEPS else m.ramp[lv]
                if spec[y, x] and m.gloss and fixed[y, x] < 0:
                    c = m.spec
                out[y, x] = (*c, 255)

        def at(a, y, x, default=-1):
            return a[y, x] if 0 <= y < n and 0 <= x < n else default

        # rim light on the shadow edge (metals, gems): darkest pixels next to the outside, bottom / right
        res = out.copy()
        for y in range(n):
            for x in range(n):
                p = paint[y, x]
                if p < 0 or not self.mats[p].rim or fixed[y, x] >= 0:
                    continue
                if level[y, x] <= 1 and (at(paint, y + 1, x) < 0 or at(paint, y, x + 1) < 0):
                    c = _mix(self.mats[p].ramp[2], (150, 175, 235), 0.35)
                    res[y, x] = (*c, 255)
        out = res

        # inner lines: a shape in front cuts a dark line into the one behind
        res = out.copy()
        for y in range(n):
            for x in range(n):
                p = paint[y, x]
                if p < 0:
                    continue
                for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
                    q = at(paint, y + dy, x + dx)
                    if q < 0 or q <= p or self.groups[q] == self.groups[p] or not self.cut[q]:
                        continue
                    if self.mats[q].emit:
                        continue
                    m = self.mats[p]
                    res[y, x] = (*(_mix(m.ramp[0], INK, 0.45) if not m.emit else m.ramp[1]), 255)
                    break
        out = res

        # outline (selout) one pixel round the silhouette
        res = out.copy()
        ink = np.zeros((n, n), bool)
        for y in range(n):
            for x in range(n):
                if paint[y, x] >= 0:
                    continue
                lit, dark, emit = None, None, None
                for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
                    q = at(paint, y + dy, x + dx)
                    if q < 0:
                        continue
                    m = self.mats[q]
                    if m.emit:
                        emit = m
                    elif dy > 0 or dx > 0:     # the shape is to the right / below: we are on its lit side
                        lit = lit or m
                    else:
                        dark = dark or m
                if dark is not None:
                    res[y, x] = (*dark.ink, 255)
                    ink[y, x] = True
                elif lit is not None:
                    res[y, x] = (*lit.edge, 255)
                    ink[y, x] = True
                elif emit is not None:
                    res[y, x] = (*emit.ink, 255)
                    ink[y, x] = True
        out = res
        solid = out[..., 3] > 0

        # manual anti-aliasing: half ink in the stair steps
        res = out.copy()
        for y in range(n):
            for x in range(n):
                if solid[y, x]:
                    continue
                for dy, dx in ((-1, -1), (-1, 1), (1, -1), (1, 1)):
                    if at(ink, y + dy, x, False) and at(ink, y, x + dx, False) and at(paint, y + dy, x + dx) >= 0:
                        r, g, b, _ = out[y + dy, x]
                        res[y, x] = (r, g, b, 120)
                        break
        out = res

        # glows round emissive shapes and auras (only where nothing is drawn)
        glow = np.zeros((n, n, 4), np.float32)
        yy, xx = np.mgrid[0:n, 0:n]
        for mask, name in self.glows:
            m = mat(name)
            src = blocks(mask.astype(np.int8)).mean(-1) > 0.3
            d = _dist(src, 3)
            a = np.select([d == 1, d == 2, d == 3], [120, 60, 25], 0)
            _over(glow, m.ramp[4], a)
        for cx, cy, r, name, strength in self.overlays:
            m = mat(name)
            d = np.hypot(xx + 0.5 - cx * n / 100, yy + 0.5 - cy * n / 100) / (r * n / 100)
            a = np.clip(1 - d, 0, 1) ** 1.4 * 120 * strength
            a = np.round(a / 30) * 30          # a few hard bands, not a smooth gradient
            _over(glow, m.ramp[4], a)
        empty = out[..., 3] == 0
        out[empty] = glow[empty].astype(np.uint8)

        # sparkles on top
        for cx, cy, size, name in self.sparkles:
            m = mat(name)
            x0, y0 = int(cx * n / 100), int(cy * n / 100)
            core = (255, 255, 245, 255)
            arm = (*m.ramp[4], 255)
            tip = (*m.ramp[3], 200)
            pts = [((0, 0), core)]
            for i in range(1, size + 2):
                c = arm if i <= size else tip
                pts += [((i, 0), c), ((-i, 0), c), ((0, i), c), ((0, -i), c)]
            if size >= 2:
                pts += [((1, 1), tip), ((-1, 1), tip), ((1, -1), tip), ((-1, -1), tip)]
            for (dx, dy), c in pts:
                if 0 <= x0 + dx < n and 0 <= y0 + dy < n:
                    out[y0 + dy, x0 + dx] = c
        return Image.fromarray(out, "RGBA")


def _dist(src, maxd):
    """Chebyshev-ish distance (4-neighbour rings) from `src` up to maxd; 0 inside, maxd + 1 beyond."""
    d = np.where(src, 0, maxd + 1)
    cur = src.copy()
    for i in range(1, maxd + 1):
        grow = cur.copy()
        grow[1:, :] |= cur[:-1, :]
        grow[:-1, :] |= cur[1:, :]
        grow[:, 1:] |= cur[:, :-1]
        grow[:, :-1] |= cur[:, 1:]
        d[grow & ~cur] = i
        cur = grow
    return d


def _over(layer, rgb, alpha):
    a = np.asarray(alpha, np.float32) / 255.0
    if a.ndim == 0:
        a = np.full(layer.shape[:2], a)
    old = layer[..., 3] / 255.0
    na = a + old * (1 - a)
    for i in range(3):
        layer[..., i] = np.where(na > 0, (rgb[i] * a + layer[..., i] * old * (1 - a)) / np.maximum(na, 1e-6),
                                 layer[..., i])
    layer[..., 3] = na * 255
