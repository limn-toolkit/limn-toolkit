package limn.demo.site;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One row of the gallery: what to render, what to call it, where its code sample comes from,
 * and, for the few where the movement is the point, how a pointer uses it.
 *
 * @param id       stable slug: the site's URL fragment and the PNG's file name. Renaming
 *                 one breaks a link, so treat it as published.
 * @param title    English display name; the site's own key, translated like any other
 * @param region   the {@code #region} marker whose text is this entry's code sample. The
 *                 build fails if no source file carries it, which is what stops a deleted
 *                 scene from shipping as an empty code block.
 * @param builder  produces a FRESH scene per call; a scene is bound to a window and
 *                 carries focus and layout state, so reusing one across two palettes would
 *                 capture the second with the first's leftovers
 * @param film     the pointer's script, or null for an entry that is only ever a still.
 *                 It is a function of the built scene rather than a value, because a script
 *                 aims at widgets and the widgets do not exist until the scene is built.
 *                 It is here rather than inside the builder so that the published code
 *                 sample stays the component's code, with none of the harness in it.
 */
record GalleryEntry(String id, String title, String region,
                    Supplier<GalleryScenes.Built> builder,
                    Function<GalleryScenes.Built, Motion> film) {

    /** An entry with no film: the still is the whole picture. */
    GalleryEntry(String id, String title, String region, Supplier<GalleryScenes.Built> builder) {
        this(id, title, region, builder, null);
    }
}
