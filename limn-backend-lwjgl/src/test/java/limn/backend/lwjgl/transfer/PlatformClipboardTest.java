package limn.backend.lwjgl.transfer;

import limn.testing.HeadlessUi;
import limn.transfer.DataFormat;
import limn.transfer.Offer;
import limn.transfer.Transfer;
import limn.transfer.TransferGoneException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clipboard an application sees over a system's (ADR 053 §2 and §3), against a system that is
 * a map: what a transfer becomes for another program, what another program's offer becomes for
 * the application, and what is spared while this process is the owner.
 */
class PlatformClipboardTest {

    private static final DataFormat<String> NOTE = DataFormat.of("application/x-limn-test-note",
            note -> note.getBytes(StandardCharsets.UTF_8), bytes -> new String(bytes, StandardCharsets.UTF_8));
    private static final DataFormat<Object> LIVE =
            DataFormat.inProcess("application/x-limn-test-live", Object.class);

    /** A system clipboard that is memory, with another program that can take it over. */
    private static final class FakeSystem implements NativeClipboard {
        boolean owner;
        Map<String, byte[]> others = new LinkedHashMap<>();
        List<String> ownTypes = List.of();
        Function<String, byte[]> ownBytes = type -> null;
        Runnable listener = () -> { };
        final List<String> reads = new ArrayList<>();

        void otherProgramCopies(Map<String, byte[]> content) {
            owner = false;
            others = content;
            listener.run();
        }

        @Override
        public boolean isOwner() {
            return owner;
        }

        @Override
        public List<String> types() {
            return owner ? ownTypes : List.copyOf(others.keySet());
        }

        @Override
        public CompletableFuture<byte[]> read(String type) {
            reads.add(type);
            byte[] bytes = owner ? ownBytes.apply(type) : others.get(type);
            return bytes == null ? CompletableFuture.failedFuture(new TransferGoneException(type))
                    : CompletableFuture.completedFuture(bytes);
        }

        @Override
        public void offer(List<String> types, Function<String, byte[]> bytes) {
            owner = true;
            ownTypes = types;
            ownBytes = bytes;
            listener.run();
        }

        @Override
        public void setChangeListener(Runnable newListener) {
            listener = newListener;
        }

        @Override
        public void close() {
        }
    }

    private HeadlessUi ui;
    private FakeSystem system;
    private PlatformClipboard clipboard;

    @BeforeEach
    void open() {
        ui = new HeadlessUi();
        system = new FakeSystem();
        clipboard = new PlatformClipboard(system, ui.runtime());
    }

    @AfterEach
    void close() {
        clipboard.close();
        ui.close();
    }

    private <T> T read(Offer offer, DataFormat<T> format) throws Exception {
        CompletableFuture<T> stage = offer.read(format).toCompletableFuture();
        ui.runtime().drain();
        return stage.get();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aTransferBecomesTypesAndBytesOnlyWhenAProgramAsks() {
        List<String> made = new ArrayList<>();
        clipboard.set(Transfer.of(DataFormat.TEXT, "título")
                .withLazy(DataFormat.HTML, () -> {
                    made.add("html");
                    return "<b>título</b>";
                })
                .with(DataFormat.FILES, List.of(Path.of("/tmp/a b.txt")))
                .with(NOTE, "n")
                .with(LIVE, new Object()));
        assertEquals(List.of(ClipboardTypes.TEXT, ClipboardTypes.HTML, ClipboardTypes.FILES,
                NOTE.name()), system.ownTypes, "an in-process format is not announced");
        assertTrue(made.isEmpty());

        assertArrayEquals(utf8("título"), system.ownBytes.apply(ClipboardTypes.TEXT));
        assertArrayEquals(utf8("<b>título</b>"), system.ownBytes.apply(ClipboardTypes.HTML));
        assertArrayEquals(utf8("<b>título</b>"), system.ownBytes.apply(ClipboardTypes.HTML));
        assertEquals(List.of("html"), made, "made once, however often it is asked for");
        assertEquals("file:///tmp/a%20b.txt\r\n",
                new String(system.ownBytes.apply(ClipboardTypes.FILES), StandardCharsets.US_ASCII));
        assertArrayEquals(utf8("n"), system.ownBytes.apply(NOTE.name()));
        assertNull(system.ownBytes.apply("application/x-nobody"));
        assertNull(system.ownBytes.apply(LIVE.name()));
    }

    @Test
    void whileThisProcessOwnsTheClipboardItReadsItsOwnTransferWithoutTheSystem() throws Exception {
        Object live = new Object();
        clipboard.set(Transfer.of(DataFormat.TEXT, "mine").with(LIVE, live));
        assertEquals("mine", clipboard.text());
        Offer offer = clipboard.content();
        assertTrue(offer.has(LIVE));
        assertEquals(live, read(offer, LIVE));
        assertTrue(system.reads.isEmpty(), "nothing was asked of the system");
    }

    @Test
    void anotherProgramsOfferIsFormatsAndValuesArriveOnTheUiThread() throws Exception {
        Map<String, byte[]> content = new LinkedHashMap<>();
        content.put(ClipboardTypes.HTML, utf8("<i>x</i>"));
        content.put(ClipboardTypes.TEXT, utf8("x\0"));
        content.put(ClipboardTypes.FILES, utf8("# a comment\r\nfile:///etc/host%20name\r\nhttp://example.org/\r\n"));
        content.put(NOTE.name(), utf8("theirs"));
        content.put("application/x-undeclared", utf8("?"));
        system.otherProgramCopies(content);

        assertEquals("x", clipboard.text(), "a C string's terminator is not text");
        Offer offer = clipboard.content();
        assertEquals(List.of(DataFormat.HTML, DataFormat.TEXT, DataFormat.FILES, NOTE),
                List.copyOf(offer.formats()), "in the source's order, and only what is declared");
        CompletableFuture<String> html = offer.read(DataFormat.HTML).toCompletableFuture();
        assertFalse(html.isDone(), "the value arrives in a task of the UI thread, not inside read");
        ui.runtime().drain();
        assertEquals("<i>x</i>", html.get());
        assertEquals(List.of(Path.of("/etc/host name")), read(offer, DataFormat.FILES));
        assertEquals("theirs", read(offer, NOTE));
    }

    @Test
    void whatWasReplacedMeanwhileFailsAsGone() {
        system.otherProgramCopies(Map.of(ClipboardTypes.TEXT, utf8("soon gone")));
        Offer offer = clipboard.content();
        system.otherProgramCopies(Map.of(ClipboardTypes.HTML, utf8("<p>new</p>")));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> read(offer, DataFormat.TEXT));
        assertInstanceOf(TransferGoneException.class, failed.getCause());
        ExecutionException never = assertThrows(ExecutionException.class, () -> read(offer, DataFormat.IMAGE));
        assertInstanceOf(TransferGoneException.class, never.getCause());
        assertEquals("", clipboard.text(), "no text there now");
    }

    @Test
    void aChangeIsHeardOnTheUiThreadAndLosingTheClipboardLetsTheTransferGo() {
        int[] changes = {0};
        var listening = clipboard.onChange(() -> changes[0]++);
        clipboard.setText("mine");
        assertEquals(0, changes[0], "posted, not called inside set");
        ui.runtime().drain();
        assertEquals(1, changes[0]);

        system.otherProgramCopies(Map.of(ClipboardTypes.TEXT, utf8("theirs")));
        ui.runtime().drain();
        assertEquals(2, changes[0]);
        assertEquals("theirs", clipboard.text());

        listening.cancel();
        clipboard.setText("again");
        ui.runtime().drain();
        assertEquals(2, changes[0]);
    }
}
