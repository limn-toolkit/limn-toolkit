package limn.components;

import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Card} that is clicked (ADR 073 §2.6, §2.8): a click outside the footer activates it
 * once, a press dragged out or taken by a part inside does not, and a press in the footer's band
 * does nothing but what the footer's own buttons do; Enter and Space on the card's own stop, on
 * their release; Tab's stops in the three cases; the hover on and off at the band's edge; a footer
 * added and taken away while it lives; and a control in the clicked part refused at the setter, and
 * put out of reach when it arrives later.
 */
class CardActivationTest extends AccessibleComponentTestBase {

    private static final float W = 300;

    private final List<String> heard = new ArrayList<>();

    /** A part that takes the press itself, as a chart's legend does. */
    private static final class Grabby extends Widget<Grabby> {
        int presses;

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, 30);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                presses++;
                event.consume();
            }
        }
    }

    /** A widget that takes the keyboard and wants none of its keys. */
    private static final class Focusable extends Widget<Focusable> {
        Focusable() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(40, 20);
        }
    }

    /** Binds, gives the window the test's ruler, and only then adds the card: see CardTest. */
    private Card bindCard(Card card) {
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        root.add(new SizedBox(W, SizedBox.UNSET, card));
        root.add(new SizedBox(W, 60, new Label("Below")));
        frame();
        return card;
    }

    private Card invoice() {
        return new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042")
                .setSubtitle("Harbor Lane Supplies");
    }

    private void press(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
    }

    private void click(float x, float y) {
        press(x, y);
        release(x, y);
    }

    private void key(int key, boolean down) {
        drive(scene).keyEvent(key, down, false, 0);
        drive(scene).inputBatchEnded();
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private static float sx(Widget<?> w, float dx) {
        return w.localToScene(0, 0).x() + dx;
    }

    private static float sy(Widget<?> w, float dy) {
        return w.localToScene(0, 0).y() + dy;
    }

    @Test
    void aClickAnywhereOnACardWithNoFooterActivatesItOnce() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        Widget<?> title = card.area().children().get(0);
        click(sx(title, 4), sy(title, 4));
        click(sx(card, 3), sy(card, card.height() - 3));
        assertEquals(List.of("card", "card"), heard, "on the title, and on the padding at the bottom");
    }

    @Test
    void aPressOnOnePartReleasedOnAnotherInsideIsOneClick() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        Widget<?> title = card.area().children().get(0);
        Widget<?> content = card.content();
        press(sx(title, 4), sy(title, 4));
        release(sx(content, 4), sy(content, 4));
        assertEquals(List.of("card"), heard);
    }

    @Test
    void aPressDraggedOutOfTheCardDoesNothing() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        press(sx(card, 20), sy(card, 20));
        release(sx(card, 20), sy(card, card.height() + 30));
        assertEquals(List.of(), heard);
    }

    @Test
    void aPressAPartTakesIsThatPartsAndNotTheCards() {
        Grabby legend = new Grabby();
        Card card = bindCard(new Card(legend).setTitle("Revenue").onActivate(() -> heard.add("card")));
        click(sx(legend, 10), sy(legend, 10));
        assertEquals(1, legend.presses);
        assertEquals(List.of(), heard);
    }

    @Test
    void aPressInTheFootersBandActivatesNothingAndAFooterButtonIsItsOwn() {
        Button approve = new Button("Approve");
        approve.onAction(() -> heard.add("approve"));
        Button hold = new Button("Hold");
        hold.onAction(() -> heard.add("hold"));
        Card card = bindCard(invoice().setFooter(hold, approve).onActivate(() -> heard.add("card")));
        click(sx(approve, 5), sy(approve, 5));
        assertEquals(List.of("approve"), heard, "the button's own handler, and not the card's");
        heard.clear();
        float between = (sx(hold, hold.width()) + sx(approve, 0)) / 2;
        click(between, sy(approve, approve.height() / 2));
        click(sx(card, 3), sy(approve, approve.height() / 2));
        click(sx(card, 20), sy(card, card.height() - 2));
        Widget<?> area = card.area();
        click(sx(card, 20), sy(area, area.height() + 1));
        assertEquals(List.of(), heard, "between the buttons, beside them, under them, at the band's top");
        click(sx(card, 20), sy(area, area.height() - 1));
        assertEquals(List.of("card"), heard, "and just above the band, the card");
    }

    @Test
    void enterAndSpaceActivateOnTheirReleaseWhileTheCardHoldsTheKeyboard() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        key(Keys.TAB, true);
        key(Keys.TAB, false);
        assertTrue(card.area().isFocused(), "one stop: the card");
        key(Keys.SPACE, true);
        assertEquals(List.of(), heard, "not on the press");
        key(Keys.SPACE, false);
        key(Keys.ENTER, true);
        key(Keys.ENTER, false);
        assertEquals(List.of("card", "card"), heard);
    }

    @Test
    void aKeyFromAControlThatArrivedInsideDoesNotActivateTheCard() {
        Column content = new Column();
        content.add(new Label("Inside"));
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Focusable late = new Focusable();
        content.add(late);
        late.requestFocus(); // before the layout that finds it
        key(Keys.ENTER, true);
        key(Keys.ENTER, false);
        assertEquals(List.of(), heard, "the key bubbled from inside, and the card is not its target");
        frame();
        assertFalse(late.isFocused(), "found at the layout, and put out of reach");
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("card"), heard, "a press on it is the card's");
    }

    @Test
    void tabStopsInTheThreeCases() {
        Button inside = new Button("Inside");
        Button action = new Button("Approve");
        Card card = bindCard(new Card(inside).setTitle("Invoice").setFooter(action));
        List<Widget<?>> stops = tabStops(2);
        assertEquals(List.of(inside, action), stops, "not clicked: the controls of its parts, in order");

        card.setContent(new Label("Due Friday")).setFooter().onActivate(() -> { });
        frame();
        assertEquals(List.of(card.area(), card.area()), tabStops(2), "clicked, no footer: the card alone");

        card.setFooter(action);
        frame();
        // From the card, where the last case left the keyboard: the action, then round to the card.
        assertEquals(List.of(action, card.area()), tabStops(2), "with a footer: the card, then each action");
    }

    private List<Widget<?>> tabStops(int count) {
        List<Widget<?>> stops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            key(Keys.TAB, true);
            key(Keys.TAB, false);
            stops.add(scene.focusedWidget());
        }
        return stops;
    }

    @Test
    void theHoverGoesOnAndOffAtTheBandsEdge() {
        Button action = new Button("Approve");
        Card card = bindCard(invoice().setFooter(action).onActivate(() -> { }));
        Widget<?> title = card.area().children().get(0);
        move(sx(title, 4), sy(title, 4));
        assertTrue(card.isAreaHovered(), "over the title is over the card");
        move(sx(card, 4), sy(card.area(), card.area().height() - 1));
        assertTrue(card.isAreaHovered(), "and over its own padding, just above the band");
        move(sx(card, 4), sy(card.area(), card.area().height() + 1));
        assertFalse(card.isAreaHovered(), "and not in the band");
        move(sx(action, 4), sy(action, 4));
        assertFalse(card.isAreaHovered(), "nor over the footer's action");
    }

    @Test
    void aFooterAddedAndTakenAwayMovesTheBoundaryAndKeepsTheFocus() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        float whole = card.area().height();
        assertEquals(card.height(), whole, 0.01f, "with no footer, the whole card");
        card.area().requestFocus();
        List<Change> changes = new ArrayList<>();
        card.observeChanges((w, change) -> changes.add(change));

        Button action = new Button("Approve");
        card.setFooter(action);
        frame();
        assertTrue(card.area().height() < card.height() - action.height(), "the area stops above the footer");
        assertTrue(card.area().isFocused(), "the focus stayed: it is the same widget");
        assertEquals(List.of(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE)), changes);
        click(sx(action, 4), sy(action, 4));
        assertEquals(List.of(), heard);

        card.setFooter();
        frame();
        assertEquals(card.height(), card.area().height(), 0.01f, "the whole card again");
        click(sx(card, 4), sy(card, card.height() - 3));
        assertEquals(List.of("card"), heard);
    }

    @Test
    void aControlInTheClickedPartIsRefusedAtTheSetterAndAtOnActivate() {
        Card card = new Card(new Label("Due Friday")).onActivate(() -> { });
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> card.setContent(new Button("Pay")));
        assertTrue(refused.getMessage().contains(Button.class.getName()), refused.getMessage());
        Column nested = new Column();
        nested.add(new TextField());
        assertThrows(IllegalArgumentException.class, () -> card.setHeaderSuffix(nested), "found inside");
        assertThrows(IllegalArgumentException.class, () -> card.setMedia(new Checkbox("Pin")));
        assertThrows(IllegalArgumentException.class, () -> card.setHeaderPrefix(new Button("Go")));
        card.setFooter(new Button("Pay")); // the footer takes any action

        Card plain = new Card(new Button("Pay"));
        assertThrows(IllegalArgumentException.class, () -> plain.onActivate(() -> { }),
                "a card whose area holds a control cannot be made clicked");
        assertFalse(plain.isActivatable());
    }

    @Test
    void aControlThatArrivesLaterAnswersNothingAndIsGivenBack() {
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = new Button("Pay");
        late.onAction(() -> heard.add("pay"));
        content.add(late);
        frame();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("card"), heard, "drawn as it is, and the press is the card's");
        key(Keys.TAB, true);
        key(Keys.TAB, false);
        assertSame(card.area(), scene.focusedWidget(), "Tab passes it by");

        card.onActivate(null);
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard, "a plain card again: the button is the application's again");
        assertEquals(0, card.inertControls(), "and the card holds it no longer");
    }

    /** A card whose content gained a button after it was activated: the button is out of reach. */
    private Button lateButton(Column content) {
        Button late = new Button("Pay");
        late.onAction(() -> heard.add("pay"));
        content.add(late);
        frame();
        return late;
    }

    @Test
    void aControlMovedFromTheAreaToTheFooterAsTheWarningSaysAnswersThere() {
        // The audit's scenario: the warning says "put it in the footer", and the button moved there
        // stayed dead, no click and no focus, since only replacing its whole part gave it back.
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = lateButton(content);
        assertEquals(1, card.inertControls());

        content.remove(late);
        card.setFooter(late);
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard, "the footer's button is clicked as the button it is");
        late.requestFocus();
        assertSame(late, scene.focusedWidget(), "and takes the keyboard");
        assertEquals(0, card.inertControls(), "the card holds it no longer");
    }

    @Test
    void aControlMovedToTheFooterInsideItsContainerAnswersThere() {
        // The hard case: the control is not the widget moved but inside it, and the move happens
        // with no frame between taking it out and putting it in.
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Row actions = new Row();
        Button late = new Button("Pay");
        late.onAction(() -> heard.add("pay"));
        actions.add(late);
        content.add(actions);
        frame();
        assertEquals(1, card.inertControls());

        content.remove(actions);
        card.setFooter(actions);
        assertEquals(0, card.inertControls(), "given back at once, before any frame");
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard);
    }

    @Test
    void aControlTakenOutOfTheAreaIsGivenBackAndForgotten() {
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = lateButton(content);

        content.remove(late);
        assertEquals(0, card.inertControls(), "the card keeps no reference to a control it no longer holds");
        Column root = (Column) card.parent().parent();
        root.add(late); // somewhere else in the window, outside any card
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard, "the button answers where the application put it");
    }

    @Test
    void aControlTakenOutWhileTheCardIsOutOfTheWindowAndPutBackElsewhereAnswers() {
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = lateButton(content);
        SizedBox box = (SizedBox) card.parent();
        Column root = (Column) box.parent();
        root.remove(box);
        content.remove(late); // nothing in a window hears this
        root.add(box);
        frame();
        assertEquals(0, card.inertControls());
        root.add(late);
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard);
    }

    @Test
    void aControlTakenOutOfACardThatNeverComesBackAnswersWhereItWent() {
        // The review's case: the card leaves the window (a page closed), its button is reused
        // elsewhere, and the card is never put back, so no layout of it gives anything back.
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = lateButton(content);
        SizedBox box = (SizedBox) card.parent();
        Column root = (Column) box.parent();
        root.remove(box);
        assertEquals(0, card.inertControls(), "a card out of the window holds nothing out of reach");
        content.remove(late);
        root.add(late);
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("pay"), heard);
        late.requestFocus();
        assertSame(late, scene.focusedWidget());
    }

    @Test
    void aControlMovedFromACardOutOfTheWindowIntoAnotherStaysTheOthersWhenTheFirstComesBack() {
        // The review's second case: card A, out of the window, still held the button when it went
        // into card B's area; A coming back gave it back there, a live control inside B's button.
        Column root = new Column();
        bind(root);
        scene.setTextRuler(RULER);
        Column inA = new Column();
        Column inB = new Column();
        Card a = new Card(inA).setTitle("A").onActivate(() -> heard.add("A"));
        Card b = new Card(inB).setTitle("B").onActivate(() -> heard.add("B"));
        SizedBox boxA = new SizedBox(W, SizedBox.UNSET, a);
        root.add(boxA);
        root.add(new SizedBox(W, SizedBox.UNSET, b));
        frame();
        Button late = lateButton(inA);
        assertEquals(1, a.inertControls());
        root.remove(boxA);
        inA.remove(late);
        inB.add(late);
        frame();
        assertEquals(1, b.inertControls(), "B holds it now");
        root.add(0, boxA);
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("B"), heard, "the press is B's");
        frame();
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("B"), heard, "and stays B's");
        assertEquals(0, a.inertControls());
    }

    @Test
    void aCardPutBackPutsItsControlsOutOfReachAgain() {
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button late = lateButton(content);
        SizedBox box = (SizedBox) card.parent();
        Column root = (Column) box.parent();
        root.remove(box);
        root.add(box);
        frame();
        assertEquals(1, card.inertControls(), "found again at the first layout back in the window");
        heard.clear();
        click(sx(late, 5), sy(late, 5));
        assertEquals(List.of("card"), heard);
    }

    @Test
    void aControlStillInTheAreaStaysOutOfReachAfterAnotherIsTakenOut() {
        Column content = new Column();
        Card card = bindCard(new Card(content).setTitle("Invoice").onActivate(() -> heard.add("card")));
        Button first = lateButton(content);
        Button second = lateButton(content);
        assertEquals(2, card.inertControls());
        content.remove(first);
        frame();
        assertEquals(1, card.inertControls());
        heard.clear();
        click(sx(second, 5), sy(second, 5));
        assertEquals(List.of("card"), heard, "the one left is still the card's");
    }

    @Test
    void aDisabledCardActivatesNothing() {
        Card card = bindCard(invoice().onActivate(() -> heard.add("card")));
        card.setEnabled(false);
        frame();
        click(sx(card, 20), sy(card, 20));
        assertEquals(List.of(), heard);
    }

    @Test
    void anActivationIsAnnouncedAsInvokedBeforeTheHandlerRuns() {
        Card card = bindCard(invoice());
        List<String> order = new ArrayList<>();
        card.observeChanges((w, change) -> order.add(change.aspect() + "/" + change.origin()));
        card.onActivate(() -> order.add("handler"));
        click(sx(card, 20), sy(card, 20));
        assertEquals(List.of("INVOKED/USER", "handler"), order);
        assertThrows(IllegalStateException.class, () -> card.onActivate(() -> { }), "one handler");
    }
}
