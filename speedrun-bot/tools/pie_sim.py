#!/usr/bin/env python3
"""Offline pie chart simulator for the ocean route's treasure localisation (design.md section 15).

The F3 pie chart sense (core/PieChart) is a pure function of the chest positions and the camera, so with a world dump
(harness-only: -Dsrb.probe=true -Dsrb.probeDump=<dir> writes every chest within 10 chunks of the spawn with its loot
table, plus a surface grid) whole sweep strategies and candidate ranking rules can be replayed in seconds instead of
full runs. Truth (which chest is a buried treasure) is used only to score a strategy, never by it.

  python3 tools/pie_sim.py <dump dir> [--strategy current|...] [--verbose]

Per seed it reports whether the strategy ends on a buried treasure column, how many candidate checks it takes, and a
rough time model (ticks: view changes, sweeps, travel at boat/swim speed) so strategies can be compared by expected
time to the right column.
"""
import argparse
import glob
import json
import math
import os

RENDER_CHUNKS = 10
NARROW = (30.0, 0.27)  # FOV option, window aspect
VIEW_CHANGE = 20
BAND0, BAND1 = 4.0, 18.0
CHEST_LO, CHEST_HI = 40, 66


def hfov(vfov, aspect):
    return math.degrees(2 * math.atan(math.tan(math.radians(vfov / 2)) * aspect))


