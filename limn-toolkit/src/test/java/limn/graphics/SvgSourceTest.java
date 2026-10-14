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
}
