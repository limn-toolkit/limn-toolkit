package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.graphics.Rect;
import limn.i18n.I18nString;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Opacity;
import limn.scene.layout.Transform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-scene dialog's card slides in as a picture moved by a {@link Transform} (ADR 072 §2.11):
 * the card was laid at a new place inside every frame's paint and the accessible tree walked again
 * for it, and its veil's every step asked for a walk too. Now a frame of the fade lays nothing out
 * and walks nothing; the tree is told where the card is when the slide arrives, and a drag still
 * moves the card for real.
 */
class DialogSlideTest extends AccessibleComponentTestBase {

    private static final float SLIDE = 14;

    /** A body that counts the walks that describe it and the layouts that place it. */
    private static final class Counted extends Widget<Counted> {
        int described;
        int layouts;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(120, 30);
        }

        @Override
        protected void onLayout() {
            layouts++;
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            described++;
            a.role(Accessible.Role.LABEL);
            a.name(I18nString.literal("counted"));
        }
    }

    @Test
    void theCardSlidesInWithNoLayoutAndNoAccessibleWalkOnTheFramesBetween() {
        bind(new Label("behind"), new OverlayHost());
        AtomicInteger layoutPasses = new AtomicInteger();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.LAYOUT) {
                layoutPasses.incrementAndGet();
            }
        });
        Counted body = new Counted();
        // No button: a focused button's ring fades in over the same frames and repaints itself as
        // any widget's animation does, which is the button's business and not the slide's.
        Dialog dialog = new Dialog("Slide", "In it comes.")
                .setContent(body)
                .setDisplayMode(DisplayMode.IN_SCENE);
        dialog.show(scene);
        frame(); // lays the overlay out, and is the first frame of the fade and of the slide
        Transform slide = (Transform) dialog.contentRoot().parent();
        Opacity fade = (Opacity) slide.parent();
        assertEquals(SLIDE, slide.translationY(), 1e-4f, "the card starts its slide's distance down");
        Rect startBounds = node("counted").bounds();

        int layouts = body.layouts;
        int described = body.described;
        int passes = layoutPasses.get();
        List<String> apart = new ArrayList<>();
        int frames = 0;
        for (; frames < 60 && dialog.fadeLevel() < 1; frames++) {
            advanceTime(16, null);
            float expected = (1 - fade.opacity()) * SLIDE;
            if (Math.abs(slide.translationY() - expected) > 1e-3f) {
                apart.add(frames + ": slide " + slide.translationY() + ", fade " + fade.opacity());
            }
            if (dialog.fadeLevel() < 1) {
                assertEquals(startBounds, node("counted").bounds(),
                        "frame " + frames + ": the tree keeps the card where it was published");
                assertEquals(described, body.described, "frame " + frames + ": no walk");
            }
        }
        assertTrue(frames > 5, "the fade took frames: " + frames);
        assertEquals(List.of(), apart, "the slide keeps pace with the fade on every frame");
        assertEquals(layouts, body.layouts, "nothing inside the card is laid out on the way");
        assertEquals(passes, layoutPasses.get(), "and no layout pass runs at all");
        assertEquals(0f, slide.translationY(), "arrived where layout put it");
        frame();
        Rect rest = node("counted").bounds();
        assertEquals(startBounds.y() - SLIDE, rest.y(), 1e-3f, "at the arrival the tree is told");
        assertTrue(body.described > described, "by a walk, the one the arrival asks for");
    }

    @Test
    void aDragStillMovesTheCardForReal() {
        bind(new Label("behind"), new OverlayHost());
        Counted body = new Counted();
        Dialog dialog = new Dialog("Drag", "Move me.")
                .setContent(body)
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE);
        dialog.show(scene);
        settleAnimations(null);
        Widget<?> card = dialog.contentRoot();
        Widget<?> placed = card.parent().parent(); // the container the overlay places
        float x = placed.x();
        float y = placed.y();
        Rect before = node("counted").bounds();
        // Press on the card's padding, beside the title, where nothing inside consumes it.
        float px = card.localToScene(4, 4).x();
        float py = card.localToScene(4, 4).y();
        drive(scene).mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, px, py, 1);
        drive(scene).mouseMoved(px + 30, py + 20);
        drive(scene).mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, px + 30, py + 20, 1);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(x + 30, placed.x(), 1e-3f, "the card's container is where the drag left it");
        assertEquals(y + 20, placed.y(), 1e-3f);
        assertEquals(before.x() + 30, node("counted").bounds().x(), 1e-3f, "and the tree says so");
    }
}
