"""Shared helpers for the speedrun-bot harness (run_batch.py, tune.py, summarize.py)."""
import csv
import json
import os
import statistics

SPLITS = [
    "first_log", "wooden_pickaxe", "treasure_map", "treasure", "stone_pickaxe", "first_iron", "furnace", "iron_ingots", "bucket",
    "flint", "flint_and_steel", "water_bucket", "portal_site", "portal_room", "obsidian_10", "portal_lit",
    "in_portal", "nether",
]

CSV_FIELDS = ["seed", "label", "success", "reason", "route", "igt_s", "igt", "last_goal", "mspt_avg", "mspt_max",
              "wall_s", "fair"] + ["split_" + s for s in SPLITS]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def result_to_row(r):
    row = {k: r.get(k, "") for k in CSV_FIELDS}
    row["igt_s"] = round(r.get("igt_ticks", 0) / 20.0, 2)
    splits = r.get("splits", {}) or {}
    for s in SPLITS:
        v = splits.get(s)
        row["split_" + s] = "" if v is None else round(v / 20.0, 2)
    for k in ("mspt_avg", "mspt_max", "wall_s"):
        if isinstance(row.get(k), float):
            row[k] = round(row[k], 2)
    return row


def write_csv(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        w.writeheader()
        for r in rows:
            w.writerow(r)


def read_csv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def milestones(row):
    """Number of splits reached (progress measure for failed runs)."""
    n = 0
    for s in SPLITS:
        v = row.get("split_" + s, "")
        if v not in ("", None):
            n += 1
    return n


def score(row, timeout_s=600.0):
    """Lower is better: IGT seconds for a nether entry, else timeout + a penalty per missing milestone."""
    ok = str(row.get("success")).lower() == "true"
    if ok:
        return float(row["igt_s"])
    return timeout_s + 60.0 * (len(SPLITS) - milestones(row))


def summary(rows):
    n = len(rows)
    ok = [r for r in rows if str(r.get("success")).lower() == "true"]
    igts = sorted(float(r["igt_s"]) for r in ok)
    reasons = {}
    for r in rows:
        if str(r.get("success")).lower() != "true":
            reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
    split_medians = {}
    for s in SPLITS:
        vals = [float(r["split_" + s]) for r in rows if r.get("split_" + s) not in ("", None)]
        if vals:
            split_medians[s] = (len(vals), statistics.median(vals))
    mspt = [float(r["mspt_avg"]) for r in rows if r.get("mspt_avg") not in ("", None)]
    return {
        "runs": n,
        "success": len(ok),
        "success_rate": (len(ok) / n) if n else 0.0,
        "igt_mean": statistics.mean(igts) if igts else None,
        "igt_median": statistics.median(igts) if igts else None,
        "igt_best": igts[0] if igts else None,
        "reasons": dict(sorted(reasons.items(), key=lambda kv: -kv[1])),
        "split_medians": split_medians,
        "mspt_avg": statistics.mean(mspt) if mspt else None,
        "score_mean": statistics.mean(score(r) for r in rows) if rows else None,
    }


def fmt_time(s):
    if s is None:
        return "-"
    s = float(s)
    return "%d:%04.1f" % (int(s // 60), s % 60)


def print_summary(rows, title=""):
    sm = summary(rows)
    if title:
        print("== " + title)
    print("runs %d, nether %d (%.0f%%), IGT mean %s median %s best %s, score %.1f, MSPT avg %s" % (
        sm["runs"], sm["success"], 100 * sm["success_rate"], fmt_time(sm["igt_mean"]), fmt_time(sm["igt_median"]),
        fmt_time(sm["igt_best"]), sm["score_mean"] or 0, "%.1f" % sm["mspt_avg"] if sm["mspt_avg"] else "-"))
    print("failures: " + ", ".join("%s %d" % kv for kv in sm["reasons"].items()))
    print("split reached (count, median IGT): " + ", ".join(
        "%s %d@%s" % (k, v[0], fmt_time(v[1])) for k, v in sm["split_medians"].items()))
    return sm


def load_json(path):
    with open(path) as f:
        return json.load(f)
