#version 330 core
// Limn canvas fragment shader: analytic antialiasing via signed distance
// fields, screen-space corrected with fwidth() so edges are exactly ~1 device
// pixel wide at ANY content scale (1.0, 1.25, 1.5, 2.0…). Output is
// premultiplied alpha (blend: ONE, ONE_MINUS_SRC_ALPHA).
// PORTABILITY RULE (ADR 001): GLSL ES 3.00-compatible subset only (fwidth is
// core in ES 3.00; the port swaps #version and adds precision qualifiers).

in vec2 v_local;
in vec2 v_halfSize;
in vec4 v_radii;
in vec4 v_misc;    // x: strokeHalfWidth (-1 = fill), y: kind, z: paintType, w: clipRadius
in vec4 v_colorA;
in vec4 v_colorB;
in vec4 v_grad;
in vec4 v_clip;
in vec2 v_uv;

uniform vec2 u_viewport;
uniform sampler2D u_atlas; // bound texture for this draw: R8 glyph atlas
                           // (.r = coverage) or RGBA8 image; LINEAR filtered.
uniform float u_exposure;  // HDR-surface quads only (kind 7): display exposure

out vec4 o_color;

// kind: 0 = rounded rect (covers rect/circle/capsule), 1 = ellipse,
//       2 = plain triangle (path fill interior),
//       3 = fringe (path-fill AA skirt: coverage ramps via v_radii.x),
//       4 = glyph (coverage from the R8 atlas texture, tinted by v_colorA),
//       5 = image (RGBA texture, tinted/modulated by v_colorA),
//       6 = image mask (RGBA texture; alpha = coverage, colored by v_colorA:
//           the icon recolor path, works for a mask of any source color),
//       7 = HDR surface (3D render target: premultiplied LINEAR scene-referred
//           texel; the display transform runs here, once, at composite time)
//       8 = backdrop effect (u_atlas is a COPY of the framebuffer under this
//           shape, taken before this quad was queued; the slots are repurposed:
//           v_misc.x = device pixels per local unit (there is no stroke here),
//           v_colorB = (variant, param1, param2, opacity),
//           v_grad = the copied region in texture coords, to clamp against)
// paintType: 0 = solid, 1 = linear gradient, 2 = radial gradient

