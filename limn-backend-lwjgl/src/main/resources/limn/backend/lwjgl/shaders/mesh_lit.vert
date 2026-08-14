#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 subset that maps 1:1 to GLSL ES 3.00.
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;

uniform mat4 u_mvp;
uniform mat3 u_normalMatrix;

out vec3 v_normal;

void main() {
    gl_Position = u_mvp * vec4(a_pos, 1.0);
    v_normal = u_normalMatrix * a_normal;
}
