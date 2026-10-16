package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ValueFacet;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link RangeSlider} is two sliders to an assistive technology (ADR 067 section 4): each named
 * for what it is, each with the bounds the other leaves it, each set and stepped as a slider is.
 */
class RangeSliderAccessibilityTest extends AccessibleComponentTestBase {

    private RangeSlider slider;

    private void bindSlider(RangeSlider under) {
        slider = under;
        Column root = new Column();
        root.add(slider);
        bind(root);
    }

    private List<AccessibleNode> sliders() {
        List<AccessibleNode> out = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.SLIDER) {
                out.add(tree().node(i));
            }
        }
        return out;
    }

    @Test
    void theTwoThumbsAreTwoNamedSlidersWithTheBoundsTheOtherLeaves() {
        bindSlider(new RangeSlider(0, 100).setMinimumGap(10));
        slider.setValues(20, 70);
        frame();
        List<AccessibleNode> nodes = sliders();
        assertEquals(2, nodes.size(), describe(tree()));
        AccessibleNode lower = node(ComponentStrings.RANGE_LOWER.get());
        AccessibleNode upper = node(ComponentStrings.RANGE_UPPER.get());
        ValueFacet lo = lower.value();
        ValueFacet hi = upper.value();
        assertNotNull(lo);
        assertNotNull(hi);
        assertEquals(20, lo.value(), 1e-6);
        assertEquals(0, lo.min(), 1e-6);
        assertEquals(60, lo.max(), 1e-6, "the upper less the gap");
        assertEquals(70, hi.value(), 1e-6);
        assertEquals(30, hi.min(), 1e-6, "the lower plus the gap");
        assertEquals(100, hi.max(), 1e-6);
    }

    @Test
    void aReaderSetsAndStepsEachThumb() throws InterruptedException {
        bindSlider(new RangeSlider(0, 100).setStep(5));
        slider.setValues(20, 70);
        frame();
        assertTrue(perform(node(ComponentStrings.RANGE_LOWER.get()).id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(40)));
        assertEquals(40, slider.lower());
        assertTrue(perform(node(ComponentStrings.RANGE_UPPER.get()).id(), Accessible.Action.DECREMENT, Accessible.Argument.NONE));
        assertEquals(65, slider.upper());
        assertTrue(perform(node(ComponentStrings.RANGE_LOWER.get()).id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(90)));
        assertEquals(65, slider.lower(), "a set past the other stops at it");
    }
}
