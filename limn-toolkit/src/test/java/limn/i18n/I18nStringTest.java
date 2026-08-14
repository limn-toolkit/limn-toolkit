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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nStringTest {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final List<StringBundle> registered = new ArrayList<>();
    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
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

    @Test
    void withoutBundlesAStringIsItsEnglish() {
        I18nString hello = new I18nString("test.plain.hello", "Hello");
        assertEquals("Hello", hello.get());
        I18n.setLocale(PT_BR);
        assertEquals("Hello", hello.get(), "no bundle can answer, so English stands");
    }

    @Test
    void aBundleAnswersAndTheLocaleSwitchIsSeenWithoutAnySubscription() {
        I18nString hello = new I18nString("test.switch.hello", "Hello");
        register(PropertyBundle.of(PT_BR, Map.of("test.switch.hello", "Olá")));

        assertEquals("Hello", hello.get());
        I18n.setLocale(PT_BR);
        assertEquals("Olá", hello.get());
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Hello", hello.get());
    }

    @Test
    void theCacheIsRebuiltOnlyWhenTheEpochMoves() {
        AtomicInteger lookups = new AtomicInteger();
        I18nString counted = new I18nString("test.cache.key", "English");
        register((key, locale) -> {
            lookups.incrementAndGet();
            return "test.cache.key".equals(key) ? "translated" : null;
        });

        int afterRegistration = lookups.get();
        for (int i = 0; i < 50; i++) {
            assertEquals("translated", counted.get());
        }
        assertEquals(afterRegistration + 1, lookups.get(), "50 reads, one resolution");

        I18n.setLocale(PT_BR);
        assertEquals("translated", counted.get());
        assertEquals(afterRegistration + 2, lookups.get(), "the switch invalidates exactly once");
    }

    @Test
    void registeringABundleIsItselfATextChange() {
        I18nString late = new I18nString("test.late.key", "English");
        assertEquals("English", late.get()); // resolved and cached before the bundle exists
        register(PropertyBundle.of(Locale.ENGLISH, Map.of("test.late.key", "Late")));
        assertEquals("Late", late.get(), "a downloaded bundle must cure what already resolved");
    }

    @Test
    void newestBundleWins() {
        I18nString key = new I18nString("test.override.key", "English");
        register(PropertyBundle.of(Locale.ENGLISH, Map.of("test.override.key", "toolkit")));
        assertEquals("toolkit", key.get());
        register(PropertyBundle.of(Locale.ENGLISH, Map.of("test.override.key", "application")));
        assertEquals("application", key.get(), "an app overrides what the toolkit installed");
    }

    @Test
    void aBundleThatDoesNotOwnAKeyDefersToTheNextOne() {
        I18nString mine = new I18nString("test.defer.mine", "Mine");
        register(PropertyBundle.of(Locale.ENGLISH, Map.of("test.defer.mine", "found")));
        register((key, locale) -> null); // a screen bundle with nothing to say about this key
        assertEquals("found", mine.get());
    }

    @Test
    void literalsAreNeverTranslatedAndCostNoLookup() {
        I18nString literal = I18nString.literal("Untranslatable");
        register((key, locale) -> "should never be asked");

        assertTrue(literal.isLiteral());
        assertNull(literal.key());
        assertEquals("Untranslatable", literal.get());
        I18n.setLocale(PT_BR);
        assertEquals("Untranslatable", literal.get());
    }

    @Test
    void valueEqualityIsWhatKeepsAComponentsUnchangedCheckWorking() {
        assertEquals(I18nString.literal("Save"), I18nString.literal("Save"));
        assertEquals(I18nString.literal("Save").hashCode(), I18nString.literal("Save").hashCode());
        assertNotEquals(I18nString.literal("Save"), I18nString.literal("Cancel"));
        assertNotEquals(I18nString.literal("Save"), new I18nString("test.equality.save", "Save"),
                "a keyed string and a literal are different values even when they read alike");
    }

    @Test
    void argumentsAreSubstitutedAndNeverCached() {
        I18nString message = new I18nString("test.args.display", "Display {0}");
        assertEquals("Display 1", message.format("1"));
        assertEquals("Display 2", message.format("2"));
        assertEquals("Display {0}", message.get(), "get() does not substitute");
    }

    @Test
    void anApostropheOnlyNeedsEscapingWhereMessageFormatRuns() {
        I18nString plain = new I18nString("test.args.plain", "Can't open the file");
        assertEquals("Can't open the file", plain.get(), "no args, no MessageFormat, no escaping");
    }

    @Test
    void aTranslatorsMalformedPatternFallsBackInsteadOfThrowing() {
        I18nString message = new I18nString("test.args.broken", "Display {0}");
        register(PropertyBundle.of(Locale.ENGLISH, Map.of("test.args.broken", "Écran {0")));
        assertEquals("Display 3", message.format("3"), "a bad bundle must not break a paint");
    }

    @Test
    void declaringOneKeyTwiceWithDifferentEnglishIsABug() {
        new I18nString("test.duplicate.key", "First");
        assertSame(I18nString.class, new I18nString("test.duplicate.key", "First").getClass(),
                "re-declaring the same pair is fine: a class may initialise more than once");
        assertThrows(IllegalStateException.class,
                () -> new I18nString("test.duplicate.key", "Second"));
    }

    @Test
    void everyDeclaredKeyIsInTheCatalog() {
        new I18nString("test.catalog.key", "Catalogued");
        Map<String, String> declared = I18n.declaredKeys();
        assertEquals("Catalogued", declared.get("test.catalog.key"));
        assertFalse(declared.containsKey(I18nString.literal("nothing").key() + ""),
                "literals declare nothing");
    }

    @Test
    void switchingToTheSameLocaleChangesNothing() {
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        I18n.addChangeListener(listener);
        try {
            long epoch = I18n.epoch();
            I18n.setLocale(I18n.locale());
            assertEquals(epoch, I18n.epoch());
            assertEquals(0, notifications.get());
        } finally {
            I18n.removeChangeListener(listener);
        }
    }

    @Test
    void bundlesArePreparedBeforeListenersRun() {
        List<String> order = new ArrayList<>();
        register(new StringBundle() {
            @Override
            public void prepare(Locale locale) {
                order.add("prepare");
            }

            @Override
            public String lookup(String key, Locale locale) {
                return null;
            }
        });
        Runnable listener = () -> order.add("notify");
        I18n.addChangeListener(listener);
        try {
            order.clear();
            I18n.setLocale(PT_BR);
            assertEquals(List.of("prepare", "notify"), order,
                    "a file-backed bundle must not read from disk inside the relayout it caused");
        } finally {
            I18n.removeChangeListener(listener);
        }
    }
}
