package limn.backend.lwjgl;

import org.lwjgl.system.Platform;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Enumerates the fonts installed on the operating system, purely by scanning the
 * platform's font directories and parsing each file's OpenType {@code name}
 * table (no AWT, no native font API). Memory-frugal by design: enumeration reads
 * only the sfnt header and {@code name} table of each file (a few KB) via random
 * access, never loading the whole font. A folder full of multi-megabyte CJK
 * faces therefore costs kilobytes to list. The full face is only loaded later,
 * on demand, when a family is actually selected (see {@link FontStore}).
 *
 * <p>Each returned {@link Face} carries the family, style, file path and face
 * index (for {@code .ttc} collections) needed to load it.
 */
final class SystemFonts {

    private static final System.Logger LOG = System.getLogger(SystemFonts.class.getName());

    /** A single face discovered on disk: enough metadata to load it lazily. */
    record Face(String family, String style, Path path, int index, boolean bold, boolean italic) {
    }

    private SystemFonts() {
    }

    // sfnt / table tags as big-endian ints.
    private static final int TAG_TTCF = 0x74746366; // 'ttcf' (collection)
    private static final int TAG_TRUE = 0x74727565; // 'true' (legacy Mac TrueType)
    private static final int TAG_OTTO = 0x4F54544F; // 'OTTO' (CFF/OpenType)
    private static final int SFNT_1_0 = 0x00010000; // TrueType outlines
    private static final int TAG_NAME = 0x6E616D65; // 'name'

