#version 330 core
// The zero-copy sibling of video_convert.frag: the same conversion, reading planes that ARE a
// decoder's own device memory rather than planes uploaded into textures of this surface's.
//
// WHY THIS IS A SECOND FILE AND NOT A #define IN THE FIRST ONE
//
// Two differences, and each one is a compile error in the other file's world:
//
//   1. `texelFetch` on a rectangle sampler takes NO level-of-detail argument, because a rectangle
//      texture has exactly one level. `texelFetch(s, p, 0)` does not compile against
//      sampler2DRect and `texelFetch(s, p)` does not compile against sampler2D.
//   2. GLSL ES 3.00 has no `sampler2DRect` AT ALL: no rectangle textures, no keyword, nothing to
//      guard with. A preprocessor branch would therefore sit inside the one file ADR 003's port
//      must keep compiling, holding a branch that can never compile there, and the port would
//      discover it by failing to build the shader every window needs.
//
// So the platform-specific half is a file the port simply does not load, and video_convert.frag is
// untouched. The uniforms, the arithmetic and the orientation are deliberately identical; the two
// sources are compared by VideoShaderTest, which is parameterised over both.
//
// The binding that makes this possible is CGLTexImageIOSurface2D, which accepts GL_TEXTURE_RECTANGLE
// and refuses GL_TEXTURE_2D. That is where the rectangle comes from; it is not a choice.
//
// EVERY number of the matrix arrives as a uniform, for video_convert.frag's reason: retyping a
// coefficient here would create a second table, and two tables drift silently.

uniform sampler2DRect u_luma;
uniform sampler2DRect u_cb;   // Cb plane; the interleaved Cb/Cr plane when u_interleaved is 1
uniform sampler2DRect u_cr;   // Cr plane; bound to u_cb's texture when u_interleaved is 1
uniform int u_interleaved;  // 1 when chroma is one two-component plane (Cb in .r, Cr in .g)
uniform int u_height;       // picture rows, to turn a bottom-up fragment into a top-down row
uniform ivec2 u_chromaShift;
// Normalized sample -> code, and code -> normalized output. Both a right-justified 10-bit layout
// and P010 arrive here as 16-bit texels, and they do NOT share this number: P010's code sits in the
// top ten bits, so its scale is the storage maximum divided by 64. That is the whole of what makes
// a hardware picture decode correctly, and it is why P010 needed no new uniform.
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
    // gl_FragCoord counts rows up from the bottom of the target, and a picture's rows run top-down,
    // so row 0 lands at the TOP of the target. A rectangle texture's texel (0,0) is the IOSurface's
    // row 0, exactly as an uploaded texture's is, so this arithmetic is unchanged (measured, not
    // assumed), because a surface arriving bottom-up would be invisible in a flat test picture.
    ivec2 frag = ivec2(gl_FragCoord.xy);
    ivec2 pixel = ivec2(frag.x, u_height - 1 - frag.y);

    ivec2 chroma = ivec2(pixel.x >> u_chromaShift.x, pixel.y >> u_chromaShift.y);

    float y = texelFetch(u_luma, pixel).r * u_sampleScale;
    vec2 cbcr;
    if (u_interleaved == 1) {
        cbcr = texelFetch(u_cb, chroma).rg * u_sampleScale;
    } else {
        cbcr = vec2(texelFetch(u_cb, chroma).r, texelFetch(u_cr, chroma).r) * u_sampleScale;
    }
    float cb = cbcr.x - u_chromaNeutral;
    float cr = cbcr.y - u_chromaNeutral;
    float luma = u_yScale * (y - u_yOffset);

    // Green sums its two chroma terms BEFORE the luma is added, exactly as bracketed. Adding them
    // one at a time re-associates the sum and lands on the other side of a rounding tie for some
    // codes, and this program is held to the same oracle the uploaded one is.
    vec3 rgb = vec3(luma + u_crToR * cr,
                    luma + (u_cbToG * cb + u_crToG * cr),
                    luma + u_cbToB * cb);

    o_color = vec4(clamp(rgb / u_maxCode, 0.0, 1.0), 1.0);
}
