#version 330 core
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset (the ES port adds
// `precision highp float;` and `precision highp int;` because mediump carries
// about three decimal digits and would round the colour matrix visibly).
//
// YCbCr planes -> display-referred, gamma-encoded RGBA. The samples are already
// encoded for a display: this decodes a matrix, it does not manage colour, and
// nothing here tonemaps or converts a transfer function. That is why the
// converted picture composites through the ordinary image path rather than the
// HDR-surface path, which would apply a display transform to it a second time.
//
// It follows that this program is only correct for a picture whose transfer
// function IS the display's; a PQ or HLG picture is refused before it reaches
// here rather than being run through a matrix that would leave it washed out.
// The depth is a separate question and this program handles both: 8- and 10-bit
// samples differ only in the two scale uniforms below.
//
// EVERY number of the matrix arrives as a uniform, from the one table the CPU
// converter reads. Retyping a coefficient here would create a second table, and
// the two would drift, silently, because the difference between two matrices
// is a slight tint that looks like a display profile rather than a bug.

uniform sampler2D u_luma;
uniform sampler2D u_cb;   // Cb plane; the interleaved Cb/Cr plane when u_interleaved is 1
uniform sampler2D u_cr;   // Cr plane; bound to u_cb's texture when u_interleaved is 1
uniform int u_interleaved;  // 1 when chroma is one two-component plane (Cb in .r, Cr in .g)
uniform int u_height;       // picture rows, to turn a bottom-up fragment into a top-down row
uniform ivec2 u_chromaShift;
// Normalized sample -> code, and code -> normalized output. They are NOT the same number for a
// 10-bit picture: a code lives right-justified in a 16-bit texel, so the sampler normalizes it by
// 65535 while the code space it belongs to ends at 1023. Using one where the other belongs is a
// factor of 64, which reads as a black picture or a white one rather than as a colour error.
uniform float u_sampleScale;
uniform float u_maxCode;
uniform float u_yScale;
uniform float u_yOffset;
uniform float u_chromaNeutral;
uniform float u_crToR;
uniform float u_cbToG;
uniform float u_crToG;
uniform float u_cbToB;

out vec4 o_color;

void main() {
    // gl_FragCoord counts rows up from the bottom of the target, and a picture's
    // rows run top-down, so row 0 lands at the TOP of the target, the bottom-up
    // orientation the 2D composite expects of every GpuSurface.
    ivec2 frag = ivec2(gl_FragCoord.xy);
    ivec2 pixel = ivec2(frag.x, u_height - 1 - frag.y);

    // Chroma for pixel x is sample x >> shift, addressed as an integer texel.
    // A nearest sample at the normalized coordinate (x + 0.5) / width instead
    // picks floor((x + 0.5) * chromaWidth / width), which is a DIFFERENT sample
    // at odd widths (at width 5, pixel 3 lands on sample 2 rather than 1), and
    // the disagreement paints a coloured stripe down one edge of the picture.
    ivec2 chroma = ivec2(pixel.x >> u_chromaShift.x, pixel.y >> u_chromaShift.y);

    // Code space, [0..u_maxCode], because the coefficients are per code: scaling
    // the normalized samples back up here is what lets one table serve both the
    // CPU converter and this program.
    float y = texelFetch(u_luma, pixel, 0).r * u_sampleScale;
    vec2 cbcr;
    if (u_interleaved == 1) {
        cbcr = texelFetch(u_cb, chroma, 0).rg * u_sampleScale;
    } else {
        cbcr = vec2(texelFetch(u_cb, chroma, 0).r, texelFetch(u_cr, chroma, 0).r) * u_sampleScale;
    }
    float cb = cbcr.x - u_chromaNeutral;
    float cr = cbcr.y - u_chromaNeutral;
    float luma = u_yScale * (y - u_yOffset);

    // Green sums its two chroma terms BEFORE the luma is added, exactly as
    // bracketed. Adding them one at a time re-associates the sum and lands on
    // the other side of a rounding tie for some codes.
    vec3 rgb = vec3(luma + u_crToR * cr,
                    luma + (u_cbToG * cb + u_crToG * cr),
                    luma + u_cbToB * cb);

    // Studio footroom and headroom decode outside the output range by design;
    // the clamp is what keeps a legal highlight white instead of wrapping it to
    // a dark speckle. Alpha is opaque: a decoded picture has no transparency,
    // and 1 also makes the premultiplied texel the composite samples equal to
    // the straight one.
    o_color = vec4(clamp(rgb / u_maxCode, 0.0, 1.0), 1.0);
}
