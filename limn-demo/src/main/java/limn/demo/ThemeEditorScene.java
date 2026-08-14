package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.components.TokenColumn;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.themeeditor.ThemeEditor;
import limn.themeeditor.ThemeEditorFiles;

/**
 * The theme editor, wired the way an application would wire it: the widget, plus the file
 * buttons the module deliberately leaves out of it.
 *
 * <p>It applies what it edits, which is the point: the window this scene is in re-skins
 * under the pointer, editor included, because a palette is process-wide and every widget
 * reads it as it paints. Nothing in this scene arranges that; it is what a {@code Theme}
 * being current already means.
 */
final class ThemeEditorScene {

    private ThemeEditorScene() {
    }

    static Scene create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.limnLight() : Theme.limn());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    private static Widget content() {
        ThemeEditor editor = new ThemeEditor(Theme.current());

        TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
        column.crossAlignment(Flex.CrossAlignment.STRETCH);

        TokenRow heading = new TokenRow(Tokens.Role.MEDIUM);
        heading.crossAlignment(Flex.CrossAlignment.CENTER);
        heading.add(Expanded.of(new Label("Theme editor")
                .setRole(Label.Role.TITLE).setStrong(true), 1));
        // Open and Save As are a separate call because the module does not decide where a
        // palette lives; an application that keeps them somewhere other than a file simply
        // does not add this row.
        heading.add(ThemeEditorFiles.buttons(editor));
        column.add(heading);

        column.add(new Label("Every tone of the palette, live. Click a swatch to pick a "
                + "colour; the window re-skins as you drag, the preview shows the tones a "
                + "running application never puts on screen at once, and the report beside "
                + "it measures each ink against every surface it can land on. Copy puts the "
                + "palette on the clipboard as text an application can ship beside its jar.")
                .setMuted(true).setWrap(true));

        column.add(Expanded.of(editor, 1));
        return column;
    }
}
