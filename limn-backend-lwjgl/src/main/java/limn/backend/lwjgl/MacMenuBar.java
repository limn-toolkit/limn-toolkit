package limn.backend.lwjgl;

import limn.backend.NativeWindow;
import limn.backend.lwjgl.internal.ObjC;
import limn.backend.lwjgl.transfer.Ffi;
import limn.backend.spi.MenuBarHost;
import limn.backend.spi.MenuBarReading;
import limn.backend.spi.MenuRow;
import limn.backend.spi.SystemMenuBar;
import limn.backend.spi.SystemMenuLabels;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.WindowInput;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.input.Keys;
import limn.scene.EditCommand;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The application's menu bar on macOS, the one the system keeps at the top of the screen: an
 * {@code NSMenu} that is the application's main menu, built from the bar of the window the user
 * is working in.
 *
 * <p>The bar has three parts. The <b>application's menu</b> is the toolkit's: Services, the
 * three of hiding and Quit always, About and Settings when an item of the application's has the
 * role. The <b>menus of the window's bar</b> follow, the one named as the Window menu and the
 * one named as the Help menu handed to the system as such, which adds its own items to them.
 * A <b>Window menu</b> is added where the application named none.
 *
 * <p>The system asks every menu to bring itself up to date when one opens and before it matches
 * an accelerator, and each is filled then from one reading of the application's bar; the
 * reading is kept until the event loop turns again, which it does not while a menu is open.
 * The top level, which the system shows without asking, is rebuilt when the loop turns and what
 * it should show has changed. A choice is told to the toolkit after the system is done with it.
 *
 * <p>Everything here runs on the UI thread, which on macOS is the main thread.
 */
final class MacMenuBar implements SystemMenuBar {

    private static final System.Logger LOG = System.getLogger(MacMenuBar.class.getName());

    private static final String TARGET_CLASS = "LimnMenuBarTarget";
    private static final String CHOOSE = "limnChooseItem:";
    private static final String NEEDS_UPDATE = "menuNeedsUpdate:";
    private static final String VALIDATE = "validateMenuItem:";

    private static final long SHIFT = 1L << 17;
    private static final long OPTION = 1L << 19;
    private static final long COMMAND = 1L << 20;
    /** {@code NSEventTypeKeyDown}. */
    private static final long KEY_DOWN = 10;

    private static long targetClass;
    private static Callback choose;
    private static Callback needsUpdate;
    private static Callback validate;
    private static final Map<Long, MacMenuBar> BY_TARGET = new HashMap<>();

    /** What a native item stands for: a row of a bar's reading, or an editing command. */
    private record Action(@Nullable MenuBarHost host, int row, @Nullable EditCommand edit, boolean enabled) {
    }

    /** A menu of the bar as it is installed now. */
    private record Top(long item, long menu, String title, MenuBarReading.Kind kind) {
    }

    private final LwjglBackend backend;
    private final Executor ui;
    /** Each window's bar, in the order they asked. */
    private final Map<LwjglWindow, MenuBarHost> bars = new LinkedHashMap<>();
    /** Whether each window's bar was held when its host was last told. */
    private final Map<LwjglWindow, Boolean> held = new IdentityHashMap<>();
    private final Map<Long, Action> actions = new HashMap<>();
    /** The tags each native menu's items carry, to forget them when the menu is filled again. */
    private final Map<Long, List<Long>> tagsOf = new HashMap<>();
    /** The items this bar put in each native menu, to take out those and no other when it is filled again. */
    private final Map<Long, List<Long>> itemsOf = new HashMap<>();
    /** Pictures of items' icons, kept between readings: a bar is read at every chord. */
    private final Map<Icon, Long> pictures = new WeakHashMap<>();
    private final List<Top> tops = new ArrayList<>();

    private long target;
    private long mainMenu;
    private long appMenu;
    private long servicesMenu;
    /** The Window menu given to a bar that named none; {@code 0} while the bar has its own. */
    private long givenWindowMenu;
    private long nextTag = 1;

