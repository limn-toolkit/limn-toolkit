/**
 * A retained scene over the imperative pass: {@link limn.render3d.scene.Scene3D} traverses
 * a {@link limn.render3d.scene.Node} tree of {@link limn.render3d.scene.MeshNode}s and
 * {@link limn.render3d.scene.LightNode}s, composes world transforms, gathers the lights,
 * frustum-culls and issues the same draws a caller could issue by hand. Keep a hierarchy
 * here when transforms nest; the guarantee worth knowing is that a declarative scene
 * renders identically to the equivalent imperative draws.
 */
package limn.render3d.scene;
