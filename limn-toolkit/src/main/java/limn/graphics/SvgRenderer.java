package limn.graphics;

/**
 * SPI that draws SVG: opens a document from SVG text, draws it into pixels at any size, and lets an
 * attribute of one of its elements be changed between two drawings, which is how an animated SVG
 * is played. The backend installs one at startup ({@link SvgIcon#installRenderer}); a headless test
 * can install its own. No AWT: the backend's lives beside its other natives.
 *
 * <p>The toolkit reads every SVG itself before a renderer sees it: it parses the XML, refuses what
 * is damaged or too large, takes the animations out to play them itself, and hands the renderer
 * text that refers to nothing outside itself. A renderer is never given a path or an address.
 *
 * <p><b>Called from any thread, including several at once</b>, as {@link SvgIcon#imageAsync} draws
 * on the worker pool. A {@link Document} is used by one thread at a time; two documents may be
 * used at once.
 */
public interface SvgRenderer {

    /**
     * Opens a document.
     *
     * @param svg SVG text in UTF-8, read and never written
     * @return the document, which the caller closes
     * @throws limn.io.MalformedContentException if the text is no SVG, or one with no size
     */
    Document open(byte[] svg);

    /**
     * Makes a face available to SVG text by its family name, beside the system's installed fonts.
     * Nothing happens by default: a renderer that draws no text has no use for one.
     *
     * @param family the family name a file asks for
     * @param bold   whether the face is the family's bold
     * @param italic whether it is its italic
     * @param data   the font file's bytes, TrueType or OpenType
     * @return whether the renderer took it
     */
    default boolean addFont(String family, boolean bold, boolean italic, byte[] data) {
        return false;
    }

    /** One SVG, opened: measured, drawn and changed until it is closed. */
    interface Document extends AutoCloseable {

        /** @return its width in its own units, above 0 */
        float width();

        /** @return its height in its own units, above 0 */
        float height();

        /**
         * Changes one attribute of the element with that id, for the drawings after this.
         *
         * @param id    the element's id
         * @param name  the attribute's name
         * @param value its new value, as it would be written in the file
         * @return whether the document has such an element
         */
        boolean setAttribute(String id, String name, String value);

        /**
         * Draws the whole document scaled to {@code width} by {@code height} pixels.
         *
         * @param width  at least 1
         * @param height at least 1
         * @return the picture, straight-alpha RGBA8 with row 0 at the top
         */
        Image render(int width, int height);

        /** Lets the document go. Idempotent. */
        @Override
        void close();
    }
}
