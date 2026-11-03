# tools/assetgen

Builds the MCPL resource pack's 3D models in code (ported from STSV): the ability weapons (`models/weapons.py`) and
the 공성전 v2 toy kingdom: buildings and capture landmarks (`models/toys.py` → `structure/*`) and creatures
(`models/creatures.py` → `creature/*`). Output: `src/main/resourcepack-src/models/**/*.bbmodel` (committed; open
them in Blockbench for a look). The Gradle `resourcePack` task turns them into the pack (`core.pack.PackBuilder`); a
sub folder is part of the id, so `models/weapon/police.bbmodel` becomes the item model `mcpl:weapon/police`.

```
python3 -I tools/assetgen/build.py                                       # rebuild every model
cd tools/assetgen/render && npm install                                  # once, for previews (three.js)
python3 -I tools/assetgen/build.py --render <dir> [--only weapons|toys]  # + preview PNGs
python3 -I tools/assetgen/attack_preview.py <dir> [kind ...]             # creature attack GIFs (Pillow)
```

Previews need Node + Playwright (Chromium). In a cloud session:
`PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers NODE_PATH=/opt/node22/lib/node_modules`.

| file | what |
|---|---|
| `mcpl_assets/palette.py` | every colour: OKLCH ramps generated from (hue, chroma, lightness) — add a material here, never a raw RGB |
| `mcpl_assets/textures.py` | the texture registry (`SURFACES`, `FRAME_TIME`, `TRANSLUCENT`), the v1 cyberpunk textures (unused) |
| `mcpl_assets/toy_textures.py` | toy kingdom textures (`t_*`, 32 px) and their pastel ramps; `t_bubble` = the shader's marker alpha |
| `mcpl_assets/toykit.py` | block-space builder for buildings / creatures: `rbox` (chamfered toy box), eyes, cheeks, slabs |
| `toy_preview.py` | toy sheets: building stages + bubble, minions in both team colours, jungle, one sheet per creature, scale lineup |
| `attack_preview.py` | creature attack GIFs from `models/creature_attacks.json` (the game's poses; `./gradlew updateCreatureAttacks`) |
| `mcpl_assets/weapon_textures.py` | weapon textures (`w_*`, 32 px) |
| `mcpl_assets/model.py` | the box DSL → `.bbmodel`, plus the checks (z-fighting, see-through boxes, −16..32 range) |
| `mcpl_assets/weaponkit.py` | the weapon builder: weapon space, common parts, the shared `display` preset |
| `weapon_preview.py` | weapon sheets: icon (+16/32 px), first person, third person, ground, item frame |

## 새 능력 무기 추가하는 법 (ability weapons)

In 공성전 a player's sword shows the 3D weapon of their ability. The sword keeps its vanilla item model (iron, diamond or
netherite sword by difficulty) and carries `custom_model_data` strings `[<ability id>]`; `PackBuilder` overrides those
three swords' item definitions (`assets/minecraft/items/<sword>.json`) with a `minecraft:select` on that string whose
cases are every `models/weapon/<id>.bbmodel` (`mcpl:item/weapon/<id>`) and whose fallback is the vanilla sword. So
players without the pack, and every normal sword, look vanilla. The weapon also keeps its own item definition
`mcpl:weapon/<ability id>`.

1. Read the ability's section in `docs/abilities.md` and pick what that job / ability would **naturally carry**:
   classic, readable, real-world or fantasy (a nightstick, a pickaxe, a forge hammer, a revolver with a bayonet, a
   cleaver...), never sci-fi greebles. Add one or two identity details (a badge, handcuffs, a lantern, a hot face).
   It must still read as a melee weapon in hand.
2. Write `def <id>():` in `tools/assetgen/models/weapons.py` (or a batch module such as `models/weapons_b3.py`, whose
   `WEAPONS` weapons.py merges) with `Weapon("weapon/<id>")` and end with
   `return w.finish()`; add it to `WEAPONS`. Use the five reference weapons there as templates.
3. Add the id to `AbilityWeaponModels.MODELED` (`game/superpower/ui/AbilityWeaponModels.java`).
4. `python3 -I tools/assetgen/build.py --render <dir> --only weapons`, look at `weapon_<id>.png` and
   `weapons_lineup.png`, iterate. Then `./gradlew resourcePack` (or `build`) and commit the `.bbmodel` with the code.
   `ResourcePackContentsTest` fails while an id in `MODELED` has no item definition / model / display preset in the
   pack, or the pack has a weapon that `MODELED` does not list, or a 공성전 pool ability has none, or a sword override
   does not select every weapon.

### Weapon space and proportions

- Upright along +y: pommel at the bottom, tip at the top. x = the broad plane (blade width, axe/pick head, gun
  profile; the business side towards +x), z = thickness (the two flats). `Weapon.box` takes x/z relative to the
  centre line, y as is.
