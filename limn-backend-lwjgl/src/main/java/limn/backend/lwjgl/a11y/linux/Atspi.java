package limn.backend.lwjgl.a11y.linux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AT-SPI2 constants and interface XML, every value taken from the machine under test
 * (Ubuntu 24.04, at-spi2-core 2.52.0-1build1, aarch64) rather than from memory:
 *
 *  - roles and states: the GObject-introspection typelib
 *      /usr/lib/aarch64-linux-gnu/girepository-1.0/Atspi-2.0.typelib
 *    read with  python3 -c 'from gi.repository import Atspi; print(int(Atspi.Role.PUSH_BUTTON))'
 *  - object paths: `strings /usr/lib/aarch64-linux-gnu/libatspi.so.0 | grep ^/org/a11y`
 *  - interface XML and every D-Bus signature: the introspection blobs compiled into
 *      /usr/lib/aarch64-linux-gnu/libatk-bridge-2.0.so.0
 *    plus a live `gdbus introspect` of at-spi2-registryd on the a11y bus.
 */
final class Atspi {

    private Atspi() {}

    // ---- object paths (libatspi.so.0 string table) ---------------------------------------
    static final String PATH_ROOT  = "/org/a11y/atspi/accessible/root";
    static final String PATH_CACHE = "/org/a11y/atspi/cache";
    static final String PATH_NULL  = "/org/a11y/atspi/null";
    static final String REGISTRY   = "org.a11y.atspi.Registry";

    // ---- interfaces -----------------------------------------------------------------------
    static final String I_ACCESSIBLE  = "org.a11y.atspi.Accessible";
    static final String I_APPLICATION = "org.a11y.atspi.Application";
    static final String I_COMPONENT   = "org.a11y.atspi.Component";
    static final String I_ACTION      = "org.a11y.atspi.Action";
    static final String I_TABLE       = "org.a11y.atspi.Table";
    static final String I_TABLE_CELL  = "org.a11y.atspi.TableCell";
    // Named as the interface XML the Fedora KDE 44 guest's ATK bridge compiles in declares it
    // (at-spi2-atk 2.60.6-1.fc44, readings/fedora-dbus-<Interface>.xml, extracted by
    // scripts/a11y/linux/extract-atspi-introspection.sh on 2026-09-13, the guest's clock then
    // reading 2026-09-10).
    static final String I_SELECTION   = "org.a11y.atspi.Selection";
    static final String I_VALUE       = "org.a11y.atspi.Value";
    static final String I_TEXT        = "org.a11y.atspi.Text";
    static final String I_EDITABLE_TEXT = "org.a11y.atspi.EditableText";
    // The node paths' common prefix, whose Introspect lists the application root and every node.
    static final String PATH_ACCESSIBLE = "/org/a11y/atspi/accessible";
    static final String I_CACHE       = "org.a11y.atspi.Cache";
    static final String I_SOCKET      = "org.a11y.atspi.Socket";
    static final String I_PROPS       = "org.freedesktop.DBus.Properties";
    static final String I_INTROSPECT  = "org.freedesktop.DBus.Introspectable";
    static final String I_PEER        = "org.freedesktop.DBus.Peer";

    // ---- AtspiRole (typelib) ---------------------------------------------------------------
    static final int ROLE_INVALID     = 0;    // Atspi.Role.INVALID
    static final int ROLE_FRAME       = 23;   // Atspi.Role.FRAME        -> "frame"
    static final int ROLE_PUSH_BUTTON = 43;   // Atspi.Role.PUSH_BUTTON  -> "push button"
    static final int ROLE_APPLICATION = 75;   // Atspi.Role.APPLICATION  -> "application"

    // ---- AtspiStateType, as bit indices into the 64-bit set (typelib) -----------------------
    static final int STATE_ACTIVE    = 1;
    static final int STATE_ENABLED   = 8;
    static final int STATE_FOCUSABLE = 11;
    static final int STATE_SENSITIVE = 24;
    static final int STATE_SHOWING   = 25;
    static final int STATE_VISIBLE   = 30;

