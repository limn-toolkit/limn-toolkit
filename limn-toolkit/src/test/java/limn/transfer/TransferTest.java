package limn.transfer;

import limn.backend.Clipboard;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a copy or a drag carries (ADR 053 §2): several formats of one item in the order they were
 * added, a lazy value made once and only when asked for, a format of the application's own by
 * name or by reference, and a clipboard of memory that hands back what it was given.
 */
class TransferTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void removeRuntime() {
        ui.close();
    }

    record Order(int number) { }

    private static final DataFormat<Order> ORDER = DataFormat.of("application/x-limn-test-order",
            order -> Integer.toString(order.number()).getBytes(StandardCharsets.UTF_8),
            bytes -> new Order(Integer.parseInt(new String(bytes, StandardCharsets.UTF_8))));

    private static final DataFormat<Order> LIVE_ORDER =
            DataFormat.inProcess("application/x-limn-test-live-order", Order.class);

    @Test
    void aTransferKeepsItsFormatsInTheOrderTheyWereAddedAndIsImmutable() {
        Transfer text = Transfer.of(DataFormat.TEXT, "42");
        Transfer both = text.with(ORDER, new Order(42)).with(DataFormat.HTML, "<b>42</b>");
        assertEquals(List.of(DataFormat.TEXT), List.copyOf(text.formats()), "with answers a new one");
        assertEquals(List.of(DataFormat.TEXT, ORDER, DataFormat.HTML), List.copyOf(both.formats()));
        assertEquals("<b>42</b>", both.get(DataFormat.HTML));
        assertNull(both.get(DataFormat.FILES), "a format it does not have");
        assertTrue(Transfer.EMPTY.isEmpty());

        Transfer replaced = both.with(DataFormat.TEXT, "43");
        assertEquals("43", replaced.get(DataFormat.TEXT));
        assertEquals(DataFormat.TEXT, replaced.formats().iterator().next(), "it keeps its place");
    }

    @Test
    void aLazyValueIsMadeOnceAndOnlyWhenAskedFor() {
        List<String> made = new ArrayList<>();
        Transfer transfer = Transfer.of(DataFormat.TEXT, "title").withLazy(DataFormat.HTML, () -> {
            made.add("html");
            return "<h1>title</h1>";
        });
        assertTrue(transfer.has(DataFormat.HTML));
        assertEquals("title", transfer.get(DataFormat.TEXT));
        assertTrue(made.isEmpty(), "listing and reading another format makes nothing");
        assertEquals("<h1>title</h1>", transfer.get(DataFormat.HTML));
        assertEquals("<h1>title</h1>", transfer.asOffer().read(DataFormat.HTML).toCompletableFuture().join());
        assertEquals(List.of("html"), made);
    }

    @Test
    void anOfferCompletesAtOnceAndFailsForWhatItDoesNotHave() {
        Offer offer = Transfer.of(DataFormat.FILES, List.of(Path.of("a.txt"))).asOffer();
        assertTrue(offer.has(DataFormat.FILES));
        assertFalse(offer.has(DataFormat.TEXT));
        assertEquals(List.of(Path.of("a.txt")), offer.read(DataFormat.FILES).toCompletableFuture().join());
        CompletionException failed = assertThrows(CompletionException.class,
                () -> offer.read(DataFormat.TEXT).toCompletableFuture().join());
        assertInstanceOf(TransferGoneException.class, failed.getCause());
        assertTrue(Offer.EMPTY.formats().isEmpty());
    }

    @Test
    void aNamedFormatIsBytesAndIsFoundAgainByItsName() {
        assertSame(ORDER, DataFormat.declared("application/x-limn-test-order"));
        assertNull(DataFormat.declared("application/x-limn-test-nobody"));
        assertEquals(new Order(7), ORDER.decode(ORDER.encode(new Order(7))));
        assertFalse(ORDER.isStandard());
        assertTrue(DataFormat.IMAGE.isStandard());
        assertThrows(UnsupportedOperationException.class, () -> DataFormat.TEXT.encode("x"));
    }

    @Test
    void anInProcessFormatCarriesTheObjectAndIsNotDeclaredToOthers() {
        Order order = new Order(9);
        Transfer transfer = Transfer.of(LIVE_ORDER, order);
        assertSame(order, transfer.get(LIVE_ORDER), "the reference, not a copy");
        assertTrue(LIVE_ORDER.isInProcess());
        assertNull(DataFormat.declared(LIVE_ORDER.name()), "another program's offer is never it");
        assertThrows(UnsupportedOperationException.class, () -> LIVE_ORDER.encode(order));
        assertNotEquals(LIVE_ORDER, DataFormat.of(LIVE_ORDER.name(), o -> new byte[0], b -> order),
                "the same name carried as bytes is another format");
    }

    @Test
    void aNameIsAMediaTypeAndNotAStandardOne() {
        for (String bad : List.of("", "order", "Application/X-Order", "a/b/c", "a b/c", "/x", "x/",
                "text/plain;charset=utf-8", "text/plain", "image/png")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DataFormat.inProcess(bad, Order.class), bad);
        }
    }

    @Test
    void aClipboardOfMemoryHandsBackWhatItWasGivenAndTellsOfEachChange() {
        Clipboard clipboard = Clipboard.inMemory();
        assertEquals("", clipboard.text());
        assertTrue(clipboard.content().formats().isEmpty());
        int[] changes = {0};
        var listening = clipboard.onChange(() -> changes[0]++);

        clipboard.setText("plain");
        assertEquals("plain", clipboard.text());
        assertEquals(List.of(DataFormat.TEXT), List.copyOf(clipboard.content().formats()));

        clipboard.set(Transfer.of(ORDER, new Order(3)));
        assertEquals("", clipboard.text(), "a transfer with no text leaves no text behind");
        assertEquals(new Order(3), clipboard.content().read(ORDER).toCompletableFuture().join());

        clipboard.setText(null);
        assertEquals("", clipboard.text());
        assertEquals(3, changes[0]);
        listening.cancel();
        listening.cancel();
        clipboard.set(Transfer.EMPTY);
        assertEquals(3, changes[0]);
        assertNotEquals(clipboard.text(), Clipboard.inMemory().text() + "x", "each is its own");
    }

    @Test
    void theFileOfTheTransferBeforeIsDeletedWhenTheNextIsWrittenInTheRunsOwnDirectory() throws Exception {
        limn.graphics.Image image = new limn.graphics.Image(2, 2, new byte[16]);
        java.nio.file.Path first = Transfer.of(DataFormat.IMAGE, image).withImageAsFile("same.png")
                .get(DataFormat.FILES).get(0);
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(first));
        java.nio.file.Path second = Transfer.of(DataFormat.IMAGE, image).withImageAsFile("same.png")
                .get(DataFormat.FILES).get(0);
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(second));
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(first),
                "one picture on the disk at a time, however many were dragged");
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(first.getParent()));
        org.junit.jupiter.api.Assertions.assertEquals(first.getParent().getParent(), second.getParent().getParent(),
                "one directory for the run, deleted whole when the program ends");
        org.junit.jupiter.api.Assertions.assertTrue(second.getParent().getParent().getFileName().toString()
                .startsWith("limn-transfer-" + ProcessHandle.current().pid() + "-"));
    }

    @Test
    void aPictureAtHandIsOfferedAsAFileThatAnyThreadMayWrite() throws Exception {
        Transfer picture = Transfer.of(DataFormat.IMAGE, new limn.graphics.Image(2, 2, new byte[16]));
        org.junit.jupiter.api.Assertions.assertFalse(picture.has(DataFormat.FILES), "not unless asked");
        Transfer withFile = picture.withImageAsFile("logo.png");
        org.junit.jupiter.api.Assertions.assertFalse(withFile.isReady(DataFormat.FILES), "written when asked for");
        org.junit.jupiter.api.Assertions.assertFalse(withFile.needsUiThread(DataFormat.FILES));
        // Off the UI thread: a program asked for its own files while its UI thread waits for them.
        java.util.List<java.nio.file.Path> files = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> withFile.get(DataFormat.FILES))
                .get(20, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals("logo.png", files.get(0).getFileName().toString());
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.size(files.get(0)) > 8);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> Transfer.of(DataFormat.TEXT, "no picture").withImageAsFile("x.png"));
    }
}
