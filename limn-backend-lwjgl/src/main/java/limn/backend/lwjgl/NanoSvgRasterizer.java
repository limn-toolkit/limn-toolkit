package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.graphics.SvgRasterizer;
import org.lwjgl.nanovg.NSVGImage;
import org.lwjgl.nanovg.NanoSVG;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * SVG icon rasterizer backed by NanoSVG (bundled with LWJGL's {@code nanovg}
 * module). Parses the SVG and rasterizes it to straight-alpha RGBA8 on the CPU
 * (no AWT, no GL context), so the result feeds the normal {@code Image → texture}
 * path and can be tinted per theme. Lightweight: handles path-based icons (the
 * common case); NanoSVG does not implement full SVG (no filters/text).
 */
final class NanoSvgRasterizer implements SvgRasterizer {

    @Override
    public Image rasterize(byte[] svgBytes, int pixelSize) {
        int box = Math.max(1, pixelSize);
        // NanoSVG parses destructively and needs a NUL-terminated copy of the text.
        ByteBuffer input = memAlloc(svgBytes.length + 1);
        input.put(svgBytes).put((byte) 0).flip();
        NSVGImage svg;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            svg = NanoSVG.nsvgParse(input, stack.ASCII("px"), 96f);
        } finally {
            memFree(input);
        }
        if (svg == null) {
            throw new IllegalArgumentException("invalid SVG");
        }
        try {
            float w = svg.width();
            float h = svg.height();
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("SVG has no intrinsic size");
            }
            float scale = box / Math.max(w, h);
            int outW = Math.max(1, Math.round(w * scale));
            int outH = Math.max(1, Math.round(h * scale));
            long rasterizer = NanoSVG.nsvgCreateRasterizer();
            ByteBuffer dst = memAlloc(outW * outH * 4);
            try {
                NanoSVG.nsvgRasterize(rasterizer, svg, 0f, 0f, scale, dst, outW, outH, outW * 4);
                byte[] rgba = new byte[outW * outH * 4];
                dst.get(rgba);
                return new Image(outW, outH, rgba);
            } finally {
                memFree(dst);
                NanoSVG.nsvgDeleteRasterizer(rasterizer);
            }
        } finally {
            NanoSVG.nsvgDelete(svg);
        }
    }
}
