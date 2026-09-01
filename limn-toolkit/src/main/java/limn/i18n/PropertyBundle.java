package limn.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link StringBundle} backed by UTF-8 {@code .properties} files on the classpath,
 * one family per domain:
 *
 * <pre>{@code
 * I18n.addBundle(PropertyBundle.family("/i18n/common"));
 * I18n.addBundle(PropertyBundle.family("/i18n/settings"));   // one per screen
 * I18n.addBundle(PropertyBundle.family("/i18n/editor"));     // as many as it takes
 * }</pre>
 *
 * <p>A family is {@code base.properties} plus one file per language tag, and a
 * locale is served by <b>merging</b> them least-specific first ({@code base}, then
 * {@code _pt}, then {@code _pt-BR}), so a regional file carries only what it changes
 * and a language file carries the rest. Underscores are accepted in place of the
 * hyphen ({@code _pt_BR}), since that is how the JDK spells the same thing.
 *
 * <p>Files are read in {@link #prepare}, which {@link I18n} calls before anything can
 * ask for a string; {@link #lookup} is then a hash get. A missing file is not an
 * error; it is simply a language this domain does not translate, and the caller's
 * English stands.
 *
 * <p>UTF-8, not ISO-8859-1: these files are read through a {@code Reader}, so
 * Japanese and Cyrillic go in literally instead of as {@code \\uXXXX} escapes.
 */
public final class PropertyBundle implements StringBundle {

    private final String base;
    private final ClassLoader loader;

    /**
     * One table per locale something is reading, and nothing else: a language nobody is
     * reading costs no memory, the way a font nobody draws with is not resident.
     *
     * <p>Who is reading is {@link I18n}'s ledger, not this class's guess: the process
     * locale plus every {@linkplain I18n#retainLocale retained} subtree locale, each
     * {@linkplain #prepare prepared} in and {@linkplain #release released} out. A process
     * that has visited ten languages holds the tables for the ones on screen, not ten;
     * before ADR 035 that number was exactly one, and it grew only because two languages
     * can now genuinely be on screen at once. Coming back to a released language re-reads
     * the file, inside {@code prepare} and never on a paint path.
     */
    private final Map<Locale, Map<String, String>> tables = new ConcurrentHashMap<>();

    private PropertyBundle(String base, ClassLoader loader) {
        this.base = Objects.requireNonNull(base, "base");
        this.loader = loader;
    }

    /**
     * A family rooted at {@code baseResourcePath}, resolved against the class loader
     * that loaded this class.
     *
     * @param baseResourcePath an absolute classpath path without the {@code .properties}
     *                         suffix; {@code "/i18n/settings"} finds
     *                         {@code /i18n/settings_pt-BR.properties}
     */
    public static PropertyBundle family(String baseResourcePath) {
        return family(baseResourcePath, PropertyBundle.class.getClassLoader());
    }

    /** A family loaded through a specific class loader, for modular or plugin layouts. */
    public static PropertyBundle family(String baseResourcePath, ClassLoader loader) {
        String path = Objects.requireNonNull(baseResourcePath, "baseResourcePath");
        return new PropertyBundle(path.startsWith("/") ? path.substring(1) : path,
                Objects.requireNonNull(loader, "loader"));
    }

    /**
     * A bundle holding one locale's strings directly, for translations that were
     * downloaded, generated, or assembled in a test rather than shipped as a file.
     */
    public static StringBundle of(Locale locale, Map<String, String> strings) {
        Objects.requireNonNull(locale, "locale");
        Map<String, String> copy = Map.copyOf(strings);
        String language = locale.getLanguage();
        return (key, asked) -> language.equals(asked.getLanguage()) ? copy.get(key) : null;
    }

    @Override
    public void prepare(Locale locale) {
        tables.computeIfAbsent(locale, this::load);
    }

    @Override
    public void release(Locale locale) {
        tables.remove(locale);
    }

    @Override
    public String lookup(String key, Locale locale) {
        Map<String, String> table = tables.get(locale);
        if (table == null) {
            // Only reachable for a locale nobody prepared this bundle for; I18n prepares
            // on registration, on retain and before every switch is visible. Loaded and
            // KEPT rather than swapped in, because the ask will repeat: a lookup that
            // replaced the resident table would make two languages alternating in one
            // frame re-read both files per frame, forever.
            table = tables.computeIfAbsent(locale, this::load);
        }
        return table.get(key);
    }

    /** Whether a table for {@code locale} is resident (test observability). */
    boolean resident(Locale locale) {
        return tables.containsKey(locale);
    }

    /** How many strings {@code locale}'s table holds; 0 when it is not resident. */
    int residentSize(Locale locale) {
        Map<String, String> table = tables.get(locale);
        return table == null ? 0 : table.size();
    }

    /** How many locales hold a resident table: the memory this bundle is actually costing. */
    int residentLocales() {
        return tables.size();
    }

    /** The classpath resources this family would read for {@code locale}, most general first. */
    List<String> candidates(Locale locale) {
        String language = locale.getLanguage();
        if (language.isEmpty()) {
            return List.of(base + ".properties");
        }
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        ordered.put(base + ".properties", true);
        ordered.put(base + "_" + language + ".properties", true);
        String script = script(locale);
        if (!script.isEmpty()) {
            addTag(ordered, language + "-" + script);
        }
        String tag = locale.toLanguageTag();
        if (!tag.equals(language)) {
            addTag(ordered, tag);
        }
        return List.copyOf(ordered.keySet());
    }

    private void addTag(LinkedHashMap<String, Boolean> ordered, String tag) {
        ordered.put(base + "_" + tag + ".properties", true);
        ordered.put(base + "_" + tag.replace('-', '_') + ".properties", true);
    }

    /**
     * The locale's script, inferred from its region when it does not carry one.
     *
     * <p>Only Chinese needs this, and it needs it badly: the region alone decides
     * between two written forms that do not substitute for each other, and the
     * locale a machine reports is almost always {@code zh-CN} or {@code zh-TW}
     * rather than the {@code zh-Hans}/{@code zh-Hant} a translation is filed under.
     * Without the inference, every Chinese user would fall through to English.
     *
     * <p>Deliberately this small: a general likely-subtags table is ICU's job, and
     * no other language in reach of the text pipeline splits this way.
     */
    private static String script(Locale locale) {
        if (!locale.getScript().isEmpty()) {
            return locale.getScript();
        }
        if (!"zh".equals(locale.getLanguage())) {
            return "";
        }
        return switch (locale.getCountry()) {
            case "TW", "HK", "MO" -> "Hant";
            case "CN", "SG", "MY", "" -> "Hans";
            default -> "";
        };
    }

    private Map<String, String> load(Locale locale) {
        Map<String, String> merged = new HashMap<>();
        for (String resource : candidates(locale)) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    continue; // a language this domain does not translate
                }
                Properties properties = new Properties();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String name : properties.stringPropertyNames()) {
                    merged.put(name, properties.getProperty(name));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("reading " + resource, e);
            }
        }
        return Map.copyOf(merged);
    }

    @Override
    public String toString() {
        return "PropertyBundle[" + base + "]";
    }
}
