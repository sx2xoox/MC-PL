#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/sky.fsh + MCPL: the 장난감 왕국 colour grade (include/mcpl_grade.glsl) on the lit colour,
// before the fog; a no-op unless the served shader preset turns it on. Everything else is unchanged.

#include <minecraft:fog.glsl>
#include <minecraft:mcpl_grade.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = apply_fog(mcpl_world_grade(ColorModulator), sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
}
