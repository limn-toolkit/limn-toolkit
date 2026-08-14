#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
// Fullscreen triangle driven by gl_VertexID alone, with no vertex buffer, no
// attributes and no varyings: the fragment shader addresses plane texels from
// gl_FragCoord, so there is nothing to interpolate and nothing that could be
// interpolated slightly differently on another driver.
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
