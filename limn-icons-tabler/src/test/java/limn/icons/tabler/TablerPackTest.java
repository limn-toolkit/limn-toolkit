package limn.icons.tabler;

import org.junit.jupiter.api.Test;

import limn.graphics.Icon;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated enums and the generated blob have to describe the same set. They are two
 * outputs of one script, so nothing but a test stops them drifting: a constant whose
 * drawing is missing compiles perfectly and fails at the moment someone draws it.
 */
class TablerPackTest {

    /**
     * Every generated enum, found by listing the package rather than by a hand-kept list:
     * one that had to be maintained would go stale in exactly the release that added a
     * category, which is the release this test exists for.
     */
    @SuppressWarnings("unchecked")
    private static List<Class<? extends TablerIcon>> enums() throws Exception {
        // Anchored on a CLASS file, not on the package path: Gradle keeps compiled classes
        // and resources in separate output trees, and the package path resolves to the one
        // holding the blob, where there is not a single .class to find.
        URL url = TablerIcon.class.getResource("TablerIcon.class");
        File directory = new File(url.toURI()).getParentFile();
        List<Class<? extends TablerIcon>> found = new ArrayList<>();
        for (String file : java.util.Objects.requireNonNull(directory.list())) {
            if (!file.endsWith(".class") || file.contains("$")) {
                continue;
            }
            Class<?> type = Class.forName("limn.icons.tabler." + file.substring(0, file.length() - 6));
            if (type.isEnum() && TablerIcon.class.isAssignableFrom(type)) {
                found.add((Class<? extends TablerIcon>) type);
            }
        }
        return found;
    }

    @Test
    void everyConstantOfEveryEnumHasADrawingInTheBlob() throws Exception {
        List<Class<? extends TablerIcon>> types = enums();
        assertTrue(types.size() > 30, "expected the category enums, found " + types.size());

        Set<String> seen = new HashSet<>();
        int constants = 0;
        List<String> missing = new ArrayList<>();
        for (Class<? extends TablerIcon> type : types) {
            for (TablerIcon icon : type.getEnumConstants()) {
                constants++;
                if (!Tabler.has(icon.iconName())) {
                    missing.add(type.getSimpleName() + "." + icon + " -> " + icon.iconName());
                }
                assertTrue(seen.add(icon.iconName()),
                        "two constants claim the name " + icon.iconName());
            }
        }
        assertTrue(missing.isEmpty(), "constants with no drawing in the blob: " + missing);
        assertEquals(Tabler.names().size(), constants,
                "the enums and the blob should describe the same outline set");
    }

    @Test
    void aFilledVariantIsOfferedOnlyWhereOneExists() {
        int filled = 0;
        for (String name : Tabler.names()) {
            if (Tabler.hasFilled(name)) {
                filled++;
                Tabler.filled(name); // resolves rather than throwing
            } else {
                assertThrows(NoSuchElementException.class, () -> Tabler.filled(name),
                        "asking for a filled variant that does not exist must not answer the "
                                + "outline one by accident");
            }
        }
        assertTrue(filled > 0 && filled < Tabler.names().size(),
                "some but not all icons have a filled twin; found " + filled);
    }

    @Test
    void oneNameIsOneSharedIcon() {
        // What keeps a hundred buttons carrying the same icon down to one rasterized bitmap.
        assertSame(TablerSystem.TRASH.icon(), TablerSystem.TRASH.icon());
        assertSame(TablerSystem.TRASH.icon(), Tabler.outline("trash"));
    }

    @Test
    void anUnknownNameFailsWhereItIsAskedFor() {
        NoSuchElementException failure = assertThrows(NoSuchElementException.class,
                () -> Tabler.outline("definitely-not-an-icon"));
        assertTrue(failure.getMessage().contains("definitely-not-an-icon"), failure.getMessage());
        assertFalse(Tabler.has("definitely-not-an-icon"));
    }

    @Test
    void theEnumsCoverTheCatalogueAndNothingElse() throws Exception {
        Set<String> fromEnums = new HashSet<>();
        for (Class<? extends TablerIcon> type : enums()) {
            for (TablerIcon icon : type.getEnumConstants()) {
                fromEnums.add(icon.iconName());
            }
        }
        Set<String> fromBlob = new HashSet<>(Tabler.names());
        Set<String> onlyInBlob = new HashSet<>(fromBlob);
        onlyInBlob.removeAll(fromEnums);
        Set<String> onlyInEnums = new HashSet<>(fromEnums);
        onlyInEnums.removeAll(fromBlob);
        assertTrue(onlyInBlob.isEmpty(), "drawings no constant names: " + limited(onlyInBlob));
        assertTrue(onlyInEnums.isEmpty(), "constants with no drawing: " + limited(onlyInEnums));
    }

    private static String limited(Set<String> names) {
        return names.stream().sorted().limit(20).toList() + (names.size() > 20
                ? " (and " + (names.size() - 20) + " more)" : "");
    }

    /**
     * The catalogue is a field read, not a walk of several thousand keys. The Javadoc promised
     * "computed once" while the body rebuilt the list per call, and the demo's own picker asked
     * for it inside a search field's change handler: a full catalogue walk per keystroke.
     */
    @Test
    void namesIsBuiltOnceAndHandedBack() {
        assertSame(Tabler.names(), Tabler.names());
        assertFalse(Tabler.names().isEmpty());
    }

    /**
     * The pack must not hold icons strongly: an {@link limn.graphics.SvgIcon} pins up to sixteen
     * rasterized pictures, each an on-heap RGBA array, so a strong cache keeps every icon ever
     * drawn at every size it was drawn at for the life of the process, which a scrolling picker
     * reaches in seconds.
     *
     * <p>Asserted structurally rather than by collecting one. A {@code SoftReference} is cleared
     * only when the JVM is close to exhausting the heap, so the behavioural version of this test
     * has to manufacture real memory pressure, and a test that fills the heap to prove a point
     * takes the rest of the suite down with it when it misjudges. What is checked here is the
     * decision itself: the value in the cache is a reference, not an icon.
     */
    @Test
    void thePackHoldsItsIconsThroughAReference() throws Exception {
        Icon held = Tabler.outline("trash"); // populate the entry, and keep it alive to read it

        java.lang.reflect.Field icons = Tabler.class.getDeclaredField("ICONS");
        icons.setAccessible(true);
        Object entry = ((java.util.Map<?, ?>) icons.get(null)).get("outline/trash");

        assertNotNull(entry, "the icon was just built, so the cache must have an entry");
        assertTrue(entry instanceof java.lang.ref.Reference,
                "icons must be cached through a reference; a strong entry pins their bitmaps"
                        + " for the life of the process");
        assertSame(held, ((java.lang.ref.Reference<?>) entry).get());
    }

    /** And while anyone does hold one, everybody asking for that name gets the same instance. */
    @Test
    void aHeldIconIsStillSharedBetweenCallers() {
        Icon held = Tabler.outline("trash");
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
        }
        assertSame(held, Tabler.outline("trash"),
                "sharing is what makes a hundred buttons hold one bitmap");
    }
}
