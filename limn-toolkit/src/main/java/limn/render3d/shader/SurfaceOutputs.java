package limn.render3d.shader;

/**
 * What a material's surface function contributes to the engine's lighting core:
 * the metallic-roughness inputs. The backend owns the BRDF and consumes these:
 * {@code baseColor} (vec4, linear rgb + alpha), {@code metallic} and
 * {@code roughness} (floats), {@code emissive} (vec3, linear) and {@code normal},
 * the shading normal in <b>world space</b>. AO joins in a later phase.
 *
 * <p>{@code normal} may be {@code null}, meaning "shade with the interpolated
 * geometric normal". A tangent-space map becomes a world normal through the
 * framework helper {@code normalMapToWorld(tangentNormal, scale)}, which builds a
 * per-pixel frame from the screen-space derivatives of the world position and the
 * UVs, so no mesh tangent attribute is required and the vertex stage is
 * untouched. That is not only convenient: nothing in this toolkit produces
 * tangents (neither the primitives nor the glTF loader), so a surface that
 * demanded them would shade most meshes from a zero-length frame.
 *
 * <h2>The diffuse response</h2>
 *
 * {@code diffuseResponse} is the one output evaluated <b>inside</b> the light loop,
 * once per light rather than once per fragment. It replaces the {@code max(dot(N, L), 0)}
 * that scales the diffuse lobe, and it may read two framework locals the other
 * expressions cannot see:
 *
 * <ul>
 *   <li>{@code L}: a {@code vec3}, the unit vector from the surface <b>toward</b> the
 *       light, in world space, with the light's type and attenuation already resolved;</li>
 *   <li>{@code NdotL}: a {@code float}, the value this expression is replacing, so a
 *       surface that wants to blend with the standard term does not have to rebuild it.</li>
 * </ul>
 *
 * <p>Null means the standard term, and null is not merely the default: it is a
 * <b>different generated line</b>. With no response the backend emits the factored
 * form it always emitted, {@code (kd * albedo / PI + specular) * radiance * NdotL};
 * with one it has to distribute the {@code NdotL} across the two lobes so the diffuse
 * half can escape it. Those two are equal in real arithmetic and not in IEEE, so a
 * surface that supplied {@code NdotL} back verbatim would still shift the last bits of
 * every lit pixel. Keeping the null path byte-identical is what lets a material adopt
 * this without re-pinning a single existing image.
 *
 * <p><b>Why the diffuse lobe only.</b> Specular is a mirror about the normal and stays
 * on {@code NdotL}: a response is a statement about how light is <i>scattered</i> by the
 * surface, not about where it reflects. Wrap lighting, toon ramps, a cloth or hair term,
 * and the six-way directional response a volumetric billboard wants are all the diffuse
 * half; none of them wants the highlight moved.
 *
 * <p>To turn {@code L} into the same tangent frame {@code normalMapToWorld} shades in,
 * use the framework helper {@code worldToTangent(v)}.
 */
public record SurfaceOutputs(Expr baseColor, Expr metallic, Expr roughness, Expr emissive,
                             Expr normal, Expr diffuseResponse) {

    /** A surface shaded by the standard {@code N·L} diffuse response. */
    public SurfaceOutputs(Expr baseColor, Expr metallic, Expr roughness, Expr emissive,
                          Expr normal) {
        this(baseColor, metallic, roughness, emissive, normal, null);
    }

    /** A surface shaded by its geometric normal. */
    public static SurfaceOutputs of(Expr baseColor, Expr metallic, Expr roughness, Expr emissive) {
        return new SurfaceOutputs(baseColor, metallic, roughness, emissive, null, null);
    }

    /** A copy whose surface normal comes from {@code normal}, in tangent space. */
    public SurfaceOutputs withNormal(Expr normal) {
        return new SurfaceOutputs(baseColor, metallic, roughness, emissive, normal, diffuseResponse);
    }

    /**
     * A copy whose diffuse lobe is scaled by {@code diffuseResponse} instead of by
     * {@code max(dot(N, L), 0)}. The expression is evaluated once per light and may
     * reference the framework locals {@code L} and {@code NdotL}.
     */
    public SurfaceOutputs withDiffuseResponse(Expr diffuseResponse) {
        return new SurfaceOutputs(baseColor, metallic, roughness, emissive, normal, diffuseResponse);
    }
}
