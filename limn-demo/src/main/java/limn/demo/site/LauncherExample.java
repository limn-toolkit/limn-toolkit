package limn.demo.site;

import limn.backend.Launcher;
import limn.components.Button;
import limn.components.Label;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Row;

import java.net.URI;
import java.nio.file.Path;

/**
 * The worked example the guide shows for handing an address or a file to the system. Compiled by
 * {@code ./gradlew check}, so the sample a reader copies builds.
 */
public final class LauncherExample {

    private LauncherExample() {
    }

    // #region guide:launcher
    /** What an export screen offers once the report is written: open it, or show where it is. */
    static Widget<?> afterExport(Path report, Label status) {
        Button open = new Button("Open");
        Button show = new Button("Show in folder").setSecondary(true);
        Button help = new Button("How exports work").setSecondary(true);
        open.onAction(() -> launcherOf(open).openFile(report)
                .thenAccept(taken -> status.setText(taken ? "" : "No program opens this file.")));
        show.onAction(() -> launcherOf(show).showInFolder(report));
        help.onAction(() -> launcherOf(help).openAddress(URI.create("https://example.org/docs/exports")));
        Row row = new Row();
        row.gap(8);
        row.add(open);
        row.add(show);
        row.add(help);
        return row;
    }

    /** The launcher of the window a widget is in, or the one that opens nothing while it is in none. */
    static Launcher launcherOf(Widget<?> widget) {
        Scene scene = widget.scene();
        return scene == null || scene.window() == null ? Launcher.NONE : scene.window().launcher();
    }
    // #endregion
}
