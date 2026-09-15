package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.RoleNames;
import limn.backend.lwjgl.ObjC;
import limn.backend.lwjgl.a11y.ClosureArgs;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.system.MemoryUtil.memGetDouble;
import static org.lwjgl.system.MemoryUtil.memPutLong;

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
         * @param nodeId a node in the published tree, not the elided window root
         * @return its element, minting it if it does not exist yet
         */
        long elementFor(long nodeId);

        /**
         * @param node a node in the published tree
         * @return the element for its parent — or the content view, for a child of the elided
         *         window root, because that is what AppKit was handed and what it expects back
         */
        long parentElementOf(AccessibleNode node);

        /**
         * @param node a node in the published tree
         * @return the elements at the other end of every relation the node declares, minting any
         *         that do not exist yet, and skipping a target that is the elided window root or
         *         has left the tree; empty when the node declares none
         */
        long[] linkedElementsOf(AccessibleNode node);

        /**
         * @return the element for where the user is — the tree's effective focus, which is the cursor
         *         item under the focused widget when there is one, and may be a node of another
         *         window's tree (decision 5) — or zero when nothing is
         */
        long focusedElement();

        /**
         * @param node a node in the published tree
         * @return whether it is where the user is, agreeing with {@link #focusedElement()}
         */
        boolean isFocused(AccessibleNode node);

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
         * Performs one parameterised verb on one node, through the scene, as {@link #perform(long,
         * Accessible.Action)} does: checked, posted, never waited for.
         *
         * @param nodeId   the node the message was sent to
         * @param action   the verb
         * @param argument what it carries
         * @return whether the scene took it
         */
        boolean perform(long nodeId, Accessible.Action action, Accessible.Argument argument);

        /** @return the published tree every answer is read from; never {@code null} */
        limn.accessibility.AccessibleTree tree();

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
    /** {@code NSAccessibilityElement}: what a released element is pointed back at. */
    private final long superclass;
    private final List<Callback> callbacks = new ArrayList<>();
    /** The table, row and cell lookups the closures below wrap. */
    private final AxGrid grid;
    /**
     * Whether {@code NSAccessibilityElement} itself lacked a legacy entry point, so AXElementBusy was
     * not installed; named in the constructor's warning.
     */
    private boolean skippedForBusy;
    /** Every listed selector's encoding, read from the running AppKit before anything is installed. */
    private final AxSelectors.Resolution selectors;
    /** The retained {@code NSString} {@link #BUSY_ATTRIBUTE}; zero until installed and after free. */
    private long busyAttribute;

    /**
     * {@code kAXElementBusyAttribute}, a {@code CFSTR} macro in HIServices'
     * {@code AXAttributeConstants.h} with no AppKit global behind it, so {@code dlsym} has nothing to
     * find; read off this build's SDK on 2026-09-13, like its notification in
     * {@link AxNotifications#BUSY_CHANGED}.
     */
    static final String BUSY_ATTRIBUTE = "AXElementBusy";
    /** The one view whose isa was re-pointed, and the class it had before; zero until then. */
    private long swizzledView;
    private long viewClassBefore;

    AxElementClass(AxObjC objc, Source source, String className) {
        this.objc = objc;
        this.source = source;
        this.grid = new AxGrid(source);
        this.selectors = AxSelectors.resolve(objc::encodingOrNull);
        this.superclass = ObjC.cls("NSAccessibilityElement");
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
        String warning = selectors.warning();
        if (warning != null) LOG.log(System.Logger.Level.WARNING, warning);
        if (skippedForBusy) {
            LOG.log(System.Logger.Level.WARNING, "NSAccessibilityElement answers no legacy attribute "
                    + "entry point on this macOS, so AXElementBusy is not served (ADR 044 §2)");
        }
    }

    private static final System.Logger LOG = System.getLogger(AxElementClass.class.getName());

    /** @return the listed selectors the running AppKit declared nothing for, and that were skipped. */
    List<String> missingSelectors() {
        return selectors.missing();
    }

    /** @return a new, retained instance; the caller owns it until it releases it. */
    long newInstance() {
        return ObjC.msg(ObjC.msg(elementClass, "alloc"), "init");
    }

    /**
     * Points one of this class's instances back at {@code NSAccessibilityElement}, so that whatever
     * still holds it after we let go gets AppKit's own empty answers and never a closure of ours.
     *
     * <p>This is what makes {@link #free()} safe, and the window-close run with VoiceOver attached
     * is why it exists: an element the bridge has released is not an element nobody will ever
     * message again. The class adds no instance variables, so the layout is the superclass's and
     * the re-point is exact.
     *
     * @param element an instance this class minted
     */
    void demote(long element) {
        ObjCRuntime.object_setClass(element, superclass);
    }

    /**
     * Gives the content view its own class back, undoing {@link #installFocusedElementOnView}.
     *
     * <p>Without this, the view keeps answering {@code accessibilityFocusedUIElement} through a
     * closure {@link #free()} is about to release — and VoiceOver asks that question while the
     * window is being destroyed, which {@code glfwDestroyWindow} pumps the run loop for. Measured
     * on the guest: a SIGSEGV inside liblwjgl at the end of the first scroll run, from
     * {@code _NSAccessibilityEntryPointValueForAttribute} under {@code glfwDestroyWindow}.
     */
    void restoreView() {
        if (swizzledView == NULL) return;
        ObjCRuntime.object_setClass(swizzledView, viewClassBefore);
        swizzledView = NULL;
    }

    /**
     * Frees every libffi closure.
     *
     * <p>Only after every instance was {@link #demote(long) demoted} and the view
     * {@link #restoreView() restored}: a closure is an IMP on a class, and freeing it while any
     * object still dispatches to that class is a crash the next time a client asks — which on this
     * platform is the moment the window goes away, not never.
     */
    void free() {
        callbacks.forEach(Callback::free);
        callbacks.clear();
        if (busyAttribute != NULL) {
            ObjC.msgVoid(busyAttribute, "release");
            busyAttribute = NULL;
        }
    }

    /**
     * The one place a method is added to a class: every install goes through here, so that nothing
     * reaches {@code class_addMethod} unlisted or with an encoding that was not read.
     *
     * <p>A selector {@link AxSelectors} does not list is a mistake in this file and refuses to build
     * the class, and so does a closure whose shape is not the one listed for the selector
     * ({@link AxSelectors.Kind}); {@code AxSelectorsTest} catches both off a Mac, where this cannot
     * run. A listed
     * selector the running AppKit declares nothing for is skipped and its closure freed, and so is
     * one installed only together with a selector that was skipped (the actions with the gate); the
     * constructor's warning names every one (MACOS-NEW-6).
     *
     * @return whether it was installed
     */
    private <C extends Callback & Shaped> boolean addMethod(long target, String selector, C body) {
        String refusal = AxSelectors.refusal(selector, body.kind());
        if (refusal != null) {
            body.free();
            throw new IllegalStateException(refusal);
        }
        String encoding = selectors.encodingOf(selector);
        if (encoding == null) {
            body.free();
            return false;
        }
        callbacks.add(body);
        ObjCRuntime.class_addMethod(target, ObjC.sel(selector), body.address(), encoding);
        return true;
    }

    private void addId(String selector, IdGetter body) {
        addMethod(elementClass, selector, body);
    }

    private void addBool(String selector, BoolGetter body) {
        addMethod(elementClass, selector, body);
    }

    private void install() {
        addId("accessibilityRole", get(node -> {
            AxRoles.Mapping mapping = AxRoles.of(node.role());
            return objc.constant(mapping == null ? "NSAccessibilityUnknownRole" : mapping.roleSymbol());
        }));
        addId("accessibilitySubrole", get(node -> {
            AxRoles.Mapping mapping = AxRoles.of(node.role());
            String subrole = mapping == null ? null : mapping.subroleSymbol();
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

        // Every role, not only the ones AppKit has no word for. NSAccessibilityRoleDescription()
        // localizes against the CALLING process's bundle and a JVM has none, so deferring to AppKit
        // means English on every non-English desktop -- measured on a wholly pt-BR guest, where
        // VoiceOver said "checkbox" and "slider" in English inside its own Portuguese sentences.
        addId("accessibilityRoleDescription", get(node ->
                objc.string(RoleNames.of(node.role(), node.locale()))));

        // Written through setAccessibilityValue: (installSetters), which posts nothing to a node
        // AccessibleNode#accepts refuses, a node without ENABLED included (semantics 5, amended
        // 2026-09-15); the value itself is the facet's, never a read-only flag the node lacks.
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
        // The one relation attribute this platform has for an element that is neither a parent
        // nor a child, and the one whose encoding the dump records: every relation the node
        // declares -- the caption that names it, the message that describes it, the control a
        // popup opened for -- is a linked element. The text of a description travels separately,
        // as accessibilityHelp above; AppKit has no attribute that names the describing element as
        // such, so the link is what a client that wants the element itself follows.
        addId("accessibilityLinkedUIElements", get(node -> {
            long[] linked = source.linkedElementsOf(node);
            if (linked.length == 0) return NULL;
            long array = objc.mutableArray();
            for (long element : linked) objc.addObject(array, element);
            return array;
        }));

        // Transparent and ignored widgets never become nodes, so everything vended here is an
        // element (§1.6). Answering false would make AppKit hoist a node's children over it.
        addBool("isAccessibilityElement", is(node -> true));
        addBool("isAccessibilityEnabled", is(node -> node.has(Accessible.State.ENABLED)));
        // Where the user is, not which widget holds the keyboard around it (semantics 4): the cursor
        // cell of a focused table answers true and the table does not, as the focused element does.
        addBool("isAccessibilityFocused", is(source::isFocused));

        installHitTest();
        installFocusedElement();
        installActions();
        installSetters();
        installTable();
        installBusy();
    }

    /**
     * {@code AXElementBusy}, the one attribute this bridge serves that AppKit's NSAccessibility
     * protocol has no property for: a row of a tree whose children are on their way (ADR 044 §2).
     *
     * <p><b>So it goes through the legacy entry points</b>, {@code accessibilityAttributeValue:} and
     * {@code accessibilityAttributeNames}, which every other attribute here leaves to AppKit. Both
     * overrides answer the busy attribute themselves and hand everything else to the
     * implementation {@code NSAccessibilityElement} already has, which is what maps an attribute
     * name onto the protocol getters above. The inherited implementations are taken from the
     * superclass before these are added to our class, so a forward reaches AppKit's and can never
     * come back into this one. A superclass with no such method would make that forward
     * {@code _objc_msgForward}, which is a crash rather than a missing attribute, so neither is
     * installed then and the constructor warns: the busy attribute goes unserved and the rest of the
     * element is built (MACOS-NEW-6).
     */
    private void installBusy() {
        long valueSelector = ObjC.sel("accessibilityAttributeValue:");
        long namesSelector = ObjC.sel("accessibilityAttributeNames");
        if (ObjCRuntime.class_getInstanceMethod(superclass, valueSelector) == NULL
                || ObjCRuntime.class_getInstanceMethod(superclass, namesSelector) == NULL) {
            // Refused, and not thrown: a forward to a method the superclass lacks would be
            // _objc_msgForward, a crash rather than a missing attribute, but a thrown constructor
            // would take every other attribute of the window with it (MACOS-NEW-6).
            skippedForBusy = true;
            return;
        }
        long inheritedValue = ObjCRuntime.class_getMethodImplementation(superclass, valueSelector);
        long inheritedNames = ObjCRuntime.class_getMethodImplementation(superclass, namesSelector);
        busyAttribute = ObjC.msg(objc.string(BUSY_ATTRIBUTE), "retain");

        AttributeGetter value = new AttributeGetter() {
            @Override public long invoke(long self, long cmd, long attribute) {
                if (attribute != NULL
                        && (ObjC.msg(attribute, "isEqualToString:", busyAttribute) & 0xFF) != 0) {
                    source.entered();
                    AccessibleNode node = source.nodeFor(self);
                    return node == null ? NULL : ObjC.msg(ObjC.cls("NSNumber"), "numberWithBool:",
                            node.has(Accessible.State.BUSY) ? 1 : 0);
                }
                return JNI.invokePPPP(self, cmd, attribute, inheritedValue);
            }
        };
        addMethod(elementClass, "accessibilityAttributeValue:", value);

        addId("accessibilityAttributeNames", new IdGetter() {
            @Override public long invoke(long self, long cmd) {
                long inherited = JNI.invokePPP(self, cmd, inheritedNames);
                return inherited == NULL
                        ? ObjC.msg(ObjC.cls("NSArray"), "arrayWithObject:", busyAttribute)
                        : ObjC.msg(inherited, "arrayByAddingObject:", busyAttribute);
            }
        });
    }

    /**
     * What NSAccessibilityTable, NSAccessibilityRow and NSAccessibilityCell ask: {@link AxGrid}'s
     * answers, wrapped for AppKit. Every lookup is there, where it can be tested without AppKit;
     * what is here is only the conversion of an element list into an {@code NSArray} and of a
     * number into the closure's return.
     */
    private void installTable() {
        addId("accessibilityRows", get(node -> nsArray(grid.rows(node))));
        addId("accessibilityVisibleRows", get(node -> nsArray(grid.visibleRows(node))));
        addId("accessibilitySelectedRows", get(node -> nsArray(grid.selectedRows(node))));
        // The selection of a container whose members are not rows (semantics 1; MACOS-NEW-2): its
        // selected children, or a grid's selected cells. Each is offered only where it is the
        // container's shape (AxGate), as a native outline offers AXSelectedRows and no
        // AXSelectedChildren.
        addId("accessibilitySelectedChildren", get(node -> nsArray(grid.selectedMembers(node))));
        addId("accessibilitySelectedCells", get(node -> nsArray(grid.selectedMembers(node))));
        addId("accessibilityColumns", get(node -> nsArray(grid.columns(node))));
        addId("accessibilityHeader", get(grid::header));
        addId("accessibilityColumnHeaderUIElements",
                get(node -> nsArray(grid.columnHeaderElements(node))));
        addLong("accessibilityRowCount", grid::rowCount);
        addLong("accessibilityColumnCount", grid::columnCount);
        addLong("accessibilityIndex", grid::index);
        addRange("accessibilityRowIndexRange", grid::rowIndexRange);
        addRange("accessibilityColumnIndexRange", grid::columnIndexRange);
        CellAt cellAt = new CellAt() {
            @Override public long invoke(long self, long cmd, long column, long row) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                return node == null ? NULL : grid.cellAt(node, column, row);
            }
        };
        addMethod(elementClass, "accessibilityCellForColumn:row:", cellAt);
        addBool("isAccessibilitySelected", is(node -> node.has(Accessible.State.SELECTED)));
        installDisclosure();
    }

    /**
     * An outline row's disclosure (M1), answered as a native NSOutlineView's rows answer it — read on
     * the macOS 26.6.2 guest, 2026-09-15 — and whether anything else that opens is open. Every one of
     * these is offered only where it has an answer ({@link AxGate}): a native row answers AXDisclosing
     * and no AXExpanded, so an outline row answers the first and never the second, and everything else
     * with an expand facet the second.
     */
    private void installDisclosure() {
        addBool("isAccessibilityDisclosed", is(grid::disclosed));
        addLong("accessibilityDisclosureLevel", grid::disclosureLevel);
        addId("accessibilityDisclosedByRow", get(grid::disclosedByRow));
        addId("accessibilityDisclosedRows", get(node -> nsArray(grid.disclosedRows(node))));
        addBool("isAccessibilityExpanded", is(node -> node.expand() != null && node.expand().expanded()));
    }

    /** An autoreleased {@code NSArray} of these elements, or nil for {@code null}. */
    private long nsArray(long[] elements) {
        if (elements == null) return NULL;
        long array = objc.mutableArray();
        for (long element : elements) objc.addObject(array, element);
        return array;
    }

    private void addLong(String selector, NodeToLong body) {
        LongGetter getter = new LongGetter() {
            @Override public long invoke(long self, long cmd) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                return node == null ? 0 : body.apply(node);
            }
        };
        addMethod(elementClass, selector, getter);
    }

    private void addRange(String selector, NodeToRange body) {
        RangeGetter getter = new RangeGetter() {
            @Override public long[] invoke(long self, long cmd) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                return node == null ? AxGrid.NOT_FOUND : body.apply(node);
            }
        };
        addMethod(elementClass, selector, getter);
    }

    private interface NodeToLong {
        long apply(AccessibleNode node);
    }

    private interface NodeToRange {
        long[] apply(AccessibleNode node);
    }

    /**
     * The action selectors, and the gate that decides which of them each element offers.
     *
     * <p>The gate is not decoration. Every implementation here goes on one class, so every element
     * responds to all of them, and AppKit builds the action list a client is shown out of what an
     * object responds to — a button would advertise "increment" and a slider "show menu". So
     * {@code isAccessibilitySelectorAllowed:} answers from the node's own {@code ActionFacet}, and
     * the list becomes per node instead of per class. The same gate refuses a row or table getter on
     * a node that has no answer for it ({@link AxGate}), because AppKit honours a refused getter too.
     *
     * <p>Which is why the two are installed as one unit: an AppKit that declared no gate would leave
     * every action here advertised on every element, so {@link AxSelectors#REQUIRES} withholds the
     * actions with it and the constructor's warning names both.
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
                return AxGate.allows(grid, node, ObjCRuntime.sel_getName(selector));
            }
        };
        addMethod(elementClass, "isAccessibilitySelectorAllowed:", gate);
    }

    /**
     * The setter half (MACOS-NEW-11): a reader's write to AXFocused, AXSelected, AXDisclosing,
     * AXExpanded or AXValue, posted as the verb it means where the node accepts that verb, and
     * nothing anywhere else. Whether each is settable is the gate's answer for the setter selector,
     * which is {@link AxSetters#offers}; the write checks again, because a client need not ask first.
     * Each installed only with the gate ({@link AxSelectors#REQUIRES}), or every element would report
     * every one of them settable.
     */
    private void installSetters() {
        for (String selector : AxSetters.BOOL_SETTERS) {
            addMethod(elementClass, selector, new BoolSetter() {
                @Override public void invoke(long self, long cmd, boolean on) {
                    source.entered();
                    AccessibleNode node = source.nodeFor(self);
                    if (node == null) return;
                    AxSetters.Setting setting = AxSetters.forBool(grid, node, selector, on);
                    if (setting != null) source.perform(node.id(), setting.action(), setting.argument());
                }
            });
        }
        IdSetter valueSetter = new IdSetter() {
            @Override public void invoke(long self, long cmd, long written) {
                source.entered();
                AccessibleNode node = source.nodeFor(self);
                if (node == null || written == NULL) return;
                String text = isKindOf(written, "NSString") ? objc.javaString(written) : null;
                Double number = null;
                if (text == null && isKindOf(written, "NSNumber")) {
                    try {
                        number = Double.valueOf(objc.javaString(ObjC.msg(written, "stringValue")));
                    } catch (NumberFormatException | NullPointerException unreadable) {
                        return;
                    }
                }
                AxSetters.Setting setting = AxSetters.forValue(node, text, number);
                if (setting != null) source.perform(node.id(), setting.action(), setting.argument());
            }
        };
        addMethod(elementClass, "setAccessibilityValue:", valueSetter);
    }

    private static boolean isKindOf(long object, String className) {
        return (ObjC.msg(object, "isKindOfClass:", ObjC.cls(className)) & 0xFF) != 0;
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
        addMethod(subclass, "accessibilityFocusedUIElement", body);
        ObjCRuntime.objc_registerClassPair(subclass);
        this.swizzledView = contentView;
        this.viewClassBefore = viewClass;
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
        addMethod(elementClass, "accessibilityHitTest:", hitTest);
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

    /** A closure that says which {@link AxSelectors.Kind} it is, so that the install can check it. */
    private interface Shaped {
        AxSelectors.Kind kind();
    }

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
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1)));
        }
        long invoke(long self, long cmd);
    }

    private abstract static class IdGetter extends Callback implements IdGetterI, Shaped {
        protected IdGetter() { super(IdGetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.ID; }
    }

    /** {@code (id self, SEL _cmd, id) -> id}, encoding {@code @24@0:8@16}. */
    private interface AttributeGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(AttributeGetterI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1),
                    ClosureArgs.pointer(args, 2)));
        }
        long invoke(long self, long cmd, long attribute);
    }

    private abstract static class AttributeGetter extends Callback implements AttributeGetterI, Shaped {
        protected AttributeGetter() { super(AttributeGetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.ID_OF_ID; }
    }

    /** {@code (id self, SEL _cmd) -> BOOL}, encoding {@code B16@0:8}. */
    private interface BoolGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(BoolGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_uint8, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            // apiClosureRet widens the BOOL to an ffi_arg correctly; writing a byte would leave the
            // rest of the register as whatever was there.
            APIUtil.apiClosureRet(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1)));
        }
        boolean invoke(long self, long cmd);
    }

    private abstract static class BoolGetter extends Callback implements BoolGetterI, Shaped {
        protected BoolGetter() { super(BoolGetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.BOOL; }
    }

    /** {@code (id self, SEL _cmd, BOOL) -> void}, encoding {@code v20@0:8B16}. */
    private interface BoolSetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(BoolSetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint8));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            // The BOOL is one byte in its slot; the rest of the slot is not the caller's to promise.
            invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1),
                    memGetByte(ClosureArgs.slot(args, 2)) != 0);
        }
        void invoke(long self, long cmd, boolean on);
    }

    private abstract static class BoolSetter extends Callback implements BoolSetterI, Shaped {
        protected BoolSetter() { super(BoolSetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.VOID_OF_BOOL; }
    }

    /** {@code (id self, SEL _cmd, id) -> void}, encoding {@code v24@0:8@16}. */
    private interface IdSetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(IdSetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_void, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1), ClosureArgs.pointer(args, 2));
        }
        void invoke(long self, long cmd, long written);
    }

    private abstract static class IdSetter extends Callback implements IdSetterI, Shaped {
        protected IdSetter() { super(IdSetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.VOID_OF_ID; }
    }

    /** {@code (id self, SEL _cmd, SEL) -> BOOL}, encoding {@code B24@0:8:16}. */
    private interface SelectorGateI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(SelectorGateI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_uint8, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            APIUtil.apiClosureRet(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1),
                    ClosureArgs.pointer(args, 2)));
        }
        boolean invoke(long self, long cmd, long selector);
    }

    private abstract static class SelectorGate extends Callback implements SelectorGateI, Shaped {
        protected SelectorGate() { super(SelectorGateI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.BOOL_OF_SELECTOR; }
    }

    /** {@code (id self, SEL _cmd) -> NSInteger}, encoding {@code q16@0:8}. */
    private interface LongGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(LongGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_sint64, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            memPutLong(ret, invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1)));
        }
        long invoke(long self, long cmd);
    }

    private abstract static class LongGetter extends Callback implements LongGetterI, Shaped {
        protected LongGetter() { super(LongGetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.INTEGER; }
    }

    /**
     * {@code (id self, SEL _cmd) -> NSRange}, encoding {@code {_NSRange=QQ}16@0:8}. Two unsigned
     * 64-bit fields, which arm64 returns in x0 and x1 and libffi writes to the return block.
     */
    private interface RangeGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(RangeGetterI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(AxObjC.uint64s(2), LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long[] range = invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1));
            memPutLong(ret, range[0]);
            memPutLong(ret + 8, range[1]);
        }
        long[] invoke(long self, long cmd);
    }

    private abstract static class RangeGetter extends Callback implements RangeGetterI, Shaped {
        protected RangeGetter() { super(RangeGetterI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.RANGE; }
    }

    /** {@code (id self, SEL _cmd, NSInteger column, NSInteger row) -> id}, encoding {@code @32@0:8q16q24}. */
    private interface CellAtI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(CellAtI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_sint64, LibFFI.ffi_type_sint64));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1),
                    ClosureArgs.int64(args, 2), ClosureArgs.int64(args, 3)));
        }
        long invoke(long self, long cmd, long column, long row);
    }

    private abstract static class CellAt extends Callback implements CellAtI, Shaped {
        protected CellAt() { super(CellAtI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.ID_OF_TWO_INTEGERS; }
    }

    /** {@code (id self, SEL _cmd, CGPoint) -> id}, encoding {@code @32@0:8{CGPoint=dd}16}. */
    private interface HitTestI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(HitTestI.class, MethodHandles.lookup(),
                APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, AxObjC.doubles(2)));
        @Override default Callback.Descriptor getDescriptor() { return DESCRIPTOR; }
        @Override default void callback(long ret, long args) {
            long point = ClosureArgs.slot(args, 2);   // a pointer to the struct
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0),
                    ClosureArgs.pointer(args, 1),
                    memGetDouble(point), memGetDouble(point + 8)));
        }
        long invoke(long self, long cmd, double x, double y);
    }

    private abstract static class HitTest extends Callback implements HitTestI, Shaped {
        protected HitTest() { super(HitTestI.DESCRIPTOR); }
        @Override public final AxSelectors.Kind kind() { return AxSelectors.Kind.ID_OF_POINT; }
    }
}
