package limn.print.internal;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The outlines of every glyph a document's pages draw, read from the backend while the pages are
 * recorded, on the UI thread where the backend's faces live, so that the PDF can be written on a
 * worker without asking the backend anything (ADR 078 §10).
 *
 * <p>An outline is kept once per face and glyph, in the face's own units, y up; a cluster drawn
 * from its characters (a colour emoji, a run whose face was evicted) is kept as the pieces the
 * backend answered for it. What the backend could not answer is kept as unknown, so it is asked
 * once.
 */
public final class GlyphBank {

    private static final System.Logger LOG = System.getLogger(GlyphBank.class.getName());

    /** A face's facts, as the backend gave them when its first glyph was kept. */
    record Face(int faceId, int unitsPerEm, String name, boolean syntheticBold) {
    }

    /** A glyph's outline: verbs and the points they take, in font units, y up. */
    record Outline(byte[] verbs, float[] points) {
        static final byte MOVE = 0;
        static final byte LINE = 1;
        static final byte QUAD = 2;
        static final byte CUBIC = 3;
        static final byte CLOSE = 4;

        boolean isEmpty() {
            return verbs.length == 0;
        }
    }

    private record ClusterKey(String characters, Font font) {
    }

    private final GlyphOutlines source;
    private final Map<Integer, Face> faces = new HashMap<>();
    private final Map<Long, Outline> outlines = new HashMap<>();
    private final Map<ClusterKey, List<GlyphOutlines.Piece>> clusters = new HashMap<>();
    private boolean warned;

    /** @param source the backend's outlines; {@link GlyphOutlines#NONE} for none */
    public GlyphBank(GlyphOutlines source) {
        this.source = source;
    }

    /**
     * Keeps whatever {@code text} needs that is not kept yet. UI thread.
     *
     * @param text a line about to be recorded
     */
    void collect(ShapedText text) {
        String value = text.text();
        for (ShapedText.Run run : text.runs()) {
            boolean known = face(run.faceId()) != null;
            for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                int glyph = text.glyphId(g);
                if (glyph == ShapedText.NO_GLYPH || !known) {
                    int from = text.glyphCluster(g);
                    int to = text.caretIndex(text.caretOrdinal(from) + 1);
                    if (to > from && to <= value.length()) {
                        for (GlyphOutlines.Piece piece : cluster(value.substring(from, to), text.font())) {
                            if (piece instanceof GlyphOutlines.Glyph drawn && face(drawn.faceId()) != null) {
                                outline(drawn.faceId(), drawn.glyphId());
                            }
                        }
                    }
                } else {
                    outline(run.faceId(), glyph);
                }
            }
        }
    }

    /** @return the face's facts, or null when the backend does not know it */
    Face face(int faceId) {
        Face kept = faces.get(faceId);
        if (kept != null) {
            return kept.unitsPerEm() > 0 ? kept : null;
        }
        int upem = source.unitsPerEm(faceId);
        Face made = new Face(faceId, upem, source.faceName(faceId), upem > 0 && source.isSyntheticBold(faceId));
        faces.put(faceId, made);
        if (upem <= 0 && !warned) {
            warned = true;
            LOG.log(System.Logger.Level.WARNING, "text whose face cannot be outlined is drawn from its "
                    + "characters' pictures where there are any, and left out where there are none");
        }
        return upem > 0 ? made : null;
    }

    /** @return the glyph's outline, read from the backend the first time */
    Outline outline(int faceId, int glyphId) {
        long key = ((long) faceId << 32) | (glyphId & 0xFFFFFFFFL);
        Outline kept = outlines.get(key);
        if (kept != null) {
            return kept;
        }
        Collector collector = new Collector();
        source.outline(faceId, glyphId, collector);
        Outline made = collector.build();
        outlines.put(key, made);
        return made;
    }

    /** @return the pieces that draw a glyphless cluster, asked of the backend the first time */
    List<GlyphOutlines.Piece> cluster(String characters, Font font) {
        return clusters.computeIfAbsent(new ClusterKey(characters, font),
                key -> List.copyOf(source.cluster(key.characters(), key.font())));
    }

    /** @return an outline kept earlier, for the writer on its own thread; empty when none was */
    Outline kept(int faceId, int glyphId) {
        Outline kept = outlines.get(((long) faceId << 32) | (glyphId & 0xFFFFFFFFL));
        return kept == null ? new Outline(new byte[0], new float[0]) : kept;
    }

    /** @return the face kept earlier, for the writer, or null for one the backend did not know */
    Face keptFace(int faceId) {
        Face kept = faces.get(faceId);
        return kept != null && kept.unitsPerEm() > 0 ? kept : null;
    }

    /** @return the pieces kept for a cluster, for the writer; empty when none were */
    List<GlyphOutlines.Piece> keptCluster(String characters, Font font) {
        List<GlyphOutlines.Piece> kept = clusters.get(new ClusterKey(characters, font));
        return kept == null ? List.of() : kept;
    }

    /** Receives an outline and keeps it compact. */
    private static final class Collector implements Path2D.Visitor {
        private byte[] verbs = new byte[32];
        private float[] points = new float[128];
        private int verbCount;
        private int pointCount;

        private void verb(byte verb) {
            if (verbCount == verbs.length) {
                verbs = Arrays.copyOf(verbs, verbs.length * 2);
            }
            verbs[verbCount++] = verb;
        }

        private void point(float x, float y) {
            if (pointCount + 2 > points.length) {
                points = Arrays.copyOf(points, points.length * 2);
            }
            points[pointCount++] = x;
            points[pointCount++] = y;
        }

        @Override
        public void moveTo(float x, float y) {
            verb(Outline.MOVE);
            point(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            verb(Outline.LINE);
            point(x, y);
        }

        @Override
        public void quadTo(float cx, float cy, float x, float y) {
            verb(Outline.QUAD);
            point(cx, cy);
            point(x, y);
        }

        @Override
        public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
            verb(Outline.CUBIC);
            point(c1x, c1y);
            point(c2x, c2y);
            point(x, y);
        }

        @Override
        public void close() {
            verb(Outline.CLOSE);
        }

        Outline build() {
            return new Outline(Arrays.copyOf(verbs, verbCount), Arrays.copyOf(points, pointCount));
        }
    }
}