    /** The bar shown, or {@code null} for the minimal one. */
    private @Nullable MenuBarHost shown;
    /** The bar that last declared About or Settings, which are the application's and not a window's. */
    private @Nullable MenuBarHost roles;
    private boolean dirty = true;
    private boolean installed;
    private boolean disposed;

    private MenuBarReading reading = MenuBarReading.EMPTY;
    private MenuBarReading rolesReading = MenuBarReading.EMPTY;
    private @Nullable LwjglWindow keyAtReading;
    private boolean fresh;
    private boolean saidChord;
    private boolean systemPanelIsKey;
    /** {@code -Dlimn.macos.menuBar.trace=true} prints each change of what the bar follows. */
    private static final boolean TRACE = Boolean.getBoolean("limn.macos.menuBar.trace");
    private String lastTrace = "";
    /** A look for the key window is waiting, and how many have been made with none found. */
    private boolean recheck;
    private int rechecks;
    private static final int MAX_RECHECKS = 12;
    private static final long RECHECK_MILLIS = 250;

    MacMenuBar(LwjglBackend backend, Executor ui) {
        this.backend = backend;
        this.ui = ui;
        ensureClass();
        target = ObjC.msg(ObjC.msg(targetClass, "alloc"), "init");
        BY_TARGET.put(target, this);
        long pool = ObjC.msg(ObjC.msg(ObjC.cls("NSAutoreleasePool"), "alloc"), "init");
        try {
            mainMenu = kept(newMenu(""));
            appMenu = kept(newMenu(""));
            ObjC.msgVoid(appMenu, "setDelegate:", target);
            servicesMenu = kept(newMenu(""));
        } finally {
            ObjC.msgVoid(pool, "drain");
        }
    }

    // ------------------------------------------------------------------ the toolkit's side

    @Override
    public boolean attach(NativeWindow window, MenuBarHost host) {
        if (disposed || !(window instanceof LwjglWindow lwjgl)) {
            return false;
        }
        MenuBarHost before = bars.get(lwjgl);
        if (before == host) {
            return true;
        }
        if (before != null && !stale(lwjgl, before)) {
            return false; // the window's scene already has a bar here
        }
        bars.put(lwjgl, host);
        if (before != null) {
            // The bar of a scene the window has left: it is nobody's now, and is told so.
            if (roles == before) {
                roles = null;
            }
            ui.execute(before::heldChanged);
        }
        held.put(lwjgl, !lwjgl.coversSystemBar());
        dirty = true;
        sync();
        return true;
    }

    @Override
    public void detach(NativeWindow window, MenuBarHost host) {
        if (window instanceof LwjglWindow lwjgl && bars.get(lwjgl) == host) {
            bars.remove(lwjgl);
            held.remove(lwjgl);
            if (roles == host) {
                roles = null;
            }
            dirty = true;
            ui.execute(this::sync); // not from inside the widget's own detaching
        }
    }

    @Override
    public boolean holds(NativeWindow window, MenuBarHost host) {
        return !disposed && window instanceof LwjglWindow lwjgl && bars.get(lwjgl) == host
                && !lwjgl.coversSystemBar();
    }

    /** Whether a bar is of a scene its window has since left for another. */
    private static boolean stale(LwjglWindow window, MenuBarHost host) {
        return host.input() != window.input();
    }

    @Override
    public void changed(MenuBarHost host) {
        if (host == shown || host == roles) {
            dirty = true;
            ui.execute(this::sync);
        }
    }

    @Override
    public boolean runs(String key, int modifiers) {
        return systemRuns(key, modifiers);
    }

    /**
     * Which accelerators the system runs from its bar, as measured: any with Command or Control,
     * and a key that types no character (a function key, Delete, an arrow) even alone. A key
     * that types a character, alone or with Option or Shift, is given to the focused view, and
     * the toolkit runs it.
     */
    static boolean systemRuns(String key, int modifiers) {
        if ((modifiers & (Keys.MOD_SUPER | Keys.MOD_CONTROL)) != 0) {
            return true;
        }
        String equivalent = MacTray.keyEquivalent(key);
        return equivalent.length() == 1 && equivalent.charAt(0) >= '\uF700' && equivalent.charAt(0) <= '\uF8FF';
    }

