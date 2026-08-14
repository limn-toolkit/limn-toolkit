/**
 * Render-to-texture 3D: {@link limn.render3d.Graphics3D} is both the facade a caller uses
 * inside a frame and the SPI the backend installs, and around it sit the neutral resources
 * a scene is made of: {@link limn.render3d.MeshData} and {@link limn.render3d.TextureData}
 * built on any thread, their uploaded twins {@link limn.render3d.GpuMesh} and
 * {@link limn.render3d.GpuTexture}, {@link limn.render3d.Camera} with its controllers,
 * {@link limn.render3d.Material}, {@link limn.render3d.Light}, and the
 * {@link limn.render3d.RenderPass} draws are issued into. A pass renders into a
 * {@link limn.render3d.RenderTarget} the 2D pipeline composites like any other texture,
 * which is why a 3D viewport is just a widget.
 */
package limn.render3d;
