package limn.backend.lwjgl.a11y.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import limn.backend.spi.TrayPicture;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import org.jspecify.annotations.Nullable;

/**
 * The files of a tray's monochrome icon, for a panel that colours a named shape itself: one file
 * per picture, written once, under a name that stays the picture's for as long as the shape lasts.
 *
 * <p>An animated shape is the reason for the last part. The tray hands its peer a state per frame,
 * and a file written per frame cost a render, a PNG encode, a folder emptied and a file written on
 * the UI thread at every one of them, for ever; the panel was also shown the first frame each time
 * (2026-10-16). Kept per frame, a loop of the animation writes each frame once, and from then on a
 * frame swap only changes the name the panel is told. A panel keeps what it loaded under a name it
 * already knows, which is right here because a name's file never changes: another icon, dot or
 * corner starts a new generation of names, and the old files go.
 */
final class TrayShapeFiles {

    private final String id;
    private final Path all;
    private final Path folder;
    /** What the current generation is of: the shape without its frame. */
    private @Nullable Generation current;
    private final Map<Integer, TrayWire.Named> frames = new HashMap<>();
    private int generations;
    private int written;

    /** What a generation of names holds in common: everything but the frame. */
    private record Generation(Icon icon, @Nullable Color badge, boolean badgeAtLeft) {
    }

    /**
     * @param id  the application's identifier for the icon
     * @param all the folder every process's tray folders are in, inside the session's runtime folder
     */
    TrayShapeFiles(String id, Path all) {
        this.id = id;
        this.all = all;
        this.folder = all.resolve(ProcessHandle.current().pid() + "-" + id).resolve("icons");
    }

    /**
     * The file of {@code shape}, written if this generation has not written that frame yet.
     *
     * @throws IOException when the file could not be written
     */
    TrayWire.Named write(TrayPicture shape) throws IOException {
        Generation generation = new Generation(shape.icon(), shape.badge(), shape.badgeAtLeft());
        if (!generation.equals(current)) {
            delete();
            sweep(all);
            current = generation;
            generations++;
        }
        TrayWire.Named known = frames.get(shape.frame());
        if (known != null) {
            return known;
        }
        byte[] png = Images.encode(new TrayPicture(shape.icon(), null, false, shape.frame())
                .render(96, Color.rgb(0xFFFFFF), true), ImageFormat.PNG);
        Files.createDirectories(folder);
        String name = "limn-" + id + "-" + generations + "-" + shape.frame() + "-symbolic";
        Files.writeString(folder.resolve(name + ".svg"),
                TrayWire.symbolicSvg(png, shape.badge(), shape.badgeAtLeft()));
        written++;
        TrayWire.Named named = new TrayWire.Named(name, folder.toString());
        frames.put(shape.frame(), named);
        return named;
    }

    /** How many files have been written, for tests. */
    int written() {
        return written;
    }

    /** The folder the files are in. */
    Path folder() {
        return folder;
    }

    /** Takes this tray's files away, and its folder; the next {@link #write} starts again. */
    void delete() {
        current = null;
        frames.clear();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(folder);
            Files.deleteIfExists(folder.getParent());
        } catch (IOException | RuntimeException ignored) {
            // The runtime folder is the session's and goes with it.
        }
    }

    /** Removes the folders of processes that ended without taking theirs away. */
    private static void sweep(Path all) {
        if (!Files.isDirectory(all)) {
            return;
        }
        try (Stream<Path> each = Files.list(all)) {
            for (Path one : each.toList()) {
                String name = one.getFileName().toString();
                int dash = name.indexOf('-');
                long pid;
                try {
                    pid = Long.parseLong(dash < 0 ? name : name.substring(0, dash));
                } catch (NumberFormatException notOurs) {
                    continue;
                }
                if (ProcessHandle.of(pid).isPresent()) {
                    continue;
                }
                Path icons = one.resolve("icons");
                if (Files.isDirectory(icons)) {
                    try (Stream<Path> files = Files.list(icons)) {
                        List<Path> list = files.toList();
                        for (Path file : list) {
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.deleteIfExists(icons);
                }
                Files.deleteIfExists(one);
            }
        } catch (IOException | RuntimeException ignored) {
            // Somebody else's, or already gone.
        }
    }
}