    // ---- AtspiRelationType ------------------------------------------------------------------
    // Read 2026-09-09 from the enum's own declaration in atspi/atspi-constants.h at the release
    // tag AT_SPI2_CORE_2_52_0 (gitlab.gnome.org/GNOME/at-spi2-core), and confirmed the same day
    // against a guest's typelib (Fedora KDE 44, at-spi2-core 2.60.6, Atspi.RelationType), which
    // is what dump-atspi-constants.py prints. The enum is unnumbered, so each value is its
    // position from ATSPI_RELATION_NULL at 0.
    static final int RELATION_LABEL_FOR      = 1;    // ATSPI_RELATION_LABEL_FOR
    static final int RELATION_LABELLED_BY    = 2;    // ATSPI_RELATION_LABELLED_BY
    static final int RELATION_CONTROLLER_FOR = 3;    // ATSPI_RELATION_CONTROLLER_FOR
    static final int RELATION_CONTROLLED_BY  = 4;    // ATSPI_RELATION_CONTROLLED_BY
    static final int RELATION_MEMBER_OF      = 5;    // ATSPI_RELATION_MEMBER_OF
    static final int RELATION_POPUP_FOR      = 15;   // ATSPI_RELATION_POPUP_FOR
    static final int RELATION_DESCRIBED_BY   = 18;   // ATSPI_RELATION_DESCRIBED_BY

    // ---- AtspiLive ------------------------------------------------------------------------------
    // Read 2026-09-13 off the Fedora KDE 44 guest's typelib (libatspi 2.60.6, Atspi.Live, every
    // enumerator) by scripts/a11y/linux/dump-atspi-constants.py --all
    // (readings/fedora-atspi-constants-all.txt). An Announcement's detail1 is one of these: GTK
    // 4.22.4 sends POLITE for a low or medium priority and ASSERTIVE for a high one, and Orca 50.2's
    // _get_priority compares detail1 with them (readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-
    // event-shapes.txt, readings/fedora-orca-event-consumers.txt).
    static final int LIVE_POLITE    = 1;   // Atspi.Live.POLITE
    static final int LIVE_ASSERTIVE = 2;   // Atspi.Live.ASSERTIVE

    // ---- AtspiTextGranularity, AtspiTextBoundaryType ------------------------------------------
    // Read 2026-09-13 off the Fedora KDE 44 guest's typelib (libatspi 2.60.6, every enumerator) by
    // scripts/a11y/linux/dump-atspi-constants.py --all (readings/fedora-atspi-constants-all.txt).
    // GetStringAtOffset takes a granularity; GetTextAtOffset, -Before- and -After- a boundary type.
    static final int TEXT_GRANULARITY_CHAR = 0;            // Atspi.TextGranularity.CHAR
    static final int TEXT_GRANULARITY_WORD = 1;            // Atspi.TextGranularity.WORD
    static final int TEXT_GRANULARITY_SENTENCE = 2;        // Atspi.TextGranularity.SENTENCE
    static final int TEXT_GRANULARITY_LINE = 3;            // Atspi.TextGranularity.LINE
    static final int TEXT_GRANULARITY_PARAGRAPH = 4;       // Atspi.TextGranularity.PARAGRAPH
    static final int TEXT_BOUNDARY_CHAR = 0;               // Atspi.TextBoundaryType.CHAR
    static final int TEXT_BOUNDARY_WORD_START = 1;         // Atspi.TextBoundaryType.WORD_START
    static final int TEXT_BOUNDARY_WORD_END = 2;           // Atspi.TextBoundaryType.WORD_END
    static final int TEXT_BOUNDARY_SENTENCE_START = 3;     // Atspi.TextBoundaryType.SENTENCE_START
    static final int TEXT_BOUNDARY_SENTENCE_END = 4;       // Atspi.TextBoundaryType.SENTENCE_END
    static final int TEXT_BOUNDARY_LINE_START = 5;         // Atspi.TextBoundaryType.LINE_START
    static final int TEXT_BOUNDARY_LINE_END = 6;           // Atspi.TextBoundaryType.LINE_END

    // ---- AtspiCoordType / AtspiComponentLayer (typelib) -------------------------------------
    static final int COORD_SCREEN = 0, COORD_WINDOW = 1;
    // Read 2026-09-13 off the Fedora KDE 44 guest's typelib (libatspi 2.60.6) by
    // dump-atspi-constants.py --all (readings/fedora-atspi-constants-all.txt): Atspi.CoordType.PARENT.
    static final int COORD_PARENT = 2;
    static final int LAYER_WIDGET = 3, LAYER_WINDOW = 7;

