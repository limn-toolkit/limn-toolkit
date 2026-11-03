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
// A path clip and a blend rule, as canvas.frag reads them (see there): the picture is the source.
uniform int u_maskOn;
uniform sampler2D u_mask;
uniform vec4 u_maskRect;
uniform int u_rule;
uniform sampler2D u_dst;

in vec2 v_uv;

out vec4 o_color;

float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r, r);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

// canvas.frag's maskAt, line for line.
float maskAt(vec2 fragDev) {
    vec2 p = floor(vec2(fragDev.x - u_maskRect.x, u_maskRect.y + u_maskRect.w - fragDev.y));
    if (p.x < 0.0 || p.y < 0.0 || p.x >= u_maskRect.z || p.y >= u_maskRect.w) {
        return 0.0;
    }
    return texelFetch(u_mask, ivec2(p), 0).a;
}

// canvas.frag's ruled, line for line.
vec4 ruled(vec4 src, float cover) {
    vec4 dst = texelFetch(u_dst, ivec2(gl_FragCoord.xy), 0);
    vec4 r;
    if (u_rule == 1) {
        r = vec4(0.0);
    } else if (u_rule == 2) {
        r = src;
    } else if (u_rule == 3) {
        r = src * dst.a;
    } else if (u_rule == 4) {
        r = dst * src.a;
    } else if (u_rule == 5) {
        r = src * (1.0 - dst.a);
    } else if (u_rule == 6) {
        r = dst * src.a + src * (1.0 - dst.a);
    } else {
        vec3 s = src.rgb * dst.a;
        vec3 d = dst.rgb * src.a;
        vec3 b = (u_rule == 7 || u_rule == 9) ? min(s, d) : max(s, d);
        vec3 c = b + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a);
        r = vec4(c, u_rule >= 9 ? dst.a : src.a + dst.a * (1.0 - src.a));
    }
    return mix(dst, r, cover);
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
    if (u_maskOn != 0) {
        coverage *= maskAt(fragDev);
    }
    if (u_rule != 0) {
        o_color = ruled(texture(u_picture, v_uv) * u_alpha, coverage);
        return;
    }
    o_color = texture(u_picture, v_uv) * (u_alpha * coverage);
}
