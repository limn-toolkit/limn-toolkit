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
in vec4 v_misc;    // x: strokeHalfWidth (-1 = fill), y: kind, z: paintType, w: rounded clip radius
in vec4 v_colorA;
in vec4 v_colorB;
in vec4 v_grad;
in vec4 v_clip;      // the clip rectangle: every clip's device bounds intersected
in vec2 v_uv;
in vec4 v_roundClip; // the rounded clip's own device rectangle (radius: v_misc.w)

uniform vec2 u_viewport; // the target's size in device px: the window, or a layer (ADR 071)
uniform vec2 u_origin;   // the target's top-left in the window's device px, top-down; 0 for the window
uniform vec2 u_window;   // the window's framebuffer size in device px, and so the backdrop copy's

// CRT pitches, in local units (points). Locked rather than passed: see
// BackdropEffect.Crt, which carries the same numbers and the reasoning.
//
// The beam is COARSER than the mask, and that ratio is the whole reason the scan
// reads as lines: at one pitch the two patterns sum into an even mesh, which is
// what a first attempt at this produced and what made it look like a screen door
// laid over the picture rather than a tube.
// Glass: the index of refraction the Clear variant bends by, and how far param2
// spreads it between red and blue. Crown glass is about 1.52 and splits by roughly
// one part in sixty; the spread here is that exaggerated to where a fringe is
// visible at full dispersion without the rim becoming a prism.
const float GLASS_IOR = 1.52;
const float GLASS_IOR_SPREAD = 0.06;

const float CRT_SCAN_PITCH = 6.0;
const float CRT_GRILLE_PITCH = 3.0;
uniform sampler2D u_atlas; // bound texture for this draw: R8 glyph atlas
                           // (.r = coverage) or RGBA8 image; LINEAR filtered.
uniform float u_exposure;  // HDR-surface quads only (kind 7): display exposure
// A path clip (GlCanvas.clipPath): its coverage in u_mask's alpha, one texel per pixel of the
// target's space, over u_maskRect (x, y, width, height in that space's device px, top-down, its
// first row at the bottom as a layer's picture has it); 0 past it. Multiplies every clip coverage.
uniform int u_maskOn;
uniform sampler2D u_mask;
uniform vec4 u_maskRect;
// A blend rule computed here (GlBlend.RULE_*; 0: the fixed factors do it), from u_dst, a copy of
// the target taken under what is being drawn, texel for pixel. The output replaces the pixel.
uniform int u_rule;
uniform sampler2D u_dst;
// A Gradient's stops (GradientRamps): one row per gradient, texel 0 the count, then each stop's
// position and premultiplied colour. Floats read exactly, hence highp in the ES port.
uniform highp sampler2D u_stops;
// An ImagePaint's picture, premultiplied, wrapped by the sampler as its tiles ask.
uniform sampler2D u_paint;

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
//       9 = shadow (a rounded rect blurred analytically; v_misc.x = how far
//           the quad reaches past the shape, three deviations of the blur)
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

// The path clip's coverage at the fragment, read at its own pixel: the mask was drawn on these
// pixels, so no filter may blend a neighbour in.
float maskAt(vec2 fragDev) {
    vec2 p = floor(vec2(fragDev.x - u_maskRect.x, u_maskRect.y + u_maskRect.w - fragDev.y));
    if (p.x < 0.0 || p.y < 0.0 || p.x >= u_maskRect.z || p.y >= u_maskRect.w) {
        return 0.0;
    }
    return texelFetch(u_mask, ivec2(p), 0).a;
}

