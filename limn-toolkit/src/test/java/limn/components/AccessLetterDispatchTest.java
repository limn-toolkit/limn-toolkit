package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;
import limn.input.Keys;
import limn.scene.internal.AccessLetterSystem;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Column;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an access letter does on Alt and the letter (ADR 070): presses a button, flips a box,
 * selects a radio, brings the keyboard to the field a label names; within the window or the
 * topmost modal, a control before the menu bar, several of one letter taking turns; drawn while
 * Alt is held; none of it on macOS. Run as each system answers, whatever the host.
 */
class AccessLetterDispatchTest extends AccessibleComponentTestBase {

    private boolean system;
    private limn.i18n.StringBundle bundle;

    @BeforeEach
    void letters() {
        system = AccessLetterSystem.setPresentForTests(true);
    }

    @AfterEach
    void restore() {
        AccessLetterSystem.setPresentForTests(system);
        if (bundle != null) {
            I18n.removeBundle(bundle);
        }
        I18n.setLocale(Locale.ENGLISH);
    }

    private void show(limn.scene.Widget<?>... widgets) {
        Column root = new Column();
        for (limn.scene.Widget<?> widget : widgets) {
            root.add(widget);
        }
        bind(root, new StubWindow(false));
        frame();
    }

    private void altAnd(int key) {
        drive(scene).press(key, Keys.MOD_ALT);
        frame();
    }

    @Test
    void altAndTheLetterPressesAButtonAndLeavesTheKeyboardWhereItWas() {
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button("&Save").onAction(saved::incrementAndGet);
        TextField name = new TextField();
        show(name, save);
        scene.requestFocus(name);
        assertEquals("Save", save.text(), "the mark is not shown");

        altAnd(Keys.S);
        assertEquals(1, saved.get());
        assertSame(name, scene.focusedWidget(), "a press is not a move of the keyboard");
    }

    @Test
    void altAndTheLetterFlipsABoxAndSelectsARadioAndBringsTheKeyboardThere() {
        Checkbox notify = new Checkbox(Checkbox.Variant.BOX, "&Notify me");
        RadioButton yearly = new RadioButton("&Yearly");
        RadioButton monthly = new RadioButton("Monthly");
        ButtonGroup group = new ButtonGroup();
        group.add(monthly);
        group.add(yearly);
        monthly.select();
        show(notify, monthly, yearly);

        altAnd(Keys.N);
        assertTrue(notify.isChecked());
        assertSame(notify, scene.focusedWidget());

        altAnd(Keys.Y);
        assertTrue(yearly.isSelected());
        assertSame(yearly, scene.focusedWidget());
    }

    @Test
    void aLabelThatNamesAFieldBringsTheKeyboardToItAndAnyOtherLabelIsText() {
        TextField field = new TextField();
        Label name = new Label("&Name").setLabelFor(field);
        Label plain = new Label("AT&T");
        show(plain, name, field);
        assertEquals("Name", name.text());
        assertEquals("AT&T", plain.text(), "a label that names nothing shows its ampersand");

        altAnd(Keys.N);
        assertSame(field, scene.focusedWidget());
        altAnd(Keys.T);
        assertSame(field, scene.focusedWidget(), "AT&T marks nothing");
    }

    /**
     * A label naming a composite that takes no keyboard itself brings it to the composite's first
     * part that does: Alt and the letter of a date picker's, a range slider's or an expander's
     * caption were consumed and moved nothing.
     */
    @Test
    void aLabelNamingACompositeBringsTheKeyboardToItsFirstPart() {
        limn.components.date.DatePicker date = new limn.components.date.DatePicker()
                .setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-09-16T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        RangeSlider nights = new RangeSlider(1, 14);
        Expander more = new Expander("More", new Button("Inside"));
        Button start = new Button("Start");
        show(start, new Label("&Delivery date").setLabelFor(date), date,
                new Label("&Nights").setLabelFor(nights), nights,
                new Label("&Options").setLabelFor(more), more);
        scene.requestFocus(start);

        java.util.Map<Integer, limn.scene.Widget<?>> named = new java.util.LinkedHashMap<>();
        named.put(Keys.D, date);
        named.put(Keys.N, nights);
        named.put(Keys.O, more);
        for (java.util.Map.Entry<Integer, limn.scene.Widget<?>> each : named.entrySet()) {
            altAnd(each.getKey());
            limn.scene.Widget<?> focused = scene.focusedWidget();
            assertNotNull(focused, each.getValue().getClass().getSimpleName());
            assertTrue(isWithin(focused, each.getValue()),
                    each.getValue().getClass().getSimpleName() + " has the keyboard, not " + focused);
        }
    }

