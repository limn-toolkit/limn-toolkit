#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
// The target is linear premultiplied (ADR 004): the authored-sRGB base color is
// decoded, lambert runs in linear (the light color is linear already, like the
// PBR pass's), and the result is premultiplied.
in vec3 v_normal;

uniform vec4 u_color;
uniform vec3 u_lightDir;    // direction toward the light
uniform vec3 u_lightColor;
uniform float u_ambient;

out vec4 o_color;

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

void main() {
    vec3 n = normalize(v_normal);
    float diffuse = max(dot(n, normalize(u_lightDir)), 0.0);
    vec3 base = srgbToLinear(u_color.rgb);
    vec3 lit = base * u_ambient + base * diffuse * u_lightColor;
    o_color = vec4(lit * u_color.a, u_color.a);
}
