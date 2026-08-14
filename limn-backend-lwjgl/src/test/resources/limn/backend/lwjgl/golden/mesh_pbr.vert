#version 330 core
// GENERATED from the neutral shader IR by GlslCodegen. Do not edit by hand.
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
// A mesh without COLOR leaves this attribute array disabled and reads the
// context's current value, which the backend sets to opaque white, so the
// per-vertex tint is an identity rather than a black multiply.
layout(location = 3) in vec4 a_color;
// Absent from most meshes; the backend sets their disabled-array defaults to
// zero, which is why a surface should make zero its identity.
layout(location = 4) in vec2 a_uv1;
layout(location = 5) in vec4 a_params;
layout(location = 6) in vec4 a_params1;

layout(std140) uniform Frame {
    mat4 u_viewProj;
    vec4 u_cameraPos;
};

uniform mat4 u_model;
uniform mat3 u_normalMatrix;

out vec3 v_worldPos;
out vec3 v_normal;
out vec2 v_uv;
out vec4 v_color;
out vec2 v_uv1;
out vec4 v_params;
out vec4 v_params1;

void main() {
    vec4 world = u_model * vec4(a_pos, 1.0);
    v_worldPos = world.xyz;
    v_normal = u_normalMatrix * a_normal;
    v_uv = a_uv;
    v_color = a_color;
    v_uv1 = a_uv1;
    v_params = a_params;
    v_params1 = a_params1;
    gl_Position = u_viewProj * world;
}
