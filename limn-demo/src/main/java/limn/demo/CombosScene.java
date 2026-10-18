package limn.demo;

import limn.components.ComboBox;
import limn.components.SelectionMode;
import limn.graphics.Icon;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.icons.tabler.Tabler;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Every kind of combo box, as an order form uses them: a customer found by a search that takes a
 * moment, a status with an icon each, a person or nobody, tags, a long list of countries that
 * typing jumps through, a city that may be new, and one that cannot be changed.
 * {@code --scene combos}.
 */
final class CombosScene {

    /**
     * An order status, with its icon, and whether that icon means a direction and so turns around
     * where the interface reads right to left.
     */
    record Status(I18nString label, String icon, Icon.Mirroring mirroring) {
        Status(I18nString label, String icon) {
            this(label, icon, Icon.Mirroring.NEVER);
        }

        Icon glyph() {
            return Tabler.outline(icon);
        }
    }

    static final List<Status> STATUSES = List.of(
            new Status(Text.OPEN, "circle-dot"),
            new Status(Text.INVOICED, "file-invoice"),
            new Status(Text.SHIPPED, "truck"),
            new Status(Text.CANCELLED, "circle-x"),
            new Status(Text.RETURNED, "arrow-back-up", Icon.Mirroring.IN_RTL)); // back is the other way

    /** The customers a search runs over: the orders page's, and a few more. */
    private static final List<String> CUSTOMERS = List.of(
            "Aurum Jewellers", "Bluestem Logistics", "Corvid Print", "Driftwood Dental",
            "Elmstead Hardware", "Fernhill Bakery", "Granite Peak Outfitters", "Halcyon Foods",
            "Ironbark Builders", "Juniper Clinic", "Kestrel Outdoor", "Lumen Optics", "Meridian Logistics",
            "Northgate Auto Parts", "Orrery Instruments", "Pinecrest Veterinary", "Quill Stationery",
            "Redfern Textiles", "Sable Paper", "Tessera Tiles", "Upland Coffee Roasters",
            "Vantage Cycles", "Westbrook Legal", "Zephyr Air");

    private static final List<String> PEOPLE = List.of(
            "Ana Ribeiro", "Ben Okafor", "Chloé Martin", "Dmitri Volkov", "Farah Haddad",
            "Hiro Tanaka", "Inés García", "Jonas Weber", "Kavya Nair", "Mei Chen");

    private static final List<I18nString> TAGS = List.of(
            Text.PRIORITY, Text.WHOLESALE, Text.FIRST_ORDER, Text.SEASONAL, Text.EXPORT, Text.FRAGILE,
            Text.RECURRING);

    private static final List<String> CITIES = List.of(
            "Hamburg", "Kraków", "Lisbon", "Lyon", "Madrid", "Milan", "Porto", "Rotterdam", "Vienna");

    private static final List<I18nString> CHANNELS = List.of(Text.WEB_STORE, Text.PHONE, Text.PARTNERS);

    private CombosScene() {
    }

    /** Standalone {@code --scene combos}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(24), content()));
        return scene;
    }

    static Widget<?> content() {
        ComboBox<String> customer = new ComboBox<>(name -> name);
        customer.setSearch(CombosScene::findCustomers);
        customer.setPlaceholder(Text.CUSTOMER_HINT);
        report(customer, Text.CUSTOMER, () -> customer.text());

        ComboBox<Status> status = new ComboBox<>(STATUSES, Status::label).setItemIcon(Status::glyph, Status::mirroring);
        status.setSelectedIndex(1);
        report(status, Text.STATUS, () -> status.selectedItem().label().get());

        // Both functions are asked for null too: that is the empty option's text and icon.
        ComboBox<String> owner = new ComboBox<>(PEOPLE, person -> person == null ? Text.UNASSIGNED : person)
                .setItemIcon(person -> Tabler.outline(person == null ? "user-off" : "user-circle"));
        owner.setEmptySelectionAllowed(true).setEditable(true);
        owner.clearSelection();
        report(owner, Text.OWNER, () -> owner.text());

        ComboBox<I18nString> tags = ComboBox.localized(TAGS).setSelectionMode(SelectionMode.MULTI);
        tags.setEditable(true).setPlaceholder(Text.TAGS_HINT);
        tags.setSelectedIndices(0, 3);
        report(tags, Text.TAGS, () -> tags.selectedItems().stream().map(I18nString::get)
                .collect(Collectors.joining(", ")));

        ComboBox<Locale> country = new ComboBox<>(countries(),
                place -> place.getDisplayCountry(I18n.locale()));
        country.setSelectedItem(new Locale.Builder().setRegion("PT").build());
        report(country, Text.COUNTRY, () -> country.text());

        ComboBox<String> city = new ComboBox<>(CITIES, name -> name).setEditable(true);
        city.setFreeText(typed -> typed.strip());
        city.setSelectedItem("Lisbon");
        report(city, Text.CITY, () -> city.text());

        ComboBox<I18nString> channels = ComboBox.localized(CHANNELS).setSelectionMode(SelectionMode.MULTI);
        channels.setSelectedIndices(0, 1, 2);
        channels.setEnabled(false); // set by the customer's contract

        Column page = Page.column();
        page.add(Page.firstSection(Text.ORDER));
        page.add(Page.columns(Page.field(Text.CUSTOMER, customer), Page.field(Text.STATUS, status)));
        page.add(Page.columns(Page.field(Text.OWNER, owner), Page.field(Text.TAGS, tags)));
        page.add(Page.section(Text.DELIVERY));
        page.add(Page.columns(Page.field(Text.COUNTRY, country), Page.field(Text.CITY, city)));
        page.add(Page.columns(Page.field(Text.CHANNELS, channels), new SizedBox(0, 0)));
        page.add(Page.hint(Text.JUMP_HINT));
        return Page.narrow(720, page);
    }

    /** Says in the status line what the user chose, by the field's name. */
    private static void report(ComboBox<?> combo, I18nString field, java.util.function.Supplier<String> value) {
        combo.onSelect(() -> Page.report(Text.CHOSEN.format(field.get(), value.get())));
    }