    static long state(int... bits) {
        long s = 0;
        for (int b : bits) s |= 1L << b;
        return s;
    }

    /** GetState is "au": exactly two uint32, low word first (not one uint64). */
    static List<Object> stateWords(long states) {
        List<Object> out = new ArrayList<>(2);
        out.add((int) (states & 0xffffffffL));
        out.add((int) (states >>> 32));
        return out;
    }

    static Map<Object, Object> attrs(String... kv) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // =========================================================================================
    // Introspection XML, verbatim from libatk-bridge-2.0.so.0's compiled-in blobs.
    // Serving the real XML means gdbus/busctl/d-feet see exactly what a GTK app shows.
    // =========================================================================================

    static final String XML_ACCESSIBLE =
        "<interface name=\"org.a11y.atspi.Accessible\">"
        + "<property name=\"Name\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Description\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Parent\" type=\"(so)\" access=\"read\"/>"
        + "<property name=\"ChildCount\" type=\"i\" access=\"read\"/>"
        + "<property name=\"Locale\" type=\"s\" access=\"read\"/>"
        + "<property name=\"AccessibleId\" type=\"s\" access=\"read\"/>"
        + "<property name=\"HelpText\" type=\"s\" access=\"read\"/>"
        + "<method name=\"GetChildAtIndex\"><arg direction=\"in\" name=\"index\" type=\"i\"/><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetChildren\"><arg direction=\"out\" type=\"a(so)\"/></method>"
        + "<method name=\"GetIndexInParent\"><arg direction=\"out\" type=\"i\"/></method>"
        + "<method name=\"GetRelationSet\"><arg direction=\"out\" type=\"a(ua(so))\"/></method>"
        + "<method name=\"GetRole\"><arg direction=\"out\" type=\"u\"/></method>"
        + "<method name=\"GetRoleName\"><arg direction=\"out\" type=\"s\"/></method>"
        + "<method name=\"GetLocalizedRoleName\"><arg direction=\"out\" type=\"s\"/></method>"
        + "<method name=\"GetState\"><arg direction=\"out\" type=\"au\"/></method>"
        + "<method name=\"GetAttributes\"><arg direction=\"out\" type=\"a{ss}\"/></method>"
        + "<method name=\"GetApplication\"><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetInterfaces\"><arg direction=\"out\" type=\"as\"/></method>"
        + "</interface>";

    static final String XML_APPLICATION =
        "<interface name=\"org.a11y.atspi.Application\">"
        + "<property name=\"ToolkitName\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Version\" type=\"s\" access=\"read\"/>"
        + "<property name=\"AtspiVersion\" type=\"s\" access=\"read\"/>"
        + "<property name=\"Id\" type=\"i\" access=\"readwrite\"/>"
        + "<method name=\"GetLocale\"><arg direction=\"in\" name=\"lctype\" type=\"u\"/><arg direction=\"out\" type=\"s\"/></method>"
        + "</interface>";

    static final String XML_COMPONENT =
        "<interface name=\"org.a11y.atspi.Component\">"
        + "<method name=\"Contains\"><arg direction=\"in\" name=\"x\" type=\"i\"/><arg direction=\"in\" name=\"y\" type=\"i\"/><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"b\"/></method>"
        + "<method name=\"GetAccessibleAtPoint\"><arg direction=\"in\" name=\"x\" type=\"i\"/><arg direction=\"in\" name=\"y\" type=\"i\"/><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"(so)\"/></method>"
        + "<method name=\"GetExtents\"><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" type=\"(iiii)\"/></method>"
        + "<method name=\"GetPosition\"><arg direction=\"in\" name=\"coord_type\" type=\"u\"/><arg direction=\"out\" name=\"x\" type=\"i\"/><arg direction=\"out\" name=\"y\" type=\"i\"/></method>"
        + "<method name=\"GetSize\"><arg direction=\"out\" name=\"width\" type=\"i\"/><arg direction=\"out\" name=\"height\" type=\"i\"/></method>"
        + "<method name=\"GetLayer\"><arg direction=\"out\" type=\"u\"/></method>"
        + "<method name=\"GetMDIZOrder\"><arg direction=\"out\" type=\"n\"/></method>"
        + "<method name=\"GrabFocus\"><arg direction=\"out\" type=\"b\"/></method>"
        + "<method name=\"GetAlpha\"><arg direction=\"out\" type=\"d\"/></method>"
        + "</interface>";

