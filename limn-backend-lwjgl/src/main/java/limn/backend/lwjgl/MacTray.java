package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;
import limn.backend.lwjgl.transfer.Ffi;
import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayRow;
import limn.backend.spi.TrayState;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A tray icon on macOS: a status item in the menu bar, with a menu of the system's.
 *
 * <p>Everything here runs on the main thread, which is the UI thread and the only one AppKit's
 * views and menus may be used from. The menu is filled when the system is about to open it, from
 * the rows the toolkit reads then. While it is open the main thread is inside the system's own
 * tracking of it, as every application's is.
 *
 * <p><b>The click.</b> A status item with a menu opens it on any click, and that is what a tray
 * with no activation is. With one, the item has no menu of its own and an action instead: the
 * main button, released with no Control key, activates; any other way of pressing the item (the
 * other button, Control with the main one, the keyboard, a screen reader) opens the menu, so the
 * menu is never out of reach.
 *
 * <p><b>The picture.</b> A monochrome icon is given to the system as a template, which it
 * colours for the menu bar. With the attention dot it cannot be one, a template having no
 * colour: the shape is then drawn here, dark or light as the status item's own appearance is
 * when the picture is made.
 */
final class MacTray implements TrayPeer {

    private static final System.Logger LOG = System.getLogger(MacTray.class.getName());
    private static final String TARGET_CLASS = "LimnTrayTarget";
    private static final String CHOOSE = "limnTrayChoose:";
    private static final String CLICK = "limnTrayClick:";
    private static final String NEEDS_UPDATE = "menuNeedsUpdate:";
    private static final String OBSERVE = "observeValueForKeyPath:ofObject:change:context:";
    private static final String APPEARANCE = "effectiveAppearance";
    /** {@code NSVariableStatusItemLength}. */
    private static final double VARIABLE_LENGTH = -1.0;
    /** The side of a status item's picture in points, and the pixels it is drawn at. */
    private static final int POINTS = 18;
    private static final int PIXELS = 36;
    private static final long LEFT_MOUSE_UP = 2;
    private static final long LEFT_AND_RIGHT_UP = 1L << 2 | 1L << 4;
    private static final long CONTROL_KEY = 1L << 18;

    private static final Map<Long, MacTray> BY_TARGET = new ConcurrentHashMap<>();
    private static long targetClass;
    private static Callback choose;
    private static Callback click;
    private static Callback needsUpdate;
    private static Callback observed;
    private static FFICIF oneDouble;
    private static FFICIF twoDoubles;

    private final String id;
    private final TrayHost host;
    private final Executor ui;
    private long item;
    private long button;
    private long menu;
    private long target;
    private @Nullable TrayState shown;
    private boolean disposed;
    private boolean observing;
    /** Whether the picture now shown was coloured here, and for which appearance. */
    private boolean drawnByUs;
    private boolean drawnForDark;

    MacTray(String id, TrayHost host, Executor ui) {
        this.id = id;
        this.host = host;
        this.ui = ui;
    }

    /** @return whether AppKit can be reached at all; a tray is not made where it cannot */
    static boolean canShow() {
        return ObjC.isAvailable();
    }

