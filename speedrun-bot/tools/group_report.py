#!/usr/bin/env python3
"""Splits a result CSV into seed groups and prints a summary per group (and per chosen route).

Groups come from a probe file (JSON lines written by run_batch.py --jvm-arg -Dsrb.probe=true): the harness's own
structure locator says how far the nearest shipwreck is from the spawn. That is seed classification for reporting
only; the bot never sees it.

  python3 tools/group_report.py results/eval.csv --probe results/probe_eval.jsonl --ship-dist 96
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srb_common as C  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--probe", help="probe JSON lines (seed -> structure distances)")
    ap.add_argument("--ship-dist", type=float, default=96.0, help="'ocean spawn' = a shipwreck within this distance")
    args = ap.parse_args()
    rows = C.read_csv(args.csv)
    groups = {"all": rows}
    if args.probe:
        probe = {}
        with open(args.probe) as f:
            for line in f:
                if line.strip():
                    r = json.loads(line)
                    probe[r["seed"]] = r.get("probe", {})
        ocean = [r for r in rows if 0 <= probe.get(r["seed"], {}).get("shipwreck", -1) <= args.ship_dist]
        groups["shipwreck<=%d" % args.ship_dist] = ocean
        groups["other"] = [r for r in rows if r not in ocean]
    for route in sorted({r.get("route", "") for r in rows}):
        groups["route=" + route] = [r for r in rows if r.get("route") == route]
    for name, g in groups.items():
        if g:
            C.print_summary(g, "%s (%d seeds)" % (name, len(g)))
            print()


if __name__ == "__main__":
    main()
