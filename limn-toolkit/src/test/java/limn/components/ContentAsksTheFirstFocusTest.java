package limn.components;

import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A form given to a dialog or a popover that starts the keyboard on one of its own fields from its
 * {@code onAttached} opens there. The request is obeyed once the form has joined, and the dialog's
 * or the popover's own first focus then ran after it: from the field just focused to the stop
 * after it, so the dialog opened on the second field and typing the name went into the wrong
 * one, or back to the content's first field, the asked one told of a gain and a loss in the turn.
 */
class ContentAsksTheFirstFocusTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private final List<String> events = new ArrayList<>();
    private HeadlessBackend backend;
    private Scene host;
    private Button opener;

    /** A form that asks for one of its fields as it joins a scene. */
    private static final class Form extends Column {
        TextField asked;

        @Override
        protected void onAttached() {
            scene().requestFocus(asked);
        }
    }

    private TextField field(String name) {
        TextField field = new TextField();
        field.setId(name);
        field.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.FOCUS) {
                events.add((field.isFocused() ? "gained " : "lost ") + name);
            }
        });
        return field;
    }

    private void openHost() {
        backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Orders", 600, 400);
        opener = new Button("Edit");
        Column page = new Column();
        page.add(opener);
        host = new Scene(page, clock::get);
        host.setTextRuler(RULER);
        host.bind(owner);
        owner.frame();
        host.requestFocus(opener);
        settle();
        events.clear();
    }

    private void settle() {
        for (int i = 0; i < 12; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aDialogOpensOnTheFieldItsContentAsksFor(DisplayMode mode) {
        for (int asked = 0; asked < 3; asked++) {
            openHost();
            Form form = new Form();
            TextField[] fields = {field("name"), field("email"), field("phone")};
            for (TextField f : fields) {
                form.add(f);
            }
            form.asked = fields[asked];
            Dialog dialog = new Dialog("Edit contact", "").setContent(form)
                    .addButton("Cancel", "cancel").addPrimaryButton("Save", "save")
                    .setCancelResult("cancel").setDisplayMode(mode);
            dialog.show(host);
            settle();
            Scene keys = fields[0].scene();
            String where = mode + ", asking " + fields[asked].id();
            assertSame(fields[asked], keys.focusedWidget(), where + ": " + events);
            assertEquals(List.of("gained " + fields[asked].id()), events, where + ": told once, of a gain");
        }
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPopoverOpensOnTheFieldItsContentAsksFor(DisplayMode mode) {
        for (int asked = 0; asked < 2; asked++) {
            openHost();
            Form form = new Form();
            TextField[] fields = {field("from"), field("to")};
            for (TextField f : fields) {
                form.add(f);
            }
            form.asked = fields[asked];
            Popover popover = new Popover(form).setDisplayMode(mode);
            popover.showFor(opener);
            settle();
            Scene keys = fields[0].scene();
            String where = mode + ", asking " + fields[asked].id();
            assertSame(fields[asked], keys.focusedWidget(), where + ": " + events);
            assertEquals(List.of("gained " + fields[asked].id()), events, where + ": told once, of a gain");
        }
    }

    @Test
    void aDialogWhoseContentAsksNothingStillOpensOnItsFirstField() {
        openHost();
        Column form = new Column();
        TextField name = field("name");
        form.add(name);
        form.add(field("email"));
        new Dialog("Edit contact", "").setContent(form).addPrimaryButton("Save", "save")
                .setDisplayMode(DisplayMode.IN_SCENE).show(host);
        settle();
        assertSame(name, host.focusedWidget(), String.valueOf(events));
    }
}
