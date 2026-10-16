package limn.graphics;

import limn.graphics.internal.SvgSource;
import limn.io.MalformedContentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a renderer is handed of an SVG (ADR 066 section 6): nothing that runs, nothing that refers
 * outside the file, a picture only as a PNG the toolkit wrote, read from the file's own folder or
 * from the file itself, and a file whose {@code use}s would draw without end refused.
 */
class SvgSourceTest {

    private static final ImageDecoder TEST_PNG = TestPngDecoder::decode;

    @TempDir
    Path directory;

    @BeforeEach
    void install() {
        Images.installDecoder(TEST_PNG);
    }

    @AfterEach
    void uninstall() {
        Images.uninstallDecoder(TEST_PNG);
    }

    private static String clean(String svg, Path folder) {
        return new String(SvgSource.read(svg.getBytes(StandardCharsets.UTF_8), folder).text(), StandardCharsets.UTF_8);
    }

    private static byte[] png() {
        byte[] rgba = new byte[2 * 2 * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) 0xFF;
            rgba[i + 3] = (byte) 0xFF;
        }
        return Images.encode(new Image(2, 2, rgba), ImageFormat.PNG);
    }

    @Test
    void scriptsEventsAndForeignContentAreTakenOut() {
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4' onload='alert(1)'>"
                + "<script>alert(2)</script><foreignObject><div/></foreignObject>"
                + "<rect width='4' height='4' onclick='x()' ONMOUSEOVER='y()'/></svg>", null);
        assertFalse(out.contains("script"), out);
        assertFalse(out.contains("foreignObject"), out);
        assertFalse(out.toLowerCase().contains("on"), out);
        assertTrue(out.contains("<rect width=\"4\" height=\"4\"/>"), out);
    }

    @Test
    void aReferenceInsideTheFileIsKeptAndOneOutsideItIsNot() {
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='4' height='4'>"
                + "<defs><rect id='r' width='1' height='1'/></defs><use href='#r'/><use xlink:href='other.svg#r'/>"
                + "<image href='http://example.com/a.png' width='1' height='1'/>"
                + "<image href='/etc/a.png' width='1' height='1'/></svg>", directory);
        assertTrue(out.contains("<use href=\"#r\"/>"), out);
        assertTrue(out.contains("<use/>"), "another file's element is never read: " + out);
        assertFalse(out.contains("example.com"), out);
        assertFalse(out.contains("/etc"), out);
    }

    @Test
    void aPictureBesideTheFileIsReadAndHandedOnAsAPngTheToolkitWrote() throws Exception {
        Files.write(directory.resolve("pic.png"), png());
        Files.createDirectories(directory.resolve("img"));
        Files.write(directory.resolve("img/deeper.png"), png());
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<image href='pic.png' width='2' height='2'/><image href='img/deeper.png' width='2' height='2'/></svg>", directory);
        int first = out.indexOf("data:image/png;base64,");
        assertTrue(first > 0, out);
        assertTrue(out.indexOf("data:image/png;base64,", first + 1) > 0, "the one in a folder inside too: " + out);
        String encoded = out.substring(first + 22, out.indexOf('"', first));
        Image read = TestPngDecoder.decode(Base64.getDecoder().decode(encoded));
        assertArrayEquals(TestPngDecoder.decode(png()).pixels(), read.pixels());
    }

    @Test
    void aPictureOutsideTheFolderOrFromNoFolderIsNotRead() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("svg"));
        Files.write(directory.resolve("outside.png"), png());
        Files.write(folder.resolve("inside.png"), png());
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<image href='../outside.png' width='2' height='2'/>"
                + "<image href='sub/../../outside.png' width='2' height='2'/>"
                + "<image href='file:outside.png' width='2' height='2'/></svg>", folder);
        assertFalse(out.contains("data:"), out);
        assertFalse(clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<image href='inside.png' width='2' height='2'/></svg>", null).contains("data:"),
                "an SVG from bytes has no folder");
        Files.createSymbolicLink(folder.resolve("link.png"), directory.resolve("outside.png"));
        assertFalse(clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<image href='link.png' width='2' height='2'/></svg>", folder).contains("data:"),
                "a link that leads out of the folder");
    }

    /**
     * The renderer opens a picture's reference as a path whatever it holds: a fragment names a file
     * in the process's folder, and anything var() or an animation writes after the cleaning is
     * looked at again before the renderer is given it.
     */
    @Test
    void aPictureShowsNothingButAPngTheToolkitWroteWhateverRewritesItsReference() {
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='4' height='4'>"
                + "<image href='#/../../outside.png' width='2' height='2'/>"
                + "<image xlink:href='#r' width='2' height='2'/>"
                + "<g style='--p: /../../outside.png'><image href='#var(--p)' width='2' height='2'/></g>"
                + "<image width='2' height='2'><set attributeName='href' to='/etc/outside.png'/></image>"
                + "<rect id='r' width='1' height='1'/><use href='#r'/></svg>", null);
        assertFalse(out.matches("(?s).*<image[^>]*href.*"), out);
        assertTrue(out.contains("<use href=\"#r\"/>"), "a reference to an element of the file stays: " + out);
    }

    @Test
    void anEmbeddedPictureIsReadByTheToolkitAndOneItCannotReadIsDropped() {
        String embedded = "data:image/png;base64," + Base64.getMimeEncoder().encodeToString(png());
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<image href='" + embedded + "' width='2' height='2'/>"
                + "<image href='data:image/png;base64,AAAA' width='2' height='2'/></svg>", null);
        assertTrue(out.contains("data:image/png;base64,iVBOR"), out);
        assertTrue(out.contains("<image width=\"2\" height=\"2\"/>"), "a picture that does not decode is not drawn: " + out);
    }

    @Test
    void usesThatWouldDrawWithoutEndAreRefused() {
        StringBuilder bomb = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'><defs>"
                + "<rect id='l0' width='1' height='1'/>");
        for (int level = 1; level <= 12; level++) {
            bomb.append("<g id='l").append(level).append("'>");
            for (int copy = 0; copy < 10; copy++) {
                bomb.append("<use href='#l").append(level - 1).append("'/>");
            }
            bomb.append("</g>");
        }
        bomb.append("</defs><use href='#l12'/></svg>");
        assertThrows(MalformedContentException.class, () -> clean(bomb.toString(), null), "ten to the twelfth");
        assertThrows(MalformedContentException.class, () -> clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
                + "<g id='a'><use href='#b'/></g><g id='b'><use href='#a'/></g></svg>", null), "a cycle");
    }

    @Test
    void aRootThatIsNoSvgIsRefused() {
        assertThrows(MalformedContentException.class, () -> clean("<html/>", null));
    }

    @Test
    void anSvgIsKnownByItsFirstElementAndNothingElseIsTakenForOne() {
        for (String svg : new String[] {"<svg/>", "<svg xmlns='x'>", "\ufeff <svg>", "<?xml version='1.0'?><!-- c --><svg>",
                "<!DOCTYPE svg PUBLIC 'x' 'y'><svg>"}) {
            assertTrue(SvgSource.looksLikeSvg(svg.getBytes(StandardCharsets.UTF_8)), svg);
        }
        for (String other : new String[] {"", "<svgx/>", "<html><svg/></html>", "GIF89a", "<?xml version='1.0'?><root/>",
                "<!-- never closed"}) {
            assertFalse(SvgSource.looksLikeSvg(other.getBytes(StandardCharsets.UTF_8)), other);
        }
    }

    /**
     * A property that names the one before it four times writes four times as much at each step:
     * eleven steps from a few hundred bytes took seconds, and twelve ran out of the heap, a file
     * inside every ceiling. What substitution writes is held to what the bytes can back.
     */
    @Test
    void customPropertiesThatMultiplyAreRefusedQuickly() {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4' style='--p0: ");
        svg.append("x".repeat(10)).append(';');
        for (int level = 1; level <= 10; level++) {
            String previous = "var(--p" + (level - 1) + ")";
            svg.append("--p").append(level).append(": ").append(previous.repeat(4)).append(';');
        }
        svg.append("'><rect fill='var(--p10)' width='1' height='1'/></svg>");
        long start = System.nanoTime();
        assertThrows(MalformedContentException.class, () -> clean(svg.toString(), null));
        assertTrue(System.nanoTime() - start < 500_000_000L, (System.nanoTime() - start) / 1_000_000 + " ms");
        // A property of empty references writes nothing and still calls itself four times a step.
        String empty = svg.toString().replace("x".repeat(10), "");
        assertThrows(MalformedContentException.class, () -> clean(empty, null));
    }

    @Test
    void customPropertiesAreResolvedAsABrowserResolvesThem() {
        String out = clean("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4' style='--accent: #00f'>"
                + "<style>:root { --edge: 3 } .a { fill: var(--accent); stroke: var(--none) }</style>"
                + "<rect fill='var(--unset, #e7e4f2ff)' width='1' height='1'/>"
                + "<rect fill='var(--accent)' stroke-width='var(--edge)' width='1' height='1'/>"
                + "<g style='--accent: red'><rect fill='var(--accent, blue)' width='1' height='1'/></g>"
                + "<rect fill='var(--nothing)' width='1' height='1'/></svg>", null);
        assertTrue(out.contains("fill=\"#e7e4f2ff\""), "the fallback, when nothing declares it: " + out);
        assertTrue(out.contains("fill=\"#00f\""), "an ancestor's declaration: " + out);
        assertTrue(out.contains("stroke-width=\"3\""), "a sheet's :root: " + out);
        assertTrue(out.contains("fill=\"red\""), "the nearest ancestor's: " + out);
        assertTrue(out.contains("<rect width=\"1\" height=\"1\"/>"), "neither: the property is unset: " + out);
        assertTrue(out.contains(".a { fill:#00f}") || out.contains(".a {fill:#00f}"), "in the sheet too: " + out);
        assertFalse(out.contains("var("), out);
    }
}
