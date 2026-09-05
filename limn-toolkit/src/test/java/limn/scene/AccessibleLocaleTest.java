package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.Accessibility;
import limn.accessibility.AccessibleEvent;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A name is resolved under the language of the subtree it is in, once, and re-resolved exactly when
 * that language moves.
 *
 * <p>The motivating case is a Hebrew interface holding a left-to-right English code pane: language
 * is a property of a subtree and not of a process, so it travels on the node and reaches the screen
 * reader's pronunciation intact. And a bridge never resolves anything: it reads a string from a
 * thread where no language scope is open, where resolving would answer in the process language
 * rather than the subtree's.
 */
class AccessibleLocaleTest extends AccessibleTestBase {

    private static final I18nString GREETING = new I18nString("greeting", "Hello");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** One bundle for both languages: a key it does not own falls through to the English. */
    private static final StringBundle GREETINGS = (key, locale) -> {
        if (!"greeting".equals(key)) {
            return null;
        }
        if (BRAZILIAN.equals(locale)) {
            return "Olá";
        }
        return Locale.FRENCH.getLanguage().equals(locale.getLanguage()) ? "Bonjour" : null;
    };

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(GREETINGS);
        I18n.setLocale(Locale.ENGLISH);
    }

    private void install() {
        I18n.addBundle(GREETINGS);
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void aSubtreeWithItsOwnLanguagePublishesItsNamesInThatLanguage() {
        install();
        Group root = new Group();
        Probe here = new Probe();
        here.role = Accessible.Role.LABEL;
        here.name = GREETING;
        Group pane = new Group();
        Probe there = new Probe();
        there.role = Accessible.Role.LABEL;
        there.name = GREETING;
        pane.add(there);
        pane.setLocale(BRAZILIAN);
        pane.setAccessibleName("pane");
        root.add(here);
        root.add(pane);
        bind(root);
        frame();

        assertEquals("Hello", tree().node(1).name(), describe(tree()));
        assertEquals("Olá", tree().node(3).name(),
                "the subtree declared its own language and its names follow it");
        assertEquals(BRAZILIAN, tree().node(3).locale(),
                "and the node says which language it is in, for the reader's pronunciation");
    }

    /**
     * The walk names some nodes itself, after the widget's hook has returned: from the tooltip
     * when nothing else named it, and from the application's override. Those are resolved under
     * the subtree's language too, or an icon-only button in a pane declaring another language
     * would record that language on its node and speak the process's.
     */
    @Test
    void aNameTheWalkSuppliesItselfFollowsTheSubtreesLanguageAsWell() {
        install();
        Group root = new Group();
        Group pane = new Group();
        Probe byTooltip = new Probe();
        byTooltip.role = Accessible.Role.BUTTON;
        byTooltip.setTooltip(GREETING);
        Probe byOverride = new Probe();
        byOverride.role = Accessible.Role.BUTTON;
        byOverride.setAccessibleName(GREETING);
        pane.add(byTooltip);
        pane.add(byOverride);
        pane.setLocale(BRAZILIAN);
        pane.setAccessibleName("pane");
        root.add(pane);
        bind(root);
        frame();

        assertEquals("Olá", tree().node(2).name(),
                "the tooltip default is resolved under the widget's language" + describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, tree().node(2).nameFrom(), describe(tree()));
        assertEquals("Olá", tree().node(3).name(),
                "and so is the application's override" + describe(tree()));
        assertEquals(BRAZILIAN, tree().node(2).locale(), describe(tree()));

        bridge.events.clear();
        pane.setLocale(null);
        frame();

        assertEquals("Hello", tree().node(2).name(),
                "and moving the subtree's language re-resolves it" + describe(tree()));
        assertEquals("Hello", tree().node(3).name(), describe(tree()));
        assertEquals(2, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED), bridge.events.toString());
    }

    /**
     * A parent that names its child names it in the CHILD's language, because that is the language
     * the node it is writing into will claim to be in.
     *
     * <p>The one hook in the toolkit that writes into a slot it does not own, and the divergence
     * it opened was invisible until a parent used it to supply a name: the walk opens the slot with
     * the child's locale and the builder stamps every name with the slot's, so resolving under the
     * parent's published a caption in one language labelled as being in another. Worse than wrong
     * once — a name is carried over from the previous frame while its slot locale and the epoch
     * both hold, so moving the parent's language re-resolved the child that named itself and left
     * the one its parent had named saying the old words, permanently, until something unrelated
     * moved the epoch.
     */
    @Test
    void aParentThatNamesItsChildResolvesInTheChildsLanguageAndNotItsOwn() {
        install();
        Namer pane = new Namer();
        Probe page = new Probe();
        page.role = Accessible.Role.GROUP;
        page.setLocale(BRAZILIAN);
        pane.add(page);
        pane.setAccessibleName("pane");
        bind(pane);
        frame();

        AccessibleNode panel = node("Olá");
        assertNotNull(panel,
                "the parent supplied the name and the child's language is what resolved it"
                        + describe(tree()));
        assertEquals(BRAZILIAN, panel.locale(),
                "which is the language the node itself claims to be in" + describe(tree()));

        // The move that used to strand it: the parent's language changes, the child's does not.
        pane.setLocale(Locale.FRENCH);
        frame();

        assertNotNull(node("Olá"),
                "the child's language did not move, so its name did not either" + describe(tree()));

        page.setLocale(Locale.FRENCH);
        frame();

        assertNotNull(node("Bonjour"),
                "and when the child's own language moves, the name its parent supplies follows it"
                        + describe(tree()));
    }

    /** A container that names its one child, which is what TabbedPane does for a tab's page. */
    private static final class Namer extends Group {
        @Override
        protected void onAccessibilityChild(Widget child, Accessibility a) {
            a.name(GREETING, Accessible.NameFrom.LABEL);
        }
    }

    @Test
    void aLanguageMoveReResolvesEveryNameUnderItExactlyOnce() {
        install();
        Group root = new Group();
        Probe label = new Probe();
        label.role = Accessible.Role.LABEL;
        label.name = GREETING;
        root.add(label);
        bind(root);
        frame();
        assertEquals("Hello", tree().node(1).name());
        String first = tree().node(1).name();

        frame();
        assertSame(first, tree().node(1).name(),
                "an unchanged frame carries the resolved string over rather than resolving again");

        bridge.events.clear();
        I18n.setLocale(Locale.FRENCH);
        frame();

        assertEquals("Bonjour", tree().node(1).name(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "one name moved, so one event: " + bridge.events);
    }

    /**
     * And a subtree language move is seen with no subscription at all: a name is compared by its
     * source, the node's own language <em>and</em> the translation epoch, so moving the second of
     * the three re-resolves exactly the names under it.
     */
    @Test
    void movingOneSubtreesLanguageReResolvesOnlyThatSubtree() {
        install();
        Group root = new Group();
        Probe outside = new Probe();
        outside.role = Accessible.Role.LABEL;
        outside.name = GREETING;
        Group pane = new Group();
        pane.setAccessibleName("pane");
        Probe inside = new Probe();
        inside.role = Accessible.Role.LABEL;
        inside.name = GREETING;
        pane.add(inside);
        root.add(outside);
        root.add(pane);
        bind(root);
        frame();
        bridge.events.clear();

        pane.setLocale(Locale.FRENCH);
        frame();

        assertEquals(List.of("Hello", "pane", "Bonjour"),
                List.of(tree().node(1).name(), tree().node(2).name(), tree().node(3).name()),
                describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "only the subtree that moved: " + bridge.events);
    }

    @Test
    void anAnnouncementIsResolvedInTheScenesLanguage() {
        install();
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root);
        scene.setLocale(Locale.FRENCH);
        frame();
        bridge.events.clear();

        scene.announce(GREETING, Accessible.Politeness.ASSERTIVE);
        frame();

        AccessibleEvent spoken = bridge.first(AccessibleEvent.Type.ANNOUNCEMENT);
        assertEquals("Bonjour", spoken.newValue());
        assertEquals(Accessible.Politeness.ASSERTIVE, spoken.politeness());
    }
}
