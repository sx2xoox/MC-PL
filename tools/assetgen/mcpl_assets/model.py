"""
Tiny modelling DSL -> Blockbench project (.bbmodel) + preview data (ported from STSV tools/assetgen).

The .bbmodel is the single source of truth handed to the Gradle build (PackBuilder / BBModelConverter turn it into a
vanilla model), and it can always be opened in Blockbench for hand edits.

Coordinates: Minecraft model units, 16 = 1 block (before the display scale). Front of a model = north face (-Z).
"""

import base64
import math
import io
import json
import uuid

from . import textures as T

FACES = ("north", "east", "south", "west", "up", "down")


def _span(a, b):
    if b - a >= 16:
        return 0.0, 16.0
    shift = (a // 16) * 16
    a2, b2 = a - shift, b - shift
    if b2 > 16:
        a2, b2 = 16 - (b - a), 16.0
    return a2, b2


def auto_uv(face, frm, to):
    """Blockbench-style auto UV, wrapped into 0..16 so tileable textures continue across parts."""
    x1, y1, z1 = frm
    x2, y2, z2 = to
    if face == "north":
        u, v = _span(16 - x2, 16 - x1), _span(16 - y2, 16 - y1)
    elif face == "south":
        u, v = _span(x1, x2), _span(16 - y2, 16 - y1)
    elif face == "east":
        u, v = _span(16 - z2, 16 - z1), _span(16 - y2, 16 - y1)
    elif face == "west":
        u, v = _span(z1, z2), _span(16 - y2, 16 - y1)
    elif face == "up":
        u, v = _span(x1, x2), _span(z1, z2)
    else:
        u, v = _span(x1, x2), _span(16 - z2, 16 - z1)
    return [round(u[0], 4), round(v[0], 4), round(u[1], 4), round(v[1], 4)]


def _turn(point, rotation, sign=1):
    """The point turned by an element rotation (axis, angle, origin); sign -1 turns it back."""
    axis, angle, origin = rotation
    a = math.radians(angle) * sign
    c, s = math.cos(a), math.sin(a)
    x, y, z = (point[i] - origin[i] for i in range(3))
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


def _inside(e, q, tol=1e-6):
    """Whether world point q is inside element e (turned or not)."""
    if e["rotation"]:
        lo, hi = _world_box(e)
        if not all(lo[i] - tol <= q[i] <= hi[i] + tol for i in range(3)):
            return False
        q = _turn(q, e["rotation"], -1)
    return all(e["from"][i] - tol <= q[i] <= e["to"][i] + tol for i in range(3))


def _world_box(e):
    """The world-space bounds of an element, turned or not (kept on the element: elements do not change)."""
    if "_box" not in e:
        if not e["rotation"]:
            e["_box"] = (list(e["from"]), list(e["to"]))
        else:
            corners = [_turn((x, y, z), e["rotation"]) for x in (e["from"][0], e["to"][0])
                       for y in (e["from"][1], e["to"][1]) for z in (e["from"][2], e["to"][2])]
            e["_box"] = ([min(c[i] for c in corners) for i in range(3)], [max(c[i] for c in corners) for i in range(3)])
    return e["_box"]


def _touches(o, e, margin=0.01):
    """Whether the world bounds of o and e meet (o may cover a face of e)."""
    (lo1, hi1), (lo2, hi2) = _world_box(o), _world_box(e)
    return all(lo1[i] <= hi2[i] + margin and lo2[i] <= hi1[i] + margin for i in range(3))


def _plane_basis(n):
    """Two unit vectors spanning the plane with normal n."""
    a = (1.0, 0.0, 0.0) if abs(n[0]) < 0.9 else (0.0, 1.0, 0.0)
    u = (n[1] * a[2] - n[2] * a[1], n[2] * a[0] - n[0] * a[2], n[0] * a[1] - n[1] * a[0])
    k = math.sqrt(sum(c * c for c in u))
    u = tuple(c / k for c in u)
    v = (n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
    return u, v


def _proj(c, basis):
    return (sum(c[i] * basis[0][i] for i in range(3)), sum(c[i] * basis[1][i] for i in range(3)))


def _area(poly):
    return abs(sum(poly[i][0] * poly[i - 1][1] - poly[i - 1][0] * poly[i][1] for i in range(len(poly)))) / 2


def _ccw(poly):
    s = sum(poly[i - 1][0] * poly[i][1] - poly[i][0] * poly[i - 1][1] for i in range(len(poly)))
    return poly if s >= 0 else poly[::-1]


def _clip(subject, clip):
    """Convex polygon intersection (Sutherland-Hodgman)."""
    out = _ccw(subject)
    clip = _ccw(clip)
    for i in range(len(clip)):
        a, b = clip[i - 1], clip[i]
        side = lambda p: (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])  # noqa: E731
        src, out = out, []
        for j in range(len(src)):
            p, q = src[j - 1], src[j]
            sp, sq = side(p), side(q)
            if sq >= 0:
                if sp < 0:
                    out.append(_cross(p, q, sp, sq))
                out.append(q)
            elif sp >= 0:
                out.append(_cross(p, q, sp, sq))
        if not out:
            return []
    return out


def _cross(p, q, sp, sq):
    t = sp / (sp - sq)
    return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)


