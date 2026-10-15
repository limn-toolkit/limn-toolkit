package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}
