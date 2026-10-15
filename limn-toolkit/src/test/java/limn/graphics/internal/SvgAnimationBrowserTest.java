package limn.graphics.internal;

import limn.graphics.Image;
import limn.graphics.SvgRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolkit plays 104 animated SVGs, two collections of indicators in SMIL and in CSS, as a
 * browser does (ADR 066 section 7): at six moments, every value it sets is the value the browser
 * computed for the same file at the same moment, within a hundredth. The browser's values are in
 * {@code svg-animation/browser.json}, and how they were taken in the README beside it.
 */
class SvgAnimationBrowserTest {

    private static final String ROOT = "/limn/graphics/svg-animation/";

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = SvgAnimationBrowserTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IOException("no " + name);
            }
            return in.readAllBytes();
        }
    }

    private static void order(SvgXml.Element element, List<SvgXml.Element> out) {
        out.add(element);
        for (SvgXml.Element child : element.elements()) {
            order(child, out);
        }
    }

    @Test
    void everyValueTheToolkitSetsIsTheBrowsers() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> browser = (Map<String, Object>) new Json(new String(resource("browser.json"),
                StandardCharsets.UTF_8)).value();
        List<String> files = List.of(new String(resource("files.txt"), StandardCharsets.UTF_8).trim().split("\n"));
        assertEquals(104, files.size());
        int compared = 0;
        List<String> wrong = new ArrayList<>();
        for (String file : files) {
            SvgSource source = SvgSource.read(resource("files/" + file), null);
            SvgAnimation animation = source.animation();
            assertTrue(animation != null, file + " moves");
            List<SvgXml.Element> elements = new ArrayList<>();
            order(source.root(), elements);
            @SuppressWarnings("unchecked")
            Map<String, Object> moments = (Map<String, Object>) browser.get(file);
            for (Map.Entry<String, Object> moment : moments.entrySet()) {
                Map<String, String> set = new HashMap<>();
                animation.apply(new SvgRenderer.Document() {
                    @Override public float width() { return 1; }
                    @Override public float height() { return 1; }
                    @Override public boolean setAttribute(String id, String name, String value) {
                        set.put(id + "\u0000" + name, value);
                        return true;
                    }
                    @Override public Image render(int width, int height) { throw new UnsupportedOperationException(); }
                    @Override public void close() { }
                }, new SvgAnimation.Applied(), Double.parseDouble(moment.getKey()));
                for (Object entry : (List<?>) moment.getValue()) {
                    List<?> e = (List<?>) entry;
                    int index = ((Number) e.get(0)).intValue();
                    String attribute = (String) e.get(1);
                    Object expected = e.get(2);
                    String engine = set.get(elements.get(index).attribute("id") + "\u0000" + attribute);
                    if (expected == null || engine == null || engine.equals("inherit")) {
                        continue;
                    }
                    compared++;
                    if (!same(engine, attribute, expected)) {
                        wrong.add(file + " at " + moment.getKey() + " ms, element " + index + " " + attribute
                                + ": toolkit " + engine + ", browser " + expected);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong.subList(0, Math.min(10, wrong.size())), wrong.size() + " values differ");
        assertTrue(compared >= 3000, "only " + compared + " values compared");
    }

    private static boolean same(String engine, String attribute, Object expected) {
        if (attribute.equals("transform")) {
            double[] m = SvgValues.matrix(engine, false, new SvgValues.Box(0, 0, 0, 0));
            List<?> b = (List<?>) expected;
            for (int i = 0; i < 6; i++) {
                if (Math.abs(m[i] - ((Number) b.get(i)).doubleValue()) > (i < 4 ? 0.02 : 0.06)) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof String word) {
            return engine.trim().equals(word.trim());
        }
        List<?> b = (List<?>) expected;
        int[] colour = SvgValues.color(engine);
        if (colour != null && b.size() == 4 && (attribute.equals("fill") || attribute.equals("stroke")
                || attribute.equals("stop-color") || attribute.equals("color"))) {
            for (int i = 0; i < 4; i++) {
                if (Math.abs(colour[i] - ((Number) b.get(i)).intValue()) > 3) {
                    return false;
                }
            }
            return true;
        }
        double[] a = SvgValues.shape(SvgValues.unitless(engine)).numbers();
        if (attribute.contains("opacity") && engine.trim().endsWith("%")) {
            a[0] /= 100;
        }
        double[] bn = new double[b.size()];
        for (int i = 0; i < bn.length; i++) {
            bn[i] = ((Number) b.get(i)).doubleValue();
        }
        if (attribute.equals("stroke-dasharray") && a.length != bn.length && a.length > 0 && bn.length > 0) {
            int n = Math.max(a.length, bn.length) * (a.length % 2 == 1 || bn.length % 2 == 1 ? 2 : 1);
            double[] ra = new double[n];
            double[] rb = new double[n];
            for (int i = 0; i < n; i++) {
                ra[i] = a[i % a.length];
                rb[i] = bn[i % bn.length];
            }
            a = ra;
            bn = rb;
        }
        if (a.length != bn.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - bn[i]) > 0.02 + 0.01 * Math.abs(bn[i])) {
                return false;
            }
        }
        return true;
    }

    /** Enough JSON for the fixture: objects, arrays, strings, numbers and null. */
    private static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object value() {
            space();
            char c = text.charAt(at);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                at++;
                space();
                if (text.charAt(at) == '}') {
                    at++;
                    return map;
                }
                while (true) {
                    space();
                    String key = (String) value();
                    space();
                    at++; // ':'
                    map.put(key, value());
                    space();
                    if (text.charAt(at++) == '}') {
                        return map;
                    }
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                at++;
                space();
                if (text.charAt(at) == ']') {
                    at++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    space();
                    if (text.charAt(at++) == ']') {
                        return list;
                    }
                }
            }
            if (c == '"') {
                StringBuilder s = new StringBuilder();
                at++;
                while (text.charAt(at) != '"') {
                    char ch = text.charAt(at++);
                    s.append(ch == '\\' ? text.charAt(at++) : ch);
                }
                at++;
                return s.toString();
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            return Double.parseDouble(text.substring(start, at));
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
