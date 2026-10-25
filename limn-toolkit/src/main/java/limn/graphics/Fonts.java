package limn.graphics;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import limn.backend.spi.Installed;
import limn.concurrent.Ui;
import limn.concurrent.internal.ChangeListeners;
import limn.concurrent.Subscription;
import org.jspecify.annotations.Nullable;

/**
 * Process-wide font configuration (mirrors the {@link TextRulers} / {@code Ui}
 * facade lifecycle). Holds the {@linkplain #installCatalog installed}
 * {@link FontCatalog} (the list of usable families) and the current
 * {@linkplain #defaultFamily() default family} that {@link Font#DEFAULT_FAMILY}
 * resolves to, so an application can switch its UI font at runtime.
 *
 * <p><b>Letting the user choose the font</b> takes three calls: {@link #available()} for the
 * list, rebuilt from {@link #observeChanges} because the list grows when the operating system's
 * families have been found, and {@link #setDefaultFamilyWhenResident} for the choice, which reads
 * the family's regular and bold faces on a worker and switches once they are in memory, so the
 * frame that shows the new font reads no file for them. {@link #setDefaultFamily} switches at
 * once and leaves that read to the next frame. {@link #preload} reads a family ahead without
 * switching to it.
 *
 * <p>Both the catalog becoming available (system-font enumeration finishes) and
 * a default-family change notify {@linkplain #observeChanges listeners} on
 * the calling thread; scenes subscribe to re-layout, and the backend's font
 * store subscribes to drop its resolution cache. Mutators are meant to be called
 * on the UI thread.
 */
public final class Fonts {

    private static final Installed<FontCatalog> CATALOG = new Installed<>(FontCatalog.EMPTY,
            "no FontCatalog installed. Is the backend started?");
    private static final Installed<FontLoader> LOADER = new Installed<>(FontLoader.UNAVAILABLE,
            "no FontLoader installed. Is the backend started?");
    private static volatile String defaultFamily = Font.DEFAULT_FAMILY;
    private static final ChangeListeners listeners = new ChangeListeners();
    /**
     * The one {@link #setDefaultFamilyWhenResident} still waiting for its faces, or {@code null}.
     * Its answer's identity is the request's: a read that lands and finds another answer here was
     * overtaken, and switches nothing. UI thread.
     */
    private static @Nullable PendingSwitch pendingSwitch;

    /**
     * A switch waiting for its faces: the family, the callback the loader was handed for it, which
     * is what {@link FontLoader#withdraw} names when the switch is overtaken, and its answer.
     */
    private record PendingSwitch(String family, Runnable whenResident,
                                 CompletableFuture<Boolean> answer) {
    }

    private Fonts() {
    }

    /** Installs the backend's catalog (e.g. once system-font enumeration completes). */
    public static void installCatalog(@Nullable FontCatalog newCatalog) {
        CATALOG.install(newCatalog == null ? FontCatalog.EMPTY : newCatalog);
        listeners.fire();
    }

    /** Resets to {@link FontCatalog#EMPTY} (backend shutdown). */
    public static void uninstallCatalog(FontCatalog current) {
        CATALOG.uninstall(current);
    }

    /** Installs the backend's file loader (backend startup). */
    public static void installLoader(@Nullable FontLoader newLoader) {
        LOADER.install(newLoader == null ? FontLoader.UNAVAILABLE : newLoader);
    }

    /**
     * Resets to {@link FontLoader#UNAVAILABLE} (backend shutdown). A
     * {@link #setDefaultFamilyWhenResident} still waiting on that loader's read is forgotten: its
     * backend will not deliver it, and it switches nothing.
     */
    public static void uninstallLoader(FontLoader current) {
        if (LOADER.current() == current) {
            pendingSwitch = null;
        }
        LOADER.uninstall(current);
    }

    /**
     * Registers a font file the application ships and returns the family name it declares, so
     * the family can then be named in a {@link Font} or in {@link #setDefaultFamily}.
     *
     * <p>UI thread. <b>Synchronous, and deliberately so; there is no asynchronous form.</b> What
     * this reads is the file's name table, which is kilobytes: the glyph outlines are parsed on
     * first use, exactly as they are for an operating-system face, so the large read this
     * facade could offload is not the read it performs. Offloading it would also defeat the
     * point of the call, because the family has to be registered before the caller builds the
     * first widget that names it; an asynchronous form would hand back a name that resolves to
     * the default face until some later frame, which is the silent-wrong-font failure this API
     * exists to remove. The parse of the faces themselves can leave the frame that first draws
     * them: {@link #preload} the family it returns.
     *
     * <p>A relative path resolves against the process working directory. That makes the call
     * dependent on where the process was started, so an application that ships a typeface
     * should resolve its own path from something it controls rather than assuming a directory.
     *
     * @throws UnsupportedOperationException if no backend is installed (nothing here can parse a
     *                                       font file; see {@link FontLoader})
     * @throws limn.io.MalformedContentException if the file carries no usable face
     */
    public static String load(java.nio.file.Path file) {
        String family = LOADER.current().load(Objects.requireNonNull(file, "file"));
        listeners.fire();
        return family;
    }