    // ------------------------------------------------------------------ which bar

    /**
     * Brings the bar to what the window the user is working in asks for. Called as the event
     * loop turns, which is where a change of the window with the keyboard, of a dialog or of a
     * fullscreen is seen, and never while a menu is open, the loop standing still then.
     */
    void sync() {
        if (disposed) {
            return;
        }
        for (Map.Entry<LwjglWindow, MenuBarHost> bar : List.copyOf(bars.entrySet())) {
            boolean now = !bar.getKey().coversSystemBar();
            if (!Boolean.valueOf(now).equals(held.put(bar.getKey(), now))) {
                bar.getValue().heldChanged();
                dirty = true;
            }
        }
        LwjglWindow key = keyWindow();
        if (key == null && !recheck && rechecks < MAX_RECHECKS && ObjC.msg(application(), "isActive") != 0) {
            // Between two key windows (a sheet going, a window closing) the system has none, and
            // tells nobody when it has one again that is not a window made here.
            recheck = true;
            rechecks++;
            limn.concurrent.Ui.postDelayed(() -> {
                recheck = false;
                sync();
            }, RECHECK_MILLIS);
        } else if (key != null) {
            rechecks = 0;
        }
        // A panel of the system's has the keyboard: its text fields take cut, copy and paste from
        // the bar and from nowhere else, so the minimal bar is given an Edit menu of the system's
        // own commands for as long as it is up.
        boolean panel = key == null && ObjC.msg(application(), "keyWindow") != NULL;
        if (panel != systemPanelIsKey) {
            systemPanelIsKey = panel;
            dirty = true;
        }
        MenuBarHost wanted = barFor(key);
        if (TRACE) {
            String line = "key=" + (key == null ? "none(" + ObjC.msg(application(), "keyWindow") + ")" : key.title())
                    + " active=" + (ObjC.msg(application(), "isActive") != 0) + " bar=" + (wanted == null ? "minimal" : "a window's")
                    + " rechecks=" + rechecks;
            if (!line.equals(lastTrace)) {
                lastTrace = line;
                System.out.println("MENUBAR-TRACE " + line);
            }
        }
        if (wanted != shown || dirty || !installed) {
            shown = wanted;
            dirty = false;
            // Reached from the toolkit's own loop as well as from AppKit's, which alone has a pool.
            long pool = ObjC.msg(ObjC.msg(ObjC.cls("NSAutoreleasePool"), "alloc"), "init");
            try {
                install();
            } finally {
                ObjC.msgVoid(pool, "drain");
            }
        }
    }

    /** The toolkit's window that is the application's key window, or {@code null}: a sheet of the system's is none of ours. */
    private @Nullable LwjglWindow keyWindow() {
        long key = ObjC.msg(application(), "keyWindow");
        if (key == NULL) {
            return null;
        }
        for (LwjglWindow window : backend.openWindows()) {
            if (window.cocoaWindow() == key) {
                return window;
            }
        }
        return null;
    }

    /**
     * The rule of which bar a window shows, on the toolkit's own state: a popup shows its
     * owner's; a window a dialog keeps out of reach shows the minimal bar, as does a dialog; a
     * window with a bar shows it; an owned window with none shows its owner's.
     *
     * @return the bar, or {@code null} for the minimal one
     */
    private @Nullable MenuBarHost barFor(@Nullable LwjglWindow key) {
        LwjglWindow window = key;
        for (int depth = 0; window != null && depth < 16; depth++) {
            if (!window.isPopupWindow()) {
                if (backend.aDialogKeepsOutOfReach(window)) {
                    return null;
                }
                MenuBarHost bar = bars.get(window);
                if (bar != null && !stale(window, bar)) {
                    return window.coversSystemBar() ? null : bar;
                }
            }
            window = backend.ownerOf(window);
        }
        return null;
    }

    // ------------------------------------------------------------------ building

