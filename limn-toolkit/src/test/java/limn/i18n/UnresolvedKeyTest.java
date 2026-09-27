package limn.i18n;

import limn.concurrent.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A key nothing translates is heard once, by whoever asked, and only where the English that
 * replaced it was not the intended text (ADR 006 §5).
 */
class UnresolvedKeyTest {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final List<StringBundle> registered = new ArrayList<>();
    private final List<UnresolvedKey> heard = new ArrayList<>();
    private Subscription listening;
    private Locale original;

    @BeforeEach
    void listen() {
        original = I18n.locale();
        I18n.setLocale(PT_BR);
        listening = I18n.observeUnresolvedKeys(heard::add);
    }

    /** Process-wide statics: a test that leaks a bundle or a listener breaks every later one. */
    @AfterEach
    void restore() {
        listening.cancel();
        registered.forEach(I18n::removeBundle);
        registered.clear();
        I18n.setLocale(original);
    }

    private void register(StringBundle bundle) {
        registered.add(bundle);
        I18n.addBundle(bundle);
    }

    @Test
    void aKeyNothingTranslatesIsReportedOnceWithWhatWasShownInstead() {
        register(PropertyBundle.of(PT_BR, Map.of("test.unresolved.there", "Aqui")));
        I18nString there = new I18nString("test.unresolved.there", "Here");
        I18nString typo = new I18nString("test.unresolved.tpyo", "Typo");

        assertEquals("Aqui", there.get());
        assertEquals("Typo", typo.get(), "the English still reaches the screen");
        assertEquals("Typo", I18n.resolve("test.unresolved.tpyo", "Typo", PT_BR));

        assertEquals(List.of(new UnresolvedKey("test.unresolved.tpyo", "Typo", PT_BR)), heard,
                "the translated key is not a miss, and the typo is heard once, not per read");
    }

    @Test
    void englishIsTheDeclaredTextAndNeverAMiss() {
        I18nString plain = new I18nString("test.unresolved.english", "Plain");
        I18n.setLocale(Locale.UK);
        assertEquals("Plain", plain.get());
        assertEquals(List.of(), heard);
    }

    @Test
    void aMissingPluralFormFallsBackQuietlyAndOnlyNoFormAtAllIsReported() {
        register(PropertyBundle.of(PT_BR, Map.of(
                "test.unresolved.files.other", "{0} arquivos")));
        PluralString files = PluralString.of("test.unresolved.files", "{0} file", "{0} files");
        assertEquals("1 arquivos", files.format(1),
                "pt-BR's own other form stands in for the missing one, deliberately");
        assertEquals(List.of(), heard);

        PluralString none = PluralString.of("test.unresolved.none", "{0} item", "{0} items");
        assertEquals("2 items", none.format(2));
        assertEquals(List.of(new UnresolvedKey("test.unresolved.none.other", "{0} items", PT_BR)),
                heard);
    }

    @Test
    void aBundleChangeLetsTheSameMissBeHeardAgain() {
        I18nString late = new I18nString("test.unresolved.late", "Late");
        late.get();
        register(PropertyBundle.of(Locale.JAPANESE, Map.of("test.unresolved.other", "x")));
        late.get();
        assertEquals(2, heard.size(), "a new bundle could have translated it, so it is asked again");
    }

    @Test
    void aCancelledSubscriptionHearsNothingMore() {
        listening.cancel();
        listening.cancel();
        new I18nString("test.unresolved.after", "After").get();
        assertEquals(List.of(), heard);
    }

    @Test
    void aListenerThatThrowsDoesNotReachTheText() {
        Subscription loud = I18n.observeUnresolvedKeys(miss -> {
            throw new IllegalStateException("fail the build");
        });
        try {
            assertEquals("Loud", new I18nString("test.unresolved.loud", "Loud").get());
            assertEquals(1, heard.size(), "the other listener still heard it");
        } finally {
            loud.cancel();
        }
    }
}
