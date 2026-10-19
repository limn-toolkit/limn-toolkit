package limn.demo;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.Field;
import limn.components.Label;
import limn.components.Popover;
import limn.components.TextField;
import limn.concurrent.Ui;
import limn.icons.tabler.TablerSystem;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * {@code --scene popover}: a tool bar's Filter button and the popover it opens, holding a text
 * field, a combo box and a quiet button, for what only a person or an input method can try: typing
 * into a field in the popover's window of its own while the owner keeps the desktop's keyboard, a
 * Japanese or a Chinese composition included. Every change of the field's text is printed on
 * standard output, so a run driven from outside (a guest's key presser) can be read from its log.
 *
 * <p>With {@code -Dlimn.demo.popover.open=true} the popover opens by itself half a second after the
 * window does, so the keys a presser posts land in its field with no click first; with
 * {@code -Dlimn.demo.popover.search=true} the window's own search field takes the keyboard instead,
 * which is the control a run of the popover is compared with.
 */
final class PopoverLabScene {

    private PopoverLabScene() {
    }

    static Scene create() {
        TextField customer = new TextField();
        customer.onChange(text -> System.out.println("popover field: [" + text + "]"));
        ComboBox<String> status = new ComboBox<>(List.of("Open", "Shipped", "Paid", "Overdue"));
        Button clear = new Button("Clear").setStyle(Button.Style.TERTIARY);
        clear.onAction(() -> customer.setText(""));
        Column panel = new Column();
        panel.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        panel.add(new SizedBox(240, SizedBox.UNSET, new Field("Customer contains", customer)));
        panel.add(new Field("Status", status));
        panel.add(new Row() {
            {
                add(clear);
            }
        });

        Button filter = new Button("Filter").setStyle(Button.Style.SECONDARY)
                .setIcon(TablerSystem.FILTER.icon());
        Popover popover = new Popover(panel).setAccessibleName("Filter orders").attachTo(filter);
        popover.onClose(origin -> System.out.println("popover closed: " + origin));

        TextField search = new TextField().setPlaceholder("Search orders");
        search.onChange(text -> System.out.println("owner field: [" + text + "]"));
        Row tools = new Row();
        tools.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        tools.add(new SizedBox(220, SizedBox.UNSET, search));
        tools.add(filter);
        Column page = new Column();
        page.gap(16);
        page.add(tools);
        page.add(new Label("The Filter button opens a popover; type into its field.").setMuted(true));
        Scene scene = new Scene(new Padding(Insets.all(24), page));
        if (Boolean.getBoolean("limn.demo.popover.open")) {
            Ui.postDelayed(() -> popover.showFor(filter), 500);
            // Which window holds the desktop's keyboard decides whose input method composes: the
            // owner's, routing to the popover, or the popover's own after a click made it key.
            Ui.postDelayed(() -> {
                Scene in = popover.content().scene();
                System.out.println("owner window focused: " + scene.isWindowFocused()
                        + ", popover window focused: " + (in != null && in != scene && in.isWindowFocused()));
            }, 2500);
        }
        if (Boolean.getBoolean("limn.demo.popover.search")) {
            // The control run: the same keys into a field of the window itself, no popover.
            Ui.postDelayed(search::requestFocus, 500);
        }
        return scene;
    }
}
