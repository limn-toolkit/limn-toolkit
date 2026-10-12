package limn.desktop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import limn.backend.Backend;
import limn.backend.spi.BackendPeer;
import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.MenuRow;
import limn.backend.spi.TrayState;
import limn.components.Menu;
import limn.components.Theme;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Icon;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.LayoutDirection;
import org.jspecify.annotations.Nullable;

/**
 * An icon an application keeps in the system's tray: the notification area on Windows, the menu
 * bar's status items on macOS, the panel's tray on a Linux desktop. It has a title, a tooltip, a
 * menu and, if the application wants one, an activation.
 *
 * <pre>{@code
 * Tray tray = Tray.create(backend, "orders");
 * tray.setIcon(icon);
 * tray.setTooltip("Orders: 3 waiting");
 * tray.setMenu(new Menu()
 *         .addItem("Open", () -> window.show())
 *         .addSeparator()
 *         .addItem("Quit", backend::requestQuit));
 * tray.onActivate(() -> window.show());
 * }</pre>
 *
 * <p><b>The system draws the menu</b>, from the same {@link Menu} a window's menu bar takes. It is
 * read each time it is about to open, so an application changes its menu, an item's check mark or
 * whether it is enabled as it does anywhere, and the next opening shows it. Of an item the system
 * shows its label, its icon, whether it is enabled and checked, a separator, a submenu and its
 * accelerator, which macOS also runs while the menu is open and the other systems only show,
 * where their menu shows one.
 *
 * <p><b>What a click does.</b> With no {@link #onActivate}, any click on the icon opens the menu.
 * With one, the click that means "this application" on that system runs it and the other click
 * opens the menu: the left click on Windows and on most Linux panels, a double click on some.
 * There are panels that never send an activation, so what the listener does should also be an
 * item of the menu.
 *
 * <p><b>A desktop may have no tray.</b> {@link #isAvailable} says whether the system took the
 * icon, and {@link #onAvailabilityChange} when that changes; it does not say that somebody sees
 * the icon, which no system tells an application. An application that hides its window "to the
 * tray" should minimize it instead where this is {@code false}.
 *
 * <p><b>A tray does not keep an application running.</b> An application whose windows may all be
 * hidden or closed while its icon stays sets {@link limn.backend.LoopLifetime#UNTIL_QUIT}.
 * Closing the backend takes every icon away.
 *
 * <p>Every method is called on the UI thread, and every listener and every menu item's action
 * runs there.
 */
public final class Tray {

    private static final Map<Backend, Set<String>> IN_USE = new WeakHashMap<>();
    private static final int MAX_ID = 64;

    private final String id;
    private final @Nullable Backend backend;
    private final TrayPeer peer;
    private final Subscription language;

    private I18nString title;
    private I18nString tooltip = I18nString.EMPTY;
    private @Nullable Icon icon;
    private @Nullable Icon monochrome;
    private @Nullable Icon attentionIcon;
    private boolean attention;
    private @Nullable Menu menu;
    private @Nullable Runnable activate;
    private LayoutDirection direction = LayoutDirection.LTR;
    private final List<Runnable> availabilityListeners = new ArrayList<>();
    private TrayMenu reading = TrayMenu.read(null);
    private @Nullable TrayState shown;
    private boolean disposed;

    private Tray(String id, @Nullable Backend backend, Function<TrayHost, TrayPeer> peers) {
        this.id = id;
        this.backend = backend;
        this.title = I18nString.literal(id);
        this.peer = Objects.requireNonNull(peers.apply(new Host()), "peer");
        this.language = I18n.observeChanges(this::push);
    }

    /**
     * Makes a tray icon. Nothing is shown until it is given an icon.
     *
     * @param backend the application's backend
     * @param id      this icon's identifier, the application's own and the same on every run:
     *                one to 64 ASCII letters, digits, dots, hyphens and underscores. A system
     *                remembers what the user chose for the icon by it, so two icons of one
     *                application have different ones
     * @return the tray
     * @throws IllegalArgumentException when {@code id} is not of that form
     * @throws IllegalStateException    when a tray of this backend that is not disposed has
     *                                  {@code id}, or off the UI thread
     */
    public static Tray create(Backend backend, String id) {
        Ui.checkUiThread();
        Objects.requireNonNull(backend, "backend");
        checkId(id);
        Set<String> used = IN_USE.computeIfAbsent(backend, b -> new HashSet<>());
        if (!used.add(id)) {
            throw new IllegalStateException("this backend already has a tray \"" + id + "\"");
        }
        try {
            return new Tray(id, backend, host -> BackendPeer.of(backend).createTray(id, host));
        } catch (RuntimeException | Error failed) {
            used.remove(id);
            throw failed;
        }
    }