    static final String XML_ACTION =
        "<interface name=\"org.a11y.atspi.Action\">"
        + "<property name=\"NActions\" type=\"i\" access=\"read\"/>"
        + "<method name=\"GetDescription\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetName\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetLocalizedName\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetKeyBinding\"><arg type=\"i\" name=\"index\" direction=\"in\"/><arg type=\"s\" direction=\"out\"/></method>"
        + "<method name=\"GetActions\"><arg direction=\"out\" type=\"a(sss)\"/></method>"
        + "<method name=\"DoAction\"><arg direction=\"in\" name=\"index\" type=\"i\"/><arg direction=\"out\" type=\"b\"/></method>"
        + "</interface>";

    static final String XML_CACHE =
        "<interface name=\"org.a11y.atspi.Cache\">"
        + "<method name=\"GetItems\"><arg direction=\"out\" name=\"nodes\" type=\"a((so)(so)(so)iiassusau)\"/></method>"
        + "<signal name=\"AddAccessible\"><arg name=\"nodeAdded\" type=\"((so)(so)(so)iiassusau)\"/></signal>"
        + "<signal name=\"RemoveAccessible\"><arg name=\"nodeRemoved\" type=\"(so)\"/></signal>"
        + "</interface>";

    // =========================================================================================
    // Introspection XML the Fedora KDE 44 guest's ATK bridge compiles in for the interfaces served
    // since 2026-09-15, verbatim, whitespace and Qt annotations included (at-spi2-atk
    // 2.60.6-1.fc44, libatk-bridge sha256 34f853bd…c95d; readings/fedora-dbus-<Interface>.xml, read
    // by scripts/a11y/linux/extract-atspi-introspection.sh: Selection, Value, Text and EditableText
    // on 2026-09-13, Table and TableCell on 2026-09-15). The blocks above were read off Ubuntu
    // 24.04's at-spi2-core 2.52.0 and are left as they were read.
    // =========================================================================================

    static final String XML_SELECTION =
        "<interface name=\"org.a11y.atspi.Selection\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <property name=\"NSelectedChildren\" type=\"i\" access=\"read\" />"
        + "        <method name=\"GetSelectedChild\">"
        + "      <arg direction=\"in\" name=\"selectedChildIndex\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReference\" />"
        + "    </method>        <method name=\"SelectChild\">"
        + "      <arg direction=\"in\" name=\"childIndex\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"DeselectSelectedChild\">"
        + "      <arg direction=\"in\" name=\"selectedChildIndex\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"IsChildSelected\">"
        + "      <arg direction=\"in\" name=\"childIndex\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"SelectAll\">      <arg direction=\"out\" type=\"b\" />"
        + "    </method>        <method name=\"ClearSelection\">"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"DeselectChild\">"
        + "      <arg direction=\"in\" name=\"childIndex\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>  </interface>";

    static final String XML_VALUE =
        "<interface name=\"org.a11y.atspi.Value\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <property name=\"MinimumValue\" type=\"d\" access=\"read\" />"
        + "        <property name=\"MaximumValue\" type=\"d\" access=\"read\" />"
        + "        <property name=\"MinimumIncrement\" type=\"d\" access=\"read\" />"
        + "        <property name=\"CurrentValue\" type=\"d\" access=\"readwrite\" />"
        + "        <property name=\"Text\" type=\"s\" access=\"read\" />  </interface>";

