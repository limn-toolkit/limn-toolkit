package limn.backend.lwjgl;

import limn.backend.Platform;

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

    /**
     * A single face discovered on disk: enough metadata to load it lazily.
     *
     * <p>{@code weight} is the face's own {@code OS/2 usWeightClass} (100 thin to 900 black) and
     * {@code width} its {@code usWidthClass} (5 normal, lower condensed, higher expanded), because a
     * family of a dozen faces cannot be told apart by two booleans: "Medium", "Heavy" and "Ultra
     * Light" are all neither bold nor italic, and picking among them by file order drew a
     * family's Medium where its Regular was asked for. {@code bold} is {@code weight >= 600}.
     *
     * <p>A variable face covers a range of weights, {@code minWeight} to {@code maxWeight} from its
     * {@code fvar} table's {@code wght} axis, and {@code weight} is its default instance; a static
     * face's range is its one weight. {@code path} is {@code null} for a face the backend bundles,
     * which is described here so one search picks among bundled and system faces alike.
     */
    record Face(String family, String style, Path path, int index, boolean bold, boolean italic,
                int weight, int width, int minWeight, int maxWeight) {

        /** A static face of a weight and a width. */
        Face(String family, String style, Path path, int index, boolean bold, boolean italic,
             int weight, int width) {
            this(family, style, path, index, bold, italic, weight, width, weight, weight);
        }

        /** A face known only by its two flags: regular or bold weight, normal width. */
        Face(String family, String style, Path path, int index, boolean bold, boolean italic) {
            this(family, style, path, index, bold, italic, bold ? 700 : 400, NORMAL_WIDTH);
        }

        /** Whether this face is drawn at more than one weight. */
        boolean variable() {
            return minWeight < maxWeight;
        }

        /** The weight this face draws a request for {@code requested} at: within its range. */
        int instanceWeight(int requested) {
            return Math.max(minWeight, Math.min(maxWeight, requested));
        }
    }

    /** {@code usWidthClass} of a face that is neither condensed nor expanded. */
    static final int NORMAL_WIDTH = 5;

    private SystemFonts() {
    }

    // sfnt / table tags as big-endian ints.
    private static final int TAG_TTCF = 0x74746366; // 'ttcf' (collection)
    private static final int TAG_TRUE = 0x74727565; // 'true' (legacy Mac TrueType)
    private static final int TAG_OTTO = 0x4F54544F; // 'OTTO' (CFF/OpenType)
    private static final int SFNT_1_0 = 0x00010000; // TrueType outlines
    private static final int TAG_NAME = 0x6E616D65; // 'name'
    private static final int TAG_OS2 = 0x4F532F32;  // 'OS/2'
    private static final int TAG_FVAR = 0x66766172; // 'fvar'
    private static final int TAG_WGHT = 0x77676874; // 'wght'

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
        return switch (Platform.current().os()) {
            case MACOS -> List.of(
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
        long os2Offset = -1;
        long os2Length = 0;
        long fvarOffset = -1;
        for (int i = 0; i < numTables; i++) {
            int tableTag = file.readInt();
            file.skipBytes(4); // checksum
            long offset = file.readInt() & 0xFFFFFFFFL;
            long length = file.readInt() & 0xFFFFFFFFL;
            if (tableTag == TAG_NAME) {
                nameOffset = offset;
            } else if (tableTag == TAG_OS2) {
                os2Offset = offset;
                os2Length = length;
            } else if (tableTag == TAG_FVAR) {
                fvarOffset = offset;
            }
        }
        if (nameOffset < 0) {
            return;
        }
        Face face = readName(file, nameOffset, path, index);
        if (face != null) {
            out.add(withWeightRange(file, fvarOffset, withClasses(file, os2Offset, os2Length, face)));
        }
    }

    /**
     * {@code face} with the weight, width and italic flag its {@code OS/2} table declares, or with
     * those read from its style name where the table is absent, too short or out of range. A
     * broken {@code OS/2} costs the face its classes, never the face itself: the name already
     * said what the family is called, and that is what the catalog lists.
     */
    private static Face withClasses(RandomAccessFile file, long os2Offset, long os2Length, Face face) {
        String lower = face.style().toLowerCase(Locale.ROOT);
        int weight = weightFromStyle(lower);
        int width = NORMAL_WIDTH;
        boolean italic = face.italic();
        // usWeightClass at 4, usWidthClass at 6, fsSelection at 62: every version of the table
        // has all three, and a table shorter than 64 bytes is not one any version defines.
        if (os2Offset >= 0 && os2Length >= 64) {
            try {
                file.seek(os2Offset + 4);
                int declaredWeight = file.readUnsignedShort();
                int declaredWidth = file.readUnsignedShort();
                file.seek(os2Offset + 62);
                int fsSelection = file.readUnsignedShort();
                if (declaredWeight >= 1 && declaredWeight <= 9) {
                    declaredWeight *= 100; // a few old faces wrote the class as 1-9
                }
                if (declaredWeight >= 1 && declaredWeight <= 1000) {
                    weight = declaredWeight;
                }
                if (declaredWidth >= 1 && declaredWidth <= 9) {
                    width = declaredWidth;
                }
                italic |= (fsSelection & 0x0201) != 0; // ITALIC (bit 0) or OBLIQUE (bit 9)
            } catch (IOException error) {
                LOG.log(System.Logger.Level.TRACE, "unreadable OS/2 table in " + face.path(), error);
            }
        }
        return new Face(face.family(), face.style(), face.path(), face.index(),
                weight >= 600, italic, weight, width);
    }

    /**
     * {@code face} with the range of its {@code fvar} table's {@code wght} axis, or as it is where
     * the face has no such axis or the table cannot be read. The default instance's weight becomes
     * the face's weight, since that is the instance its {@code OS/2} and its name describe.
     */
    private static Face withWeightRange(RandomAccessFile file, long fvarOffset, Face face) {
        if (fvarOffset < 0) {
            return face;
        }
        try {
            file.seek(fvarOffset + 4); // majorVersion, minorVersion
            int axesArrayOffset = file.readUnsignedShort();
            file.skipBytes(2); // reserved
            int axisCount = file.readUnsignedShort();
            int axisSize = file.readUnsignedShort();
            if (axisCount > 64 || axisSize < 20) {
                return face;
            }
            for (int i = 0; i < axisCount; i++) {
                file.seek(fvarOffset + axesArrayOffset + (long) i * axisSize);
                if (file.readInt() != TAG_WGHT) {
                    continue;
                }
                int min = Math.round(file.readInt() / 65536f);
                int def = Math.round(file.readInt() / 65536f);
                int max = Math.round(file.readInt() / 65536f);
                if (min < 1 || max > 1000 || min > def || def > max || min == max) {
                    return face;
                }
                return new Face(face.family(), face.style(), face.path(), face.index(), def >= 600,
                        face.italic(), def, face.width(), min, max);
            }
        } catch (IOException error) {
            LOG.log(System.Logger.Level.TRACE, "unreadable fvar table in " + face.path(), error);
        }
        return face;
    }

    /** The weight a style name says, for a face whose {@code OS/2} does not: "Semibold" is 600. */
    static int weightFromStyle(String lowerStyle) {
        String s = lowerStyle.replace(" ", "").replace("-", "");
        if (s.contains("thin") || s.contains("hairline")) {
            return 100;
        }
        if (s.contains("extralight") || s.contains("ultralight")) {
            return 200;
        }
        if (s.contains("light")) {
            return 300;
        }
        if (s.contains("semibold") || s.contains("demibold")) {
            return 600;
        }
        if (s.contains("extrabold") || s.contains("ultrabold")) {
            return 800;
        }
        if (s.contains("black") || s.contains("heavy")) {
            return 900;
        }
        if (s.contains("bold")) {
            return 700;
        }
        if (s.contains("medium")) {
            return 500;
        }
        return 400;
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
