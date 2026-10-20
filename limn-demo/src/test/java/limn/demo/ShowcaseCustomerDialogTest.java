package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleTree;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customer's form on the Showcase's Dialogs page asks nothing the user did not ask for. It
 * opened, and 0.7 s later a "Save changes?" question opened over it by itself, from a timer in the
 * page, while the user had changed nothing and closed nothing (the owner's review of 2026-10-20).
 * The question belongs to the user closing a form he changed, and it is asked over the form, which
 * waits for the answer: Don't save and Save close both, Cancel goes back to the form as it was.
 */
class ShowcaseCustomerDialogTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);
    private static final limn.graphics.SvgRenderer RENDERER =
            limn.testfixtures.FakeSvg.renderer((svg, px) -> new Image(1, 1, new byte[4]));
    private static final int FRAME_MS = 16;

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    /** The windows of the scenarios run before this one, which a count of what is open leaves out. */
    private final java.util.Set<HeadlessWindow> earlier = new java.util.HashSet<>();
    private HeadlessUi ui;
    private HeadlessBackend backend;
    private HeadlessWindow window;
    private Scene scene;
    private Label status;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> nanos[0]);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        limn.graphics.SvgIcon.installRenderer(RENDERER);
        backend = new HeadlessBackend(ui.runtime());
    }

    @AfterEach
    void uninstallRuntime() {
        Images.uninstallDecoder(DECODER);
        limn.graphics.SvgIcon.uninstallRenderer(RENDERER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    /** The Dialogs page alone in a window, its dialogs in windows of their own or inside it. */
    private void openPage(boolean inWindow) {
        earlier.addAll(backend.windows());
        status = new Label("");
        Page.reportTo(status);
        Widget<?> page = DialogsScene.dialogs(status);
        scene = new Scene(page, () -> nanos[0]);
        window = backend.open("Dialogs", 1000, 760);
        scene.bind(window);
        frames(1);
        Checkbox inside = find(page, Checkbox.class,
                box -> DialogsScene.Text.IN_WINDOW.get().equals(box.text()));
        inside.setChecked(inWindow);
        frames(1);
    }

    /** Every window the backend has open framed once, with the clock a frame further on. */
    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(FRAME_MS);
            for (HeadlessWindow each : List.copyOf(backend.windows())) {
                each.frame();
            }
        }
    }

    /** The names of the dialogs open now, in every window, from what a screen reader is given. */
    private List<String> dialogsOpen() {
        List<String> names = new ArrayList<>();
        for (HeadlessWindow each : backend.windows()) {
            if (each.isClosed() || earlier.contains(each) || !each.publishesAccessibility()) {
                continue;
            }
            AccessibleTree tree = each.bridge().tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                if (tree.node(i).role() == Accessible.Role.DIALOG) {
                    names.add(tree.node(i).name());
                }
            }
        }
        return names;
    }

    private Button editCustomer() {
        return find(scene.root(), Button.class, b -> DialogsScene.Text.EDIT_CUSTOMER.get().equals(b.text()));
    }

    private static <W> W find(Widget<?> root, Class<W> kind, Predicate<W> which) {
        List<W> found = new ArrayList<>();
        collect(root, kind, which, found);
        assertTrue(!found.isEmpty(), "no " + kind.getSimpleName() + " of that kind on the page");
        return found.get(0);
    }

    private static <W> void collect(Widget<?> at, Class<W> kind, Predicate<W> which, List<W> into) {
        if (kind.isInstance(at) && which.test(kind.cast(at))) {
            into.add(kind.cast(at));
        }
        for (Widget<?> child : at.children()) {
            collect(child, kind, which, into);
        }
    }

    /** The scene a dialog's card is in: its own window's, or the page's in the window mode. */
    private Scene sceneOf(String dialog) {
        for (HeadlessWindow each : backend.windows()) {
            Scene shown = each.scene();
            if (shown != null && each != window && !each.isClosed() && !earlier.contains(each) && dialogsIn(each).contains(dialog)) {
                return shown;
            }
        }
        return scene;
    }

    private static List<String> dialogsIn(HeadlessWindow each) {
        List<String> names = new ArrayList<>();
        if (!each.isClosed() && each.publishesAccessibility()) {
            AccessibleTree tree = each.bridge().tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                if (tree.node(i).role() == Accessible.Role.DIALOG) {
                    names.add(tree.node(i).name());
                }
            }
        }
        return names;
    }

    /** The button of an open dialog with this caption, in whichever scene its card is. */
    private Button buttonOf(String dialog, String caption) {
        Scene where = sceneOf(dialog);
        List<Button> found = new ArrayList<>();
        for (Widget<?> root : roots(where)) {
            collect(root, Button.class, b -> caption.equals(b.text()), found);
        }
        assertTrue(!found.isEmpty(), "\"" + caption + "\" in " + dialog);
        return found.get(found.size() - 1); // the newest card's, should a caption repeat below it
    }

    private void click(String dialog, String caption) {
        Button button = buttonOf(dialog, caption);
        drive(button.scene()).click(button);
    }

    private static List<Widget<?>> roots(Scene where) {
        List<Widget<?>> roots = new ArrayList<>();
        roots.add(where.root());
        try {
            java.lang.reflect.Method overlays = Scene.class.getDeclaredMethod("overlays");
            overlays.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Widget<?>> layered = (List<Widget<?>>) overlays.invoke(where);
            roots.addAll(layered);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return roots;
    }

    @Test
    void theCustomersFormStaysAloneForFiveSecondsWithNoInputInEitherModeWithAndWithoutMotion() {
        for (boolean inWindow : new boolean[] {false, true}) {
            for (boolean reduced : new boolean[] {false, true}) {
                try (TestPreferences system = new TestPreferences()) {
                    system.reduceMotion(reduced);
                    String what = (inWindow ? "inside the window" : "in a window of its own")
                            + (reduced ? ", less motion" : ", with motion");
                    openPage(inWindow);
                    drive(scene).click(editCustomer());
                    String form = DialogsScene.Text.CUSTOMER_TITLE.get();
                    for (int ms = 0; ms < 5000; ms += FRAME_MS) {
                        frames(1);
                        if (ms > 400) { // past any fade in, whatever the motion
                            assertEquals(List.of(form), dialogsOpen(),
                                    what + ": at " + ms + " ms with no input, only the form is open");
                        }
                    }
                    assertEquals("", status.text(), what + ": nothing was answered");
                    click(form, limn.demo.ShowcaseStrings.ACTION_CANCEL.get());
                    frames(60);
                    assertEquals(List.of(), dialogsOpen(), what + ": Cancel on a form left as it was closes it");
                    assertEquals(DialogsScene.Text.UNCHANGED.format("Harbor Supply"), status.text(),
                            what + ": and asks nothing");
                }
            }
        }
    }

    /** Types over the customer's name in the open form. */
    private TextField changeName(String form, String to) {
        Scene formScene = sceneOf(form);
        TextField name = null;
        for (Widget<?> root : roots(formScene)) {
            List<TextField> fields = new ArrayList<>();
            collect(root, TextField.class, f -> "Harbor Supply".equals(f.text()), fields);
            if (!fields.isEmpty()) {
                name = fields.get(0);
            }
        }
        assertTrue(name != null, "the form shows the customer's name");
        formScene.requestFocus(name);
        name.selectAll();
        drive(formScene).type(to);
        frames(2);
        return name;
    }

    private List<String> sortedDialogs() {
        List<String> open = new ArrayList<>(dialogsOpen());
        open.sort(null);
        return open;
    }

    @Test
    void closingAChangedFormAsksOverItAndTheFormWaitsForTheAnswer() {
        for (boolean inWindow : new boolean[] {false, true}) {
            String what = inWindow ? "inside the window" : "in a window of its own";
            String form = DialogsScene.Text.CUSTOMER_TITLE.get();
            String question = DialogsScene.Text.UNSAVED_TITLE.format("Harbor Supply");
            List<String> both = new ArrayList<>(List.of(form, question));
            both.sort(null);
            openPage(inWindow);
            drive(scene).click(editCustomer());
            frames(40);
            TextField name = changeName(form, "Harbor Supply Co.");

            drive(sceneOf(form)).press(Keys.ESCAPE);
            frames(60);
            assertEquals(both, sortedDialogs(), what + ": Escape on a changed form asks over the open form");
            click(question, limn.demo.ShowcaseStrings.ACTION_CANCEL.get());
            frames(60);
            assertEquals(List.of(form), dialogsOpen(), what + ": the question's Cancel goes back to the form");
            assertEquals(DialogsScene.Text.STILL_EDITING.format("Harbor Supply"), status.text(), what);
            assertEquals("Harbor Supply Co.", name.text(), what + ": with the change as it was left");

            click(form, limn.demo.ShowcaseStrings.ACTION_CANCEL.get());
            frames(60);
            assertEquals(both, sortedDialogs(), what + ": Cancel asks too, over the form");
            click(question, DialogsScene.Text.DONT_SAVE.get());
            frames(60);
            assertEquals(List.of(), dialogsOpen(), what + ": Don't save closes both");
            assertEquals(DialogsScene.Text.DISCARDED.format("Harbor Supply"), status.text(), what);

            drive(scene).click(editCustomer());
            frames(40);
            changeName(form, "Harbor Supply Ltd.");
            click(form, limn.demo.ShowcaseStrings.ACTION_CANCEL.get());
            frames(60);
            assertEquals(both, sortedDialogs(), what + ": asked again on a new form");
            click(question, limn.demo.ShowcaseStrings.ACTION_SAVE.get());
            frames(60);
            assertEquals(List.of(), dialogsOpen(), what + ": Save closes both");
            assertEquals(DialogsScene.Text.SAVED.format("Harbor Supply"), status.text(), what);
        }
    }
}