    /** One reading of the bar shown for as long as the event loop does not turn. */
    private MenuBarReading read() {
        if (!fresh) {
            fresh = true;
            keyAtReading = keyWindow();
            MenuBarHost bar = shown;
            reading = bar == null ? MenuBarReading.EMPTY : bar.read();
            if (reading.about() != null || reading.settings() != null) {
                roles = bar;
                rolesReading = reading;
            } else if (roles != null && roles != bar) {
                rolesReading = roles.read();
            } else {
                rolesReading = MenuBarReading.EMPTY;
            }
            ui.execute(() -> fresh = false);
        }
        return reading;
    }

    /** Rebuilds the top level, which the system shows without asking, and fills every menu under it. */
    private void install() {
        fresh = false;
        MenuBarReading now = read();
        SystemMenuLabels labels = labels();
        long app = application();

        ObjC.msgVoid(mainMenu, "removeAllItems");
        for (Top top : tops) {
            forget(top.menu);
            itemsOf.remove(top.menu);
        }
        tops.clear();
        givenWindowMenu = NULL;

        long appItem = newItem("", null, "", 0);
        ObjC.msgVoid(appItem, "setSubmenu:", appMenu);
        ObjC.msgVoid(mainMenu, "addItem:", appItem);
        fillApplicationMenu(labels);

        if (shown == null && systemPanelIsKey) {
            long edit = newMenu(labels.edit());
            ObjC.msgVoid(edit, "addItem:", newItem(labels.undo(), "undo:", "z", COMMAND));
            ObjC.msgVoid(edit, "addItem:", newItem(labels.redo(), "redo:", "z", COMMAND | SHIFT));
            separator(edit);
            ObjC.msgVoid(edit, "addItem:", newItem(labels.cut(), "cut:", "x", COMMAND));
            ObjC.msgVoid(edit, "addItem:", newItem(labels.copy(), "copy:", "c", COMMAND));
            ObjC.msgVoid(edit, "addItem:", newItem(labels.paste(), "paste:", "v", COMMAND));
            ObjC.msgVoid(edit, "addItem:", newItem(labels.selectAll(), "selectAll:", "a", COMMAND));
            long item = newItem(labels.edit(), null, "", 0);
            ObjC.msgVoid(item, "setSubmenu:", edit);
            ObjC.msgVoid(mainMenu, "addItem:", item);
        }
        long windowsMenu = NULL;
        long helpMenu = NULL;
        int helpAt = -1;
        for (MenuBarReading.Top source : now.menus()) {
            long menu = newMenu(source.title());
            ObjC.msgVoid(menu, "setAutoenablesItems:", 0);
            ObjC.msgVoid(menu, "setDelegate:", target);
            long item = newItem(source.title(), null, "", 0);
            ObjC.msgVoid(item, "setSubmenu:", menu);
            if (source.kind() == MenuBarReading.Kind.HELP && helpMenu == NULL) {
                helpMenu = menu;
                helpAt = (int) ObjC.msg(mainMenu, "numberOfItems");
            } else if (source.kind() == MenuBarReading.Kind.WINDOW && windowsMenu == NULL) {
                windowsMenu = menu;
            }
            ObjC.msgVoid(mainMenu, "addItem:", item);
            tops.add(new Top(item, menu, source.title(), source.kind()));
        }
        if (windowsMenu == NULL) {
            // Nobody loses the Window menu the windowing library's own bar had, nor Cmd-M with it.
            windowsMenu = newMenu(labels.window());
            givenWindowMenu = windowsMenu;
            ObjC.msgVoid(windowsMenu, "addItem:", newItem(labels.minimize(), "performMiniaturize:", "m", COMMAND));
            ObjC.msgVoid(windowsMenu, "addItem:", newItem(labels.zoom(), "performZoom:", "", 0));
            long item = newItem(labels.window(), null, "", 0);
            ObjC.msgVoid(item, "setSubmenu:", windowsMenu);
            if (helpAt >= 0) {
                ObjC.msgVoid(mainMenu, "insertItem:atIndex:", item, helpAt);
            } else {
                ObjC.msgVoid(mainMenu, "addItem:", item);
            }
        }
        // Filled after the given Window menu exists: whether Cmd-M is the toolkit's depends on it.
        for (int i = 0; i < tops.size(); i++) {
            fill(tops.get(i).menu, now.menus().get(i).rows(), now.rightToLeft());
        }
        ObjC.msgVoid(mainMenu, "setUserInterfaceLayoutDirection:", now.rightToLeft() ? 1 : 0);
        if (!installed) {
            ObjC.msgVoid(app, "setMainMenu:", mainMenu);
            ObjC.msgVoid(app, "setServicesMenu:", servicesMenu);
            installed = true;
        }
        ObjC.msgVoid(app, "setWindowsMenu:", windowsMenu);
        ObjC.msgVoid(app, "setHelpMenu:", helpMenu);
    }

