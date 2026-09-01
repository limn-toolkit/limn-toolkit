/**
 * The locale set and the URL rules that follow from it.
 *
 * English lives at `/` and every other locale is prefixed. That asymmetry is
 * confined to this file: nothing else in the site may branch on `locale === "en"`.
 */

/** BCP-47 tags, in the order the language picker shows them. */
export const LOCALES = [
  "en",
  "pt-BR",
  "ja",
  "zh-Hans",
  "ko",
  "de",
  "fr",
  "es",
  "ru",
  "zh-Hant",
  "ar",
] as const;

export type Locale = (typeof LOCALES)[number];

/** The source of truth for every string; a key exists here before it exists anywhere. */
export const DEFAULT_LOCALE: Locale = "en";

/**
 * Endonyms: a picker shows a language in that language, because a reader who cannot read
 * the current page cannot read "Japanese" either.
 */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: "English",
  "pt-BR": "Português (Brasil)",
  ja: "日本語",
  "zh-Hans": "简体中文",
  ko: "한국어",
  de: "Deutsch",
  fr: "Français",
  es: "Español",
  ru: "Русский",
  "zh-Hant": "繁體中文",
  ar: "العربية",
};

/**
 * What the picker's own button shows, which is the same endonym with any region qualifier
 * dropped.
 * The full name still appears in the list, where there is room for it and where a reader
 * comparing two variants of one language needs the distinction.
 */
export const LOCALE_SHORT_NAMES: Record<Locale, string> = {
  ...LOCALE_NAMES,
  "pt-BR": "Português",
};

/**
 * The locales whose pages read right to left. Confined here the way the English-at-the-root
 * asymmetry is: nothing else in the site may test `locale === "ar"` to decide a direction,
 * and adding Hebrew or Persian later is one entry in this set.
 */
const RTL_LOCALES: ReadonlySet<Locale> = new Set<Locale>(["ar"]);

/**
 * What `<html dir>` says for a locale. The layout is written in CSS logical properties, so
 * this one attribute is the whole of mirroring a page; per-element `dir` never appears.
 */
export function localeDir(locale: Locale): "ltr" | "rtl" {
  return RTL_LOCALES.has(locale) ? "rtl" : "ltr";
}

/** The locale segment of a path, or `""` for English. */
export function localeSegment(locale: Locale): string {
  return locale === DEFAULT_LOCALE ? "" : `${locale}/`;
}

/**
 * The value of the `[...locale]` rest parameter for a locale: `undefined` for English,
 * which is what drops the segment and leaves English at the root.
 *
 * Here rather than in a page so that the English-at-the-root asymmetry stays in this one
 * file; a page that spelled the conditional itself would be the second place to change.
 */
export function localeParam(locale: Locale): string | undefined {
  return locale === DEFAULT_LOCALE ? undefined : locale;
}

/**
 * Builds a site-absolute path. Everything user-facing goes through here rather than
 * writing a leading slash, so that `base` is applied in exactly one place. A deploy under
 * a project path would otherwise break half the navigation.
 *
 * @param base Astro's `import.meta.env.BASE_URL`, always with a trailing slash
 * @param path route below the locale, no leading slash (`""` is the home page)
 */
export function localePath(base: string, locale: Locale, path = ""): string {
  const root = base.endsWith("/") ? base : `${base}/`;
  return `${root}${localeSegment(locale)}${path}`;
}

/** Absolute URL, for `hreflang`, canonical and Open Graph, none of which can be relative. */
export function localeUrl(site: URL | undefined, base: string, locale: Locale, path = ""): string {
  const relative = localePath(base, locale, path);
  return site ? new URL(relative, site).href : relative;
}

/**
 * Every alternate for a page, plus `x-default`. `x-default` points at English because
 * English is where an unmatched reader should land: the site offers a language and never
 * redirects on `Accept-Language`.
 *
 * Takes the PUBLISHED locale list, not the full one: an `hreflang` link is a promise that
 * the URL serves that language, and emitting all ten while only English has a catalog
 * pointed crawlers at nine 404s. The caller passes `publishedLocales()`; this module
 * cannot import it without a cycle.
 */
export function alternates(
  site: URL | undefined,
  base: string,
  published: readonly Locale[],
  path = "",
): Array<{ hreflang: string; href: string }> {
  return [
    ...published.map((locale) => ({
      hreflang: locale,
      href: localeUrl(site, base, locale, path),
    })),
    { hreflang: "x-default", href: localeUrl(site, base, DEFAULT_LOCALE, path) },
  ];
}

/** Narrows a route parameter, so an unknown segment 404s rather than rendering English. */
export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

/**
 * Carrying the reader's language out of the English-only sections (`/docs/`, `/api/`).
 *
 * The mechanism lives in `locale-continuity-script.mjs` as plain JavaScript, because the
 * Javadoc injector is one of its consumers, and is re-exported here so the site's own
 * components take everything locale-shaped from this one module. A bar link that has a
 * localized counterpart carries {@link LOCALE_LINK_ATTR} with its path below the locale
 * segment, and the page emits {@link localeContinuityScript} once; see the note in that
 * file for the one rule about where it may be emitted.
 */
export { LOCALE_LINK_ATTR, localeContinuityScript } from "./locale-continuity-script.mjs";

/**
 * The other half of the same idea: those sections' links keep the reader's language, and
 * this puts the reader's language on the words. Re-exported here for the same reason, and
 * subject to the same rule — the English-only sections, and nowhere else.
 */
export { NAV_KEY_ATTR, NAV_LABEL_ATTR, navLanguageScript } from "./nav-language-script.mjs";
