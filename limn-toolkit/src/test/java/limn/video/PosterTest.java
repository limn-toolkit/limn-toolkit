package limn.video;

import limn.graphics.Image;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link Poster}'s shape (ADR 069): made only by its factories, so there is one way to each value,
 * and a time given and read back as a {@link Duration}, the one unit the factory takes. Read by
 * reflection where the shape is the point, so a constructor or an accessor that comes back is a
 * failing test and not a build that stops elsewhere.
 */
class PosterTest {

    @Test
    void eachKindIsMadeOnlyByItsFactory() {
        for (Class<?> kind : Poster.class.getPermittedSubclasses()) {
            for (Constructor<?> constructor : kind.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers())
                                || Modifier.isProtected(constructor.getModifiers()),
                        kind.getSimpleName() + " has a constructor beside the factories: " + constructor);
            }
        }
    }

    @Test
    void aPosterAtATimeGivesItsTimeBackAsADuration() throws ReflectiveOperationException {
        Poster poster = Poster.at(Duration.ofSeconds(3));
        Method time = poster.getClass().getMethod("time");
        assertEquals(Duration.class, time.getReturnType(), "the unit the factory takes");
        assertEquals(Duration.ofSeconds(3), time.invoke(poster));
        // To the microsecond a stream is moved by, as the player is asked for it.
        assertEquals(Duration.ofNanos(1_000), time.invoke(Poster.at(Duration.ofNanos(1_999))));
        assertSame(Poster.firstPicture(), Poster.at(Duration.ofNanos(999)), "under a microsecond is the start");
    }

    @Test
    void postersAreValues() {
        assertEquals(Poster.at(Duration.ofMillis(1500)), Poster.at(Duration.ofMillis(1500)));
        assertEquals(Poster.at(Duration.ofMillis(1500)).hashCode(), Poster.at(Duration.ofMillis(1500)).hashCode());
        assertNotEquals(Poster.at(Duration.ofMillis(1500)), Poster.at(Duration.ofMillis(1501)));
        Image cover = new Image(1, 1, new byte[4]);
        assertEquals(Poster.image(cover), Poster.image(cover));
        assertEquals("Poster.at(PT1.5S)", Poster.at(Duration.ofMillis(1500)).toString());
    }
}
