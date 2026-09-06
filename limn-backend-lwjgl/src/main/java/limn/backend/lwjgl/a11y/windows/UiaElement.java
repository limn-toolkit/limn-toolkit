package limn.backend.lwjgl.a11y.windows;

/**
 * One COM object standing for one node of the published tree, as far as the registry is concerned.
 *
 * <p>Deliberately this small. The registry's whole job is identity and lifetime, and neither needs
 * to know what a provider answers — so an element here is a node identifier, the interface pointer
 * a client holds, and the one operation the registry performs on it. That is also what makes the
 * registry testable on a machine with no COM at all: the rules it enforces are about maps and
 * threads, and those are the rules that bite.
 *
 * <p><b>No element ever holds a widget</b>, for ADR 039 §1.2's reason: a client can hold an element
 * for minutes, and one that reached a widget would pin a detached subtree for exactly that long.
 */
interface UiaElement {

    /** @return the identifier of the node this element stands for, stable across republishes */
    long nodeId();

    /**
     * @return the {@code IRawElementProviderSimple} pointer handed to clients, and the key a call
     *         arriving on an RPC thread is recovered by
     */
    long pointer();

    /**
     * Drops this bridge's own reference.
     *
     * <p><b>Not a destruction.</b> The COM object outlives this call by however long a client keeps
     * its own reference, and answers {@code UIA_E_ELEMENTNOTAVAILABLE} for the whole of that time —
     * which is precisely the behaviour §1.3 promises to a client holding an element for a node that
     * has left the tree. Calling it twice for one element is what the registry's protocol exists to
     * prevent.
     */
    void release();
}