    /**
     * Makes {@code family}'s regular and bold faces resident without reading a font file on the
     * UI thread, so that the first measure or paint in that family finds them in memory instead
     * of reading them inside its frame. It does not switch to the family; see
     * {@link #setDefaultFamilyWhenResident} for that.
     *
     * <p>UI thread. The backend reads on its worker pool, and the returned stage completes on the
     * UI thread, on a later turn and <b>never inside this call</b>, once the faces are resident.
     * It completes the same way when there is nothing to read: a family the backend carries
     * itself ({@link Font#DEFAULT_FAMILY} among them), one already resident, one the operating
     * system does not have, a blank name, and any family when no backend has installed a font
     * loader, as under a headless test's runtime. It never completes exceptionally: a face that
     * cannot be read is logged and left out, and the family then resolves as it would have
     * without this call.
     *
     * <p>Asking for a family whose read is already under way shares that read, and a read once
     * started is not withdrawn: what it produces joins the faces already resident, where it is
     * worth the same to whoever names the family next. Italic faces are not read ahead, because
     * regular and bold are what a UI draws on the frame after a switch. A backend keeps a bounded
     * number of faces resident, so preload a family shortly before it is wanted, not at startup
     * for every family a list could offer.
     *
     * <p>If the backend shuts down first, the stage does not complete: there is no UI thread left
     * to complete it on.
     *
     * @param family the family to read ahead, matched as a {@link Font} matches it
     * @return a stage completed on the UI thread once the faces are resident
     * @throws IllegalStateException off the UI thread, or when no {@code UiRuntime} is installed
     */
    public static CompletionStage<Void> preload(String family) {
        Objects.requireNonNull(family, "family");
        Ui.checkUiThread();
        CompletableFuture<Void> resident = new CompletableFuture<>();
        preload(family, () -> resident.complete(null));
        return resident.minimalCompletionStage();
    }

    /** Hands a preload to the installed loader, answering itself the names that need no read. */
    private static void preload(String family, Runnable whenResident) {
        if (answeredHere(family)) {
            // The alias names the embedded face, which the backend carries itself, and a blank
            // name names nothing. Still answered on a later turn, as every other answer is, so a
            // caller never has to tell the two kinds apart.
            Ui.post(whenResident);
            return;
        }
        LOADER.current().preload(family, whenResident);
    }

    /** Whether {@link #preload} answers {@code family} itself rather than asking the loader. */
    private static boolean answeredHere(String family) {
        return family.isBlank() || family.equalsIgnoreCase(Font.DEFAULT_FAMILY);
    }

    /**
     * @return the available families (bundled + system), or empty when headless. Reads no file and
     *         does not block. It is the installed catalog's current answer, which may be a partial
     *         one: a backend that enumerates the operating system does so in the background and
     *         installs a fuller catalog when it finishes. Rebuild a family list from
     *         {@linkplain #observeChanges a change listener} rather than reading this once at
     *         startup and trusting it.
     */
    public static List<String> available() {
        return CATALOG.current().families();
    }

    /** @return the family {@link Font#DEFAULT_FAMILY} currently resolves to */
    public static String defaultFamily() {
        return defaultFamily;
    }

    /**
     * Sets the family the default UI font resolves to; {@code null}/blank restores
     * {@link Font#DEFAULT_FAMILY}. No-op if unchanged. Notifies listeners so the
     * UI re-lays-out in the new font. UI thread.
     *
     * <p>A switch that {@link #setDefaultFamilyWhenResident} is still waiting to make is
     * abandoned by this call, whether or not the name changes: the later choice wins.
     *
     * <p>This call itself reads nothing: it stores a name and runs the listeners. The cost it
     * schedules is the part worth knowing. A backend resolves families to font files lazily and
     * drops its resolution cache when this fires, so the <em>next</em> measure or paint resolves
     * the new family, and if its faces are not already resident, that resolution reads and parses
     * a font file (tens of megabytes for a CJK face) on the UI thread, inside that frame.
     *
     * <p>{@link #setDefaultFamilyWhenResident} is the form that keeps that read out of the frame,
     * and the one a font picker or a settings screen calls: it reads the faces on a worker and
     * makes this same switch once they are resident. This form is for a switch that has to have
     * happened when the call returns: before the first window opens, or in a test or a capture
     * that draws next. Called from a menu or a settings dialog, its one long frame reads as the
     * switch happening; never call it from an animation, a drag, or anything that must stay at
     * frame rate. It costs once per face: switching back to a family already read is cheap, and
     * so is switching to one {@link #preload} has made resident.
     */
    public static void setDefaultFamily(@Nullable String family) {
        checkUiThreadIfInstalled();
        abandonPendingSwitch();
        switchTo(normalized(family));
    }