- **Orientation rule** (every weapon, no exceptions): the business side is **+x** (cutting edge, striking face, pick
  point, axe bit, hook, a gun's trigger guard and sights' far side), the tip / head at the top (+y), the hand at
  `GRIP_Y`. The shared preset turns +x so it faces **forward / down in hand** (first and third person, both hands: a
  cleaver's edge and a hammer's face lead the swing, the spine stays back) and **upper-left in the inventory icon**,
  exactly like the blade of a vanilla axe. Never mirror a model or tune `display` per weapon to fix a backwards look:
  move the part to +x instead.
- The hand holds `(0, GRIP_Y = 1.5, 0)`: grip about y −2..6, pommel below, the weapon's "head" from y ~16 up.
  Reference length 26 units (y −4..26); keep x within −7..7 (a few units more for a spike), z within −3..3.
- Thickness: blades 0.75–1.25, shafts 1.75–3, heads 4–5.5. Details (rivets, badges, bands) are at least 0.125–0.25 thick.
- Silhouette first: the weapon must read at 16 px in the inventory (the sheet shows 16/32 px icons). One big,
  characteristic shape plus one or two identity details beats many small greebles.

### Texel density and textures

- Weapon textures are 32×32 = **2 texels per model unit** (twice vanilla item density; towers use 64 px at display
  scale 4 = vanilla block density). Auto UV wraps every 16 units, so textures tile; design them seamless.
- Light from the top-left, at most one ramp step of jitter, fixed seeds (STSV ART_STYLE). Name new textures `w_*`
  and register them in `weapon_textures.SURFACES` (+ `FRAME_TIME` for animated strips, 32 px per frame).
- Transparent pixels only on thin plates (≤ 0.5 thick, e.g. the taser arc); the model checks refuse others.

### Colour and emissive rules

- **Fit the ability's identity.** Real materials in their real colours: `w_iron` / `w_iron_dark` (forged / blued),
  `w_steel` / `w_steel_edge` (blades), `w_wood` / `w_wood_dark`, `w_wrap` / `w_leather`, `w_brass`, `w_gold`,
  `w_lacquer`, `w_grip`. Need another material: add a ramp to `palette.py` (hue, chroma, lightness — never raw RGB)
  and a `w_*` texture.
- **Emissive only where the ability's element calls for it**: fire / heat (`w_heat`), lightning (`w_arc`,
  `w_spark`), a lamp or flame (`w_amber`), magic (`w_crystal`). `glow=True` renders unshaded at full light, so keep
  it to the one part that *is* that element (the blacksmith's red-hot face, the miner's lantern). No neon edges, LEDs
  or screens on a job that has nothing to do with light; the police baton has no glow at all.
- Animate a glow through texture frames (`.png.mcmeta`: flicker, pulse, crawling heat), never through the server.
- Weapons are not team-tinted.

### Display preset (do not hand-tune per weapon)

`Weapon.finish()` writes the same `display` for every weapon: the vanilla sword pose (`item/handheld.json`) turned
so the upright weapon lies on the sword sprite's diagonal with the hand on the grip (first/third person, both hands),
and GUI / ground / item-frame transforms fitted to the model's bounds. Weapons longer than the reference shrink in
hand so all of them look about 1.1× a vanilla sword; `w.size` scales the hand poses only if a weapon really needs it.

## 장난감 왕국 모델 (공성전 v2: buildings, landmarks, creatures)

Design in **blocks** at real size with `toykit.Build` (feet / base at y = 0, the face towards **-z**), then
`centred(name, scale, centre)` (one model at display scale `scale`, its centre `centre` blocks up: it may use x/z within
±1.5·scale and y within centre ± 1.5·scale) or `slabs(...)` for buildings (12-block slabs at scale 4; keep stepped roof
tiers ending exactly on y = 12, a box split there z-fights with its own chamfer arms). Register the model in `ALL` of
`models/toys.py` / `models/creatures.py` and its display (scale, centre, motion) in `ModelTowerLook.SPECS` /
`CreatureModels` (Java); `ModelTowerLookTest`, `ModelCreatureLookTest` and `ResourcePackContentsTest` fail while the
two disagree. Previews: `--render <dir> --only toys`, look at `scale_lineup.png` next to the player.

Style: chunky, rounded (`rbox` everywhere, chamfer ~1/8 of the size), friendly. Big heads and short legs, dot eyes with a
white glint (`Build.eyes`), rosy cheeks (`Build.cheeks`), at most one or two identity props (a sabre, a pocket watch, a
wind-up key). Materials are painted plastic, felt and wood in the pastel `t_*` ramps; gold and gems are the only shiny
bits; nothing glows except syrup and gems. Faces and decals sit on the flat centre of a face (inside the chamfer) or
they float. Team colour only through tinted `t_*_tint` textures (`tint=True`): uniforms, roofs, bands, pennants, lids.
Damage is cute, never grim: cracks, sticking plasters, knocked-off roof tips, scattered toy blocks.
