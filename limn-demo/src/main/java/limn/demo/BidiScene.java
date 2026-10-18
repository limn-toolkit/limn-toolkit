package limn.demo;

import limn.components.Card;
import limn.components.Label;
import limn.components.ScrollView;

import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text in the scripts a per-code-point pipeline cannot draw, the algorithm that puts mixed
 * directions in order, and customer cards that each declare their customer's language
 * ({@code --scene bidi}).
 *
 * <p>Every specimen is a case where one code point in does not mean one glyph out, in that order,
 * at that place: a letter whose glyph comes from its neighbours, a mark placed by the font rather
 * than by an advance, three characters that become one glyph, a vowel drawn before the consonant
 * it is written after, marks stacked two high, and a line laid out in two directions at once.
 *
 * <p>A picture is the wrong instrument for bidi (it looks right while being wrong), which is why
 * the caret, the selection boxes and the glyph counts are pinned headlessly instead. What a human
 * gets here is the part a test cannot have: whether the shapes are the ones a reader of the script
 * expects, and whether a caret dragged through the editable fields lands where a hand meant it to.
 *
 * <p>Each customer card declares its customer's locale, and the right-to-left ones their direction
 * too: the card's "Notes" caption is a string of the showcase's bundle, so it reads in the
 * customer's language wherever the showcase ships that language, whatever the window's own.
 */
final class BidiScene {

    /** The strings of this page, ready for translation; the specimens are data and stay literal. */
    static final class Text {
        static final I18nString TYPOGRAPHY = new I18nString("showcase.text.typography", "Typography");
        static final I18nString REGULAR = new I18nString("showcase.text.regular", "Regular");
        static final I18nString BOLD = new I18nString("showcase.text.bold", "Bold");
        static final I18nString ITALIC = new I18nString("showcase.text.italic", "Italic");
        static final I18nString BOLD_ITALIC = new I18nString("showcase.text.boldItalic", "Bold italic");

        static final I18nString SCRIPTS = new I18nString("showcase.text.scripts", "Scripts");
        static final I18nString MIXED = new I18nString("showcase.text.mixed", "Mixed directions");
        static final I18nString CUSTOMERS = new I18nString("showcase.text.customers", "Customers");

        static final I18nString ARABIC = new I18nString("showcase.text.arabic", "Arabic");
        static final I18nString HEBREW = new I18nString("showcase.text.hebrew", "Hebrew");
        static final I18nString DEVANAGARI = new I18nString("showcase.text.devanagari", "Devanagari");
        static final I18nString THAI = new I18nString("showcase.text.thai", "Thai");
        static final I18nString JAPANESE = new I18nString("showcase.text.japanese", "Japanese");
        static final I18nString KOREAN = new I18nString("showcase.text.korean", "Korean");
        static final I18nString CHINESE = new I18nString("showcase.text.chinese", "Chinese");
        static final I18nString RUSSIAN = new I18nString("showcase.text.russian", "Russian");
        static final I18nString UKRAINIAN = new I18nString("showcase.text.ukrainian", "Ukrainian");
        static final I18nString VIETNAMESE = new I18nString("showcase.text.vietnamese", "Vietnamese");

        private Text() {
        }
    }

    // The Arabic and Hebrew pairs are spelled in escapes because there the mark IS the fixture.
    // A combining mark inside a literal is invisible in any editor that renders it correctly —
    // it lands on the letter before it — so "vocalized" and "bare" would read as the same
    // string in the source, and the wrong one would never be noticed. The other words stay
    // literal and name their code points above instead: there the marks are part of the word
    // rather than the difference being shown.

    /** {@code al-arabiyya}: seven code points, ten glyphs, chosen from the company they keep. */
    private static final String AL_ARABIYYA = "العربية";

    /** {@code marhaba} ("hello"): five letters, and the Arabic half of the mixed line below. */
    private static final String MARHABA = "مرحبا";

    /** {@code riyal}: the right-to-left run of the three-run bidi line below. */
    private static final String RIYAL = "ريال";

    /** {@code kataba} ("he wrote"): kaf, fatha, teh, fatha, beh, fatha — three letters, three marks. */
    private static final String KATABA = "كَتَبَ";

    /** The same word unvocalized: kaf, teh, beh. The marks are the only difference. */
    private static final String KATABA_BARE = "كتب";

    /** {@code shalom} with niqqud: shin, qamats, shin-dot, lamed, vav, holam, final mem. */
    private static final String SHALOM_POINTED =
            "שָׁלוֹם";

    /** The same word unpointed: shin, lamed, vav, final mem. Four letters, four glyphs. */
    private static final String SHALOM = "שלום";

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

    /** Kanji, hiragana and katakana: three writing systems in one Japanese line. */
    private static final String NIHONGO = "日本語 ひらがな カタカナ";

    /** Hangul: each block a syllable composed of two or three jamo. */
    private static final String HANGUL = "한국어 한글";

    /** The same two words in simplified and in traditional characters. */
    private static final String ZHONGWEN = "简体中文 繁體中文";

