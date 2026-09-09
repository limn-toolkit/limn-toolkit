package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each of the toolkit's relations is called on this platform, in AT-SPI2's own numbering.
 *
 * <p>The numbers are {@code AtspiRelationType} enumerators; see the note on {@link Atspi} for
 * where they were read. Every relation the toolkit can publish has a number here, and
 * {@code AtspiConstantsTest} holds that as a ratchet: a relation added to the model with no
 * reading fails there rather than being dropped from every relation set in silence.
 */
final class AtspiRelations {

    private AtspiRelations() {
    }

    private static final Map<Accessible.Relation, Integer> NUMBER =
            new EnumMap<>(Accessible.Relation.class);

    static {
        NUMBER.put(Accessible.Relation.LABELLED_BY, Atspi.RELATION_LABELLED_BY);
        NUMBER.put(Accessible.Relation.LABEL_FOR, Atspi.RELATION_LABEL_FOR);
        NUMBER.put(Accessible.Relation.DESCRIBED_BY, Atspi.RELATION_DESCRIBED_BY);
        NUMBER.put(Accessible.Relation.CONTROLLER_FOR, Atspi.RELATION_CONTROLLER_FOR);
        NUMBER.put(Accessible.Relation.CONTROLLED_BY, Atspi.RELATION_CONTROLLED_BY);
        NUMBER.put(Accessible.Relation.MEMBER_OF, Atspi.RELATION_MEMBER_OF);
        NUMBER.put(Accessible.Relation.POPUP_FOR, Atspi.RELATION_POPUP_FOR);
    }

    /**
     * @param relation one of the toolkit's relations
     * @return its {@code AtspiRelationType} number, or {@code null} when this platform has none
     */
    static Integer of(Accessible.Relation relation) {
        return NUMBER.get(relation);
    }
}
