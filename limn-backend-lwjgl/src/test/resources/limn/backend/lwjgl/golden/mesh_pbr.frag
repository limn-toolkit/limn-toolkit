#version 330 core
// GENERATED from the neutral shader IR by GlslCodegen. Do not edit by hand.
// PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Metallic-roughness
// PBR (Cook-Torrance GGX): sRGB→linear decode, light in linear, WRITE linear;
// the target is scene-referred; the display transform runs in the 2D composite
// (ADR 004).
#define MAX_LIGHTS 8
#define PI 3.14159265359

in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
in vec4 v_color;
// Read only by application-supplied surfaces (Material.Surface). Declared
// unconditionally so there is ONE vertex program for every surface: the
// vertex stage is identical whatever the fragment does, and a variant of it
// would be a second thing to keep in step for no gain.
in vec2 v_uv1;
in vec4 v_params;
in vec4 v_params1;

layout(std140) uniform Frame {
    mat4 u_viewProj;
    vec4 u_cameraPos; // xyz camera position, w unused (exposure lives in the composite: ADR 004)
};

struct Light {
    vec4 posRange;
    vec4 dirType;
    vec4 colorIntensity;
    vec4 spot;
};

layout(std140) uniform Lights {
    vec4 u_ambient;
    ivec4 u_lightCount;
    Light u_lights[MAX_LIGHTS];
};

layout(std140) uniform Material {
    vec4 u_baseColor;
    vec4 u_emissiveHasTex;
    vec4 u_mr;
};

uniform sampler2D u_baseColorTex;
uniform sampler2D u_normalTex;
uniform mat4 u_shadowVp;
uniform sampler2D u_shadowMap;
uniform float u_shadowStrength;
uniform vec3 u_sh[9];
uniform vec3 u_skyColor;
uniform vec3 u_horizonColor;
uniform vec3 u_groundColor;
uniform float u_iblIntensity;
uniform float u_iblEnabled;

out vec4 o_color;

vec3 srgbToLinear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

float distributionGGX(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 1e-7);
}

float geometrySchlickGGX(float NdotX, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotX / (NdotX * (1.0 - k) + k);
}

float geometrySmith(float NdotV, float NdotL, float roughness) {
    return geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(NdotL, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

// Fraction of the fragment that is lit (1 = fully lit), 3x3 PCF with a slope bias.
float shadowFactor(vec3 worldPos, float NdotL) {
    vec4 lightClip = u_shadowVp * vec4(worldPos, 1.0);
    vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    if (proj.z > 1.0 || proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0) {
        return 1.0;
    }
    float bias = max(0.0025 * (1.0 - NdotL), 0.0008);
    vec2 texel = 1.0 / vec2(textureSize(u_shadowMap, 0));
    float lit = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            float depth = texture(u_shadowMap, proj.xy + vec2(float(dx), float(dy)) * texel).r;
            lit += (proj.z - bias) <= depth ? 1.0 : 0.0;
        }
    }
    return lit / 9.0;
}

// Image-based lighting: procedural sky radiance + SH diffuse irradiance.
vec3 skyRadiance(vec3 dir) {
    float t = dir.y;
    vec3 c = t >= 0.0 ? mix(u_horizonColor, u_skyColor, t) : mix(u_horizonColor, u_groundColor, -t);
    return c * u_iblIntensity;
}

vec3 shIrradiance(vec3 n) {
    return u_sh[0] * 0.282095
        + u_sh[1] * (0.488603 * n.y)
        + u_sh[2] * (0.488603 * n.z)
        + u_sh[3] * (0.488603 * n.x)
        + u_sh[4] * (1.092548 * n.x * n.y)
        + u_sh[5] * (1.092548 * n.y * n.z)
        + u_sh[6] * (0.315392 * (3.0 * n.z * n.z - 1.0))
        + u_sh[7] * (1.092548 * n.x * n.z)
        + u_sh[8] * (0.546274 * (n.x * n.x - n.y * n.y));
}

vec3 fresnelSchlickRoughness(float cosTheta, vec3 F0, float roughness) {
    return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - cosTheta, 5.0);
}