    /** A tray over a peer a test hands in, with no backend. */
    static Tray over(String id, Function<TrayHost, TrayPeer> peers) {
        Ui.checkUiThread();
        checkId(id);
        return new Tray(id, null, peers);
    }

    private static void checkId(String id) {
        Objects.requireNonNull(id, "id");
        boolean fine = !id.isEmpty() && id.length() <= MAX_ID;
        for (int i = 0; fine && i < id.length(); i++) {
            char c = id.charAt(i);
            fine = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '-' || c == '_';
        }
        if (!fine) {
            throw new IllegalArgumentException("a tray's identifier is 1 to " + MAX_ID
                    + " ASCII letters, digits, dots, hyphens and underscores: \"" + id + "\"");
        }
    }

    /** @return the identifier this tray was made with */
    public String id() {
        return id;
    }

    /**
     * Sets the icon's name, which a panel shows beside it where it shows names and a screen
     * reader says. It is the identifier until set.
     *
     * @param title the name; not empty
     * @return this tray
     */
    public Tray setTitle(String title) {
        return setTitle(I18nString.literal(Objects.requireNonNull(title, "title")));
    }

    /**
     * Sets the icon's name to a text that follows the language.
     *
     * @param title the name; not empty in any language
     * @return this tray
     */
    public Tray setTitle(I18nString title) {
        Ui.checkUiThread();
        this.title = Objects.requireNonNull(title, "title");
        push();
        return this;
    }

    /** @return the icon's name now */
    public String title() {
        String text = title.get();
        return text.isEmpty() ? id : text;
    }

    /**
     * Sets the icon, shown as it is. Until a tray has this or {@link #setMonochromeIcon} it shows
     * nothing.
     *
     * @param icon the icon, or {@code null} for none
     * @return this tray
     */
    public Tray setIcon(@Nullable Icon icon) {
        Ui.checkUiThread();
        this.icon = icon;
        push();
        return this;
    }

    /**
     * Sets an icon that takes the panel's colour: a shape, whose own colour is not used. macOS
     * colours it as it does its own status items and Windows shows it light or dark as its task
     * bar is. A Linux panel does not say its colour, so there the icon of {@link #setIcon} is
     * shown, and this one in a grey that reads on a light and a dark panel when there is no
     * other.
     *
     * @param shape an icon that is {@linkplain Icon#tintable() tintable}, or {@code null} for none
     * @return this tray
     * @throws IllegalArgumentException when {@code shape} is not tintable
     */
    public Tray setMonochromeIcon(@Nullable Icon shape) {
        Ui.checkUiThread();
        if (shape != null && !shape.tintable()) {
            throw new IllegalArgumentException("a monochrome icon is a tintable one");
        }
        this.monochrome = shape;
        push();
        return this;
    }

    /**
     * Sets the tooltip.
     *
     * @param tooltip the text, empty for none. A system may cut a long one, and some panels show
     *                none
     * @return this tray
     */
    public Tray setTooltip(String tooltip) {
        return setTooltip(I18nString.literal(Objects.requireNonNull(tooltip, "tooltip")));
    }

    /**
     * Sets the tooltip to a text that follows the language.
     *
     * @param tooltip the text
     * @return this tray
     */
    public Tray setTooltip(I18nString tooltip) {
        Ui.checkUiThread();
        this.tooltip = Objects.requireNonNull(tooltip, "tooltip");
        push();
        return this;
    }

    /**
     * Sets the menu. The tray reads it each time the system is about to open it, so later changes
     * to the same menu need no call here.
     *
     * @param menu the menu, or {@code null} for none
     * @return this tray
     * @throws IllegalArgumentException when a submenu of {@code menu} contains a menu it is in
     */
    public Tray setMenu(@Nullable Menu menu) {
        Ui.checkUiThread();
        if (menu != null && TrayMenu.containsItself(menu)) {
            throw new IllegalArgumentException("a tray's menu cannot contain itself");
        }
        this.menu = menu;
        return this;
    }

    /** @return the menu, or {@code null} */
    public @Nullable Menu menu() {
        return menu;
    }

