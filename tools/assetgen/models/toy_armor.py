"""
공성전 장난감 병정 갑옷 (docs/siege-v2.md §7.3): the pack files that dress a 공성전 player's armour as a toy soldier's
uniform in the team's colour, fancier with every 장난감 갑옷 tier (0 = the kit armour, I-III bought in the shop).

How it works (the game: game/siege/shop/ToyArmor.java, same table as LOOKS below):
  - the plugin keeps the kit's armour (its stats) but gives each worn piece the vanilla leather equipment asset, the
    team colour as dyed_color, the vanilla item model of the leather piece, one exact vanilla armour trim per
    (team, tier) and custom_model_data string 0 = the look id. Players without the pack see team-dyed leather armour
    with that trim (the trim gets busier with the tier);
  - with the pack, assets/minecraft/equipment/leather.json (the vanilla layers, unchanged) adds trim_overrides: that
    exact (pattern, material) pair draws our texture instead of the trim (no palette), over the dyed leather, which it
    covers completely. Any other leather armour (other modes, minions) looks vanilla;
  - assets/minecraft/items/leather_<piece>.json select the look id (custom_model_data) for our icon, every other
    leather piece falls back to the vanilla definition (a select on the trim material with the dye tint).

Output (committed, copied into the pack as is): src/main/resourcepack-src/assets/...
"""

import json
from pathlib import Path

from mcpl_assets import armor_icons, armor_textures

TEAMS = armor_textures.TEAMS
TIERS = armor_textures.TIERS
PIECES = armor_icons.PIECES

# the vanilla trim that marks each look: the pattern by tier (busier with the tier for players without the pack),
# the material by team (gold braid on blue, amber resin braid on red)
TIER_PATTERN = {0: "minecraft:sentry", 1: "minecraft:coast", 2: "minecraft:bolt", 3: "minecraft:silence"}
TEAM_MATERIAL = {"blue": "minecraft:gold", "red": "minecraft:resin"}
# the vanilla leather colour the leather layers keep when undyed
LEATHER_UNDYED = -6265536
VANILLA_TRIM_MATERIALS = ("quartz", "iron", "netherite", "redstone", "copper", "gold", "emerald", "diamond", "lapis",
                          "amethyst", "resin")


def look_id(team, tier):
    """custom_model_data string 0 of a dressed piece, and the texture id below trims/entity/<layer>/."""
    return f"toy_armor/{team}_{tier}"


LOOKS = {(team, tier): (look_id(team, tier), TIER_PATTERN[tier], TEAM_MATERIAL[team]) for team in TEAMS
         for tier in TIERS}


def _leather_layers():
    dyed = [{"dyeable": {"color_when_undyed": LEATHER_UNDYED}, "texture": "minecraft:leather"},
            {"texture": "minecraft:leather_overlay"}]
    return {kind: [dict(layer) for layer in dyed] for kind in ("horse_body", "humanoid", "humanoid_baby",
                                                                "humanoid_leggings")}


def equipment():
    """assets/minecraft/equipment/leather.json: the 26.3 leather layers plus one trim override per look."""
    overrides = [{"when": {"material": material, "pattern": pattern}, "texture": f"mcpl:{look}"}
                 for (team, tier), (look, pattern, material) in sorted(LOOKS.items())]
    return {"layers": _leather_layers(), "trim_overrides": overrides}


def _vanilla_item(piece):
    """The 26.3 item definition of leather_<piece> (its trim overlays by material, the dye tint)."""
    tint = [{"type": "minecraft:dye", "default": LEATHER_UNDYED}]
    cases = [{"model": {"type": "minecraft:model", "model": f"minecraft:item/leather_{piece}_{m}_trim",
                        "tints": [dict(t) for t in tint]}, "when": f"minecraft:{m}"} for m in VANILLA_TRIM_MATERIALS]
    return {"type": "minecraft:select", "cases": cases,
            "fallback": {"type": "minecraft:model", "model": f"minecraft:item/leather_{piece}",
                         "tints": [dict(t) for t in tint]},
            "property": "minecraft:trim_material"}


def item_definition(piece):
    """assets/minecraft/items/leather_<piece>.json: the look's icon by custom_model_data, else vanilla."""
    cases = [{"when": look, "model": {"type": "minecraft:model",
                                      "model": f"mcpl:item/toy_armor/{piece}_{team}_{tier}"}}
             for (team, tier), (look, _p, _m) in sorted(LOOKS.items())]
    return {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                      "cases": cases, "fallback": _vanilla_item(piece)}}


def _json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def _png(path, image):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def write(assets: Path):
    """Writes every toy armour file below `assets` (src/main/resourcepack-src/assets); returns the paths written."""
    problems = []
    written = []
    for (team, tier), (look, _pattern, _material) in sorted(LOOKS.items()):
        for layer, paint in armor_textures.LAYERS.items():
            image = paint(team, tier)
            problems += [f"{layer} {look}: {p}" for p in armor_textures.check(layer, image)]
            path = assets / "mcpl" / "textures" / "trims" / "entity" / layer / f"{look}.png"
            _png(path, image)
            written.append(path)
        for piece in PIECES:
            image = armor_icons.icon(piece, team, tier)
            problems += [f"icon {piece} {look}: {p}" for p in armor_icons.check_icon(image)]
            name = f"{piece}_{team}_{tier}"
            path = assets / "mcpl" / "textures" / "item" / "toy_armor" / f"{name}.png"
            _png(path, image)
            model = assets / "mcpl" / "models" / "item" / "toy_armor" / f"{name}.json"
            _json(model, {"parent": "minecraft:item/generated",
                          "textures": {"layer0": f"mcpl:item/toy_armor/{name}"}})
            written += [path, model]
    if problems:
        raise ValueError("toy armour failed the checks:\n  - " + "\n  - ".join(problems))
    for folder in (assets / "mcpl" / "textures" / "trims", assets / "mcpl" / "textures" / "item" / "toy_armor",
                   assets / "mcpl" / "models" / "item" / "toy_armor"):
        for stale in sorted(folder.rglob("*")) if folder.exists() else []:
            if stale.is_file() and stale not in written:
                stale.unlink()      # a look that is no longer defined
    path = assets / "minecraft" / "equipment" / "leather.json"
    _json(path, equipment())
    written.append(path)
    for piece in PIECES:
        path = assets / "minecraft" / "items" / f"leather_{piece}.json"
        _json(path, item_definition(piece))
        written.append(path)
    return written
