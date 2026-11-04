#ifndef MCPL_GRADE_GLSL
#define MCPL_GRADE_GLSL

// MCPL 장난감 왕국 colour grade (docs/siege-v2.md §7.2): softer pastel saturation, slightly lifted lilac shadows and
// warm cream highlights, applied to the lit colour of the world (terrain, block entities, entities, items, particles,
// sky, clouds) before the fog. On while the served shader preset defines MCPL_COLOR_GRADE (include/mcpl_preset.glsl,
// written by the plugin: resource-pack.shaders, /게임 셰이더); the GUI, its items and every text stay untouched.
// tools/assetgen/grade_preview.py applies the same formula to map renders for before/after previews: keep them equal.

#include <minecraft:fog.glsl>
#include <minecraft:mcpl_preset.glsl>

const float MCPL_GRADE_SATURATION = 0.9;    // < 1: softer colours
const float MCPL_GRADE_LIFT = 0.055;        // lifts the shadows (most at black)
const float MCPL_GRADE_BRIGHTEN = 0.08;     // lightens the mid tones (pastel)
const vec3 MCPL_GRADE_SHADOW_TINT = vec3(0.012, 0.004, 0.024);   // a hint of lilac in the dark
const vec3 MCPL_GRADE_HIGHLIGHT = vec3(1.03, 1.012, 0.975);      // warm cream highlights

vec3 mcpl_grade(vec3 original) {
    vec3 c = max(original, vec3(0.0));
    float luma = dot(min(c, vec3(1.0)), vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, MCPL_GRADE_SATURATION);
    vec3 dark = vec3(1.0) - min(c, vec3(1.0));
    c += MCPL_GRADE_LIFT * dark * dark;
    c += MCPL_GRADE_BRIGHTEN * min(c, vec3(1.0)) * dark;
    c += MCPL_GRADE_SHADOW_TINT * (1.0 - smoothstep(0.0, 0.5, luma));
    c *= mix(vec3(1.0), MCPL_GRADE_HIGHLIGHT, smoothstep(0.45, 1.0, luma));
    return min(c, max(vec3(1.0), original));
}

// The world is drawn with the level's fog; the GUI (inventory items, the player doll, the HUD) with the empty fog,
// whose distances are all Float.MAX_VALUE (26.3 FogRenderer). In the world the render distance end is always finite.
bool mcpl_in_world() {
    return FogRenderDistanceEnd < 1.0e30;
}

// `color` has straight (not premultiplied) alpha: call it before sampleColorForAccumulation and apply_fog.
vec4 mcpl_world_grade(vec4 color) {
    #ifdef MCPL_COLOR_GRADE
    if (mcpl_in_world()) {
        color.rgb = mcpl_grade(color.rgb);
    }
    #endif
    return color;
}

#endif
