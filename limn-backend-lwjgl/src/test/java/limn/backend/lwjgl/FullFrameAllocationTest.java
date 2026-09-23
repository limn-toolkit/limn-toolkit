package limn.backend.lwjgl;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.ProgressBar;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What painting a whole window allocates, with the real shaping ruler (PF-6). Measured before the
 * fix at 18–40 KB a full frame: a new key for every lookup in the shaping cache, colours mixed
 * per paint in the checkbox, and a rounded rectangle built per paint. The ceiling is what is
 * left, so a new allocation on the paint path of these widgets fails here.
 */
class FullFrameAllocationTest {

    @Test
    void aFullFrameOfAFormAllocatesAlmostNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(), "no per-thread allocation counter");
        try (HeadlessUi ui = new HeadlessUi(); FontStore store = new FontStore()) {
            Column list = new Column();
            list.gap(4);
            for (int i = 0; i < 40; i++) {
                Row row = new Row();
                row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
                row.add(new Label(String.format("Item %03d", i)));
                row.add(Expanded.of(new TextField().setText("Value " + i), 1));
                row.add(new Checkbox(Checkbox.Variant.BOX, "Enabled").setChecked(i % 2 == 0));
                ProgressBar bar = new ProgressBar();
                bar.setProgress((i % 100) / 100f);
                row.add(Expanded.of(bar, 1));
                row.add(new Button("Open"));
                list.add(row);
            }
            Column page = new Column();
            page.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
            page.add(Expanded.of(new ScrollView(list), 1));
            page.add(new Label("Ready."));
            Scene scene = new Scene(new Padding(Insets.all(8), page));
            scene.setTextRuler(new ShapingRuler(store));
            scene.setPartialRendering(true);
            // The scalar round-rect forms drawn directly, as GlCanvas draws them: the interface's
            // default builds a RoundRect per call, which is the canvas's garbage and not the paint's.
            NoopCanvas canvas = new NoopCanvas(1100, 800) {
                @Override
                public void fillRoundRect(float x, float y, float w, float h, float r, limn.graphics.Paint p) {
                }

                @Override
                public void drawRoundRect(float x, float y, float w, float h, float r, float s,
                                          limn.graphics.Paint p) {
                }
            };
            for (int i = 0; i < 50; i++) {
                scene.requestRender();
                scene.renderFrame(canvas);
            }
            long bytes = AllocationProbe.leastAllocatedBy(() -> {
                scene.requestRender();
                scene.renderFrame(canvas);
            }, 20);
            assertTrue(bytes <= CEILING, "a full frame allocated " + bytes + " bytes; ceiling " + CEILING);
        }
    }

    /** Measured at 64 bytes after the fix; the slack is for what the JIT may yet decide. */
    private static final long CEILING = 1024;
}