def direction(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def in_frustum(view, ex, ey, ez, yaw, pitch, bx, by, bz):
    """Port of PieChart.blockInFrustum."""
    vfov, aspect = view
    ecx, ecz = math.floor(ex) >> 4, math.floor(ez) >> 4
    if abs((bx >> 4) - ecx) > RENDER_CHUNKS or abs((bz >> 4) - ecz) > RENDER_CHUNKS:
        return False
    f = direction(yaw, pitch)
    r = direction(yaw + 90, 0)
    u = (r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
    th, tv = math.tan(math.radians(hfov(vfov, aspect) / 2)), math.tan(math.radians(vfov / 2))
    right = left = up = down = front = False
    for k in range(8):
        dx, dy, dz = bx + (k & 1) - ex, by + ((k >> 1) & 1) - ey, bz + ((k >> 2) & 1) - ez
        fz = dx * f[0] + dy * f[1] + dz * f[2]
        rx = dx * r[0] + dy * r[1] + dz * r[2]
        uy = dx * u[0] + dy * u[1] + dz * u[2]
        right |= rx <= fz * th
        left |= -rx <= fz * th
        up |= uy <= fz * tv
        down |= -uy <= fz * tv
        front |= fz > 0.05
    return right and left and up and down and front


def angle_diff(a, b):
    d = (a - b) % 360
    if d > 180:
        d -= 360
    return abs(d)


def yaw_to(x, z, tx, tz):
    return math.degrees(math.atan2(-(tx - x), tz - z))


class World:
    def __init__(self, d):
        self.seed = d['seed']
        self.spawn = d['spawn']
        self.x0, self.z0, self.n = d['x0'], d['z0'], d['n']
        self.grid = d['grid']
        self.chests = [(c[0], c[1], c[2], c[3].split('/')[-1]) for c in d['chests']]
        self.treasures = [(c[0], c[2]) for c in self.chests if c[3] == 'buried_treasure']
        self.chest_y = {(c[0], c[2]): c[1] for c in self.chests if c[3] == 'buried_treasure'}

    def col(self, x, z):
        i, j = int(math.floor(x)) - self.x0, int(math.floor(z)) - self.z0
        if 0 <= i < self.n and 0 <= j < self.n:
            return self.grid[i * self.n + j]
        return None

    def water(self, x, z):
        v = self.col(x, z)
        return v is not None and v < 0

    def eye_at(self, x, z):
        v = self.col(x, z)
        if v is None or v < 0:
            return 63.0  # swimming / in a boat on the sea
        return v + 1.62

    def count(self, view, ex, ey, ez, yaw, pitch, loaded_from):
        lx, lz = loaded_from
        n = 0
        for (bx, by, bz, _) in self.chests:
            # loaded chunks: around where the bot has been (server view distance 10)
            if in_frustum(view, ex, ey, ez, yaw, pitch, bx, by, bz):
                n += 1
        return n

    def nearest_water(self, x, z, r=16):
        if self.water(x, z):
            return x, z
        best = None
        for dx in range(-r, r + 1, 2):
            for dz in range(-r, r + 1, 2):
                if self.water(x + dx, z + dz):
                    d = math.hypot(dx, dz)
                    if best is None or d < best[0]:
                        best = (d, x + dx, z + dz)
        return (best[1], best[2]) if best else (x, z)


class Reading:
    __slots__ = ('x', 'ey', 'z', 'yaw', 'pitch', 'h', 'v', 'count')

    def __init__(self, x, ey, z, yaw, pitch, h, v, count):
        self.x, self.ey, self.z, self.yaw, self.pitch, self.h, self.v, self.count = x, ey, z, yaw, pitch, h, v, count

    @property
    def hit(self):
        return self.count > 0


def in_view(r, cx, cz, lo=CHEST_LO, hi_=CHEST_HI):
    """Port of TreasureLocator.inView (column centre in the reading's view, vertical chest-height model)."""
    dist = max(1.0, math.hypot(cx - r.x, cz - r.z))
    slack = math.degrees(math.atan(0.71 / dist))
    if angle_diff(yaw_to(r.x, r.z, cx, cz), r.yaw) > r.h / 2 + slack:
        return False
    hi = math.degrees(math.atan2(r.ey - lo, dist))
    lo_ = math.degrees(math.atan2(r.ey - (hi_ + 1), dist))
    vs = slack
    return hi >= r.pitch - r.v / 2 - vs and lo_ <= r.pitch + r.v / 2 + vs


def place_index(places, r):
    for i, p in enumerate(places):
        if math.hypot(p[0] - r.x, p[1] - r.z) < 3:
            return i
    places.append((r.x, r.z))
    return len(places) - 1


def rank_columns(readings, x, z, rng, rule='current', excluded=()):
    places = []
    pidx = [place_index(places, r) for r in readings]
    out = []
    x0 = math.floor((x - rng) / 16) * 16 + 9
    z0 = math.floor((z - rng) / 16) * 16 + 9
    cx = x0
    while cx <= x + rng:
        cz = z0
        while cz <= z + rng:
            d = math.hypot(cx + .5 - x, cz + .5 - z)
            if d <= rng and (cx, cz) not in excluded:
                votes = set()
                s = 0.0
                miss_places = set()
                for r, pi in zip(readings, pidx):
                    iv = in_view(r, cx + .5, cz + .5)
                    if not iv:
                        continue
                    if r.hit:
                        s += 1
                        votes.add(pi)
                    else:
                        s -= 0.25
                        miss_places.add(pi)
                if rule == 'current':
                    key = (len(votes) * 100 + s, -d)
                elif rule == 'veto':
                    # a place that looked at the column at some band and saw nothing there vetoes it
                    key = (len(votes) - 2 * len(miss_places - votes), len(votes) * 100 + s, -d)
                else:
                    raise ValueError(rule)
                if s > 0.5:
                    out.append((key, cx, cz))
            cz += 16
        cx += 16
    out.sort(reverse=True)
    return out


def sweep(w, x, z, yaw0=0.0, step=6.0, bands=(BAND0, BAND1)):
    """A full sweep at (x,z): band 0 all around, band 1 only at the yaws band 0 hit (as TreasureHunt.locate)."""
    ey = w.eye_at(x, z)
    h = hfov(*NARROW)
    rs = []
    hits = []
    n = int(round(360 / step))
    for i in range(n):
        yaw = yaw0 + i * step
        c = w.count(NARROW, x, ey, z, yaw, bands[0], (x, z))
        rs.append(Reading(x, ey, z, yaw, bands[0], h, NARROW[0], c))
        if c:
            hits.append(yaw)
    for yaw in hits:
        for b in bands[1:]:
            c = w.count(NARROW, x, ey, z, yaw, b, (x, z))
            rs.append(Reading(x, ey, z, yaw, b, h, NARROW[0], c))
    ticks = VIEW_CHANGE + n + len(hits) * (len(bands) - 1) + 4
    return rs, ticks


def bearing(rs, x, z):
    sx = sz = 0.0
    n = 0
    for r in rs:
        if r.hit and math.hypot(r.x - x, r.z - z) < 2:
            sx += math.cos(math.radians(r.yaw))
            sz += math.sin(math.radians(r.yaw))
            n += 1
    return None if n == 0 else math.degrees(math.atan2(sz, sx))


def check_down(w, cx, cz):
    """Verify: look straight down at the column from just above it (pitch toward y 58 and 42 of the column)."""
    x, z = cx + .5, cz + .5
    ey = w.eye_at(x, z) if w.water(x, z) else w.eye_at(x, z)
    ex, ez = x - 0.6, z  # within a block of it
    for ty in (58, 42):
        dist = math.hypot(x - ex, z - ez)
        pitch = math.degrees(math.atan2(ey - ty, dist))
        yaw = yaw_to(ex, ez, x, z)
        if w.count(NARROW, ex, ey, ez, yaw, pitch, (ex, ez)) > 0:
            return True
    return False


def check_cross(w, cx, cz, off=0.4):
    """Verify from straight above: pitch 90 with the thin window at two yaws 90 degrees apart. Both strips hold the
    column's axis; a deeper chest a few blocks off the axis (dungeons, ruins) is in at most one of them."""
    x, z = cx + .5, cz + .5
    ey = w.eye_at(x, z)
    ex, ez = x - off, z + off * 0.5
    a = w.count(NARROW, ex, ey, ez, 0.0, 90.0, (ex, ez))
    b = w.count(NARROW, ex, ey, ez, 90.0, 90.0, (ex, ez))
    if not (a and b):
        return False
    # the same chest in both: count chests in both views
    both = [c for c in w.chests if in_frustum(NARROW, ex, ey, ez, 0.0, 90.0, c[0], c[1], c[2])
            and in_frustum(NARROW, ex, ey, ez, 90.0, 90.0, c[0], c[1], c[2])]
    return len(both) > 0


def depth_ok(w, cx, cz, max_depth=16):
    v = w.col(cx, cz)
    return v is None or v >= 0 or (63 + v) <= max_depth  # v = -(floor top y)


BOAT = 8.0 / 20  # blocks per tick
SWIM = 4.0 / 20


def run_current(w, opts):
    """The round-5 strategy: sweep at the spawn, 40 to the side, a third off the line, then up to 4 checks."""
    sx, sz = w.spawn[0] + .5, w.spawn[2] + .5
    ticks = 0
    rs, t = sweep(w, sx, sz)
    ticks += t
    if not any(r.hit for r in rs):
        return dict(result='no_signal', ticks=ticks, checks=0)
    places = [(sx, sz)]
    x, z = sx, sz
    nsweeps = opts.get('sweeps', 3)
    for k in range(1, nsweeps):
        if k == 1:
            b = bearing(rs, x, z)
            side = math.radians(b + 90)
            dist = opts.get('side', 40)
        else:
            best = rank_columns(rs, x, z, 150, opts.get('rule', 'current'))
            if not best:
                break
            bx, bz = best[0][1] + .5, best[0][2] + .5
            side = math.radians(yaw_to(x, z, bx, bz))
            dist = min(opts.get('side', 40), 0.5 * math.hypot(bx - x, bz - z))
        tx, tz = x - math.sin(side) * dist, z + math.cos(side) * dist
        tx, tz = w.nearest_water(tx, tz)
        ticks += math.hypot(tx - x, tz - z) / BOAT + 30
        x, z = tx, tz
        r2, t = sweep(w, x, z)
        rs += r2
        ticks += t
        places.append((x, z))
    excluded = set()
    checks = 0
    while checks < opts.get('verify', 4):
        cand = rank_columns(rs, x, z, 150 + (32 if checks else 0), opts.get('rule', 'current'), excluded)
        if not cand:
            break
        cx, cz = cand[0][1], cand[0][2]
        d = math.hypot(cx + .5 - x, cz + .5 - z)
        ticks += d / BOAT + 2 * VIEW_CHANGE + 25
        x, z = cx + .5, cz + .5
        checks += 1
        if check_down(w, cx, cz):
            ok = (cx, cz) in w.treasures
            return dict(result='treasure' if ok else 'false_confirm', ticks=ticks, checks=checks, col=(cx, cz),
                        depth=(63 + w.col(cx, cz) if w.col(cx, cz) is not None and w.col(cx, cz) < 0 else 0))
        excluded.add((cx, cz))
    return dict(result='not_found', ticks=ticks, checks=checks)


def run_early(w, opts):
    """Decide before any wood: sweep at the spawn, swim/walk to a second place 40 to the side, sweep, then check the
    best candidates near enough from straight above (two crossed thin strips) and give up (reset) past a budget."""
    sx, sz = w.spawn[0] + .5, w.spawn[2] + .5
    ticks = 80  # the spawn look-around before the sweep
    rs, t = sweep(w, sx, sz)
    ticks += t
    if not any(r.hit for r in rs):
        return dict(result='no_signal', ticks=ticks, checks=0)
    b = bearing(rs, sx, sz)
    best = None
    side_d = opts.get('side', 40)
    for sgn in (1, -1):
        tx, tz = sx - math.sin(math.radians(b + 90 * sgn)) * side_d, sz + math.cos(math.radians(b + 90 * sgn)) * side_d
        wx, wz = w.nearest_water(tx, tz)
        if w.water(wx, wz):
            best = (wx, wz)
            break
    if best is None:
        best = (tx, tz)
    x, z = best
    ticks += math.hypot(x - sx, z - sz) / SWIM + 20
    r2, t = sweep(w, x, z)
    rs += r2
    ticks += t
    if opts.get('sweeps', 2) >= 3:
        cand = rank_columns(rs, x, z, 150, opts.get('rule', 'current'))
        if cand:
            bx, bz = cand[0][1] + .5, cand[0][2] + .5
            side = math.radians(yaw_to(x, z, bx, bz))
            dist = min(side_d, 0.5 * math.hypot(bx - x, bz - z))
            tx, tz = w.nearest_water(x - math.sin(side) * dist, z + math.cos(side) * dist)
            ticks += math.hypot(tx - x, tz - z) / SWIM + 20
            x, z = tx, tz
            r3, t = sweep(w, x, z)
            rs += r3
            ticks += t
    nplaces = len({place_index([], r) for r in rs}) if False else None
    excluded = set()
    checks = 0
    rmax = opts.get('range', 100)
    budget = opts.get('budget', 1200)
    minvotes = opts.get('minvotes', 2)
    while checks < opts.get('verify', 3) and ticks < budget:
        cand = [c for c in rank_columns(rs, x, z, 150, opts.get('rule', 'current'), excluded)
                if math.hypot(c[1] + .5 - sx, c[2] + .5 - sz) <= rmax and c[0][0] >= minvotes * 100]
        if not cand:
            break
        cx, cz = cand[0][1], cand[0][2]
        d = math.hypot(cx + .5 - x, cz + .5 - z)
        ticks += d / SWIM + VIEW_CHANGE + 15
        x, z = cx + .5, cz + .5
        checks += 1
        if check_cross(w, cx, cz):
            ok = (cx, cz) in w.treasures
            return dict(result=('treasure' if depth_ok(w, cx, cz) else 'treasure_deep') if ok else 'false_confirm', ticks=ticks,
                        checks=checks, col=(cx, cz), depth=(63 + w.col(cx, cz) if w.col(cx, cz) < 0 else 0))
        excluded.add((cx, cz))
    return dict(result='not_found', ticks=ticks, checks=checks)


STRATEGIES = {'current': run_current, 'early': run_early}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dumps')
    ap.add_argument('--strategy', default='current')
    ap.add_argument('--opt', action='append', default=[], help='key=value strategy options')
    ap.add_argument('--verbose', action='store_true')
    ap.add_argument('--stats', action='store_true')
    a = ap.parse_args()
    if a.stats:
        stats(a.dumps)
        return
    opts = {}
    for o in a.opt:
        k, v = o.split('=')
        try:
            opts[k] = float(v)
        except ValueError:
            opts[k] = v
    res = []
    for fn in sorted(glob.glob(os.path.join(a.dumps, '*.json'))):
        w = World(json.load(open(fn)))
        sp = w.spawn
        near = [math.hypot(t[0] - sp[0], t[1] - sp[2]) for t in w.treasures]
        r = STRATEGIES[a.strategy](w, opts)
        r['seed'] = w.seed
        r['nearest'] = round(min(near)) if near else -1
        r['chests'] = len(w.chests)
        res.append(r)
        if a.verbose:
            print(r)
    n = len(res)
    by = {}
    for r in res:
        by[r['result']] = by.get(r['result'], 0) + 1
    tr = [r for r in res if r['result'] == 'treasure']
    print('seeds %d  %s' % (n, by))
    tot = sum(r['ticks'] for r in res) / 20
    print('time spent on all seeds %.0f s, per found diggable treasure %.0f s' % (tot, tot / max(1, len(tr))))
    if tr:
        ts = sorted(r['ticks'] / 20 for r in tr)
        print('treasure: median %.0f s (of localisation+checks), checks %s' % (ts[len(ts) // 2], sorted(r['checks'] for r in tr)))
    with_t = [r for r in res if r['nearest'] >= 0]
    print('seeds with a buried treasure within 10 chunks: %d; found %d' % (len(with_t), len(tr)))



def stats(dumps):
    """What lies around the ocean spawns: chest kinds by distance (truth, for choosing a route)."""
    import collections
    rows = []
    for fn in sorted(glob.glob(os.path.join(dumps, '*.json'))):
        w = World(json.load(open(fn)))
        sp = w.spawn
        sea = sum(1 for dx in range(-48, 49, 4) for dz in range(-48, 49, 4) if w.water(sp[0] + dx, sp[2] + dz)) / 625
        near = collections.defaultdict(lambda: 999)
        for (x, y, z, k) in w.chests:
            d = math.hypot(x - sp[0], z - sp[2])
            near[k] = min(near[k], round(d))
        rows.append((w.seed, round(sea, 2), dict(near)))
    n = len(rows)
    print('seeds', n)
    for k in ('buried_treasure', 'shipwreck_treasure', 'shipwreck_map', 'shipwreck_supply', 'underwater_ruin_small', 'underwater_ruin_big', 'simple_dungeon'):
        ds = [r[2].get(k, 999) for r in rows]
        print('%-22s <64:%3d  <100:%3d  <160:%3d' % (k, sum(d < 64 for d in ds), sum(d < 100 for d in ds), sum(d < 160 for d in ds)))
    iron = [min(r[2].get('buried_treasure', 999), r[2].get('shipwreck_treasure', 999)) for r in rows]
    print('iron chest (treasure or wreck) <64:%d <100:%d <160:%d' % (sum(d < 64 for d in iron), sum(d < 100 for d in iron), sum(d < 160 for d in iron)))
    return rows


if __name__ == '__main__':
    main()