def _in_poly(poly, pt):
    poly = _ccw(poly)
    return all((b[0] - a[0]) * (pt[1] - a[1]) - (b[1] - a[1]) * (pt[0] - a[0]) >= 0
               for a, b in zip([poly[-1]] + poly[:-1], poly))


class Model:
    def __init__(self, name: str):
        self.name = name
        self.elements = []
        self._group = "root"
        self.display = {}
        # also check turned elements for z-fighting (_turned_overlaps): on for the map props (models/props.py),
        # which are made of turned bars; the older families still have a few such overlaps to clean up first
        self.check_turned = False

    def group(self, name: str):
        self._group = name
        return self

    def box(self, name, frm, to, tex, faces=None, full_uv=(), glow=False, rotation=None, uv=None, tint=False):
        """
        tex:      texture used on every face
        faces:    {face: texture | None}  override / remove faces (None = no face, e.g. hidden sides)
        full_uv:  faces that show the whole texture instead of a tiled slice
        glow:     emits light (light_emission 15) and renders unshaded
        rotation: (axis, angle, origin)  axis in "xyz", angle in 22.5 degree steps between -45 and 45
        uv:       {face: [u1, v1, u2, v2]} explicit UVs
        tint:     faces take the item's minecraft:dye tint (team colour)
        """
        fs = {f: tex for f in FACES}
        if faces:
            fs.update(faces)
        fs = {f: t for f, t in fs.items() if t is not None}
        frm, to = [float(v) for v in frm], [float(v) for v in to]
        for a in range(3):
            assert frm[a] < to[a] or (frm[a] == to[a]), f"{name}: from > to"
            assert -16 <= frm[a] and to[a] <= 32, f"{name}: outside -16..32 ({frm}, {to})"
        if rotation:
            axis, angle, _ = rotation
            assert axis in "xyz" and angle in (-45, -22.5, 0, 22.5, 45), f"{name}: invalid rotation"
        self.elements.append({
            "name": name, "group": self._group, "from": frm, "to": to, "faces": fs,
            "full_uv": set(full_uv), "glow": glow, "rotation": rotation, "uv": uv or {}, "tint": tint,
        })
        return self

    # ------------------------------------------------------------------------------------------
    # Validation: catches the classic in-game artefacts before they ship
    # ------------------------------------------------------------------------------------------

    _AXIS = {"west": (0, -1), "east": (0, 1), "down": (1, -1), "up": (1, 1), "north": (2, -1), "south": (2, 1)}

    def validate(self):
        """Returns a list of problems. The face checks look at unturned elements (anything, turned or not, may cover
        them); turned faces are checked in world space by _turned_overlaps."""
        problems = []
        plain = [e for e in self.elements if not e["rotation"]]
        solid = self.elements

        def plane(e, f):
            ax, sign = self._AXIS[f]
            return ax, sign, (e["to"][ax] if sign > 0 else e["from"][ax])

        for e in plain:
            near = [o for o in solid if o is not e and _touches(o, e)]
            for f in FACES:
                ax, sign, p = plane(e, f)
                a1, a2 = [a for a in range(3) if a != ax]
                if f not in e["faces"]:
                    if e["to"][a1] - e["from"][a1] <= 0 or e["to"][a2] - e["from"][a2] <= 0:
                        continue
                    if e["to"][ax] - e["from"][ax] <= 0:
                        continue    # a flat plate has no inside to show
                    # a removed face must be covered from outside, else you can see into the box
                    step = 0.5
                    u = e["from"][a1] + step / 2
                    hole = False
                    while u < e["to"][a1] and not hole:
                        v = e["from"][a2] + step / 2
                        while v < e["to"][a2]:
                            if not any(self._occupies_beyond(o, ax, sign, p, a1, u, a2, v)
                                       or self._plate_on(o, f, ax, p, a1, u, a2, v) for o in near):
                                hole = True
                                break
                            v += step
                        u += step
                    if hole:
                        problems.append(f"{e['name']}: removed '{f}' face is not covered (the inside shows through)")
                    continue
                # two visible faces on the same plane, same direction, overlapping -> flicker
                for o in plain:
                    if o is e or f not in o["faces"] or id(o) < id(e):
                        continue
                    _, _, q = plane(o, f)
                    if abs(p - q) > 1e-6:
                        continue
                    ou = min(e["to"][a1], o["to"][a1]) - max(e["from"][a1], o["from"][a1])
                    ov = min(e["to"][a2], o["to"][a2]) - max(e["from"][a2], o["from"][a2])
                    if ou > 1e-6 and ov > 1e-6 and self._visible_overlap(near, e, o, ax, sign, p, a1, a2):
                        problems.append(f"{e['name']} / {o['name']}: '{f}' faces overlap on one plane (z-fighting)")

        if self.check_turned:
            problems += self._turned_overlaps()

        for e in self.elements:
            thick = min(e["to"][a] - e["from"][a] for a in range(3))
            for f, t in e["faces"].items():
                if thick > 0.5 and T.has_transparency(t):
                    problems.append(f"{e['name']}: texture '{t}' has see-through pixels on a solid box")
                    break
        return problems

    def _turned_overlaps(self, tol=1e-3, step=0.25):
        """Z-fighting where a turned element is involved: two visible faces facing the same way on one plane (world
        space, within `tol` units) that overlap where nothing else covers them. Plain pairs are the check above."""
        faces = []
        for e in self.elements:
            for f in e["faces"]:
                ax, sign = self._AXIS[f]
                a1, a2 = [a for a in range(3) if a != ax]
                if e["to"][a1] - e["from"][a1] <= 0 or e["to"][a2] - e["from"][a2] <= 0:
                    continue
                p = e["to"][ax] if sign > 0 else e["from"][ax]
                quad = []
                for cu, cv in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    c = [0.0, 0.0, 0.0]
                    c[ax] = p
                    c[a1] = e["to"][a1] if cu else e["from"][a1]
                    c[a2] = e["to"][a2] if cv else e["from"][a2]
                    quad.append(_turn(c, e["rotation"]) if e["rotation"] else tuple(c))
                n = [0.0, 0.0, 0.0]
                n[ax] = float(sign)
                if e["rotation"]:
                    o = e["rotation"][2]
                    n = [a - b for a, b in zip(_turn([o[i] + n[i] for i in range(3)], e["rotation"]), o)]
                faces.append((e, f, tuple(n), sum(n[i] * quad[0][i] for i in range(3)), quad))
        groups = {}
        for item in faces:
            groups.setdefault(tuple(round(v, 4) for v in item[2]), []).append(item)
        problems = []
        for n, items in groups.items():
            items.sort(key=lambda it: it[3])
            basis = _plane_basis(n)
            for i, (e, f, _, d, quad) in enumerate(items):
                for o, g, _, d2, quad2 in items[i + 1:]:
                    if d2 - d > tol:
                        break
                    if o is e or not (e["rotation"] or o["rotation"]):
                        continue
                    poly = _clip([_proj(c, basis) for c in quad], [_proj(c, basis) for c in quad2])
                    if len(poly) < 3 or _area(poly) < 1e-3:
                        continue
                    if self._shows(poly, basis, n, d, e, o, step):
                        problems.append(f"{e['name']} / {o['name']}: turned '{f}' / '{g}' faces overlap on one "
                                        f"plane (z-fighting)")
        return problems

    def _shows(self, poly, basis, n, d, e, o, step):
        """Whether some sample of the overlap polygon (plane coordinates) is left uncovered by the other elements."""
        us = [p[0] for p in poly]
        vs = [p[1] for p in poly]
        samples = [(sum(us) / len(us), sum(vs) / len(vs))]
        u = min(us) + step / 2
        while u < max(us):
            v = min(vs) + step / 2
            while v < max(vs):
                if _in_poly(poly, (u, v)):
                    samples.append((u, v))
                v += step
            u += step
        for u, v in samples:
            w = [basis[0][i] * u + basis[1][i] * v + n[i] * (d + 1e-3) for i in range(3)]
            if not any(_inside(t, w) for t in self.elements if t is not e and t is not o):
                return True
        return False

    def _visible_overlap(self, plain, e, o, ax, sign, p, a1, a2, step=0.5):
        u0, u1 = max(e["from"][a1], o["from"][a1]), min(e["to"][a1], o["to"][a1])
        v0, v1 = max(e["from"][a2], o["from"][a2]), min(e["to"][a2], o["to"][a2])
        u = u0 + step / 2
        while u < u1:
            v = v0 + step / 2
            while v < v1:
                if not any(self._occupies_beyond(t, ax, sign, p, a1, u, a2, v)
                           for t in plain if t is not e and t is not o):
                    return True
                v += step
            u += step
        return False

    @staticmethod
    def _plate_on(o, f, ax, p, a1, u, a2, v):
        """o is a flat plate on the plane p showing face f over (u, v): it closes a removed face there (round bar
        ends in models/props.py)."""
        return (o["from"][ax] == o["to"][ax] == p and f in o["faces"]
                and o["from"][a1] <= u <= o["to"][a1] and o["from"][a2] <= v <= o["to"][a2])

    @staticmethod
    def _occupies_beyond(o, ax, sign, p, a1, u, a2, v):
        if o["rotation"]:
            q = [0.0, 0.0, 0.0]
            q[ax], q[a1], q[a2] = p + sign * 1e-3, u, v
            return _inside(o, q)
        if not (o["from"][a1] <= u <= o["to"][a1] and o["from"][a2] <= v <= o["to"][a2]):
            return False
        if sign > 0:
            return o["from"][ax] <= p < o["to"][ax]
        return o["from"][ax] < p <= o["to"][ax]

    def textures_used(self):
        seen = []
        for e in self.elements:
            for t in e["faces"].values():
                if t not in seen:
                    seen.append(t)
        return seen

    def _face_uv(self, e, f):
        if f in e["uv"]:
            return e["uv"][f]
        if f in e["full_uv"]:
            return [0, 0, 16, 16]
        return auto_uv(f, e["from"], e["to"])

    @staticmethod
    def _png(tex, first_frame=False):
        buf = io.BytesIO()
        (T.first_frame(tex) if first_frame else T.get(tex)).save(buf, "PNG", optimize=True)
        return buf.getvalue()

    def to_bbmodel(self) -> dict:
        tex_names = self.textures_used()
        idx = {t: i for i, t in enumerate(tex_names)}
        textures = []
        for i, t in enumerate(tex_names):
            image = T.get(t)
            textures.append({
                "path": "", "name": f"{t}.png", "folder": "item", "namespace": "",
                "id": str(i), "width": image.width, "height": image.height, "uv_width": 16, "uv_height": 16,
                "particle": i == 0, "render_mode": "default", "render_sides": "auto",
                "frame_time": T.FRAME_TIME.get(t, 1), "frame_order_type": "loop", "frame_order": "", "frame_interpolate": False,
                "visible": True, "internal": True, "saved": False,
                "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/tex/{t}")),
                "relative_path": "",
                "source": "data:image/png;base64," + base64.b64encode(self._png(t)).decode(),
            })
        elements, groups = [], {}
        for n, e in enumerate(self.elements):
            uid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/el/{n}/{e['name']}"))
            faces = {}
            for f in FACES:
                if f in e["faces"]:
                    face = {"uv": self._face_uv(e, f), "texture": idx[e["faces"][f]]}
                    if e["tint"]:
                        face["tint"] = 0
                    faces[f] = face
                else:
                    faces[f] = {"uv": [0, 0, 0, 0], "texture": None}
            rot, origin = [0, 0, 0], [8, 8, 8]
            if e["rotation"]:
                axis, angle, origin = e["rotation"]
                rot["xyz".index(axis)] = angle
            el = {
                "name": e["name"], "box_uv": False, "rescale": False, "locked": False,
                "render_order": "default", "allow_mirror_modeling": True,
                "from": e["from"], "to": e["to"], "autouv": 0, "color": len(groups) % 8,
                "rotation": rot, "origin": list(origin), "faces": faces, "type": "cube", "uuid": uid,
            }
            if e["glow"]:
                el["light_emission"] = 15
                el["shade"] = False
            elements.append(el)
            groups.setdefault(e["group"], []).append(uid)
        outliner = [{
            "name": g, "origin": [8, 8, 8], "color": 0,
            "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{self.name}/grp/{g}")),
            "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True,
            "autouv": 0, "children": kids,
        } for g, kids in groups.items()]
        return {
            "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
            "name": self.name, "parent": "", "ambientocclusion": False, "front_gui_light": False,
            "visible_box": [1, 1, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
            "unhandled_root_fields": {}, "resolution": {"width": 16, "height": 16},
            "elements": elements, "outliner": outliner, "textures": textures,
            "display": self.display,
        }

    def to_preview(self, tint=None) -> dict:
        """tint: (r, g, b) multiplied onto tinted faces, like the game's dye tint."""
        return {
            "textures": {t: "data:image/png;base64," + base64.b64encode(self._png(t, True)).decode()
                         for t in self.textures_used()},
            "elements": [{
                "from": e["from"], "to": e["to"], "glow": e["glow"],
                "tint": list(tint) if (tint and e["tint"]) else None,
                "translucent": any(t in T.TRANSLUCENT for t in e["faces"].values()),
                "rotation": list(e["rotation"][:2]) + [list(e["rotation"][2])] if e["rotation"] else None,
                "faces": {f: {"uv": self._face_uv(e, f), "texture": t} for f, t in e["faces"].items()},
            } for e in self.elements],
        }

    def write(self, bbmodel_path):
        problems = self.validate()
        if problems:
            raise ValueError(f"model '{self.name}' failed the checks:\n  - " + "\n  - ".join(problems))
        bbmodel_path.write_text(json.dumps(self.to_bbmodel(), separators=(",", ":")) + "\n")
