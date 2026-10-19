package limn.demo;

import limn.components.Button;
import limn.components.Label;
import limn.components.ListView;
import limn.components.ScrollGutters;
import limn.components.ScrollView;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.SelectionMode;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.icons.tabler.TablerDevices;
import limn.icons.tabler.TablerSystem;
import limn.scene.Constraints;
import limn.scene.ControlSize;
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

import java.text.Collator;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists of a business application: an address book in A to Z, with a heading per letter, a
 * search that can find nothing, and one or several contacts selected at a time; and the calls of
 * the day, a log that nothing in is selected. Rows of two kinds and two heights, each kind pooled
 * on its own, and only the rows in view are built.
 */
final class ListScene {

    private ListScene() {
    }

    /** What the page says. */
    static final class Text {
        static final I18nString CONTACTS = new I18nString("showcase.lists.contacts", "Contacts");
        static final I18nString SEARCH = new I18nString("showcase.lists.search", "Name or company");
        static final I18nString ONE = new I18nString("showcase.lists.selectOne", "Select one");
        static final I18nString SEVERAL = new I18nString("showcase.lists.selectSeveral", "Select several");
        static final I18nString NO_MATCH =
                new I18nString("showcase.lists.noMatch", "No contacts match the search");
        static final I18nString CLEAR_SEARCH = new I18nString("showcase.lists.clearSearch", "Clear search");
        static final I18nString FAVOURITE = new I18nString("showcase.lists.favourite", "Favourite");
        static final I18nString STARRED = new I18nString("showcase.lists.starred", "{0} added to favourites.");
        static final I18nString UNSTARRED =
                new I18nString("showcase.lists.unstarred", "{0} removed from favourites.");
        static final I18nString OPENED = new I18nString("showcase.lists.opened", "{0} opened.");
        static final limn.i18n.PluralString SELECTED = limn.i18n.PluralString.of(
                "showcase.lists.selected", "{0} contact selected.", "{0} contacts selected.");
        static final I18nString CALLS = new I18nString("showcase.lists.calls", "Today's calls");
        static final I18nString MULTI_HINT = new I18nString("showcase.lists.multiHint",
                "Shift-click selects a range; Ctrl-click or Command-click adds one contact.");

        private Text() {
        }
    }

