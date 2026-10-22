# MC-PL — classic Minecraft mini-games, remastered

One Paper plugin (`MCPL`) that hosts several classic Korean Minecraft streamer games (초능력전쟁, 랜덤무기전쟁,
살인자를 찾아라, ...) behind one command hub, so a server can rotate between modes without swapping plugins.
Target users: streamers and ordinary friend groups. Player-facing text is Korean (MiniMessage).

## Build

- Java 25, Paper API 26.3 (`compileOnly`), Gradle wrapper (`./gradlew build`). Tests: JUnit 6 + MockBukkit v26.3.
- `./gradlew runServer` starts a local Paper 26.3 test server (needs internet access to PaperMC).
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
  game/superpower/             초능력전쟁
    ability/                   ability API contract (Ability, AbilityContext, AbilityInfo, AbilityParams, ...)
    abilities/                 one class per ability + BuiltinAbilities registration
  util/                        small shared helpers (Text = MiniMessage)
docs/                          game design docs (Korean), the source of truth for game rules
```

## Conventions

- Code, identifiers and comments in English; player-facing strings in Korean MiniMessage.
- Everything runs on the main thread; no async Bukkit access.
- Never leave the world changed: a match must restore players (snapshot) and every block/entity it created.
  Use temporary blocks and tracked entities; never break or place real blocks permanently.
- Abilities only harm entities for which `AbilityContext#isEnemy` is true and only through the context helpers
  (`damage`, `enemiesNear`, `findTarget`, ...) so team mode, spectators and kill credit work.
- Every balance number lives in `AbilityInfo` params (seconds, blocks, health points), never hard-coded.
- Prefer modern API: data components (`DataComponentTypes`), `Attribute` modifiers keyed by `NamespacedKey`,
  display entities for visuals, `DamageSource.builder`, Adventure components/titles/boss bars.