    /**
     * Sets what the click that means "this application" does; see the class description for
     * which click that is. Setting or clearing it on an icon already shown may make the system
     * show the icon anew.
     *
     * @param listener what to run, or {@code null} for every click to open the menu
     * @return this tray
     */
    public Tray onActivate(@Nullable Runnable listener) {
        Ui.checkUiThread();
        this.activate = listener;
        push();
        return this;
    }

    /**
     * Sets whether the icon asks for attention. While it does, it is shown with a dot at its top
     * trailing corner, or as the icon of {@link #setAttentionIcon} when there is one, and a panel
     * that knows the state may bring the icon out from among its hidden ones.
     *
     * @param attention whether to ask
     * @return this tray
     */
    public Tray setAttention(boolean attention) {
        Ui.checkUiThread();
        this.attention = attention;
        push();
        return this;
    }

    /** @return whether the icon asks for attention */
    public boolean isAttention() {
        return attention;
    }

    /**
     * Sets the icon shown while the tray asks for attention, in place of the icon with a dot.
     *
     * @param icon the icon, shown as it is, or {@code null} for the dot
     * @return this tray
     */
    public Tray setAttentionIcon(@Nullable Icon icon) {
        Ui.checkUiThread();
        this.attentionIcon = icon;
        push();
        return this;
    }

    /**
     * Sets the direction the menu is laid out in, where the system takes one; left to right
     * until set.
     *
     * @param direction the direction
     * @return this tray
     */
    public Tray setLayoutDirection(LayoutDirection direction) {
        Ui.checkUiThread();
        this.direction = Objects.requireNonNull(direction, "direction");
        push();
        return this;
    }

    /**
     * @return whether the system took the icon: {@code false} on a desktop with no tray, before
     *         the tray has an icon, and once it is disposed. Not whether somebody sees it
     */
    public boolean isAvailable() {
        Ui.checkUiThread();
        return !disposed && shown != null && peer.isAvailable();
    }

    /**
     * Subscribes to {@link #isAvailable} changing, which it does when a desktop's tray appears
     * or goes while the application runs.
     *
     * @param listener what to run after a change
     * @return a handle that unsubscribes
     */
    public Subscription onAvailabilityChange(Runnable listener) {
        Ui.checkUiThread();
        Objects.requireNonNull(listener, "listener");
        availabilityListeners.add(listener);
        return () -> availabilityListeners.remove(listener);
    }

    /** Takes the icon away. A disposed tray ignores every call; its identifier is free again. */
    public void dispose() {
        Ui.checkUiThread();
        if (disposed) {
            return;
        }
        disposed = true;
        language.cancel();
        availabilityListeners.clear();
        peer.dispose();
        if (backend != null) {
            Set<String> used = IN_USE.get(backend);
            if (used != null) {
                used.remove(id);
            }
        }
    }

    /** Hands the peer what the tray shows now, when it shows anything and that changed. */
    private void push() {
        if (disposed || icon == null && monochrome == null) {
            return;
        }
        boolean rtl = direction.isRightToLeft();
        boolean ownAttention = attention && attentionIcon != null;
        limn.graphics.Color dot = attention && !ownAttention ? Theme.current().danger() : null;
        TrayPicture picture = ownAttention ? new TrayPicture(attentionIcon, null, rtl)
                : icon == null ? null : new TrayPicture(icon, dot, rtl);
        TrayPicture shape = ownAttention || monochrome == null ? null : new TrayPicture(monochrome, dot, rtl);
        TrayState state = new TrayState(title(), tooltip.get(), picture, shape, attention, activate != null, rtl);
        if (!state.equals(shown)) {
            shown = state;
            peer.update(state);
        }
    }

    /** What the peer asks and tells; every call arrives on the UI thread. */
    private final class Host implements TrayHost {

        @Override
        public List<MenuRow> menu() {
            Ui.checkUiThread();
            if (disposed) {
                return List.of();
            }
            reading = TrayMenu.read(menu);
            return reading.rows();
        }

        @Override
        public void choose(int id) {
            Ui.checkUiThread();
            if (!disposed) {
                reading.choose(id);
            }
        }

        @Override
        public void activate() {
            Ui.checkUiThread();
            Runnable listener = activate;
            if (!disposed && listener != null) {
                listener.run();
            }
        }

        @Override
        public void availabilityChanged() {
            Ui.checkUiThread();
            if (!disposed) {
                for (Runnable listener : List.copyOf(availabilityListeners)) {
                    listener.run();
                }
            }
        }
    }
}
