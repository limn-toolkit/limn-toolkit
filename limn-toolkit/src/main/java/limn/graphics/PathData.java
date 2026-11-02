package limn.graphics;

/**
 * Reads SVG path data into a {@link Path2D}: the grammar of SVG 1.1's {@code d} attribute, every
 * command, with the smooth curves' reflected control points and the arc's flags written without a
 * separator ({@code a1 1 0 00 10 10}).
 */
final class PathData {

    private final String data;
    private int at;
    private final Path2D path = new Path2D();
    private float curX;
    private float curY;
    private float startX;
    private float startY;
    /** The second control point of the last cubic, or the control point of the last quadratic. */
    private float ctrlX;
    private float ctrlY;
    /** The last command, in capitals: what {@code S} and {@code T} reflect from. */
    private char last;
    private boolean closed;

    private PathData(String data) {
        this.data = data;
    }

    static Path2D parse(String data) {
        return new PathData(data).read();
    }

    private Path2D read() {
        skipSeparators();
        if (at == data.length()) {
            return path;
        }
        char first = data.charAt(at);
        if (first != 'M' && first != 'm') {
            throw error("path data must start with a move");
        }
        char command = 0;
        while (true) {
            skipSeparators();
            if (at == data.length()) {
                return path;
            }
            char c = data.charAt(at);
            if (isCommand(c)) {
                command = c;
                at++;
            } else if (command == 0 || command == 'Z' || command == 'z') {
                throw error("expected a command");
            } else if (command == 'M') {
                command = 'L'; // the pairs after a move's first are lines
            } else if (command == 'm') {
                command = 'l';
            }
            run(command);
        }
    }

    private void run(char command) {
        boolean rel = Character.isLowerCase(command);
        char upper = Character.toUpperCase(command);
        if (upper != 'M' && upper != 'Z' && closed) {
            // A command after a close starts a subpath where the closed one started.
            path.moveTo(startX, startY);
        }
        closed = false;
        float ox = rel ? curX : 0;
        float oy = rel ? curY : 0;
        switch (upper) {
            case 'M' -> {
                curX = ox + number();
                curY = oy + number();
                startX = curX;
                startY = curY;
                path.moveTo(curX, curY);
            }
            case 'L' -> line(ox + number(), oy + number());
            case 'H' -> line(ox + number(), curY);
            case 'V' -> line(curX, oy + number());
            case 'C' -> {
                float c1x = ox + number();
                float c1y = oy + number();
                cubic(c1x, c1y, ox + number(), oy + number(), ox + number(), oy + number());
            }
            case 'S' -> {
                boolean follows = last == 'C' || last == 'S';
                float c1x = follows ? 2 * curX - ctrlX : curX;
                float c1y = follows ? 2 * curY - ctrlY : curY;
                cubic(c1x, c1y, ox + number(), oy + number(), ox + number(), oy + number());
            }
            case 'Q' -> {
                float cx = ox + number();
                float cy = oy + number();
                quad(cx, cy, ox + number(), oy + number());
            }
            case 'T' -> {
                boolean follows = last == 'Q' || last == 'T';
                quad(follows ? 2 * curX - ctrlX : curX, follows ? 2 * curY - ctrlY : curY,
                        ox + number(), oy + number());
            }
            case 'A' -> {
                float rx = number();
                float ry = number();
                float rotation = (float) Math.toRadians(number());
                boolean large = flag();
                boolean sweep = flag();
                float x = ox + number();
                float y = oy + number();
                path.ellipticalArcTo(rx, ry, rotation, large, sweep, x, y);
                curX = x;
                curY = y;
            }
            case 'Z' -> {
                path.close();
                curX = startX;
                curY = startY;
                closed = true;
            }
            default -> throw new AssertionError(command);
        }
        last = upper;
    }

    private void line(float x, float y) {
        path.lineTo(x, y);
        curX = x;
        curY = y;
    }

    private void cubic(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        path.cubicTo(c1x, c1y, c2x, c2y, x, y);
        ctrlX = c2x;
        ctrlY = c2y;
        curX = x;
        curY = y;
    }

    private void quad(float cx, float cy, float x, float y) {
        path.quadTo(cx, cy, x, y);
        ctrlX = cx;
        ctrlY = cy;
        curX = x;
        curY = y;
    }

    private float number() {
        skipSeparators();
        int begin = at;
        int n = data.length();
        if (at < n && (data.charAt(at) == '+' || data.charAt(at) == '-')) {
            at++;
        }
        int digits = 0;
        while (at < n && isDigit(data.charAt(at))) {
            at++;
            digits++;
        }
        if (at < n && data.charAt(at) == '.') {
            at++;
            while (at < n && isDigit(data.charAt(at))) {
                at++;
                digits++;
            }
        }
        if (digits == 0) {
            at = begin;
            throw error("expected a number");
        }
        if (at < n && (data.charAt(at) == 'e' || data.charAt(at) == 'E')) {
            int mark = at;
            at++;
            if (at < n && (data.charAt(at) == '+' || data.charAt(at) == '-')) {
                at++;
            }
            int exponent = 0;
            while (at < n && isDigit(data.charAt(at))) {
                at++;
                exponent++;
            }
            if (exponent == 0) {
                at = mark; // an 'e' with no digits is not part of the number
            }
        }
        float value = Float.parseFloat(data.substring(begin, at));
        if (!Float.isFinite(value)) {
            at = begin;
            throw error("number out of range");
        }
        return value;
    }

    private boolean flag() {
        skipSeparators();
        if (at < data.length()) {
            char c = data.charAt(at);
            if (c == '0' || c == '1') {
                at++;
                return c == '1';
            }
        }
        throw error("expected an arc flag, 0 or 1");
    }

    private void skipSeparators() {
        int n = data.length();
        while (at < n) {
            char c = data.charAt(at);
            if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                at++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at index " + at + " of path data \""
                + (data.length() > 80 ? data.substring(0, 77) + "..." : data) + "\"");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0;
    }
}
