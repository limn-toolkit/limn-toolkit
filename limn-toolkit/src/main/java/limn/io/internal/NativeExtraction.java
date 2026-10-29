package limn.io.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Copies native libraries a jar carries out to a directory they can be loaded from, safely: the
 * one way every Limn module that loads a native of its own payload does it, the FFmpeg decoder and
 * the SVG renderer alike.
 *
 * <p>The directory is named after a digest of the libraries themselves, so two builds never share
 * one and a directory left by an older version is never consulted again. It lies inside a directory
 * of this user's own under the cache root, created owner-only and verified before anything is
 * loaded out of it, because the cache root is shared ({@code /tmp} on most Unix systems) and the
 * digest is taken over bytes another account can read. Files are staged in a temporary directory
 * and renamed into place, so two applications extracting at once is ordinary and the loser uses
 * the winner's copy.
 */
public final class NativeExtraction {

    private final String prefix;
    private final String cacheProperty;

    /**
     * @param prefix        the start of this user's directory's name, such as {@code "limn-ffmpeg"}
     * @param cacheProperty the system property that names another cache root than the temporary
     *                      directory
     */
    public NativeExtraction(String prefix, String cacheProperty) {
        this.prefix = prefix;
        this.cacheProperty = cacheProperty;
    }

    /**
     * Extracts the named resources under {@code root} to a directory of their own, or finds them
     * extracted already.
     *
     * @param loader        what the resources are read through
     * @param root          the resources' folder, ending in {@code /}
     * @param names         the files, the first being the one whose presence marks a complete
     *                      extraction
     * @param salt          more text the directory's digest covers, such as a manifest
     * @return the directory the files lie in
     * @throws IOException naming what failed: a resource missing, a directory not this user's own
     */
    public Path extract(ClassLoader loader, String root, List<String> names, String salt) throws IOException {
        String digest = digestOf(loader, root, names, salt);
        Path base = privateBase();
        Path target = base.resolve(digest);
        if (complete(target, names)) {
            return target;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            setAside(base, target);
        }

        Path staging = Files.createTempDirectory(base, "staging-");
        try {
            for (String name : names) {
                try (InputStream in = loader.getResourceAsStream(root + name)) {
                    if (in == null) {
                        throw new IOException(name + " is named but is not in this build");
                    }
                    Files.copy(in, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
                // The whole set is made executable: a dependency without the bit fails to map on
                // some hardened Linux configurations, and the failure names the library that
                // loaded it rather than the file that actually lacked it.
                staging.resolve(name).toFile().setExecutable(true, true);
            }
            moveIntoPlace(staging, target, names.get(0));
            return target;
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Whether {@code target} is a directory holding every one of {@code names}. */
    private static boolean complete(Path target, List<String> names) {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        for (String name : names) {
            if (!Files.isRegularFile(target.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves an incomplete directory at {@code target} out of the way and deletes it, so that the
     * staged one can be renamed there.
     *
     * <p>A directory is only ever renamed to its target whole, so one that is there and incomplete
     * was emptied afterwards, and the usual hand is a cleaner of temporary files: Windows' own
     * deletes files it judges old and leaves their directories. Left in place it fails every later
     * extraction's rename, since a directory cannot be renamed onto an existing one, and the native
     * never loads again for this user. It lies in this user's private directory, so nobody else
     * could have made it.
     *
     * <p>Renaming it first, rather than deleting it where it is, means a rename that fails leaves it
     * whole: on Windows that is a directory a running process still holds a library open in, and
     * the move that follows reports the collision as it always did.
     */
    private static void setAside(Path base, Path target) {
        try {
            Path aside = Files.createTempDirectory(base, "stale-");
            Files.delete(aside);
            Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
            deleteQuietly(aside);
        } catch (IOException held) {
            // Left for moveIntoPlace, which reports it unless a complete copy is there by then.
        }
    }

    /**
     * Renames the staged directory into place, treating "somebody got there first" as success.
     *
     * <p>Two of this user's applications extracting at once is ordinary, and the loser of the
     * rename uses the winner's copy: the digest is taken over the content, so both directories
     * hold the same bytes. What decides that a failed move was that race is <b>the directory being
     * there and complete afterwards</b>, not the class of the exception. Every plausible spelling
     * turned up in practice: a rename onto an existing non-empty directory is {@code ENOTEMPTY} on
     * Unix, which the JDK does not map to {@link DirectoryNotEmptyException}; it arrives as a bare
     * {@link java.nio.file.FileSystemException} whose only distinguishing mark is a message no code
     * should read. Anything else is rethrown, so a full disk still reports a full disk.
     *
     * <p>The nesting is the other half. The fallback for a file system that cannot rename
     * atomically is a second move, and it loses the same race; one catch around both is what stops
     * it from reporting a missing native while a complete extraction sits at {@code target}.
     *
     * @param staging the directory the files were copied into
     * @param target  where they belong
     * @param marker  a file the winner's directory must hold for it to count as a complete
     *                extraction, the same one the reuse path looks for
     * @throws IOException when the move failed and no complete extraction is at {@code target}
     */
    public static void moveIntoPlace(Path staging, Path target, String marker) throws IOException {
        try {
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(staging, target);
            }
        } catch (IOException collided) {
            if (!(Files.isDirectory(target) && Files.isRegularFile(target.resolve(marker)))) {
                throw collided;
            }
            // The winner's directory stays; this one's staging goes in the caller's finally.
        }
    }

    private Path cacheRoot() {
        String configured = System.getProperty(cacheProperty);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }

    /**
     * The directory the digest-named ones are extracted into: one of this user's own, under the
     * cache root, created owner-only and verified before anything is loaded out of it.
     *
     * <p>Nothing is written to the cache root itself. It is shared ({@code /tmp} on most Unix
     * systems), and the digest is taken over bytes that ship in the application, so another
     * account can compute the extraction path offline, create it first and have its own libraries
     * loaded into this process. The private directory is what makes the rest of extraction, and
     * the race between two extractions, a question about this user's own processes only.
     *
     * @return this user's own directory under the cache root
     * @throws IOException when it cannot be made, or is not this user's alone
     */
    public Path privateBase() throws IOException {
        Path root = cacheRoot();
        Files.createDirectories(root);
        UserPrincipal user = self(root);
        Path base = root.resolve(prefix + "-" + ownerTag(user));
        try {
            Files.createDirectory(base, ownerOnly(root));
        } catch (FileAlreadyExistsException existing) {
            // This user's own directory from an earlier run, ordinarily. Or somebody else's,
            // which is what the check below is for.
        }
        requirePrivateDirectory(base, user);
        return base;
    }

    /**
     * Refuses a directory another account could have planted, or could still write into.
     *
     * @param directory the directory to check
     * @param user      this user, as {@code Files.getOwner} names a file this process made
     * @throws IOException naming what is wrong with it, so a loader's failure can say
     */
    public static void requirePrivateDirectory(Path directory, UserPrincipal user) throws IOException {
        // NOFOLLOW on every question: a symbolic link left at this path is the attack, and asking
        // about its target instead would answer about a directory that is indeed fine.
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(directory + " is not a directory");
        }
        if (!Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(user)) {
            throw new IOException(directory + " belongs to another account");
        }
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException(directory + " is writable by other accounts");
            }
        }
    }

    /**
     * The owner of a file this process has just made: this user, however the platform spells it.
     * Looking {@code user.name} up in the file system's principal service instead would have to
     * guess at Windows' domain-qualified form and at what an identity-mapped mount reports, and
     * a wrong guess refuses a directory that is perfectly this user's own.
     */
    private static UserPrincipal self(Path writable) throws IOException {
        Path probe = Files.createTempFile(writable, "limn-owner-", "");
        try {
            return Files.getOwner(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /**
     * A directory name for the principal that is about to own it, ASCII and short whatever the
     * account is called.
     *
     * <p><b>Taken from the owner and not from {@code user.name}</b>, because the owner is what
     * {@link #requirePrivateDirectory} compares against and the two must not be able to disagree
     * about who this is. On Windows they can: a process holding an elevated token creates its
     * objects owned by the Administrators group rather than by the person, so one run of an
     * application and a differently-elevated run of the same application by the same user arrive
     * with two different owners. Naming the directory after the owner gives each of them one of
     * its own, which is what lets the check stay strict; the mismatch stops arising instead of
     * having to be tolerated. {@code user.name} could not do that: it is a system property, it is
     * identical across both runs, and it is settable on the command line.
     *
     * <p>The readable half is a convenience and the digest is the identity, so two principals
     * whose names sanitise alike still get separate directories rather than queueing behind one.
     * The ownership check, never the name, remains what makes either of them safe.
     *
     * <p>A principal name is localized on Windows ({@code BUILTIN\Administrators} is
     * {@code BUILTIN\Administradores} on a Portuguese install), and that costs nothing here,
     * because the directory only has to be stable on this machine. Changing the system language
     * orphans the previous one, which spends disk and not correctness.
     *
     * @param owner the principal that is about to own the directory
     * @return the directory's name
     * @throws IOException when no SHA-256 is available
     */
    public static String ownerTag(UserPrincipal owner) throws IOException {
        String name = owner.getName();
        StringBuilder tag = new StringBuilder();
        for (int i = 0; i < name.length() && tag.length() < 24; i++) {
            char c = name.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            tag.append(plain ? c : '_');
        }
        if (tag.isEmpty()) {
            tag.append("user");
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("no SHA-256", impossible);
        }
        return tag + "-"
                + HexFormat.of().formatHex(sha.digest(name.getBytes(StandardCharsets.UTF_8)), 0, 6);
    }

    /** {@code rwx------} where the file system understands it, nothing where it does not. */
    private static FileAttribute<?>[] ownerOnly(Path where) {
        if (!where.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Windows: the temporary directory is already per-user there, and an ACL set here
            // would be a second mechanism to get wrong for no ground it does not already cover.
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        };
    }

    /**
     * A digest of every library's bytes, so the directory name changes whenever the content does.
     * Naming the directory after a version instead would leave an application that was rebuilt
     * without a version bump loading the previous library for as long as the temporary directory
     * survived, which is exactly the bug that is hardest to believe while looking at it.
     */
    private static String digestOf(ClassLoader loader, String root, List<String> names, String salt)
            throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("no SHA-256", impossible);
        }
        sha.update(salt.getBytes(StandardCharsets.UTF_8));
        byte[] chunk = new byte[64 * 1024];
        for (String name : names) {
            try (InputStream in = loader.getResourceAsStream(root + name)) {
                if (in == null) {
                    throw new IOException(name + " is named but is not in this build");
                }
                int read;
                while ((read = in.read(chunk)) > 0) {
                    sha.update(chunk, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(sha.digest(), 0, 10);
    }

    private static void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover staging directory in the temporary directory is litter, not a
                    // failure, and reporting it would replace a working load with an error.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
