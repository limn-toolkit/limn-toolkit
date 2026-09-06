package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetDouble;
import static org.lwjgl.system.Pointer.POINTER_SIZE;

/**
 * The runtime subclass of {@code NSAccessibilityElement} every node is vended as, and the
 * implementations installed on it.
 *
 * <p><b>One class for the whole process, and {@code self} is what identifies the node.</b> The
 * instance carries no state of ours: an implementation recovers the node by asking the source what
 * this pointer stands for — the same recovery the Windows spike used to get a Java object back from
 * a COM interface pointer. That is what keeps the answers coming from the published snapshot rather
 * than from something stored on an object and left to go stale.
 *
 * <p><b>Nothing is installed on a class GLFW owns.</b> §13.16 was withdrawn by measurement: pushing
 * the root's children onto the content view is sufficient to place our elements under the window a
 * reader walks, so the three {@code class_addMethod} calls an earlier design made on GLFW's content
 * view are gone. Every implementation here is on our own class.
 *
 * <p><b>Every encoding is read out of the running AppKit</b> and handed straight to
 * {@code class_addMethod} (§12.3). A table of encodings in a source file is a copy of an answer, and
 * a copy is what goes stale.
 */
final class AxElementClass {

    /** What an implementation needs in order to answer: the snapshot, and the two element maps. */
    interface Source {

        /**
         * @param element the {@code self} an implementation was entered with
         * @return the node it stands for, or {@code null} when the registry has forgotten it —
         *         which happens legitimately, for a message that arrives after a destruction
         */
        AccessibleNode nodeFor(long element);

        /**
         * @param node a node in the published tree
         * @return the elements for its children, minting any that do not exist yet
         */
        long[] childElementsOf(AccessibleNode node);

        /**
         * @param node a node in the published tree
         * @return the element for its parent — or the content view, for a child of the elided
         *         window root, because that is what AppKit was handed and what it expects back
         */
        long parentElementOf(AccessibleNode node);

        /**
         * @return the element for the node that has the keyboard, or zero when nothing does
         */
        long focusedElement();

        /**
         * Performs one verb on one node, through the scene.
         *
         * <p>It resolves nothing and waits for nothing: the identifier is checked against the
         * published tree, the verb is posted, and the answer is whether it was <b>accepted</b> —
         * never whether it is done. On this platform the calling thread is the user-interface
         * thread, so a wait here would be an instant self-deadlock (§1.9).
         *
         * @param nodeId the node the message was sent to
         * @param action the verb
         * @return whether the scene took it
         */
        boolean perform(long nodeId, limn.accessibility.Accessible.Action action);

        /**
         * Called on entry to every implementation below. §6's honest gate on this platform is
         * "someone has asked", and this is the ask: there is no {@code UiaClientsAreListening} here
         * and no registry to consult.
         */
        void entered();
    }

    private final AxObjC objc;
    private final Source source;
    private final long elementClass;
    private final List<Callback> callbacks = new ArrayList<>();

    AxElementClass(AxObjC objc, Source source, String className) {
        this.objc = objc;
        this.source = source;
        long superclass = objc.cls("NSAccessibilityElement");
        if (superclass == NULL) {
            throw new IllegalStateException("no NSAccessibilityElement: this is not AppKit");
        }
        long created = ObjCRuntime.objc_allocateClassPair(superclass, className, 0);
        if (created == NULL) {
            throw new IllegalStateException("objc_allocateClassPair(NSAccessibilityElement, "
                    + className + ") failed; the name is already taken in this process");
        }
        this.elementClass = created;
        install();
        ObjCRuntime.objc_registerClassPair(created);
    }

    /** @return a new, retained instance; the caller owns it until it releases it. */
    long newInstance() {
        return objc.msg(objc.msg(elementClass, "alloc"), "init");
    }

    /** Frees every libffi closure. After this the class must never be messaged again. */
    void free() {
        callbacks.forEach(Callback::free);
        callbacks.clear();
    }

    private void addId(String selector, IdGetter body) {
        callbacks.add(body);
        ObjCRuntime.class_addMethod(elementClass, objc.sel(selector), body.address(),
                objc.encodingOf(selector));
    }

    private void addBool(String selector, BoolGetter body) {
        callbacks.add(body);
        ObjCRuntime.class_addMethod(elementClass, objc.sel(selector), body.address(),
                objc.encodingOf(selector));
    }

