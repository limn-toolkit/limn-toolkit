#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 subset that maps 1:1 to GLSL ES 3.00;
// the ES port only swaps the #version line and adds precision qualifiers.
layout(location = 0) in vec3 a_pos;

uniform mat4 u_mvp;

void main() {
    gl_Position = u_mvp * vec4(a_pos, 1.0);
}
