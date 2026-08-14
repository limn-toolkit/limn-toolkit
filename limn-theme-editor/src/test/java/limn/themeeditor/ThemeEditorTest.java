package limn.themeeditor;

import limn.components.ColorPickerButton;
import limn.components.Theme;
import limn.components.ThemeFormat;
import limn.graphics.Color;
import limn.scene.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor, driven headlessly through a real scene.
 *
 * <p>{@link #everyToneIsEditable()} is the one to keep. Three things here iterate the token
 * vocabulary rather than naming it (the rows, the captions and the report), and a token
 * added to {@link Theme} that any of them misses does not fail to compile: it fails to
 * appear, which is the kind of defect that ships.
 */
class ThemeEditorTest extends EditorTestBase {

    private ThemeEditor editor;
    private Scene scene;
    private final List<Theme> changes = new ArrayList<>();

    @BeforeEach
    void layOutAnEditor() {
        editor = new ThemeEditor(Theme.dark());
        editor.setApplyLive(false); // most tests are about the value, not about the process
        editor.onChange(changes::add);
        scene = new Scene(editor);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);
    }

    // --- the vocabulary -----------------------------------------------------

    @Test
    void everyToneIsEditable() {
        Set<Theme.Token> missing = EnumSet.allOf(Theme.Token.class);
        for (Theme.Token token : Theme.Token.values()) {
            if (editor.wellFor(token) != null) {
                missing.remove(token);
            }
        }
        assertEquals(Set.of(), missing, "these tones have no row in the editor");
    }

    @Test
    void everyToneHasACaptionOfItsOwn() {
        Set<String> captions = new java.util.HashSet<>();
        for (Theme.Token token : Theme.Token.values()) {
            String caption = ThemeEditorStrings.of(token).get();
            assertNotEquals(token.key(), caption,
                    token.key() + " fell back to its key; it has no caption");
            assertTrue(captions.add(caption), "two tones share the caption " + caption);
        }
    }

    @Test
    void aWellCarriesTheToneItEdits() {
        for (Theme.Token token : Theme.Token.values()) {
            assertEquals(token.read(Theme.dark()), editor.wellFor(token).color(), token.key());
        }
    }

    // --- editing ------------------------------------------------------------

    @Test
    void settingAToneMovesThePaletteTheWellAndTheListener() {
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));

        assertEquals(Color.rgb(0x4FD1C5), editor.theme().primary);
        assertEquals(Color.rgb(0x4FD1C5), editor.wellFor(Theme.Token.PRIMARY).color());
        assertEquals(1, changes.size());
        assertEquals(Color.rgb(0x4FD1C5), changes.get(0).primary);
    }

    @Test
    void theEditorKnowsWhetherItHasBeenTouched() {
        assertFalse(editor.isModified());
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
        assertTrue(editor.isModified());
        editor.revert();
        assertFalse(editor.isModified());
        assertEquals(Theme.dark(), editor.theme());
    }

    @Test
    void revertIsReportedLikeAnyOtherChange() {
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
        changes.clear();
        editor.revert();
        assertEquals(1, changes.size(),
                "a caller applying what it is handed must be told the palette went back");
        assertEquals(Theme.dark().primary, changes.get(0).primary);
    }

    @Test
    void loadingAPaletteResetsWhatRevertGoesBackTo() {
        editor.setTheme(Theme.limn());
        assertFalse(editor.isModified());
        assertEquals(List.of(), changes, "the application is the source of a setTheme");

        editor.setToken(Theme.Token.PRIMARY, Color.BLACK);
        editor.revert();
        assertEquals(Theme.limn(), editor.theme());
    }

    @Test
    void theNameIsKeptAndTheToneIsNot() {
        editor.setTheme(Theme.dark().toBuilder().name("Ocean").build());
        assertEquals("Ocean", editor.theme().name);
    }

    // --- the derivations ----------------------------------------------------

    @Test
    void aDerivationFillsInTheGroupItSitsUnder() {
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x808080));
        editor.setToken(Theme.Token.PRIMARY_PRESSED, Color.rgb(0xFF00FF));
        changes.clear();

        click(ThemeEditorStrings.DERIVE_ACCENT.get());

        assertEquals(Color.rgb(0x666666), editor.theme().primaryPressed,
                "20% toward black, the ramp the built-in palettes were built from");
        assertEquals(Color.rgb(0x666666), editor.wellFor(Theme.Token.PRIMARY_PRESSED).color(),
                "the well has to follow, or the screen disagrees with the palette");
        assertFalse(changes.isEmpty(), "a derivation is an edit like any other");
    }

    /**
     * The trap this pins: the derive buttons are built once, and {@code setTheme} replaces
     * the builder under them. A button holding the original would derive into a palette
     * nobody is editing any more, and the tone on screen would simply not move.
     */
    @Test
    void aDerivationRunsOnTheBuilderTheEditorIsCurrentlyHolding() {
        editor.setTheme(Theme.light());
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x808080));

        click(ThemeEditorStrings.DERIVE_ACCENT.get());

        assertEquals(Color.rgb(0x666666), editor.theme().primaryPressed);
        // 12% toward white, not the 16% a dark palette uses (which would be #949494): the
        // derivation read the builder the editor holds now, not the dark one it opened on.
        assertEquals(Color.rgb(0x8F8F8F), editor.theme().primaryHover);
    }

    /** Clicks the button whose caption is {@code caption}, wherever the layout put it. */
    private void click(String caption) {
        limn.components.Button button = findButton(scene.root(), caption);
        assertNotNull(button, "no button captioned \"" + caption + '"');
        float x = button.localToSceneX() + button.width() / 2;
        float y = button.localToSceneY() + button.height() / 2;
        scene.mouseMoved(x, y);
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    private static limn.components.Button findButton(limn.scene.Widget root, String caption) {
        if (root instanceof limn.components.Button button && button.text().equals(caption)) {
            return button;
        }
        for (limn.scene.Widget child : root.children()) {
            limn.components.Button found = findButton(child, caption);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --- the base -----------------------------------------------------------

    /**
     * The combo used to change the palette and then show the first entry anyway: the pick
     * keeps the user's name, so the result never equalled the built-in it came from and the
     * lookup that drove the control answered -1 every time.
     */
    @Test
    void theBaseComboKeepsShowingWhatWasPicked() {
        int nord = baseEntryFor("Nordic");
        editor.pickBase(nord);

        assertEquals(nord, editor.baseChoice().selectedIndex(),
                "the control has to still show the palette it just applied");
        assertEquals(Theme.builtins().get(nord - 1).primary, editor.theme().primary);
    }

    @Test
    void pickingABaseKeepsTheNameTheUserTyped() {
        editor.setTheme(Theme.dark().toBuilder().name("Ocean").build());
        int nord = baseEntryFor("Nordic");
        editor.pickBase(nord);

        assertEquals("Ocean", editor.theme().name, "the name is the user's, not the base's");
        assertEquals(nord, editor.baseChoice().selectedIndex());
    }

    @Test
    void anEditAfterPickingABaseDoesNotMoveTheControl() {
        int nord = baseEntryFor("Nordic");
        editor.pickBase(nord);
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
        assertEquals(nord, editor.baseChoice().selectedIndex());
    }

    /** Presses at the slider's own left edge, which is what "drag it to zero" is. */
    private void dragSliderToStart(limn.components.Slider slider) {
        float x = slider.localToSceneX() + 1;
        float y = slider.localToSceneY() + slider.height() / 2;
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    /**
     * A palette that is not one of the built-ins has to say so rather than name one. The
     * control has no unselected state, so the list carries a "Custom" entry for it.
     */
    @Test
    void aPaletteThatCameFromNowhereShowsCustom() {
        assertEquals(baseEntryFor("Dark"), editor.baseChoice().selectedIndex(),
                "an editor opened on a built-in shows that built-in");

        editor.setTheme(Theme.dark().toBuilder().primary(Color.rgb(0x4FD1C5)).build());
        assertEquals(0, editor.baseChoice().selectedIndex(),
                "a palette nobody shipped must not be labelled as one that was");
    }

    @Test
    void pickingCustomDoesNothingAtAll() {
        Theme before = editor.theme();
        editor.pickBase(0);
        assertEquals(before, editor.theme());
        assertEquals(List.of(), changes);
    }

    /** The entry of the base list a built-in sits at: one past its index, after "Custom". */
    private static int baseEntryFor(String name) {
        for (int i = 0; i < Theme.builtins().size(); i++) {
            if (Theme.builtins().get(i).name.equals(name)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("no built-in called " + name);
    }

    // --- the shape ----------------------------------------------------------

    @Test
    void theShapeSliderIsAnEditLikeAnyOther() {
        assertEquals(1f, editor.theme().cornerScale);
        dragSliderToStart(editor.cornerSlider());

        assertEquals(0f, editor.theme().cornerScale);
        assertEquals(0f, editor.theme().tokens(limn.scene.ControlSize.MEDIUM).radiusMedium());
        assertFalse(changes.isEmpty(), "shape is part of the palette the listener is handed");
        assertTrue(editor.isModified());
    }

    /**
     * The readout showed a stale number for every drag that kept its digit count, which is
     * most of them. The defect was in {@code Label}, but this is the screen it was found on,
     * so the frame is asserted here too: the model being right is not the same as the number
     * on screen being right.
     */
    @Test
    void theShapeReadoutIsPaintedAndNotJustHeld() {
        assertEquals("8.0 pt", editor.cornerReadout().text());

        editor.setCornerScale(1.1f); // "8.8 pt": same digit count, so no layout pass runs
        assertEquals("8.8 pt", editor.cornerReadout().text());
        assertTrue(paintedTexts().contains("8.8 pt"),
                "the frame drew " + paintedTexts().stream().filter(t -> t.endsWith(" pt")).toList());

        editor.setCornerScale(3f); // "24.0 pt": wider, so this one does relayout
        assertTrue(paintedTexts().contains("24.0 pt"));
    }

    /** Every string a full frame draws. */
    private List<String> paintedTexts() {
        List<String> drawn = new ArrayList<>();
        scene.renderFrame(new NullCanvas(1000, 700) {
            @Override
            public void drawText(String text, float x, float y,
                                 limn.graphics.Font font, limn.graphics.Paint paint) {
                drawn.add(text);
            }
        });
        return drawn;
    }

    /**
     * The preview's whole reason to paint itself is showing a palette that is not the
     * process-wide one, and a palette now carries its corner radii. Reading the row off
     * {@code Theme.current()} showed the edited colours wearing somebody else's shape, which
     * is invisible for exactly as long as the two happen to be the same palette.
     */
    @Test
    void thePreviewDrawsTheEditedShapeAndNotTheProcessWideOne() {
        assertFalse(editor.isApplyLive(), "this test is about the two palettes disagreeing");
        editor.setCornerScale(0);

        List<Float> radii = new ArrayList<>();
        scene.renderFrame(new NullCanvas(1000, 700) {
            @Override
            public void fillRoundRect(limn.graphics.RoundRect r, limn.graphics.Paint paint) {
                radii.add(r.topLeft());
            }
        });
        assertTrue(radii.contains(0f),
                "nothing was drawn square, so the preview kept the palette it is not showing");
    }

    @Test
    void aLoadedShapeReachesTheSlider() {
        editor.setTheme(Theme.dark().toBuilder().cornerScale(2.5f).build());
        assertEquals(2.5f, editor.cornerSlider().value(), 1e-4f,
                "a control that ignores a loaded palette shows the wrong number and writes it back");
    }

    // --- the clipboard ------------------------------------------------------

    @Test
    void aPaletteSurvivesTheClipboard() {
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
        Theme copied = editor.theme();
        editor.copyToClipboard();

        editor.setTheme(Theme.light());
        assertTrue(editor.pasteFromClipboard());
        assertEquals(copied, editor.theme());
    }

    @Test
    void pastingSomethingElseFailsQuietlyAndChangesNothing() {
        Theme before = editor.theme();
        editor.setStatus("");
        scene.clipboard().set("not a palette at all");

        assertFalse(editor.pasteFromClipboard());
        assertEquals(before, editor.theme(), "a bad paste must leave the palette alone");
    }

    @Test
    void whatIsCopiedIsWhatTheFormatWrites() {
        editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
        editor.copyToClipboard();
        assertEquals(ThemeFormat.write(editor.theme()), scene.clipboard().get());
    }

    // --- applying live ------------------------------------------------------

    @Test
    void applyingLiveInstallsThePaletteAndDetachingPutsItBack() {
        Theme started = Theme.current();
        ThemeEditor live = new ThemeEditor(Theme.dark());
        Scene liveScene = new Scene(live);
        liveScene.setTextRuler(RULER);
        liveScene.layoutPass(1000, 700);

        assertTrue(live.isApplyLive(), "an editor applies what it edits unless told not to");
        live.setToken(Theme.Token.BACKGROUND, Color.rgb(0x101010));
        assertEquals(Color.rgb(0x101010), Theme.current().background);
        assertEquals(Color.rgb(0x101010), liveScene.background(),
                "the clear colour is the one thing a repaint cannot fix");

        live.detachLive();
        assertSame(started, Theme.current());
    }

    @Test
    void anEditorToldNotToApplyLeavesTheProcessAlone() {
        Theme started = Theme.current();
        editor.setToken(Theme.Token.BACKGROUND, Color.rgb(0x101010));
        assertSame(started, Theme.current());
    }

    // --- the report ---------------------------------------------------------

    /**
     * A finding that names a tone is only useful if the row it names can be acted on, and
     * the veil's row is the only one where the alpha the finding is about can be moved at
     * all. Turning that off would not merely hide a control: {@code setAlphaEnabled(false)}
     * makes the colour opaque on the spot, so every palette built here would ship a modal
     * that hides what it blocks, which the audit reports and nobody could then fix.
     */
    @Test
    void theVeilIsTheOnlyToneWhoseWellOffersAnAlpha() {
        for (Theme.Token token : Theme.Token.values()) {
            assertEquals(token == Theme.Token.SCRIM, editor.wellFor(token).isAlphaEnabled(),
                    token.key());
        }
    }

    /** The row has to show the number the finding is about, alpha pair included. */
    @Test
    void theVeilRowShowsTheAlphaAFindingWouldNameIt() {
        editor.setToken(Theme.Token.SCRIM, Color.rgba(0x000000, 0.1f));
        ColorPickerButton well = editor.wellFor(Theme.Token.SCRIM);
        assertEquals(26 / 255f, well.color().a(), 1e-6f, "the well kept the alpha");
        assertEquals("#0000001A", well.text(), "the caption has to carry the alpha pair");
    }

    /** And the line the report writes for it has to reach the panel. */
    @Test
    void aVeilFindingReachesTheReport() {
        editor.setToken(Theme.Token.SCRIM, Color.rgba(0x000000, 0.1f));
        assertTrue(labelTexts().stream().anyMatch(text -> text.startsWith("scrim: ")),
                "no veil line in the report: " + labelTexts());
    }

    /**
     * The token column is taller than any window it is put in, so the row a finding names
     * has to be reachable rather than merely present.
     */
    @Test
    void theVeilRowCanBeBroughtOnScreen() {
        scene.layoutPass(900, 320);
        ColorPickerButton well = editor.wellFor(Theme.Token.SCRIM);
        assertTrue(well.localToSceneY() + well.height() > 320,
                "the column has to overflow for this test to be about anything");

        well.revealInView();
        assertTrue(well.localToSceneY() >= 0
                        && well.localToSceneY() + well.height() <= 320,
                "the veil's row cannot be scrolled to: " + well.localToSceneY());
    }

    /** Every string the tree is holding, findings included. */
    private List<String> labelTexts() {
        List<String> texts = new ArrayList<>();
        collectLabels(scene.root(), texts);
        return texts;
    }

    private static void collectLabels(limn.scene.Widget root, List<String> into) {
        if (root instanceof limn.components.Label label) {
            into.add(label.text());
        }
        for (limn.scene.Widget child : root.children()) {
            collectLabels(child, into);
        }
    }

    @Test
    void theReportFollowsTheEdit() {
        assertNotNull(editor.audit());
        // Text the same colour as the surface it sits on is the plainest failure there is.
        editor.setToken(Theme.Token.TEXT, Theme.dark().surface);
        boolean flagged = editor.audit().stream()
                .anyMatch(f -> f.subject() == Theme.Token.TEXT
                        && f.against() == Theme.Token.SURFACE
                        && f.level() == ThemeAudit.Level.ERROR);
        assertTrue(flagged, "invisible body text has to be reported");
    }

    // --- layout -------------------------------------------------------------

    @Test
    void theEditorLaysOutAndPaintsHeadless() {
        scene.layoutPass(1000, 700);
        assertTrue(editor.width() > 0 && editor.height() > 0);
        // The preview paints a palette that is not the process-wide one, which is the whole
        // reason it exists; a frame is what proves it does not reach for Theme.current().
        scene.renderFrame(new NullCanvas(1000, 700));
    }

    @Test
    void aNarrowEditorStillLaysOut() {
        scene.layoutPass(360, 400);
        assertTrue(editor.width() > 0 && editor.height() > 0);
    }
}
