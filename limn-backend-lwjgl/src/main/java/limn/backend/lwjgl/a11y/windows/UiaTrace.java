package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where the Windows bridge says what it did, so a reader that heard nothing can be asked about
 * rather than guessed at.
 *
 * <p>The 2026-09-16 Windows reading (readings/phase5-windows/summary.md) could not tell, from the
 * application's side, why NVDA heard a Limn widget in one run of fourteen while a plain UI
 * Automation client heard every one of them. Three facts were out of reach and each is a line here:
 * <b>the {@code HRESULT}</b> of a raise, which the bridge discarded — "the HRESULT of the raise is
 * not reachable: the Windows bridge has no trace switch"; <b>whether a client's process reaches the
 * provider at all</b>, which only an inbound trace can say; and <b>what a client subscribed to</b>,
 * which {@code AdviseEventAdded} knows and nothing wrote down.
 *
 * <p><b>Nothing listens by default, and then nothing is built.</b> The sink is read once per site
 * and every line is formatted only behind {@link #on()}, as the Linux bridge's {@code AtspiTrace}
 * does. {@code -Dlimn.a11y.uia.trace=<file>} opens that file once, at this class's initialization,
 * and writes one line per event flushed as it is written, so a run killed in native code keeps
 * what it had. A test installs its own sink instead, either here or on {@link UiaWindow#trace},
 * which is where this bridge's own notes have always gone and where its tests read them.
 *
 * <h2>The kinds, which are the first word of every line</h2>
 * <ul>
 *   <li>{@code RAISE} — one call into {@code UiaRaise*}: which entry point, the event or property
 *       by name and number, the node with its role, name and runtime id, whether the element was
 *       minted for this raise or already held, the {@code HRESULT}, and the thread.</li>
 *   <li>{@code CALL} — the {@code UIAutomationCore} entry points that are not raises:
 *       {@code UiaReturnRawElementProvider}, {@code UiaHostProviderFromHwnd} and
 *       {@code UiaDisconnectProvider}.</li>
 *   <li>{@code SKIP} — something the bridge decided <em>not</em> to raise, and why.</li>
 *   <li>{@code GATE} — the per-frame listening decision, written when it changes: the process-wide
 *       flag, the standing subscriptions, the owed event and how much of the asked window is
 *       left.</li>
 *   <li>{@code IN} — a provider entry point a client called, with the caller's thread and the
 *       answer. {@code AdviseEventAdded}/{@code AdviseEventRemoved} are the decisive ones.
 *       <b>Every member of the four interfaces an element serves writes one</b> — all fourteen of
 *       {@code IRawElementProviderSimple}, {@code IRawElementProviderFragment},
 *       {@code IRawElementProviderFragmentRoot} and {@code IRawElementProviderAdviseEvents},
 *       pinned by {@code UiaTraceTest.everyMemberOfTheFourInboundInterfacesSaysThatItWasCalled}.
 *       The <b>pattern</b> members ({@link UiaPatternProviders}, some thirty-five slots: an
 *       invoke, a toggle, a value written, a scroll) write none, so a file with no {@code IN} line
 *       says that no client read this provider — not that none acted on it.
 *       {@code -Dlimn.a11y.uia.trace.inbound=decisions} narrows this half to the lines that decide
 *       something and every failing return, for a run whose question is a timing race rather than
 *       arrival ({@link #onForRead}).</li>
 *   <li>{@code NOTE} — the bridge's own older notes, which the file carries unchanged.</li>
 * </ul>
 *
 * <p>Every file line is prefixed with a monotonic timestamp, {@code +<milliseconds>} since this
 * class initialized; the header line written when the file opens carries the wall clock of that
 * moment, which is what aligns this file with a screen reader's own log. The in-process sink gets
 * the line without the stamp, because that is the form this bridge's tests have always read.
 *
 * <p><b>The microseconds a {@code NOTE} reports for a raise</b> —
 * {@code raised FOCUS_CHANGED for node 1001 in 2314 us} — are measured around the platform call
 * alone and exclude everything this class then spends writing the lines beside them. The measured
 * figure for how long a raise waits for a reader's handler (2.5 ms median, one of 50 ms) is read
 * off those notes, and one flushed line to a file on the guest is of the same order as the number;
 * an instrument that inflates the measurement it is read beside would be worse than none.
 *
 * <p>Strings a line repeats — a node's name, a property's answer — are cut to
 * {@value #STRING_LIMIT} characters. They are the values the provider itself would hand a client,
 * so a password field's masked text stays masked here; nothing is read from anywhere the client
 * could not read it from.
 */
final class UiaTrace {

    private UiaTrace() {
    }

    /** The system property naming the file to write, read once at this class's initialization. */
    static final String PROPERTY = "limn.a11y.uia.trace";

    /** The longest string value a line repeats. */
    static final int STRING_LIMIT = 40;

    /** When this class initialized, which every line's stamp is measured from. */
    private static final long OPENED = System.nanoTime();

    /**
     * Where a stamped copy of every line goes: the file the property named, or {@code null}, which
     * is nobody and costs one read per site. Volatile and not final so a test can install its own.
     */
    static volatile Consumer<String> file = openFromProperty();

    /**
     * @return whether anything at all is listening. Read before a line is formatted, never after:
     *         the whole point is that a bridge with the trace off allocates nothing for it
     */
    static boolean on() {
        return file != null || UiaWindow.trace != null;
    }

    /** The property choosing how much of the inbound half is written. */
    static final String INBOUND_PROPERTY = "limn.a11y.uia.trace.inbound";

    /**
     * Whether a client's ordinary reads of one node are written. Volatile and not final so a test
     * can turn it over.
     */
    static volatile boolean reads = readsFromProperty(propertyOrNull(INBOUND_PROPERTY));

    /**
     * Whether a client's read of one node should say so.
     *
     * <p><b>The volume and the decisions are separate switches.</b> An NVDA read of one node is
     * dozens of {@code GetPropertyValue} calls, each a flushed line through one {@code PrintWriter}
     * whose {@code println} is synchronized — so with the whole inbound half on, the drain thread's
     * {@code RAISE} waits for the RPC threads' disk writes, and the scheduling of the very thread
     * the 1-in-14 race turns on is perturbed by the instrument.
     * {@code -Dlimn.a11y.uia.trace.inbound=decisions} keeps the lines that decide something — what
     * a client subscribed to, what it asked the window for, what it acted on, and <b>every failing
     * return</b> — and drops the per-node reads.
     *
     * <p><b>The default is everything</b>, which is not what the review proposed (it proposed
     * advise-only) and is a judgement to be overruled if the owner disagrees: the first question
     * this file exists to answer is whether a client's process arrives at all, a client can arrive
     * and read without ever subscribing, and a file that is quiet by default cannot answer the
     * question it was turned on for. The quiet mode is one word on the command line for the run
     * that is chasing the timing race rather than the arrival.
     *
     * @param hresult what the member answered
     * @return whether to write the line
     */
    static boolean onForRead(int hresult) {
        return hresult == UiaIds.S_OK ? onForRead() : on();
    }

    /**
     * @return {@link #onForRead}'s {@code S_OK} half, for a site a failure never reaches
     */
    static boolean onForRead() {
        return reads && on();
    }

    /**
     * @param said what the property said, or {@code null}
     * @return whether a client's ordinary reads are written: {@code decisions} narrows the file to
     *         the decisions and the failures; anything else is everything, and an unrecognised
     *         value says so on standard error rather than quietly writing less than was asked for
     */
    static boolean readsFromProperty(String said) {
        if (said == null || said.isBlank() || said.trim().equalsIgnoreCase("all")) {
            return true;
        }
        if (said.trim().equalsIgnoreCase("decisions")) {
            return false;
        }
        System.err.println("[uia] -D" + INBOUND_PROPERTY + '=' + said
                + " is neither all nor decisions; writing everything");
        return true;
    }

    /**
     * One of this bridge's own notes, in the words it has always used.
     *
     * @param line the note, already formatted by a caller that checked {@link #on()}
     */
    static void note(String line) {
        Consumer<String> hook = UiaWindow.trace;
        if (hook != null) {
            hook.accept(line);
        }
        Consumer<String> sink = file;
        if (sink != null) {
            sink.accept(stamp() + "NOTE " + line);
        }
    }

    /**
     * One line of the instrumentation.
     *
     * @param kind {@code RAISE}, {@code CALL}, {@code SKIP}, {@code GATE} or {@code IN}
     * @param body everything after it
     */
    static void line(String kind, String body) {
        String whole = kind + ' ' + body + " thread=" + Thread.currentThread().getName();
        Consumer<String> hook = UiaWindow.trace;
        if (hook != null) {
            hook.accept(whole);
        }
        Consumer<String> sink = file;
        if (sink != null) {
            sink.accept(stamp() + whole);
        }
    }

    /**
     * One call into {@code UiaRaise*}, with everything a diagnosis needs to match it against a
     * client's own log.
     *
     * @param entryPoint the {@code UIAutomationCore} function called
     * @param what       the event or property, from {@link #event} or {@link #property}
     * @param tree       the snapshot the node is read from
     * @param nodeId     the node it was raised on
     * @param held       whether a client already held the element, as read before the raise
     * @param hresult    what the call answered
     */
    static void raised(String entryPoint, String what, AccessibleTree tree, long nodeId,
                       boolean held, int hresult) {
        line("RAISE", entryPoint + ' ' + what + ' ' + element(tree, nodeId)
                + " element=" + (held ? "held" : "minted") + " hr=" + hresult(hresult));
    }

    /**
     * Something the bridge did not raise.
     *
     * @param what   the event or property it would have been
     * @param nodeId the node it would have been raised on
     * @param why    the gate that refused it, in one hyphenated word
     */
    static void skipped(String what, long nodeId, String why) {
        line("SKIP", what + " node=" + nodeId + " why=" + why);
    }

    /**
     * One provider entry point a client called.
     *
     * <p>Every member of the four interfaces writes one, the failing returns included: the
     * question a live run puts to this half of the trace is whether a reader's process reaches
     * this provider at all, and an entry that answered {@code UIA_E_ELEMENTNOTAVAILABLE} reached
     * it. That is why identity ({@code GetRuntimeId}), geometry
     * ({@code get_BoundingRectangle}) and the options every element is asked for first
     * ({@code get_ProviderOptions}) are here too, though none of them says anything about a widget:
     * a client that arrived and was rejected early may have called nothing else, and an empty
     * column would then be read as a client that never arrived.
     *
     * @param entryPoint the COM member, by its declared name
     * @param detail     what it was asked and what it answered
     * @param hresult    what it returned
     */
    static void inbound(String entryPoint, String detail, int hresult) {
        line("IN", entryPoint + ' ' + detail + " hr=" + hresult(hresult));
    }

    /**
     * A node as a line names it: the identifier, the model's role, the name and the runtime id UI
     * Automation completes with the window's own.
     *
     * @param tree   the snapshot, which may no longer hold the node
     * @param nodeId the node
     * @return {@code node=1001 role=BUTTON name="Save" runtimeId=[3,0,1001]}
     */
    static String element(AccessibleTree tree, long nodeId) {
        AccessibleNode node = tree == null ? null : tree.find(nodeId);
        int[] id = UiaFragment.runtimeId(nodeId);
        return "node=" + nodeId
                + " role=" + (node == null ? "gone" : node.role().name())
                + " name=" + text(node == null ? null : node.name())
                + " runtimeId=[" + id[0] + ',' + id[1] + ',' + id[2] + ']';
    }

    /** @param id an event id
     *  @return {@code AUTOMATION_FOCUS_CHANGED(20005)}, or the number alone when it is not ours */
    static String event(int id) {
        return "event=" + named(Names.EVENTS, id);
    }

    /** @param id a property id
     *  @return {@code property=NAME(30005)} */
    static String property(int id) {
        return "property=" + named(Names.PROPERTIES, id);
    }

    /** @param id a pattern id
     *  @return {@code pattern=INVOKE_PATTERN(10000)} */
    static String pattern(int id) {
        return "pattern=" + named(Names.PATTERNS, id);
    }

    /** @param ids the property array a client advised for, which may be empty
     *  @return the names and numbers in order, in brackets */
    static String properties(int[] ids) {
        StringBuilder out = new StringBuilder("properties=[");
        for (int at = 0; at < ids.length; at++) {
            out.append(at == 0 ? "" : ",").append(named(Names.PROPERTIES, ids[at]));
        }
        return out.append(']').toString();
    }

    /** @param direction one of {@code NavigateDirection}'s
     *  @return its name and number */
    static String direction(int direction) {
        String name = switch (direction) {
            case UiaIds.NAVIGATE_DIRECTION_PARENT -> "PARENT";
            case UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING -> "NEXT_SIBLING";
            case UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING -> "PREVIOUS_SIBLING";
            case UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD -> "FIRST_CHILD";
            case UiaIds.NAVIGATE_DIRECTION_LAST_CHILD -> "LAST_CHILD";
            default -> "UNKNOWN";
        };
        return "direction=" + name + '(' + direction + ')';
    }

    /** @param type one of {@code StructureChangeType}'s
     *  @return its name and number */
    static String structureChange(int type) {
        String name = switch (type) {
            case UiaIds.STRUCTURE_CHANGE_CHILD_ADDED -> "CHILD_ADDED";
            case UiaIds.STRUCTURE_CHANGE_CHILD_REMOVED -> "CHILD_REMOVED";
            case UiaIds.STRUCTURE_CHANGE_CHILDREN_INVALIDATED -> "CHILDREN_INVALIDATED";
            case UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_ADDED -> "CHILDREN_BULK_ADDED";
            case UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED -> "CHILDREN_BULK_REMOVED";
            case UiaIds.STRUCTURE_CHANGE_CHILDREN_REORDERED -> "CHILDREN_REORDERED";
            default -> "UNKNOWN";
        };
        return "change=" + name + '(' + type + ')';
    }

    /** @param kind a {@code NotificationKind}
     *  @param processing a {@code NotificationProcessing}
     *  @return both by name and number */
    static String notification(int kind, int processing) {
        String kindName = switch (kind) {
            case UiaIds.NOTIFICATION_KIND_ITEM_ADDED -> "ITEM_ADDED";
            case UiaIds.NOTIFICATION_KIND_ITEM_REMOVED -> "ITEM_REMOVED";
            case UiaIds.NOTIFICATION_KIND_ACTION_COMPLETED -> "ACTION_COMPLETED";
            case UiaIds.NOTIFICATION_KIND_ACTION_ABORTED -> "ACTION_ABORTED";
            case UiaIds.NOTIFICATION_KIND_OTHER -> "OTHER";
            default -> "UNKNOWN";
        };
        String processingName = switch (processing) {
            case UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_ALL -> "IMPORTANT_ALL";
            case UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT -> "IMPORTANT_MOST_RECENT";
            case UiaIds.NOTIFICATION_PROCESSING_ALL -> "ALL";
            case UiaIds.NOTIFICATION_PROCESSING_MOST_RECENT -> "MOST_RECENT";
            case UiaIds.NOTIFICATION_PROCESSING_CURRENT_THEN_MOST_RECENT ->
                    "CURRENT_THEN_MOST_RECENT";
            case UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_CURRENT_THEN_MOST_RECENT ->
                    "IMPORTANT_CURRENT_THEN_MOST_RECENT";
            default -> "UNKNOWN";
        };
        return "kind=" + kindName + '(' + kind + ") processing=" + processingName
                + '(' + processing + ')';
    }

    /**
     * @param hresult what a call answered
     * @return the number in hexadecimal, with the name where this bridge has one for it. The
     *         failures are named because the question a live run asks is which of them came back
     */
    static String hresult(int hresult) {
        String name = switch (hresult) {
            case UiaIds.S_OK -> "S_OK";
            case UiaIds.E_NO_INTERFACE -> "E_NO_INTERFACE";
            case UiaIds.E_ELEMENT_NOT_AVAILABLE -> "E_ELEMENT_NOT_AVAILABLE";
            case UiaIds.E_ELEMENT_NOT_ENABLED -> "E_ELEMENT_NOT_ENABLED";
            case UiaIds.E_INVALID_OPERATION -> "E_INVALID_OPERATION";
            case UiaIds.E_ARGUMENT_OUT_OF_RANGE -> "E_ARGUMENT_OUT_OF_RANGE";
            default -> null;
        };
        String hex = "0x" + Integer.toHexString(hresult);
        return name == null ? hex : hex + '(' + name + ')';
    }

    /**
     * A value a property change carries, as the line repeats it.
     *
     * @param value what {@code changedValue} made of the model's own
     * @return a string in quotes, anything else as it prints, and an absent value as
     *         {@code none} — which is the {@code VT_EMPTY} the raise writes for it
     */
    static String value(Object value) {
        if (value == null) {
            return "none";
        }
        return value instanceof String said ? text(said) : String.valueOf(value);
    }

    /**
     * @param value a string a line repeats, or {@code null}
     * @return it in quotes, cut to {@value #STRING_LIMIT} characters, or {@code null} unquoted
     */
    static String text(String value) {
        if (value == null) {
            return "null";
        }
        String cut = value.length() > STRING_LIMIT ? value.substring(0, STRING_LIMIT) + "…" : value;
        return '"' + cut.replace('\n', ' ') + '"';
    }

    /** @return the monotonic stamp every file line begins with */
    private static String stamp() {
        return String.format(Locale.ROOT, "+%010.3f ", (System.nanoTime() - OPENED) / 1_000_000d);
    }

    private static String named(Map<Integer, String> names, int id) {
        String name = names.get(id);
        return name == null ? String.valueOf(id) : name + '(' + id + ')';
    }

    /** Opens the file {@link #PROPERTY} names, or answers {@code null}. */
    private static Consumer<String> openFromProperty() {
        return opened(propertyOrNull(PROPERTY));
    }

    /**
     * @param name a system property
     * @return its value, or {@code null} — and never a throw, because every reader of this is in
     *         this class's initialization, which {@link #opened} explains must not fail
     */
    private static String propertyOrNull(String name) {
        try {
            return System.getProperty(name);
        } catch (RuntimeException cannot) {
            System.err.println("[uia] cannot read -D" + name + ": " + cannot);
            return null;
        }
    }

    /**
     * Opens the file a property value names, or answers {@code null}.
     *
     * <p>Appended to rather than truncated, with a header naming the wall clock it opened at: one
     * path reused by two runs then reads as two runs rather than as one run with its first half
     * missing, and the header is what a reader aligns the monotonic stamps against. A path that
     * cannot be opened leaves the trace off and says so on standard error — the alternative is a
     * live run that believes it is tracing and writes nothing.
     *
     * <p><b>It answers {@code null} for every unusable path and throws for none</b>, which is not
     * belt and braces: this runs in this class's initialization, which the first {@link
     * UiaWindow#say} of a window's subclassing triggers on the user-interface thread, so anything
     * thrown here leaves {@code UiaWindow.attach} with an {@code ExceptionInInitializerError} and
     * the window never opens. {@code Path.of} is the one that surprises — an invalid path is an
     * {@link java.nio.file.InvalidPathException}, which is an {@code IllegalArgumentException} and
     * no kind of {@code IOException}, and on Windows {@code < > " | ? *} are all invalid: a stray
     * quote or wildcard in the guest's command line is enough. A diagnostic switch that can take
     * the process down is worse than no switch, because the operator has one run to spend.
     *
     * @param path what the property said, which may be {@code null}, blank, or unusable
     * @return the sink, or {@code null} for no trace
     */
    static Consumer<String> opened(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return writingTo(Path.of(path.trim()));
        } catch (RuntimeException | IOException cannot) {
            System.err.println("[uia] cannot write the trace to " + path + ": " + cannot);
            return null;
        }
    }

    /**
     * A sink writing one flushed line at a time to a file.
     *
     * @param path where to write
     * @return the sink, which is never closed: every line is flushed as it is written, so a run
     *         that ends in native code — which is how this bridge's live runs have ended — keeps
     *         everything it said
     * @throws IOException if the file cannot be opened
     */
    static Consumer<String> writingTo(Path path) throws IOException {
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
                true);
        out.println("=== limn uia trace opened " + java.time.Instant.now()
                + " stamps are milliseconds from here");
        return out::println;
    }

    /**
     * The names of {@link UiaIds}' identifiers, by number, built from the source itself the first
     * time a line needs one — so a constant renamed there is renamed here, and a machine with the
     * trace off never builds these maps at all.
     *
     * <p>The three spaces do not overlap: patterns are 10000-10999, events 20000-20999 and
     * properties 30000-30999, which is UI Automation's own partition and is what makes one
     * reflective pass safe. Everything else {@link UiaIds} holds — control types at 50000, the
     * enumerators that start at zero, the limits, the {@code HRESULT}s — falls outside all three
     * and is named by a switch where a line needs it.
     */
    private static final class Names {

        private Names() {
        }

        static final Map<Integer, String> PATTERNS = namesIn(10_000, 10_999);
        static final Map<Integer, String> EVENTS = namesIn(20_000, 20_999);
        static final Map<Integer, String> PROPERTIES = namesIn(30_000, 30_999);

        private static Map<Integer, String> namesIn(int low, int high) {
            Map<Integer, String> found = new java.util.HashMap<>();
            for (Field field : UiaIds.class.getDeclaredFields()) {
                if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    int value = field.getInt(null);
                    if (value >= low && value <= high) {
                        found.putIfAbsent(value, field.getName());
                    }
                } catch (IllegalAccessException notOurs) {
                    // A field this class may not read is a field no line can name; the number
                    // alone is still printed, which is what the map's absence means.
                }
            }
            return Map.copyOf(found);
        }
    }
}