    /** One person in the address book. */
    record Contact(String name, String company, String role, String phone) {
        String initials() {
            String[] parts = name.split(" ");
            return parts.length < 2 ? name.substring(0, 1)
                    : parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1);
        }
    }

    /** A row of the address book: a letter heading or a contact. */
    private sealed interface Entry permits Letter, Person {
    }

    private record Letter(String letter) implements Entry {
    }

    private record Person(Contact contact) implements Entry {
    }

    /** A call of the day: who, which way, and when. */
    private record Call(Contact contact, Kind kind, LocalTime at) {
        enum Kind { INCOMING, OUTGOING, MISSED }
    }

    private static final String[] FIRST = {"Aiko", "Amara", "Bruno", "Camila", "Dmitri", "Elif", "Farah",
            "Gustavo", "Hana", "Ivo", "Jonas", "Kofi", "Leila", "Mateus", "Nadia", "Oskar", "Priya", "Quentin",
            "Rosa", "Sven", "Tomás", "Uma", "Viktor", "Wen", "Yara", "Zane"};
    private static final String[] LAST = {"Abbott", "Brandt", "Castillo", "Duarte", "Eriksen", "Ferreira",
            "Grant", "Holm", "Ibarra", "Jansen", "Kaur", "Lindqvist", "Mendes", "Novak", "Ortega", "Pereira",
            "Quist", "Ramos", "Sato", "Takahashi", "Uddin", "Varga", "Weber", "Xu", "Yilmaz", "Zielinski"};
    private static final String[] COMPANIES = {"Meridian Logistics", "Halcyon Foods", "Corvid Print",
            "Lumen Optics", "Tessera Tiles", "Orrery Instruments", "Vantage Cycles", "Kestrel Outdoor",
            "Sable Paper", "Aurum Jewellers", "Quill Stationery", "Zephyr Air"};
    private static final String[] ROLES = {"Purchasing", "Accounts payable", "Logistics", "Sales",
            "Operations", "Finance"};

    /** The address book, in the order a person looks a name up in: by name, as the language sorts. */
    static List<Contact> contacts() {
        Set<String> seen = new HashSet<>();
        List<Contact> contacts = new ArrayList<>();
        for (int i = 0; contacts.size() < 84; i++) {
            String name = FIRST[i * 7 % FIRST.length] + " " + LAST[(i * 11 + i / 26) % LAST.length];
            if (!seen.add(name)) {
                continue;
            }
            contacts.add(new Contact(name, COMPANIES[i % COMPANIES.length], ROLES[i * 5 % ROLES.length],
                    String.format(Locale.ROOT, "+1 555 %03d %04d", 100 + i * 37 % 900, 1000 + i * 731 % 9000)));
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        contacts.sort((a, b) -> collator.compare(a.name(), b.name()));
        return contacts;
    }

    /** The address book under its letters, keeping only the contacts {@code keep} lets through. */
    private static List<Entry> entries(List<Contact> contacts, String query) {
        String wanted = query.strip().toLowerCase(Locale.ROOT);
        List<Entry> rows = new ArrayList<>();
        String letter = "";
        for (Contact contact : contacts) {
            if (!wanted.isEmpty() && !contact.name().toLowerCase(Locale.ROOT).contains(wanted)
                    && !contact.company().toLowerCase(Locale.ROOT).contains(wanted)) {
                continue;
            }
            String first = contact.name().substring(0, 1);
            if (!first.equals(letter)) {
                letter = first;
                rows.add(new Letter(first));
            }
            rows.add(new Person(contact));
        }
        return rows;
    }

    static Scene create() {
        Scene scene = new Scene(new ScrollView(new Padding(Insets.all(20), content())));
        return scene;
    }

    /** The page, for the Showcase's Lists. */
    static Widget<?> content() {
        List<Contact> contacts = contacts();
        Set<Contact> favourites = new HashSet<>(List.of(contacts.get(2), contacts.get(5)));

        // ---- the address book: rows of two kinds, each pooled on its own
        Deque<LetterCell> letters = new ArrayDeque<>();
        Deque<ContactCell> people = new ArrayDeque<>();
        ListView<Entry> book = new ListView<>(entry -> {
            if (entry instanceof Letter letter) {
                LetterCell cell = letters.isEmpty() ? new LetterCell() : letters.pop();
                cell.bind(letter.letter());
                return cell;
            }
            Contact contact = ((Person) entry).contact();
            ContactCell cell = people.isEmpty() ? new ContactCell(favourites) : people.pop();
            cell.bind(contact);
            return cell;
        }, widget -> {
            if (widget instanceof LetterCell letter) {
                letters.push(letter);
            } else if (widget instanceof ContactCell person) {
                people.push(person);
            }
        });
        book.setItemName(entry -> entry instanceof Person person
                ? I18nString.literal(person.contact().name())
                : I18nString.literal(((Letter) entry).letter()));
        book.setItems(entries(contacts, ""));
        book.setSelectedIndex(3); // a contact, under the first letter
        // A strip for the bar, so it never sits over a row's star.
        book.setBarLayout(ScrollGutters.Layout.RESERVED);
        book.setVisibleRows(11);
        book.setAccessibleName(Text.CONTACTS);
        book.onActivate(index -> {
            if (book.items().get(index) instanceof Person person) {
                Page.report(Text.OPENED.format(person.contact().name()));
            }
        });
        book.onSelect(() -> {
            long count = book.selectedItems().stream().filter(Person.class::isInstance).count();
            if (book.selectionMode() == SelectionMode.MULTI && count > 1) {
                Page.report(Text.SELECTED.format(count));
            }
        });

        Button clear = new Button(Text.CLEAR_SEARCH).setStyle(Button.Style.SECONDARY);
        Column nothing = new Column();
        nothing.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        nothing.add(new Label(Text.NO_MATCH).setStrong(true).setIcon(TablerSystem.SEARCH.icon()));
        nothing.add(clear);
        EmptyState shown = new EmptyState(book, nothing);

        SearchField search = new SearchField();
        search.setPlaceholder(Text.SEARCH);
        search.setAccessibleName(Text.SEARCH);
        search.onChange(text -> {
            List<Entry> rows = entries(contacts, text);
            book.setItems(rows);
            shown.setEmpty(rows.isEmpty());
        });
        clear.onAction(() -> {
            search.clear();
            search.requestFocus();
        });

        SegmentedControl mode = new SegmentedControl(Text.ONE, Text.SEVERAL);
        mode.setAccessibleName(Text.CONTACTS);
        Label multiHint = Page.hint(Text.MULTI_HINT);
        multiHint.setVisible(false);
        mode.onSelect(index -> {
            book.setSelectionMode(index == 1 ? SelectionMode.MULTI : SelectionMode.SINGLE);
            multiHint.setVisible(index == 1);
        });

        Row tools = Page.row(12, new SizedBox(260, SizedBox.UNSET, search), mode);

        // ---- the calls of the day: a log, read and not chosen from
        Call.Kind[] kinds = Call.Kind.values();
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            calls.add(new Call(contacts.get(i * 9 % contacts.size()), kinds[i * 2 % kinds.length],
                    LocalTime.of(17 - i, (i * 23) % 60)));
        }
        ListView<Call> log = ListView.pooled(CallCell::new, CallCell::bind);
        log.setItems(calls);
        log.setItemName(call -> I18nString.literal(call.contact().name()));
        log.setSelectionMode(SelectionMode.NONE);
        log.setVisibleRows(5);
        log.setBarLayout(ScrollGutters.Layout.RESERVED);
        log.setAccessibleName(Text.CALLS);
        Page.afterLanguageChange(log::refresh); // a call's time is written as the language writes one

        Column page = Page.column();
        page.add(Page.firstSection(Text.CONTACTS));
        page.add(tools);
        page.add(Page.narrow(720, framed(shown)));
        page.add(multiHint);
        page.add(Page.section(Text.CALLS));
        page.add(Page.narrow(720, framed(log)));
        return page;
    }

    /** A list set in a hairline frame, so where it ends is seen and not guessed. */
    private static Widget<?> framed(Widget<?> list) {
        return new HairlineFrame(list);
    }

    // ------------------------------------------------------------------ rows

    /** A letter over the contacts it heads. */
    private static final class LetterCell extends Widget<LetterCell> {
        private final Label label = new Label("").setStrong(true).setMuted(true);
        private final Widget<?> content = new Padding(new Insets(10, 16, 4, 16), label);

        LetterCell() {
            label.setRole(Label.Role.LABEL);
            add(content);
        }

        void bind(String letter) {
            label.setText(letter);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = content.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), size.height());
        }

        @Override
        protected void onLayout() {
            content.measure(Constraints.tight(width(), height()));
            content.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(16, height() - 1, width() - 32, 1, Theme.of(this).outline());
        }
    }

    /** A contact: who, where they work, a number to call, and a star to keep them near the top of mind. */
    private static final class ContactCell extends Widget<ContactCell> {
        private final Avatar avatar = new Avatar();
        private final Label name = new Label("");
        private final Label works = new Label("").setMuted(true);
        private final Label phone = new Label("").setMuted(true);
        private final Button star = new Button("").setStyle(Button.Style.SECONDARY);
        private final Widget<?> content;
        private Contact contact;

        ContactCell(Set<Contact> favourites) {
            works.setRole(Label.Role.LABEL);
            phone.setRole(Label.Role.LABEL);
            star.setControlSize(ControlSize.SMALL);
            star.setAccessibleName(Text.FAVOURITE);
            star.setTooltip(Text.FAVOURITE);
            star.onAction(() -> {
                boolean starred = !favourites.remove(contact);
                if (starred) {
                    favourites.add(contact);
                }
                showStar(starred);
                Page.report((starred ? Text.STARRED : Text.UNSTARRED).format(contact.name()));
            });
            this.favourites = favourites;

            Column texts = new Column();
            texts.gap(2).crossAlignment(Flex.CrossAlignment.START);
            texts.add(name);
            texts.add(works);
            Row row = new Row();
            row.gap(14).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(avatar);
            row.add(Expanded.of(texts, 1));
            row.add(new SizedBox(130, SizedBox.UNSET, phone));
            row.add(star);
            content = new Padding(new Insets(8, 16, 8, 16), row);
            add(content);
        }

        private final Set<Contact> favourites;

        void bind(Contact contact) {
            this.contact = contact;
            avatar.bind(contact);
            name.setText(contact.name());
            works.setText(contact.company() + " · " + contact.role());
            phone.setText(contact.phone());
            showStar(favourites.contains(contact));
        }

        private void showStar(boolean starred) {
            star.setIcon(starred && TablerSystem.STAR.hasFilled() ? TablerSystem.STAR.filled()
                    : TablerSystem.STAR.icon());
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = content.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), size.height());
        }

        @Override
        protected void onLayout() {
            content.measure(Constraints.tight(width(), height()));
            content.layoutBox(0, 0, width(), height());
        }
    }

    /** A call: which way it went, with whom, and when. */
    private static final class CallCell extends Widget<CallCell> {
        private final Glyph glyph = new Glyph(20);
        private final Label name = new Label("");
        private final Label company = new Label("").setMuted(true);
        private final Label time = new Label("").setMuted(true);
        private final Widget<?> content;

        CallCell() {
            company.setRole(Label.Role.LABEL);
            time.setRole(Label.Role.LABEL);
            Row row = new Row();
            row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(glyph);
            row.add(new SizedBox(170, SizedBox.UNSET, name));
            row.add(Expanded.of(company, 1));
            row.add(time);
            content = new Padding(new Insets(8, 16, 8, 16), row);
            add(content);
        }

        void bind(Call call) {
            glyph.bind(switch (call.kind()) {
                case INCOMING -> TablerDevices.PHONE_INCOMING.icon();
                case OUTGOING -> TablerDevices.PHONE_OUTGOING.icon();
                case MISSED -> TablerDevices.PHONE_X.icon();
            }, call.kind() == Call.Kind.MISSED ? Theme::danger : Theme::textMuted);
            name.setText(call.contact().name());
            company.setText(call.contact().company());
            time.setText(Formats.time(call.at()));
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size size = content.measure(constraints);
            return constraints.constrain(constraints.maxWidth(), size.height());
        }

        @Override
        protected void onLayout() {
            content.measure(Constraints.tight(width(), height()));
            content.layoutBox(0, 0, width(), height());
        }
    }

    /** A person's initials on a disc tinted by one of the theme's tones, the same tone for the same name. */
    private static final class Avatar extends Widget<Avatar> {
        private static final float SIZE = 36;
        private final Label initials = new Label("").setStrong(true);
        private int tone;

        Avatar() {
            initials.setRole(Label.Role.LABEL);
            setAccessibleIgnored(true); // the row says the name
            add(initials);
        }

        void bind(Contact contact) {
            initials.setText(contact.initials());
            tone = Math.floorMod(contact.name().hashCode(), 5);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            initials.measure(Constraints.loose(SIZE, SIZE));
            return constraints.constrain(SIZE, SIZE);
        }

        @Override
        protected void onLayout() {
            Size size = initials.measure(Constraints.loose(SIZE, SIZE));
            initials.layoutBox((SIZE - size.width()) / 2, (SIZE - size.height()) / 2, size.width(), size.height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            Color color = switch (tone) {
                case 0 -> theme.primary();
                case 1 -> theme.success();
                case 2 -> theme.warning();
                case 3 -> theme.info();
                default -> theme.danger();
            };
            canvas.fillRoundRect(0, 0, SIZE, SIZE, SIZE / 2, color.withAlpha(theme.isDark() ? 0.34f : 0.22f));
        }
    }
}