    /** A customer search that answers after a moment, as one over the network does. */
    private static CompletableFuture<List<String>> findCustomers(String text) {
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        return CompletableFuture.supplyAsync(() -> CUSTOMERS.stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).contains(wanted))
                        .limit(8)
                        .toList(),
                CompletableFuture.delayedExecutor(600, TimeUnit.MILLISECONDS));
    }

    /** Every country, in the order the language in effect sorts their names. */
    private static List<Locale> countries() {
        Locale language = I18n.locale();
        Collator order = Collator.getInstance(language);
        List<Locale> all = new ArrayList<>();
        for (String code : Locale.getISOCountries()) {
            all.add(new Locale.Builder().setRegion(code).build());
        }
        all.sort(Comparator.comparing(place -> place.getDisplayCountry(language), order));
        return all;
    }

    /** What this page says, ready for translation. */
    static final class Text {
        private Text() {
        }

        static final I18nString ORDER = new I18nString("showcase.combos.order", "Order");
        static final I18nString CUSTOMER = new I18nString("showcase.combos.customer", "Customer");
        static final I18nString CUSTOMER_HINT = new I18nString("showcase.combos.customerHint", "Type to search");
        static final I18nString STATUS = new I18nString("showcase.combos.status", "Status");
        static final I18nString OPEN = new I18nString("showcase.combos.open", "Open");
        static final I18nString INVOICED = new I18nString("showcase.combos.invoiced", "Invoiced");
        static final I18nString SHIPPED = new I18nString("showcase.combos.shipped", "Shipped");
        static final I18nString CANCELLED = new I18nString("showcase.combos.cancelled", "Cancelled");
        static final I18nString RETURNED = new I18nString("showcase.combos.returned", "Returned");
        static final I18nString OWNER = new I18nString("showcase.combos.owner", "Assign to");
        static final I18nString UNASSIGNED = new I18nString("showcase.combos.unassigned", "Unassigned");
        static final I18nString TAGS = new I18nString("showcase.combos.tags", "Tags");
        static final I18nString TAGS_HINT = new I18nString("showcase.combos.tagsHint", "Add tags");
        static final I18nString PRIORITY = new I18nString("showcase.combos.priority", "Priority");
        static final I18nString WHOLESALE = new I18nString("showcase.combos.wholesale", "Wholesale");
        static final I18nString FIRST_ORDER = new I18nString("showcase.combos.firstOrder", "First order");
        static final I18nString SEASONAL = new I18nString("showcase.combos.seasonal", "Seasonal");
        static final I18nString EXPORT = new I18nString("showcase.combos.export", "Export");
        static final I18nString FRAGILE = new I18nString("showcase.combos.fragile", "Fragile");
        static final I18nString RECURRING = new I18nString("showcase.combos.recurring", "Recurring");
        static final I18nString DELIVERY = new I18nString("showcase.combos.delivery", "Delivery");
        static final I18nString COUNTRY = new I18nString("showcase.combos.country", "Country");
        static final I18nString CITY = new I18nString("showcase.combos.city", "City");
        static final I18nString CHANNELS = new I18nString("showcase.combos.channels", "Sales channels");
        static final I18nString WEB_STORE = new I18nString("showcase.combos.webStore", "Web store");
        static final I18nString PHONE = new I18nString("showcase.combos.phone", "Phone");
        static final I18nString PARTNERS = new I18nString("showcase.combos.partners", "Partners");
        static final I18nString JUMP_HINT = new I18nString("showcase.combos.jumpHint",
                "Open Country and type the first letters of a name to jump to it.");
        static final I18nString CHOSEN = new I18nString("showcase.combos.chosen", "{0}: {1}");
    }
}
