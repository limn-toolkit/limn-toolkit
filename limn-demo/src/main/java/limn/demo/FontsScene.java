package limn.demo;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Font;
import limn.graphics.Fonts;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;

import java.util.List;

/**
 * Script/font-fallback showcase: one line per writing system, an emoji row, and
 * an editable {@link TextField} whose "Insert IME text + emoji" button
 * drops CJK text and emoji straight into the field (as an IME commit would). All
 * text is rendered from a single face per label; CJK and emoji fall back to the
 * bundled Noto faces, once per shaped run (see {@code fonts/README.md}). When
 * those optional fonts are absent, CJK/emoji show {@code .notdef} boxes.
 */
final class FontsScene {

    /** The built body plus handles the capture scene uses to trigger the button. */
    record Built(Widget widget, TextField field, Runnable insert) {
    }

    private FontsScene() {
    }

    /** The tab/scene body (kitchen tab uses this). */
    static Widget content() {
        return build().widget();
    }

    /** Builds the body and exposes the editable field + its insert action. */
    static Built build() {
        Column content = new Column();
        content.gap(12).crossAlignment(Flex.CrossAlignment.START);

        content.add(new Label("Fonts & scripts: per-run fallback")
                .setFont(Theme.current().title));
        content.add(new Label("Roboto for Latin/Greek/Cyrillic; Noto Sans CJK, Arabic, Hebrew, "
                + "Devanagari, Thai and Color Emoji fill the rest.")
                .setMuted(true));

        // Live font switcher: picks any bundled or system family; the whole panel
        // (everything below, which uses the default family) re-renders on change.
        content.add(new FontPicker());

        content.add(sample("Latin", "The quick brown fox in Limn UI"));
        content.add(sample("Greek", "Ελληνικά: Γειά σου Κόσμε αβγδ ΑΒΓΔ"));
        content.add(sample("Cyrillic", "Кириллица: Привет мир абвг АБВГ"));
        content.add(sample("Japanese", "日本語: こんにちは世界 カタカナ ひらがな"));
        content.add(sample("Chinese", "中文: 你好世界 汉字"));
        content.add(sample("Korean", "한국어: 안녕하세요 세계"));
        // The four faces that arrived with shaping, and the rows where per-run fallback is doing
        // something a per-code-point chain could not. Each is chosen to fail visibly rather than
        // subtly if the run reached the wrong face or reached the right one unshaped: the Arabic
        // letters join into one another, the Hebrew niqqud sit under their consonants, the
        // Devanagari carries a conjunct and a matra drawn before the consonant it follows in the
        // string, and the Thai stacks a vowel above and a tone above that.
        content.add(sample("Arabic", "العربية: مرحبا بالعالم"));
        content.add(sample("Hebrew", "עברית: שָׁלוֹם עוֹלָם"));
        content.add(sample("Devanagari", "हिन्दी: नमस्ते दुनिया क्ष"));
        content.add(sample("Thai", "ไทย: สวัสดีชาวโลก"));
        // The menu key symbols, which no UI font has: they come from the small bundled face the
        // accelerator hints are drawn with, and this row is where a build missing it shows boxes.
        content.add(sample("Menu keys", "⌘ ⌥ ⌃ ⇧ ⇪ ⏎ ⌤ ⌫ ⌦ ⇥ ⎋ ␣ ⇞ ⇟ ↖ ↘ ← ↑ → ↓"));
        content.add(sample("A hint", "⇧⌘S   ⌥⌘←   ⌘⌫   Ctrl+Shift+S"));

        // Emoji row, larger so the glyphs are legible. Color, from Noto Color Emoji.
        content.add(new Label("Emoji (color, Noto Color Emoji)").setMuted(true));
        content.add(new Label("😀 🎉 ❤ 🔥 👍 🚀 🌍 ⭐ ✅ 🐛 📦 🎨").setFont(Font.of(30)));

        // Editable field + a button that inserts CJK text and emoji into it, the
        // way an IME commit would, proving mixed scripts render in a live editor.
        TextField field = new TextField().setPreferredWidth(440)
                .setPlaceholder("Editable: click the button to insert IME text + emoji");
        Runnable insert = () -> field.insertText("日本語 你好 안녕 😀🎉🔥 ");
        content.add(new Label("Editable input (IME text + emoji in a real TextField):").setMuted(true));
        Row row = new Row();
        row.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(field);
        row.add(new Button("Insert IME text + emoji").onAction(insert));
        content.add(row);

        return new Built(content, field, insert);
    }

    /** One "Name: sample text" line with a muted script label. */
    private static Widget sample(String script, String text) {
        return new Label(script + ":  " + text).setFont(Theme.current().body);
    }

    static Scene create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());
        return new Scene(content());
    }

    /**
     * Live UI-font switcher: a combo of all available families (bundled + system,
     * enumerated in the background) that drives {@link Fonts#setDefaultFamily}. It
     * rebuilds the combo only when the family <em>list</em> changes (the async
     * system-font scan finishing), not on every selection, and un/subscribes with
     * the widget lifecycle so it holds no listener while detached.
     */
    private static final class FontPicker extends Column {

        private final Runnable onFontsChanged = this::rebuild;
        private List<String> built = List.of();
        private boolean populated; // the listing is requested on first SHOWN paint

        FontPicker() {
            gap(6).crossAlignment(Flex.CrossAlignment.START);
            // Placeholder only: asking Fonts.available() here would kick the
            // OS font enumeration at scene BUILD time even when this picker
            // lives in a hidden tab. The real listing is requested on the
            // first visible paint below.
            buildRows(List.of("Roboto"), "enumerating fonts…");
        }

        @Override
        protected void onAttached() {
            Fonts.addChangeListener(onFontsChanged);
        }

        @Override
        protected void onDetached() {
            Fonts.removeChangeListener(onFontsChanged);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            if (!populated && isShowing()) {
                populated = true;
                // First time actually on screen: NOW someone wants the listing.
                // Posted (not inline): rebuild mutates children mid-frame.
                limn.concurrent.Ui.post(this::rebuild);
            }
        }

        private void rebuild() {
            List<String> families = Fonts.available(); // requests the enumeration, once
            if (families.isEmpty()) {
                families = List.of("Roboto");
            }
            if (families.equals(built) && !children().isEmpty()) {
                return; // list unchanged (e.g. only the default family switched); keep the combo
            }
            built = families;
            buildRows(families, families.size() + " families available (bundled + system)");
        }

        private void buildRows(List<String> list, String subtitle) {
            while (!children().isEmpty()) {
                remove(children().get(0));
            }
            ComboBox combo = new ComboBox(list);
            // Before onSelect is wired, deliberately: a programmatic set fires the listener like
            // any other change, and this one would push the family already in force back through
            // Fonts.setDefaultFamily on every rebuild.
            combo.setSelectedIndex(indexOfDefault(list));
            combo.onSelect(index -> Fonts.setDefaultFamily(list.get(index)));

            Row row = new Row();
            row.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(new Label("UI font:"));
            row.add(combo);
            add(row);
            add(new Label(subtitle).setMuted(true));
            markNeedsLayout();
        }

        private static int indexOfDefault(List<String> families) {
            String current = Fonts.defaultFamily();
            String target = current.equals(Font.DEFAULT_FAMILY) ? "Roboto" : current;
            for (int i = 0; i < families.size(); i++) {
                if (families.get(i).equalsIgnoreCase(target)) {
                    return i;
                }
            }
            return 0;
        }
    }
}
