#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
// The target is linear premultiplied (ADR 004): decode the authored-sRGB color
// and premultiply; an unlit surface is not exempt from the color space.
uniform vec4 u_color;
out vec4 o_color;

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

void main() {
    o_color = vec4(srgbToLinear(u_color.rgb) * u_color.a, u_color.a);
}
