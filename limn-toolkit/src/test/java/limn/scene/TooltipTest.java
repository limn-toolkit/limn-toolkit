package limn.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The tooltip owner is resolved by walking up from the hovered leaf, like the cursor. */
class TooltipTest extends SceneTestBase {

    @Test
    void resolvesTheNearestAncestorWithATooltip() {
        FixedBox parent = new FixedBox(100, 100);
        parent.setTooltip("parent tip");
        FixedBox child = new FixedBox(50, 50);
        parent.add(child);
        Scene scene = new Scene(parent);

        // A child without its own tooltip inherits the parent's.
        assertSame(parent, scene.tooltipOwner(child));
        // A widget with a tooltip owns it.
        assertSame(parent, scene.tooltipOwner(parent));
        // The nearest tooltip wins.
        child.setTooltip("child tip");
        assertSame(child, scene.tooltipOwner(child));
    }

    @Test
    void noTooltipAnywhereYieldsNull() {
        FixedBox lone = new FixedBox(10, 10);
        Scene scene = new Scene(lone);
        assertNull(scene.tooltipOwner(lone));
        assertNull(scene.tooltipOwner(null));
    }
}
