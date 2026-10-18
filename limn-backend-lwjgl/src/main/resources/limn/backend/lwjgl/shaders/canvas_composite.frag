#version 330 core
// Limn layer composite fragment shader: the picture's premultiplied texel times one opacity, and
// nothing else. The picture was drawn under its clip already, so there is no clip here; and on a
// renderer that runs fragment shaders on the CPU (macOS's software fallback, a VM with no GPU) the
// canvas program's clip and branches over every pixel of a layer were most of a fade frame's cost:
// measured at 2560 x 1600 on Apple Software Renderer, a window-sized layer composited by the canvas
// program added 251 ms to a 194 ms frame, where this costs what the probe's composite did.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

uniform sampler2D u_picture;
uniform float u_alpha;

in vec2 v_uv;

out vec4 o_color;

void main() {
    o_color = texture(u_picture, v_uv) * u_alpha;
}
