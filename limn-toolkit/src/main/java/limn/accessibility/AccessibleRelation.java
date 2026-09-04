package limn.accessibility;

/**
 * One link from a node to another node of the same tree, by identifier.
 *
 * <p>By identifier and not by reference, for the reason the whole snapshot holds no references: a
 * client can hold one node for minutes, and a link that pinned another would keep a subtree alive
 * that nothing on screen refers to any more.
 *
 * @param kind   what the link means
 * @param target the identifier of the node at the other end; always a node of the same tree
 */
public record AccessibleRelation(Accessible.Relation kind, long target) {

    /** @throws NullPointerException if {@code kind} is {@code null} */
    public AccessibleRelation {
        java.util.Objects.requireNonNull(kind, "kind");
    }
}