    private void fillApplicationMenu(SystemMenuLabels labels) {
        forget(appMenu);
        ObjC.msgVoid(appMenu, "removeAllItems");
        List<Long> tags = new ArrayList<>();
        MenuRow about = rolesReading.about();
        MenuRow settings = rolesReading.settings();
        if (about != null) {
            ObjC.msgVoid(appMenu, "addItem:", ours(labels.about(), "", 0,
                    new Action(roles, about.id(), null, about.enabled()), tags));
            separator(appMenu);
        }
        if (settings != null) {
            ObjC.msgVoid(appMenu, "addItem:", ours(labels.settings(), ",", COMMAND,
                    new Action(roles, settings.id(), null, settings.enabled()), tags));
            separator(appMenu);
        }
        long services = newItem(labels.services(), null, "", 0);
        ObjC.msgVoid(services, "setSubmenu:", servicesMenu);
        ObjC.msgVoid(appMenu, "addItem:", services);
        separator(appMenu);
        ObjC.msgVoid(appMenu, "addItem:", newItem(labels.hide(), "hide:", "h", COMMAND));
        ObjC.msgVoid(appMenu, "addItem:", newItem(labels.hideOthers(), "hideOtherApplications:", "h", COMMAND | OPTION));
        ObjC.msgVoid(appMenu, "addItem:", newItem(labels.showAll(), "unhideAllApplications:", "", 0));
        separator(appMenu);
        // The application's own terminate:, which the backend already turns into the one
        // question every Quit of the system asks.
        ObjC.msgVoid(appMenu, "addItem:", newItem(labels.quit(), "terminate:", "q", COMMAND));
        tagsOf.put(appMenu, tags);
    }

    /**
     * A menu of the bar from its rows. Only the items put here before are taken out, and the new
     * ones go in at the top: what the system added to the menu, to the Window menu above all,
     * stays where the system put it.
     */
    private void fill(long menu, List<MenuRow> rows, boolean rightToLeft) {
        forget(menu);
        List<Long> before = itemsOf.remove(menu);
        if (before != null) {
            for (long item : before) {
                if (ObjC.msg(menu, "indexOfItem:", item) >= 0) {
                    ObjC.msgVoid(menu, "removeItem:", item);
                }
            }
        }
        ObjC.msgVoid(menu, "setUserInterfaceLayoutDirection:", rightToLeft ? 1 : 0);
        List<Long> tags = new ArrayList<>();
        List<Long> items = build(rows, rightToLeft, tags);
        for (int i = 0; i < items.size(); i++) {
            ObjC.msgVoid(menu, "insertItem:atIndex:", items.get(i), i);
        }
        tagsOf.put(menu, tags);
        itemsOf.put(menu, items);
    }