// The pixel a blend rule leaves: its result for the premultiplied source over the destination, the
// copy of the target at this pixel, weighed against the destination by the coverage, so that a pixel
// the shape covers in part keeps the rest of what it held and one it misses keeps all of it. The
// numbers are GlBlend's RULE_ constants.
vec4 ruled(vec4 src, float cover) {
    vec4 dst = texelFetch(u_dst, ivec2(gl_FragCoord.xy), 0);
    vec4 r;
    if (u_rule == 1) {
        r = vec4(0.0);                                   // clear
    } else if (u_rule == 2) {
        r = src;                                         // source
    } else if (u_rule == 3) {
        r = src * dst.a;                                 // source in
    } else if (u_rule == 4) {
        r = dst * src.a;                                 // destination in
    } else if (u_rule == 5) {
        r = src * (1.0 - dst.a);                         // source out
    } else if (u_rule == 6) {
        r = dst * src.a + src * (1.0 - dst.a);           // destination atop
    } else {
        // Darken or lighten: the web's separable blend, B(cs, cb) the minimum or the maximum,
        // premultiplied: B over both, each where the other is not.
        vec3 s = src.rgb * dst.a;
        vec3 d = dst.rgb * src.a;
        vec3 b = (u_rule == 7 || u_rule == 9) ? min(s, d) : max(s, d);
        vec3 c = b + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a);
        r = vec4(c, u_rule >= 9 ? dst.a : src.a + dst.a * (1.0 - src.a));
    }
    return mix(dst, r, cover);
}

// A Gradient's colour at the fragment, premultiplied. v_grad and v_colorB.xy map the shape's local
// point into the gradient's unit space: for a linear one, x runs 0 to 1 from its start to its end;
// for a radial one, the circle is the unit circle and its focus is v_colorB.zw; for a sweep, the
// angle from the x axis is the turn from its start. v_colorA is (row, kind, spread, opacity).
vec4 gradientAt() {
    vec2 g = vec2(dot(v_grad.xy, v_local), dot(v_grad.zw, v_local)) + v_colorB.xy;
    float t;
    if (v_colorA.y < 0.5) {
        t = g.x;
    } else if (v_colorA.y < 1.5) {
        // The two-circle gradient from a point, the focus, out to the unit circle: the circle
        // through g of the family growing from the focus to the circle, as a share of the way.
        // With the focus at the centre this is length(g).
        vec2 f = v_colorB.zw;
        vec2 d = g - f;
        float a = dot(d, d);
        float b = dot(f, d);
        float c = dot(f, f) - 1.0;
        t = a / max(-b + sqrt(max(b * b - a * c, 0.0)), 1e-12);
    } else {
        t = atan(g.y, g.x) / 6.28318530718;
        t -= floor(t);
    }
    if (v_colorA.z < 0.5) {
        t = clamp(t, 0.0, 1.0);
    } else if (v_colorA.z < 1.5) {
        t -= floor(t);
    } else {
        t = 1.0 - abs(t - 2.0 * floor(t * 0.5) - 1.0);
    }
    int row = int(v_colorA.x + 0.5);
    int n = int(texelFetch(u_stops, ivec2(0, row), 0).x + 0.5);
    float p0 = texelFetch(u_stops, ivec2(1, row), 0).x;
    vec4 c0 = texelFetch(u_stops, ivec2(2, row), 0);
    if (t <= p0) {
        return c0 * v_colorA.w;
    }
    for (int i = 1; i < n; i++) {
        float p1 = texelFetch(u_stops, ivec2(1 + 2 * i, row), 0).x;
        vec4 c1 = texelFetch(u_stops, ivec2(2 + 2 * i, row), 0);
        if (t <= p1) {
            return mix(c0, c1, p1 > p0 ? (t - p0) / (p1 - p0) : 1.0) * v_colorA.w;
        }
        p0 = p1;
        c0 = c1;
    }
    return c0 * v_colorA.w;
}