    private void install() {
        addId("accessibilityRole", get(node ->
                objc.constant(AxRoles.of(node.role()).roleSymbol())));
        addId("accessibilitySubrole", get(node -> {
            String subrole = AxRoles.of(node.role()).subroleSymbol();
            return subrole == null ? NULL : objc.constant(subrole);
        }));

        // Finding 5, and never both: a name that is the control's own text is a title, and anything
        // else is a description. Setting both makes VoiceOver say the name twice.
        addId("accessibilityTitle", get(node ->
                AxNames.attributeFor(node.nameFrom()) == AxNames.Attribute.TITLE
                        ? objc.string(node.name()) : NULL));
        addId("accessibilityLabel", get(node ->
                AxNames.attributeFor(node.nameFrom()) == AxNames.Attribute.LABEL
                        ? objc.string(node.name()) : NULL));
        addId("accessibilityHelp", get(node ->
                node.description().isEmpty() ? NULL : objc.string(node.description())));

        addId("accessibilityValue", get(this::valueOf));
        addId("accessibilityIdentifier", get(node -> objc.string(Long.toString(node.id()))));

        addId("accessibilityChildren", get(node -> {
            long[] children = source.childElementsOf(node);
            if (children.length == 0) return NULL;
            long array = objc.mutableArray();
            for (long child : children) objc.addObject(array, child);
            return array;
        }));
        addId("accessibilityParent", get(source::parentElementOf));

        // Transparent and ignored widgets never become nodes, so everything vended here is an
        // element (§1.6). Answering false would make AppKit hoist a node's children over it.
        addBool("isAccessibilityElement", is(node -> true));
        addBool("isAccessibilityEnabled", is(node -> node.has(Accessible.State.ENABLED)));
        addBool("isAccessibilityFocused", is(node -> node.has(Accessible.State.FOCUSED)));

        installHitTest();
        installFocusedElement();
        installActions();
    }

