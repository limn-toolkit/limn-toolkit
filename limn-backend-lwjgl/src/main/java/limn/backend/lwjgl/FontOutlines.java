package limn.backend.lwjgl;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.Font;
import limn.graphics.Path2D;
import org.lwjgl.util.harfbuzz.hb_draw_close_path_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_cubic_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_line_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_move_to_func_t;
import org.lwjgl.util.harfbuzz.hb_draw_quadratic_to_func_t;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_make_immutable;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_set_close_path_func;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_set_cubic_to_func;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_set_line_to_func;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_set_move_to_func;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_draw_funcs_set_quadratic_to_func;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_draw_glyph;

/**
 * The glyph outlines of the faces this backend's font store shapes with, for a printed page that
 * writes text as text.
 *
 * <p>HarfBuzz draws each outline, through the font the shaper already made for the face: the face
 * that shaped the run is the face that is outlined, a TrueType, CFF or CFF2 outline alike. Where
 * HarfBuzz did not load, stb_truetype reads the same outline (the default instance of a variable
 * face, which is the instance this toolkit shapes and draws anyway, since it sets no axis).
 *
 * <p>The draw callbacks are made once and live as long as the process: HarfBuzz keeps a pointer
 * to them in its immutable function table, and the function table is shared by every face. UI
 * thread only, as the font store is; the visitor being filled is a field for that reason.
 */
final class FontOutlines implements GlyphOutlines {

    private final FontStore store;

    FontOutlines(FontStore store) {
        this.store = store;
    }

    @Override
    public int unitsPerEm(int faceId) {
        StbFont face = store.faceById(faceId);
        return face == null ? 0 : face.unitsPerEm();
    }

    @Override
    public String faceName(int faceId) {
        StbFont face = store.faceById(faceId);
        return face == null ? "" : face.name();
    }

    @Override
    public boolean isSyntheticBold(int faceId) {
        StbFont face = store.faceById(faceId);
        return face != null && face.isSyntheticBold();
    }

    @Override
    public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
        StbFont face = store.faceById(faceId);
        if (face == null) {
            return false;
        }
        HarfBuzzShaper.Handle shaper = face.shaper();
        if (shaper != null && Draw.ready()) {
            Draw.into(shaper.font(), glyphId, out);
        } else {
            face.outline(glyphId, out);
        }
        return true;
    }

    @Override
    public java.util.concurrent.CompletionStage<Void> settled() {
        return store.whenSettled();
    }

    @Override
    public List<Piece> cluster(String characters, Font font) {
        // The walk GlCanvas.drawClusterCharacters makes, in ems instead of pixels, so the page puts
        // the same pictures and glyphs where the screen does.
        StbFont primary = store.resolve(font);
        float size = Math.max(1f, font.size());
        List<Piece> pieces = new ArrayList<>(2);
        for (int i = 0; i < characters.length(); ) {
            int cp = characters.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || StbFont.isZeroWidthFormat(cp)) {
                continue;
            }
            int primaryGlyph = primary.glyphIndex(cp);
            if (primaryGlyph == 0) {
                ColorEmojiFont.Emoji colored = store.colorEmojiGlyph(cp);
                if (colored != null) {
                    float advance = (float) store.colorEmojiAdvance(cp, size) / size;
                    pieces.add(new Picture(colored.image(), colored.width(), colored.height(),
                            colored.top(), advance));
                    continue;
                }
            }
            StbFont face = primaryGlyph != 0 ? primary : store.faceForCodepoint(primary, cp);
            int index = primaryGlyph != 0 ? primaryGlyph : face.glyphIndex(cp);
            pieces.add(new Glyph(store.faceId(face), index, face.glyphAdvance(index, size) / size));
        }
        return pieces;
    }

    /**
     * HarfBuzz's draw functions, made once. The callbacks write into {@link #target}, which is set
     * for the length of one {@code hb_font_draw_glyph}: the calls are synchronous and UI-thread
     * confined, so one field is enough and no user data pointer is needed.
     */
    private static final class Draw {

        private static long funcs;
        private static boolean tried;
        private static Path2D.Visitor target;

        // Held so the native trampolines are never collected while HarfBuzz points at them.
        @SuppressWarnings("unused")
        private static Object[] keep;

        static boolean ready() {
            if (!tried) {
                tried = true;
                if (HarfBuzzShaper.isAvailable()) {
                    try {
                        make();
                    } catch (Throwable failure) {
                        System.getLogger(FontOutlines.class.getName()).log(System.Logger.Level.WARNING,
                                "HarfBuzz cannot draw outlines; stb_truetype reads them instead", failure);
                        funcs = 0;
                    }
                }
            }
            return funcs != 0;
        }

        private static void make() {
            long made = hb_draw_funcs_create();
            hb_draw_move_to_func_t move = hb_draw_move_to_func_t.create(
                    (dfuncs, data, st, x, y, user) -> target.moveTo(x, y));
            hb_draw_line_to_func_t line = hb_draw_line_to_func_t.create(
                    (dfuncs, data, st, x, y, user) -> target.lineTo(x, y));
            hb_draw_quadratic_to_func_t quad = hb_draw_quadratic_to_func_t.create(
                    (dfuncs, data, st, cx, cy, x, y, user) -> target.quadTo(cx, cy, x, y));
            hb_draw_cubic_to_func_t cubic = hb_draw_cubic_to_func_t.create(
                    (dfuncs, data, st, c1x, c1y, c2x, c2y, x, y, user) -> target.cubicTo(c1x, c1y, c2x, c2y, x, y));
            hb_draw_close_path_func_t close = hb_draw_close_path_func_t.create(
                    (dfuncs, data, st, user) -> target.close());
            hb_draw_funcs_set_move_to_func(made, move, 0, null);
            hb_draw_funcs_set_line_to_func(made, line, 0, null);
            hb_draw_funcs_set_quadratic_to_func(made, quad, 0, null);
            hb_draw_funcs_set_cubic_to_func(made, cubic, 0, null);
            hb_draw_funcs_set_close_path_func(made, close, 0, null);
            hb_draw_funcs_make_immutable(made);
            keep = new Object[] {move, line, quad, cubic, close};
            funcs = made;
        }

        static void into(long font, int glyph, Path2D.Visitor out) {
            target = out;
            try {
                hb_font_draw_glyph(font, glyph, funcs, 0);
            } finally {
                target = null;
            }
        }
    }
}
