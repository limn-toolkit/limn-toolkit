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

    /**
     * Every member of the four interfaces a client calls into says that it was called — which is
     * what makes the absence of an {@code IN} line evidence of anything.
     *
     * <p>The decisive negative reading a live run takes from this file is "NVDA's process never
     * reached this provider". Until the 2026-09-16 review that reading was unsound: identity
     * ({@code GetRuntimeId}), geometry ({@code get_BoundingRectangle}), the options every element
     * is asked for first and the client's own {@code SetFocus} all answered in silence, so a reader
     * that arrived and walked only those left an empty column. This is the ratchet: a member added
     * to any of these four interfaces without a line of its own fails here rather than quietly
     * widening the silence again.
     *
     * <p>The pattern members are deliberately not in it — some thirty-five slots across
     * {@code UiaPatternProviders}, which are what a client calls once it has decided to act. What
     * the file's {@code IN} column means is therefore exactly "the members of
     * {@code IRawElementProviderSimple}, {@code Fragment}, {@code FragmentRoot} and
     * {@code AdviseEvents}", and the next reading should say so rather than read silence as
     * absence.
     */
    @Test
    void everyMemberOfTheFourInboundInterfacesSaysThatItWasCalled() {
        List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Consumer<String> before = UiaWindow.trace;
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        long out = MemoryUtil.nmemCallocChecked(1, 64);
        try {
            bridge.publish(aFocusedButton(), false);
            UiaProvider.Context context = bridge.contextForTests();
            java.util.Map<String, org.lwjgl.system.CallbackI> slots = new java.util.TreeMap<>();
            slots.putAll(UiaProvider.simpleSlots(1001, context));
            slots.putAll(UiaProvider.fragmentSlots(1001, context));
            slots.putAll(UiaProvider.fragmentRootSlots(context));
            slots.putAll(UiaProvider.adviseEventsSlots(context));
            assertEquals(14, slots.size(), "the four interfaces' members: " + slots.keySet());
            UiaWindow.trace = said::add;
            for (java.util.Map.Entry<String, org.lwjgl.system.CallbackI> slot : slots.entrySet()) {
                said.clear();
                callSlot(slot.getValue(), out);
                assertTrue(said.stream().anyMatch(l -> l.startsWith("IN " + slot.getKey() + ' ')),
                        "a client called " + slot.getKey() + " and the trace says nothing: " + said);
            }
        } finally {
            UiaWindow.trace = before;
            MemoryUtil.nmemFree(out);
            bridge.detach();
        }
    }

    /**
     * The quiet mode drops a client's reads of one node and keeps what decides something —
     * including every failing return.
     *
     * <p>An NVDA read of one node is dozens of {@code GetPropertyValue} calls, each a flushed line
     * through one {@code PrintWriter} whose {@code println} is synchronized, so the drain thread's
     * {@code RAISE} queues behind the RPC threads' disk writes and the instrument perturbs the
     * scheduling of the very thread the 1-in-14 race turns on (2026-09-16 review). The volume and
     * the decisions are separate switches for that reason. The default stays everything: a client
     * can arrive and read without ever subscribing, and a file that is quiet by default cannot
     * answer the arrival question this trace exists for.
     */
    @Test
    void theQuietModeDropsAClientsReadsAndKeepsItsDecisionsAndEveryFailure() {
        List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Consumer<String> before = UiaWindow.trace;
        boolean beforeReads = UiaTrace.reads;
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        long out = MemoryUtil.nmemCallocChecked(1, 64);
        try {
            bridge.publish(aFocusedButton(), false);
            UiaProvider.Context context = bridge.contextForTests();
            org.lwjgl.system.CallbackI read =
                    UiaProvider.simpleSlots(1001, context).get("GetPropertyValue");
            org.lwjgl.system.CallbackI readOfAGoneNode =
                    UiaProvider.simpleSlots(4242, context).get("GetPropertyValue");
            org.lwjgl.system.CallbackI advise =
                    UiaProvider.adviseEventsSlots(context).get("AdviseEventAdded");
            org.lwjgl.system.CallbackI act = UiaProvider.fragmentSlots(1001, context).get("SetFocus");
            UiaWindow.trace = said::add;

            UiaTrace.reads = false;
            callSlot(read, out);
            assertEquals(List.of(), inbound(said),
                    "a read that answered is the volume this mode is for: " + said);
            callSlot(readOfAGoneNode, out);
            callSlot(advise, out);
            callSlot(act, out);
            List<String> kept = inbound(said);
            assertEquals(3, kept.size(), "a failure, a subscription and a client acting: " + said);
            assertTrue(kept.get(0).startsWith("IN GetPropertyValue "), kept.get(0));
            assertTrue(kept.get(0).contains("hr=0x80040201(E_ELEMENT_NOT_AVAILABLE)"), kept.get(0));
            assertTrue(kept.get(1).startsWith("IN AdviseEventAdded "), kept.get(1));
            assertTrue(kept.get(2).startsWith("IN SetFocus "), kept.get(2));

            said.clear();
            UiaTrace.reads = true;
            callSlot(read, out);
            assertEquals(1, inbound(said).size(),
                    "and with the whole half on, the read says so: " + said);
            assertTrue(inbound(said).get(0).startsWith("IN GetPropertyValue "), said.toString());
        } finally {
            UiaTrace.reads = beforeReads;
            UiaWindow.trace = before;
            MemoryUtil.nmemFree(out);
            bridge.detach();
        }
    }

    /**
     * The four formatters no assertion reached: the enumerators a line names by hand.
     *
     * <p>The identifier spaces come from {@link UiaIds} itself, so a constant renamed there is
     * renamed in the file and {@code UiaConstantsTest} pins the numbers. These four do not: they
     * are switches written out here, and a case pointing at the wrong name would be read as the
     * platform having done something it did not (2026-09-16 review). Each is checked against the
     * constant it names rather than against a number typed twice.
     */
    @Test
    void theEnumeratorsALineNamesByHandAreNamedRight() {
        assertEquals("direction=PARENT(" + UiaIds.NAVIGATE_DIRECTION_PARENT + ')',
                UiaTrace.direction(UiaIds.NAVIGATE_DIRECTION_PARENT));
        assertEquals("direction=NEXT_SIBLING(" + UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING + ')',
                UiaTrace.direction(UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals("direction=PREVIOUS_SIBLING("
                        + UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING + ')',
                UiaTrace.direction(UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING));
        assertEquals("direction=FIRST_CHILD(" + UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD + ')',
                UiaTrace.direction(UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
        assertEquals("direction=LAST_CHILD(" + UiaIds.NAVIGATE_DIRECTION_LAST_CHILD + ')',
                UiaTrace.direction(UiaIds.NAVIGATE_DIRECTION_LAST_CHILD));
        assertEquals("direction=UNKNOWN(9)", UiaTrace.direction(9),
                "a direction this platform does not have is still printed, as its number");

        assertEquals("change=CHILD_ADDED(" + UiaIds.STRUCTURE_CHANGE_CHILD_ADDED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILD_ADDED));
        assertEquals("change=CHILD_REMOVED(" + UiaIds.STRUCTURE_CHANGE_CHILD_REMOVED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILD_REMOVED));
        assertEquals("change=CHILDREN_INVALIDATED("
                        + UiaIds.STRUCTURE_CHANGE_CHILDREN_INVALIDATED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILDREN_INVALIDATED));
        assertEquals("change=CHILDREN_BULK_ADDED("
                        + UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_ADDED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_ADDED));
        assertEquals("change=CHILDREN_BULK_REMOVED("
                        + UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED));
        assertEquals("change=CHILDREN_REORDERED("
                        + UiaIds.STRUCTURE_CHANGE_CHILDREN_REORDERED + ')',
                UiaTrace.structureChange(UiaIds.STRUCTURE_CHANGE_CHILDREN_REORDERED));
        assertEquals("change=UNKNOWN(99)", UiaTrace.structureChange(99));

        // The pairing an announcement is raised with, which is the one this bridge chooses
        // (notificationFor): Other, and a processing by politeness.
        assertEquals("kind=OTHER(" + UiaIds.NOTIFICATION_KIND_OTHER + ") processing=ALL("
                        + UiaIds.NOTIFICATION_PROCESSING_ALL + ')',
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_OTHER,
                        UiaIds.NOTIFICATION_PROCESSING_ALL));
        assertEquals("kind=ITEM_ADDED(" + UiaIds.NOTIFICATION_KIND_ITEM_ADDED
                        + ") processing=IMPORTANT_MOST_RECENT("
                        + UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT + ')',
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_ITEM_ADDED,
                        UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT));
        assertEquals("kind=ITEM_REMOVED(" + UiaIds.NOTIFICATION_KIND_ITEM_REMOVED
                        + ") processing=CURRENT_THEN_MOST_RECENT("
                        + UiaIds.NOTIFICATION_PROCESSING_CURRENT_THEN_MOST_RECENT + ')',
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_ITEM_REMOVED,
                        UiaIds.NOTIFICATION_PROCESSING_CURRENT_THEN_MOST_RECENT));
        assertEquals("kind=ACTION_COMPLETED(" + UiaIds.NOTIFICATION_KIND_ACTION_COMPLETED
                        + ") processing=IMPORTANT_ALL("
                        + UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_ALL + ')',
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_ACTION_COMPLETED,
                        UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_ALL));
        assertEquals("kind=ACTION_ABORTED(" + UiaIds.NOTIFICATION_KIND_ACTION_ABORTED
                        + ") processing=MOST_RECENT("
                        + UiaIds.NOTIFICATION_PROCESSING_MOST_RECENT + ')',
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_ACTION_ABORTED,
                        UiaIds.NOTIFICATION_PROCESSING_MOST_RECENT));
        assertEquals("kind=UNKNOWN(77) processing=IMPORTANT_CURRENT_THEN_MOST_RECENT("
                        + UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_CURRENT_THEN_MOST_RECENT + ')',
                UiaTrace.notification(77,
                        UiaIds.NOTIFICATION_PROCESSING_IMPORTANT_CURRENT_THEN_MOST_RECENT));
        assertEquals("kind=OTHER(" + UiaIds.NOTIFICATION_KIND_OTHER + ") processing=UNKNOWN(88)",
                UiaTrace.notification(UiaIds.NOTIFICATION_KIND_OTHER, 88));
    }

    /**
     * What a property change's two values print as, which is the other formatter no assertion
     * reached — including the absent value, whose word has to be the one the raise itself writes
     * for it.
     */
    @Test
    void aPropertyChangesValuesPrintAsTheClientWouldHaveThem() {
        assertEquals("none", UiaTrace.value(null),
                "which is the VT_EMPTY the raise writes for an absent value");
        assertEquals("\"Save as\"", UiaTrace.value("Save as"));
        assertEquals("true", UiaTrace.value(Boolean.TRUE));
        assertEquals("7.5", UiaTrace.value(7.5));
        assertEquals("1", UiaTrace.value(1));
        String long42 = "x".repeat(UiaTrace.STRING_LIMIT + 2);
        assertEquals('"' + "x".repeat(UiaTrace.STRING_LIMIT) + "…\"", UiaTrace.value(long42),
                "cut to the limit and said to be cut, so no line carries a whole document");
        assertEquals("\"two lines\"", UiaTrace.value("two\nlines"),
                "a value with a newline in it must not break the one-line-per-event shape");
        assertEquals("null", UiaTrace.text(null),
                "and a string that is absent is not the same as one that is empty");
        assertEquals("\"\"", UiaTrace.text(""));
    }

    /** @return the {@code IN} lines of what was said, in order, without the bridge's own notes */
    private static List<String> inbound(List<String> said) {
        synchronized (said) {
            return said.stream().filter(l -> l.startsWith("IN ")).toList();
        }
    }

    /** What the property says, and what an unreadable value falls back to. */
    @Test
    void onlyTheWordDecisionsNarrowsTheInboundHalf() {
        java.io.PrintStream beforeErr = System.err;
        java.io.ByteArrayOutputStream complained = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(complained, true, StandardCharsets.UTF_8));
        try {
            assertTrue(UiaTrace.readsFromProperty(null), "nothing asked for is everything");
            assertTrue(UiaTrace.readsFromProperty("all"));
            assertTrue(UiaTrace.readsFromProperty(" ALL "));
            assertFalse(UiaTrace.readsFromProperty("decisions"));
            assertFalse(UiaTrace.readsFromProperty(" Decisions "));
            assertTrue(UiaTrace.readsFromProperty("decision"),
                    "a value nobody recognises writes more rather than less");
        } finally {
            System.setErr(beforeErr);
        }
        assertTrue(complained.toString(StandardCharsets.UTF_8)
                        .contains("is neither all nor decisions"),
                "and says so, because writing less than was asked for is the one failure this "
                        + "switch can have that nothing downstream reveals: " + complained);
    }

    /**
     * Calls one slot through the Java interface its vtable entry implements, with arguments no
     * member can refuse to answer for: a zeroed out parameter big enough for the widest of them,
     * and {@code 0} wherever the member takes an identifier or a coordinate.
     */
    private static void callSlot(org.lwjgl.system.CallbackI slot, long out) {
        if (slot instanceof UiaCom.P self) {
            self.invoke(0);
        } else if (slot instanceof UiaCom.PP two) {
            two.invoke(0, out);
        } else if (slot instanceof UiaCom.PIP three) {
            three.invoke(0, 0, out);
        } else if (slot instanceof UiaCom.PDDP four) {
            four.invoke(0, 0, 0, out);
        } else {
            org.junit.jupiter.api.Assertions.fail("no way to call a " + slot.getClass());
        }
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