// An ImagePaint's colour at the fragment, premultiplied: the picture at the local point mapped to
// its texture coordinates by v_grad and v_colorB.xy, wrapped by the sampler; past an edge whose tile
// is none (v_colorB.zw, 1 for none), clear, the edge antialiased over a pixel. v_colorA.w: opacity.
vec4 imageAt() {
    vec2 uv = vec2(dot(v_grad.xy, v_local), dot(v_grad.zw, v_local)) + v_colorB.xy;
    vec4 texel = texture(u_paint, uv);
    float keep = 1.0;
    vec2 w = max(fwidth(uv), vec2(1e-6));
    if (v_colorB.z > 0.5) {
        keep *= clamp(min(uv.x, 1.0 - uv.x) / w.x + 0.5, 0.0, 1.0);
    }
    if (v_colorB.w > 0.5) {
        keep *= clamp(min(uv.y, 1.0 - uv.y) / w.y + 0.5, 0.0, 1.0);
    }
    return texel * (keep * v_colorA.w);
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

    // Clip, in device space (gl_FragCoord is y-up; flip): the clip rectangle
    // intersected with the rounded clip, each from its OWN geometry, as the
    // max of the two distances. Rounding the corners of the rectangle every
    // clip has narrowed instead put a corner wherever a rectangular clip cut a
    // rounded one, and a partial repaint clips each pass to a rectangle: the
    // pass's edge grew arcs inside the surface it cut. Kept apart, a pixel
    // inside a rectangular clip lies at least half a pixel from its
    // pixel-aligned edges, so its coverage is exactly what it was without that
    // clip: a pass draws what the whole frame drew. With no rounded clip the
    // rounded rectangle is the whole target with no radius, which contains the
    // clip rectangle and leaves the max the rectangle's own distance. The
    // radius is clamped to the rounded rectangle's half extents, or the SDF
    // degenerates and clips everything.
    //
    // Only one of the two distances is computed unless both are needed, because on a renderer
    // that runs this shader on the CPU (macOS's software fallback) the second one cost a fifth
    // of the frame rate. The clip rectangle always lies inside the rounded clip's bounds, so:
    // with no radius the rectangle's distance is the whole answer; when nothing has cut the
    // rounded clip the two rectangles coincide and the rounded distance, never below the
    // rectangle's, is the answer; only a rounded clip that a rectangle has cut needs the max.
    // Each branch gives exactly what the max gives, so the coverage is unchanged bit for bit.
    //
    // Every clip, and the backdrop copy, is in the window's device pixels, so the fragment finds
    // itself there first: inside a layer gl_FragCoord counts from the layer's own corner, and the
    // layer's origin moves it back. With no layer the offset is exactly zero and this is
    // gl_FragCoord itself, bit for bit.
    vec2 fragWin = gl_FragCoord.xy + vec2(u_origin.x, u_window.y - u_origin.y - u_viewport.y);
    vec2 fragDev = vec2(fragWin.x, u_window.y - fragWin.y);
    vec2 clipCenter = (v_clip.xy + v_clip.zw) * 0.5;
    vec2 clipHalf = (v_clip.zw - v_clip.xy) * 0.5;
    float dClip;
    if (clipRadius <= 0.0) {
        dClip = sdRoundRect(fragDev - clipCenter, clipHalf, vec4(0.0));
    } else {
        vec2 roundCenter = (v_roundClip.xy + v_roundClip.zw) * 0.5;
        vec2 roundHalf = (v_roundClip.zw - v_roundClip.xy) * 0.5;
        float roundR = min(clipRadius, min(roundHalf.x, roundHalf.y));
        float dRound = sdRoundRect(fragDev - roundCenter, roundHalf, vec4(roundR));
        if (all(lessThan(abs(v_clip - v_roundClip), vec4(1.0e-3)))) {
            dClip = dRound;
        } else {
            dClip = max(sdRoundRect(fragDev - clipCenter, clipHalf, vec4(0.0)), dRound);
        }
    }
    float clipCoverage = 1.0 - smoothstep(-0.5, 0.5, dClip);
    if (u_maskOn != 0) {
        clipCoverage *= maskAt(fragDev);
    }

    if (kind > 8.5) {
        // Shadow: the shape blurred, with no blur pass. The blur of a straight edge is the
        // error function of the distance to it, and a rounded rectangle's distance field is
        // close enough to that everywhere but inside a corner tighter than the blur, where the
        // difference is a shadow a little rounder than a kernel would leave it. v_misc.x is how
        // far the quad reaches past the shape, three deviations of the blur.
        float dShadow = sdRoundRect(v_local, v_halfSize, v_radii);
        float sigma = max(v_misc.x / 3.0, fwidth(dShadow) * 0.5 + 1e-4);
        float x = dShadow / (sigma * 1.41421356);
        // erf by Abramowitz and Stegun 7.1.26, good to 1.5e-7: nothing in the portable subset has one.
        float ax = abs(x);
        float tt = 1.0 / (1.0 + 0.3275911 * ax);
        float erfAx = 1.0 - (((((1.061405429 * tt - 1.453152027) * tt) + 1.421413741) * tt
                - 0.284496736) * tt + 0.254829592) * tt * exp(-ax * ax);
        float aShadow = 0.5 * (1.0 - sign(x) * erfAx) * v_colorA.a * clipCoverage;
        if (u_rule != 0) {
            float aOwn = 0.5 * (1.0 - sign(x) * erfAx) * v_colorA.a;
            o_color = ruled(vec4(v_colorA.rgb * aOwn, aOwn), clipCoverage);
            return;
        }
        o_color = vec4(v_colorA.rgb * aShadow, aShadow);
        return;
    }

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
        vec2 uv = fragWin / u_window;
        vec3 sampled;
        if (variant < 0.5) {
            // Clear: a pane with a rounded bevel, refracting by Snell's law.
            //
            // The first version of this ramped the displacement quadratically across the
            // rim, which is smooth everywhere and therefore reads as a soft lens: real
            // glass bends almost nothing across its flat middle and then very hard in the
            // last fraction of the bevel, and that concentration is the whole look. So the
            // rim is modelled as a quarter-round and the light is actually refracted
            // through it.
            vec2 grad = vec2(dFdx(dGlass), dFdy(dGlass));
            vec2 normal = grad / max(length(grad), 1e-5);

            // How far in, across the bevel: 0 at the edge, 1 where the pane goes flat.
            //
            // A zero-width bevel is NO bevel, not an infinitely steep one. Guarding the
            // divide with an epsilon instead makes `across` collapse to 0 in the edge
            // fragments, which refracts at a rim the pane does not have: the identity
            // assertion caught exactly that.
            float across = param1 > 0.0 ? clamp(-dGlass / param1, 0.0, 1.0) : 1.0;
            // A quarter-round surface: the sine of the angle its normal makes with the
            // pane is exactly the distance still to go. At the edge that is 1 (grazing),
            // in the middle 0 (flat), and nothing in between needs a height map.
            float sinIn = 1.0 - across;

            // param1 is the bevel width AND the optical depth, because for a bevelled pane
            // they are the same measurement: a thicker pane has a wider roll and carries
            // the ray further sideways before it comes out.
            float depth = param1 * scale;

            // Snell, twice, for the two ends of the visible spectrum. param2 spreads the
            // index rather than the displacement: an earlier version scaled the whole push
            // by (1 +/- dispersion), so at the default the red and blue taps sat 70% of the
            // full rim displacement apart and the rim wore a rainbow. Crown glass splits by
            // about one part in sixty, and GLASS_IOR_SPREAD is that exaggerated to where it
            // is visible without being the subject.
            float spread = param2 * GLASS_IOR_SPREAD;
            vec3 offset;
            for (int channel = 0; channel < 3; channel++) {
                // Red bends least, blue most: the index rises toward the short end.
                float ior = GLASS_IOR + spread * (float(channel) - 1.0);
                float sinOut = sinIn / ior;
                float cosOut = sqrt(max(1.0 - sinOut * sinOut, 1e-4));
                float cosIn = sqrt(max(1.0 - sinIn * sinIn, 1e-4));
                // Lateral shift through a slab: d * sin(in - out) / cos(out). Not
                // d * tan(out), which is where the ray sits INSIDE the glass rather than
                // how far the picture behind it moves, and which disperses backwards: a
                // higher index has to displace MORE, and blue has the higher index.
                offset[channel] = depth * (sinIn * cosOut - cosIn * sinOut) / cosOut;
            }
            vec2 dir = normal / u_window;
            sampled = vec3(backdropAt(uv + dir * offset.r).r,
                           backdropAt(uv + dir * offset.g).g,
                           backdropAt(uv + dir * offset.b).b);
        } else if (variant < 1.5) {
            // Wash: undisplaced, saturation moved toward (or past) grey, then lifted or
            // sunk. The lift is the axis saturation cannot reach: toward white for a
            // positive value and toward black for a negative one, and exactly nothing at
            // zero, which is what keeps the two-argument form of the record unchanged.
            vec3 back = backdropAt(uv);
            sampled = mix(vec3(dot(back, vec3(0.2126, 0.7152, 0.0722))), back, param1);
            sampled = mix(sampled, vec3(step(0.0, param2)), abs(param2));
        } else if (variant < 2.5) {
            // Pixelate: one sample per cell, and the grid is anchored to the
            // framebuffer rather than to the shape: a panel that moves must not
            // shimmer through its own cells.
            float cellPx = max(param1 * scale, 1.0);
            vec2 snapped = (floor(fragWin / cellPx) + 0.5) * cellPx;
            sampled = backdropAt(snapped / u_window);
        } else if (variant < 3.5) {
            // CRT: the picture is bent onto a tube face and then striped by the scan.
            // Both are anchored to the SHAPE, which is the opposite of Pixelate above
            // and deliberate: a grid belongs to the framebuffer, but a tube's curve and
            // its scan lines belong to the tube, and a panel that kept its stripes while
            // it slid would read as a hole cut in a filter.
            vec2 p = v_local / max(v_halfSize, vec2(1e-4));  // -1..1 across the shape
            vec2 pushLocal = v_local * (param2 * dot(p, p)); // outward, by radius squared

            // Clamped to the SHAPE's own extent, not to the copied region.
            //
            // A tube shows what is behind the tube. Letting the bulge reach past the shape
            // makes it import whatever the frame drew next door: the copy is the shape plus
            // a margin, so the pixels are genuinely there and the sampling clamp has no
            // reason to stop them, and a strong curvature over a busy window pulled in the
            // neighbouring widget and read as the picture repeating. Clamping the TARGET in
            // local units instead is shape-relative by construction, so it holds for a
            // rectangle, a capsule or a circle without any of them being special-cased.
            pushLocal = clamp(v_local + pushLocal, -v_halfSize, v_halfSize) - v_local;

            // Local displacement to screen displacement, through the inverse of the
            // local-to-screen Jacobian. Clear (above) gets away with a screen-space
            // gradient because a normal is direction-only; this carries a magnitude in
            // local units, so it has to be transformed rather than assumed. Doing it
            // this way also means a rotated or flipped quad bends the right way instead
            // of pincushioning on one axis.
            vec2 jx = vec2(dFdx(v_local.x), dFdy(v_local.x));
            vec2 jy = vec2(dFdx(v_local.y), dFdy(v_local.y));
            float det = jx.x * jy.y - jx.y * jy.x;
            vec2 pushDev = vec2(0.0);
            if (abs(det) > 1e-9) {
                pushDev = vec2(jy.y * pushLocal.x - jx.y * pushLocal.y,
                              -jy.x * pushLocal.x + jx.x * pushLocal.y) / det;
            }
            sampled = backdropAt(uv + pushDev / u_window);

            // Everything below is in LOCAL units, so every pitch is in points and the
            // structure is the same size on a dense display as on a coarse one.
            //
            // Two parameters drive five things, because on a real tube they are not five
            // independent knobs: param1 is how deep the MASK cuts (scan lines and the
            // grille together, which is one physical thing seen twice), param2 is how the
            // GLASS is shaped (the bulge above and the falloff below). Every one of them
            // is written so that a zero parameter is exactly 1.0, which is what keeps the
            // identity assertion in GlBackdropTest true.

            // The aperture grille: vertical RGB triads. This is the thing that actually
            // reads as a tube rather than as a filter laid over a picture; scan lines
            // alone are just horizontal stripes, and a picture wearing only those looks
            // painted on.
            float triad = mod(v_local.x * (3.0 / CRT_GRILLE_PITCH), 3.0);
            vec3 grille = vec3(1.0 - clamp(abs(triad - 0.5), 0.0, 1.0),
                               1.0 - clamp(abs(triad - 1.5), 0.0, 1.0),
                               1.0 - clamp(abs(triad - 2.5), 0.0, 1.0));
            // Lifted off black and gained back up: a grille that multiplied straight by
            // the ramp would throw away two thirds of the light, and a real one is a mask
            // in front of a brighter beam, not a filter over the same one.
            grille = mix(vec3(1.0), grille * 1.35 + 0.25, param1);

            // The scan. Brighter lines swell into their own gap, which is what a phosphor
            // does and what a fixed-width stripe cannot imitate: it is the difference
            // between a lit tube and a grid drawn over an image.
            float lum = dot(sampled, vec3(0.2126, 0.7152, 0.0722));
            float gap = abs(fract(v_local.y / CRT_SCAN_PITCH) - 0.5) * 2.0;
            float width = 0.35 + 0.5 * lum;
            float beam = smoothstep(width + 0.3, width - 0.3, gap);
            sampled *= mix(1.0, beam * 0.75 + 0.45, param1) * grille;

            // The glass falls away at the corners, by the same radius the bulge uses.
            sampled *= 1.0 - param2 * 0.45 * dot(p, p);
        } else {
            // Blur: one axis, sampled symmetrically. Two of these crossed are a full blur,
            // at 2r samples instead of the r*r a two-dimensional kernel would cost, and
            // the second pass reads what the first one wrote because a backdrop effect
            // both reads and writes the framebuffer.
            //
            // A fixed tap count with the SPACING carrying the radius: a loop whose trip
            // count came from a varying is not something every driver in the portable
            // subset compiles well, and a wide blur wants fewer, further taps anyway.
            vec2 axis = param2 < 0.5 ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec2 step1 = axis * (param1 * scale / u_window) / 4.0;
            // Gaussian-ish weights, normalized: 0.20 centre, then 0.17, 0.12, 0.07, 0.04.
            sampled = backdropAt(uv) * 0.2;
            sampled += (backdropAt(uv + step1) + backdropAt(uv - step1)) * 0.17;
            sampled += (backdropAt(uv + step1 * 2.0) + backdropAt(uv - step1 * 2.0)) * 0.12;
            sampled += (backdropAt(uv + step1 * 3.0) + backdropAt(uv - step1 * 3.0)) * 0.07;
            sampled += (backdropAt(uv + step1 * 4.0) + backdropAt(uv - step1 * 4.0)) * 0.04;
        }
        float aGlass = coverGlass * v_colorB.w;
        if (u_rule != 0) {
            o_color = ruled(vec4(mix(sampled, v_colorA.rgb, v_colorA.a) * v_colorB.w, v_colorB.w), coverGlass);
            return;
        }
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
            if (u_rule != 0) {
                o_color = ruled(vec4(c * v_colorA.rgb * texel.a * v_colorA.a, texel.a * v_colorA.a),
                        clipCoverage);
                return;
            }
            o_color = vec4(c * v_colorA.rgb * texel.a * tintScale, texel.a * tintScale);
        } else if (kind >= 5.5) {
            // Image mask (icon): the texel's alpha is the coverage; paint it in
            // the tint color (v_colorA). A mask of ANY source color recolors to
            // the theme this way, unlike the modulate branch below, where a
            // black mask (texel.rgb == 0) would stay black.
            float a = texel.a * v_colorA.a * clipCoverage;
            if (u_rule != 0) {
                // The mask is the shape: its alpha covers, as a fill's edge does.
                o_color = ruled(vec4(v_colorA.rgb * v_colorA.a, v_colorA.a), texel.a * clipCoverage);
                return;
            }
            o_color = vec4(v_colorA.rgb * a, a);
        } else {
            // Image: PREMULTIPLIED RGBA texel (images premultiply at upload
            // and the 3D pipeline renders premultiplied), so GL_LINEAR filters
            // associated alpha. Filtering straight alpha would bleed the RGB
            // of transparent texels into every edge sample (color halos).
            // The tint (v_colorA) stays straight; output premultiplied.
            float tintScale = v_colorA.a * clipCoverage;
            if (u_rule != 0) {
                // An image is a paint over its whole quad: a transparent texel is a transparent
                // source, which a rule such as source draws as it is.
                o_color = ruled(vec4(texel.rgb * v_colorA.rgb * v_colorA.a, texel.a * v_colorA.a), clipCoverage);
                return;
            }
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
    } else if (paintType < 2.5) {
        vec4 b = vec4(v_colorB.rgb * v_colorB.a, v_colorB.a);
        float t = clamp(length(v_local - v_grad.xy) / max(v_grad.z, 1e-6), 0.0, 1.0);
        color = mix(a, b, t);
    } else if (paintType < 3.5) {
        color = gradientAt();
    } else {
        color = imageAt();
    }

    if (u_rule != 0) {
        o_color = ruled(color, coverage);
        return;
    }
    o_color = color * coverage;
}
