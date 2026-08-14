package limn.demo.site;

import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.Theme;
import limn.components.TokenColumn;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.graphics.Fonts;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.themeeditor.ThemeEditor;
import limn.themeeditor.ThemeEditorFiles;

import java.nio.file.Path;

/**
 * The theme editor as the site captures it: the module's own screen, and nothing this file adds.
 *
 * <p>The point of publishing it is that {@code limn-theme-editor} is a <em>module an application
 * opts into</em>, not a program shipped with the toolkit, so the picture has to be the widget an
 * application would embed, arranged the way that application would arrange it, rather than a demo
 * built around it.
 *
 * <p>No explanatory text. The demo's own editor scene carries a paragraph describing what the
 * editor does, which belongs there; on the site that sentence is the page's job, in the page's
 * type size and in the reader's language: a capture cannot be translated.
 */
public final class ThemeEditorExample {

    private ThemeEditorExample() {
    }

    /**
     * The editor filling the window, under the palette currently in force.
     *
     * <p>{@code Theme.current()} rather than a palette named here: the capture sets the palette
     * before it builds, and the editor loads whatever it is handed as the palette being edited,
     * so this screen shows the shipped light theme being edited in the light pass and the dark
     * one in the dark pass, which is what a reader would see on their own machine.
     */
    public static Widget content() {
        ThemeEditor editor = new ThemeEditor(Theme.current());
        // The pickers open inside the window, which is the only way a capture of this screen can
        // contain one. It is also the honest presentation for a colour picker over a theme editor:
        // every tone it changes is being repainted live behind it, and a native window floats over
        // that rather than inside it.
        editor.setPickerDisplayMode(DisplayMode.IN_SCENE);

        TokenRow heading = new TokenRow(Tokens.Role.MEDIUM);
        heading.crossAlignment(Flex.CrossAlignment.CENTER);
        heading.add(Expanded.of(
                new Label("Theme editor").setRole(Label.Role.TITLE).setStrong(true), 1));
        // The file row comes from the module, not from here: where a palette is stored is the
        // application's decision, and this is the call an application makes to delegate it.
        heading.add(ThemeEditorFiles.buttons(editor));

        TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(heading);
        // The editor takes the slack: it is the screen, and a fixed height would leave the
        // window's own background showing under it in one palette and clip it in the other.
        column.add(Expanded.of(editor));
        return column;
    }

    /**
     * The screen on a canvas, wearing a typeface the palette asked for. Filmed by the capture;
     * see {@code Films.forShowcase}.
     *
     * <p><b>A face this repository ships, not one this machine happens to have.</b> The obvious
     * reading of "show it in a system font" is to take the first family the operating system
     * offers, and it cannot be done here: the gallery is captured on a developer's machine and
     * again on a Linux runner with an entirely different font set, so the published picture would
     * depend on which of the two ran last. Inter is committed under {@code limn-demo/fonts/}, so
     * every machine renders the same screen, and it is a real family the toolkit had to resolve,
     * not the embedded default under another name.
     *
     * <p>The family comes from {@link Fonts#load}, never from a literal: the name a file declares
     * and the name of the file need not agree, and a name that resolved to nothing would render
     * the screen in the default face and publish it without a word in the log.
     */
    public static Scene scene() {
        String family = Fonts.load(Path.of("limn-demo/fonts/Inter-Variable.ttf"));
        Theme themed = Theme.current().toBuilder().fontFamily(family).build();
        Theme.setCurrent(themed);
        themed.applyFontFamily();

        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(themed.background);
        return scene;
    }
}
