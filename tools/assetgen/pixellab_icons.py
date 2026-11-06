"""Generates the 증강 card icons with PixelLab (https://api.pixellab.ai/v2, pixel art from text).

    python3 -I tools/assetgen/pixellab_icons.py <out dir> [augment id ...] [--seed N]   # raw 32 x 32 candidates

The prompts, the endpoint and every parameter are in tools/assetgen/augment_icons/prompts.json (an icon's own "seed" or
"params" override the shared ones). The set's look is the shared "style": a casual mobile-game sticker (chubby rounded
everyday objects and toys, bright pastels with one vivid accent, thick dark outline, no faces), 32 x 32 so the pixels
stay big and cute on the card. Each prompt is one plain object read from the augment's name and effect (a toaster for
자동 제련, a cardboard box for 잠복): no weapons-and-chalices fantasy, no pedestal, ground or lettering.

Look at each candidate, copy the ones to keep to tools/assetgen/augment_icons/<augment id>.png (the raw API output,
committed) and record a changed seed or prompt in prompts.json. augment_cards.py cleans them up for the font
(mcpl_assets/pixel_icons.py: alpha, outline, 2x size).

The API key is never in this repository: requests carry `Authorization: Bearer $PIXELLAB_API_KEY` (or a dummy value
where a proxy adds the real key). Server errors (5xx) and "too many concurrent jobs" (429) are retried; they cost no
generation.
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMPTS = HERE / "augment_icons" / "prompts.json"
API = "https://api.pixellab.ai"


def post(path, body, tries=10):
    key = os.environ.get("PIXELLAB_API_KEY", "dummy")
    for attempt in range(tries):
        req = urllib.request.Request(API + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        try:
            return json.load(urllib.request.urlopen(req, timeout=300))
        except urllib.error.HTTPError as e:
            detail = e.read()[:300]
            if (e.code >= 500 or e.code == 429) and attempt < tries - 1:
                time.sleep(6 + 4 * attempt)
                continue
            raise RuntimeError(f"HTTP {e.code}: {detail}") from None
        except (urllib.error.URLError, TimeoutError):
            if attempt == tries - 1:
                raise
            time.sleep(8 * (attempt + 1))


def request(spec, aid, seed=None):
    icon = spec["icons"][aid]
    body = {"description": spec["style"] + icon["prompt"], **spec["params"], **icon.get("params", {}),
            "seed": seed if seed is not None else icon.get("seed", spec["seed"])}
    return body


def generate(spec, aid, out, seed=None):
    body = request(spec, aid, seed)
    r = post(spec["endpoint"], body)
    b64 = r["image"]["base64"] if isinstance(r["image"], dict) else r["image"]
    if "," in b64[:40]:
        b64 = b64.split(",", 1)[1]
    name = f"{aid}.png" if seed is None else f"{aid}_s{seed}.png"
    (out / name).write_bytes(base64.b64decode(b64))
    used = (r.get("usage") or {}).get("generations", 0)
    return aid, name, used


def main():
    args = sys.argv[1:]
    seed = None
    if "--seed" in args:
        i = args.index("--seed")
        seed = int(args[i + 1])
        del args[i:i + 2]
    out, ids = Path(args[0]), args[1:]
    spec = json.loads(PROMPTS.read_text(encoding="utf-8"))
    ids = ids or sorted(spec["icons"])
    out.mkdir(parents=True, exist_ok=True)
    total = 0
    with ThreadPoolExecutor(4) as pool:
        for fut in [pool.submit(generate, spec, aid, out, seed) for aid in ids]:
            try:
                aid, name, used = fut.result()
                total += used
                print(f"{name}: {used:g} generation(s)", flush=True)
            except Exception as e:  # keep the others going
                print(f"failed: {e}", flush=True)
    print(f"{len(ids)} icons, {total:g} generations")


if __name__ == "__main__":
    main()
