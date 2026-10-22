package limn.backend.spi;

import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Path2D;

import java.util.List;
import java.util.Objects;

/**
 * The outlines of the glyphs a {@link limn.graphics.ShapedText} names, for a writer that draws text
 * as text rather than as pixels: a PDF page keeps each glyph as its outline and maps it back to the
 * characters it stands for (ADR 078 §7.2).
 *
 * <p>A shaped run names its face by a number only the ruler that shaped it understands
 * ({@link limn.graphics.ShapedText.Run#faceId()}); the backend that installed that ruler is the one
 * that can turn the number back into a face, so it implements this. Optional: a backend that has
 * none leaves it out, and a document then draws a run it cannot outline from its characters'
 * pictures where it has them, and not at all where it has none, with a warning.
 *
 * <p><b>UI thread only</b>, as the ruler is: the faces are the ruler's.
 */
public interface GlyphOutlines {

    /**
     * @param faceId a face number from a shaped run
     * @return the face's units per em, or 0 when this source does not know the face (one evicted
     *         since the run was shaped, or one from another ruler)
     */
    int unitsPerEm(int faceId);

    /**
     * @param faceId a face number from a shaped run
     * @return the face's name, for the document's font resources; {@code ""} for an unknown face
     */
    String faceName(int faceId);

    /**
     * @param faceId a face number from a shaped run
     * @return whether the face is drawn bold by thickening a regular one, which a writer reproduces
     *         by stroking each outline as well as filling it
     */
    boolean isSyntheticBold(int faceId);

    /**
     * Draws a glyph's outline in the face's own units, y growing <b>up</b> from the baseline, as
     * the font file has it: contours whose winding is the non-zero rule's, curves as curves.
     *
     * @param faceId  a face number from a shaped run
     * @param glyphId a glyph of that face
     * @param out     receives the outline
     * @return whether the face is known; a known glyph with no ink (a space) draws nothing and
     *         answers true
     */
    boolean outline(int faceId, int glyphId, Path2D.Visitor out);

    /**
     * What draws the characters of a cluster a shaped run left without a glyph
     * ({@link limn.graphics.ShapedText#NO_GLYPH}): a colour emoji's picture, or a glyph of the face
     * the screen would fall back to, each placed one after the other from the cluster's x.
     *
     * @param characters the cluster's characters
     * @param font       the run's font
     * @return the pieces in order; empty when nothing draws them
     */
    List<Piece> cluster(String characters, Font font);

    /**
     * When no face this source shapes with is still loading. A face the shaper asked for while a
     * document was recorded (a script's fallback, loaded in the background on its first need) was
     * drawn as boxes for that recording; a document waits on this and records its pages again,
     * where a window simply draws its next frame.
     *
     * @return a stage completed on the UI thread once nothing is loading; completed already when
     *         nothing is, which the default answers
     */
    default java.util.concurrent.CompletionStage<Void> settled() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** One thing a glyphless cluster draws. */
    sealed interface Piece permits Glyph, Picture {
    }

    /**
     * A glyph drawn in place of a character.
     *
     * @param faceId  its face, a number {@link #outline} understands
     * @param glyphId the glyph
     * @param advance how far the next piece starts after it, in ems of the run's font
     */
    record Glyph(int faceId, int glyphId, float advance) implements Piece {
    }

    /**
     * A picture drawn in place of a character: a colour emoji's strike.
     *
     * @param image   the picture
     * @param width   its width, in ems of the run's font
     * @param height  its height, in ems
     * @param top     how far its top stands above the baseline, in ems
     * @param advance how far the next piece starts after it, in ems
     */
    record Picture(Image image, float width, float height, float top, float advance) implements Piece {
        public Picture {
            Objects.requireNonNull(image, "image");
        }
    }

    /**
     * Installs a backend's source where a document reads it; called by
     * {@link BackendServices#install()}.
     *
     * @param outlines the backend's source
     */
    static void install(GlyphOutlines outlines) {
        limn.print.internal.Outlines.install(outlines);
    }

    /**
     * Takes back a source installed by {@link #install}, at the backend's shutdown; another one
     * installed since stays.
     *
     * @param outlines the source that was installed
     */
    static void uninstall(GlyphOutlines outlines) {
        limn.print.internal.Outlines.uninstall(outlines);
    }

    /** A source that knows no face. */
    GlyphOutlines NONE = new GlyphOutlines() {
        @Override
        public int unitsPerEm(int faceId) {
            return 0;
        }

        @Override
        public String faceName(int faceId) {
            return "";
        }

        @Override
        public boolean isSyntheticBold(int faceId) {
            return false;
        }

        @Override
        public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
            return false;
        }

        @Override
        public List<Piece> cluster(String characters, Font font) {
            return List.of();
        }
    };
}
