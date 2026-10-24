/**
 * Small helpers shared by the built-in abilities, so each ability file only contains what makes it unique.
 *
 * <ul>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop} — owner-scoped repeating tasks with a
 *     run index and an end callback.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat} — ability damage and damage-event
 *     checks.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion} — velocities, knockback that respects
 *     knockback resistance, launch speeds and the server-side ground check.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots} and
 *     {@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body} — safe teleport destinations (fit, hazards,
 *     void, line of sight and the arena border).</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard} — fall damage protection for the
 *     landing after a teleport or launch.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth} — hiding the owner from enemies with
 *     {@code hidePlayer} and showing them again.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx} — sounds, pitch jitter, particle rings and
 *     countdown text.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx} — particle shapes (rings, spheres,
 *     helices, lines, arcs, bursts, shockwaves, trails) and palettes, scaled by the configured effect scale.</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays} — block/item/text display entities
 *     with glow, lifetime, interpolated animation and centered transformations, and per-viewer visibility
 *     ({@code onlyFor}: owner-only overlays, allies-only markers, late joiners included).</li>
 *     <li>{@link io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs} — voluntary input for rules ("don't
 *     move", "don't jump", "don't attack"): held keys, presses since a tick, ping slack and the exemptions for players
 *     under hard crowd control or moved by something else.</li>
 * </ul>
 *
 * <p>Lines another player must read and react to (riddles, questions, rules, warnings) go through the match's prompt
 * channel, {@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext#prompt}, never raw titles or action
 * bars.
 *
 * <p>Everything here runs on the main thread, like the abilities themselves.
 */
package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;
