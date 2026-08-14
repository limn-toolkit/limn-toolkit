#version 330 core
// The target is linear premultiplied (ADR 004): the authored-sRGB face colors
// decode before shading, so the demo cube is lit in linear like everything else.
in vec3 v_normal;
in vec3 v_color;
out vec4 o_color;

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

void main() {
    vec3 n = normalize(v_normal);
    vec3 l = normalize(vec3(0.4, 0.85, 0.55));
    float diffuse = max(dot(n, l), 0.0);
    float shade = 0.35 + 0.65 * diffuse; // ambient + lambert
    o_color = vec4(srgbToLinear(v_color) * shade, 1.0);
}
