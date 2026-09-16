package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Windows bridge's trace: off it costs nothing and writes nothing, on it says what a
 * diagnosis needs.
 *
 * <p>The platform calls are no-ops on this machine, so the {@code HRESULT} a raise records here is
 * always {@code S_OK} — which is what {@link Uia} answers where {@code uiautomationcore} did not
 * open. What is checked is that the number travels from the call to the line at all, which is the
 * one thing the 2026-09-16 Windows reading could not do: "the HRESULT of the raise is not
 * reachable: the Windows bridge has no trace switch". A failing {@code HRESULT} is a guest's to
 * produce.
 */
class UiaTraceTest {

    /** A window with one focused button, which is the smallest tree that raises a focus change. */
    private static AccessibleTree aFocusedButton() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, true);
        a.end();
        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }

    /**
     * With no sink at all the gate is shut, and the one call every window makes on every damaged
     * frame allocates nothing: the whole instrumentation is written so that a machine with the
     * trace off pays two volatile reads and never builds a string.
     *
     * <p>{@link UiaBridge#isListening()} is the site that matters, because it is the one on the
     * idle path — the call a window with no reader pays sixty times a second. Measured against
     * itself with the trace on, which does build a line and therefore does allocate.
     */
    @Test
    void withNothingListeningTheGateAllocatesNothingAndWithASinkItDoesNot() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine cannot count allocation");
        assertNull(UiaTrace.file, "no -D" + UiaTrace.PROPERTY + " was given to this run");
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = null;
        try {
            bridge.publish(aFocusedButton(), false);
            assertFalse(UiaTrace.on(), "nothing is listening");
            assertEquals(0L, AllocationProbe.leastAllocatedBy(bridge::isListening, 64),
                    "the gate allocated with the trace off");

            // And with a sink it says so, which is what the zero above is a zero of. The line is
            // written when the answer CHANGES, so a fresh bridge writes exactly one.
            List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            UiaWindow.trace = said::add;
            bridge.isListening();
            assertEquals(1, said.size(), "one line for the first answer: " + said);
            assertTrue(said.get(0).startsWith("GATE listening=false anyoneInTheSession=false "
                    + "advised=0 owedAnEvent=false askedWindowLeftMs=0 thread="), said.get(0));
            bridge.isListening();
            assertEquals(1, said.size(), "an unchanged answer says nothing again: " + said);
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * With the file sink closed off, a full exercise of the bridge — the raise path and every
     * inbound entry point — writes not one line to it.
     *
     * <p>The file is opened first and detached immediately, so what is asserted is a real file
     * the sink could have written to and did not. The drain thread is stopped and joined by the
     * detach, so nothing it might have said can arrive after the assertion.
     */
    @Test
    void withTheSinkDetachedAFullExerciseWritesNothingToTheFile(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("uia-off.log");
        Consumer<String> beforeFile = UiaTrace.file;
        Consumer<String> beforeHook = UiaWindow.trace;
        UiaTrace.file = UiaTrace.writingTo(file);
        UiaTrace.file = null;
        UiaWindow.trace = null;
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            assertFalse(UiaTrace.on());
            exercise(bridge);
        } finally {
            // Stops and joins the drain, so every line it could have written is written.
            bridge.detach();
            UiaTrace.file = beforeFile;
            UiaWindow.trace = beforeHook;
        }
        assertEquals(1, Files.readAllLines(file).size(),
                "only the header the open wrote: " + Files.readString(file));
    }

    /**
     * With the file sink open, a raise records its {@code HRESULT} and an advise records the event
     * it was subscribed for and the properties that came with it.
     *
     * <p>Read back while the sink is still open, which is the other half of the claim: the file is
     * flushed per line, so a run killed in native code — which is how this bridge's live runs have
     * ended — keeps what it had.
     */
    @Test
    void withTheSinkOpenARaiseCarriesItsHresultAndAnAdviseItsEventAndProperties(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("uia-on.log");
        Consumer<String> beforeFile = UiaTrace.file;
        Consumer<String> beforeHook = UiaWindow.trace;
        List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        UiaTrace.file = UiaTrace.writingTo(file);
        UiaWindow.trace = said::add;
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        long array = 0;
        long data = 0;
        try {
            bridge.publish(aFocusedButton(), false);
            bridge.objectFor(1001);
            bridge.noteAsked();
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(await(said, l -> l.startsWith("RAISE UiaRaiseAutomationEvent")),
                    "the focus change never reached the platform: " + said);

            // A property-changed subscription as UI Automation hands one over: one dimension of
            // four-byte elements, which is the shape UiaProvider.int32sOf reads.
            array = MemoryUtil.nmemCallocChecked(1, 32);
            data = MemoryUtil.nmemCallocChecked(2, 4);
            MemoryUtil.memPutShort(array, (short) 1);
            MemoryUtil.memPutInt(array + 4, 4);
            MemoryUtil.memPutAddress(array + 16, data);
            MemoryUtil.memPutInt(array + 24, 2);
            MemoryUtil.memPutInt(data, UiaIds.NAME);
            MemoryUtil.memPutInt(data + 4, UiaIds.ITEM_STATUS);
            UiaCom.PIP added = (UiaCom.PIP) UiaProvider
                    .adviseEventsSlots(bridge.contextForTests()).get("AdviseEventAdded");
            assertEquals(UiaIds.S_OK,
                    added.invoke(0, UiaIds.AUTOMATION_PROPERTY_CHANGED, array));
        } finally {
            MemoryUtil.nmemFree(data);
            MemoryUtil.nmemFree(array);
            bridge.detach();
            UiaTrace.file = beforeFile;
            UiaWindow.trace = beforeHook;
        }

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.get(0).startsWith("=== limn uia trace opened "), lines.get(0));
        String raise = only(lines, "RAISE UiaRaiseAutomationEvent");
        assertEquals("RAISE UiaRaiseAutomationEvent event=AUTOMATION_FOCUS_CHANGED(20005) "
                        + "node=1001 role=BUTTON name=\"Save\" runtimeId=[3,0,1001] element=held "
                        + "hr=0x0(S_OK) thread=limn-a11y-uia-drain",
                raise.replaceFirst("^\\+[0-9.]+ ", ""),
                "the raise line: " + raise);
        assertTrue(raise.matches("^\\+\\d+\\.\\d{3} .*"),
                "every file line is stamped with a monotonic millisecond: " + raise);

        String advise = only(lines, "IN AdviseEventAdded");
        assertEquals("IN AdviseEventAdded event=AUTOMATION_PROPERTY_CHANGED(20004) "
                        + "properties=[NAME(30005),ITEM_STATUS(30026)] hr=0x0(S_OK) thread="
                        + Thread.currentThread().getName(),
                advise.replaceFirst("^\\+[0-9.]+ ", ""),
                "the advise line: " + advise);
    }

    /**
     * The microseconds a raise reports are the platform call's, not the trace's.
     *
     * <p>The bridge has always printed how long a raise took, and ADR 039 §13.28's "2.5 ms median,
     * one of 50 ms" is read off those notes — the reader's own handler runs inside the call. The
     * trace's lines were being written inside the same interval (2026-09-16 review), so turning the
     * instrument on inflated the one latency number this bridge produces, and the guest run had no
     * way to tell the two apart.
     *
     * <p>Measured against a sink that takes a tenth of a second per line, which is not a plausible
     * disk but is the same shape as one and is unmistakable in the number: the reported figure has
     * to stay under half of one line's cost, while the raise's own work here is a no-op call and
     * some model reads. Both shapes are covered — the direct raise (INVOKED, one
     * {@code UiaRaiseAutomationEvent}) and the property path (NAME_CHANGED, which writes two lines
     * of its own before the note carrying the number).
     */
    @Test
    void theMicrosecondsARaiseReportsExcludeWhatTheTraceSpentWritingItDown() {
        long perLineMs = 100;
        List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Consumer<String> before = UiaWindow.trace;
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aFocusedButton(), false);
            bridge.objectFor(1001);
            bridge.noteAsked();
            UiaWindow.trace = line -> {
                said.add(line);
                try {
                    Thread.sleep(perLineMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };

            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVOKED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1001,
                    "Save", "Save as"));

            for (String what : List.of("INVOKED", "NAME_CHANGED")) {
                String note = await(said,
                        l -> l.startsWith("raised " + what + " for node 1001 in "));
                assertNotNull(note, what + " was never raised: " + said);
                long micros = Long.parseLong(note.replaceFirst(".* in (\\d+) us on .*", "$1"));
                assertTrue(micros < perLineMs * 1_000 / 2,
                        "the " + what + " raise reports " + micros + " us, which is the sink's "
                                + perLineMs + " ms a line and not the raise: " + note);
            }
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * A raise's answer carries its {@code HRESULT} back on the caller's stack, whole, and is
     * distinguishable from a raise that never ran.
     *
     * <p>The number used to travel in a volatile field written inside the popup bridge's guard and
     * read after it (2026-09-16 review): two windows' drain threads raising a focus into the same
     * popup bridge (decision 5) could interleave between the write and the read, and the trace
     * would print the other thread's number. It cannot be raced here — this machine has no
     * {@code UIAutomationCore}, so every raise answers {@code S_OK} and no two threads could be
     * told apart — so what is pinned is the mechanism that replaced it: the failures a live raise
     * can answer all survive the round trip, and none of them reads as "it did not run".
     */
    @Test
    void aRaisesHresultTravelsBackWholeAndIsNotTheAnswerForARaiseThatDidNotRun() {
        for (int hresult : new int[] {UiaIds.S_OK, UiaIds.E_ELEMENT_NOT_AVAILABLE,
                UiaIds.E_NO_INTERFACE, UiaIds.E_INVALID_OPERATION, -1, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            long answer = UiaBridge.ran(hresult);
            assertEquals(hresult, UiaBridge.hresultOf(answer),
                    "the HRESULT 0x" + Integer.toHexString(hresult) + " did not survive the trip");
            assertNotEquals(UiaBridge.NOT_RAISED, answer,
                    "a raise that answered 0x" + Integer.toHexString(hresult)
                            + " reads as a raise that never ran");
        }
    }

    /**
     * A property value no file system can be opened for leaves the trace off, says so on standard
     * error, and — the whole point — throws nothing.
     *
     * <p>This routine runs in {@link UiaTrace}'s own initialization, which the first
     * {@code UiaWindow.say} of a window's subclassing triggers on the user-interface thread.
     * Anything thrown out of it leaves {@code UiaWindow.attach} with an
     * {@code ExceptionInInitializerError} and the window never opens: the switch would kill the
     * application it was turned on to diagnose, on the one run the operator has to spend.
     *
     * <p>The three unusable shapes are the ones a command line produces: a character no path may
     * hold (on Windows {@code < > " | ? *} and, everywhere, {@code NUL} — a stray quote or
     * wildcard), a directory where a file was meant, and a file under a directory that is not
     * there. The first is the one that was fatal: {@code InvalidPathException} is an
     * {@code IllegalArgumentException} and no kind of {@code IOException}.
     */
    @Test
    void anUnusablePathLeavesTheTraceOffAndSaysSoInsteadOfThrowing(@TempDir Path dir)
            throws IOException {
        Path missing = dir.resolve("no-such-directory").resolve("uia.log");
        // In the middle of the name and not at its end, where String.trim() would take it off:
        // trim removes everything up to and including the space, this character included.
        String nul = dir + java.io.File.separator + "uia" + (char) 0 + ".log";
        java.io.PrintStream beforeErr = System.err;
        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(said, true, StandardCharsets.UTF_8));
        try {
            assertNull(UiaTrace.opened(nul), "a path holding a character no path may hold");
            assertNull(UiaTrace.opened(dir.toString()), "a directory is not a file to write to");
            assertNull(UiaTrace.opened(missing.toString()), "no directory to write into");
            assertNull(UiaTrace.opened(null), "nothing asked for");
            assertNull(UiaTrace.opened("   "), "nor by an empty -D");
        } finally {
            System.setErr(beforeErr);
        }
        String printed = said.toString(StandardCharsets.UTF_8);
        assertEquals(3, printed.lines().count(), "one line per unusable path: " + printed);
        assertTrue(printed.contains("[uia] cannot write the trace to " + missing),
                "the line names the path the operator typed: " + printed);
        assertTrue(printed.contains("InvalidPathException"),
                "and what was wrong with it: " + printed);
        assertFalse(Files.exists(missing), "nothing was created for a path that cannot be opened");
    }

    /** Everything a client and a scene can make this bridge do, for the silent exercise. */
    private static void exercise(UiaBridge bridge) {
        bridge.publish(aFocusedButton(), false);
        bridge.objectFor(1001);
        bridge.noteAsked();
        bridge.isListening();
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1001,
                "Save", "Save as"));
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVOKED, 1001));
        UiaProvider.Context context = bridge.contextForTests();
        long out = MemoryUtil.nmemCallocChecked(1, UiaVariant.SIZE);
        try {
            UiaObject root = bridge.objectFor(1000);
            UiaObject button = bridge.objectFor(1001);
            call(button, UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE, "GetPropertyValue",
                    UiaIds.NAME, out);
            call(button, UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE, "GetPatternProvider",
                    UiaIds.INVOKE_PATTERN, out);
            call(button, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "Navigate",
                    UiaIds.NAVIGATE_DIRECTION_PARENT, out);
            callNoArgument(button, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT, "get_FragmentRoot",
                    out);
            callNoArgument(root, UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT, "GetFocus", out);
            ((UiaCom.PIP) UiaProvider.adviseEventsSlots(context).get("AdviseEventAdded"))
                    .invoke(0, UiaIds.AUTOMATION_FOCUS_CHANGED, 0);
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    private static void call(UiaObject object, UiaInterfaces.Vtable iface, String slot, int id,
                             long out) {
        long element = object.pointerFor(iface);
        org.lwjgl.system.JNI.invokePPI(element, id, out,
                UiaCom.slotOf(element, 3 + iface.slots().indexOf(slot)));
    }

    private static void callNoArgument(UiaObject object, UiaInterfaces.Vtable iface, String slot,
                                       long out) {
        long element = object.pointerFor(iface);
        org.lwjgl.system.JNI.invokePPI(element, out,
                UiaCom.slotOf(element, 3 + iface.slots().indexOf(slot)));
    }

    /** @return the one line whose text after the stamp starts with {@code prefix} */
    private static String only(List<String> lines, String prefix) {
        List<String> found = lines.stream()
                .filter(l -> l.replaceFirst("^\\+[0-9.]+ ", "").startsWith(prefix)).toList();
        assertEquals(1, found.size(), "expected one " + prefix + " line in " + lines);
        return found.get(0);
    }

    private static String await(List<String> lines, java.util.function.Predicate<String> what) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (lines) {
                for (String line : lines) {
                    if (what.test(line)) {
                        return line;
                    }
                }
            }
            Thread.onSpinWait();
        }
        return null;
    }
}
