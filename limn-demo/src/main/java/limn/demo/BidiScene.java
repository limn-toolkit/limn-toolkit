package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Font;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * The four scripts a per-code-point pipeline cannot draw, and the algorithm that puts them in
 * order ({@code --scene bidi}).
 *
 * <p>Every specimen here is a case where one code point in does not mean one glyph out, in
 * that order, at that place: a letter whose glyph comes from its neighbours, a mark placed by
 * the font rather than by an advance, three characters that become one glyph, a vowel drawn
 * before the consonant it is written after, and a line laid out in two directions at once.
 * Each carries a caption saying which of those it is, because a specimen nobody can read is a
 * specimen nobody can check.
 *
 * <p><b>What this screen deliberately does not show is mirroring.</b> The Arabic and Hebrew is
 * correct right-to-left text sitting in a left-to-right layout: the specimens start at the left
 * edge, and the fields keep their unmirrored frames. ADR 031 §4 makes direction-as-a-layout-axis
 * an ADR of its own rather than a loose end here, because it reaches every component; correct
 * text inside an unmirrored screen is the intermediate state it chose over shipping neither
 * half.
 *
 * <p>A picture is the wrong instrument for bidi — it looks right while being wrong — which is
 * why the caret, the selection boxes and the glyph counts are pinned headlessly instead. What a
 * human gets from this screen is the part a test cannot have: whether the shapes are the ones a
 * reader of the script expects, and whether a caret dragged through them lands where a hand
 * meant it to. Hence the editable fields. The specimens can be read; those two can be argued
 * with.
 */
final class BidiScene {

    // The Arabic and Hebrew pairs are spelled in escapes because there the mark IS the fixture.
    // A combining mark inside a literal is invisible in any editor that renders it correctly —
    // it lands on the letter before it — so "vocalized" and "bare" would read as the same
    // string in the source, and the wrong one would never be noticed. The Devanagari and Thai
    // words stay literal and name their code points above instead: there the marks are part of
    // the word rather than the difference being shown.

    /** {@code al-arabiyya}: seven code points, ten glyphs, chosen from the company they keep. */
    private static final String AL_ARABIYYA = "العربية";

    /** {@code marhaba} ("hello"): five letters, and the Arabic half of the mixed line below. */
    private static final String MARHABA = "مرحبا";

    /** {@code riyal}: the right-to-left run of ADR 031 Finding 4's bidi fixture. */
    private static final String RIYAL = "ريال";

    /** {@code kataba} ("he wrote"): kaf, fatha, teh, fatha, beh, fatha — three letters, three marks. */
    private static final String KATABA = "\u0643\u064E\u062A\u064E\u0628\u064E";

    /** The same word unvocalized: kaf, teh, beh. The marks are the only difference. */
    private static final String KATABA_BARE = "\u0643\u062A\u0628";

    /** {@code shalom} with niqqud: shin, qamats, shin-dot, lamed, vav, holam, final mem. */
    private static final String SHALOM_POINTED =
            "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD";

    /** The same word unpointed: shin, lamed, vav, final mem. Four letters, four glyphs. */
    private static final String SHALOM = "\u05E9\u05DC\u05D5\u05DD";

    /** ka, virama, ssa: the ksha conjunct, three code points that shape into one glyph. */
    private static final String KSHA = "क्ष";

    /** {@code hindi}: ha, i-matra, na, virama, da, ii. Six code points, five glyphs. */
    private static final String HINDI = "हिन्दी";

    /** {@code pin}: consonant, vowel above it, tone above the vowel. */
    private static final String PIN = "ปิ่น";

    /** {@code nam} ("water"): sara am is one code point and comes back as two glyphs. */
    private static final String NAM = "น้ำ";

    /** {@code phasa thai}: seven code points carrying no mark, so the plain Thai case. */
    private static final String PHASA_THAI = "ภาษาไทย";

    /** The Latin run the mixed lines and the editable fields put inside right-to-left text. */
    private static final String ISLAND = "Limn UI 2026";

    /** Wide enough for a 24 pt specimen, narrow enough to leave the paragraphs their column. */
    private static final float SPECIMEN_COLUMN = 400;

