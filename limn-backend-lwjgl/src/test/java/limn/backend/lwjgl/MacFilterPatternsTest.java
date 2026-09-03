package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macOS filter-pattern expansion. Every dynamic UTI pinned here was
 * measured from macOS itself (System Events' {@code type identifier} of a
 * probe file carrying that extension, cross-checked against Swift's
 * {@code UTType(filenameExtension:)}), so the expectations are the system's
 * own derivations, not values the encoder once produced.
 */
class MacFilterPatternsTest {

    @Test
    void dynamicUtiMatchesTheSystemsOwnDerivation() {
        assertEquals("dyn.ah62d4rv4ge80c25rqy", MacFilterPatterns.dynamicUti("acme"));
        assertEquals("dyn.ah62d4rv4ge8024prr2", MacFilterPatterns.dynamicUti("limn"));
        assertEquals("dyn.ah62d4rv4ge81c", MacFilterPatterns.dynamicUti("q"));
        assertEquals("dyn.ah62d4rv4ge80c25rqz2he55nqzv1k",
                MacFilterPatterns.dynamicUti("acmeproject"));
        assertEquals("dyn.ah62d4rv4ge80455egk", MacFilterPatterns.dynamicUti("mod2"));
        assertEquals("dyn.ah62d4rv4ge8048mrqz6hk", MacFilterPatterns.dynamicUti("my-ext"));
    }

    @Test
    void allowlistEdgeCharactersWereMeasuredToo() {
        assertEquals("dyn.ah62d4rv4ge8048n9qz6hk", MacFilterPatterns.dynamicUti("my_ext"));
        assertEquals("dyn.ah62d4rv4ge80q55tfrvgc6u", MacFilterPatterns.dynamicUti("foo+bar"));
    }

    @Test
    void extensionsAreLowercasedLikeTheSystemDoes() {
        assertEquals(MacFilterPatterns.dynamicUti("acme"), MacFilterPatterns.dynamicUti("ACME"));
        assertEquals(MacFilterPatterns.dynamicUti("acme"), MacFilterPatterns.dynamicUti("Acme"));
    }

    @Test
    void simpleExtensionPatternGainsItsDynamicTwin() {
        // The twin keeps the "*." envelope: tinyfd strips the first two chars of
        // every pattern unconditionally, so this is what lands in AppleScript
        // as {"acme","dyn.ah62d4rv4ge80c25rqy"}.
        assertEquals(List.of("*.acme", "*.dyn.ah62d4rv4ge80c25rqy"),
                MacFilterPatterns.expand(List.of("*.acme"), "Open", ""));
    }

    @Test
    void eachPatternKeepsItsTwinAdjacent() {
        List<String> out = MacFilterPatterns.expand(List.of("*.png", "*.limn"), "Open", "");
        assertEquals(4, out.size());
        assertEquals("*.png", out.get(0));
        assertTrue(out.get(1).startsWith("*.dyn.a")); // inert for a registered type, but sent
        assertEquals("*.limn", out.get(2));
        assertEquals("*.dyn.ah62d4rv4ge8024prr2", out.get(3));
    }

    @Test
    void anythingButASimpleExtensionPassesThroughUntouched() {
        for (String pattern : List.of("*", "*.", "*.tar.gz", "foo.acme", "*acme",
                "*.ac*me", "*.a?me", "dir/*.acme", "*.acme ", "*.é", "")) {
            assertEquals(List.of(pattern),
                    MacFilterPatterns.expand(List.of(pattern), "Open", ""), pattern);
        }
    }

    @Test
    void typicalFilterExpandsEvenWithARealTitleAndPath() {
        List<String> out = MacFilterPatterns.expand(List.of("*.png", "*.jpg", "*.jpeg"),
                "Open image", "/Users/someone/Pictures/screenshots/2026/");
        assertEquals(6, out.size());
    }

    @Test
    void oversizedFilterListsAreLeftAloneRatherThanOverflowTinyfd() {
        List<String> many = IntStream.range(0, 40).mapToObj(i -> "*.someext" + i).toList();
        assertEquals(many, MacFilterPatterns.expand(many, "", ""));
    }

    @Test
    void titleAndLocationCountAgainstTinyfdsCommandBuffer() {
        // tinyfd strcats scaffolding + title + location + the type list into one
        // 1024-byte buffer. 15 patterns fit with nothing else in the command
        // (478 fixed + 15×33 = 973 of the 1000 allowed), but a real title plus
        // a deep directory must push the expansion into backing off (the
        // pre-fix behavior) instead of overflowing natively.
        List<String> many = IntStream.range(10, 25).mapToObj(i -> "*.ex" + i).toList();
        assertEquals(30, MacFilterPatterns.expand(many, "", "").size());
        assertEquals(many, MacFilterPatterns.expand(many, "Open project assets",
                "/Users/someone/works/clients/acme/projects/2026/redesign/assets/textures/environment/"));
    }

    @Test
    void unpairedSurrogatesCountAsTheMarshallerEncodesThem() {
        // LWJGL's UTF-8 marshaller writes an unpaired surrogate as 3 bytes where
        // String.getBytes shrinks it to a 1-byte '?'. On the 973-byte list above,
        // 12 of them must tip the command over the limit (973+36 > 1000); the
        // 1-byte undercount (973+12) would wrongly let the expansion through.
        List<String> many = IntStream.range(10, 25).mapToObj(i -> "*.ex" + i).toList();
        assertEquals(many, MacFilterPatterns.expand(many, "\uDC00".repeat(12), ""));
    }