    /** Scans the platform font directories. Slow (I/O): call off the UI thread; cache the result. */
    static List<Face> scan() {
        List<Face> faces = new ArrayList<>();
        for (Path dir : fontDirectories()) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir, 8)) {
                walk.filter(Files::isRegularFile)
                        .filter(SystemFonts::looksLikeFont)
                        .forEach(path -> readFaces(path, faces));
            } catch (IOException | RuntimeException error) {
                LOG.log(System.Logger.Level.DEBUG, "skipping font dir " + dir, error);
            }
        }
        return faces;
    }

    private static List<Path> fontDirectories() {
        String home = System.getProperty("user.home", "");
        return switch (Platform.get()) {
            case MACOSX -> List.of(
                    Path.of("/System/Library/Fonts"),
                    Path.of("/Library/Fonts"),
                    Path.of(home, "Library/Fonts"));
            case WINDOWS -> {
                String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
                String localAppData = System.getenv("LOCALAPPDATA");
                List<Path> dirs = new ArrayList<>();
                dirs.add(Path.of(windir, "Fonts"));
                if (localAppData != null) {
                    dirs.add(Path.of(localAppData, "Microsoft", "Windows", "Fonts"));
                }
                yield dirs;
            }
            default -> List.of( // Linux / BSD
                    Path.of("/usr/share/fonts"),
                    Path.of("/usr/local/share/fonts"),
                    Path.of(home, ".fonts"),
                    Path.of(home, ".local/share/fonts"));
        };
    }

    private static boolean looksLikeFont(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    /** Reads every face in a single font file; this is the parser entry point (also for tests). */
    static List<Face> facesIn(Path path) {
        List<Face> out = new ArrayList<>();
        readFaces(path, out);
        return out;
    }

    /** Reads every face in {@code path} (a .ttc holds several), appending to {@code out}. */
    private static void readFaces(Path path, List<Face> out) {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            int tag = file.readInt();
            if (tag == TAG_TTCF) {
                file.skipBytes(4); // majorVersion + minorVersion
                int numFonts = file.readInt();
                if (numFonts <= 0 || numFonts > 1024) {
                    return;
                }
                int[] offsets = new int[numFonts];
                for (int i = 0; i < numFonts; i++) {
                    offsets[i] = file.readInt();
                }
                for (int i = 0; i < numFonts; i++) {
                    readFaceAt(file, offsets[i] & 0xFFFFFFFFL, path, i, out);
                }
            } else if (tag == SFNT_1_0 || tag == TAG_OTTO || tag == TAG_TRUE) {
                readFaceAt(file, 0, path, 0, out);
            }
        } catch (IOException | RuntimeException error) {
            // Unreadable / malformed file: skip it, never fail the whole scan.
            LOG.log(System.Logger.Level.TRACE, "unreadable font " + path, error);
        }
    }

    /** Parses the table directory at {@code sfntOffset}, then the 'name' table. */
    private static void readFaceAt(RandomAccessFile file, long sfntOffset, Path path, int index,
                                   List<Face> out) throws IOException {
        file.seek(sfntOffset);
        file.readInt(); // sfntVersion (re-read; ignored)
        int numTables = file.readUnsignedShort();
        file.skipBytes(6); // searchRange + entrySelector + rangeShift
        long nameOffset = -1;
        for (int i = 0; i < numTables; i++) {
            int tableTag = file.readInt();
            file.skipBytes(4); // checksum
            long offset = file.readInt() & 0xFFFFFFFFL;
            file.skipBytes(4); // length (unused)
            if (tableTag == TAG_NAME) {
                nameOffset = offset;
            }
        }
        if (nameOffset < 0) {
            return;
        }
        Face face = readName(file, nameOffset, path, index);
        if (face != null) {
            out.add(face);
        }
    }

    /** Best decoded name plus the score of the record it came from (higher = more preferred). */
    private static final class Best {
        String value;
        int score = Integer.MIN_VALUE;
    }

    /** Extracts family (nameID 1/16) and style (nameID 2/17) from the 'name' table. */
    private static Face readName(RandomAccessFile file, long nameOffset, Path path, int index)
            throws IOException {
        file.seek(nameOffset);
        file.readUnsignedShort(); // format
        int count = file.readUnsignedShort();
        int storageOffset = file.readUnsignedShort();
        if (count <= 0 || count > 4096) {
            return null;
        }
        int[] platform = new int[count];
        int[] encoding = new int[count];
        int[] language = new int[count];
        int[] nameId = new int[count];
        int[] length = new int[count];
        int[] strOffset = new int[count];
        for (int i = 0; i < count; i++) {
            platform[i] = file.readUnsignedShort();
            encoding[i] = file.readUnsignedShort();
            language[i] = file.readUnsignedShort();
            nameId[i] = file.readUnsignedShort();
            length[i] = file.readUnsignedShort();
            strOffset[i] = file.readUnsignedShort();
        }
        long storageBase = nameOffset + storageOffset;
        Best family = new Best();
        Best familyTypographic = new Best();
        Best style = new Best();
        Best styleTypographic = new Best();
        for (int i = 0; i < count; i++) {
            int id = nameId[i];
            if ((id != 1 && id != 2 && id != 16 && id != 17)
                    || !decodable(platform[i], encoding[i])) {
                continue;
            }
            // Prefer an English/Latin record so we never surface a name in a script
            // the UI font can't draw; skip anything that scores no better than what we have.
            int score = score(platform[i], language[i]);
            Best target = switch (id) {
                case 1 -> family;
                case 16 -> familyTypographic;
                case 2 -> style;
                default -> styleTypographic;
            };
            if (score <= target.score) {
                continue;
            }
            String value = decode(file, storageBase + strOffset[i], length[i], platform[i]);
            if (value == null || value.isBlank()) {
                continue;
            }
            target.value = value;
            target.score = score;
        }
        String resolvedFamily = sanitize(pick(familyTypographic, family));
        // Skip macOS internal faces ('.SF NS', '.AppleSystemUIFont', …), which are not
        // usable, and anything whose name failed to decode into a clean, non-empty string.
        if (resolvedFamily.isEmpty() || resolvedFamily.charAt(0) == '.') {
            return null;
        }
        String resolvedStyle = sanitize(pick(styleTypographic, style));
        if (resolvedStyle.isEmpty()) {
            resolvedStyle = "Regular";
        }
        String lower = resolvedStyle.toLowerCase(Locale.ROOT);
        boolean bold = lower.contains("bold");
        boolean italic = lower.contains("italic") || lower.contains("oblique");
        return new Face(resolvedFamily, resolvedStyle, path, index, bold, italic);
    }

    private static String pick(Best typographic, Best legacy) {
        return typographic.value != null ? typographic.value
                : legacy.value != null ? legacy.value : "";
    }

    /** Whether a name record's platform/encoding is one we can decode to text. */
    private static boolean decodable(int platformId, int encodingId) {
        return switch (platformId) {
            case 0 -> true;               // Unicode → UTF-16BE
            case 1 -> encodingId == 0;    // Macintosh: Roman only (skip MacJapanese, etc.)
            case 3 -> encodingId == 1 || encodingId == 0 || encodingId == 10; // Windows Unicode/Symbol
            default -> false;
        };
    }

    /** Higher = more preferred: an English, Unicode/Windows/Mac-English record. */
    private static int score(int platformId, int languageId) {
        if (platformId == 3 && languageId == 0x0409) {
            return 5; // Windows English (US)
        }
        if (platformId == 3 && (languageId & 0x3FF) == 0x09) {
            return 4; // any Windows English sublanguage
        }
        if (platformId == 1 && languageId == 0) {
            return 4; // Mac English
        }
        if (platformId == 0) {
            return 3; // Unicode (usually the default/English name)
        }
        if (platformId == 3) {
            return 1; // other Windows language
        }
        return 0; // other Mac language
    }

    private static String decode(RandomAccessFile file, long offset, int length, int platformId)
            throws IOException {
        if (length <= 0 || length > 512) {
            return null; // family/style names are short; guard against junk
        }
        byte[] bytes = new byte[length];
        file.seek(offset);
        file.readFully(bytes);
        return platformId == 1
                ? new String(bytes, StandardCharsets.ISO_8859_1) // Mac Roman (ASCII range)
                : new String(bytes, StandardCharsets.UTF_16BE);   // Windows / Unicode
    }

    /** Trims, drops control characters, and rejects (as empty) names with a decode-failure marker. */
    private static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '�') {
                return ""; // replacement char → the record didn't decode cleanly; unusable
            }
            if (c == '\t' || c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }
}