    /**
     * Sets the family the default UI font resolves to once its faces are resident: reads them on
     * a worker as {@link #preload} does, then makes the switch {@link #setDefaultFamily} makes,
     * on the UI thread, so the frame that shows the new font reads no file for its regular or bold
     * text; an italic face, not read ahead, is still read where it is first drawn.
     * {@code null}/blank restores {@link Font#DEFAULT_FAMILY}. The call a font picker and a
     * settings screen make.
     *
     * <p>UI thread. Nothing changes inside this call: until the faces are resident, text stays in
     * the family it was in. The returned stage completes on the UI thread, on a later turn and
     * never inside this call, with {@code true} once {@code family} is the default family, which
     * is right after the switch has notified {@linkplain #observeChanges listeners} (they are not
     * notified when it already was), or with {@code false} when a later {@code setDefaultFamily}
     * or {@code setDefaultFamilyWhenResident} came first and this one switched nothing.
     *
     * <p><b>The later choice wins.</b> A font picker moving through a list asks once per entry,
     * and only the last request switches: an earlier one whose read lands afterwards leaves the
     * default family where the later call put it, or is about to, and answers {@code false}.
     * The overtaken request is {@linkplain FontLoader#withdraw withdrawn} from the backend, whose
     * read cannot be called back once started, and what that read produces disturbs nothing: it
     * lays nothing out again, and the backend keeps it only in room it can make without letting
     * go of a face in use, so stepping back to that family is cheap while the backend has room
     * for it, and costs another read off the UI thread when it had none. Asking again for the
     * family a pending call names replaces that call too: the first stage answers {@code false}
     * and the second {@code true}, and the backend reads the faces once for both.
     *
     * <p>A family the operating system does not have is switched to as {@code setDefaultFamily}
     * would switch to it, and then resolves to the toolkit's embedded face. If the backend shuts
     * down before the faces are resident, nothing switches and the stage does not complete.
     *
     * <p>There is nothing to start: the name does not end in {@code Async} because the toolkit
     * keeps that suffix for an unstarted {@link limn.concurrent.Work}. This call has started when
     * it returns and answers with a stage, as a dialog's {@code show} does, and what withdraws it
     * is the next choice rather than a cancel.
     *
     * @param family the family to switch to, or {@code null}/blank for {@link Font#DEFAULT_FAMILY}
     * @return a stage completed on the UI thread with whether this call's family became the
     *         default family
     * @throws IllegalStateException off the UI thread, or when no {@code UiRuntime} is installed
     */
    public static CompletionStage<Boolean> setDefaultFamilyWhenResident(@Nullable String family) {
        Ui.checkUiThread();
        String value = normalized(family);
        abandonPendingSwitch();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Runnable whenResident = () -> {
            PendingSwitch pending = pendingSwitch;
            if (pending == null || pending.answer() != answer) {
                return; // overtaken: the later call has answered this one already
            }
            pendingSwitch = null;
            switchTo(value);
            answer.complete(true);
        };
        pendingSwitch = new PendingSwitch(value, whenResident, answer);
        preload(value, whenResident);
        return answer.minimalCompletionStage();
    }

    /** Makes {@code value} the default family and tells the listeners, if it is not already. */
    private static void switchTo(String value) {
        if (value.equals(defaultFamily)) {
            return;
        }
        defaultFamily = value;
        listeners.fire();
    }

    /**
     * Withdraws the switch {@link #setDefaultFamilyWhenResident} is waiting to make, answering it
     * {@code false} on the UI thread's next turn rather than inside the call that overtook it, and
     * takes its request back from the loader, so that a read landing with nobody left wanting it
     * disturbs nothing in the backend (see {@link FontLoader#withdraw}).
     */
    private static void abandonPendingSwitch() {
        PendingSwitch abandoned = pendingSwitch;
        if (abandoned == null) {
            return;
        }
        pendingSwitch = null;
        if (!answeredHere(abandoned.family())) {
            LOADER.current().withdraw(abandoned.family(), abandoned.whenResident());
        }
        if (Ui.isInstalled()) {
            Ui.post(() -> abandoned.answer().complete(false));
        }
    }

    private static String normalized(@Nullable String family) {
        return family == null || family.isBlank() ? Font.DEFAULT_FAMILY : family;
    }

    /**
     * The thread check of the process-wide axes, skipped when no runtime is installed, as
     * {@code I18n}'s is: a family is settable from a plain unit test with no window.
     */
    private static void checkUiThreadIfInstalled() {
        if (Ui.isInstalled()) {
            Ui.checkUiThread();
        }
    }

    /**
     * Subscribes to catalog and default-family changes.
     *
     * @param listener what to run when either moves
     * @return a handle that unsubscribes; cancelling it twice is a no-op. UI thread
     */
    public static Subscription observeChanges(Runnable listener) {
        return listeners.observe(listener);
    }


}
