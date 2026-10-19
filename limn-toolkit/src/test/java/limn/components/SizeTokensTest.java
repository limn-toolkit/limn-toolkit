package limn.components;

import limn.scene.ControlSize;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size table is read, never built nor changed, from outside: a size a widget needs in a 1.x
 * minor must be able to join it without breaking a caller, which a record's public constructor and
 * deconstruction pattern forbid, and a public array would let any caller rewrite every widget's
 * rows for the whole process.
 */
class SizeTokensTest {

    /**
     * A record's canonical constructor took every token in order, so the token a 1.x widget adds
     * would have removed the constructor and the pattern a caller compiled against.
     */
    @Test
    void anApplicationCannotBuildOne() {
        assertFalse(SizeTokens.class.isRecord(), "a record's components are its contract");
        for (Constructor<?> constructor : SizeTokens.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers())
                    || Modifier.isProtected(constructor.getModifiers()), constructor + " is reachable");
        }
        assertTrue(Modifier.isFinal(SizeTokens.class.getModifiers()), "a subclass would be a second way in");
    }

    /**
     * {@code SizeTokens.TABLE[ControlSize.LARGE.ordinal()] = SizeTokens.MEDIUM} compiled and made
     * every {@code LARGE} widget in the process measure as {@code MEDIUM}: the only public fields
     * left are the one immutable default row.
     */
    @Test
    void noPublicFieldHoldsAnythingACallerCouldChange() {
        for (Field field : SizeTokens.class.getFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers), field + " can be reassigned");
            assertSame(SizeTokens.class, field.getType(), field + " exposes a mutable table");
        }
        assertSame(SizeTokens.of(ControlSize.MEDIUM), SizeTokens.MEDIUM);
    }

    /**
     * What the record gave, kept: two rows are equal when every token is, so the same table built
     * twice (a palette with its own corner scale builds its rows once) compares equal row by row.
     */
    @Test
    void rowsCompareByEveryTokenAsTheRecordDid() {
        SizeTokens[] once = SizeTokens.tableWithCornerScale(1.5f);
        SizeTokens[] again = SizeTokens.tableWithCornerScale(1.5f);
        for (ControlSize step : ControlSize.values()) {
            SizeTokens a = once[step.ordinal()];
            SizeTokens b = again[step.ordinal()];
            assertNotSame(a, b);
            assertEquals(a, b, step + " built twice");
            assertEquals(a.hashCode(), b.hashCode(), step + " built twice");
            assertNotEquals(SizeTokens.of(step), a, step + ": the corners differ");
        }
        assertNotEquals(SizeTokens.of(ControlSize.SMALL), SizeTokens.of(ControlSize.MEDIUM));
        assertSame(SizeTokens.of(ControlSize.LARGE), SizeTokens.tableWithCornerScale(1f)[ControlSize.LARGE.ordinal()],
                "at scale 1 a palette shares the process-wide rows");

        String text = SizeTokens.MEDIUM.toString();
        assertTrue(text.startsWith("SizeTokens[body="), text);
        assertTrue(text.contains(", cardPad=16.0,"), text);
        assertTrue(text.endsWith(", spinnerWidth=140.0]"), text);
    }
}
