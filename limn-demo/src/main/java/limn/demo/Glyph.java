package limn.demo;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.function.Function;

/**
 * An icon drawn in one of the theme's tones, read when it paints, so it follows a theme change:
 * a missed call in the danger tone, a folder in the primary one. Decorative: the row beside it
 * says what it means in words.
 */
final class Glyph extends Widget<Glyph> {

    private final float size;
    private Icon icon;
    private Function<Theme, Color> tone = Theme::textMuted;

    Glyph(float size) {
        this.size = size;
        setAccessibleIgnored(true);
    }

    /** Shows {@code icon} in the tone {@code tone} picks from the theme in effect. */
    Glyph bind(Icon icon, Function<Theme, Color> tone) {
        this.icon = icon;
        this.tone = tone;
        invalidate();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(size, size);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (icon != null) {
            Theme theme = Theme.of(this);
            icon.paint(canvas, (width() - size) / 2, (height() - size) / 2, size, tone.apply(theme),
                    theme.isDark());
        }
    }
}
