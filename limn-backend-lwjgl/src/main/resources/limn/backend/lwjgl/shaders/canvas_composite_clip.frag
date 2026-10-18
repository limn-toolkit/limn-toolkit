#version 330 core
// Limn layer composite fragment shader under a clip a scissor cannot make: canvas_composite.frag's
// texel times the opacity, times the clip's coverage at the fragment, computed as canvas.frag
// computes it. A picture drawn in a space of its own (a rotated subtree) was drawn under its own
// clip and not the enclosing one, so the enclosing clip is applied here. A rectangle on whole
// pixels needs only the scissor, which costs nothing per pixel; this program runs only while a
// rounded clip is in force or a rectangle's edge falls between pixels, because a clip computed over
// every pixel of a picture is what a renderer without a GPU pays most for.
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only.

uniform sampler2D u_picture;
uniform float u_alpha;
uniform vec2 u_viewport;   // the target's size in device px
uniform vec2 u_origin;     // the target's top-left in its space's device px, top-down
uniform vec4 u_clip;       // the clip rectangle: every clip's device bounds intersected
uniform vec4 u_roundClip;  // the rounded clip's own device rectangle
uniform float u_clipRadius;

in vec2 v_uv;

out vec4 o_color;

float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r, r);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    // The fragment in its space's device pixels, top-down: gl_FragCoord counts up from the
    // target's bottom-left corner, which lies u_viewport.y below its top.
    vec2 fragDev = vec2(gl_FragCoord.x + u_origin.x, u_origin.y + u_viewport.y - gl_FragCoord.y);
    vec2 clipCenter = (u_clip.xy + u_clip.zw) * 0.5;
    vec2 clipHalf = (u_clip.zw - u_clip.xy) * 0.5;
    vec2 roundCenter = (u_roundClip.xy + u_roundClip.zw) * 0.5;
    vec2 roundHalf = (u_roundClip.zw - u_roundClip.xy) * 0.5;
    float roundR = min(u_clipRadius, min(roundHalf.x, roundHalf.y));
    // The rectangle and the rounded rectangle each from its own geometry, as canvas.frag keeps
    // them, so that a pass's rectangle cuts the rounded clip straight.
    float dClip = max(sdRoundRect(fragDev - clipCenter, clipHalf, 0.0),
                      sdRoundRect(fragDev - roundCenter, roundHalf, roundR));
    float coverage = 1.0 - smoothstep(-0.5, 0.5, dClip);
    o_color = texture(u_picture, v_uv) * (u_alpha * coverage);
}
