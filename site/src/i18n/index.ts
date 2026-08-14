/**
 * Message lookup, and the two guarantees that go with it.
 *
 * 1. **A missing translation falls back to English.** Same contract as the toolkit's
 *    `I18nString`, and for the same reason: a partly translated page is readable, a page
 *    with holes in it is not.
 * 2. **A raw key never reaches the screen.** A key absent from English is a programming
 *    error, and {@link t} throws at build time, which is a failed build rather than
 *    `home.hero.headline` rendered as a heading.
 */
import { en, type MessageKey } from "./en";
import { PREFIXED_LOCALE_TAGS, SHARED_NAV_TRANSLATIONS } from "./shared.mjs";
import { de } from "./de";
import { es } from "./es";
import { fr } from "./fr";
import { ja } from "./ja";
import { ko } from "./ko";
import { ptBR } from "./pt-BR";
import { ru } from "./ru";
import { zhHans } from "./zh-Hans";
import { zhHant } from "./zh-Hant";
import { DEFAULT_LOCALE, LOCALES, localeParam, type Locale } from "../lib/i18n";

/** Partial by construction: a catalog carries what it has translated and nothing more. */
export type Catalog = Partial<Record<MessageKey, string>>;

/**
 * Every catalog that exists. Adding one here is the whole of adding a language: the
 * published set, the picker and the coverage table are all derived from this map, so no
 * list of languages is maintained twice.
 */
export const CATALOGS: Partial<Record<Locale, Catalog>> = {
  en,
  "pt-BR": ptBR,
  ja,
  "zh-Hans": zhHans,
  ko,
  de,
  fr,
  es,
  ru,
  "zh-Hant": zhHant,
};

/** Resolves a key for a locale, falling back to English. Throws if English lacks it. */
export function t(locale: Locale, key: MessageKey): string {
  const translated = CATALOGS[locale]?.[key];
  if (translated !== undefined) return translated;
  const fallback = en[key];
  if (fallback === undefined) {
    throw new Error(
      `i18n: no English text for '${key}'. Every key must exist in en.ts before it is used; ` +
        "a key with no text would otherwise render as itself.",
    );
  }
  return fallback;
}

/** Curried form, so a page resolves its locale once and reads keys as `msg("nav.home")`. */
export function messages(locale: Locale) {
  return (key: MessageKey) => t(locale, key);
}

/** Fraction of English keys a locale actually translates, 0–1. English is 1 by definition. */
export function coverage(locale: Locale): number {
  if (locale === DEFAULT_LOCALE) return 1;
  const catalog = CATALOGS[locale];
  if (!catalog) return 0;
  const keys = Object.keys(en) as MessageKey[];
  const translated = keys.filter((key) => catalog[key] !== undefined).length;
  return translated / keys.length;
}

/**
 * The locales this build actually publishes.
 *
 * A locale with no catalog is not published at all: routing it would produce a URL that
 * promises a language and serves English. The threshold below which a *partly* translated
 * locale is hidden is set in exactly one place: here.
 */
export const COVERAGE_THRESHOLD = 0;

export function publishedLocales(): Locale[] {
  return LOCALES.filter((locale) => coverage(locale) > COVERAGE_THRESHOLD);
}

/** Locales published *besides* the default: the ones that live under a path prefix. */
export function prefixedLocales(): Locale[] {
  return publishedLocales().filter((locale) => locale !== DEFAULT_LOCALE);
}

/**
 * The `getStaticPaths` return value every localized page uses, so that adding a language is
 * still only a matter of adding a catalog: no page carries a list of locales.
 *
 * The locale travels as a **prop** and not as the raw parameter. The parameter is
 * `undefined` on the English route, and a page reading it back would have to re-derive the
 * default. Astro's own types cannot express that, which is the whole reason this exists.
 */
export function localeRoutes(): Array<{
  params: { locale: string | undefined };
  props: { locale: Locale };
}> {
  return publishedLocales().map((locale) => ({
    params: { locale: localeParam(locale) },
    props: { locale },
  }));
}

// `/api/` builds its bar from shared.mjs, which carries a plain-JavaScript copy of the
// prefixed-locale list because the injector cannot read these catalogs. Asserted on every
// build precisely because it is a copy; an unasserted copy is the one that drifts.
{
  const fromCatalogs = [...prefixedLocales()].sort().join(" ");
  const fromShared = [...PREFIXED_LOCALE_TAGS].sort().join(" ");
  if (fromCatalogs !== fromShared) {
    throw new Error(
      "i18n: PREFIXED_LOCALE_TAGS in src/i18n/shared.mjs does not match the published " +
        `catalogs: shared.mjs says [${fromShared}], the catalogs say [${fromCatalogs}]. ` +
        "Update shared.mjs so /api/ carries the same languages as the site.",
    );
  }
}

// The bar's words for those sections, copied into shared.mjs for the same reason and
// asserted the same way. Every published locale must be present and every string must be
// the one its catalog holds, so a retranslated menu item cannot reach the site while
// `/docs/` and `/api/` keep saying the old word.
{
  for (const locale of prefixedLocales()) {
    const shared = (SHARED_NAV_TRANSLATIONS as Record<string, Record<string, string>>)[locale];
    if (!shared) {
      throw new Error(
        `i18n: SHARED_NAV_TRANSLATIONS in src/i18n/shared.mjs has no entry for '${locale}', ` +
          "so the bar on /docs/ and /api/ would stay English for it.",
      );
    }
    for (const [key, value] of Object.entries(shared)) {
      const fromCatalog = t(locale, key as MessageKey);
      if (fromCatalog !== value) {
        throw new Error(
          `i18n: SHARED_NAV_TRANSLATIONS['${locale}']['${key}'] is "${value}", but the ` +
            `catalog says "${fromCatalog}". Update shared.mjs so the bar on /docs/ and ` +
            "/api/ says what the rest of the site says.",
        );
      }
    }
  }
}

/** The coverage table a build can print to say which languages it published. */
export function coverageTable(): Array<{ locale: Locale; percent: number; published: boolean }> {
  const published = new Set(publishedLocales());
  return LOCALES.map((locale) => ({
    locale,
    percent: Math.round(coverage(locale) * 100),
    published: published.has(locale),
  }));
}