// Tangent-space normal to world, with the frame derived per pixel from the
// screen-space derivatives of the world position and the UVs (Mikkelsen's
// cotangent frame). No TANGENT vertex attribute: nothing in this toolkit
// produces one, and a camera-facing billboard has no meaningful stored
// tangent anyway. dFdx/dFdy are core in GLSL 330 and in GLSL ES 3.00, so
// this stays inside the ADR 001 subset.
vec3 normalMapToWorld(vec3 tangentNormal, float scale) {
    vec3 Ng = normalize(v_normal);
    vec3 n = vec3(tangentNormal.xy * scale, tangentNormal.z);
    vec3 dp1 = dFdx(v_worldPos);
    vec3 dp2 = dFdy(v_worldPos);
    vec2 duv1 = dFdx(v_uv);
    vec2 duv2 = dFdy(v_uv);
    vec3 dp2perp = cross(dp2, Ng);
    vec3 dp1perp = cross(Ng, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    // The floor is load-bearing, not defensive: inversesqrt(0) is inf and
    // 0 * inf is NaN, which would poison the whole fragment. With it, a
    // degenerate frame (no UV gradient) leaves T and B at zero and the
    // result collapses to Ng, which is also what scale = 0 produces.
    float invMax = inversesqrt(max(dot(T, T), max(dot(B, B), 1e-12)));
    // The bitangent enters NEGATED, and that sign is the whole convention.
    //
    // B as derived above runs along increasing v, and v runs DOWN an image.
    // Feeding it in unnegated makes the green channel mean "tilt down", which
    // is the DirectX convention, while the scale semantics this material
    // documents are glTF's, and glTF mandates green up. A surface would then
    // be lit from the wrong vertical direction by a toolkit that says it
    // follows the opposite standard.
    //
    // The failure has no visual signature of its own: it does not look like a
    // texture bug, it looks like the lights are in the wrong place. It was
    // found by rendering a hemisphere under a light pointing straight down and
    // measuring which half came out bright.
    return normalize(mat3(T * invMax, -B * invMax, Ng) * n);
}

// A world-space direction expressed in the same tangent frame the helper
// above shades in: x toward the right of the image, y toward its TOP (the
// same negation, for the same reason), z out of the surface. Read by
// application-supplied diffuse responses that have to know where a light is
// relative to the surface rather than merely how much of it lands.
//
// The axes are NORMALIZED here and deliberately are not above. That helper
// divides both by the LARGER of the two, so a surface drawn out along one
// axis has its normal map drawn out with it: the map is painted in uv space
// and stretches with the geometry. A direction has no length to stretch:
// "which way is right" of a quad pulled into a streak is still right, and
// carrying the stretch into this frame would skew it, making a fixed light
// appear to swing as the geometry lengthened.
vec3 worldToTangent(vec3 v) {
    vec3 Ng = normalize(v_normal);
    vec3 dp1 = dFdx(v_worldPos);
    vec3 dp2 = dFdy(v_worldPos);
    vec2 duv1 = dFdx(v_uv);
    vec2 duv2 = dFdy(v_uv);
    vec3 dp2perp = cross(dp2, Ng);
    vec3 dp1perp = cross(Ng, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    // The floors are load-bearing for the same reason as above: a degenerate
    // frame must collapse to the geometric normal, not poison the fragment.
    float tl = max(length(T), 1e-6);
    float bl = max(length(B), 1e-6);
    return vec3(dot(v, T / tl), dot(v, -B / bl), dot(v, Ng));
}

void main() {
    vec3 N = normalMapToWorld(((texture(u_normalTex, v_uv).xyz * 2.0) - 1.0), u_mr.z);
    vec3 V = normalize(u_cameraPos.xyz - v_worldPos);
    float NdotV = max(dot(N, V), 1e-4);

    vec4 baseColor = vec4(((srgbToLinear(u_baseColor.rgb) * srgbToLinear(texture(u_baseColorTex, v_uv).rgb)) * v_color.rgb), ((u_baseColor.a * texture(u_baseColorTex, v_uv).a) * v_color.a));
    vec3 albedo = baseColor.rgb;
    float metallic = clamp(u_mr.x, 0.0, 1.0);
    float roughness = clamp(u_mr.y, 0.04, 1.0);
    vec3 emissive = (u_emissiveHasTex.xyz * v_color.rgb);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);

    vec3 Lo = vec3(0.0);
    int count = u_lightCount.x;
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= count) {
            break;
        }
        Light light = u_lights[i];
        int type = int(light.dirType.w + 0.5);

        vec3 L;
        float attenuation = 1.0;
        if (type == 0) {
            L = normalize(light.dirType.xyz);
        } else {
            vec3 toLight = light.posRange.xyz - v_worldPos;
            float dist = length(toLight);
            L = toLight / max(dist, 1e-4);
            attenuation = 1.0 / max(dist * dist, 1e-4);
            float range = light.posRange.w;
            if (range > 0.0) {
                float win = clamp(1.0 - pow(dist / range, 4.0), 0.0, 1.0);
                attenuation *= win * win;
            }
            if (type == 2) {
                float cosAngle = dot(-L, normalize(light.dirType.xyz));
                float t = clamp((cosAngle - light.spot.y) / max(light.spot.x - light.spot.y, 1e-4), 0.0, 1.0);
                attenuation *= t * t;
            }
        }
        vec3 radiance = light.colorIntensity.xyz * light.colorIntensity.w * attenuation;

        vec3 H = normalize(V + L);
        float NdotL = max(dot(N, L), 0.0);
        float NdotH = max(dot(N, H), 0.0);
        float VdotH = max(dot(V, H), 0.0);

        float D = distributionGGX(NdotH, roughness);
        float G = geometrySmith(NdotV, NdotL, roughness);
        vec3 F = fresnelSchlick(VdotH, F0);

        vec3 specular = (D * G * F) / max(4.0 * NdotV * NdotL, 1e-4);
        vec3 kd = (vec3(1.0) - F) * (1.0 - metallic);
        float shadow = 1.0;
        if (type == 0 && u_shadowStrength > 0.0) {
            shadow = mix(1.0, shadowFactor(v_worldPos, NdotL), u_shadowStrength);
        }
        Lo += (kd * albedo / PI + specular) * radiance * NdotL * shadow;
    }

    vec3 ambientLight;
    if (u_iblEnabled > 0.5) {
        vec3 Fr = fresnelSchlickRoughness(NdotV, F0, roughness);
        vec3 kdIbl = (vec3(1.0) - Fr) * (1.0 - metallic);
        vec3 irradiance = max(shIrradiance(N), vec3(0.0));
        vec3 diffuseIbl = kdIbl * albedo * irradiance;
        vec3 reflection = mix(skyRadiance(reflect(-V, N)), irradiance, roughness);
        ambientLight = diffuseIbl + reflection * Fr;
    } else {
        ambientLight = u_ambient.xyz * albedo;
    }
    vec3 color = ambientLight + Lo + emissive;

    // Linear, premultiplied, scene-referred, with no tonemap and no encode:
    // the target's contract (ADR 004); the composite applies the
    // display transform when this target is drawn.
    float alpha = baseColor.a;
    o_color = vec4(color * alpha, alpha);
}
