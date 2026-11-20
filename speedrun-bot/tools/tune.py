#!/usr/bin/env python3
"""Automatic parameter search ("learning" by repeated runs): a cross-entropy method over tools/param_space.json.

Every candidate params.json is run on the same fixed seed set (common random numbers); its score is the mean of
srb_common.score() (IGT seconds of a nether entry, else timeout + 60 s per milestone not reached), lower is better.
Each generation samples candidates around the current mean (normalised space, per-key sigma), keeps the elite and
moves the mean/sigma toward it. Generation 0 always contains the current params (the baseline).

  python3 tools/tune.py --seed-file tools/seeds_tune.txt --generations 2 --population 5 --out results/tune
"""
import argparse
import json
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srb_common as C  # noqa: E402


def to_unit(space, params):
    return {k: (params[k] - v["min"]) / (v["max"] - v["min"]) for k, v in space.items()}


def from_unit(space, u):
    out = {}
    for k, v in space.items():
        x = min(1.0, max(0.0, u[k]))
        val = v["min"] + x * (v["max"] - v["min"])
        out[k] = int(round(val)) if v.get("int") else round(val, 3)
    return out


def evaluate(args, params, tag):
    path = os.path.join(args.out, tag + ".params.json")
    with open(path, "w") as f:
        json.dump(params, f, indent=2, sort_keys=True)
    csv_path = os.path.join(args.out, tag + ".csv")
    cmd = [sys.executable, "-I", os.path.join(C.ROOT, "tools", "run_batch.py"), "--seed-file", args.seed_file,
           "--params", path, "--out", csv_path, "--parallel", str(args.parallel), "--timeout-ticks",
           str(args.timeout_ticks), "--label", tag, "--quiet"] + args.batch_args
    subprocess.run(cmd, check=False)
    rows = C.read_csv(csv_path)
    sm = C.summary(rows)
    timeout_s = args.timeout_ticks / 20.0
    score = sum(C.score(r, timeout_s) for r in rows) / max(1, len(rows))
    return score, sm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed-file", required=True)
    ap.add_argument("--space", default=os.path.join(C.ROOT, "tools", "param_space.json"))
    ap.add_argument("--base", default=None, help="starting params.json (default: code defaults from defaults.json)")
    ap.add_argument("--generations", type=int, default=2)
    ap.add_argument("--population", type=int, default=5)
    ap.add_argument("--elite", type=int, default=2)
    ap.add_argument("--sigma", type=float, default=0.25)
    ap.add_argument("--parallel", type=int, default=4)
    ap.add_argument("--timeout-ticks", type=int, default=12000)
    ap.add_argument("--rng", type=int, default=7)
    ap.add_argument("--out", required=True)
    ap.add_argument("batch_args", nargs="*", help="extra args for run_batch.py after --")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    space = C.load_json(args.space)
    base = C.load_json(args.base) if args.base else C.load_json(os.path.join(C.ROOT, "tools", "defaults.json"))
    base = {k: base[k] for k in space}
    rng = random.Random(args.rng)
    mean = to_unit(space, base)
    sigma = {k: args.sigma for k in space}
    log = []
    best = None
    for g in range(args.generations):
        cands = []
        if g == 0:
            cands.append(dict(base))
        while len(cands) < args.population:
            u = {k: rng.gauss(mean[k], sigma[k]) for k in space}
            cands.append(from_unit(space, u))
        scored = []
        for i, p in enumerate(cands):
            tag = "g%d_c%d" % (g, i)
            score, sm = evaluate(args, p, tag)
            print("%s score %.1f nether %d/%d milestones %s" % (tag, score, sm["success"], sm["runs"],
                                                               {k: v[0] for k, v in sm["split_medians"].items()}),
                  flush=True)
            scored.append((score, tag, p))
            log.append({"gen": g, "tag": tag, "score": score, "success": sm["success"], "params": p})
            if best is None or score < best[0]:
                best = (score, tag, p)
        scored.sort(key=lambda t: t[0])
        elite = [to_unit(space, p) for _, _, p in scored[:args.elite]]
        for k in space:
            vals = [e[k] for e in elite]
            m = sum(vals) / len(vals)
            var = sum((v - m) ** 2 for v in vals) / len(vals)
            mean[k] = 0.5 * mean[k] + 0.5 * m
            sigma[k] = max(0.05, 0.5 * sigma[k] + 0.5 * var ** 0.5)
    with open(os.path.join(args.out, "tune_log.json"), "w") as f:
        json.dump(log, f, indent=2)
    with open(os.path.join(args.out, "best.params.json"), "w") as f:
        json.dump(best[2], f, indent=2, sort_keys=True)
    print("best %s score %.1f -> %s" % (best[1], best[0], os.path.join(args.out, "best.params.json")))


if __name__ == "__main__":
    main()
