#version 330 core
// Limn flat-fill vertex shader: large solid rectangles whose edges lie on whole device pixels,
// emitted already cut to the clip (see GlCanvas.emitFlatRect). Position and colour only.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

layout(location = 0) in vec2 a_pos;   // device px, y-down, origin top-left
layout(location = 1) in vec4 a_color; // straight alpha, opacity already applied

uniform vec2 u_viewport;

flat out vec4 v_color;

void main() {
    vec2 ndc = a_pos / u_viewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
    v_color = a_color;
}
