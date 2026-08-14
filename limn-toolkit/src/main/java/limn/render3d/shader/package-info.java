/**
 * A neutral, typed shader IR: a material's surface is a
 * {@link limn.render3d.shader.SurfaceOutputs} built from
 * {@link limn.render3d.shader.Expr} nodes, and a backend's code generator walks that DAG
 * to emit its own shading language, so the toolkit ships no GLSL, and a new target means
 * a new generator rather than new materials.
 * {@link limn.render3d.shader.StandardSurface} expresses the built-in metallic-roughness
 * surface in this IR, and is the model for writing a custom one.
 */
package limn.render3d.shader;
