#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/clouds.fsh + MCPL: the 장난감 왕국 colour grade (include/mcpl_grade.glsl) on the lit colour,
// before the fog; a no-op unless the served shader preset turns it on. Everything else is unchanged.

#include <minecraft:fog.glsl>
#include <minecraft:mcpl_grade.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    color = mcpl_world_grade(color);
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

void main() {
    vec4 color = vertexColor;
    #ifndef OIT_DEPTH_BOUNDS
    color.a *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
