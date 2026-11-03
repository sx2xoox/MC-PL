#ifndef MCPL_BUBBLE_GLSL
#define MCPL_BUBBLE_GLSL

// MCPL 공성전 v2: the soap-bubble shield film (docs/siege-v2.md §7). The bubble models' texture (tools/assetgen
// t_bubble) has every pixel at alpha 77/255: that alpha is the marker, nothing else in the game uses it. The film is
// drawn from the view angle only (no time uniform: the OIT alpha pass has no Globals), so it shimmers as you move.
// Served only with resource-pack.shaders: true; without the shaders the baked pastel bands of the texture show.

const float MCPL_BUBBLE_ALPHA = 77.0;

bool mcpl_is_bubble(float alpha) {
    return abs(alpha * 255.0 - MCPL_BUBBLE_ALPHA) < 0.6;
}

vec3 mcpl_rainbow(float t) {
    return 0.5 + 0.5 * cos(6.2831853 * (vec3(t) + vec3(0.0, 0.33, 0.67)));
}

// How much of the film shows: thin and clear where you look straight through, a bright rim at grazing angles.
float mcpl_bubble_alpha(vec3 normal, vec3 position) {
    float facing = abs(dot(normalize(normal), normalize(-position)));
    float fresnel = pow(1.0 - facing, 2.0);
    return mix(0.14, 0.62, fresnel);
}

// The film colour: thin-film rainbow bands that slide with the view angle, over the texture's pastel bands, with a
// white rim. `texel` is the texture's own colour (its bands make the pattern wobble across the dome).
vec4 mcpl_bubble(vec3 texel, vec3 normal, vec3 position) {
    float facing = abs(dot(normalize(normal), normalize(-position)));
    float fresnel = pow(1.0 - facing, 2.0);
    float film = facing * 1.7 + dot(texel, vec3(0.6, 0.3, 0.1));
    vec3 color = mix(vec3(0.94, 0.96, 1.0), mcpl_rainbow(film), 0.5);
    color += vec3(pow(fresnel, 3.0) * 0.55);
    return vec4(min(color, vec3(1.0)), mcpl_bubble_alpha(normal, position));
}

#endif
