#!/usr/bin/env python3
"""Prepares the headless server template used by run_batch.py: downloads the Fabric server launcher for 1.16.1
(which fetches the Mojang 1.16.1 server jar and libraries on its first start) and starts it once.

Minecraft's EULA (https://aka.ms/MinecraftEULA) must be accepted by YOU before a server runs. This script only
writes eula.txt when you pass --accept-eula, i.e. when you state that you accept it.

  python3 tools/setup_server.py --accept-eula [--java /path/to/java17]
"""
import argparse
import os
import shutil
import subprocess
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import srb_common as C  # noqa: E402

LOADER = "0.20.1"
INSTALLER = "1.1.0"
URL = "https://meta.fabricmc.net/v2/versions/loader/1.16.1/%s/%s/server/jar" % (LOADER, INSTALLER)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.path.join(C.ROOT, "work"))
    ap.add_argument("--java", default=os.environ.get("SRB_JAVA", "java"))
    ap.add_argument("--accept-eula", action="store_true", help="you accept the Minecraft EULA")
    args = ap.parse_args()
    if not args.accept_eula:
        sys.exit("Read https://aka.ms/MinecraftEULA and pass --accept-eula if you accept it.")
    tdir = os.path.join(args.work, "template")
    os.makedirs(os.path.join(tdir, "mods"), exist_ok=True)
    jar = os.path.join(tdir, "fabric-server-launch.jar")
    if not os.path.exists(jar):
        print("downloading", URL)
        with urllib.request.urlopen(URL) as r, open(jar, "wb") as f:
            shutil.copyfileobj(r, f)
    with open(os.path.join(tdir, "eula.txt"), "w") as f:
        f.write("# accepted by the user via setup_server.py --accept-eula\neula=true\n")
    with open(os.path.join(tdir, "server.properties"), "w") as f:
        f.write("online-mode=false\nserver-port=25609\nlevel-seed=1\nview-distance=4\n")
    print("first start (downloads the 1.16.1 server jar and libraries) ...")
    # srb.enabled=false: no bot; the server is stopped through its console
    p = subprocess.Popen([args.java, "-Xmx1g", "-Dsrb.enabled=false", "-jar", "fabric-server-launch.jar", "nogui"],
                         cwd=tdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in p.stdout:
        sys.stdout.write(line)
        if "Done (" in line:
            p.stdin.write("stop\n")
            p.stdin.flush()
    p.wait()
    for d in ("world", "logs"):
        shutil.rmtree(os.path.join(tdir, d), ignore_errors=True)
    print("template ready:", tdir)


if __name__ == "__main__":
    main()
