# MC-PL — classic Minecraft mini-games, remastered

One Paper plugin (`MCPL`) that hosts several classic Korean Minecraft streamer games (초능력전쟁, 증강 야생전,
랜덤무기전쟁, 살인자를 찾아라, ...) behind one command hub, so a server can rotate between modes without swapping plugins.
Target users: streamers and ordinary friend groups. Player-facing text is Korean (MiniMessage).

## Build

- Java 25, Paper API 26.3 (`compileOnly`), Gradle wrapper (`./gradlew build`). Tests: JUnit 6 + MockBukkit v26.3.
- `./gradlew runServer` starts a local Paper 26.3 test server (needs internet access to PaperMC).
- Maps: `./gradlew renderMaps [-Pmap=<id>] [-Pzone=<zone>]` writes PNG previews to `build/map-previews/`; `CitadelMapTest` audits
  walkability (every standable place reachable, no stranded pits) and blocks (no floating sand, spilling water, lava);
  `MapBlockChecks` audits block support (lanterns, torches, ladders, signs, plants, doors, chains, whole trees) and
  `SiegeMapTest` asserts it is clean (the citadel's findings are only printed).
  Bump `ArenaMap#version` whenever a map's blocks change, so servers rebuild it.
- `docs/abilities.md` is generated from every `AbilityInfo`; after changing ability names, descriptions or params run
  `./gradlew updateAbilityDocs` (`AbilityDocsTest` fails while it is stale). New abilities need a docs section there.
- `docs/augments.md` is generated the same way from every `AugmentInfo` and `BonusCondition`: run
  `./gradlew updateAugmentDocs` after changing them (`AugmentDocsTest` fails while it is stale).
- The 공성전 basic attack (평타) table in `docs/siege-v2.md` §8 is generated from `BasicAttacks`: run
  `./gradlew updateBasicAttackDocs` after changing them (`BasicAttackDocsTest` fails while it is stale).
- 공성전 creature attack motions (`CreatureModels.Attack` keyframes) are exported as the game's poses to
  `tools/assetgen/models/creature_attacks.json`: run `./gradlew updateCreatureAttacks` after changing them
  (`CreatureAttacksExportTest` fails while it is stale); `tools/assetgen/attack_preview.py <dir>` draws GIFs from it.
- Resource pack: `./gradlew resourcePack` (part of `build`) converts `src/main/resourcepack-src` into
  `build/resourcepack/MCPL-pack.zip` (bundled into the jar; 26.3 `pack.mcmeta` uses `min_format`/`max_format` 97, no
  `pack_format`). Models are code in `tools/assetgen/models/*.py`: after changing one run
  `python3 -I tools/assetgen/build.py` (`--render <dir>` for previews) and commit the `.bbmodel`;
  `ResourcePackContentsTest` fails while the bundled pack is stale or a reference is missing. Show pack models only to
  `PackAudience#hasPack` players with a vanilla fallback for the rest. Item components are seen by everyone, so never
  put an `mcpl:` `item_model` on an item another player may see; keep the vanilla model and let the pack pick the 3D
  one by `custom_model_data` (PackBuilder overrides the vanilla item definition with a `minecraft:select`, fallback =
  vanilla). Ability weapons (`mcpl:item/weapon/<ability id>`, selected on the iron/diamond/netherite sword by
  `custom_model_data` string 0 = ability id): `tools/assetgen/models/weapons.py` + `weaponkit.py`, listed in
  `AbilityWeaponModels.MODELED`; how-to and style guide in `tools/assetgen/README.md`.
- Pack shaders (`src/main/resourcepack-src/assets/minecraft/shaders`, docs/siege-v2.md §7.2) are 26.3 vanilla core shaders
  + one MCPL line/include; they are served as a shader preset (`core/pack/ShaderPreset`: name → features → files,
  `resource-pack.shaders`, `/게임 셰이더`), each feature behind its macro in the generated `include/mcpl_preset.glsl`.
  Never override text/GUI shaders. After changing one run `node tools/shaders/check.mjs <26.3 client shaders dir>`
  (WebGL2 compile of every variant); the colour grade's numbers live in `include/mcpl_grade.glsl` and
  `tools/assetgen/grade_preview.py` alike (`ResourcePackContentsTest` compares them).
- Sandboxed cloud sessions cannot reach `repo.papermc.io`; a locally built Paper API is published to `~/.m2` and
  `~/.gradle/gradle.properties` + `~/.gradle/init.d/` redirect the build to it. Do not commit those overrides.
- Paper API sources for reference (when available): `/home/user/papermc/paper/paper-api/src/main/java`. Check a
  method exists there before using it — the API changed a lot in 26.x (e.g. `GameRules`, `DamageSource`,
  data components, `Mannequin`). Avoid deprecated API when a replacement exists.

## Layout

```
src/main/java/io/github/sx2xoox/mcpl/
  MCPLPlugin.java              entry point, wires core + games
  core/                        game hub shared by all modes (sessions, rotation, snapshots, arena, UI, commands)
    map/                       battle maps built in code into void worlds (MapCanvas/BlockSpec, MapService, /게임 맵)
      citadel/                 폐허의 성채: CitadelLayout (the plan) + one MapZone class per zone
      siege/                   네온 시티 (공성전 map, cyberpunk): SiegeSites (the contract the siege game reads), SiegeLayout
                               (the plan), SiegeKit/SiegeStreets (buildings, props), SiegeMap + one MapZone per part
                               (slums, firewall, hub, hq); SiegeMapTest (+ MapBlockChecks: block support audits)
      lanes/                   LaneSites: the contract of a two-lane 공성전 v2 map (spawns, shop, towers, core, lane paths,
                               objectives, dragon, regions; docs/siege-v2.md)
      toykingdom/              장난감 왕국 (공성전 v2 map, pastel, mirror symmetric): ToyLayout (the plan + geometry),
                               ToyKit (ground, props), ToyKingdomMap (ArenaMap + LaneSites) + one MapZone per part
                               (border, castles, lanes, jungle, river, glass dome); ToyKingdomMapTest (sites, paths, drops,
                               symmetry, palette); previews leave the dome out (toykingdom-dome-iso.png shows it)
    world/                     throw-away match worlds (TempWorlds: create/dispose/leftovers, WorldProvider, ChunkPregen)
    pack/                      MCPL resource pack: ResourcePackService (resource-pack.*, built-in HTTP host, per-player
                               PackAudience status), PackBuilder + BBModelConverter (build time), ModelRef (item_model),
                               ShaderPreset (shader presets; PackBuilder#withShaders builds each preset's pack variant)
  game/siege/                  초능력 공성전 v2 (SiegeGame = the MiniGame, SiegeSettings = games.siege.*, SiegeMatch = flow,
                               score, structures, respawns + the hooks features use; abilities run in a hosted
                               SuperpowerMatch = runtime/MatchHost; map via core/map/lanes/LaneSites → LaneLayout,
                               FallbackLaneSites in tests; features plug in as SiegeModule (SiegeGame#addModule; the lane
                               systems via the built-in LaneSystemsModule = arena/SiegeArena impl); reusable
                               readability parts: OverheadBar, DamageNumbers, SiegeSounds, Guidance; gold = SiegeEconomy)
    balance/                   ability balance in 공성전 only: SiegeBalance(+Settings) = global factor × per-ability correction
                               (generated siege-balance.yml) × level, SiegePerks = level 3/6/9 param perks (capped),
                               SiegeModifiers = per-player skill damage/taken/cooldown for shop items and buffs;
                               `./gradlew updateSiegeBalance` regenerates docs/siege-balance.md + siege-balance.yml
                               (SiegeBalanceReport fails while stale)
    stats/                     balance records of real matches (docs/siege-v2.md §10): SiegeStats = the SiegeModule (round ×
                               player × ability; nothing shown in play), SiegeStatsStore (stats/siege/*.csv + totals.yml),
                               SiegeStatsCommand (/게임 공성전 통계, admins only), games.siege.stats.*
    tower/                     Tower (rules + Mannequin hitbox) and looks (TowerLook contract; ModelTowerLook = 3D models +
                               per-viewer vanilla fallback; v2 toy kingdom: look ids tower / core)
    bot/                       공성전 봇전 (docs/siege-v2.md §9): bot-only jobs (BotJob, SiegeBotSettings = games.siege.bots.*),
                               pure SiegeBotBrain + LanePath, SiegeBots = the SiegeModule (bots enlisted with
                               GameSession#addBot; Mannequin body + hidden legs + plate; rules, kill credit, respawns);
                               /게임 시작 공성전 봇전 [팀당 인원] [난이도]
    arena/ minion/ jungle/     v2 lane systems (docs/siege-v2.md §2–§4) behind SiegeArena (what they need from the rules);
    economy/ shop/ creature/   SiegeSystems = facade the match drives (+ MatchHost hooks), LaneModifiers = shared additive
                               per-player modifiers; creature/CreatureLook spawns the vanilla body (VanillaCreatureLook),
                               CreatureSkin dresses it (ModelCreatureLook: pack players see the toy model following the mob
                               + an Interaction hitbox; landmarks), SkinnedCreatureLook = both; CreatureModels (kinds,
                               parts, motions; tools/assetgen/models/creatures.py); tests on lanes/TestLaneSites
  game/superpower/             초능력전쟁 (SuperpowerGame = the MiniGame, SuperpowerSettings = games.superpower.*)
    ability/                   ability API contract (Ability, AbilityContext, AbilityInfo, AbilityParams, ...)
    basic/                     basic attacks (평타) of the ability weapon: BasicAttack (shape + numbers + effect + look),
                               BasicAttacks (the 50 pool defaults), BasicAttackSettings; run by runtime/BasicAttackRunner
                               when a MatchHost turns them on (공성전)
    abilities/                 one class per ability + BuiltinAbilities registration
    config/                    abilities.yml (enabled flags + params, generated from AbilityInfo)
    runtime/                   the match: phases, AbilityContext impl, listener, assignment, gauge, cooldown, HUD
    loot/                      loot chests, supply drops, superpower consumables (games.superpower.loot.*)
    ui/ menu/ command/         ability item/texts/reveal, CHOICE menu, /게임 능력 commands
  game/augment/                증강 야생전 (AugmentGame = the MiniGame + its fresh world per match, AugmentSettings = games.augment.*)
    api/ augments/ bonus/      augment contract (Augment, AugmentContext, AugmentInfo, Params), one class per augment, bonus conditions
    config/ runtime/ command/  augments.yml, the match (phases, offers/card menu, bonus tracker, HUD), /게임 증강
    bot/                       봇전 bots: pure brain (utility AI), gear schedule, base planner, ballistics, aim/turn math +
                               Bukkit body/controller/manager (chunk tickets, border damage); 봇 관전전 = AugmentSpectateGame
                               (MiniGame#spectatorsOnly) + runtime/SpectatorView; 봇전 팀전 = pure TeamTactics +
                               BotTeams (shared sightings, focus, peel, leader, shared base)
  util/                        small shared helpers (Text = MiniMessage)
src/main/resourcepack-src/     pack sources: models/*.bbmodel (from tools/assetgen) + assets/ (copied as-is)
tools/assetgen/                Python model/texture generator (STSV palette + checks), render/ = preview renderer
docs/                          game design docs (Korean), the source of truth for game rules; abilities.md and augments.md
                               are generated
```

## Conventions

- Code, identifiers and comments in English; player-facing strings in Korean MiniMessage.
- Everything runs on the main thread; no async Bukkit access.
- Never leave the world changed: a match must restore players (snapshot) and every block/entity it created.
  Use temporary blocks and tracked entities; never break or place real blocks permanently. A mode that needs a world it
  may change freely creates its own through `MiniGame#createArena` + `TempWorlds` (deleted in `releaseArena`).
- Abilities only harm entities for which `AbilityContext#isEnemy` is true and only through the context helpers
  (`damage`, `enemiesNear`, `findTarget`, ...) so team mode, spectators and kill credit work.
- Every balance number lives in `AbilityInfo` params (seconds, blocks, health points), never hard-coded.
- Abilities break terrain only through `AbilityContext#breakBlocks` / `breakBlocksAlong` (temporary removal via
  `TempBlockService`, map worlds only by default, restored at match end); never set blocks directly.
- Lines another player must read and answer (riddles, questions, rules) go through `AbilityContext#prompt`, never raw
  titles/action bars (the window starts when shown; never punish an `UNSEEN`/`INTERRUPTED` prompt); rules on what a
  player does judge voluntary input via kit `Inputs` from the prompt's current showing (`Inputs.judgeFrom(ctx, handle,
  grace)`; ping slack, exemptions), never position or velocity. Per-viewer displays are filtered inside the spawn
  (kit `Displays` overloads with `viewers`).
- Prefer modern API: data components (`DataComponentTypes`), `Attribute` modifiers keyed by `NamespacedKey`,
  display entities for visuals, `DamageSource.builder`, Adventure components/titles/boss bars.
