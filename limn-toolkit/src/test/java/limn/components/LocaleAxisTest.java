package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;
import limn.i18n.StringBundle;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The locale axis (ADR 035): the resolution rule, the coexistence contract, the measure-cache
 * key, and the scope that hands a widget's effective locale to everything resolving text
 * inside its passes. ADR 006 §4's escape hatch, delivered in the shape the direction axis
 * modelled.
 *
 * <p>Written against {@link limn.scene.ControlSize}'s and {@code LayoutDirection}'s own
 * contracts deliberately: three axes resolving through the same chain in the same order for
 * the same reasons, and a locale axis that answered any of these differently would be a third
 * inheritance mechanism wearing the first one's name.
 */
class LocaleAxisTest extends ComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Locale HEBREW = Locale.forLanguageTag("he");
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    /** Counts measure passes so the cache key can be observed rather than assumed. */
    private static final class Probe extends Widget {
        int measures;

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return constraints.constrain(10, 10);
        }

        @Override
        protected void onLayout() {
            for (Widget child : children()) {
                child.measure(Constraints.loose(100, 100));
                child.layoutBox(0, 0, 10, 10);
            }
        }
    }

    private final List<StringBundle> registered = new ArrayList<>();
    private Locale original;

    @BeforeEach
    void pinProcessLocale() {
        original = I18n.processLocale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its language breaks every later one. */
    @AfterEach
    void restoreProcessLocale() {
        registered.forEach(I18n::removeBundle);
        registered.clear();
        I18n.setLocale(original);
    }

    private void register(StringBundle bundle) {
        registered.add(bundle);
        I18n.addBundle(bundle);
    }

    // ------------------------------------------------------------- resolution

    @Test
    void anUndeclaredWidgetResolvesToTheProcessLocale() {
        Probe w = new Probe();
        assertNull(w.declaredLocale(), "nothing declared");
        assertEquals(Locale.ENGLISH, w.locale());
        I18n.setLocale(PT_BR);
        assertEquals(PT_BR, w.locale(),
                "the chain bottoms out in the process locale, and the memo sees the switch "
                        + "through the i18n epoch even though no widget-side writer ran");
    }

    @Test
    void aDeclaredLocaleWinsOverEverything() {
        Probe w = new Probe();
        w.setLocale(HEBREW);
        try {
            assertEquals(HEBREW, w.declaredLocale());
            assertEquals(HEBREW, w.locale());
            I18n.setLocale(PT_BR);
            assertEquals(HEBREW, w.locale(), "a declaration does not follow the process");
        } finally {
            w.setLocale(null);
        }
    }

    @Test
    void aLocaleInheritsDownTheTreeAndASubtreeOverridesIt() {
        Probe root = new Probe();
        Probe mid = new Probe();
        Probe leaf = new Probe();
        Probe codePane = new Probe();
        Probe underCodePane = new Probe();
        root.add(mid);
        mid.add(leaf);
        root.add(codePane);
        codePane.add(underCodePane);

        root.setLocale(HEBREW);
        codePane.setLocale(Locale.ENGLISH);
        try {
            assertEquals(HEBREW, leaf.locale(), "inherits through two links");
            // The recorded motivation, verbatim: a Hebrew UI holding an LTR, English code pane.
            assertEquals(Locale.ENGLISH, underCodePane.locale(), "the nearer declaration wins");
            assertEquals(HEBREW, leaf.locale(), "the other branch is untouched");
            assertNull(leaf.declaredLocale(), "inheriting is not declaring");
        } finally {
            root.setLocale(null);
            codePane.setLocale(null);
        }
    }

    @Test
    void nullRestoresInheritance() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        root.setLocale(HEBREW);
        child.setLocale(PT_BR);
        try {
            assertEquals(PT_BR, child.locale());
            child.setLocale(null);
            assertNull(child.declaredLocale());
            assertEquals(HEBREW, child.locale(), "back to inheriting");
        } finally {
            root.setLocale(null);
            child.setLocale(null);
        }
    }

    @Test
    void theSceneDefaultSitsBelowTheTreeAndAboveTheProcessLocale() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);

        assertNull(scene.locale(), "a scene declares nothing by default");
        assertEquals(Locale.ENGLISH, child.locale());

        scene.setLocale(HEBREW);
        child.setLocale(PT_BR);
        try {
            assertEquals(PT_BR, child.locale(), "a declaration still wins");
            child.setLocale(null);
            assertEquals(HEBREW, child.locale(), "the scene default reaches the leaf: two "
                    + "windows in two languages, which ADR 006 said v1 could not express");
        } finally {
            child.setLocale(null);
            scene.setLocale(null);
        }
    }

    // -------------------------------------------------------------- host link

    @Test
    void theOneHostLinkCarriesTheLocaleToAnOutOfTreeRootLive() {
        Probe owner = new Probe();
        owner.setLocale(HEBREW);
        Probe popupRoot = new Probe(); // parentless, as a popup scene's root is
        try {
            assertEquals(Locale.ENGLISH, popupRoot.locale());
            popupRoot.setInheritanceHost(owner);
            assertEquals(HEBREW, popupRoot.locale(), "inherits across the link: a combo "
                    + "opened from a Hebrew subtree drops down in Hebrew");
            owner.setLocale(PT_BR);
            assertEquals(PT_BR, popupRoot.locale(), "the link is live, not a copy");
        } finally {
            owner.setLocale(null);
        }
    }

    @Test
    void aSceneDefaultBeatsTheHostLinkSoScenesStayReachable() {
        Probe owner = new Probe();
        owner.setLocale(HEBREW);
        Probe popupRoot = new Probe();
        popupRoot.setInheritanceHost(owner);
        Scene popupScene = new Scene(popupRoot);
        popupScene.setTextRuler(RULER);
        try {
            assertEquals(HEBREW, popupRoot.locale(), "no scene default: fall through");
            popupScene.setLocale(PT_BR);
            assertEquals(PT_BR, popupRoot.locale(),
                    "the scene default is consulted before the host, or setLocale would be "
                            + "unreachable for every popup, menu and dialog scene");
        } finally {
            owner.setLocale(null);
            popupScene.setLocale(null);
        }
    }

    @Test
    void theTreeBeatsAHostLink() {
        Probe parent = new Probe();
        parent.setLocale(PT_BR);
        Probe host = new Probe();
        host.setLocale(HEBREW);
        Probe child = new Probe();
        parent.add(child);
        child.setInheritanceHost(host);
        try {
            assertEquals(PT_BR, child.locale(), "a real parent wins over the link");
        } finally {
            parent.setLocale(null);
            host.setLocale(null);
        }
    }

    // ------------------------------------------------------------ reparenting

    @Test
    void reparentingChangesTheResolvedLocale() {
        Probe hebrew = new Probe();
        hebrew.setLocale(HEBREW);
        Probe portuguese = new Probe();
        portuguese.setLocale(PT_BR);
        Probe child = new Probe();
        try {
            hebrew.add(child);
            assertEquals(HEBREW, child.locale());
            hebrew.remove(child);
            portuguese.add(child);
            assertEquals(PT_BR, child.locale(), "the memo cannot survive a reparent");
        } finally {
            hebrew.setLocale(null);
            portuguese.setLocale(null);
        }
    }

    @Test
    void aConstructorTimeReadResolvesToTheProcessLocaleWhateverTheParentDeclares() {
        Probe parent = new Probe();
        parent.setLocale(HEBREW);
        try {
            Probe child = new Probe();
            Locale atConstruction = child.locale();
            parent.add(child);
            assertEquals(Locale.ENGLISH, atConstruction, "no parent yet: the process locale");
            assertEquals(HEBREW, child.locale(), "and the answer moved underneath");
        } finally {
            parent.setLocale(null);
        }
    }

    // ----------------------------------------------------- measure cache key

    @Test
    void aLocaleChangeReMeasuresInheritorsAndLeavesDeclaringSubtreesCached() {
        Probe root = new Probe();
        Probe inheritor = new Probe();
        Probe declaring = new Probe();
        root.add(inheritor);
        root.add(declaring);
        declaring.setLocale(Locale.ENGLISH);
        try {
            Constraints c = Constraints.loose(100, 100);
            root.measure(c);
            inheritor.measure(c);
            declaring.measure(c);
            int inheritorBefore = inheritor.measures;
            int declaringBefore = declaring.measures;

            inheritor.measure(c);
            declaring.measure(c);
            assertEquals(inheritorBefore, inheritor.measures, "unchanged inputs must not re-measure");
            assertEquals(declaringBefore, declaring.measures);

            root.setLocale(HEBREW);
            try {
                inheritor.measure(c);
                declaring.measure(c);
                assertEquals(inheritorBefore + 1, inheritor.measures,
                        "the inheritor's resolved locale changed: its text would resolve "
                                + "differently, so its cached size is stale");
                assertEquals(declaringBefore, declaring.measures,
                        "the declaring subtree's locale did not change; this is why no deep "
                                + "invalidation API is needed");
            } finally {
                root.setLocale(null);
            }
        } finally {
            declaring.setLocale(null);
        }
    }

    @Test
    void aProcessLocaleSwitchInvalidatesEveryMemo() {
        Probe w = new Probe();
        Constraints c = Constraints.loose(100, 100);
        w.measure(c);
        int before = w.measures;
        I18n.setLocale(PT_BR);
        w.measure(c);
        assertEquals(before + 1, w.measures,
                "the switch reaches an undeclared widget's measure cache through the key");
        assertEquals(PT_BR, w.locale());
    }

    // ------------------------------------------------------------- the scope

    /** Reads its text inside its own passes, the way every real component does. */
    private static final class Reading extends Widget {
        final I18nString text;
        String measured;
        String painted;
        String digits;

        Reading(I18nString text) {
            this.text = text;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            measured = text.get();
            digits = I18n.localizeDigits("42");
            return constraints.constrain(10, 10);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            painted = text.get();
        }
    }

    @Test
    void aWidgetsPassesResolveInItsOwnLanguageWithNoWidgetCode() {
        // The delivery mechanism of ADR 035: text.get() — the default spelling, unchanged
        // from ADR 006 — answers in the subtree's language, because the pass holds the
        // widget's effective locale in scope. A seam the component had to remember to call
        // would have been revision 1's unenforceable rule all over again.
        I18nString save = new I18nString("test.axis.save", "Save");
        register(PropertyBundle.of(PT_BR, Map.of("test.axis.save", "Salvar")));

        Probe root = new Probe();
        Reading english = new Reading(save);
        Reading portuguese = new Reading(save);
        root.add(english);
        root.add(portuguese);
        portuguese.setLocale(PT_BR);
        try {
            root.measure(Constraints.loose(100, 100));
            root.layoutBox(0, 0, 100, 100);
            english.paintWidget(new FakeCanvas(100, 100));
            portuguese.paintWidget(new FakeCanvas(100, 100));

            assertEquals("Save", english.measured);
            assertEquals("Salvar", portuguese.measured,
                    "one static declaration, two languages on one screen, in one frame");
            assertEquals("Save", english.painted);
            assertEquals("Salvar", portuguese.painted);
            assertEquals(Locale.ENGLISH, I18n.locale(),
                    "every scope closed: nothing leaks past the pass");
        } finally {
            portuguese.setLocale(null);
        }
    }

    @Test
    void digitsFollowTheSubtreeLocaleAtFormatTime() {
        Probe root = new Probe();
        Reading latin = new Reading(I18nString.literal(""));
        Reading arabic = new Reading(I18nString.literal(""));
        root.add(latin);
        root.add(arabic);
        arabic.setLocale(ARABIC);
        try {
            root.measure(Constraints.loose(100, 100));
            root.layoutBox(0, 0, 100, 100); // onLayout is what measures the children
            assertEquals("42", latin.digits);
            assertEquals("٤٢", arabic.digits,
                    "an Arabic subtree's numbers take Arabic-Indic digits from the same "
                            + "format-time seam ADR 033 built, with no second axis");
        } finally {
            arabic.setLocale(null);
        }
    }

    @Test
    void aTooltipResolvesUnderTheWidgetItAnnotates() {
        I18nString tip = new I18nString("test.axis.tooltip", "Copy");
        register(PropertyBundle.of(PT_BR, Map.of("test.axis.tooltip", "Copiar")));
        Probe w = new Probe();
        w.setTooltip(tip);
        w.setLocale(PT_BR);
        try {
            assertEquals("Copiar", w.tooltip(),
                    "the scene painting the tooltip is outside any pass, so the widget "
                            + "carries its own locale into the resolution");
        } finally {
            w.setLocale(null);
        }
    }
}
