#version 330 core
// Demo 3D viewport: spinning cube. Real perspective + depth (rendered into an
// FBO), then composited by the 2D pipeline as a normal image quad.
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec3 a_color;

uniform mat4 u_mvp;
uniform mat4 u_model;

out vec3 v_normal;
out vec3 v_color;

void main() {
    gl_Position = u_mvp * vec4(a_pos, 1.0);
    v_normal = mat3(u_model) * a_normal; // model is rotation-only → no inverse-transpose needed
    v_color = a_color;
}