    @Override
    public void update(TrayState state) {
        if (disposed) {
            return;
        }
        try {
            if (item == NULL) {
                create();
            }
            TrayState before = shown;
            shown = state;
            if (before == null || !picturesEqual(before, state)) {
                setPicture(state);
            }
            String tip = state.tooltip().isEmpty() ? state.title() : state.tooltip();
            ObjC.msgVoid(button, "setToolTip:", nsString(tip));
            ObjC.msgVoid(button, "setAccessibilityLabel:", nsString(state.title()));
            if (before == null || before.activates() != state.activates()) {
                wireClick(state.activates());
            }
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.WARNING, "the status item could not be shown", failed);
        }
    }

    private static boolean picturesEqual(TrayState a, TrayState b) {
        return java.util.Objects.equals(a.picture(), b.picture())
                && java.util.Objects.equals(a.monochrome(), b.monochrome());
    }

    @Override
    public boolean isAvailable() {
        return item != NULL && !disposed;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (observing && button != NULL) {
            ObjC.msgVoid(button, "removeObserver:forKeyPath:", target, nsString(APPEARANCE));
            observing = false;
        }
        if (item != NULL) {
            ObjC.msgVoid(ObjC.msg(ObjC.cls("NSStatusBar"), "systemStatusBar"), "removeStatusItem:", item);
            ObjC.msgVoid(item, "release");
            item = NULL;
        }
        if (menu != NULL) {
            ObjC.msgVoid(menu, "release");
            menu = NULL;
        }
        if (target != NULL) {
            BY_TARGET.remove(target);
            ObjC.msgVoid(target, "release");
            target = NULL;
        }
    }

    private void create() {
        ensureClass();
        long bar = ObjC.msg(ObjC.cls("NSStatusBar"), "systemStatusBar");
        item = ObjC.msg(sendDouble(bar, "statusItemWithLength:", VARIABLE_LENGTH), "retain");
        ObjC.msgVoid(item, "setAutosaveName:", nsString("limn." + id));
        button = ObjC.msg(item, "button");
        target = ObjC.msg(ObjC.msg(targetClass, "alloc"), "init");
        BY_TARGET.put(target, this);
        menu = ObjC.msg(ObjC.msg(ObjC.cls("NSMenu"), "alloc"), "init");
        ObjC.msgVoid(menu, "setAutoenablesItems:", 0);
        ObjC.msgVoid(menu, "setDelegate:", target);
        // The menu bar is light or dark as the picture behind it is, and says so late: not yet
        // when the item is made, and again whenever the desktop's picture changes.
        ObjC.msg(button, "addObserver:forKeyPath:options:context:", target, nsString(APPEARANCE), 0, NULL);
        observing = true;
        ui.execute(this::appearanceChanged);
        ui.execute(host::availabilityChanged);
    }

    /** A picture drawn here for the bar's appearance is drawn again when that changes. */
    private void appearanceChanged() {
        TrayState state = shown;
        if (disposed || state == null || button == NULL) {
            return;
        }
        boolean dark = barIsDark();
        if (dark != drawnForDark && drawnByUs) {
            setPicture(state);
        }
    }

    /** With no activation the item owns the menu; with one it has an action that tells the clicks apart. */
    private void wireClick(boolean activates) {
        if (activates) {
            ObjC.msgVoid(item, "setMenu:", NULL);
            ObjC.msgVoid(button, "setTarget:", target);
            ObjC.msgVoid(button, "setAction:", ObjC.sel(CLICK));
            ObjC.msg(button, "sendActionOn:", LEFT_AND_RIGHT_UP);
        } else {
            ObjC.msgVoid(button, "setAction:", NULL);
            ObjC.msgVoid(item, "setMenu:", menu);
        }
    }

    private void setPicture(TrayState state) {
        TrayPicture shape = state.monochrome();
        TrayPicture picture = state.picture();
        Image image;
        boolean template = false;
        boolean dark = barIsDark();
        drawnForDark = dark;
        drawnByUs = false;
        if (shape != null && shape.badge() == null) {
            image = shape.render(PIXELS, Color.rgb(0x000000), false);
            template = true;
        } else if (shape != null) {
            image = shape.render(PIXELS, dark ? Color.rgb(0xFFFFFF) : Color.rgb(0x000000), dark);
            drawnByUs = true;
        } else if (picture != null) {
            image = picture.render(PIXELS, null, dark);
            drawnByUs = true;
        } else {
            ObjC.msgVoid(button, "setImage:", NULL);
            return;
        }
        byte[] png = Images.encode(image, ImageFormat.PNG);
        long data;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bytes = MemoryUtil.nmemAlloc(png.length);
            try {
                for (int i = 0; i < png.length; i++) {
                    MemoryUtil.memPutByte(bytes + i, png[i]);
                }
                data = ObjC.msg(ObjC.cls("NSData"), "dataWithBytes:length:", bytes, png.length);
            } finally {
                MemoryUtil.nmemFree(bytes);
            }
        }
        long nsImage = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSImage"), "alloc"), "initWithData:", data), "autorelease");
        if (nsImage == NULL) {
            return;
        }
        sendTwoDoubles(nsImage, "setSize:", POINTS, POINTS);
        ObjC.msgVoid(nsImage, "setTemplate:", template ? 1 : 0);
        ObjC.msgVoid(button, "setImage:", nsImage);
    }

    /** Whether the menu bar under the item is dark now, which follows the picture behind it and not the theme. */
    private boolean barIsDark() {
        long appearance = ObjC.msg(button, "effectiveAppearance");
        long name = appearance == NULL ? NULL : ObjC.msg(appearance, "name");
        long utf8 = name == NULL ? NULL : ObjC.msg(name, "UTF8String");
        return utf8 != NULL && MemoryUtil.memUTF8(utf8).contains("Dark");
    }

    // ---- what the system calls ----------------------------------------------------------------

    /** The system is about to open the menu: its items become the rows read now. */
    private void fill() {
        TrayState state = shown;
        if (disposed || state == null) {
            return;
        }
        ObjC.msgVoid(menu, "removeAllItems");
        ObjC.msgVoid(menu, "setUserInterfaceLayoutDirection:", state.rightToLeft() ? 1 : 0);
        append(menu, host.menu());
    }

    private void append(long into, List<TrayRow> rows) {
        for (TrayRow row : rows) {
            if (row.kind() == TrayRow.Kind.SEPARATOR) {
                ObjC.msgVoid(into, "addItem:", ObjC.msg(ObjC.cls("NSMenuItem"), "separatorItem"));
                continue;
            }
            long entry = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSMenuItem"), "alloc"),
                    "initWithTitle:action:keyEquivalent:", nsString(row.label()), ObjC.sel(CHOOSE), nsString("")),
                    "autorelease");
            ObjC.msgVoid(entry, "setTarget:", target);
            ObjC.msgVoid(entry, "setTag:", row.id());
            ObjC.msgVoid(entry, "setEnabled:", row.enabled() ? 1 : 0);
            ObjC.msgVoid(entry, "setState:", row.checked() ? 1 : 0);
            if (row.kind() == TrayRow.Kind.SUBMENU && !row.children().isEmpty()) {
                long sub = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSMenu"), "alloc"), "init"), "autorelease");
                ObjC.msgVoid(sub, "setAutoenablesItems:", 0);
                append(sub, row.children());
                ObjC.msgVoid(entry, "setSubmenu:", sub);
            }
            ObjC.msgVoid(into, "addItem:", entry);
        }
    }

    /** A menu's item was chosen; the toolkit hears of it after the system is done with the menu. */
    private void chosen(long sender) {
        int row = (int) ObjC.msg(sender, "tag");
        ui.execute(() -> {
            if (!disposed) {
                host.choose(row);
            }
        });
    }

    /** The item was pressed while it activates: the plain main button activates, anything else opens the menu. */
    private void pressed() {
        TrayState state = shown;
        if (disposed || state == null) {
            return;
        }
        long event = ObjC.msg(ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication"), "currentEvent");
        boolean plainMainButton = event != NULL && ObjC.msg(event, "type") == LEFT_MOUSE_UP
                && (ObjC.msg(event, "modifierFlags") & CONTROL_KEY) == 0;
        if (plainMainButton) {
            ui.execute(() -> {
                if (!disposed) {
                    host.activate();
                }
            });
            return;
        }
        // The item shows a menu it owns: given one for the length of this click, and none after.
        ObjC.msgVoid(item, "setMenu:", menu);
        ObjC.msgVoid(button, "performClick:", NULL);
        ObjC.msgVoid(item, "setMenu:", NULL);
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
            MacTray tray = BY_TARGET.get(args[0]);
            if (tray != null) {
                tray.chosen(args[2]);
            }
            return 0;
        });
        click = Ffi.callback("ppp>v", args -> {
            MacTray tray = BY_TARGET.get(args[0]);
            if (tray != null) {
                tray.pressed();
            }
            return 0;
        });
        needsUpdate = Ffi.callback("ppp>v", args -> {
            MacTray tray = BY_TARGET.get(args[0]);
            if (tray != null) {
                tray.fill();
            }
            return 0;
        });
        ObjCRuntime.class_addMethod(made, ObjC.sel(CHOOSE), choose.address(), "v@:@");
        ObjCRuntime.class_addMethod(made, ObjC.sel(CLICK), click.address(), "v@:@");
        ObjCRuntime.class_addMethod(made, ObjC.sel(NEEDS_UPDATE), needsUpdate.address(), "v@:@");
        observed = Ffi.callback("pppppp>v", args -> {
            MacTray tray = BY_TARGET.get(args[0]);
            if (tray != null) {
                tray.ui.execute(tray::appearanceChanged);   // after the change, not inside its telling
            }
            return 0;
        });
        ObjCRuntime.class_addMethod(made, ObjC.sel(OBSERVE), observed.address(), "v@:@@@^v");
        ObjCRuntime.objc_registerClassPair(made);
        oneDouble = APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                LibFFI.ffi_type_double);
        twoDoubles = APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                LibFFI.ffi_type_double, LibFFI.ffi_type_double);
        targetClass = made;
    }

    // ---- calls ----------------------------------------------------------------------------------

    private static long nsString(String text) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:", MemoryUtil.memAddress(stack.UTF8(text)));
        }
    }

    /** A message whose one argument is a float of the system's width, answered with an object. */
    private static long sendDouble(long self, String selector, double value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long values = stack.ncalloc(8, 3, 8);
            long slots = stack.ncalloc(8, 3, 8);
            MemoryUtil.memPutAddress(values, self);
            MemoryUtil.memPutAddress(values + 8, ObjC.sel(selector));
            MemoryUtil.memPutDouble(values + 16, value);
            for (int i = 0; i < 3; i++) {
                MemoryUtil.memPutAddress(slots + 8L * i, values + 8L * i);
            }
            long result = stack.ncalloc(8, 1, 8);
            LibFFI.nffi_call(oneDouble.address(), ObjC.msgSend(), result, slots);
            return MemoryUtil.memGetAddress(result);
        }
    }

    /** A message whose one argument is a size: two floats, passed as the two they are. */
    private static void sendTwoDoubles(long self, String selector, double a, double b) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long values = stack.ncalloc(8, 4, 8);
            long slots = stack.ncalloc(8, 4, 8);
            MemoryUtil.memPutAddress(values, self);
            MemoryUtil.memPutAddress(values + 8, ObjC.sel(selector));
            MemoryUtil.memPutDouble(values + 16, a);
            MemoryUtil.memPutDouble(values + 24, b);
            for (int i = 0; i < 4; i++) {
                MemoryUtil.memPutAddress(slots + 8L * i, values + 8L * i);
            }
            long result = stack.ncalloc(8, 1, 8);
            LibFFI.nffi_call(twoDoubles.address(), ObjC.msgSend(), result, slots);
        }
    }
}