    private static boolean isWithin(limn.scene.Widget<?> widget, limn.scene.Widget<?> ancestor) {
        for (limn.scene.Widget<?> at = widget; at != null; at = at.parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * A letter is a key the user pressed, so the focus it moves is the user's: every path called
     * the public requestFocus, and a watcher of origins (ADR 040) read Alt+letter as code.
     */
    @Test
    void theFocusALetterMovesIsTheUsers() {
        TextField field = new TextField();
        Label name = new Label("&Name").setLabelFor(field);
        Checkbox notify = new Checkbox(Checkbox.Variant.BOX, "N&otify me");
        RadioButton yearly = new RadioButton("&Yearly");
        Button save = new Button("&Save");
        Button send = new Button("S&end");
        Button sort = new Button("&Sort");
        show(name, field, notify, yearly, save, send, sort);
        java.util.List<String> origins = new java.util.ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && source.isFocused()) {
                origins.add(change.origin().name());
            }
        });

        altAnd(Keys.N);
        altAnd(Keys.O);
        altAnd(Keys.Y);
        altAnd(Keys.S); // two with S take turns
        assertSame(save, scene.focusedWidget());
        assertEquals(java.util.List.of("USER", "USER", "USER", "USER"), origins);
    }

    @Test
    void severalWithOneLetterTakeTurnsWithTheKeyboardAndNoneActs() {
        AtomicInteger acted = new AtomicInteger();
        Button save = new Button("&Save").onAction(acted::incrementAndGet);
        Button send = new Button("&Send").onAction(acted::incrementAndGet);
        show(save, send);

        altAnd(Keys.S);
        assertSame(save, scene.focusedWidget());
        altAnd(Keys.S);
        assertSame(send, scene.focusedWidget());
        altAnd(Keys.S);
        assertSame(save, scene.focusedWidget(), "and round again");
        assertEquals(0, acted.get());
    }

    /**
     * A key that prints nothing is no letter, though its code is one: Home is 268, the code of Č,
     * and Insert is 260, Ą's. Alt+Home pressed a Czech "&amp;Člen", and Insert chose a Polish menu
     * row; the layout's Č and Ą still reach them.
     */
    @Test
    void aKeyThatPrintsNothingIsNoLetterThoughItsCodeIsOne() {
        AtomicInteger member = new AtomicInteger();
        AtomicInteger action = new AtomicInteger();
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("&Ąkcja", action::incrementAndGet));
        Button join = new Button("&Člen").onAction(member::incrementAndGet);
        show(bar, join);

        altAnd(Keys.HOME);
        assertEquals(0, member.get(), "Alt+Home is not Alt+Č");
        drive(scene).keyEvent(Keys.C, 'č', true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.C, 'č', false, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        frame();
        assertEquals(1, member.get(), "the key the layout types Č with is");

        altAnd(Keys.S);
        assertTrue(bar.isOpen());
        drive(scene).press(Keys.INSERT, 0);
        frame();
        assertEquals(0, action.get(), "Insert is not Ą");
        assertTrue(bar.isOpen());
    }

    /**
     * A popup that leaves the keyboard beneath it, as an editable combo box's list drawn in the
     * scene does, hides none of the window's letters: they were collected from the topmost layer,
     * which holds none, and the key fell through to the menu bar.
     */
    @Test
    void aPopupThatLeavesTheKeyboardBeneathItHidesNoLetter() {
        TextField city = new TextField();
        TextField name = new TextField();
        show(city, new Label("&Name").setLabelFor(name), name);
        scene.requestFocus(city);
        SceneAccess.pushPopupLeavingKeyboard(scene, new Column());
        frame();

        altAnd(Keys.N);
        assertSame(name, scene.focusedWidget());
    }

    /** Several with one letter are logged once, as ADR 070 §3.4 promises, so the collision is found. */
    @Test
    void aSharedLetterIsLoggedOnce() {
        java.util.List<java.util.logging.LogRecord> logged = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger sceneLogger = java.util.logging.Logger.getLogger("limn.scene.Scene");
        sceneLogger.addHandler(capture);
        try {
            show(new Button("&Salvar"), new Button("&Sair"), new Button("&Abrir"));
            altAnd(Keys.S);
            altAnd(Keys.S);
            altAnd(Keys.S);
            altAnd(Keys.A);
        } finally {
            sceneLogger.removeHandler(capture);
        }
        java.util.List<java.util.logging.LogRecord> shared = logged.stream()
                .filter(r -> r.getMessage().contains("access letter")).toList();
        assertEquals(1, shared.size(), "once, however often the letter is pressed");
        assertEquals(java.util.logging.Level.WARNING, shared.get(0).getLevel());
        assertEquals('S', shared.get(0).getParameters()[0]);
    }

    /**
     * Two radios of one group with one letter take turns too, though only one of them is the
     * group's tab stop: the other is lent the stop for its turn, and gives it back when the keyboard
     * leaves. The turn stopped on the stop for ever, and the other could never be reached.
     */
    @Test
    void twoRadiosOfAGroupWithOneLetterTakeTurns() {
        RadioButton alpha = new RadioButton("&Alpha");
        RadioButton another = new RadioButton("&Another");
        ButtonGroup group = new ButtonGroup();
        group.add(alpha);
        group.add(another);
        Button done = new Button("Done");
        show(alpha, another, done);

        altAnd(Keys.A);
        assertSame(alpha, scene.focusedWidget());
        altAnd(Keys.A);
        assertSame(another, scene.focusedWidget(), "the radio that is not the stop has its turn");
        altAnd(Keys.A);
        assertSame(alpha, scene.focusedWidget(), "and round again");
        assertEquals(-1, group.selectedIndex(), "a turn selects nothing");

        altAnd(Keys.A);
        scene.requestFocus(done);
        assertFalse(another.isFocusable(), "the stop went back when the keyboard left");
        assertTrue(alpha.isFocusable());
    }

    @Test
    void hiddenAndDisabledControlsHaveNoLetter() {
        AtomicInteger acted = new AtomicInteger();
        Button hidden = new Button("&Hide").onAction(acted::incrementAndGet);
        hidden.setVisible(false);
        Button disabled = new Button("&Delete").onAction(acted::incrementAndGet);
        disabled.setEnabled(false);
        show(hidden, disabled);

        altAnd(Keys.H);
        altAnd(Keys.D);
        assertEquals(0, acted.get());
    }

    @Test
    void aControlTakesALetterBeforeTheMenuBar() {
        AtomicInteger sent = new AtomicInteger();
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("Report", () -> { }));
        Button send = new Button("&Send").onAction(sent::incrementAndGet);
        show(bar, send);

        altAnd(Keys.S);
        assertEquals(1, sent.get(), "the form in use wins the letter");
        assertFalse(bar.isOpen());
    }

    /**
     * Alt and a control's letter, then Alt let go: the menu bar does not take the keyboard. The
     * letter never reached the bar, which read the release as Alt pressed alone (seen on Windows).
     */
    @Test
    void lettingGoOfAltAfterAControlsLetterLeavesTheMenuBarAlone() {
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("Report", () -> { }));
        TextField field = new TextField();
        Label name = new Label("&Name").setLabelFor(field);
        show(bar, name, field);

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.N, 'n', true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.N, 'n', false, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertSame(field, scene.focusedWidget(), "the keyboard stays where the letter took it");

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertSame(bar, scene.focusedWidget(), "and Alt alone still reaches the bar");
    }

    /**
     * Alt let go while a menu in its own window takes the window's keys: the window's letters go.
     * The release went to the menu with the other keys, and the window kept drawing its letters
     * until Alt was pressed again (seen on Ubuntu, after Alt and a menu title's letter).
     */
    @Test
    void lettingGoOfAltWhileAMenuTakesTheKeysHidesTheWindowsLetters() {
        show(new Button("&Keep"));
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertTrue(SceneAccess.accessLettersShown(scene));

        java.util.List<Integer> routed = new java.util.ArrayList<>();
        limn.concurrent.Subscription route = SceneAccess.routeKeyboard(scene, new SceneAccess.KeyRoute() {
            @Override
            public void key(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers) {
                routed.add(key);
            }

            @Override
            public void character(int codepoint) {
            }

            @Override
            public limn.scene.@org.jspecify.annotations.Nullable Scene popup() {
                return null;
            }
        });
        try {
            drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
            drive(scene).inputBatchEnded();
            assertEquals(java.util.List.of(Keys.LEFT_ALT), routed, "the menu still has the release");
            assertFalse(SceneAccess.accessLettersShown(scene), "and the window's letters go");
        } finally {
            route.cancel();
        }
    }

    /** Esc closes a menu its title's letter opened: the title is not left lit, the field keeps the keys. */
    @Test
    void aMenuOpenedByItsLetterLeavesNoTitleLitWhenClosed() {
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Sales", new Menu().addItem("Report", () -> { }));
        TextField field = new TextField();
        show(bar, field);
        scene.requestFocus(field);

        altAnd(Keys.S);
        assertTrue(bar.isOpen());
        drive(scene).press(Keys.ESCAPE, 0);
        frame();
        assertFalse(bar.isOpen());
        assertSame(field, scene.focusedWidget());
        assertEquals(-1, bar.hoverIndexForTest(), "no title stays lit");
    }

    @Test
    void theTopmostModalTakesItsLettersAndNothingBeneathAnswers() {
        AtomicInteger killed = new AtomicInteger();
        Button kill = new Button("&Kill").onAction(killed::incrementAndGet);
        Column root = new Column();
        root.add(kill);
        bind(root, new OverlayHost()); // a window that can hold a dialog in its scene
        frame();
        AtomicReference<String> answer = new AtomicReference<>();
        new Dialog("Close?", "Unsaved changes.")
                .addButton("&Keep", "keep")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .show(scene)
                .thenAccept(answer::set);
        advanceTime(600, null); // the card settles over the window

        altAnd(Keys.K);
        advanceTime(1000, null);
        assertEquals("keep", answer.get());
        assertEquals(0, killed.get(), "a letter never reaches past a modal");
    }

    @Test
    void theLettersAreShownWhileAltIsHeld() {
        show(new Button("&Save"));
        assertFalse(SceneAccess.accessLettersShown(scene));
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertTrue(SceneAccess.accessLettersShown(scene));
        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(scene));
    }

    /**
     * Alt let go during a drag takes the letters away: the drag had the keyboard and the release
     * never reached the check, so every caption stayed underlined after the drop.
     */
    @Test
    void lettingGoOfAltDuringADragHidesTheLetters() {
        show(new Button("&Keep"), new DragSource());
        float x = 20;
        float y = 50;

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertTrue(SceneAccess.accessLettersShown(scene));
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, Keys.MOD_ALT, x, y, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(x, y + 40);
        drive(scene).inputBatchEnded();
        assertTrue(scene.isDragging());

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(scene), "Alt is up, though the drag has the keyboard");
    }

    /**
     * A menu in a window of its own, opened with the pointer, shows its letters while Alt is held in
     * the window that opened it, and is repainted when that changes: it read its own scene's flag,
     * which never moves because that window never has the keyboard.
     */
    @Test
    void aMenuInAWindowOfItsOwnShowsItsLettersWhileAltIsHeldInItsOwner() {
        Button opener = new Button("&Menu");
        show(opener);
        limn.scene.Widget<?> menuRoot = new DragSource().setInheritanceHost(opener);
        SceneAccess.drawsAccessLetters(menuRoot); // as a menu's surface says of itself
        limn.scene.Scene menu = new limn.scene.Scene(menuRoot);
        scene.graftPopup(menu);
        StubWindow menuWindow = new StubWindow(false);
        menu.bind(menuWindow);
        menu.renderFrame(canvas);
        int before = menuWindow.framesRequested;
        assertFalse(SceneAccess.accessLettersShown(menu));

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertTrue(SceneAccess.accessLettersShown(menu), "Alt is held in the window that opened it");
        assertTrue(menuWindow.framesRequested > before, "and the menu's window draws again to show them");

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(menu));
    }

    /**
     * Alt repaints the widgets that draw a letter and nothing else, and nothing at all where the
     * system keeps the letters drawn: every Alt press and release invalidated the whole window, so
     * an Alt+Tab away from a large table repainted it twice for a few underlines.
     */
    @Test
    void altRepaintsOnlyWhatDrawsALetter() {
        int[] paints = new int[2];
        DragSource save = new DragSource() { // a control that marks a letter, as a button does
            @Override
            protected void onPaint(limn.graphics.Canvas canvas) {
                paints[0]++;
            }
        };
        SceneAccess.ownAccessLetter(save, limn.scene.internal.AccessLetterOwner.of(() -> 'S', () -> save, () -> { }));
        DragSource table = new DragSource() {
            @Override
            protected void onPaint(limn.graphics.Canvas canvas) {
                paints[1]++;
            }
        };
        Column root = new Column();
        root.add(save);
        root.add(new DragSource()); // apart, so the underline's damage does not reach the table
        root.add(table);
        limn.scene.Scene unbound = new limn.scene.Scene(root, () -> 0L); // partial rendering on
        unbound.layoutPass(400, 300);
        limn.testing.NoopCanvas surface = new limn.testing.NoopCanvas(400, 300);
        unbound.renderFrame(surface);
        unbound.renderFrame(surface);
        unbound.renderFrame(surface);
        int[] settled = paints.clone();

        drive(unbound).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(unbound).inputBatchEnded();
        unbound.renderFrame(surface);
        assertTrue(paints[0] > settled[0], "the button draws its underline");
        assertEquals(settled[1], paints[1], "the table beside it is not drawn again");
        drive(unbound).keyEvent(Keys.LEFT_ALT, -1, false, false, 0);
        drive(unbound).inputBatchEnded();
        unbound.renderFrame(surface);
        unbound.renderFrame(surface);

        limn.scene.SystemPreferences was = limn.scene.SystemPreferences.current();
        try {
            limn.scene.SystemPreferences.report(new limn.scene.SystemPreferences(
                    limn.scene.SystemPreferences.ColorScheme.NO_PREFERENCE, false, false, true));
            unbound.renderFrame(surface);
            unbound.renderFrame(surface);
            settled = paints.clone();
            drive(unbound).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
            drive(unbound).inputBatchEnded();
            unbound.renderFrame(surface);
            assertEquals(settled[0], paints[0], "drawn at all times already: Alt changes nothing");
        } finally {
            limn.scene.SystemPreferences.report(was);
        }
    }

    /** A box that starts a drag kept in its window wherever it is pressed. */
    private static class DragSource extends limn.scene.Widget<DragSource> {
        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            return constraints.constrain(200, 120);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
        }

        @Override
        protected limn.transfer.Drag onDragStart(limn.scene.event.DragStartEvent event) {
            return limn.transfer.Drag.of(limn.transfer.Transfer.of(limn.transfer.DataFormat.TEXT, "card"))
                    .withinWindow();
        }
    }

    @Test
    void theLettersStayShownWhereTheSystemAsksForUnderlines() {
        show(new Button("&Save"));
        limn.scene.SystemPreferences was = limn.scene.SystemPreferences.current();
        try {
            limn.scene.SystemPreferences.report(new limn.scene.SystemPreferences(
                    limn.scene.SystemPreferences.ColorScheme.NO_PREFERENCE, false, false, true));
            assertTrue(SceneAccess.accessLettersShown(scene), "Windows' underline-access-keys setting");
        } finally {
            limn.scene.SystemPreferences.report(was);
        }
        assertFalse(SceneAccess.accessLettersShown(scene));
    }

    /**
     * The system's "underline access keys" setting turned on or off while a window is open draws or
     * hides every letter at once: nothing asked for a frame, so under partial rendering the
     * underlines came and went button by button as each happened to be drawn again.
     */
    @Test
    void theSystemsUnderlineSettingChangedRedrawsTheLetters() {
        show(new Button("&Save"));
        frame();
        frame();
        limn.scene.SystemPreferences was = limn.scene.SystemPreferences.current();
        int before = window.framesRequested;
        try {
            limn.scene.SystemPreferences.report(new limn.scene.SystemPreferences(
                    limn.scene.SystemPreferences.ColorScheme.NO_PREFERENCE, false, false, true));
            assertTrue(window.framesRequested > before, "the window draws its letters now");
            frame();
            before = window.framesRequested;
        } finally {
            limn.scene.SystemPreferences.report(was);
        }
        assertTrue(window.framesRequested > before, "and takes them away when the setting goes off");
    }

    @Test
    void macOSHasNone() {
        AccessLetterSystem.setPresentForTests(false);
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button("&Save").onAction(saved::incrementAndGet);
        show(save);
        assertEquals("Save", save.text(), "the word is kept");

        drive(scene).keyEvent(Keys.LEFT_ALT, -1, true, false, Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        assertFalse(SceneAccess.accessLettersShown(scene), "nothing is ever underlined");
        altAnd(Keys.S);
        assertEquals(0, saved.get(), "Option and a letter type a character, and press nothing");
        assertNull(node("Save").actions().accessKey());
    }

    @Test
    void eachLanguageCarriesItsOwnLetter() {
        Locale portuguese = Locale.forLanguageTag("pt-BR");
        bundle = PropertyBundle.of(portuguese, Map.of("test.access.save", "&Gravar"));
        I18n.addBundle(bundle);
        AtomicInteger saved = new AtomicInteger();
        Button save = new Button(new I18nString("test.access.save", "&Save")).onAction(saved::incrementAndGet);
        show(save);

        I18n.setLocale(portuguese);
        frame();
        assertEquals("Gravar", save.text());
        altAnd(Keys.S);
        assertEquals(0, saved.get(), "the English letter went with the English word");
        altAnd(Keys.G);
        assertEquals(1, saved.get());
    }

    @Test
    void aFieldNamedByAMarkedCaptionIsNamedByItsWordsAndCarriesItsKey() {
        TextField field = new TextField();
        show(new Label("&Name").setLabelFor(field), field);
        limn.accessibility.AccessibleNode named = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == limn.accessibility.Accessible.Role.TEXT_FIELD) {
                named = tree().node(i);
            }
        }
        assertNotNull(named, describe(tree()));
        assertEquals("Name", named.name(), "the field is named by the words, not by &Name" + describe(tree()));
        assertNotNull(named.actions(), describe(tree()));
        String key = named.actions().accessKey();
        assertNotNull(key, "Alt and the caption's letter reach the field" + describe(tree()));
        assertTrue(key.endsWith("N"), key);
    }

    /**
     * A caption the letter cannot reach lends the field no key: one kept hidden as the field's name,
     * one disabled, one in a hidden section. The field published "Alt+N" and the key did nothing.
     */
    @Test
    void aCaptionTheLetterCannotReachLendsNoKey() {
        TextField hiddenNamed = new TextField();
        Label hidden = new Label("&Name").setLabelFor(hiddenNamed);
        hidden.setVisible(false);
        TextField disabledNamed = new TextField();
        Label disabled = new Label("&City").setLabelFor(disabledNamed);
        disabled.setEnabled(false);
        TextField sectionNamed = new TextField();
        Column section = new Column();
        section.add(new Label("&Phone").setLabelFor(sectionNamed));
        section.setVisible(false);
        TextField reachedNamed = new TextField();
        show(hidden, hiddenNamed, disabled, disabledNamed, section, sectionNamed,
                new Label("&Email").setLabelFor(reachedNamed), reachedNamed);

        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            limn.accessibility.AccessibleNode node = tree().node(i);
            if (node.role() == limn.accessibility.Accessible.Role.TEXT_FIELD) {
                keys.add(node.name() + "=" + (node.actions() == null ? null : node.actions().accessKey()));
            }
        }
        String alt = limn.components.Accelerator.modifiersDisplay(Keys.MOD_ALT);
        assertEquals(java.util.List.of("Name=null", "City=null", "Phone=null", "Email=" + alt + "E"), keys,
                describe(tree()));
    }

    /**
     * A menu row is named by its words and publishes its letter as its access key, beside its
     * accelerator: a row published only the accelerator, so a reader never heard the letter that
     * chooses it, which a native menu item's access key gives.
     */
    @Test
    void aMenuRowPublishesItsLetterBesideItsAccelerator() {
        MenuItem save = MenuItem.of("Sa&lvar", () -> { })
                .setAccelerator(Accelerator.of(Keys.S, Keys.MOD_CONTROL));
        MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                .addMenu("&Arquivo", new Menu().add(save).addItem("Fechar", () -> { }));
        show(bar);
        altAnd(Keys.A);
        assertTrue(bar.isOpen());

        AccessibleNodeView row = null;
        AccessibleNodeView plain = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            limn.accessibility.AccessibleNode node = tree().node(i);
            if (node.role() == limn.accessibility.Accessible.Role.MENU_ITEM && "Salvar".equals(node.name())) {
                row = new AccessibleNodeView(node);
            } else if (node.role() == limn.accessibility.Accessible.Role.MENU_ITEM && "Fechar".equals(node.name())) {
                plain = new AccessibleNodeView(node);
            }
        }
        assertNotNull(row, "the row is named by its words, the mark taken out" + describe(tree()));
        assertEquals("L", row.node.actions().accessKey(), "the letter, which chooses it in the open menu");
        assertEquals(Accelerator.of(Keys.S, Keys.MOD_CONTROL).display(), row.node.actions().keyBinding());
        assertNotNull(plain, describe(tree()));
        assertNull(plain.node.actions().accessKey(), "a row with no letter has no key");
    }

    private record AccessibleNodeView(limn.accessibility.AccessibleNode node) {
    }

    @Test
    void aReaderHearsTheWordAndTheChord() {
        show(new Button("&Save"));
        String binding = node("Save").actions().accessKey();
        assertNotNull(binding, describe(tree()));
        assertTrue(binding.endsWith("S"), binding);
    }

    /**
     * A letter outside ASCII is a key a reader is told of too: the chord was built through an
     * accelerator, which has no name for a Cyrillic, Hebrew or accented letter, and five of the
     * Showcase's languages published no access key at all.
     */
    @Test
    void aLetterOfAnyScriptIsTheKeyAReaderIsTold() {
        show(new Button("&Файл"), new Button("&Éditer"), new Button("&מסמך"));
        for (String[] pair : new String[][] {{"Файл", "Ф"}, {"Éditer", "É"}, {"מסמך", "מ"}}) {
            String binding = node(pair[0]).actions().accessKey();
            assertNotNull(binding, pair[0] + describe(tree()));
            assertEquals(limn.components.Accelerator.modifiersDisplay(Keys.MOD_ALT) + pair[1], binding);
        }
    }
}