    /** Cyrillic, Russian. */
    private static final String RUSSKIY = "Русский язык";

    /** Cyrillic, Ukrainian: ї and є are letters Russian does not have. */
    private static final String UKRAINSKA = "Українська мова, Київ, Євген";

    /** Vietnamese: a vowel can carry two marks, one over the other (ễ, ượ, ặ, ổ). */
    private static final String TIENG_VIET = "Tiếng Việt: Nguyễn, Phượng, Đặng, Phổ";

    /** The Latin run the mixed lines and the editable fields put inside right-to-left text. */
    private static final String ISLAND = "Limn UI 2026";

    /**
     * The Arabic customer's locale: Egypt and not a bare {@code ar}, since CLDR makes Latin the
     * Arabic default and names twenty-three regions that write Arabic-Indic digits.
     */
    private static final Locale ARABIC_EGYPT = Locale.forLanguageTag("ar-EG");

    /** Fixed sizes rather than Label roles: specimens of glyph shape, big enough to see a mark. */
    private static final Font SPECIMEN = Font.of(24);
    private static final Font PARAGRAPH = Font.of(14);

    /** What the standalone fields are sized to. */
    private static final float FIELD_WIDTH = 320;

    /** The scene, and the selection to install once there is a layout to put it in. */
    record Built(Scene scene, Runnable afterLayout) {
    }

    /** One customer of the sample company, in their own script and language. */
    /** "Notes" in Thai, for the one card in a language the Showcase has no strings in. */
    private static final String THAI_NOTES = "หมายเหตุ";

    private record Customer(String locale, String name, String city, String note) {
    }

