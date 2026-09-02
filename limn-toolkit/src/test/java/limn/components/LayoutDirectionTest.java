package limn.components;

import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout-direction axis: the resolution rule, the coexistence contract, the measure-cache
 * key, and the two ways a direction change is allowed to reach a cached value. Phase 1: nothing
 * consumes the axis yet, so nothing here asserts a mirrored coordinate.
 *
 * <p>Written against {@link ControlSize}'s own contract deliberately. The two axes resolve
 * through the same chain in the same order for the same reasons, and a direction axis that
 * answered any of these differently would be a second inheritance mechanism wearing the first
 * one's name.
 */
class LayoutDirectionTest extends ComponentTestBase {

    /** Counts measure passes so the cache key can be observed rather than assumed. */
    private static final class Probe extends Widget {
        int measures;

        @Override
        protected Size onMeasure(Constraints constraints) {
            measures++;
            return constraints.constrain(10, 10);
        }

        @Override
        protected void onLayout() {
            for (Widget child : children()) {
                child.measure(Constraints.loose(100, 100));
                child.layoutBox(0, 0, 10, 10);
            }
        }
    }

    @AfterEach
    void restoreProcessDefault() {
        LayoutDirection.setProcessDefault(LayoutDirection.LTR);
    }

    // ------------------------------------------------------------- resolution

    @Test
    void anUndeclaredWidgetResolvesToTheProcessDefaultAndTheDefaultIsLeftToRight() {
        Probe w = new Probe();
        assertNull(w.declaredLayoutDirection(), "nothing declared");
        assertSame(LayoutDirection.LTR, LayoutDirection.processDefault());
        assertSame(LayoutDirection.LTR, w.layoutDirection());
    }

    @Test
    void aDeclaredDirectionWinsOverEverything() {
        Probe w = new Probe();
        w.setLayoutDirection(LayoutDirection.RTL);
        assertSame(LayoutDirection.RTL, w.declaredLayoutDirection());
        assertSame(LayoutDirection.RTL, w.layoutDirection());
    }

    @Test
    void aDirectionInheritsDownTheTreeAndASubtreeOverridesIt() {
        Probe root = new Probe();
        Probe mid = new Probe();
        Probe leaf = new Probe();
        Probe overriding = new Probe();
        Probe underOverride = new Probe();
        root.add(mid);
        mid.add(leaf);
        root.add(overriding);
        overriding.add(underOverride);

        root.setLayoutDirection(LayoutDirection.RTL);
        assertSame(LayoutDirection.RTL, leaf.layoutDirection(), "inherits through two links");

        // The case §1.2 exists for: a left-to-right code editor inside a right-to-left interface.
        overriding.setLayoutDirection(LayoutDirection.LTR);
        assertSame(LayoutDirection.LTR, underOverride.layoutDirection(),
                "the nearer declaration wins");
        assertSame(LayoutDirection.RTL, leaf.layoutDirection(), "the other branch is untouched");
        assertNull(leaf.declaredLayoutDirection(), "inheriting is not declaring");
    }

    @Test
    void severalDirectionsCoexistInOneTreeInOneFrame() {
        Probe root = new Probe();
        Probe form = new Probe();
        Probe logPane = new Probe();
        Probe urlBar = new Probe();
        root.add(form);
        root.add(logPane);
        root.add(urlBar);
        root.setLayoutDirection(LayoutDirection.RTL);
        logPane.setLayoutDirection(LayoutDirection.LTR);

        assertSame(LayoutDirection.RTL, form.layoutDirection());
        assertSame(LayoutDirection.LTR, logPane.layoutDirection());
        assertSame(LayoutDirection.RTL, urlBar.layoutDirection());
    }

    @Test
    void nullRestoresInheritance() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        root.setLayoutDirection(LayoutDirection.RTL);
        child.setLayoutDirection(LayoutDirection.LTR);
        assertSame(LayoutDirection.LTR, child.layoutDirection());

