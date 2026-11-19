#!/usr/bin/env python3
"""Headless batch runner: one fresh 1.16.1 world per seed, the bot runs once, the result is recorded, the world is
deleted, next seed. Several servers run in parallel (one slot directory each).

  python3 tools/run_batch.py --seeds 40 --parallel 4 --out results/baseline.csv
  python3 tools/run_batch.py --seed-file tools/seeds_tune.txt --params my_params.json --out results/x.csv

The slot directories are copies of the server template made by tools/setup_server.py (which needs your own EULA
acceptance). Results: one CSV row per run (seed, route, splits in IGT seconds, success/failure reason, MSPT) plus the
raw JSON lines next to it.
"""
import argparse
import json
import os
import queue
import random
import shutil
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srb_common as C  # noqa: E402

SERVER_PROPS = """online-mode=false
server-port={port}
view-distance={view}
level-seed={seed}
spawn-protection=0
enable-query=false
enable-rcon=false
snooper-enabled=false
difficulty=easy
gamemode=survival
max-players=2
sync-chunk-writes=false
motd=speedrun-bot
"""


def prepare_slot(template, slot_dir, mod_jar):
    if not os.path.isdir(slot_dir):
        shutil.copytree(template, slot_dir, symlinks=True)
    mods = os.path.join(slot_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        if f.startswith("speedrun-bot"):
            os.remove(os.path.join(mods, f))
    shutil.copy(mod_jar, mods)


def run_one(args, slot, slot_dir, seed, label):
    for d in ("world", "logs", "crash-reports"):
        shutil.rmtree(os.path.join(slot_dir, d), ignore_errors=True)
    with open(os.path.join(slot_dir, "server.properties"), "w") as f:
        f.write(SERVER_PROPS.format(port=args.port_base + slot, view=args.view_distance, seed=seed))
    out = os.path.join(slot_dir, "result.jsonl")
    if os.path.exists(out):
        os.remove(out)
    trace = os.path.join(args.trace_dir, "%s.txt" % seed) if args.trace_dir else None
    cmd = [args.java, "-Xmx%s" % args.xmx, "-XX:+UseG1GC",
           "-Dsrb.out=" + out,
           "-Dsrb.timeoutTicks=%d" % args.timeout_ticks,
           "-Dsrb.fair=%s" % ("false" if args.relaxed else "true"),
           "-Dsrb.renderChunks=%d" % args.view_distance,
           "-Dsrb.label=" + label]
    if args.params:
        cmd.append("-Dsrb.params=" + os.path.abspath(args.params))
    cmd += args.jvm_arg or []
    if trace:
        cmd.append("-Dsrb.trace=" + trace)
    cmd += ["-jar", "fabric-server-launch.jar", "nogui"]
    t0 = time.time()
    with open(os.path.join(slot_dir, "console.log"), "w") as log:
        try:
            subprocess.run(cmd, cwd=slot_dir, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                           timeout=args.wall_timeout)
        except subprocess.TimeoutExpired:
            pass
    result = None
    if os.path.exists(out):
        with open(out) as f:
            lines = [l for l in f.read().splitlines() if l.strip()]
        if lines:
            result = json.loads(lines[-1])
    if result is None:
        result = {"seed": str(seed), "label": label, "success": False, "reason": "harness_no_result",
                  "igt_ticks": 0, "splits": {}, "wall_s": time.time() - t0}
    if not args.keep_worlds:
        shutil.rmtree(os.path.join(slot_dir, "world"), ignore_errors=True)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, default=0, help="number of random seeds")
    ap.add_argument("--seed-file", help="file with one seed per line")
    ap.add_argument("--seed-list", help="comma separated seeds")
    ap.add_argument("--rng", type=int, default=None, help="random generator seed for --seeds")
    ap.add_argument("--parallel", type=int, default=4)
    ap.add_argument("--params", help="params.json (overrides the code defaults)")
    ap.add_argument("--out", required=True, help="CSV output path")
    ap.add_argument("--label", default="")
    ap.add_argument("--timeout-ticks", type=int, default=12000)
    ap.add_argument("--wall-timeout", type=int, default=900, help="seconds before a server is killed")
    ap.add_argument("--view-distance", type=int, default=10)
    ap.add_argument("--relaxed", action="store_true", help="relaxed (non-fair) perception, comparison only")
    ap.add_argument("--java", default=os.environ.get("SRB_JAVA", "java"))
    ap.add_argument("--xmx", default="2500m")
    ap.add_argument("--work", default=os.path.join(C.ROOT, "work"))
    ap.add_argument("--template", default=None, help="server template dir (default: <work>/template)")
    ap.add_argument("--mod", default=None, help="mod jar (default: build/libs/speedrun-bot-*.jar)")
    ap.add_argument("--trace-dir", default=None, help="write a decision trace per seed here")
    ap.add_argument("--keep-worlds", action="store_true")
    ap.add_argument("--port-base", type=int, default=25610, help="server port of slot 0 (separate batches need separate ranges)")
    ap.add_argument("--jvm-arg", action="append", help="extra -D... for the server (e.g. -Dsrb.probe=true)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    seeds = []
    if args.seed_file:
        with open(args.seed_file) as f:
            seeds += [l.strip() for l in f if l.strip() and not l.startswith("#")]
    if args.seed_list:
        seeds += [s.strip() for s in args.seed_list.split(",") if s.strip()]
    if args.seeds:
        rng = random.Random(args.rng)
        seeds += [str(rng.randint(-2 ** 63, 2 ** 63 - 1)) for _ in range(args.seeds)]
    if not seeds:
        ap.error("no seeds")
    template = args.template or os.path.join(args.work, "template")
    if not os.path.isfile(os.path.join(template, "fabric-server-launch.jar")):
        ap.error("no server template at %s: run tools/setup_server.py first" % template)
    mod = args.mod
    if mod is None:
        libs = os.path.join(C.ROOT, "build", "libs")
        cands = [f for f in os.listdir(libs) if f.startswith("speedrun-bot") and not f.endswith("-sources.jar")]
        mod = os.path.join(libs, sorted(cands)[-1])
    if args.trace_dir:
        os.makedirs(args.trace_dir, exist_ok=True)
    os.makedirs(args.work, exist_ok=True)
    import fcntl
    lock = open(os.path.join(args.work, ".lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        ap.error("another run_batch.py is using %s (one batch per work dir)" % args.work)

    jobs = queue.Queue()
    for s in seeds:
        jobs.put(s)
    results = []
    lock = threading.Lock()
    raw_path = os.path.splitext(args.out)[0] + ".jsonl"
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    raw = open(raw_path, "w")
    t_start = time.time()

    def worker(slot):
        slot_dir = os.path.join(args.work, "slot%d" % slot)
        prepare_slot(template, slot_dir, mod)
        while True:
            try:
                seed = jobs.get_nowait()
            except queue.Empty:
                return
            r = run_one(args, slot, slot_dir, seed, args.label)
            with lock:
                results.append(r)
                raw.write(json.dumps(r) + "\n")
                raw.flush()
                C.write_csv(args.out, [C.result_to_row(x) for x in results])
                if not args.quiet:
                    print("[%d/%d] seed %s: %s %s (%s) wall %.0fs" % (
                        len(results), len(seeds), r.get("seed"), "NETHER" if r.get("success") else "fail",
                        r.get("igt", "-"), r.get("reason"), r.get("wall_s", 0)), flush=True)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(min(args.parallel, len(seeds)))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    raw.close()
    rows = [C.result_to_row(x) for x in results]
    C.write_csv(args.out, rows)
    print("wall %.0fs for %d runs" % (time.time() - t_start, len(rows)))
    C.print_summary(rows, args.out)


if __name__ == "__main__":
    main()
