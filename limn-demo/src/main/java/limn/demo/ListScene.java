package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.ScrollBar;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;

/**
 * Demo of {@link ListView}: a virtualized list of ~5&nbsp;000 rows of <b>two
 * different heights</b>: small grouping <i>headers</i> and taller rich
 * <i>cards</i> (thumbnail + labels + "Open" button + favorite switch). Rows are
 * supplied and pooled per type by the adapter (headers reuse header widgets,
 * cards reuse card widgets), and only the visible rows are materialized.
 */
final class ListScene {

    private static final int GROUPS = 500;
    private static final int CARDS_PER_GROUP = 9;

    private ListScene() {
    }

    /** A row is either a group header or a card. */
    private sealed interface RowItem permits Header, CardData {
    }

    private record Header(String title) implements RowItem {
    }

    private record CardData(String title, String subtitle, Image image) implements RowItem {
    }

    static Scene create() {
        Theme.setCurrent(Theme.dark());
        Label status = new Label("Wheel scrolls · click selects · ↑↓/PgUp/PgDn/Home/End navigate · "
                + "Tab focuses buttons/switch · Enter activates. Headers and cards have different heights.")
                .setMuted(true);
        ListView list = buildList(status::setText);

        Label heading = new Label("Virtualized list: headers + cards (variable heights)")
                .setFont(Theme.current().title);

        Column page = new Column();
        page.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        page.add(heading);
        page.add(Expanded.of(list, 1));
        page.add(new SizedBox(SizedBox.UNSET, 20, status));

        Widget root = new Padding(Insets.all(20), page);
        Scene scene = new Scene(root);
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** Builds the list widget, routing every interaction to {@code status}. Shared with the kitchen tab. */
    static ListView buildList(Consumer<String> status) {
        Image[] images = {
                Images.fromResource("/limn/demo/images/icon-star.png"),
                Images.fromResource("/limn/demo/images/icon-heart.png"),
                Images.fromResource("/limn/demo/images/icon-gear.png"),
                Images.fromResource("/limn/demo/images/icon-check.png"),
                Images.fromResource("/limn/demo/images/icon-download.png"),
                Images.fromResource("/limn/demo/images/logo.png"),
        };
        List<RowItem> rows = new ArrayList<>();
        for (int g = 0; g < GROUPS; g++) {
            rows.add(new Header("Group " + g));
            for (int c = 0; c < CARDS_PER_GROUP; c++) {
                int n = g * CARDS_PER_GROUP + c;
                rows.add(new CardData("Card #" + n,
                        "Description of item " + n + ": scroll, select and focus",
                        images[n % images.length]));
            }
        }
        boolean[] favorites = new boolean[rows.size()];
        IntConsumer onOpen = i -> status.accept("Open: row #" + i);
        ObjIntConsumer<Boolean> onFavorite = (checked, i) -> {
            favorites[i] = checked;
            status.accept((checked ? "★ favorited" : "☆ unfavorited") + ": row #" + i);
        };
        Image placeholder = images[0];

        // Per-type pools: the adapter reuses header/card widgets instead of the
        // ListView owning the cache, exactly the "you control the cache" model.
        Deque<HeaderCell> headerPool = new ArrayDeque<>();
        Deque<CardCell> cardPool = new ArrayDeque<>();

        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return rows.size();
            }

            @Override
            public Widget rowAt(int index) {
                RowItem row = rows.get(index);
                if (row instanceof Header header) {
                    HeaderCell cell = headerPool.isEmpty() ? new HeaderCell() : headerPool.pop();
                    cell.bind(header.title());
                    return cell;
                }
                CardData card = (CardData) row;
                CardCell cell = cardPool.isEmpty() ? new CardCell(placeholder, onOpen, onFavorite) : cardPool.pop();
                cell.bind(card, index, favorites[index]);
                return cell;
            }

            @Override
            public void recycle(Widget widget) {
                if (widget instanceof HeaderCell header) {
                    headerPool.push(header);
                } else if (widget instanceof CardCell card) {
                    cardPool.push(card);
                }
            }
        });
        list.setScrollbarPolicy(ScrollBar.Policy.AUTO);
        list.onSelect(i -> status.accept("Selected: row #" + i));
        list.onActivate(i -> status.accept("Activated (Enter): row #" + i));
        return list;
    }

    /** Small grouping header row (~40 pt tall). */
    private static final class HeaderCell extends Widget {
        private final Label label;
        private final Widget content;

        HeaderCell() {
            label = new Label("").setFont(Theme.current().label).setMuted(true);
            content = new Padding(new Insets(14, 8, 6, 8), label);
            add(content);
        }

        void bind(String title) {
            label.setText(title);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            content.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), 40);
        }

        @Override
        protected void onLayout() {
            content.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // A hairline under the group title.
            canvas.fillRect(6, height() - 1, width() - 12, 1, Theme.current().outline);
        }
    }

    /** Taller rich card row (~88 pt): thumbnail + title/subtitle + button + favorite switch. */
    private static final class CardCell extends Widget {
        private final ImageView image;
        private final Label title;
        private final Label subtitle;
        private final Checkbox favorite;
        private final Widget content;
        private int index = -1;

        CardCell(Image placeholder, IntConsumer onOpen, ObjIntConsumer<Boolean> onFavorite) {
            Theme theme = Theme.current();
            image = new ImageView(placeholder).setFit(ImageView.Fit.CONTAIN);
            title = new Label("").setFont(theme.body);
            subtitle = new Label("").setFont(theme.label).setMuted(true);
            Column texts = new Column();
            texts.gap(4).crossAlignment(Flex.CrossAlignment.START);
            texts.add(title);
            texts.add(subtitle);

            Button open = new Button("Open").setSecondary(true);
            open.onAction(() -> onOpen.accept(index));
            favorite = new Checkbox(Checkbox.Variant.SWITCH, "Fav");
            favorite.onChange(checked -> onFavorite.accept(checked, index));

            Row row = new Row();
            row.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(new SizedBox(52, 52, image));
            row.add(Expanded.of(texts, 1));
            row.add(open);
            row.add(favorite);
            // The extra pad is on the side the overlay scrollbar is on, and Padding resolves that
            // side from the direction: written as the right inset, it becomes the leading one and
            // lands on the physical left in a right-to-left subtree, which is exactly where the
            // list's own bar has moved to.
            content = new Padding(new Insets(12, 16, 12, 12), row);
            add(content);
        }

        void bind(CardData card, int index, boolean favorite) {
            this.index = index;
            title.setText(card.title());
            subtitle.setText(card.subtitle());
            image.setImage(card.image());
            this.favorite.setChecked(favorite);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            content.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), 88);
        }

        @Override
        protected void onLayout() {
            content.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(3, 3, width() - 6, height() - 6, theme.tokensFor(this).radiusMedium(), theme.surfaceRaised);
            canvas.drawRoundRect(3.5f, 3.5f, width() - 7, height() - 7, theme.tokensFor(this).radiusMedium(), 1, theme.outline);
        }
    }
}
