#!/usr/bin/env python3
"""RSG-style metrics from per-seed results (JSONL from run_batch.py).

Runs record the bot's first reset decision (`reset_why`, `reset_ticks`; core/ResetPolicy) but play on, so the
per-seed tables stay comparable. Here an attempt ends at that decision when it comes before the nether (exact:
the decision only used what the bot had seen by then), otherwise at the nether or the timeout.

  python3 tools/rsg_report.py results/eval_a.jsonl [results/eval_b.jsonl ...] [--reset-overhead 5]

Prints: attempts, nether entries, nether rate per attempt, attempts per nether, IGT spent per nether (all attempts,
resets included), the same with a fixed per-reset overhead added for world creation (an RTA-like figure), and the
reset reasons.
"""
import argparse
import json
from collections import Counter


def load(paths):
    rows = []
    for p in paths:
        with open(p) as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def attempt(r):
    """(nether?, igt seconds spent, reset reason or '')."""
    splits = r.get("splits", {}) or {}
    nether_t = splits.get("nether")
    reset_t = r.get("reset_ticks", -1)
    why = r.get("reset_why", "") or ""
    if why and reset_t is not None and reset_t >= 0 and (nether_t is None or reset_t < nether_t):
        return False, reset_t / 20.0, why
    if nether_t is not None:
        return True, nether_t / 20.0, ""
    return False, (r.get("igt_ticks") or 0) / 20.0, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--reset-overhead", type=float, default=5.0, help="seconds per reset (new world) for the RTA-like figure")
    a = ap.parse_args()
    rows = [r for r in load(a.files) if "splits" in r]
    att = [attempt(r) for r in rows]
    n = len(att)
    wins = sum(1 for ok, _, _ in att if ok)
    spent = sum(t for _, t, _ in att)
    resets = Counter(w for _, _, w in att if w)
    win_t = sorted(t for ok, t, _ in att if ok)
    print("attempts %d, nether %d (%.1f%% per attempt), resets %d" % (n, wins, 100.0 * wins / max(1, n), sum(resets.values())))
    if wins:
        print("attempts per nether %.1f; IGT spent per nether %.0f s (%.1f min); with %.0f s per reset %.1f min"
              % (n / wins, spent / wins, spent / wins / 60, a.reset_overhead,
                 (spent + a.reset_overhead * sum(resets.values())) / wins / 60))
        print("nether IGT of the kept attempts: " + ", ".join("%d:%04.1f" % (t // 60, t % 60) for t in win_t))
    else:
        print("no nether: IGT spent %.0f s over %d attempts (mean %.0f s per attempt)" % (spent, n, spent / max(1, n)))
    print("reset reasons: " + ", ".join("%s %d" % kv for kv in resets.most_common()))


if __name__ == "__main__":
    main()
