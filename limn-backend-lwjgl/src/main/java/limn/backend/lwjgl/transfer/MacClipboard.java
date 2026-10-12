package limn.backend.lwjgl.transfer;

import limn.backend.lwjgl.internal.ObjC;
import limn.transfer.TransferGoneException;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The macOS clipboard: the general {@code NSPasteboard}, on the main thread, which is the UI
 * thread.
 *
 * <p>What this process offers is promised: a pasteboard item names its types and a data provider,
 * an object of a class made here, which is sent {@code pasteboard:item:provideDataForType:} when
 * a program pastes. Files are the exception, written at once as one URL an item, which is what
 * Finder and the file panels read. What is still promised as this closes is provided then, so
 * that a copy outlives the application, as on Windows.
 *
 * <p>An item takes only uniform type identifiers, which an application's media type with its
 * slash is not. Such a name is written under the dynamic identifier the system derives for a
 * pasteboard type of that name (conforming to {@code public.data}), and is still found by the
 * name, by this toolkit and by any program that asks the pasteboard for it.
 *
 * <p>The pasteboard announces no change. Its change count is compared when the application
 * becomes active, which is when another program's copy can first matter here, and each time the
 * types are asked for.
 */
public final class MacClipboard implements NativeClipboard {

    private static final String UTI_TEXT = "public.utf8-plain-text";
    private static final String UTI_HTML = "public.html";
    private static final String UTI_PNG = "public.png";
    private static final String UTI_TIFF = "public.tiff";
    private static final String UTI_FILE_URL = "public.file-url";
    private static final String CLASS_NAME = "LimnPasteboardOwner";
    /** {@code NSBitmapImageFileTypePNG}. */
    private static final long PNG_FILE_TYPE = 4;

    private static MacClipboard open;
    private static final java.util.Map<Long, java.util.function.BiConsumer<Long, String>> OTHER_PROVIDERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> DYNAMIC = new java.util.concurrent.ConcurrentHashMap<>();
    private static long preferredIdentifier;
    private static long ownerClass;
    private static Callback provide;
    private static Callback activated;

    private final long pasteboard;
    private final long owner;
    private List<String> ownTypes = List.of();
    private Function<String, byte[]> ownBytes = type -> null;
    private long promisedItem;
    private final java.util.Set<String> provided = new java.util.HashSet<>();
    private long ownCount = -1;
    private long seenCount = -1;
    private List<String> offered = List.of();
    private Runnable changeListener = () -> { };
    private boolean closed;

