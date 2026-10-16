package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code Images.decode} reads an SVG and draws it at its own size, by the backend's renderer (ADR 066 section 4). */
class SvgDecodeTest {

    private final LunaSvgRenderer renderer = new LunaSvgRenderer();

    @BeforeEach
    void install() {
        SvgIcon.installRenderer(renderer);
    }

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRenderer(renderer);
    }

    @Test
    void anSvgIsDecodedAtItsOwnSize() {
        for (String head : new String[] {"", "<?xml version='1.0'?>\n<!-- drawn -->\n", "﻿  "}) {
            Image image = Images.decode((head + "<svg xmlns='http://www.w3.org/2000/svg' width='30' height='12'>"
                    + "<rect width='15' height='12' fill='blue'/></svg>").getBytes(StandardCharsets.UTF_8));
            assertEquals(30, image.width(), head);
            assertEquals(12, image.height());
            assertEquals(255, image.pixels()[(6 * 30 + 3) * 4 + 3] & 0xFF, "the rectangle");
            assertEquals(0, image.pixels()[(6 * 30 + 25) * 4 + 3] & 0xFF, "and nothing beside it");
        }
    }

    @TempDir
    Path directory;

    /**
     * An SVG from bytes may show no picture but one it embeds (ADR 066 section 6), and the
     * renderer opens whatever path an image's reference holds: an animation that moved the
     * reference to a file on the disk drew that file, read here through the real renderer.
     */
    @Test
    void anAnimationCannotShowAPictureFromTheDisk() throws Exception {
        byte[] red = new byte[4 * 4 * 4];
        for (int i = 0; i < red.length; i += 4) {
            red[i] = (byte) 0xFF;
            red[i + 3] = (byte) 0xFF;
        }
        Path picture = directory.resolve("outside.png");
        Files.write(picture, Images.encode(new Image(4, 4, red), ImageFormat.PNG));
        String path = picture.toAbsolutePath().toString();
        for (String body : new String[] {
            "<image width='4' height='4'><set attributeName='href' to='" + path + "'/></image>",
            "<style>@keyframes k { from { href: " + path + " } to { href: " + path + " } }"
                    + " image { animation: k 1s infinite }</style><image width='4' height='4'/>"}) {
            Image image = Images.decode(("<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>" + body
                    + "</svg>").getBytes(StandardCharsets.UTF_8));
            for (int i = 3; i < image.pixels().length; i += 4) {
                assertEquals(0, image.pixels()[i] & 0xFF, body);
            }
        }
    }
}