    private List<Long> build(List<MenuRow> rows, boolean rightToLeft, List<Long> tags) {
        List<Long> out = new ArrayList<>(rows.size());
        for (MenuRow row : rows) {
            if (row.kind() == MenuRow.Kind.SEPARATOR) {
                out.add(ObjC.msg(ObjC.cls("NSMenuItem"), "separatorItem"));
                continue;
            }
            String key = MacTray.keyEquivalent(row.shortcutKey());
            long mask = MacTray.modifierMask(row.shortcutModifiers());
            if (!key.isEmpty() && theSystemsOwn(key, mask)) {
                key = "";
                if (!saidChord) {
                    saidChord = true;
                    LOG.log(Level.INFO, "an item of the menu bar is on a chord the system keeps for the "
                            + "application's menu or the Window menu, and is shown without it: " + row.label());
                }
            }
            boolean enabled = row.enabled();
            EditCommand edit = row.edit();
            if (edit != null && enabled) {
                WindowInput input = keyAtReading == null ? null : keyAtReading.input();
                enabled = input != null && input.canEditFocused(edit);
            }
            long entry = ours(row.label(), key, mask, new Action(shown, row.id(), edit, enabled), tags);
            ObjC.msgVoid(entry, "setEnabled:", enabled ? 1 : 0);
            ObjC.msgVoid(entry, "setState:", row.checked() ? 1 : 0);
            Icon icon = row.icon();
            if (icon != null) {
                long image = picture(icon);
                if (image != NULL) {
                    ObjC.msgVoid(entry, "setImage:", image);
                }
            }
            if (row.kind() == MenuRow.Kind.SUBMENU && !row.children().isEmpty()) {
                long sub = newMenu(row.label());
                ObjC.msgVoid(sub, "setAutoenablesItems:", 0);
                ObjC.msgVoid(sub, "setUserInterfaceLayoutDirection:", rightToLeft ? 1 : 0);
                for (long child : build(row.children(), rightToLeft, tags)) {
                    ObjC.msgVoid(sub, "addItem:", child);
                }
                ObjC.msgVoid(entry, "setSubmenu:", sub);
            }
            out.add(entry);
        }
        return out;
    }

    /** The chords of the items the toolkit puts in the bar itself, which an item of the application's does not take. */
    private boolean theSystemsOwn(String key, long mask) {
        return mask == COMMAND && (key.equals(",") || key.equals("h") || key.equals("q")
                || key.equals("m") && givenWindowMenu != NULL)
                || mask == (COMMAND | OPTION) && key.equals("h");
    }

    private long picture(Icon icon) {
        Long kept = pictures.get(icon);
        if (kept != null) {
            return kept;
        }
        long image = MacTray.nsImage(new TrayPicture(icon, null, false)
                .render(MacTray.MENU_PIXELS, Color.rgb(0x000000), false), MacTray.MENU_POINTS, icon.tintable());
        if (image != NULL) {
            ObjC.msg(image, "retain"); // for as long as the icon is somebody's
            pictures.put(icon, image);
        }
        return image;
    }

    /** An item whose choice comes back here. */
    private long ours(String title, String key, long mask, Action action, List<Long> tags) {
        long entry = newItem(title, CHOOSE, key, mask);
        ObjC.msgVoid(entry, "setTarget:", target);
        long tag = nextTag++;
        ObjC.msgVoid(entry, "setTag:", tag);
        actions.put(tag, action);
        tags.add(tag);
        return entry;
    }

    private void forget(long menu) {
        List<Long> tags = tagsOf.remove(menu);
        if (tags != null) {
            tags.forEach(actions::remove);
        }
    }