        child.setLayoutDirection(null);
        assertNull(child.declaredLayoutDirection());
        assertSame(LayoutDirection.RTL, child.layoutDirection(), "back to inheriting");
    }

    @Test
    void theSceneDefaultSitsBelowTheTreeAndAboveTheProcessDefault() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);

        assertNull(scene.layoutDirection(), "a scene declares nothing by default");
        assertSame(LayoutDirection.LTR, child.layoutDirection());

        // The one line an application whose interface reads right to left writes.
        scene.setLayoutDirection(LayoutDirection.RTL);
        assertSame(LayoutDirection.RTL, child.layoutDirection(), "the scene default reaches the leaf");

        child.setLayoutDirection(LayoutDirection.LTR);
        assertSame(LayoutDirection.LTR, child.layoutDirection(), "a declaration still wins");
    }

    @Test
    void theProcessDefaultIsTheRootOfTheChain() {
        Probe w = new Probe();
        LayoutDirection.setProcessDefault(LayoutDirection.RTL);
        assertSame(LayoutDirection.RTL, w.layoutDirection());
    }

    // -------------------------------------------------------------- host link

    @Test
    void aHostLinkCarriesTheDirectionToAnOutOfTreeRoot() {
        Probe owner = new Probe();
        owner.setLayoutDirection(LayoutDirection.RTL);
        Probe popupRoot = new Probe(); // parentless, as a popup scene's root is
        assertSame(LayoutDirection.LTR, popupRoot.layoutDirection());

        popupRoot.setInheritanceHost(owner);
        assertSame(LayoutDirection.RTL, popupRoot.layoutDirection(), "inherits across the link");

        owner.setLayoutDirection(LayoutDirection.LTR);
        assertSame(LayoutDirection.LTR, popupRoot.layoutDirection(), "the link is live, not a copy");
    }

    @Test
    void aSceneDefaultBeatsTheHostLinkSoScenesStayReachable() {
        Probe owner = new Probe();
        owner.setLayoutDirection(LayoutDirection.RTL);
        Probe popupRoot = new Probe();
        popupRoot.setInheritanceHost(owner);
        Scene popupScene = new Scene(popupRoot);
        popupScene.setTextRuler(RULER);

        assertSame(LayoutDirection.RTL, popupRoot.layoutDirection(),
                "no scene default: fall through");

        popupScene.setLayoutDirection(LayoutDirection.LTR);
        assertSame(LayoutDirection.LTR, popupRoot.layoutDirection(),
                "the scene default is consulted before the host, or setLayoutDirection would be "
                        + "unreachable for every popup, menu and dialog scene");
    }

    @Test
    void theTreeBeatsAHostLink() {
        Probe parent = new Probe();
        parent.setLayoutDirection(LayoutDirection.LTR);
        Probe host = new Probe();
        host.setLayoutDirection(LayoutDirection.RTL);
        Probe child = new Probe();
        parent.add(child);
        child.setInheritanceHost(host);
        assertSame(LayoutDirection.LTR, child.layoutDirection(), "a real parent wins over the link");
    }

    @Test
    void aHostCycleIsRejected() {
        Probe a = new Probe();
        Probe b = new Probe();
        b.setInheritanceHost(a);
        assertThrows(IllegalArgumentException.class, () -> a.setInheritanceHost(b),
                "a resolves through b, so b -> a would spin forever");
        assertThrows(IllegalArgumentException.class, () -> a.setInheritanceHost(a));
    }

    @Test
    void theHostLinkIsOneLinkSharedByBothAxesAndTheOldNameStillReachesIt() {
        Probe owner = new Probe();
        owner.setControlSize(ControlSize.SMALL);
        owner.setLayoutDirection(LayoutDirection.RTL);
        Probe popupRoot = new Probe();

        // The deprecated name is the only thing that changed: one link, both axes, and a second
        // link that could name a different widget per axis would have no honest resolution.
        popupRoot.setInheritanceHost(owner);
        assertSame(ControlSize.SMALL, popupRoot.controlSize());
        assertSame(LayoutDirection.RTL, popupRoot.layoutDirection());
    }

    // ------------------------------------------------------------ reparenting

    @Test
    void reparentingChangesTheResolvedDirection() {
        Probe ltr = new Probe();
        ltr.setLayoutDirection(LayoutDirection.LTR);
        Probe rtl = new Probe();
        rtl.setLayoutDirection(LayoutDirection.RTL);
        Probe child = new Probe();

        ltr.add(child);
        assertSame(LayoutDirection.LTR, child.layoutDirection());
        ltr.remove(child);
        rtl.add(child);
        assertSame(LayoutDirection.RTL, child.layoutDirection(), "the memo cannot survive a reparent");
    }

    @Test
    void aConstructorTimeReadResolvesToTheProcessDefaultWhateverTheParentDeclares() {
        // The rule the axis inherits verbatim from ControlSize, asserted rather than only
        // documented: add() assigns the parent after the child is fully constructed, so a
        // direction captured during construction is permanently wrong with no path to recovery.
        Probe parent = new Probe();
        parent.setLayoutDirection(LayoutDirection.RTL);

        Probe child = new Probe();
        LayoutDirection atConstruction = child.layoutDirection();
        parent.add(child);

        assertSame(LayoutDirection.LTR, atConstruction, "no parent yet: the process default");
        assertSame(LayoutDirection.RTL, child.layoutDirection(), "and the answer moved underneath");
    }

    // ----------------------------------------------------- measure cache key

    @Test
    void aDirectionChangeReMeasuresInheritorsAndLeavesOverridingSubtreesCached() {
        Probe root = new Probe();
        Probe inheritor = new Probe();
        Probe overriding = new Probe();
        root.add(inheritor);
        root.add(overriding);
        overriding.setLayoutDirection(LayoutDirection.LTR);

        Constraints c = Constraints.loose(100, 100);
        root.measure(c);
        inheritor.measure(c);
        overriding.measure(c);
        int inheritorBefore = inheritor.measures;
        int overridingBefore = overriding.measures;

        inheritor.measure(c);
        overriding.measure(c);
        assertEquals(inheritorBefore, inheritor.measures, "unchanged inputs must not re-measure");
        assertEquals(overridingBefore, overriding.measures);

        root.setLayoutDirection(LayoutDirection.RTL);
        inheritor.measure(c);
        overriding.measure(c);
        assertEquals(inheritorBefore + 1, inheritor.measures,
                "the inheritor's resolved direction changed, so its cached size is stale");
        assertEquals(overridingBefore, overriding.measures,
                "the overriding subtree's direction did not change; this is why no deep "
                        + "invalidation API is needed");
    }

    @Test
    void aProcessDefaultChangeInvalidatesEveryMemo() {
        Probe w = new Probe();
        Constraints c = Constraints.loose(100, 100);
        w.measure(c);
        int before = w.measures;
        LayoutDirection.setProcessDefault(LayoutDirection.RTL);
        w.measure(c);
        assertEquals(before + 1, w.measures);
        assertSame(LayoutDirection.RTL, w.layoutDirection());
    }

    // ---------------------------------------------------------- two epochs

    @Test
    void aDeclaredValueOnOneAxisIsUntouchedByTheOtherAxisMoving() {
        // What this pins is the resolution, not the epochs. The two counters are a cost
        // separation and not a correctness one — merging them would still give every right
        // answer, because a memo is a pure function of its inputs and measure's key compares
        // resolved VALUES rather than epochs — so no assertion here could tell them apart, and
        // one claiming to would be lying. What is worth pinning is that the axes do not leak
        // into each other's answers.
        Probe sizeOnly = new Probe();
        Probe directionOnly = new Probe();
        Constraints c = Constraints.loose(100, 100);
        sizeOnly.measure(c);
        directionOnly.measure(c);

        // A widget that declares its own direction is untouched by a size change...
        directionOnly.setLayoutDirection(LayoutDirection.RTL);
        int afterDeclaring = directionOnly.measures;
        ControlSize.setProcessDefault(ControlSize.LARGE);
        try {
            directionOnly.measure(c);
            assertEquals(afterDeclaring + 1, directionOnly.measures,
                    "its resolved SIZE moved, so it re-measures");

            // ...and a direction change leaves a size memo alone. The observable proof that the
            // counters are separate is that a widget declaring both re-measures once per axis
            // change and not twice.
            int beforeDirection = directionOnly.measures;
            LayoutDirection.setProcessDefault(LayoutDirection.RTL);
            directionOnly.measure(c);
            assertEquals(beforeDirection, directionOnly.measures,
                    "it declares its own direction, so the process default cannot reach it");
        } finally {
            ControlSize.setProcessDefault(ControlSize.MEDIUM);
        }
    }

    // -------------------------------------------------- a held value goes stale

    @Test
    void aHeldShapedValueIsNotCurrentAcrossADirectionChange() {
        // The failure this axis most likely ships: invisible in a screenshot, a fraction of a
        // point wrong in every geometry query asked of the held value. The check is the fix.
        TextMetrics vertical = new TextMetrics(30, 8, 2, 12);
        Font font = Font.of(16);
        ShapedText held = ShapedText.uniform("abc", font, 10, vertical, 0);

        assertTrue(held.matches("abc", font, ShapedText.Direction.LTR, TextRuler.NONE),
                "current in the direction it was shaped for");
        assertFalse(held.matches("abc", font, ShapedText.Direction.RTL, TextRuler.NONE),
                "and not current in the other one");
    }

    // ------------------------------------------------------------ the bridge

    @Test
    void forLocaleReadsTheScriptFirstAndTheLanguageOnlyWhenThereIsNone() {
        assertSame(LayoutDirection.RTL, LayoutDirection.forLocale(Locale.forLanguageTag("ar")));
        assertSame(LayoutDirection.RTL, LayoutDirection.forLocale(Locale.forLanguageTag("he-IL")));
        assertSame(LayoutDirection.RTL, LayoutDirection.forLocale(Locale.forLanguageTag("fa")));
        assertSame(LayoutDirection.LTR, LayoutDirection.forLocale(Locale.ENGLISH));
        assertSame(LayoutDirection.LTR, LayoutDirection.forLocale(Locale.JAPANESE));

        // A language written in either script: only the script subtag says which, which is why
        // it is consulted first.
        assertSame(LayoutDirection.RTL,
                LayoutDirection.forLocale(Locale.forLanguageTag("az-Arab")));
        assertSame(LayoutDirection.LTR,
                LayoutDirection.forLocale(Locale.forLanguageTag("az-Latn")));

        // Locale still normalises Hebrew's modern tag to the historical one.
        assertSame(LayoutDirection.RTL, LayoutDirection.forLocale(new Locale("iw")));
    }
}
