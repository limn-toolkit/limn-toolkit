package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * People drawn as a stack of {@link Avatar}s: the approvers of a document, the members of a team.
 * Each avatar overlaps the one before by a quarter of its width, ringed in the surface colour so
 * the overlap reads as a stack. A circle covered by the next one centres its initials on the part
 * that shows, the three quarters away from the overlap, so no letter is cut; the last circle keeps
 * them in its middle. Every circle draws its initials at one size: the label's, or the smaller size
 * the group's widest pair needs to fit that part ("MW" in the shipped face), so the stack reads
 * even.
 *
 * <p><b>Past {@link #setMaxShown(int) the most shown}</b> (four by default), the first ones are
 * drawn and the last place is a circle that says how many more there are, "+3", whose tooltip lists
 * the hidden names. The count takes no click: a group is a picture of who, and a list of everyone
 * is the application's, in a {@link Popover} from a button beside it if it wants one.
 *
 * <p><b>To a reader a group is one node</b>, a {@link Accessible.Role#GROUP} named by
 * {@link #setAccessibleName} ("Approvers") and described by every member's name in order, the
 * hidden ones included, joined by the language's list comma. The avatars inside are not published:
 * they would repeat the description, and the hidden ones have no box to publish. A reader is not
 * limited by the width the eye is.
 *
 * <p>Right to left, the group starts at the leading edge, the right one, and overlaps toward the
 * left.
 *
 * <pre>{@code
 * new AvatarGroup(new Avatar("Ana Lima"), new Avatar("Rui Costa"), new Avatar("Eva Melo"))
 *         .setAccessibleName("Approvers");
 * }</pre>
 */
public final class AvatarGroup extends Widget<AvatarGroup> {

    /** The most avatars shown by default, the count's circle included. */
    private static final int DEFAULT_MAX_SHOWN = 4;

    private final List<Avatar> members = new ArrayList<>();
    private int maxShown = DEFAULT_MAX_SHOWN;
    private final More more = new More();

    // Every member's name joined for a reader, built when a member or a name changes, or the
    // language does, with the counter the node's description is diffed by.
    private String description = "";

    // The group's one size for initials, and what it was worked out for.
    private long initialsStamp;
    private long fitStamp = -1;
    /** The letters each circle drew, in order, as the size was worked out for them. */
    private final List<String> fitLetters = new ArrayList<>();
    private float fitD = -1;
    private @Nullable Font fitFrom;
    private @Nullable Font fitFont;
    private @Nullable TextRuler fitRuler;
    private long fitRulerEpoch;
    private long describedEpoch = -1;
    private @Nullable Locale describedIn;
    private boolean describedStale = true;
    private long descriptionWitness;

    /**
     * A group of the avatars given, in order.
     *
     * @param avatars the members, in the order drawn
     */
    public AvatarGroup(Avatar... avatars) {
        for (Avatar avatar : avatars) {
            join(avatar);
        }
        rebuild();
    }

    /**
     * Adds a member at the end.
     *
     * @param avatar the member to add, which is in no other group
     * @return this group
     * @throws IllegalArgumentException if the avatar is already in a group
     */
    public AvatarGroup add(Avatar avatar) {
        Ui.checkUiThread();
        join(avatar);
        rebuild();
        return this;
    }

    /**
     * Removes a member.
     *
     * @param avatar the member
     * @return this group
     */
    public AvatarGroup remove(Avatar avatar) {
        Ui.checkUiThread();
        if (members.remove(avatar)) {
            avatar.setRinged(false);
            avatar.group = null;
            rebuild();
        }
        return this;
    }

    /** @return the members, in order, the hidden ones included */
    public List<Avatar> members() {
        return List.copyOf(members);
    }

    /**
     * How many circles the group draws at most, the "+k" circle included: past it, the first
     * {@code count - 1} members are drawn and the last place counts the rest.
     *
     * @param count at least 1
     * @return this group
     * @throws IllegalArgumentException if {@code count} is less than 1
     */
    public AvatarGroup setMaxShown(int count) {
        Ui.checkUiThread();
        if (count < 1) {
            throw new IllegalArgumentException("a group shows at least one circle: " + count);
        }
        if (maxShown != count) {
            this.maxShown = count;
            rebuild();
        }
        return this;
    }

    /** @return how many circles the group draws at most */
    public int maxShown() {
        return maxShown;
    }

    /** @return how many members are hidden behind the "+k" circle */
    public int hiddenCount() {
        return members.size() > maxShown ? members.size() - (maxShown - 1) : 0;
    }

    private void join(Avatar avatar) {
        Objects.requireNonNull(avatar, "avatar");
        if (avatar.group != null || avatar.parent() != null) {
            throw new IllegalArgumentException("the avatar already has a place: " + avatar.name());
        }
        avatar.group = this;
        avatar.setRinged(true);
        members.add(avatar);
    }

    /** A member's name changed: the description and the count's tooltip follow it. */
    void memberRenamed() {
        describedStale = true;
        invalidateAccessible();
        more.retitle();
        memberDrawnAnew();
    }

    /** A member's initials or picture changed: the group's one size for initials may move. */
    void memberDrawnAnew() {
        initialsStamp++;
        invalidate();
    }

    /**
     * The font every circle of the group draws its initials in: {@code full}, or the size the
     * widest pair among the circles that draw initials needs to fit the part of a circle the next
     * one leaves, whichever is smaller; {@code null} when that is under a point. One size for all,
     * the last circle included, the count's "+k" too, so the stack reads even and a member joining
     * at the end does not change the circle before it alone. Kept while the members, the letters
     * they draw, the font, the circle and the ruler hold: the letters are each member's own, in
     * its language, which a window's or a subtree's language moves as well as the process's.
     *
     * @param full  the font an avatar alone would draw its initials in
     * @param d     the circles' diameter
     * @param ruler the ruler the circles shape with
     * @return the font, or {@code null} for no initials
     */
    @Nullable Font initialsFont(Font full, float d, TextRuler ruler) {
        if (fitStamp == initialsStamp && fitD == d && full.equals(fitFrom) && fitRuler == ruler
                && fitRulerEpoch == ruler.epoch() && sameLetters()) {
            return fitFont;
        }
        float r = d / 2;
        float part = step(d) - Avatar.RING;
        float size = full.size();
        fitLetters.clear();
        for (Avatar member : members) {
            String letters = drawnLetters(member);
            if (letters == null) {
                continue;
            }
            fitLetters.add(letters);
            TextMetrics m = ruler.measure(letters, full);
            float room = part - edgeAt(r, m.height());
            if (m.width() > room) {
                size = Math.min(size, (float) Math.floor(full.size() * room / m.width()));
            }
        }
        fitFont = size >= 1 ? (size == full.size() ? full : full.withSize(size)) : null;
        fitFrom = full;
        fitD = d;
        fitRuler = ruler;
        fitRulerEpoch = ruler.epoch();
        fitStamp = initialsStamp;
        return fitFont;
    }

    /** The letters {@code member} draws in this group, or {@code null} for none. */
    private @Nullable String drawnLetters(Avatar member) {
        if (member.parent() != this || member.image() != null) {
            return null; // hidden, or a picture: no letters drawn
        }
        String letters = member.shownInitials(); // read and upper-cased in the member's own language
        return letters.isEmpty() ? null : letters;
    }

    /** Whether the members draw the letters the size was worked out for, in the same order. */
    private boolean sameLetters() {
        int at = 0;
        for (Avatar member : members) {
            String letters = drawnLetters(member);
            if (letters != null && (at >= fitLetters.size() || !letters.equals(fitLetters.get(at++)))) {
                return false;
            }
        }
        return at == fitLetters.size();
    }

    /**
     * How much of {@code member}'s circle shows when another circle is drawn after it, over its
     * trailing quarter and that circle's ring; 0 for the last circle, which nothing covers.
     */
    float shownPart(Widget<?> member, float d) {
        List<Widget<?>> circles = children();
        int at = circles.indexOf(member);
        return at >= 0 && at < circles.size() - 1 ? step(d) - Avatar.RING : 0;
    }

    /**
     * How far in from a circle's side its edge is at the height of a run {@code height} tall
     * centred on it: letters that start nearer would cross the edge at their top or bottom.
     */
    static float edgeAt(float r, float height) {
        float half = Math.min(r, height / 2);
        return r - (float) Math.sqrt(r * r - half * half);
    }

    /**
     * Puts the shown members and the count's circle in the widget tree, in order. The hidden ones
     * are not children: they have no box, and a child without one would still be a node.
     */
    private void rebuild() {
        for (Widget<?> child : List.copyOf(children())) {
            super.remove(child);
        }
        int hidden = hiddenCount();
        int shown = members.size() - hidden;
        for (int i = 0; i < shown; i++) {
            super.add(members.get(i)); // the widget tree's add: the public one joins a member
        }
        if (hidden > 0) {
            super.add(more);
            more.retitle();
        }
        describedStale = true;
        initialsStamp++; // who draws initials, and so their one size, may have changed
        invalidateAccessible();
        markNeedsLayout();
    }

    private float diameter() {
        return Theme.of(this).tokensFor(this).controlHeight();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float d = diameter();
        int n = children().size();
        float w = n == 0 ? 0 : d + (n - 1) * step(d);
        return constraints.constrain(w, n == 0 ? 0 : d);
    }

    /** Each circle starts three quarters of a diameter after the one before: a quarter overlaps. */
    static float step(float d) {
        return d * 0.75f;
    }

    @Override
    protected void onLayout() {
        float d = diameter();
        boolean rtl = isRightToLeft();
        float y = (height() - d) / 2;
        List<Widget<?>> circles = children();
        for (int i = 0; i < circles.size(); i++) {
            float offset = i * step(d);
            // Leading, as a coordinate: from the left end reading left to right, from the right
            // end reading right to left; each later circle is drawn over the one before either way.
            circles.get(i).layoutBox(rtl ? width() - d - offset : offset, y, d, d);
        }
    }

    /** Every member's name, in order, joined by the language's list comma. */
    private String description() {
        long epoch = I18n.epoch();
        // The process's language, and the group's own, which a window's or a subtree's moves.
        Locale in = locale();
        if (describedStale || describedEpoch != epoch || !in.equals(describedIn)) {
            StringBuilder names = new StringBuilder();
            String comma = ComponentStrings.COMBO_SEPARATOR.get();
            for (Avatar member : members) {
                if (names.length() > 0) {
                    names.append(comma);
                }
                names.append(member.name());
            }
            String built = names.toString();
            if (!built.equals(description)) {
                description = built;
                descriptionWitness++;
            }
            describedStale = false;
            describedEpoch = epoch;
            describedIn = in;
        }
        return description;
    }

    /**
     * One {@link Accessible.Role#GROUP}, named by the application and described by every member,
     * the hidden ones included.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.GROUP);
        a.description(description(), descriptionWitness);
    }

    /** The circles inside are not published: the group's description already says every name. */
    @Override
    protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
        a.ignore();
    }

    /**
     * The last place of a group past its limit: a circle of the raised surface holding "+k" in the
     * muted text colour, at the size the group's initials take, with the hidden names as its
     * tooltip. Takes no click.
     */
    private final class More extends Widget<More> {

        private ShapedText line;

        private String text() {
            return ComponentStrings.COMBO_MORE.format(hiddenCount());
        }

        /** The tooltip lists the hidden names, joined by the language's list comma. */
        void retitle() {
            int hidden = hiddenCount();
            if (hidden == 0) {
                setTooltip((String) null);
                return;
            }
            StringBuilder names = new StringBuilder();
            String comma = ComponentStrings.COMBO_SEPARATOR.get();
            for (int i = members.size() - hidden; i < members.size(); i++) {
                if (names.length() > 0) {
                    names.append(comma);
                }
                names.append(members.get(i).name());
            }
            setTooltip(names.toString());
            invalidate();
        }

        @Override
        protected float paintOutset() {
            return Avatar.RING;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            float d = diameter();
            return constraints.constrain(d, d);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float d = Math.min(width(), height());
            float r = d / 2;
            float cx = width() / 2;
            float cy = height() / 2;
            canvas.fillCircle(cx, cy, r + Avatar.RING, theme.surface());
            canvas.fillCircle(cx, cy, r, theme.surfaceRaised());
            // At the group's one size for initials, so the stack reads even (the last circle takes
            // it as every other does), in the label's own weight: a count, not a person's letters.
            Font full = Avatar.initialsFont(this, d);
            Font group = full == null ? null : initialsFont(full, d, textRuler());
            if (group == null) {
                return; // a group too small for letters: the circles alone, the tooltip still says who
            }
            Font font = theme.tokensFor(this).label().withSize(group.size());
            String value = text();
            ShapedText.Direction base = ShapedText.Direction.of(value, neutralBase());
            if (line == null || !line.matches(value, font, base, textRuler())) {
                line = textRuler().shape(value, font, base);
            }
            TextMetrics m = line.metrics();
            canvas.drawText(line, cx - m.width() / 2, cy - m.height() / 2 + m.ascent(), theme.textMuted());
        }
    }
}