    static final String XML_TEXT =
        "<interface name=\"org.a11y.atspi.Text\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <property name=\"CharacterCount\" type=\"i\" access=\"read\" />"
        + "        <property name=\"CaretOffset\" type=\"i\" access=\"read\" />"
        + "        <method name=\"GetStringAtOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"granularity\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"s\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetText\">"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"s\" />    </method>"
        + "        <method name=\"SetCaretOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"GetTextBeforeOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"type\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"s\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetTextAtOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"type\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"s\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetTextAfterOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"type\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"s\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetCharacterAtOffset\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetAttributeValue\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"attributeName\" type=\"s\" />"
        + "      <arg direction=\"out\" type=\"s\" />    </method>"
        + "        <method name=\"GetAttributes\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"a{ss}\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiAttributeSet\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetDefaultAttributes\">"
        + "      <arg direction=\"out\" type=\"a{ss}\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiAttributeSet\" />"
        + "    </method>        <method name=\"GetCharacterExtents\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"coordType\" type=\"u\" />"
        + "      <arg direction=\"out\" name=\"x\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"y\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"width\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"height\" type=\"i\" />    </method>"
        + "        <method name=\"GetOffsetAtPoint\">"
        + "      <arg direction=\"in\" name=\"x\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"y\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"coordType\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetNSelections\">      <arg direction=\"out\" type=\"i\" />"
        + "    </method>        <method name=\"GetSelection\">"
        + "      <arg direction=\"in\" name=\"selectionNum\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"AddSelection\">"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"RemoveSelection\">"
        + "      <arg direction=\"in\" name=\"selectionNum\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"SetSelection\">"
        + "      <arg direction=\"in\" name=\"selectionNum\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"GetRangeExtents\">"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"coordType\" type=\"u\" />"
        + "      <arg direction=\"out\" name=\"x\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"y\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"width\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"height\" type=\"i\" />    </method>"
        + "        <method name=\"GetBoundedRanges\">"
        + "      <arg direction=\"in\" name=\"x\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"y\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"width\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"height\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"coordType\" type=\"u\" />"
        + "      <arg direction=\"in\" name=\"xClipType\" type=\"u\" />"
        + "      <arg direction=\"in\" name=\"yClipType\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"a(iisv)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiRangeList\" />"
        + "    </method>        <method name=\"GetAttributeRun\">"
        + "      <arg direction=\"in\" name=\"offset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"includeDefaults\" type=\"b\" />"
        + "      <arg direction=\"out\" type=\"a{ss}\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiAttributeSet\" />"
        + "      <arg direction=\"out\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"endOffset\" type=\"i\" />    </method>"
        + "        <method name=\"GetDefaultAttributeSet\">"
        + "      <arg direction=\"out\" type=\"a{ss}\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiAttributeSet\" />"
        + "    </method>        <method name=\"ScrollSubstringTo\">"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"type\" type=\"u\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"ScrollSubstringToPoint\">"
        + "      <arg direction=\"in\" name=\"startOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endOffset\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"coordType\" type=\"u\" />"
        + "      <arg direction=\"in\" name=\"x\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"y\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>  </interface>";

    static final String XML_EDITABLE_TEXT =
        "<interface name=\"org.a11y.atspi.EditableText\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <method name=\"SetTextContents\">"
        + "      <arg direction=\"in\" name=\"newContents\" type=\"s\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"InsertText\">"
        + "      <arg direction=\"in\" name=\"position\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"text\" type=\"s\" />"
        + "      <arg direction=\"in\" name=\"length\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"CopyText\">"
        + "      <arg direction=\"in\" name=\"startPos\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endPos\" type=\"i\" />    </method>"
        + "        <method name=\"CutText\">"
        + "      <arg direction=\"in\" name=\"startPos\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endPos\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"DeleteText\">"
        + "      <arg direction=\"in\" name=\"startPos\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"endPos\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"PasteText\">"
        + "      <arg direction=\"in\" name=\"position\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>  </interface>";

