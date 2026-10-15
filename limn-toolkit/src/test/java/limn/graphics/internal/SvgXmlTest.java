package limn.graphics.internal;

import limn.io.MalformedContentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolkit's own reading of an SVG's XML (ADR 066 section 6): what real files carry is read,
 * the internal entities of illustration programs among it, and what a DTD could do beyond that,
 * an external entity, a parameter entity, an entity within an entity, is refused, as is anything
 * past a ceiling.
 */
class SvgXmlTest {

    private static SvgXml.Element parse(String xml) {
        return SvgXml.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String round(String xml) {
        return new String(SvgXml.write(parse(xml)), StandardCharsets.UTF_8);
    }

    @Test
    void elementsAttributesAndTextAreReadAndWrittenBack() {
        SvgXml.Element root = parse("<?xml version='1.0'?><!-- a comment -->\n"
                + "<svg xmlns='http://www.w3.org/2000/svg' width=\"10\"><g id='a'><text>x &amp; y &#65;&#x42;</text></g></svg>");
        assertEquals("svg", root.name());
        assertEquals("10", root.attribute("width"));
        SvgXml.Element text = root.elements().get(0).elements().get(0);
        assertEquals("x & y AB", text.text());
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\"><g id=\"a\"><text>x &amp; y AB</text></g></svg>",
                round("<svg xmlns='http://www.w3.org/2000/svg' width=\"10\"><g id='a'><text>x &amp; y &#65;&#x42;</text></g></svg>"));
    }

    @Test
    void aStyleSheetKeepsItsOwnSignsAndCdataIsText() {
        assertEquals("<svg><style><![CDATA[g > rect { fill: red }]]></style></svg>",
                round("<svg><style><![CDATA[g > rect { fill: red }]]></style></svg>"));
        assertEquals("a < b", parse("<svg><desc><![CDATA[a < b]]></desc></svg>").elements().get(0).text());
    }

    @Test
    void whiteSpaceInAnAttributeReadsAsSpaces() {
        assertEquals("M 0 0  L 1 1", parse("<svg d='M 0 0\n\tL 1 1'/>").attribute("d"));
    }

    @Test
    void theEntitiesAnIllustrationProgramDeclaresAreExpanded() {
        SvgXml.Element root = parse("<?xml version='1.0' encoding='iso-8859-1'?>\n"
                + "<!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN' 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd' [\n"
                + "  <!ENTITY ns_ai \"http://ns.adobe.example/AI/\">\n"
                + "  <!ENTITY Rect \"<rect id='r' width='2' height='2'/>\">\n"
                + "]>\n<svg xmlns:i='&ns_ai;'><g>&Rect;</g></svg>");
        assertEquals("http://ns.adobe.example/AI/", root.attribute("xmlns:i"));
        assertEquals("r", root.elements().get(0).elements().get(0).attribute("id"), "markup in an entity is read as markup");
    }

    @Test
    void whatADtdCouldDoBeyondThatIsRefused() {
        assertThrows(MalformedContentException.class, () -> parse(
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg>&x;</svg>"), "an external entity");
        assertThrows(MalformedContentException.class, () -> parse(
                "<!DOCTYPE svg [<!ENTITY % p 'x'>]><svg/>"), "a parameter entity");
        assertThrows(MalformedContentException.class, () -> parse(
                "<!DOCTYPE svg [<!ENTITY a 'x'><!ENTITY b '&a;&a;'>]><svg>&b;</svg>"), "an entity within an entity");
        assertThrows(MalformedContentException.class, () -> parse(
                "<!DOCTYPE svg [<!ELEMENT svg ANY>]><svg/>"), "any other declaration");
        assertThrows(MalformedContentException.class, () -> parse("<svg>&undeclared;</svg>"));
    }

    @Test
    void expansionsTogetherAreHeldUnderTheirCeiling() {
        String big = "x".repeat(10_000);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            body.append("&big;");
        }
        assertThrows(MalformedContentException.class, () -> parse(
                "<!DOCTYPE svg [<!ENTITY big '" + big + "'>]><svg>" + body + "</svg>"));
    }

    @Test
    void depthAndElementCountsHaveCeilings() {
        String deep = "<g>".repeat(SvgXml.MAX_DEPTH + 1) + "</g>".repeat(SvgXml.MAX_DEPTH + 1);
        assertThrows(MalformedContentException.class, () -> parse("<svg>" + deep + "</svg>"));
        String many = "<g/>".repeat(SvgXml.MAX_ELEMENTS);
        assertThrows(MalformedContentException.class, () -> parse("<svg>" + many + "</svg>"));
        assertThrows(MalformedContentException.class,
                () -> SvgXml.parse(new byte[SvgXml.MAX_BYTES + 1]));
    }

    @Test
    void damagedXmlIsRefused() {
        for (String damaged : new String[] {"", "text", "<svg>", "<svg></g>", "<svg a=1/>", "<svg a='1' a='2'/>",
                "<svg a='<'/>", "<svg/><svg/>", "<svg><![CDATA[x</svg>", "<svg>&#xD800;</svg>", "<svg>&#99999999;</svg>"}) {
            assertThrows(MalformedContentException.class, () -> parse(damaged), damaged);
        }
    }

    @Test
    void theEncodingsFilesAreWrittenInAreRead() {
        byte[] latin = "<?xml version='1.0' encoding='ISO-8859-1'?><svg><title>café</title></svg>"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("café", SvgXml.parse(latin).elements().get(0).text());
        byte[] utf16 = "﻿<svg><title>日</title></svg>".getBytes(StandardCharsets.UTF_16BE);
        assertEquals("日", SvgXml.parse(utf16).elements().get(0).text());
        byte[] bom = "﻿<svg/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("svg", SvgXml.parse(bom).name());
        assertThrows(MalformedContentException.class, () -> SvgXml.parse(
                "<?xml version='1.0' encoding='EBCDIC'?><svg/>".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(MalformedContentException.class, () -> SvgXml.parse(new byte[] {'<', 's', 'v', 'g', (byte) 0xC3, '/', '>'}),
                "UTF-8 that is not");
    }

    @Test
    void writingEscapesWhatXmlRequires() {
        SvgXml.Element root = new SvgXml.Element("svg");
        root.setAttribute("a", "\"<&>");
        root.children().add("<&>");
        String written = new String(SvgXml.write(root), StandardCharsets.UTF_8);
        assertEquals("<svg a=\"&quot;&lt;&amp;&gt;\">&lt;&amp;></svg>", written);
        assertTrue(parse(written).text().equals("<&>"));
    }
}
