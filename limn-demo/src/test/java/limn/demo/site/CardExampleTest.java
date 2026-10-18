package limn.demo.site;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.Card;
import limn.graphics.Image;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleHarness;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout guide's cards do what the guide says: the customer's record holds its picture across
 * the top and its actions at the bottom, and the invoice opens when it is clicked above its footer,
 * is approved by the footer's button and not opened by it, and is heard as a button named by its
 * title and described as the code says.
 */
class CardExampleTest {

    private static Image picture() {
        return new Image(16, 9, new byte[16 * 9 * 4]);
    }

    @Test
    void theCustomersPictureCoversTheTopAndItsActionsSitAtTheBottom() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Card card = CardExample.customer(picture());
            Column root = new Column();
            root.add(new SizedBox(320, SizedBox.UNSET, card));
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(400, 600);
            Widget<?> media = card.media();
            assertEquals(0, media.localToScene(0, 0).y() - card.localToScene(0, 0).y(), 0.01f,
                    "the picture out to the top edge");
            assertEquals(320, media.width(), 0.01f, "and across the card");
            Widget<?> last = card.footer().get(1);
            assertTrue(last.localToScene(0, 0).y() > card.content().localToScene(0, 0).y(),
                    "the actions under the text");
        }
    }

    @Test
    void theInvoiceOpensAboveItsFooterAndIsApprovedByItsButton() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<String> heard = new ArrayList<>();
            Card invoice = CardExample.invoice(() -> heard.add("open"), number -> heard.add("approve " + number));
            Column root = new Column();
            root.add(new SizedBox(320, SizedBox.UNSET, invoice));
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(400, 600);
            Widget<?> content = invoice.content();
            click(scene, content.localToScene(0, 0).x() + 4, content.localToScene(0, 0).y() + 4);
            Widget<?> approve = invoice.footer().get(0);
            click(scene, approve.localToScene(0, 0).x() + 4, approve.localToScene(0, 0).y() + 4);
            assertEquals(List.of("open", "approve 1042"), heard);
        }
    }

    @Test
    void theInvoiceIsHeardAsAButtonNamedByItsTitle() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Card invoice = CardExample.invoice(() -> { }, number -> { });
            Column root = new Column();
            root.add(new SizedBox(320, SizedBox.UNSET, invoice));
            AccessibleHarness harness = new AccessibleHarness(ui.runtime(), root);
            AccessibleNode button = null;
            for (int i = 0; i < harness.tree().nodeCount(); i++) {
                AccessibleNode node = harness.tree().node(i);
                if (node.role() == Accessible.Role.BUTTON && node.name().equals("Invoice 1042")) {
                    button = node;
                }
            }
            assertTrue(button != null, harness.describe());
            assertEquals("Harbor Lane Supplies, $1,240.00, due on Friday", button.description());
        }
    }

    private static void click(Scene scene, float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
    }
}