    /**
     * Main thread.
     *
     * @throws RuntimeException when the Objective-C runtime will not make the owner's class; the
     *                          caller then keeps GLFW's text clipboard
     */
    public MacClipboard() {
        pasteboard = ObjC.msg(ObjC.cls("NSPasteboard"), "generalPasteboard");
        if (ownerClass == NULL) {
            long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), CLASS_NAME, 0);
            if (made == NULL) {
                throw new IllegalStateException("could not make " + CLASS_NAME);
            }
            provide = Ffi.callback("ppppp>v", args -> {
                // One class provides for the clipboard and for a drag: the object asked says which.
                java.util.function.BiConsumer<Long, String> other = OTHER_PROVIDERS.get(args[0]);
                MacClipboard clipboard = open;
                if (other != null) {
                    other.accept(args[3], string(args[4]));
                } else if (clipboard != null) {
                    clipboard.provide(args[3], string(args[4]));
                }
                return 0;
            });
            activated = Ffi.callback("ppp>v", args -> {
                MacClipboard clipboard = open;
                if (clipboard != null) {
                    clipboard.look();
                }
                return 0;
            });
            ObjCRuntime.class_addMethod(made, ObjC.sel("pasteboard:item:provideDataForType:"), provide.address(), "v@:@@@");
            // An item checks that its provider conforms, and refuses one that only responds.
            long protocol = ObjCRuntime.objc_getProtocol("NSPasteboardItemDataProvider");
            if (protocol != NULL) {
                ObjCRuntime.class_addProtocol(made, protocol);
            }
            ObjCRuntime.class_addMethod(made, ObjC.sel("limnApplicationActivated:"), activated.address(), "v@:@");
            ObjCRuntime.objc_registerClassPair(made);
            ownerClass = made;
        }
        owner = ObjC.msg(ObjC.msg(ownerClass, "alloc"), "init");
        open = this;
        long pool = pool();
        try {
            ObjC.msg(ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter"),
                    "addObserver:selector:name:object:", owner, ObjC.sel("limnApplicationActivated:"),
                    nsString("NSApplicationDidBecomeActiveNotification"), NULL);
            look();
        } finally {
            drain(pool);
        }
    }

    // ---- Objective-C ------------------------------------------------------------------------------

    /**
     * A data provider of the class the clipboard's is, for pasteboard items that are not the
     * clipboard's: a drag's. Main thread; the clipboard must have been opened first.
     *
     * @param provide given the item asked and the identifier asked for
     * @return the provider object, kept for the life of the process
     */
    static long newProvider(java.util.function.BiConsumer<Long, String> provide) {
        if (ownerClass == NULL) {
            throw new IllegalStateException("the pasteboard's provider class is made with the clipboard, "
                    + "which is not open");
        }
        long made = ObjC.msg(ObjC.msg(ownerClass, "alloc"), "init");
        OTHER_PROVIDERS.put(made, provide);
        return made;
    }

    static long pool() {
        return ObjC.msg(ObjC.msg(ObjC.cls("NSAutoreleasePool"), "alloc"), "init");
    }

    static void drain(long pool) {
        ObjC.msgVoid(pool, "drain");
    }

    static long nsString(String text) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:", memAddress(stack.UTF8(text)));
        }
    }

    static String string(long nsString) {
        if (nsString == NULL) {
            return "";
        }
        long utf8 = ObjC.msg(nsString, "UTF8String");
        return utf8 == NULL ? "" : memUTF8(utf8);
    }

    static byte[] bytes(long nsData) {
        if (nsData == NULL) {
            return null;
        }
        long size = ObjC.msg(nsData, "length");
        if (size < 0 || size > PlatformClipboard.MAX_BYTES) {
            return null; // another program's block, past what a transfer is read whole
        }
        int length = (int) size;
        byte[] out = new byte[length];
        if (length > 0) {
            memByteBuffer(ObjC.msg(nsData, "bytes"), length).get(out);
        }
        return out;
    }

    static long nsData(byte[] bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(Math.max(1, bytes.length));
            try {
                copy.put(0, bytes);
                return ObjC.msg(ObjC.cls("NSData"), "dataWithBytes:length:", memAddress(copy), bytes.length);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(copy);
            }
        }
    }

    static String uti(String type) {
        return switch (type) {
            case ClipboardTypes.TEXT -> UTI_TEXT;
            case ClipboardTypes.HTML -> UTI_HTML;
            case ClipboardTypes.IMAGE -> UTI_PNG;
            case ClipboardTypes.FILES -> UTI_FILE_URL;
            default -> type;
        };
    }

    // ---- what is there ------------------------------------------------------------------------------

    /** Compares the change count, and reads the types again when it moved. */
    private void look() {
        long count = ObjC.msg(pasteboard, "changeCount");
        if (count == seenCount) {
            return;
        }
        seenCount = count;
        offered = typesOf(pasteboard);
        if (count != ownCount) {
            ownTypes = List.of();
            ownBytes = type -> null;
        }
        changeListener.run();
    }

    /** The canonical types a pasteboard holds, in its order: the general one's, or a drag's. */
    static List<String> typesOf(long pasteboard) {
        List<String> types = new ArrayList<>();
        long array = ObjC.msg(pasteboard, "types");
        long n = array == NULL ? 0 : ObjC.msg(array, "count");
        for (long i = 0; i < n; i++) {
            String uti = string(ObjC.msg(array, "objectAtIndex:", i));
            String type = switch (uti) {
                case UTI_TEXT, "NSStringPboardType", "public.utf16-external-plain-text" -> ClipboardTypes.TEXT;
                case UTI_HTML -> ClipboardTypes.HTML;
                case UTI_PNG, UTI_TIFF -> ClipboardTypes.IMAGE;
                case UTI_FILE_URL, "NSFilenamesPboardType" -> ClipboardTypes.FILES;
                default -> uti;
            };
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        return List.copyOf(types);
    }

    @Override
    public boolean isOwner() {
        return !closed && ObjC.msg(pasteboard, "changeCount") == ownCount;
    }

    @Override
    public List<String> types() {
        long pool = pool();
        try {
            look();
        } finally {
            drain(pool);
        }
        return offered;
    }

    @Override
    public CompletableFuture<byte[]> read(String type) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        byte[] bytes;
        long pool = pool();
        try {
            bytes = readNow(pasteboard, type);
        } finally {
            drain(pool);
        }
        // Completed from another thread, so that whoever decodes what arrived does it off the UI.
        Thread hand = new Thread(() -> {
            if (bytes == null) {
                result.completeExceptionally(new TransferGoneException(type + " is not on the clipboard"));
            } else {
                result.complete(bytes);
            }
        }, "limn-mac-clipboard-read");
        hand.setDaemon(true);
        hand.start();
        return result;
    }

    /** The bytes of one canonical type of a pasteboard, read now; null when it has none. Main thread. */
    static byte[] readNow(long pasteboard, String type) {
        switch (type) {
            case ClipboardTypes.TEXT: {
                long text = ObjC.msg(pasteboard, "stringForType:", nsString(UTI_TEXT));
                return text == NULL ? null : string(text).getBytes(StandardCharsets.UTF_8);
            }
            case ClipboardTypes.IMAGE: {
                byte[] png = bytes(ObjC.msg(pasteboard, "dataForType:", nsString(UTI_PNG)));
                if (png != null) {
                    return png;
                }
                long tiff = ObjC.msg(pasteboard, "dataForType:", nsString(UTI_TIFF));
                if (tiff == NULL) {
                    return null;
                }
                long rep = ObjC.msg(ObjC.cls("NSBitmapImageRep"), "imageRepWithData:", tiff);
                long none = ObjC.msg(ObjC.cls("NSDictionary"), "dictionary");
                return rep == NULL ? null
                        : bytes(ObjC.msg(rep, "representationUsingType:properties:", PNG_FILE_TYPE, none));
            }
            case ClipboardTypes.FILES: {
                List<Path> files = new ArrayList<>();
                long items = ObjC.msg(pasteboard, "pasteboardItems");
                long n = items == NULL ? 0 : ObjC.msg(items, "count");
                for (long i = 0; i < n; i++) {
                    long url = ObjC.msg(ObjC.msg(items, "objectAtIndex:", i), "stringForType:", nsString(UTI_FILE_URL));
                    if (url != NULL) {
                        // A Finder copy may hand a file reference URL; its path is the file's.
                        long nsUrl = ObjC.msg(ObjC.cls("NSURL"), "URLWithString:", url);
                        long path = nsUrl == NULL ? NULL : ObjC.msg(nsUrl, "path");
                        if (path != NULL) {
                            files.add(Path.of(string(path)));
                        }
                    }
                }
                return files.isEmpty() ? null : UriLists.encode(files);
            }
            default:
                return bytes(ObjC.msg(pasteboard, "dataForType:", nsString(uti(type))));
        }
    }

    // ---- what we offer --------------------------------------------------------------------------------

    @Override
    public void offer(List<String> types, Function<String, byte[]> bytes) {
        if (closed) {
            return;
        }
        long pool = pool();
        try {
            ObjC.msg(pasteboard, "clearContents");
            releasePromised();
            ownTypes = List.copyOf(types);
            ownBytes = bytes;
            provided.clear();
            if (!ownTypes.isEmpty()) {
                long items = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
                long first = ObjC.msg(ObjC.msg(ObjC.cls("NSPasteboardItem"), "alloc"), "init");
                ObjC.msgVoid(items, "addObject:", first);
                long promised = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
                for (String type : ownTypes) {
                    if (type.equals(ClipboardTypes.FILES)) {
                        byte[] list = bytes.apply(type);
                        long item = first;
                        for (Path file : list == null ? List.<Path>of() : UriLists.decode(list)) {
                            long url = ObjC.msg(ObjC.cls("NSURL"), "fileURLWithPath:", nsString(file.toString()));
                            if (item == NULL) {
                                item = ObjC.msg(ObjC.msg(ObjC.msg(ObjC.cls("NSPasteboardItem"), "alloc"), "init"),
                                        "autorelease");
                                ObjC.msgVoid(items, "addObject:", item);
                            }
                            ObjC.msg(item, "setString:forType:", ObjC.msg(url, "absoluteString"),
                                    nsString(UTI_FILE_URL));
                            item = NULL; // one file an item
                        }
                        provided.add(type);
                    } else {
                        ObjC.msgVoid(promised, "addObject:", nsString(itemType(type)));
                    }
                }
                if (ObjC.msg(promised, "count") > 0) {
                    ObjC.msg(first, "setDataProvider:forTypes:", owner, promised);
                }
                ObjC.msg(pasteboard, "writeObjects:", items);
                promisedItem = first; // ours, from alloc, until the next offer or the end
            }
            ownCount = ObjC.msg(pasteboard, "changeCount");
            seenCount = ownCount;
        } finally {
            drain(pool);
        }
        changeListener.run();
    }

    private void releasePromised() {
        if (promisedItem != NULL) {
            ObjC.msgVoid(promisedItem, "release");
            promisedItem = NULL;
        }
    }

    /**
     * The identifier an item holds a type under: the system's own for the standard ones, and for
     * an application's name the dynamic one the system derives for a pasteboard type so named.
     */
    static String itemType(String type) {
        String uti = uti(type);
        if (uti.indexOf('/') < 0) {
            return uti;
        }
        if (preferredIdentifier == NULL) {
            preferredIdentifier = org.lwjgl.system.APIUtil.apiGetFunctionAddress(
                    org.lwjgl.system.APIUtil.apiCreateLibrary(
                            "/System/Library/Frameworks/CoreServices.framework/CoreServices"),
                    "UTTypeCreatePreferredIdentifierForTag");
        }
        return DYNAMIC.computeIfAbsent(uti, name -> {
            // Conforming to public.data, as the pasteboard itself derives it: without that the
            // identifier differs and the name no longer finds the data (read on the guest).
            long made = Ffi.call(preferredIdentifier, "ppp>p", nsString("com.apple.nspboard-type"),
                    nsString(name), nsString("public.data"));
            if (made == NULL) {
                return name;
            }
            String identifier = string(made);
            ObjC.msgVoid(made, "release"); // a CFString handed over by a Create function
            return identifier;
        });
    }

    /** The pasteboard asks an item for one promised type. Main thread. */
    private void provide(long item, String identifier) {
        for (String type : ownTypes) {
            if (itemType(type).equals(identifier) && !type.equals(ClipboardTypes.FILES)) {
                byte[] data = ownBytes.apply(type);
                if (data != null) {
                    ObjC.msg(item, "setData:forType:", nsData(data), nsString(identifier));
                    provided.add(type);
                }
                return;
            }
        }
    }

    @Override
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        long pool = pool();
        try {
            if (ObjC.msg(pasteboard, "changeCount") == ownCount) {
                for (String type : ownTypes) {
                    if (promisedItem != NULL && !provided.contains(type)) {
                        provide(promisedItem, itemType(type)); // what was copied here stays after the application
                    }
                }
            }
            ObjC.msgVoid(ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter"), "removeObserver:", owner);
        } finally {
            drain(pool);
        }
        releasePromised();
        closed = true;
        open = null;
    }
}