    private static final List<Customer> CUSTOMERS = List.of(
            new Customer("ar-EG", "شركة النيل للتوريدات", "القاهرة",
                    "يفضّل التسليم صباحًا، وتُرسل الفواتير إلى المكتب الرئيسي في نهاية كل شهر."),
            new Customer("he", "אורן ציוד משרדי בע״מ", "חיפה",
                    "מעדיפים משלוח בבוקר; את החשבוניות שולחים למשרד הראשי בסוף כל חודש."),
            new Customer("hi", "गंगा ट्रेडर्स", "पुणे",
                    "सुबह डिलीवरी पसंद करते हैं; बिल हर महीने के अंत में मुख्य कार्यालय भेजे जाते हैं।"),
            new Customer("th", "บริษัท ทองคำ ซัพพลาย จำกัด", "เชียงใหม่",
                    "ต้องการให้จัดส่งในช่วงเช้า และส่งใบแจ้งหนี้ไปที่สำนักงานใหญ่ทุกสิ้นเดือน"),
            new Customer("ja", "株式会社みどり商会", "大阪市北区",
                    "午前中の配達を希望。請求書は毎月末に本社へ送付してください。"),
            new Customer("ko", "푸른물산 주식회사", "부산광역시",
                    "오전 배송을 선호합니다. 청구서는 매월 말 본사로 보내 주세요."),
            new Customer("zh-Hans", "华东办公用品有限公司", "杭州市",
                    "希望上午送货。发票请于每月月底寄至总部。"),
            new Customer("ru", "ООО «Северный склад»", "Новосибирск",
                    "Предпочитает доставку утром; счета отправлять в головной офис в конце каждого месяца."),
            new Customer("uk", "ТОВ «Галицький постачальник»", "Київ",
                    "Надає перевагу доставці зранку; рахунки надсилати до головного офісу наприкінці місяця."),
            new Customer("vi", "Công ty TNHH Thiết bị Phương Đông", "Thành phố Hồ Chí Minh",
                    "Ưu tiên giao hàng buổi sáng; hóa đơn gửi về trụ sở chính vào cuối mỗi tháng."));

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
        TextField arabic = arabicField();
        TextField hebrew = hebrewField();
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(page(arabic, hebrew))));
        scene.requestFocus(arabic);
        // Derived from the fixtures rather than written down: editing either constant would
        // otherwise slide a literal index onto the wrong side of the boundary this has to cross.
        int insideTheArabic = 2;
        int intoTheLatin = MARHABA.length() + 1 + 4;
        return new Built(scene, () -> arabic.select(insideTheArabic, intoTheLatin));
    }

    /**
     * The same page as {@code --scene bidi}, as a page of the showcase.
     *
     * <p>It builds its own fields rather than sharing the scene's: the standalone scene focuses one
     * of them and installs a selection across its direction boundary, and a page that inherited that
     * would open with a selection nobody made.
     */
    static Widget<?> tabContent() {
        return page(arabicField(), hebrewField());
    }

    private static TextField arabicField() {
        return new TextField().setPreferredWidth(FIELD_WIDTH).setText(MARHABA + " " + ISLAND + " " + AL_ARABIYYA);
    }

    private static TextField hebrewField() {
        return new TextField().setPreferredWidth(FIELD_WIDTH).setText(SHALOM + " " + ISLAND);
    }

    private static Widget<?> page(TextField arabic, TextField hebrew) {
        Column page = Page.column();

        page.add(Page.firstSection(Text.TYPOGRAPHY));
        page.add(Page.row(28,
                new Label(Text.REGULAR),
                new Label(Text.BOLD).setStrong(true),
                new Label(Text.ITALIC).setItalic(true),
                new Label(Text.BOLD_ITALIC).setStrong(true).setItalic(true)));

        page.add(Page.section(Text.SCRIPTS));
        // The pair is the assertion in two of these: marks add ink and no width, so the vocalized
        // word and the bare one are the same length on screen.
        page.add(pairs(List.of(
                specimen(Text.ARABIC, "ar", AL_ARABIYYA + "   " + KATABA + "   " + KATABA_BARE),
                specimen(Text.HEBREW, "he", SHALOM_POINTED + "   " + SHALOM),
                specimen(Text.DEVANAGARI, "hi", KSHA + "   " + HINDI),
                specimen(Text.THAI, "th", NAM + "   " + PIN + "   " + PHASA_THAI),
                specimen(Text.JAPANESE, "ja", NIHONGO),
                specimen(Text.KOREAN, "ko", HANGUL),
                specimen(Text.CHINESE, "zh", ZHONGWEN),
                specimen(Text.RUSSIAN, "ru", RUSSKIY),
                specimen(Text.UKRAINIAN, "uk", UKRAINSKA),
                specimen(Text.VIETNAMESE, "vi", TIENG_VIET)), 18));

        page.add(Page.section(Text.MIXED));
        Column lines = new Column();
        lines.gap(6).crossAlignment(Flex.CrossAlignment.START);
        // Three runs at levels 0 / 1 / 0, and the same thing the other way up: here the first
        // strong character is Arabic, so the paragraph runs right to left and the Latin keeps its
        // own direction inside it.
        lines.add(new Label("Total: 42 " + RIYAL + " (SAR)").setFont(SPECIMEN));
        lines.add(new Label(MARHABA + " " + ISLAND + " " + AL_ARABIYYA).setFont(SPECIMEN));
        Column fields = new Column();
        fields.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        fields.add(arabic);
        fields.add(hebrew);
        page.add(pairs(List.of(lines, fields), 18));

        page.add(Page.section(Text.CUSTOMERS));
        List<Widget<?>> cards = new ArrayList<>();
        for (Customer customer : CUSTOMERS) {
            cards.add(card(customer));
        }
        page.add(pairs(cards, Page.GAP));
        return page;
    }

    /**
     * Two flexible columns, row by row: each column takes half the width there is, and each row is
     * as tall as its taller cell, so two cards side by side are one height.
     */
    private static Widget<?> pairs(List<Widget<?>> cells, float rowGap) {
        Column rows = new Column();
        rows.gap(rowGap).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int i = 0; i < cells.size(); i += 2) {
            Row row = new Row();
            row.gap(24).crossAlignment(Flex.CrossAlignment.STRETCH);
            row.add(Expanded.of(cells.get(i), 1));
            row.add(i + 1 < cells.size() ? Expanded.of(cells.get(i + 1), 1) : Expanded.spacer(1));
            rows.add(row);
        }
        return rows;
    }

    /**
     * A script's name and a few words in it, at a size the marks are legible at. The words declare
     * their language, which is what picks a face's regional forms where one code point is drawn
     * differently in Japanese and in Chinese.
     */
    private static Widget<?> specimen(I18nString script, String language, String text) {
        Column block = new Column();
        block.gap(2).crossAlignment(Flex.CrossAlignment.START);
        block.add(new Label(script).setMuted(true));
        Label words = new Label(text).setFont(SPECIMEN).setOverflow(Label.Overflow.ELLIPSIS);
        words.setLocale(Locale.forLanguageTag(language));
        block.add(words);
        return block;
    }

    /**
     * A customer's card, in the customer's locale and, for a script that reads right to left, in
     * its direction: the name is its title and names it to a reader, the city its subtitle, and the
     * caption above the note is the showcase's own string for "Notes", read in the card's
     * language. The Showcase ships no Thai, so the Thai card carries its one word with its data,
     * as it carries its note; through the string it read the English "Notes" in every language
     * and logged the key as untranslated each time it was drawn.
     */
    private static Widget<?> card(Customer customer) {
        Column notes = new Column();
        notes.gap(4).crossAlignment(Flex.CrossAlignment.STRETCH);
        notes.add(new Label(customer.locale().equals("th") ? I18nString.literal(THAI_NOTES)
                : ShowcaseStrings.ORDERS_NOTES).setMuted(true));
        notes.add(new Label(customer.note()).setFont(PARAGRAPH).setWrap(true));
        Card card = new Card(notes).setTitle(customer.name()).setSubtitle(customer.city());
        Locale locale = customer.locale().equals("ar-EG") ? ARABIC_EGYPT : Locale.forLanguageTag(customer.locale());
        card.setLocale(locale);
        card.setLayoutDirection(LayoutDirection.forLocale(locale));
        return card;
    }
}
