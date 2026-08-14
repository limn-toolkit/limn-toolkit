package limn.backend;

/**
 * A snapshot of GPU resources the renderer is holding: currently the number of
 * GL textures resident (glyph-atlas pages + cached image textures) and their
 * estimated size in bytes. Exposed for on-screen diagnostics (a performance
 * monitor); cheap to read (it counts CPU-side bookkeeping, touches no GL state).
 */
public record RenderStats(int textures, long textureBytes) {

    public static final RenderStats EMPTY = new RenderStats(0, 0);

    /** @return the component-wise sum, for aggregating across windows. */
    public RenderStats plus(RenderStats other) {
        return new RenderStats(textures + other.textures, textureBytes + other.textureBytes);
    }
}
