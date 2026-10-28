# MC-PL — classic Minecraft mini-games, remastered

One Paper plugin (`MCPL`) that hosts several classic Korean Minecraft streamer games (초능력전쟁, 증강 야생전,
랜덤무기전쟁, 살인자를 찾아라, ...) behind one command hub, so a server can rotate between modes without swapping plugins.
Target users: streamers and ordinary friend groups. Player-facing text is Korean (MiniMessage).

## Build

- Java 25, Paper API 26.3 (`compileOnly`), Gradle wrapper (`./gradlew build`). Tests: JUnit 6 + MockBukkit v26.3.
- `./gradlew runServer` starts a local Paper 26.3 test server (needs internet access to PaperMC).
- Maps: `./gradlew renderMaps [-Pzone=<zone>]` writes PNG previews to `build/map-previews/`; `CitadelMapTest` audits
  walkability (every standable place reachable, no stranded pits) and blocks (no floating sand, spilling water, lava).
  Bump `ArenaMap#version` whenever a map's blocks change, so servers rebuild it.
- `docs/abilities.md` is generated from every `AbilityInfo`; after changing ability names, descriptions or params run
  `./gradlew updateAbilityDocs` (`AbilityDocsTest` fails while it is stale). New abilities need a docs section there.
- `docs/augments.md` is generated the same way from every `AugmentInfo` and `BonusCondition`: run
  `./gradlew updateAugmentDocs` after changing them (`AugmentDocsTest` fails while it is stale).
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
    map/                       battle maps built in code into void worlds (MapCanvas/BlockSpec, MapService, /게임 경기장 맵)
      citadel/                 폐허의 성채: CitadelLayout (the plan) + one MapZone class per zone
    world/                     throw-away match worlds (TempWorlds: create/dispose/leftovers, WorldProvider, ChunkPregen)
  game/superpower/             초능력전쟁 (SuperpowerGame = the MiniGame, SuperpowerSettings = games.superpower.*)
    ability/                   ability API contract (Ability, AbilityContext, AbilityInfo, AbilityParams, ...)
    abilities/                 one class per ability + BuiltinAbilities registration
    config/                    abilities.yml (enabled flags + params, generated from AbilityInfo)
    runtime/                   the match: phases, AbilityContext impl, listener, assignment, gauge, cooldown, HUD
    loot/                      loot chests, supply drops, superpower consumables (games.superpower.loot.*)
    ui/ menu/ command/         ability item/texts/reveal, CHOICE menu, /게임 능력 commands
  game/augment/                증강 야생전 (AugmentGame = the MiniGame + its fresh world per match, AugmentSettings = games.augment.*)
    api/ augments/ bonus/      augment contract (Augment, AugmentContext, AugmentInfo, Params), one class per augment, bonus conditions
    config/ runtime/ command/  augments.yml, the match (phases, offers/card menu, bonus tracker, HUD), /게임 증강
    bot/                       봇전 bots: pure brain (utility AI), gear schedule, base planner, ballistics + Bukkit body/controller/manager
  util/                        small shared helpers (Text = MiniMessage)
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
