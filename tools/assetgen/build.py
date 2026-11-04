"""
Builds the MCPL 3D models into the resource pack sources (src/main/resourcepack-src/models/**/*.bbmodel) and renders
preview sheets. The Gradle `resourcePack` task converts the .bbmodel files into the pack; this script only needs to
run when a model changes (its output is committed).

    python3 -I tools/assetgen/build.py                      # models only
    python3 -I tools/assetgen/build.py --render <out dir>   # + preview PNGs (needs node, playwright and three:
                                                            #   cd tools/assetgen/render && npm install)
    python3 -I tools/assetgen/build.py --render <out dir> --only weapons|toys|armor   # just one family's sheets

Models: the ability weapons (models/weapons*.py -> models/weapon/<id>), the 공성전 v2 toy kingdom buildings
(models/toys.py -> models/structure/<id>) and creatures (models/creatures.py -> models/creature/<id>); the 공성전 toy
soldier armour (models/toy_armor.py -> assets/minecraft/equipment/leather.json, the leather item definitions and
assets/mcpl/textures/{trims/entity,item}/toy_armor).
Ported from STSV tools/assetgen (same palette formula, texture rules and model checks).
"""

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from mcpl_assets.palette import PALETTE  # noqa: E402
from models import creatures, toy_armor, toys, weapons  # noqa: E402
import armor_preview  # noqa: E402
import toy_preview  # noqa: E402
import weapon_preview  # noqa: E402

REPO = HERE.parent.parent
MODELS = REPO / "src" / "main" / "resourcepack-src" / "models"
ASSETS = REPO / "src" / "main" / "resourcepack-src" / "assets"
BUILD = REPO / "build" / "assetgen"
RENDER = HERE / "render" / "render.mjs"


def render(json_path, png_path):
    subprocess.run(["node", str(RENDER), str(json_path), str(png_path)], check=True)


def _clean(folder, keep):
    """Removes .bbmodel files below `folder` whose id is no longer defined (and the old flat tower_* models)."""
    for stale in sorted(folder.rglob("*.bbmodel")):
        rel = stale.relative_to(MODELS).with_suffix("").as_posix()
        if rel not in keep:
            stale.unlink()
            print(f"removed {rel}.bbmodel (model no longer defined)")


def main():
    args = sys.argv[1:]
    render_dir = Path(args[args.index("--render") + 1]) if "--render" in args else None
    only = args[args.index("--only") + 1] if "--only" in args else None
    MODELS.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)

    for stale in MODELS.glob("*.bbmodel"):
        stale.unlink()      # the cyberpunk towers lived here (tower_*); every model now has a sub folder
        print(f"removed {stale.name} (model no longer defined)")

    weapon_dir = MODELS / "weapon"
    weapon_dir.mkdir(exist_ok=True)
    _clean(weapon_dir, {f"weapon/{a}" for a in weapons.WEAPONS})
    armoury = []
    for ability, fn in weapons.WEAPONS.items():
        model = fn()
        assert model.name == f"weapon/{ability}", f"{ability}: the model must be named weapon/{ability}"
        armoury.append(model)
        model.write(weapon_dir / f"{ability}.bbmodel")
        print(f"weapon  {ability}: {len(model.elements)} elements")

    toy_models = dict(toys.ALL)
    toy_models.update(creatures.ALL)
    for folder in ("structure", "creature"):
        (MODELS / folder).mkdir(exist_ok=True)
        _clean(MODELS / folder, set(toy_models))
    built = {}
    for name, fn in toy_models.items():
        model = fn()
        assert model is not None and model.name == name, name
        built[name] = model
        model.write(MODELS / f"{name}.bbmodel")
        print(f"toy     {name}: {len(model.elements)} elements")

    written = toy_armor.write(ASSETS)
    print(f"armour  toy_armor: {len(written)} files ({len(toy_armor.LOOKS)} looks)")

    if render_dir and only in (None, "weapons"):
        render_dir.mkdir(parents=True, exist_ok=True)
        for model in armoury:
            weapon_preview.sheet(model, render, BUILD, render_dir / f"{model.name.replace('/', '_')}.png")
        weapon_preview.lineup(armoury, render, BUILD, render_dir / "weapons_lineup.png")
    if render_dir and only in (None, "toys"):
        render_dir.mkdir(parents=True, exist_ok=True)
        toy_preview.render_all(built, render, BUILD, render_dir)
    if render_dir and only in (None, "armor"):
        render_dir.mkdir(parents=True, exist_ok=True)
        armor_preview.main_sheets(render_dir)


if __name__ == "__main__":
    _ = PALETTE
    main()