float sdRoundRect(vec2 p, vec2 halfSize, vec4 radii) {
    // Per-quadrant radius; y grows down, so y < 0 is the top half.
    float r = (p.x < 0.0) ? ((p.y < 0.0) ? radii.x : radii.w)
                          : ((p.y < 0.0) ? radii.y : radii.z);
    vec2 q = abs(p) - halfSize + vec2(r, r);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float sdEllipse(vec2 p, vec2 ab) {
    // Scaled-circle approximation with first-order gradient correction.
    float k1 = length(p / ab);
    if (k1 < 1e-6) {
        return -min(ab.x, ab.y);
    }
    float k2 = length(p / (ab * ab));
    return k1 * (k1 - 1.0) / max(k2, 1e-6);
}

vec3 tonemapACES(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}

// Display transform for HDR surfaces (ADR 004): scene-referred linear light →
// exposure → ACES → sRGB encode. A function, not inline in the branch, so a
// selectable transform later swaps this body rather than the dispatch.
vec3 displayTransform(vec3 scene) {
    return linearToSrgb(tonemapACES(scene * u_exposure));
}

// Backdrop sampling is clamped to the region that was actually copied: outside
// it the texture still holds whatever an earlier frame or an earlier effect put
// there, and a refracted sample near the edge reaches further than the shape.
vec2 clampBackdrop(vec2 uv) {
    return clamp(uv, v_grad.xy, v_grad.zw);
}

vec3 backdropAt(vec2 uv) {
    vec4 texel = texture(u_atlas, clampBackdrop(uv));
    // The framebuffer is premultiplied; un-premultiply so the tint below mixes
    // against a straight colour. On an opaque window this divides by one.
    return texel.rgb / max(texel.a, 1e-4);
}

void main() {
    float strokeHW = v_misc.x;
    float kind = v_misc.y;
    float paintType = v_misc.z;
    float clipRadius = v_misc.w;

    // Clip: rounded rect in device space (gl_FragCoord is y-up; flip). The
    // radius must be clamped to the intersected AABB's half extents or the
    // SDF degenerates and clips everything (nested clipRoundRect + clipRect).
    vec2 fragDev = vec2(gl_FragCoord.x, u_viewport.y - gl_FragCoord.y);
    vec2 clipCenter = (v_clip.xy + v_clip.zw) * 0.5;
    vec2 clipHalf = (v_clip.zw - v_clip.xy) * 0.5;
    float clipR = min(clipRadius, min(clipHalf.x, clipHalf.y));
    float dClip = sdRoundRect(fragDev - clipCenter, clipHalf, vec4(clipR));
    float clipCoverage = 1.0 - smoothstep(-0.5, 0.5, dClip);

    if (kind >= 7.5) {
        // Backdrop effect: the shape's own SDF gives both its coverage and, through
        // its gradient, the surface normal the refracting variant needs. Nothing
        // here samples v_uv; the fragment finds itself in the copied framebuffer
        // by its own device coordinate, so the effect is correct under any
        // transform that moved the quad.
        float dGlass = sdRoundRect(v_local, v_halfSize, v_radii);
        float aaGlass = fwidth(dGlass) * 0.5 + 1e-4;
        float coverGlass = clipCoverage * (1.0 - smoothstep(-aaGlass, aaGlass, dGlass));
        float scale = v_misc.x;      // device pixels per local unit
        float variant = v_colorB.x;
        float param1 = v_colorB.y;
        float param2 = v_colorB.z;
        vec2 uv = gl_FragCoord.xy / u_viewport;
        vec3 sampled;
        if (variant < 0.5) {
            // Clear: a bevelled rim. The SDF gradient is the outward normal, and the
            // backdrop is pushed along it, hardest at the edge and not at all past
            // param1 (the rim width, in local units), the pinch a real pane shows.
            vec2 grad = vec2(dFdx(dGlass), dFdy(dGlass));
            vec2 normal = grad / max(length(grad), 1e-5);
            float bevel = clamp(1.0 + dGlass / max(param1, 1e-4), 0.0, 1.0);
            vec2 push = normal * (param1 * scale * 0.5 * bevel * bevel) / u_viewport;
            if (param2 > 0.0) {
                // Dispersion: the channels bend by different amounts, which is what
                // glass does to white light and what no amount of blur can imitate.
                sampled = vec3(backdropAt(uv + push * (1.0 + param2)).r,
                               backdropAt(uv + push).g,
                               backdropAt(uv + push * (1.0 - param2)).b);
            } else {
                sampled = backdropAt(uv + push);
            }
        } else if (variant < 1.5) {
            // Wash: undisplaced, saturation moved toward (or past) grey.
            vec3 back = backdropAt(uv);
            sampled = mix(vec3(dot(back, vec3(0.2126, 0.7152, 0.0722))), back, param1);
        } else {
            // Pixelate: one sample per cell, and the grid is anchored to the
            // framebuffer rather than to the shape: a panel that moves must not
            // shimmer through its own cells.
            float cellPx = max(param1 * scale, 1.0);
            vec2 snapped = (floor(gl_FragCoord.xy / cellPx) + 0.5) * cellPx;
            sampled = backdropAt(snapped / u_viewport);
        }
        float aGlass = coverGlass * v_colorB.w;
        o_color = vec4(mix(sampled, v_colorA.rgb, v_colorA.a) * aGlass, aGlass);
        return;
    }

    if (kind >= 4.5) {
        vec4 texel = texture(u_atlas, v_uv);
        if (kind >= 6.5) {
            // HDR surface (3D render target): premultiplied LINEAR scene-referred
            // light. Un-premultiply BEFORE the transform (ACES is non-linear, so
            // tonemapping alpha-scaled RGB would shade a translucent bright
            // surface differently from an opaque one of the same color), then
            // transform and re-premultiply. Tint semantics match the image branch
            // (applied display-referred; drawSurface always sends white).
            float tintScale = v_colorA.a * clipCoverage;
            vec3 c = displayTransform(texel.rgb / max(texel.a, 1e-4));
            o_color = vec4(c * v_colorA.rgb * texel.a * tintScale, texel.a * tintScale);
        } else if (kind >= 5.5) {
            // Image mask (icon): the texel's alpha is the coverage; paint it in
            // the tint color (v_colorA). A mask of ANY source color recolors to
            // the theme this way, unlike the modulate branch below, where a
            // black mask (texel.rgb == 0) would stay black.
            float a = texel.a * v_colorA.a * clipCoverage;
            o_color = vec4(v_colorA.rgb * a, a);
        } else {
            // Image: PREMULTIPLIED RGBA texel (images premultiply at upload
            // and the 3D pipeline renders premultiplied), so GL_LINEAR filters
            // associated alpha. Filtering straight alpha would bleed the RGB
            // of transparent texels into every edge sample (color halos).
            // The tint (v_colorA) stays straight; output premultiplied.
            float tintScale = v_colorA.a * clipCoverage;
            o_color = vec4(texel.rgb * v_colorA.rgb * tintScale, texel.a * tintScale);
        }
        return;
    }

    float coverage = clipCoverage;
    if (kind < 1.5) {
        float d = (kind < 0.5) ? sdRoundRect(v_local, v_halfSize, v_radii)
                               : sdEllipse(v_local, v_halfSize);
        // AA width from the raw SDF gradient, BEFORE the stroke fold: fwidth
        // of abs(d) collapses where d changes sign inside a 2x2 quad, which
        // under-antialiases thin strokes along their medial axis.
        float aa = fwidth(d) * 0.5 + 1e-4;
        if (strokeHW >= 0.0) {
            d = abs(d) - strokeHW;
        }
        coverage *= 1.0 - smoothstep(-aa, aa, d);
    } else if (kind >= 3.5) {
        coverage *= texture(u_atlas, v_uv).r; // glyph: stb-rasterized coverage
    } else if (kind >= 2.5) {
        coverage *= clamp(v_radii.x, 0.0, 1.0); // fringe ramp: 1 at contour, 0 outside
    }

    // Gradients interpolate PREMULTIPLIED endpoints: mixing straight alpha
    // bleeds a transparent stop's RGB into every intermediate color (a fade
    // to Color.TRANSPARENT, black at alpha 0, would darken through gray).
    vec4 a = vec4(v_colorA.rgb * v_colorA.a, v_colorA.a);
    vec4 color;
    if (paintType < 0.5) {
        color = a;
    } else if (paintType < 1.5) {
        vec4 b = vec4(v_colorB.rgb * v_colorB.a, v_colorB.a);
        vec2 dir = v_grad.zw - v_grad.xy;
        float len2 = max(dot(dir, dir), 1e-6);
        float t = clamp(dot(v_local - v_grad.xy, dir) / len2, 0.0, 1.0);
        color = mix(a, b, t);
    } else {
        vec4 b = vec4(v_colorB.rgb * v_colorB.a, v_colorB.a);
        float t = clamp(length(v_local - v_grad.xy) / max(v_grad.z, 1e-6), 0.0, 1.0);
        color = mix(a, b, t);
    }

    o_color = color * coverage;
}
