package limn.components;

import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control-size axis: the resolution rule, the coexistence contract, the measure-cache key
 * and the token table's structural invariants. Phase 0: no component consumes tokens yet, so
 * nothing here asserts a rendered pixel.
 */
class ControlSizeTest extends ComponentTestBase {

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
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
    }

    // ------------------------------------------------------------- resolution

    @Test
    void anUndeclaredWidgetResolvesToTheProcessDefault() {
        Probe w = new Probe();
        assertNull(w.declaredControlSize(), "nothing declared");
        assertSame(ControlSize.MEDIUM, w.controlSize());
    }

    @Test
    void aDeclaredStepWinsOverEverything() {
        Probe w = new Probe();
        w.setControlSize(ControlSize.XLARGE);
        assertSame(ControlSize.XLARGE, w.declaredControlSize());
        assertSame(ControlSize.XLARGE, w.controlSize());
    }

    @Test
    void aStepInheritsDownTheTreeAndASubtreeOverridesIt() {
        Probe root = new Probe();
        Probe mid = new Probe();
        Probe leaf = new Probe();
        Probe overriding = new Probe();
        Probe underOverride = new Probe();
        root.add(mid);
        mid.add(leaf);
        root.add(overriding);
        overriding.add(underOverride);

        root.setControlSize(ControlSize.SMALL);
        assertSame(ControlSize.SMALL, leaf.controlSize(), "inherits through two links");

        overriding.setControlSize(ControlSize.LARGE);
        assertSame(ControlSize.LARGE, underOverride.controlSize(), "the nearer declaration wins");
        assertSame(ControlSize.SMALL, leaf.controlSize(), "the other branch is untouched");
        assertNull(leaf.declaredControlSize(), "inheriting is not declaring");
    }

    @Test
    void severalStepsCoexistInOneTree() {
        Probe root = new Probe();
        Probe toolbar = new Probe();
        Probe form = new Probe();
        Probe dialog = new Probe();
        root.add(toolbar);
        root.add(form);
        root.add(dialog);
        toolbar.setControlSize(ControlSize.SMALL);
        dialog.setControlSize(ControlSize.LARGE);

        // The mandate, asserted: three scopes, one tree, resolved independently.
        assertSame(ControlSize.SMALL, toolbar.controlSize());
        assertSame(ControlSize.MEDIUM, form.controlSize());
        assertSame(ControlSize.LARGE, dialog.controlSize());
    }

    @Test
    void nullRestoresInheritance() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        root.setControlSize(ControlSize.XSMALL);
        child.setControlSize(ControlSize.XLARGE);
        assertSame(ControlSize.XLARGE, child.controlSize());

        child.setControlSize(null);
        assertNull(child.declaredControlSize());
        assertSame(ControlSize.XSMALL, child.controlSize(), "back to inheriting");
    }

    @Test
    void theSceneDefaultSitsBelowTheTreeAndAboveTheProcessDefault() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);

        assertNull(scene.controlSize(), "a scene declares nothing by default");
        assertSame(ControlSize.MEDIUM, child.controlSize());

        scene.setControlSize(ControlSize.SMALL);
        assertSame(ControlSize.SMALL, child.controlSize(), "the scene default reaches the leaf");

        child.setControlSize(ControlSize.LARGE);
        assertSame(ControlSize.LARGE, child.controlSize(), "a declaration still wins");
    }

    @Test
    void theProcessDefaultIsTheRootOfTheChain() {
        Probe w = new Probe();
        ControlSize.setProcessDefault(ControlSize.XSMALL);
        assertSame(ControlSize.XSMALL, w.controlSize());
    }

    // -------------------------------------------------------------- host link

    @Test
    void aHostLinkCarriesTheStepToAnOutOfTreeRoot() {
        Probe owner = new Probe();
        owner.setControlSize(ControlSize.SMALL);
        Probe popupRoot = new Probe(); // parentless, as a popup scene's root is
        assertSame(ControlSize.MEDIUM, popupRoot.controlSize());

        popupRoot.setControlSizeHost(owner);
        assertSame(ControlSize.SMALL, popupRoot.controlSize(), "inherits across the link");

        owner.setControlSize(ControlSize.XLARGE);
        assertSame(ControlSize.XLARGE, popupRoot.controlSize(), "the link is live, not a copy");
    }

    @Test
    void aSceneDefaultBeatsTheHostLinkSoScenesStayReachable() {
        Probe owner = new Probe();
        owner.setControlSize(ControlSize.SMALL);
        Probe popupRoot = new Probe();
        popupRoot.setControlSizeHost(owner);
        Scene popupScene = new Scene(popupRoot);
        popupScene.setTextRuler(RULER);

        assertSame(ControlSize.SMALL, popupRoot.controlSize(), "no scene default: fall through");

        popupScene.setControlSize(ControlSize.LARGE);
        assertSame(ControlSize.LARGE, popupRoot.controlSize(),
                "the scene default is consulted before the host, or setControlSize would be "
                        + "unreachable for every popup, menu and dialog scene");
    }

    @Test
    void theTreeBeatsAHostLink() {
        Probe parent = new Probe();
        parent.setControlSize(ControlSize.XSMALL);
        Probe host = new Probe();
        host.setControlSize(ControlSize.XLARGE);
        Probe child = new Probe();
        parent.add(child);
        child.setControlSizeHost(host);
        assertSame(ControlSize.XSMALL, child.controlSize(), "a real parent wins over the link");
    }

    @Test
    void aHostCycleIsRejected() {
        Probe a = new Probe();
        Probe b = new Probe();
        b.setControlSizeHost(a);
        assertThrows(IllegalArgumentException.class, () -> a.setControlSizeHost(b),
                "a resolves through b, so b -> a would spin forever");
        assertThrows(IllegalArgumentException.class, () -> a.setControlSizeHost(a));
    }

    // ------------------------------------------------------------ reparenting

    @Test
    void reparentingChangesTheResolvedStep() {
        Probe small = new Probe();
        small.setControlSize(ControlSize.SMALL);
        Probe large = new Probe();
        large.setControlSize(ControlSize.LARGE);
        Probe child = new Probe();

        small.add(child);
        assertSame(ControlSize.SMALL, child.controlSize());
        small.remove(child);
        large.add(child);
        assertSame(ControlSize.LARGE, child.controlSize(), "the memo cannot survive a reparent");
    }

    // ----------------------------------------------------- measure cache key

    @Test
    void aStepChangeReMeasuresInheritorsAndLeavesOverridingSubtreesCached() {
        Probe root = new Probe();
        Probe inheritor = new Probe();
        Probe overriding = new Probe();
        root.add(inheritor);
        root.add(overriding);
        overriding.setControlSize(ControlSize.XLARGE);

        Constraints c = Constraints.loose(100, 100);
        root.measure(c);
        inheritor.measure(c);
        overriding.measure(c);
        int inheritorBefore = inheritor.measures;
        int overridingBefore = overriding.measures;

        // Same constraints, same step: both caches hold.
        inheritor.measure(c);
        overriding.measure(c);
        assertEquals(inheritorBefore, inheritor.measures, "unchanged inputs must not re-measure");
        assertEquals(overridingBefore, overriding.measures);

        root.setControlSize(ControlSize.SMALL);
        inheritor.measure(c);
        overriding.measure(c);
        assertEquals(inheritorBefore + 1, inheritor.measures,
                "the inheritor's resolved step changed, so its cached size is stale");
        assertEquals(overridingBefore, overriding.measures,
                "the overriding subtree's step did not change; this is why no deep "
                        + "invalidation API is needed");
    }

    @Test
    void aProcessDefaultChangeInvalidatesEveryMemo() {
        Probe w = new Probe();
        Constraints c = Constraints.loose(100, 100);
        w.measure(c);
        int before = w.measures;
        ControlSize.setProcessDefault(ControlSize.LARGE);
        w.measure(c);
        assertEquals(before + 1, w.measures);
        assertSame(ControlSize.LARGE, w.controlSize());
    }

    // ---------------------------------------------------------- token table

    @Test
    void everyControlHeightIsAnEvenIntegerSoHalfHeightIsWhole() {
        // The parity rule: height/2 drives pill radii, thumb centres and rail centring, so an
        // odd or fractional height puts a centred extent on a half point. A 5pt rail in a 26pt
        // Slider box is how this was found in shipped code.
        for (ControlSize step : ControlSize.values()) {
            float h = SizeTokens.of(step).controlHeight();
            assertEquals(h, Math.rint(h), step + " control height is an integer");
            assertEquals(0f, h % 2, step + " control height is even");
        }
    }

    @Test
    void theHeightRampIsStrictlyIncreasingAndClearsTheAccessibilityFloor() {
        float previous = 0;
        for (ControlSize step : ControlSize.values()) {
            float h = SizeTokens.of(step).controlHeight();
            assertTrue(h > previous, step + " is taller than the step below");
            assertTrue(h >= Strokes.MIN_HIT_TARGET,
                    step + " meets the 24pt target in paint, so no hit outset is needed");
            previous = h;
        }
    }

    @Test
    void theTypeRampIsStrictlyIncreasingAndLabelTracksBody() {
        float previous = 0;
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            assertTrue(t.body().size() > previous, step + " body is larger than the step below");
            assertTrue(t.label().size() < t.body().size(), step + " label is smaller than body");
            assertTrue(t.title().size() > t.body().size(), step + " title is larger than body");
            previous = t.body().size();
        }
    }

    @Test
    void theInkFractionFallsAcrossTheRampExceptForTheOneProvenDip() {
        // 3.1 proves monotone ink fraction, all-even heights and a 24pt floor cannot hold
        // together: monotonicity pins SMALL's height to an interval whose only integer is odd.
        // So the assertion is the weaker true one (XSMALL is the densest, XLARGE the airiest),
        // and the SMALL-vs-MEDIUM dip is asserted as a bound, not wished away.
        float xsmall = inkFraction(ControlSize.XSMALL);
        float small = inkFraction(ControlSize.SMALL);
        float medium = inkFraction(ControlSize.MEDIUM);
        float large = inkFraction(ControlSize.LARGE);
        float xlarge = inkFraction(ControlSize.XLARGE);

        assertTrue(xsmall > medium, "XSMALL is denser than MEDIUM");
        assertTrue(medium > large && large > xlarge, "the display half is monotone");
        assertTrue(medium - small < 0.02f,
                "the SMALL dip stays under 2 points of ink fraction; it was 0.0105");
    }

    private static float inkFraction(ControlSize step) {
        SizeTokens t = SizeTokens.of(step);
        return (1.171875f * t.body().size()) / t.controlHeight(); // Roboto lineHeight ratio
    }

    @Test
    void mediumIsTheDefaultRowAndThemeSharesItsFontInstances() {
        assertSame(SizeTokens.of(ControlSize.MEDIUM), SizeTokens.MEDIUM);
        Theme theme = Theme.current();
        assertSame(theme.tokens(ControlSize.MEDIUM), SizeTokens.MEDIUM);
        // Rebasing the three Font fields buys object identity, which is what keeps the
        // backend's identity-keyed font memo at one entry per logical face.
        assertSame(theme.body, SizeTokens.MEDIUM.body());
        assertSame(theme.label, SizeTokens.MEDIUM.label());
        assertSame(theme.title, SizeTokens.MEDIUM.title());
    }

    @Test
    void themesInlinedMediumLiteralsAgreeWithTheTable() {
        // Theme keeps its three spacing tokens as JLS 4.12.4 constant variables on purpose, so
        // this is the check that makes that safe. Theme's static initializer asserts the same
        // thing at class-init; this is the version a reader can find. The radii are absent by
        // design: a constant cannot follow cornerScale, so they are only ever read off the row.
        Theme theme = Theme.current();
        SizeTokens m = SizeTokens.MEDIUM;
        assertEquals(theme.spacingSmall, m.spacingSmall());
        assertEquals(theme.spacingMedium, m.spacingMedium());
        assertEquals(theme.spacingLarge, m.spacingLarge());
    }

    @Test
    void tokensForResolvesThroughTheWidget() {
        Probe root = new Probe();
        Probe child = new Probe();
        root.add(child);
        root.setControlSize(ControlSize.XSMALL);
        assertSame(SizeTokens.of(ControlSize.XSMALL), Theme.current().tokensFor(child));
    }

    @Test
    void spacingRolesMapToTheThreeSpacingTokens() {
        Probe w = new Probe();
        w.setControlSize(ControlSize.LARGE);
        SizeTokens t = SizeTokens.of(ControlSize.LARGE);
        assertEquals(t.spacingSmall(), Tokens.spacingFor(w, Tokens.Role.SMALL));
        assertEquals(t.spacingMedium(), Tokens.spacingFor(w, Tokens.Role.MEDIUM));
        assertEquals(t.spacingLarge(), Tokens.spacingFor(w, Tokens.Role.LARGE));
    }

    @Test
    void everyColourPickerRowIsStrictlyIncreasing() {
        // Added with the picker (7.32). A row that flattened at two steps would
        // make the widget look identical at both while everything around it moved.
        assertRowClimbs("colorGap", SizeTokens::colorGap);
        assertRowClimbs("colorDialogW", SizeTokens::colorDialogW);
        assertRowClimbs("colorFieldH", SizeTokens::colorFieldH);
        assertRowClimbs("colorRampW", SizeTokens::colorRampW);
        assertRowClimbs("colorRailH", SizeTokens::colorRailH);
        assertRowClimbs("colorThumbH", SizeTokens::colorThumbH);
        assertRowClimbs("colorThumbW", SizeTokens::colorThumbW);
    }

    @Test
    void theColourRailIsEvenSoItCentresOnAWholePoint() {
        // 3.6 parity: the rail is centred in its box, so an odd band would put its
        // two long edges on half points and render as two rows of grey.
        for (ControlSize step : ControlSize.values()) {
            float rail = SizeTokens.of(step).colorRailH();
            assertEquals(0f, rail % 2, step + " colour rail is even");
        }
    }

    private void assertRowClimbs(String name, java.util.function.Function<SizeTokens, Float> row) {
        float previous = 0;
        for (ControlSize step : ControlSize.values()) {
            float value = row.apply(SizeTokens.of(step));
            assertTrue(value > previous, name + " at " + step + " is not above the step below");
            previous = value;
        }
    }
}