    /**
     * A menu that whoever it is given to keeps: the item it is the submenu of, or the three fields
     * of this bar, which {@link #kept} holds on to.
     */
    private static long newMenu(String title) {
        return ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSMenu"), "alloc"), "initWithTitle:", MacTray.nsString(title)),
                "autorelease");
    }

    private static long kept(long object) {
        return ObjC.msg(object, "retain");
    }

    private static long newItem(String title, @Nullable String action, String key, long mask) {
        long entry = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSMenuItem"), "alloc"),
                "initWithTitle:action:keyEquivalent:", MacTray.nsString(title),
                action == null ? NULL : ObjC.sel(action), MacTray.nsString(key)), "autorelease");
        if (!key.isEmpty()) {
            ObjC.msgVoid(entry, "setKeyEquivalentModifierMask:", mask);
        }
        return entry;
    }

    private static void separator(long menu) {
        ObjC.msgVoid(menu, "addItem:", ObjC.msg(ObjC.cls("NSMenuItem"), "separatorItem"));
    }

    private SystemMenuLabels labels() {
        return SystemMenuLabels.current(backend.applicationNameForTheSystem());
    }

    private static long application() {
        return ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
    }

    // ------------------------------------------------------------------ what the system asks and tells

    /** The system asks a menu to bring itself up to date: one is opening, or a chord is being matched. */
    private void update(long menu) {
        if (disposed) {
            return;
        }
        MenuBarReading now = read();
        if (menu == appMenu) {
            fillApplicationMenu(labels());
            return;
        }
        for (int i = 0; i < tops.size(); i++) {
            Top top = tops.get(i);
            if (top.menu != menu) {
                continue;
            }
            if (i < now.menus().size() && now.menus().get(i).title().equals(top.title)
                    && now.menus().get(i).kind() == top.kind && now.menus().size() == tops.size()) {
                fill(menu, now.menus().get(i).rows(), now.rightToLeft());
            } else {
                // The bar's own menus changed under the system: shown as it was for this menu, and
                // rebuilt when the loop turns.
                dirty = true;
                ui.execute(this::sync);
            }
            return;
        }
    }

    /** An item of ours was chosen, by the pointer or by its chord. */
    private void chosen(long sender) {
        Action action = actions.get(ObjC.msg(sender, "tag"));
        if (disposed || action == null || !action.enabled) {
            return;
        }
        long event = ObjC.msg(application(), "currentEvent");
        boolean repeat = event != NULL && ObjC.msg(event, "type") == KEY_DOWN && ObjC.msg(event, "isARepeat") != 0;
        if (repeat && action.edit == null) {
            return; // a held chord runs an editing command again and nothing else, as in the window
        }
        LwjglWindow key = keyWindow();
        ui.execute(() -> {
            if (disposed) {
                return;
            }
            if (action.edit != null) {
                WindowInput input = key == null ? null : key.input();
                if (input != null) {
                    input.editFocused(action.edit);
                    key.requestFrame();
                }
            } else if (action.host != null && (bars.containsValue(action.host))) {
                action.host.choose(action.row);
            }
        });
    }

    private boolean valid(long item) {
        Action action = actions.get(ObjC.msg(item, "tag"));
        return action != null && action.enabled;
    }

    /** Takes the bar away as the backend closes: the application's menu alone stays, with its Quit. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        bars.clear();
        held.clear();
        actions.clear();
        tagsOf.clear();
        itemsOf.clear();
        BY_TARGET.remove(target);
    }

    private static synchronized void ensureClass() {
        if (targetClass != NULL) {
            return;
        }
        long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), TARGET_CLASS, 0);
        if (made == NULL) {
            throw new IllegalStateException("could not make " + TARGET_CLASS);
        }
        choose = Ffi.callback("ppp>v", args -> {
            MacMenuBar bar = BY_TARGET.get(args[0]);
            if (bar != null) {
                bar.chosen(args[2]);
            }
            return 0;
        });
        needsUpdate = Ffi.callback("ppp>v", args -> {
            MacMenuBar bar = BY_TARGET.get(args[0]);
            if (bar != null) {
                bar.update(args[2]);
            }
            return 0;
        });
        validate = Ffi.callback("ppp>i", args -> {
            MacMenuBar bar = BY_TARGET.get(args[0]);
            return bar != null && bar.valid(args[2]) ? 1 : 0;
        });
        ObjCRuntime.class_addMethod(made, ObjC.sel(CHOOSE), choose.address(), "v@:@");
        ObjCRuntime.class_addMethod(made, ObjC.sel(NEEDS_UPDATE), needsUpdate.address(), "v@:@");
        ObjCRuntime.class_addMethod(made, ObjC.sel(VALIDATE), validate.address(), "c@:@");
        ObjCRuntime.objc_registerClassPair(made);
        targetClass = made;
    }
}
