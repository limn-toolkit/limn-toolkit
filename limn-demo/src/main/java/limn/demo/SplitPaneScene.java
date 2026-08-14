package limn.demo;

import limn.components.Label;
import limn.components.ListView;
import limn.components.SplitPane;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.util.List;

/**
 * The split pane, in the shape an application actually uses it: a sidebar beside
 * a working area, and the working area split again over a console.
 *
 * <p>Two splitters rather than one three-pane control, because that is the whole
 * design: nesting is how a split pane grows, and a demo that only showed one
 * would hide the answer to the first question anyone asks about it.
 */
final class SplitPaneScene {

    private SplitPaneScene() {
    }

    /** Standalone {@code --scene split}. */
    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** Reusable subtree (kitchen-sink tab). */
    static Widget content() {
        Column col = new Column();
        col.gap(14).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("Split pane").setRole(Label.Role.TITLE).setStrong(true));
        col.add(new Label("Drag a divider to share the space; it is thin to look at and "
                + "wide to hit, and it takes the accent one shade lighter for hover than "
                + "for focus, lighter again while it is being dragged. A divider is not a "
                + "tab stop until asked; setDividerFocusable(true), on for both of these, "
                + "gives it the arrows: Shift for a single point, Home and End to park it "
                + "against either minimum. The split is a ratio, so both panes grow when "
                + "the window does.")
                .setMuted(true).setWrap(true));

        SplitPane inner = SplitPane.vertical(
                panel("Editor", "The pane that gets the room."),
                panel("Console", "Nested split: one splitter inside the other."));
        inner.setRatio(0.68f).setMinimums(80, 60).setDividerFocusable(true);

        SplitPane outer = SplitPane.horizontal(sidebar(), inner);
        outer.setRatio(0.26f).setMinimums(140, 240).setDividerFocusable(true);

        col.add(new SizedBox(SizedBox.UNSET, 380, outer));
        return col;
    }

    private static Widget sidebar() {
        List<String> files = List.of(
                "Main.java", "SplitPane.java", "SplitPaneTest.java", "Theme.java",
                "ColorPicker.java", "Spinner.java", "ListView.java", "ToolBar.java");
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return files.size();
            }

            @Override
            public Widget rowAt(int index) {
                return new Padding(Insets.symmetric(8, 6), new Label(files.get(index)));
            }
        });
        return new Panel(new Padding(Insets.all(6), list));
    }

    private static Widget panel(String title, String body) {
        Column col = new Column();
        col.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label(title).setStrong(true));
        col.add(new Label(body).setMuted(true).setWrap(true));
        return new Panel(new Padding(Insets.all(12), col));
    }

    /** A surface, so each pane reads as a pane rather than as more background. */
    private static final class Panel extends Widget {

        private final Widget child;

        Panel(Widget child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            child.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            // No border of its own: the divider is what separates two panes, and
            // a framed pane beside a framed pane hides the line doing the work.
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
        }
    }
}
