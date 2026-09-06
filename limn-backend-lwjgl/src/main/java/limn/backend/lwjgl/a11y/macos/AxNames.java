package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;

/**
 * Which attribute a node's name goes into, which on this platform is a question and not a detail.
 *
 * <p>Finding 5: AppKit maps {@code accessibilityTitle} to {@code AXTitle} and
 * {@code accessibilityLabel} to {@code AXDescription}, and they mean different things to a reader.
 * A title is the control's <em>own</em> text — the words painted on a button. A label is text that
 * describes a control which has no words of its own — an icon button's "Delete", a field's
 * placeholder. VoiceOver speaks them differently and a client searching by name matches
 * {@code AXTitle} first, so a bridge that guesses is silently wrong rather than loudly wrong: the
 * spike's own probe set both and could not tell which one a by-name client had matched.
 *
 * <p><b>Never both.</b> Setting a title and a label with the same string makes VoiceOver say the
 * name twice, which is the failure a reader of this file is most likely to reintroduce, because
 * setting both looks like belt and braces and reads like a stammer.
 */
final class AxNames {

    private AxNames() {
    }

    /** Which of AppKit's two name attributes a name belongs in. */
    enum Attribute {
        /** {@code accessibilityTitle} → {@code AXTitle}: the control's own painted text. */
        TITLE,
        /** {@code accessibilityLabel} → {@code AXDescription}: text about a control that has none. */
        LABEL,
    }

    /**
     * @param nameFrom where the name came from
     * @return the attribute it belongs in
     */
    static Attribute attributeFor(Accessible.NameFrom nameFrom) {
        // CONTENT is the one provenance that means "these are the control's own words". Everything
        // else -- a separate label widget, a tooltip, a placeholder, a name the application set by
        // hand -- is text ABOUT the control, which is what AXDescription is for.
        return nameFrom == Accessible.NameFrom.CONTENT ? Attribute.TITLE : Attribute.LABEL;
    }
}
