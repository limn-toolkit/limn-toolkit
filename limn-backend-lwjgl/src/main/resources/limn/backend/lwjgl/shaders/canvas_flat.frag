#version 330 core
// Limn flat-fill fragment shader: the colour, premultiplied as canvas.frag premultiplies a solid
// paint, and nothing else. Every pixel this program covers lies wholly inside the shape and its
// clip, where canvas.frag's coverage is one, so the distance fields, the derivatives and the clip
// test it would run there decide nothing; on a renderer that runs fragment shaders on the CPU
// (macOS's software fallback, a VM with no GPU) they were the whole cost of a full-window scrim.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

flat in vec4 v_color;

out vec4 o_color;

void main() {
    o_color = vec4(v_color.rgb * v_color.a, v_color.a);
}