    @Test
    void anOrdinaryTitleAndPathPassThroughFitUntouched() {
        MacFilterPatterns.Bounded in = MacFilterPatterns.fit("Save as…",
                "/Users/someone/works/clients/acme/projects/2026/redesign/ocean-deep.limntheme");
        assertEquals("Save as…", in.title());
        assertEquals("/Users/someone/works/clients/acme/projects/2026/redesign/ocean-deep.limntheme",
                in.location());
    }

    @Test
    void aLocationThatCannotFitIsDroppedBeforeTheTitleIsTouched() {
        // The shape that aborted the process: a theme whose name is a few
        // hundred letters, saved from a deep working directory. The panel then
        // opens on its default folder with the caller's title intact.
        String location = "/Users/someone/works/" + "a".repeat(700) + ".limntheme";
        MacFilterPatterns.Bounded in = MacFilterPatterns.fit("Save as…", location);
        assertEquals("Save as…", in.title());
        assertEquals("", in.location());
    }

    @Test
    void aTitleThatCannotFitAloneIsCutOnACodePointBoundary() {
        // 400 four-byte code points: 1600 bytes, over the buffer twice.
        String title = "\uD83C\uDF0A".repeat(400);
        MacFilterPatterns.Bounded in = MacFilterPatterns.fit(title, "/tmp/x");
        assertEquals("", in.location());
        assertTrue(in.title().length() < title.length());
        assertEquals(0, in.title().length() % 2, "never half a surrogate pair");
        assertTrue(title.startsWith(in.title()));
        // And what is left is in bounds with the scaffolding counted the same
        // way expand() counts it, plus the margin nothing may eat into.
        assertTrue(in.title().length() / 2 * 4 <= 1000 - (430 + 15 + 20 + 13),
                "the cut title leaves the command under the buffer with the margin intact");
        assertTrue((in.title().length() / 2 + 1) * 4 > 1000 - (430 + 15 + 20 + 13),
                "and is the longest prefix that does");
    }

    @Test
    void fitTakesNullsAsTinyfdDoes() {
        MacFilterPatterns.Bounded in = MacFilterPatterns.fit(null, null);
        assertEquals("", in.title());
        assertEquals("", in.location());
    }

    @Test
    void patternsShorterThanTinyfdsStripDefeatAllAccountingSoNothingExpands() {
        // tinyfd unconditionally skips two bytes of every pattern; a shorter one
        // makes it strcat from past the terminator, so the emitted entry cannot
        // be sized: the whole list stays exactly as the caller sent it. (In
        // production sanitize() removes such patterns before expand() runs;
        // this back-off keeps expand safe on its own.)
        assertEquals(List.of("*", "*.acme"),
                MacFilterPatterns.expand(List.of("*", "*.acme"), "Open", ""));
        assertEquals(List.of("", "*.acme"),
                MacFilterPatterns.expand(List.of("", "*.acme"), "Open", ""));
    }

    @Test
    void matchAllAnywhereMakesTheWholeFilterANoOp() {
        // "*" admits every file, and a filter is the union of its patterns:
        // sending only the survivors would narrow what the caller asked for,
        // so the dialog must get no type clause at all.
        assertEquals(List.of(), MacFilterPatterns.sanitize(List.of("*")));
        assertEquals(List.of(),
                MacFilterPatterns.sanitize(List.of("*.acme", "*", "*.limn")));
    }

    @Test
    void subStripPatternsAreDroppedAndTheRestStillFilter() {
        // Fewer than the two bytes tinyfd skips: it would strcat from past the
        // terminator. "" and one-byte globs are inexpressible on macOS anyway.
        assertEquals(List.of("*.acme"),
                MacFilterPatterns.sanitize(List.of("", "*.acme")));
        assertEquals(List.of("*.acme"),
                MacFilterPatterns.sanitize(List.of("?", "*.acme", "x")));
        assertEquals(List.of(), MacFilterPatterns.sanitize(List.of("")));
        assertEquals(List.of(), MacFilterPatterns.sanitize(List.of("", "?")));
    }

    @Test
    void deliverablePatternsSurviveByteForByte() {
        // The criterion is UTF-8 bytes as the marshaller writes them, not char
        // count: "é" is two bytes and an unpaired surrogate three, so both are
        // safe to strip from: tinyfd then emits a useless-but-bounded entry,
        // the same pre-existing fate as any pattern not starting with "*.".
        List<String> safe = List.of("*.acme", "*.tar.gz", "**", "é", "\uDC00");
        assertEquals(safe, MacFilterPatterns.sanitize(safe));
    }

    @Test
    void embeddedNulEndsThePatternWhereTheNativeStringEnds() {
        // C strings stop at the first NUL, so that is the form to judge and to
        // forward: "*\0…" is a bare "*" to tinyfd (match-all), "x\0…" is one
        // byte however long the Java string is, and bytes past a survivor's
        // NUL never reach the command.
        assertEquals(List.of(), MacFilterPatterns.sanitize(List.of("*\0.acme")));
        assertEquals(List.of("*.acme"),
                MacFilterPatterns.sanitize(List.of("x\0long", "*.acme")));
        assertEquals(List.of("*.acme"),
                MacFilterPatterns.sanitize(List.of("*.acme\0junk")));
    }
}
