package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.Shape;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@link Expander} to an assistive technology (ADR 067 section 3): its header a button that is
 * expanded or collapsed, named by the title, described by the summary, controlling the content,
 * which the header names back; opened and closed by a reader's verbs, refused the one the state
 * does not allow.
 */
class ExpanderAccessibilityTest extends AccessibleComponentTestBase {

    private Expander section;
    private Button inside;

    private void bindSection() {
        inside = new Button("Apply");
        Column body = new Column();
        body.add(inside);
        section = new Expander("Filters", body).setSummary("2 on");
        Column root = new Column();
        root.add(section);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
    }

    @Test
    void theHeaderIsAButtonThatDisclosesTheContent() {
        bindSection();
        AccessibleNode header = node("Filters");
        assertEquals(Accessible.Role.BUTTON, header.role());
        assertEquals(Shape.POPUP_OWNER, Shape.of(header), "a button that discloses in place");
        assertEquals("2 on", header.description());
        assertFalse(header.expand().expanded());
        assertTrue(header.accepts(Accessible.Action.EXPAND));
        assertFalse(header.accepts(Accessible.Action.COLLAPSE));

        section.setExpanded(true);
        settleAnimations(null);
        header = node("Filters");
        assertTrue(header.expand().expanded());
        assertTrue(header.has(Accessible.State.EXPANDED));
        assertTrue(header.accepts(Accessible.Action.COLLAPSE));
        AccessibleNode content = null;
        for (AccessibleRelation relation : header.relations()) {
            if (relation.kind() == Accessible.Relation.CONTROLLER_FOR) {
                content = node(relation.target());
            }
        }
        assertTrue(content != null, describe(tree()));
        assertEquals(Accessible.Role.GROUP, content.role());
        assertEquals("Filters", content.name(), "named by the header it opens under");
        assertTrue(node("Apply").has(Accessible.State.SHOWING), "and what is in it is there");
    }

    /**
     * What slides shut stays published, and enabled as the application left it, but offers no verb
     * and performs none, as the options of a combo's list fading out do (ADR 068): the scene's gate
     * refuses a verb taken off an older snapshot as well. Open again, it is worked as before.
     */
    @Test
    void aReadersVerbsInsideAreRefusedWhileItCloses() throws InterruptedException {
        bindSection();
        java.util.List<String> pressed = new java.util.ArrayList<>();
        inside.onAction(() -> pressed.add("apply"));
        section.setExpanded(true);
        settleAnimations(null);
        long apply = node("Apply").id();
        assertTrue(node("Apply").accepts(Accessible.Action.PRESS), describe(tree()));

        section.setExpanded(false);
        advanceTime(50, null);
        assertTrue(section.isRevealing(), "still sliding shut");

        AccessibleNode closing = node("Apply");
        assertTrue(closing.has(Accessible.State.ENABLED), "published as the application left it");
        assertFalse(closing.accepts(Accessible.Action.PRESS), "and offering no verb: " + describe(tree()));
        assertFalse(closing.accepts(Accessible.Action.FOCUS));
        perform(apply, Accessible.Action.PRESS, Accessible.Argument.NONE);
        perform(apply, Accessible.Action.FOCUS, Accessible.Argument.NONE);
        frame();
        assertEquals(java.util.List.of(), pressed, "a verb from the snapshot before is refused too");
        assertFalse(inside.isFocused());

        section.setExpanded(true);
        settleAnimations(null);
        assertTrue(node("Apply").accepts(Accessible.Action.PRESS), "open again, the verb is back");
        assertTrue(perform(node("Apply").id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(java.util.List.of("apply"), pressed);
    }

    @Test
    void aReaderOpensAndClosesItAndIsRefusedWhatTheStateDoesNotAllow() throws InterruptedException {
        bindSection();
        long header = node("Filters").id();
        // A perform answers whether the scene took the request, not what the widget did with it:
        // what is refused shows as nothing changing.
        perform(header, Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        assertFalse(section.isExpanded(), "a closed section is not closed again into opening");
        assertTrue(perform(header, Accessible.Action.EXPAND, Accessible.Argument.NONE));
        assertTrue(section.isExpanded());
        perform(header, Accessible.Action.EXPAND, Accessible.Argument.NONE);
        assertTrue(section.isExpanded(), "nor an open one toggled shut by EXPAND");
        assertTrue(perform(header, Accessible.Action.PRESS, Accessible.Argument.NONE));
        assertFalse(section.isExpanded(), "a press toggles");
    }
}
