package limn.testfixtures;

import limn.graphics.Image;
import limn.graphics.SvgRenderer;

/**
 * An {@link SvgRenderer} for a test that needs SVG icons to draw without the native renderer: every
 * document is one unit square, so an icon drawn at {@code px} asks {@link Picture} for a square of
 * {@code px}.
 */
public final class FakeSvg {

    /** What a fake document draws at a side. */
    @FunctionalInterface
    public interface Picture {
        /**
         * @param svg  the text the icon was opened with
         * @param side the side asked for, in pixels
         * @return the picture
         */
        Image draw(byte[] svg, int side);
    }

    private FakeSvg() {
    }

    /** @return a renderer drawing every SVG one transparent pixel */
    public static SvgRenderer blank() {
        return renderer((svg, side) -> new Image(1, 1, new byte[4]));
    }

    /**
     * @param picture what each document draws
     * @return a renderer whose documents are unit squares drawn by {@code picture}
     */
    public static SvgRenderer renderer(Picture picture) {
        return svg -> new SvgRenderer.Document() {
            @Override
            public float width() {
                return 1;
            }

            @Override
            public float height() {
                return 1;
            }

            @Override
            public boolean setAttribute(String id, String name, String value) {
                return false;
            }

            @Override
            public Image render(int width, int height) {
                return picture.draw(svg, Math.max(width, height));
            }

            @Override
            public void close() {
            }
        };
    }
}
