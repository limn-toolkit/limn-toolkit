#version 330 core
// Limn layer composite vertex shader: one quad laying a layer's picture back where it was drawn
// (see GlBatch.composite). Position and texture coordinates only.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

layout(location = 0) in vec2 a_pos; // device px of the window, y-down, origin top-left
layout(location = 1) in vec2 a_uv;  // the picture's texture coordinates

uniform vec2 u_viewport; // the target's size in device px: the window, or a layer
uniform vec2 u_origin;   // the target's top-left in the window's device px; see canvas.vert

out vec2 v_uv;

void main() {
    vec2 ndc = (a_pos - u_origin) / u_viewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
    v_uv = a_uv;
}