    /**
     * The action selectors, and the gate that decides which of them each element offers.
     *
     * <p>The gate is not decoration. Every implementation here goes on one class, so every element
     * responds to all of them, and AppKit builds the action list a client is shown out of what an
     * object responds to — a button would advertise "increment" and a slider "show menu". So
     * {@code isAccessibilitySelectorAllowed:} answers from the node's own {@code ActionFacet}, and
     * the list becomes per node instead of per class.
     */
    private void installActions() {
        for (String selector : AxActions.selectors()) {
            addBool(selector, new BoolGetter() {
                @Override public boolean invoke(long self, long cmd) {
                    source.entered();
                    AccessibleNode node = source.nodeFor(self);
                    if (node == null) return false;
                    Accessible.Action verb = AxActions.verbFor(node, selector);
                    // False is "this element does not do that", which is the truth for a selector
                    // the node never advertised -- and a client that got here anyway asked for
                    // something the gate below already refused.
                    return verb != null && source.perform(node.id(), verb);
                }
            });
        }

        SelectorGate gate = new SelectorGate() {
            @Override public boolean invoke(long self, long cmd, long selector) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                if (node == null) return false;
                String name = ObjCRuntime.sel_getName(selector);
                // Only the action selectors are gated. Everything else this class implements is an
                // attribute, and answering false for one of those would hide the node's name.
                if (!AxActions.isActionSelector(name)) return true;
                return AxActions.verbFor(node, name) != null;
            }
        };
        callbacks.add(gate);
        ObjCRuntime.class_addMethod(elementClass, objc.sel("isAccessibilitySelectorAllowed:"),
                gate.address(), objc.encodingOf("isAccessibilitySelectorAllowed:"));
    }

    /**
     * §13.22's experiment, and the selector this design could not reason its way to.
     *
     * <p>{@code NSAccessibilityElement} does not declare it at all — {@code NSView} does — so our
     * elements are not obviously the thing AppKit asks. The spike never moved focus, and posting
     * {@code AXFocusedUIElementChanged} is proven to be <em>delivered</em>; being able to answer
     * "where am I" afterwards is not, and a reader that is told the focus moved and cannot find out
     * where it went does nothing at all, which is what the first VoiceOver run of this bridge
     * showed: it landed on the first element and stayed there through every move.
     *
     * <p>It is installed here first because here is where it costs nothing. Whether AppKit ever
     * enters it is the measurement; {@link #focusedElementAsks()} is the answer.
     */
    private void installFocusedElement() {
        addId("accessibilityFocusedUIElement", new IdGetter() {
            @Override public long invoke(long self, long cmd) {
                source.entered();
                focusedElementAsks++;
                return source.focusedElement();
            }
        });
    }

    private int focusedElementAsks;
    private int focusedElementAsksOnView;

    /** @return how many times AppKit has asked one of our elements where the focus is. */
    int focusedElementAsks() {
        return focusedElementAsks;
    }

    /** @return how many times AppKit has asked the CONTENT VIEW where the focus is. */
    int focusedElementAsksOnView() {
        return focusedElementAsksOnView;
    }

    /**
     * Answers {@code accessibilityFocusedUIElement} on the content view, by pointing that one
     * instance at a subclass of its own class that implements it.
     *
     * <p><b>This is §13.16 reopened for exactly one selector, which §13.22 said it would be.</b> The
     * measurement that reopened it: with the selector on our element class alone, AppKit entered it
     * <b>zero</b> times, and VoiceOver landed on the first element and stayed there through every
     * focus move — told each time that the focus had changed, and with no way to ask where it went.
     * Our elements are not responders, so the question never reaches them; it goes to the view.
     *
     * <p><b>Nothing is added to a class GLFW owns.</b> {@code class_addMethod} on
     * {@code GLFWContentView} would change every window in the process, including windows this
     * bridge knows nothing about. This allocates a subclass <em>of</em> that class and re-points
     * one instance's {@code isa} at it — the technique the spike proved — so the effect is exactly
     * one view wide, and GLFW's own class is left as it was found.
     *
     * @param contentView the window's content view
     * @param subclassName a name unique in this process
     */
    void installFocusedElementOnView(long contentView, String subclassName) {
        long viewClass = ObjCRuntime.object_getClass(contentView);
        long subclass = ObjCRuntime.objc_allocateClassPair(viewClass, subclassName, 0);
        if (subclass == NULL) {
            throw new IllegalStateException("objc_allocateClassPair(" + subclassName + ") failed");
        }
        IdGetter body = new IdGetter() {
            @Override public long invoke(long self, long cmd) {
                source.entered();
                focusedElementAsksOnView++;
                long focused = source.focusedElement();
                // Zero would be nil, and nil from the view means "nothing here has the keyboard",
                // which is the truthful answer while no node of ours is focused. Answering the view
                // itself instead would put the reader on a thing with no name.
                return focused;
            }
        };
        callbacks.add(body);
        ObjCRuntime.class_addMethod(subclass, objc.sel("accessibilityFocusedUIElement"),
                body.address(), objc.encodingOf("accessibilityFocusedUIElement"));
        ObjCRuntime.objc_registerClassPair(subclass);
        ObjCRuntime.object_setClass(contentView, subclass);
    }

    /**
     * The selector the phase 7 probe run proved is not optional.
     *
     * <p>AppKit's own hit test resolves a point to the top level of the array pushed onto the
     * content view and stops there: three points inside three different grandchildren all came back
     * as the grandchild's grandparent, and pushing the children at every level as well changed
     * nothing (§13.21). It then sends this selector <b>once</b>, to the element it resolved — so
     * this implementation walks the whole subtree rather than descending one level and waiting to
     * be asked again.
     *
     * <p>The point arrives in screen space with a bottom-left origin, which is the space
     * {@code accessibilityFrame} answers in, so nothing here flips anything: the flip is AppKit's
     * (§1.8).
     */
    private void installHitTest() {
        HitTest hitTest = new HitTest() {
            @Override public long invoke(long self, long cmd, double x, double y) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                return node == null ? self : descend(self, node, x, y);
            }
        };
        callbacks.add(hitTest);
        ObjCRuntime.class_addMethod(elementClass, objc.sel("accessibilityHitTest:"),
                hitTest.address(), objc.encodingOf("accessibilityHitTest:"));
    }

    private long descend(long element, AccessibleNode node, double x, double y) {
        for (long child : source.childElementsOf(node)) {
            double[] frame = objc.msgGetRect(child, "accessibilityFrame");
            if (x >= frame[0] && x < frame[0] + frame[2]
                    && y >= frame[1] && y < frame[1] + frame[3]) {
                AccessibleNode childNode = source.nodeFor(child);
                return childNode == null ? child : descend(child, childNode, x, y);
            }
        }
        return element;
    }

    /**
     * The value, and the hole §2.1 spends a paragraph on for the other platform.
     *
     * <p>Three facets share this one attribute, which is why they are three facets rather than one
     * field. A text node whose value came only from {@code ValueFacet} is a field VoiceOver cannot
     * read — so {@code TextFacet} answers here too.
     */
    private long valueOf(AccessibleNode node) {
        if (node.toggle() != null) {
            // A toggle's value is a number here, not a boolean and not a string: AppKit's own check
            // boxes answer 0, 1 or 2, and the mixed state is why it is not a BOOL.
            return objc.number(switch (node.toggle().state()) {
                case OFF -> 0;
                case ON -> 1;
                case MIXED -> 2;
            });
        }
        if (node.text() != null) return objc.string(node.text().text());
        if (node.value() != null) {
            // The displayed text when the node has one, and the number otherwise. A slider that
            // shows "40%" must not be read as "0.4", and one that shows nothing has only the number.
            String shown = node.value().text();
            return shown != null ? objc.string(shown) : objc.number((long) node.value().value());
        }
        return NULL;
    }

    // ---- the two shapes of implementation, and the libffi closures under them -------------------

    private interface NodeToId {
        long apply(AccessibleNode node);
    }

    private interface NodeToBool {
        boolean test(AccessibleNode node);
    }

    private IdGetter get(NodeToId body) {
        return new IdGetter() {
            @Override public long invoke(long self, long cmd) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                // A message to an element whose node is gone is not an error: it is a client using
                // a reference it held across a destruction, and nil is the honest answer.
                return node == null ? NULL : body.apply(node);
            }
        };
    }

    private BoolGetter is(NodeToBool body) {
        return new BoolGetter() {
            @Override public boolean invoke(long self, long cmd) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                return node != null && body.test(node);
            }
        };
    }

    /** {@code (id self, SEL _cmd) -> id}, encoding {@code @16@0:8}. */
    private interface IdGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(IdGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(memGetAddress(memGetAddress(args)),
                    memGetAddress(memGetAddress(args + POINTER_SIZE))));
        }
        long invoke(long self, long cmd);
    }

    private abstract static class IdGetter extends Callback implements IdGetterI {
        protected IdGetter() { super(IdGetterI.DESCRIPTOR); }
    }

    /** {@code (id self, SEL _cmd) -> BOOL}, encoding {@code B16@0:8}. */
    private interface BoolGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(BoolGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_uint8, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            // apiClosureRet widens the BOOL to an ffi_arg correctly; writing a byte would leave the
            // rest of the register as whatever was there.
            APIUtil.apiClosureRet(ret, invoke(memGetAddress(memGetAddress(args)),
                    memGetAddress(memGetAddress(args + POINTER_SIZE))));
        }
        boolean invoke(long self, long cmd);
    }

    private abstract static class BoolGetter extends Callback implements BoolGetterI {
        protected BoolGetter() { super(BoolGetterI.DESCRIPTOR); }
    }

    /** {@code (id self, SEL _cmd, SEL) -> BOOL}, encoding {@code B24@0:8:16}. */
    private interface SelectorGateI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(SelectorGateI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_uint8, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(memGetAddress(memGetAddress(args)),
                    memGetAddress(memGetAddress(args + POINTER_SIZE)),
                    memGetAddress(memGetAddress(args + 2L * POINTER_SIZE))));
        }
        boolean invoke(long self, long cmd, long selector);
    }

    private abstract static class SelectorGate extends Callback implements SelectorGateI {
        protected SelectorGate() { super(SelectorGateI.DESCRIPTOR); }
    }

    /** {@code (id self, SEL _cmd, CGPoint) -> id}, encoding {@code @32@0:8{CGPoint=dd}16}. */
    private interface HitTestI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(HitTestI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, AxObjC.doubles(2)));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long point = memGetAddress(args + 2L * POINTER_SIZE);   // a pointer to the struct
            APIUtil.apiClosureRetP(ret, invoke(memGetAddress(memGetAddress(args)),
                    memGetAddress(memGetAddress(args + POINTER_SIZE)),
                    memGetDouble(point), memGetDouble(point + 8)));
        }
        long invoke(long self, long cmd, double x, double y);
    }

    private abstract static class HitTest extends Callback implements HitTestI {
        protected HitTest() { super(HitTestI.DESCRIPTOR); }
    }
}
