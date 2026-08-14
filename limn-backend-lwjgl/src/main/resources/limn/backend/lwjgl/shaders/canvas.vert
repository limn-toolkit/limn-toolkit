#version 330 core
// Limn canvas vertex shader.
// PORTABILITY RULE (ADR 001): restricted to the GLSL 330 subset that maps 1:1
// to GLSL ES 3.00; the ES port only swaps the #version line and adds
// precision qualifiers. No desktop-only builtins or qualifiers.

layout(location = 0) in vec2 a_pos;      // device px, y-down, origin top-left
layout(location = 1) in vec2 a_local;    // shape-local units (user space)
layout(location = 2) in vec2 a_halfSize; // shape half extents (user space)
layout(location = 3) in vec4 a_radii;    // corner radii: tl, tr, br, bl
layout(location = 4) in vec4 a_misc;     // strokeHalfWidth(-1=fill), kind, paintType, clipRadius(device px)
layout(location = 5) in vec4 a_colorA;   // straight alpha
layout(location = 6) in vec4 a_colorB;
layout(location = 7) in vec4 a_grad;     // linear: p0,p1 (local); radial: center,radius,unused
layout(location = 8) in vec4 a_clip;     // device px: x0,y0,x1,y1
layout(location = 9) in vec2 a_uv;       // glyph atlas texels (normalized)

uniform vec2 u_viewport;                 // framebuffer size in device px

out vec2 v_local;
out vec2 v_halfSize;
out vec4 v_radii;
out vec4 v_misc;
out vec4 v_colorA;
out vec4 v_colorB;
out vec4 v_grad;
out vec4 v_clip;
out vec2 v_uv;

void main() {
    vec2 ndc = a_pos / u_viewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
    v_local = a_local;
    v_halfSize = a_halfSize;
    v_radii = a_radii;
    v_misc = a_misc;
    v_colorA = a_colorA;
    v_colorB = a_colorB;
    v_grad = a_grad;
    v_clip = a_clip;
    v_uv = a_uv;
}