    static final String XML_TABLE =
        "<interface name=\"org.a11y.atspi.Table\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <property name=\"NRows\" type=\"i\" access=\"read\" />"
        + "        <property name=\"NColumns\" type=\"i\" access=\"read\" />"
        + "        <property name=\"Caption\" type=\"(so)\" access=\"read\">"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName\" value=\"QSpiObjectReference\" />"
        + "    </property>        <property name=\"Summary\" type=\"(so)\" access=\"read\">"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName\" value=\"QSpiObjectReference\" />"
        + "    </property>        <property name=\"NSelectedRows\" type=\"i\" access=\"read\" />"
        + "        <property name=\"NSelectedColumns\" type=\"i\" access=\"read\" />"
        + "        <method name=\"GetAccessibleAt\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReference\" />"
        + "    </method>        <method name=\"GetIndexAt\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetRowAtIndex\">"
        + "      <arg direction=\"in\" name=\"index\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetColumnAtIndex\">"
        + "      <arg direction=\"in\" name=\"index\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetRowDescription\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"s\" />    </method>"
        + "        <method name=\"GetColumnDescription\">"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"s\" />    </method>"
        + "        <method name=\"GetRowExtentAt\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetColumnExtentAt\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"i\" />    </method>"
        + "        <method name=\"GetRowHeader\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReference\" />"
        + "    </method>        <method name=\"GetColumnHeader\">"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReference\" />"
        + "    </method>        <method name=\"GetSelectedRows\">"
        + "      <arg direction=\"out\" type=\"ai\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiIntList\" />"
        + "    </method>        <method name=\"GetSelectedColumns\">"
        + "      <arg direction=\"out\" type=\"ai\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiIntList\" />"
        + "    </method>        <method name=\"IsRowSelected\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"IsColumnSelected\">"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"IsSelected\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"AddRowSelection\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"AddColumnSelection\">"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"RemoveRowSelection\">"
        + "      <arg direction=\"in\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"RemoveColumnSelection\">"
        + "      <arg direction=\"in\" name=\"column\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />    </method>"
        + "        <method name=\"GetRowColumnExtentsAtIndex\">"
        + "      <arg direction=\"in\" name=\"index\" type=\"i\" />"
        + "      <arg direction=\"out\" type=\"b\" />"
        + "      <arg direction=\"out\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"col\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"row_extents\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"col_extents\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"is_selected\" type=\"b\" />    </method>"
        + "  </interface>";

    static final String XML_TABLE_CELL =
        "<interface name=\"org.a11y.atspi.TableCell\">"
        + "        <property name=\"version\" type=\"u\" access=\"read\" />"
        + "        <property access=\"read\" name=\"ColumnSpan\" type=\"i\" />"
        + "        <property access=\"read\" name=\"Position\" type=\"(ii)\">"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName\" value=\"QPoint\" />"
        + "    </property>        <property access=\"read\" name=\"RowSpan\" type=\"i\" />"
        + "        <property access=\"read\" name=\"Table\" type=\"(so)\">"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName\" value=\"QSpiObjectReference\" />"
        + "    </property>        <method name=\"GetRowColumnSpan\">"
        + "      <arg direction=\"out\" type=\"b\" />"
        + "      <arg direction=\"out\" name=\"row\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"col\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"row_extents\" type=\"i\" />"
        + "      <arg direction=\"out\" name=\"col_extents\" type=\"i\" />    </method>"
        + "        <method name=\"GetColumnHeaderCells\">"
        + "      <arg direction=\"out\" type=\"a(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReferenceArray\" />"
        + "    </method>        <method name=\"GetRowHeaderCells\">"
        + "      <arg direction=\"out\" type=\"a(so)\" />"
        + "      <annotation name=\"org.qtproject.QtDBus.QtTypeName.Out0\" value=\"QSpiObjectReferenceArray\" />"
        + "    </method>  </interface>";

    /** The cache item struct, from libatk-bridge-2.0.so.0 / libatspi.so.0. */
    static final String CACHE_ITEM = "((so)(so)(so)iiassusau)";
    static final String CACHE_ITEMS = "a" + CACHE_ITEM;

    static final String XML_STD =
        "<interface name=\"org.freedesktop.DBus.Introspectable\">"
        + "<method name=\"Introspect\"><arg name=\"data\" direction=\"out\" type=\"s\"/></method></interface>"
        + "<interface name=\"org.freedesktop.DBus.Properties\">"
        + "<method name=\"Get\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"propname\" direction=\"in\" type=\"s\"/><arg name=\"value\" direction=\"out\" type=\"v\"/></method>"
        + "<method name=\"Set\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"propname\" direction=\"in\" type=\"s\"/><arg name=\"value\" direction=\"in\" type=\"v\"/></method>"
        + "<method name=\"GetAll\"><arg name=\"interface\" direction=\"in\" type=\"s\"/><arg name=\"props\" direction=\"out\" type=\"a{sv}\"/></method></interface>"
        + "<interface name=\"org.freedesktop.DBus.Peer\">"
        + "<method name=\"Ping\"/><method name=\"GetMachineId\"><arg name=\"machine_uuid\" direction=\"out\" type=\"s\"/></method></interface>";

    static String node(String... interfaces) {
        StringBuilder sb = new StringBuilder(
            "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\"\n"
            + "\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>");
        for (String i : interfaces) sb.append(i);
        sb.append(XML_STD).append("</node>");
        return sb.toString();
    }
}
