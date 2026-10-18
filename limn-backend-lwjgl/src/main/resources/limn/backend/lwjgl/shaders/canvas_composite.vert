#version 330 core
// Limn layer composite vertex shader: one quad laying a layer's picture where it is drawn (see
// GlBatch.composite). For a layer drawn on its target's own pixels the quad is where the picture
// was drawn, w = 1; for one drawn in a space of its own (a rotated or skewed subtree) it is the
// picture's four corners through the layer's transform, in homogeneous coordinates, so that the
// rasterizer interpolates the picture perspective-correctly and cuts what lies behind the eye
// (w < 0) by itself. Position and texture coordinates only.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

layout(location = 0) in vec4 a_pos; // (x, y, 0, 1) times w: device px of the target's space, y-down
layout(location = 1) in vec2 a_uv;  // the picture's texture coordinates

uniform vec2 u_viewport; // the target's size in device px: the window, or a layer
uniform vec2 u_origin;   // the target's top-left in its space's device px; see canvas.vert

out vec2 v_uv;

void main() {
    // ((x / w - origin) / viewport * 2 - 1) * w, written so that w stays the clip coordinate's.
    vec2 ndc = (a_pos.xy - u_origin * a_pos.w) / u_viewport * 2.0 - a_pos.w;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, a_pos.w);
    v_uv = a_uv;
}
