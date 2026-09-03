package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of ADR 035 that live in this package: the resolution <b>scope</b>
 * (what makes {@code I18n.locale()} answer the subtree being worked inside) and the
 * <b>retain ledger</b> (what keeps a subtree's language resident in every bundle while
 * anything declares it). The widget end of the same ADR — the inheritance chain, the
 * measure key, where the toolkit opens scopes — is {@code LocaleAxisTest}.
 */
class LocaleScopeTest {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Locale HEBREW = Locale.forLanguageTag("he");

    private final List<StringBundle> registered = new ArrayList<>();
    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.processLocale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its language breaks every later one. */
    @AfterEach
    void restore() {
        registered.forEach(I18n::removeBundle);
        registered.clear();
        I18n.setLocale(original);
    }

    private void register(StringBundle bundle) {
        registered.add(bundle);
        I18n.addBundle(bundle);
    }

    // ---------------------------------------------------------------- the scope

    @Test
    void aScopeAnswersInsideAndNotOutside() {
        assertEquals(Locale.ENGLISH, I18n.locale());
        Locale enclosing = I18n.pushScope(HEBREW);
        try {
            assertEquals(HEBREW, I18n.locale(), "in scope, the subtree's language answers");
            assertEquals(Locale.ENGLISH, I18n.processLocale(),
                    "the process locale is unmoved: a scope is resolution context, not state");
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals(Locale.ENGLISH, I18n.locale(), "closed, the process locale answers again");
    }

    @Test
    void scopesNestAndRestoreWhatTheyReplaced() {
        Locale outer = I18n.pushScope(HEBREW);
        try {
            Locale inner = I18n.pushScope(PT_BR);
            try {
                assertEquals(PT_BR, I18n.locale(), "the innermost scope wins");
            } finally {
                I18n.popScope(inner);
            }
            assertEquals(HEBREW, I18n.locale(), "popping restores the enclosing scope, "
                    + "which is how a child's paint hands its parent back its language");
        } finally {
            I18n.popScope(outer);
        }
        assertNull(I18n.pushScope(HEBREW), "no scope open: the enclosing value is null");
        I18n.popScope(null);
    }

    @Test
    void aScopeEqualToTheEnclosingOneStillNestsAndRestores() {
        // The common shape: a child resolving to its parent's language. The push is elided
        // as a write, and the pop must still leave exactly what the outer push found.
        Locale outer = I18n.pushScope(HEBREW);
        try {
            Locale inner = I18n.pushScope(HEBREW);
            assertEquals(HEBREW, inner, "the enclosing scope is what the inner push found");
            assertEquals(HEBREW, I18n.locale());
            I18n.popScope(inner);
            assertEquals(HEBREW, I18n.locale(), "popping the inner scope keeps the outer one");
        } finally {
            I18n.popScope(outer);
        }
        assertEquals(I18n.processLocale(), I18n.locale());
    }

    @Test
    void anI18nStringResolvesUnderTheScopeAndItsMemoSeesTheLocale() {
        I18nString save = new I18nString("test.scope.save", "Save");
        register(PropertyBundle.of(PT_BR, Map.of("test.scope.save", "Salvar")));
        register(PropertyBundle.of(HEBREW, Map.of("test.scope.save", "שמירה")));

        assertEquals("Save", save.get());
        Locale enclosing = I18n.pushScope(PT_BR);
        try {
            assertEquals("Salvar", save.get(),
                    "the same declaration reads in the scope's language: no widget code, "
                            + "no second field, no capture bug");
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("Save", save.get(),
                "and the memo cannot serve the scope's answer once the scope is gone");
    }

    @Test
    void formatRunsMessageFormatUnderTheScope() {
        I18nString count = new I18nString("test.scope.count", "Display {0}");
        register(PropertyBundle.of(PT_BR, Map.of("test.scope.count", "Tela {0}")));
        Locale enclosing = I18n.pushScope(PT_BR);
        try {
            assertEquals("Tela 7", count.format("7"));
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("Display 7", count.format("7"));
    }

    @Test
    void theNumberingSystemFollowsTheScopeAndTheOverrideStillWinsEverywhere() {
        Locale arabic = Locale.forLanguageTag("ar");
        assertEquals("42", I18n.localizeDigits("42"));
        Locale enclosing = I18n.pushScope(arabic);
        try {
            assertEquals(NumberingSystem.ARAB, I18n.numberingSystem(),
                    "an Arabic subtree writes Arabic-Indic digits with no second axis");
            assertEquals("٤٢", I18n.localizeDigits("42"));
            I18n.setNumberingSystem(NumberingSystem.LATN);
            try {
                assertEquals("42", I18n.localizeDigits("42"),
                        "the declared override is a statement about the process and wins "
                                + "inside every scope");
            } finally {
                I18n.setNumberingSystem(null);
            }
        } finally {
            I18n.popScope(enclosing);
        }
    }

    @Test
    void orderAndCaseFollowTheScopeToo() {
        // ADR 034's text locale derives from the effective locale, so its whole-string fold
        // answers for the subtree in scope — the Turkish dotted İ is the classic witness.
        Locale enclosing = I18n.pushScope(Locale.forLanguageTag("tr"));
        try {
            assertEquals("İ", I18n.toUpperCase("i"));
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("I", I18n.toUpperCase("i"), "and the process language folds as before");
    }

    // ------------------------------------------------------------- the ledger

    @Test
    void retainingPreparesEveryBundleSoNoFileReadLandsInAPass() {
        PropertyBundle files = PropertyBundle.family("/limn/i18n/test-domain");
        register(files);
        Locale pt = Locale.forLanguageTag("pt");
        assertFalse(files.resident(pt));

        I18n.retainLocale(pt);
        try {
            assertTrue(files.resident(pt), "prepared at the retain, which is the moment a "
                    + "subtree declares the language, never inside its first measure");
        } finally {
            I18n.releaseLocale(pt);
        }
        assertFalse(files.resident(pt), "the last release drops what nothing reads");
    }

    @Test
    void theLedgerCountsAndOnlyTheLastReleaseDrops() {
        PropertyBundle files = PropertyBundle.family("/limn/i18n/test-domain");
        register(files);
        Locale pt = Locale.forLanguageTag("pt");

        I18n.retainLocale(pt);
        I18n.retainLocale(pt); // a second subtree declares the same language
        I18n.releaseLocale(pt);
        assertTrue(files.resident(pt), "one declaration still stands");
        I18n.releaseLocale(pt);
        assertFalse(files.resident(pt));

        I18n.releaseLocale(pt); // releasing what was never retained is a no-op
    }

    @Test
    void aLateBundleIsPreparedForRetainedLocalesToo() {
        Locale pt = Locale.forLanguageTag("pt");
        I18n.retainLocale(pt);
        try {
            PropertyBundle late = PropertyBundle.family("/limn/i18n/test-domain");
            register(late);
            assertTrue(late.resident(pt),
                    "a bundle registered after the retain must still never read a file "
                            + "inside a pass");
        } finally {
            I18n.releaseLocale(pt);
        }
    }

    @Test
    void switchingTheProcessAwayReleasesTheOldLanguageUnlessRetained() {
        PropertyBundle files = PropertyBundle.family("/limn/i18n/test-domain");
        register(files);
        Locale pt = Locale.forLanguageTag("pt");
        Locale ja = Locale.JAPANESE;

        I18n.setLocale(pt);
        assertTrue(files.resident(pt));
        I18n.setLocale(ja);
        assertFalse(files.resident(pt),
                "nothing reads Portuguese any more: the promise that a process visiting "
                        + "ten languages does not hold ten tables");

        I18n.setLocale(pt);
        I18n.retainLocale(pt);
        try {
            I18n.setLocale(ja);
            assertTrue(files.resident(pt),
                    "a subtree still reads Portuguese; the switch must not drop it");
        } finally {
            I18n.releaseLocale(pt);
        }
        assertFalse(files.resident(pt), "released after the switch: dropped then");
    }

    @Test
    void twoLanguagesAlternatingResolveCorrectlyAndBothStayResident() {
        // The failure the multi-table residency exists to prevent: a Hebrew pane beside an
        // English one asks for both languages every frame, and a bundle that swapped its
        // one resident table would re-read both files per frame, forever. Both tables stay,
        // and the string's one-slot memo re-resolves per alternation without ever serving
        // one pane the other's language.
        register(PropertyBundle.of(HEBREW, Map.of("test.scope.alternating", "כך")));
        AtomicInteger lookups = new AtomicInteger();
        register((key, locale) -> {
            lookups.incrementAndGet();
            return null;
        });
        I18nString s = new I18nString("test.scope.alternating", "Either");
        for (int i = 0; i < 25; i++) {
            assertEquals("Either", s.get());
            Locale enclosing = I18n.pushScope(HEBREW);
            try {
                assertEquals("כך", s.get());
            } finally {
                I18n.popScope(enclosing);
            }
        }
        assertTrue(lookups.get() >= 50, "one slot, so each alternation re-resolves: the "
                + "recorded price of two languages genuinely on one screen, a hash walk "
                + "and never a file read");

        PropertyBundle files = PropertyBundle.family("/limn/i18n/test-domain");
        register(files);
        Locale pt = Locale.forLanguageTag("pt");
        assertEquals("Guardar", files.lookup("test.shared", pt));
        assertEquals("保存", files.lookup("test.shared", Locale.JAPANESE));
        assertTrue(files.resident(pt) && files.resident(Locale.JAPANESE),
                "a second language's lazy load does not evict the first");
    }
}
