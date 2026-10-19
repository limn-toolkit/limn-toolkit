package limn.demo.site;

import limn.components.Button;
import limn.components.Card;
import limn.components.ImageView;
import limn.components.Label;
import limn.graphics.Image;

import java.util.function.Consumer;

/**
 * The layout guide's card examples, compiled: the page shows this file's marked regions, so what a
 * reader copies is code this repository builds. A customer's record made of the parts a card
 * takes, and an invoice that is clicked above the actions under it.
 */
public final class CardExample {

    private CardExample() {
    }

    /**
     * A customer's record: a picture across the top, a name over a city with the year's total
     * after them, a line about what they buy, and two actions at the bottom.
     *
     * @param photo the customer's picture
     * @return the card
     */
    public static Card customer(Image photo) {
        // #region guide:card
        Card card = new Card(new Label("Office furniture, delivered every Monday.").setWrap(true))
                .setMedia(new ImageView(photo).setFit(ImageView.Fit.COVER))
                .setMediaFit(Card.MediaFit.COVER)
                .setTitle("Harbor Lane Supplies")
                .setSubtitle("Lisbon")
                .setHeaderSuffix(new Label("$48,200").setStrong(true))
                .setFooter(new Button("Call").setStyle(Button.Style.SECONDARY), new Button("New order"));
        // #endregion
        return card;
    }

    /**
     * An invoice that opens when it is clicked anywhere above its footer, and is approved by the
     * footer's own button.
     *
     * @param open    what opening the invoice does
     * @param approve what approving it does
     * @return the card
     */
    public static Card invoice(Runnable open, Consumer<String> approve) {
        // #region guide:card-clicked
        Button approveButton = new Button("Approve");
        approveButton.onAction(() -> approve.accept("1042"));
        Card invoice = new Card(new Label("Three items, due on Friday.").setWrap(true))
                .setTitle("Invoice 1042")
                .setSubtitle("Harbor Lane Supplies")
                .setHeaderSuffix(new Label("$1,240.00").setStrong(true))
                .setFooter(approveButton);
        invoice.onActivate(open);
        // A reader hears the title and the subtitle; what else the card shows, it says here.
        invoice.setAccessibleDescription("Harbor Lane Supplies, $1,240.00, due on Friday");
        // #endregion
        return invoice;
    }
}
