package limn.demo;

import limn.components.Checkbox;
import limn.components.ColorPicker;
import limn.components.ColorPickerButton;
import limn.components.DisplayMode;
import limn.components.Elevation;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Separator;
import limn.components.Surface;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * Colours as a preferences page: a picker inline for a tag's colour, and a form of swatches for
 * the brand colours, each of which raises the picker in a dialog. Below them, how a surface is
 * lifted at each elevation, and a preview of a palette of the application's own built from the
 * brand colours with {@link Theme#builder}.
 */
final class ColorPickerScene {

    /** The strings of this page, ready for translation. */
    static final class Text {
        static final I18nString BRAND = new I18nString("showcase.colours.brand", "Brand colours");
        static final I18nString TAG = new I18nString("showcase.colours.tag", "Tag colour");
        static final I18nString PRIMARY = new I18nString("showcase.colours.primary", "Primary");
        static final I18nString ACCENT = new I18nString("showcase.colours.accent", "Accent");
        static final I18nString BACKGROUND = new I18nString("showcase.colours.background", "Background");
        static final I18nString SYSTEM_ACCENT = new I18nString("showcase.colours.systemAccent",
                "Use the system's accent colour");
        static final I18nString IN_WINDOW = new I18nString("showcase.colours.inWindow",
                "Open pickers inside this window");
        static final I18nString SET = new I18nString("showcase.colours.set", "{0}: {1}");

        static final I18nString APPEARANCE = new I18nString("showcase.colours.appearance", "Appearance");
        static final I18nString PAGE = new I18nString("showcase.colours.page", "Page");
        static final I18nString CARD = new I18nString("showcase.colours.card", "Card");
        static final I18nString MENU = new I18nString("showcase.colours.menu", "Menu");
        static final I18nString DIALOG = new I18nString("showcase.colours.dialog", "Dialog");
        static final I18nString PREVIEW = new I18nString("showcase.colours.preview", "Brand theme preview");

        private Text() {
        }
    }

    /** The width the inline picker is given: a panel's, not the window's. */
    private static final float PICKER_WIDTH = 320;

    private ColorPickerScene() {
    }

    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(content())));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    static Widget<?> content() {
        Column page = Page.column();
        page.add(Page.firstSection(Text.BRAND));

        // The inline picker, opening on its HSV notation and keeping its alpha rail.
        ColorPicker tag = new ColorPicker();
        tag.setInitialColor(Color.rgb(0x3B82F6));
        tag.setFormat(ColorPicker.Format.HSV);
        tag.onCommit(colour -> Page.report(Text.SET.format(Text.TAG.get(), colour.toHex())));

        BrandPreview preview = new BrandPreview();
        ColorPickerButton primary = swatch(Text.PRIMARY, Color.rgb(0x2563EB), preview);
        ColorPickerButton accent = swatch(Text.ACCENT, Color.rgb(0xF59E0B), preview);
        ColorPickerButton background = swatch(Text.BACKGROUND, Color.rgb(0xF8FAFC), preview);
        preview.from(primary, accent, background);

        // Following the system's accent leaves the application's own one there but out of reach.
        Checkbox systemAccent = new Checkbox(Text.SYSTEM_ACCENT).setChecked(true);
        accent.setEnabled(false);
        systemAccent.onChange(on -> accent.setEnabled(!on));

        Checkbox inWindow = new Checkbox(Text.IN_WINDOW);
        inWindow.onChange(on -> {
            DisplayMode mode = on ? DisplayMode.IN_SCENE : DisplayMode.NATIVE_WINDOW;
            for (ColorPickerButton each : List.of(primary, accent, background)) {
                each.setPickerDisplayMode(mode);
            }
        });

        Column form = new Column();
        form.gap(Page.GAP).crossAlignment(Flex.CrossAlignment.START);
        form.add(Page.field(Text.PRIMARY, primary));
        form.add(Page.field(Text.ACCENT, accent));
        form.add(systemAccent);
        form.add(Page.field(Text.BACKGROUND, background));
        form.add(inWindow);

        Row brand = new Row();
        brand.gap(24).crossAlignment(Flex.CrossAlignment.STRETCH);
        brand.add(Page.field(Text.TAG, tag, new SizedBox(PICKER_WIDTH, SizedBox.UNSET, tag)));
        brand.add(Separator.vertical());
        brand.add(form);
        page.add(Page.narrow(PICKER_WIDTH + 24 + 1 + 24 + 280, brand));

        page.add(Page.section(Text.APPEARANCE));
        Row levels = Page.row(24,
                level(Text.PAGE, Elevation.FLAT),
                level(Text.CARD, Elevation.RAISED),
                level(Text.MENU, Elevation.OVERLAY),
                level(Text.DIALOG, Elevation.MODAL));
        page.add(new Padding(new Insets(4, 0, 16, 0), levels));
        page.add(Page.field(Text.PREVIEW, preview, Page.narrow(BrandPreview.WIDTH, preview)));
        return page;
    }

    /** A swatch that opens the picker without alpha, and reports the colour it was given. */
    private static ColorPickerButton swatch(I18nString name, Color initial, BrandPreview preview) {
        ColorPickerButton swatch = new ColorPickerButton(initial);
        swatch.setAlphaEnabled(false);
        swatch.setDialogTitle(name);
        swatch.onChange(colour -> {
            preview.rebuild();
            Page.report(Text.SET.format(name.get(), colour.toHex()));
        });
        return swatch;
    }

    /** A surface at one elevation, named for what sits at it. */
    private static Widget<?> level(I18nString name, Elevation elevation) {
        Label label = new Label(name).setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER);
        return new Surface(new SizedBox(140, 84, label)).setElevation(elevation);
    }

    /**
     * A window of the application drawn in a palette built from the brand colours: a palette
     * applies to a whole window, so the preview paints from the palette's tones rather than
     * hosting widgets in it.
     */
    private static final class BrandPreview extends Widget<BrandPreview> {

        static final float WIDTH = 420;
        static final float HEIGHT = 220;

        private ColorPickerButton primary;
        private ColorPickerButton accent;
        private ColorPickerButton background;
        private Theme palette = Theme.light();

        void from(ColorPickerButton newPrimary, ColorPickerButton newAccent, ColorPickerButton newBackground) {
            primary = newPrimary;
            accent = newAccent;
            background = newBackground;
            rebuild();
        }

        void rebuild() {
            if (primary == null) {
                return;
            }
            palette = Theme.builder("Brand", false)
                    .background(background.color())
                    .primary(primary.color())
                    .info(accent.color())
                    .deriveAccentStates()
                    .deriveDisabled()
                    .build();
            invalidate();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(WIDTH, HEIGHT);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            float w = width();
            float h = height();
            float radius = 10;
            boolean rtl = isRightToLeft();
            canvas.fillRoundRect(0, 0, w, h, radius, palette.background());
            canvas.drawRoundRect(0.5f, 0.5f, w - 1, h - 1, radius, 1, palette.outline());
            // The window's bar, in the primary colour.
            canvas.fillRoundRect(0, 0, w, 30, radius, palette.primary());
            canvas.fillRect(0, 20, w, 10, palette.primary());
            bar(canvas, rtl, 14, 11, 90, 8, palette.onPrimary());
            // A sidebar at the start, its chosen row marked in the accent.
            float side = 110;
            float sideX = rtl ? w - side : 0;
            canvas.fillRect(sideX, 30, side, h - 30 - radius, palette.surface());
            canvas.fillRoundRect(sideX, h - radius * 2, side, radius * 2, radius, palette.surface());
            for (int i = 0; i < 5; i++) {
                float y = 46 + i * 24;
                if (i == 1) {
                    canvas.fillRoundRect(start(rtl, w, sideX, side, 8, side - 16), y - 6, side - 16, 20, 4,
                            palette.info().withAlpha(0.25f));
                }
                bar(canvas, rtl, (rtl ? 0 : sideX) + 16, y, 60 + (i % 2) * 14, 7,
                        i == 1 ? palette.text() : palette.textMuted());
            }
            // A card with lines of text, a badge in the accent and a primary button.
            float cardX = rtl ? 16 : side + 16;
            float cardW = w - side - 32;
            canvas.fillRoundRect(cardX, 46, cardW, h - 62, 8, palette.surface());
            canvas.drawRoundRect(cardX + 0.5f, 46.5f, cardW - 1, h - 63, 8, 1, palette.outline());
            float inner = cardX + 14;
            bar(canvas, rtl, inner, 62, 120, 10, palette.text(), cardX, cardW);
            canvas.fillRoundRect(start(rtl, w, cardX, cardW, cardW - 74, 58), 58, 58, 18, 9, palette.info());
            for (int i = 0; i < 3; i++) {
                bar(canvas, rtl, inner, 88 + i * 16, cardW - 28 - i * 40, 7, palette.textMuted(), cardX, cardW);
            }
            float buttonX = start(rtl, w, cardX, cardW, 14, 96);
            canvas.fillRoundRect(buttonX, h - 52, 96, 26, 6, palette.primary());
            canvas.fillRoundRect(buttonX + 22, h - 42, 52, 6, 3, palette.onPrimary());
        }

        /** A line of "text" as a bar, at {@code x} from the start of the whole preview. */
        private void bar(Canvas canvas, boolean rtl, float x, float y, float length, float thickness, Color colour) {
            float left = rtl ? width() - x - length : x;
            canvas.fillRoundRect(left, y, length, thickness, thickness / 2, colour);
        }

        /** The same, measured from the start of a box at {@code boxX}, {@code boxW} wide. */
        private void bar(Canvas canvas, boolean rtl, float x, float y, float length, float thickness, Color colour,
                         float boxX, float boxW) {
            float offset = x - boxX;
            canvas.fillRoundRect(start(rtl, width(), boxX, boxW, offset, length), y, length, thickness,
                    thickness / 2, colour);
        }

        /** The left edge of something {@code offset} from the start of a box, {@code length} long. */
        private static float start(boolean rtl, float w, float boxX, float boxW, float offset, float length) {
            return rtl ? boxX + boxW - offset - length : boxX + offset;
        }
    }
}
