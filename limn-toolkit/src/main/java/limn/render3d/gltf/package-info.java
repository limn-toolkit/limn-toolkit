/**
 * glTF 2.0 loading: {@link limn.render3d.gltf.GltfLoader} reads {@code .gltf} or binary
 * {@code .glb} into a neutral {@link limn.render3d.gltf.GltfModel} of meshes, materials
 * and node transforms (no GPU and no third-party parser), so the reading and decoding
 * can run on a worker (the {@code loadAsync} forms are cancellable and report progress)
 * and the upload happens later, inside a frame.
 */
package limn.render3d.gltf;
