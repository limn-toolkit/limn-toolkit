package limn.demo;

import limn.graphics.Image;

/**
 * Pictures the Showcase's cards hold, painted here pixel by pixel: shelves of boxes in a
 * warehouse, hills under a morning sky, a road at dusk, and an oak desk seen from above. Drawn
 * rather than shipped, so the demo carries no photograph and no licence with it.
 */
final class Pictures {

    private static final int W = 640;
    private static final int H = 360;

    /** Shelves of boxes, for a stock count. */
    static final Image STOCK = shelves();
    /** Green hills under a blue morning sky, for sales across regions. */
    static final Image SALES = hills(0x7FB7E6, 0xDDEEFA, 0x5E9E57, 0x3F7A3F, 0xFFF4C2);
    /** A road into the hills at dusk, for delivery routes. */
    static final Image ROUTES = road();
    /** An oak desk top, for a desk on sale. */
    static final Image DESK = desk();

    /** A portrait for a person's avatar: dark hair, a teal backdrop, a navy jacket. */
    static final Image PORTRAIT_RUI = portrait(0x2E7D7A, 0xD9A27A, 0x2B1D16, 0x26365C);
    /** Another: black hair, an amber backdrop, a burgundy top. */
    static final Image PORTRAIT_MEI = portrait(0xC98A2E, 0xEFC9A8, 0x141110, 0x7A2638);

    private Pictures() {
    }

    /**
     * A head and shoulders on a backdrop that darkens toward the foot, drawn on a square of 128
     * pixels: what an avatar's picture is cut from, without a photograph of anyone.
     */
    private static Image portrait(int backdrop, int skin, int hair, int clothes) {
        int size = 128;
        byte[] rgba = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int colour = mix(backdrop, 0x000000, y / (double) size * 0.35);
                double shoulders = Math.pow((x - 64) / 52.0, 2) + Math.pow((y - 136) / 46.0, 2);
                double neck = Math.abs(x - 64) < 12 && y > 70 && y < 100 ? 0 : 2;
                double head = Math.pow((x - 64) / 25.0, 2) + Math.pow((y - 56) / 31.0, 2);
                double hairCap = Math.pow((x - 64) / 28.0, 2) + Math.pow((y - 46) / 26.0, 2);
                if (shoulders < 1) {
                    colour = mix(clothes, 0x000000, (y - 96) / 96.0);
                } else if (head < 1 && y > 42 || neck < 1) {
                    colour = mix(skin, 0x000000, Math.max(0, (x - 64) / 160.0));
                } else if (hairCap < 1 && y < 58) {
                    colour = hair;
                }
                int o = (y * size + x) * 4;
                rgba[o] = (byte) (colour >> 16);
                rgba[o + 1] = (byte) (colour >> 8);
                rgba[o + 2] = (byte) colour;
                rgba[o + 3] = (byte) 0xFF;
            }
        }
        return new Image(size, size, rgba);
    }

    private static void put(byte[] rgba, int x, int y, int rgb) {
        int o = (y * W + x) * 4;
        rgba[o] = (byte) (rgb >> 16);
        rgba[o + 1] = (byte) (rgb >> 8);
        rgba[o + 2] = (byte) rgb;
        rgba[o + 3] = (byte) 0xFF;
    }

    /** {@code a} and {@code b} mixed, {@code t} of the way from one to the other. */
    private static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Two ridges of hills under a sky that pales towards the horizon, a sun low in it. */
    private static Image hills(int sky, int horizon, int far, int near, int sun) {
        byte[] rgba = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double farRidge = H * 0.55 + Math.sin(x * 0.012) * 22 + Math.sin(x * 0.031 + 1) * 9;
                double nearRidge = H * 0.72 + Math.sin(x * 0.008 + 2) * 30 + Math.sin(x * 0.027) * 8;
                int colour;
                if (y > nearRidge) {
                    colour = mix(near, 0x000000, (y - nearRidge) / H * 0.8);
                } else if (y > farRidge) {
                    colour = mix(far, horizon, 0.25 - (y - farRidge) / H);
                } else {
                    colour = mix(sky, horizon, y / farRidge);
                    double fromSun = Math.hypot(x - W * 0.72, y - H * 0.34);
                    if (fromSun < 30) {
                        colour = sun;
                    } else if (fromSun < 80) {
                        colour = mix(sun, colour, (fromSun - 30) / 50);
                    }
                }
                put(rgba, x, y, colour);
            }
        }
        return new Image(W, H, rgba);
    }

    /** A road running into dusk-lit hills. */
    private static Image road() {
        Image base = hills(0x3B2A63, 0xF29E6D, 0x5B4A7A, 0x2D2442, 0xFFD79A);
        byte[] rgba = base.pixels().clone();
        for (int y = (int) (H * 0.6); y < H; y++) {
            double t = (y - H * 0.6) / (H * 0.4);
            double half = 6 + t * W * 0.32;
            double centre = W * 0.42 + t * W * 0.1;
            for (int x = (int) Math.max(0, centre - half); x < Math.min(W, centre + half); x++) {
                int colour = mix(0x3A3A44, 0x22222A, t);
                boolean stripe = Math.abs(x - centre) < 1 + t * 4 && ((int) (Math.pow(t, 0.6) * 18)) % 2 == 0;
                put(rgba, x, y, stripe ? 0xF4E2A1 : colour);
            }
        }
        return new Image(W, H, rgba);
    }

    /** Shelves of cardboard boxes in rows, a concrete floor below them. */
    private static Image shelves() {
        byte[] rgba = new byte[W * H * 4];
        int shelf = H / 3;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int row = y / shelf;
                int inRow = y % shelf;
                int colour = mix(0xC9CDD3, 0x9AA0A8, y / (double) H);
                if (inRow > shelf - 10) {
                    colour = 0x4A5A72; // the shelf's steel
                } else if (inRow > 18) {
                    int box = (x + row * 37) / 90;
                    int inBox = (x + row * 37) % 90;
                    if (inBox > 6 && inBox < 86) {
                        int tone = (box * 7 + row * 3) % 3;
                        int card = tone == 0 ? 0xC79A62 : tone == 1 ? 0xB98A55 : 0xD4A970;
                        colour = mix(card, 0x6E5232, (inRow - 18) / (double) shelf * 0.5);
                        if (Math.abs(inBox - 46) < 3) {
                            colour = mix(colour, 0xE8D3A8, 0.6); // the tape down the middle
                        }
                    }
                }
                if (x % 213 < 8) {
                    colour = 0x3D4B60; // the uprights
                }
                put(rgba, x, y, colour);
            }
        }
        return new Image(W, H, rgba);
    }

    /** An oak top seen from above, its grain running across, a closed laptop on it. */
    private static Image desk() {
        byte[] rgba = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double grain = Math.sin(y * 0.09 + Math.sin(x * 0.011) * 3 + Math.sin(x * 0.043) * 0.6);
                int colour = mix(0xC8955A, 0xA8763F, (grain + 1) / 2 * 0.7);
                boolean laptop = x > W * 0.3 && x < W * 0.7 && y > H * 0.28 && y < H * 0.72;
                if (laptop) {
                    double edge = Math.min(Math.min(x - W * 0.3, W * 0.7 - x), Math.min(y - H * 0.28, H * 0.72 - y));
                    colour = edge < 3 ? 0x8E949C : mix(0xB9BEC5, 0xA3A9B1, (y - H * 0.28) / (H * 0.44));
                }
                put(rgba, x, y, colour);
            }
        }
        return new Image(W, H, rgba);
    }
}
