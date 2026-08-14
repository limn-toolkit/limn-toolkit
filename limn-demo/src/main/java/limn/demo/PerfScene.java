package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;

/**
 * Standalone performance monitor ({@code --scene perf}): the animation showcase
 * (continuous render load) above a live {@link PerfFooter}. Everything (the
 * numbers and the sparklines) is drawn with the Limn toolkit.
 */
final class PerfScene {

    private PerfScene() {
    }

    static Scene create() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Performance monitor").setFont(Theme.current().title));
        column.add(new Label("The footer measures, live, the FPS, the frame time (CPU: tick + layout + "
                + "paint) and the event-processing time, with averages and sparklines.")
                .setMuted(true).setWrap(true));
        column.add(Expanded.of(AnimationsScene.content(), 1)); // animation = render load to measure

        limn.components.Checkbox partialRendering =
                new limn.components.Checkbox(limn.components.Checkbox.Variant.SWITCH, "Partial rendering");
        limn.components.Checkbox damageDebug =
                new limn.components.Checkbox(limn.components.Checkbox.Variant.SWITCH, "Damage debug");
        limn.scene.layout.Row renderRow = new limn.scene.layout.Row();
        renderRow.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
        renderRow.add(partialRendering);
        renderRow.add(damageDebug);
        column.add(renderRow);
        column.add(new PerfFooter());

        Scene scene = new Scene(new Padding(Insets.all(20), column));
        scene.setBackground(Theme.current().background);
        partialRendering.onChange(scene::setPartialRendering);
        damageDebug.onChange(scene::setDamageDebug);
        return scene;
    }
}