    /** The paragraph column, and therefore the width every wrap decision here is made against. */
    private static final float PARAGRAPH_COLUMN = 320;

    /** Between the two columns, and under the subtitle that spans both. */
    private static final float COLUMN_GAP = 24;

    // Fixed sizes rather than Label roles, and one shared instance each: these are specimens of
    // glyph shape, not of the control ramp, and a mark has to be big enough to see. Sharing the
    // instance also keeps every label on one entry of the font memo.
    private static final Font SPECIMEN = Font.of(24);
    private static final Font PARAGRAPH = Font.of(14);

    /** The scene, and the selection to install once there is a layout to put it in. */
    record Built(Scene scene, Runnable afterLayout) {
    }

    private BidiScene() {
    }

    /**
     * Builds {@code --scene bidi} with the Arabic field focused and a selection crossing its
     * direction boundary.
     *
     * <p>That selection is the one thing here a still frame can show and a reader cannot produce
     * by looking: a range contiguous in the string is two boxes on screen the moment it crosses
     * from Arabic into Latin, and a toolkit that drew it as one rectangle would paint over text
     * nobody selected. It is installed after the first layout because a field with no geometry
     * has nowhere to put it.
     */
    static Built create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());
        TextField arabic = new TextField().setPreferredWidth(PARAGRAPH_COLUMN)
                .setText(MARHABA + " " + ISLAND + " " + AL_ARABIYYA);
        TextField hebrew = new TextField().setPreferredWidth(PARAGRAPH_COLUMN)
                .setText(SHALOM + " " + ISLAND);
        Scene scene = new Scene(new Padding(Insets.all(20), content(arabic, hebrew)));
        scene.setBackground(Theme.current().background);
        scene.requestFocus(arabic);
        // Derived from the fixtures rather than written down: editing either constant would
        // otherwise slide a literal index onto the wrong side of the boundary this has to cross.
        int insideTheArabic = 2;
        int intoTheLatin = MARHABA.length() + 1 + 4;
        return new Built(scene, () -> {
            arabic.model().setCursor(insideTheArabic, false);
            arabic.model().setCursor(intoTheLatin, true);
        });
    }

    /**
     * The same specimens as {@code --scene bidi}, as a body the kitchen sink can host in a tab.
     *
     * <p>It builds its own fields rather than sharing the scene's: the standalone scene focuses one
     * of them and installs a selection across its direction boundary, and a tab that inherited that
     * would open with a selection nobody made.
     */
    static Widget tabContent() {
        TextField arabic = new TextField().setPreferredWidth(PARAGRAPH_COLUMN)
                .setText(MARHABA + " " + ISLAND + " " + AL_ARABIYYA);
        TextField hebrew = new TextField().setPreferredWidth(PARAGRAPH_COLUMN)
                .setText(SHALOM + " " + ISLAND);
        return page(arabic, hebrew);
    }

    private static Widget content(TextField arabic, TextField hebrew) {
        // The two columns are sized to fit the demo window without scrolling, but a larger
        // control-size step or a face with taller metrics grows them; scrolling is what keeps the
        // last specimen reachable rather than clipped off the bottom.
        return new ScrollView(page(arabic, hebrew));
    }

    private static Widget page(TextField arabic, TextField hebrew) {
        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.START);
        page.add(new Label("Bidi and complex scripts").setRole(Label.Role.TITLE));
        page.add(new SizedBox(SPECIMEN_COLUMN + COLUMN_GAP + PARAGRAPH_COLUMN, SizedBox.UNSET,
                new Label("A shaper chooses the glyphs and java.text.Bidi puts them in order. "
                        + "The layout is not mirrored: right-to-left text, left-to-right screen.")
                        .setMuted(true).setWrap(true)));

        Row columns = new Row();
        columns.gap(COLUMN_GAP).crossAlignment(Flex.CrossAlignment.START);
        columns.add(new SizedBox(SPECIMEN_COLUMN, SizedBox.UNSET, specimens()));
        columns.add(new SizedBox(PARAGRAPH_COLUMN, SizedBox.UNSET, paragraphs(arabic, hebrew)));
        page.add(columns);
        return page;
    }

    /** One word per property, each under the property it demonstrates. */
    private static Widget specimens() {
        Column column = new Column();
        column.gap(10).crossAlignment(Flex.CrossAlignment.START);
        column.add(specimen("Arabic — 7 code points, 10 glyphs, chosen by the neighbours",
                AL_ARABIYYA));
        // The pair is the assertion: the marks add ink and no width, so the vocalized word and
        // the bare one are the same length on screen.
        column.add(specimen("Arabic marks — one word vocalized and bare; the font places them",
                KATABA + "   " + KATABA_BARE));
        column.add(specimen("Hebrew — right to left, niqqud at zero advance",
                SHALOM_POINTED + "   " + SHALOM));
        column.add(specimen("Bidi — one line, three runs at levels 0 / 1 / 0",
                "Total: 42 " + RIYAL + " (SAR)"));
        // The same thing the other way up. Here the first strong character is Arabic, so the
        // paragraph itself runs right-to-left and the Latin keeps its own direction inside it.
        column.add(specimen("Bidi — an Arabic line with a Latin island inside it",
                MARHABA + " " + ISLAND + " " + AL_ARABIYYA));
        column.add(specimen("Devanagari — ksha: 3 code points, 1 glyph", KSHA));
        column.add(specimen("Devanagari — the i-matra is drawn before its consonant", HINDI));
        column.add(specimen("Thai — sara am is one code point and two glyphs",
                NAM + "   " + PIN + "   " + PHASA_THAI));
        return column;
    }

    /** The editable pair, then one wrapped paragraph per script. */
    private static Widget paragraphs(TextField arabic, TextField hebrew) {
        Column column = new Column();
        column.gap(10).crossAlignment(Flex.CrossAlignment.START);
        column.add(caption("Editable — click into it, drag a selection, walk it with the arrows"));
        column.add(arabic);
        column.add(hebrew);

        column.add(paragraph("Wrapped Arabic",
                "هذه فقرة عربية تلتف على عدة أسطر داخل عمود ثابت، ومكان القطع"
                        + " تحدده حدود يونيكود لا عدُّ الحروف."));
        column.add(paragraph("Wrapped Hebrew",
                "זו פסקה בעברית הנשברת לכמה שורות בתוך עמודה קבועה, ומקום השבירה"
                        + " נקבע לפי גבולות יוניקוד ולא לפי ספירת תווים."));
        column.add(paragraph("Wrapped Devanagari — conjuncts in running text",
                "यह हिन्दी का अनुच्छेद कई पंक्तियों में लिपटता है; क्षमता और शिक्षा"
                        + " जैसे संयुक्ताक्षर यहाँ भी बनते हैं।"));
        // Thai writes no space between words, so the hard break this replaced — one code point
        // wide, because that was all a per-code-point pipeline could see — could cut between a
        // consonant and the vowel drawn above it. The line breaker looks for words instead.
        column.add(paragraph("Wrapped Thai — not one space in it",
                "ภาษาไทยไม่เว้นวรรคระหว่างคำการขึ้นบรรทัดใหม่จึงต้องอาศัยตัวแบ่งบรรทัดแทนช่องว่าง"));
        return column;
    }

    /** A caption and the word it describes, at a size the marks are legible at. */
    private static Widget specimen(String what, String text) {
        Column block = new Column();
        block.gap(2).crossAlignment(Flex.CrossAlignment.START);
        block.add(caption(what));
        block.add(new Label(text).setFont(SPECIMEN));
        return block;
    }

    /** A caption and a paragraph that has to break inside the column to fit it. */
    private static Widget paragraph(String what, String text) {
        Column block = new Column();
        block.gap(2).crossAlignment(Flex.CrossAlignment.START);
        block.add(caption(what));
        block.add(new SizedBox(PARAGRAPH_COLUMN, SizedBox.UNSET,
                new Label(text).setFont(PARAGRAPH).setWrap(true)));
        return block;
    }

    private static Label caption(String text) {
        return new Label(text).setMuted(true);
    }
}
