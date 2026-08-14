package limn.render3d.shader;

/**
 * A shading-language target the IR can be compiled to. The two GLSL profiles share
 * one body (the GLSL 330 ∩ ES 3.00 subset) and differ only in the
 * version/precision header, so a portable IR compiles to both unchanged. SPIR-V
 * and MSL are reserved for later.
 */
public enum TargetProfile {
    GLSL_330("#version 330 core\n"),
    GLSL_ES_300("#version 300 es\nprecision highp float;\nprecision highp int;\n");

    /** The version line (and, for ES, precision qualifiers) prepended to a shader. */
    public final String header;

    TargetProfile(String header) {
        this.header = header;
    }
}
