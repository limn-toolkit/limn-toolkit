package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Finding 5, asserted: a name goes in one of AppKit's two attributes, and which one is not a guess. */
class AxNamesTest {

    @Test
    void onlyTheControlsOwnTextIsATitle() {
        assertEquals(AxNames.Attribute.TITLE,
                AxNames.attributeFor(Accessible.NameFrom.CONTENT));
    }

    @Test
    void everyOtherProvenanceIsALabel() {
        for (Accessible.NameFrom from : Accessible.NameFrom.values()) {
            if (from == Accessible.NameFrom.CONTENT) continue;
            assertEquals(AxNames.Attribute.LABEL, AxNames.attributeFor(from),
                    from + " is text ABOUT the control, which is what AXDescription is for");
        }
    }
}
