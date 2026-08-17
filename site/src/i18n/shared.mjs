/**
 * The English strings `/api/` needs, as plain JavaScript.
 *
 * They belong to the site's one catalog: `en.ts` spreads this in, so there is still a
 * single set of keys and a single place a translation looks them up. They live in a `.mjs`
 * for one reason: `scripts/build-api.mjs` is a plain Node script patching a Javadoc tree
 * that no bundler ever sees, and it builds the same top bar and the same privacy prompt for
 * the API reference. Transcribed there a second time, they would drift, and `/api/` is the
 * copy nobody would think to check.
 *
 * So the rule is: **a string the Javadoc injector renders goes here, not in `en.ts`.**
 * Same reasoning as `src/lib/theme-script.mjs`, which the same script imports.
 */
export const SHARED_EN = {
  "site.name": "Limn",

  "nav.primaryLabel": "Site",
  /** The disclosure that holds the navigation below the bar's breakpoint. */
  "nav.menu": "Menu",
  "nav.components": "Components",
  "nav.showcase": "Showcase",
  "nav.docs": "Docs",
  "nav.api": "API",

  "theme.label": "Theme",
  "theme.system": "Auto",
  "theme.light": "Light",
  "theme.dark": "Dark",

  "consent.label": "Privacy choices",
  "consent.title": "No cookies unless you allow measurement",
  "consent.body":
    "Three things are kept in this browser and nowhere else: the theme you pick, the language you pick, and the answer you give here. Anything optional stays switched off until you turn it on.",
  "consent.more": "What is stored, in full",
  "consent.accept": "Allow all",
  "consent.reject": "Only what is necessary",
  "consent.choose": "Choose",
  "consent.save": "Save choices",
  "consent.alwaysOn": "Always on",
  "consent.necessaryName": "Strictly necessary",
  "consent.necessaryBody":
    "The light or dark theme you pick, the language you pick, and this answer. All three are local to this browser, none of them is a cookie, and none of them leaves the machine.",
  "consent.analyticsName": "Measurement",
  "consent.analyticsBody":
    "Google Analytics, loaded from googletagmanager.com. It sets its own cookies and tells the project which pages get read. It ships blocked and starts running only once you allow it here.",
};

/**
 * The published locale tags that live under a path prefix: every locale but English.
 *
 * Here for the same reason the strings above are: the Javadoc injector needs the list to
 * build the locale-continuity script for `/api/`, and it cannot read the TypeScript
 * catalogs that are the real source. `src/i18n/index.ts` asserts this list against those
 * catalogs on every build, so editing one without the other is a failed build rather than
 * an `/api/` that quietly carries yesterday's languages.
 */
export const PREFIXED_LOCALE_TAGS = [
  "pt-BR",
  "ja",
  "zh-Hans",
  "ko",
  "de",
  "fr",
  "es",
  "ru",
  "zh-Hant",
];

/**
 * The bar's own words, in the languages the site publishes.
 *
 * `/docs/` and `/api/` are one build each, in English, so their bar cannot be rendered in
 * the reader's language: it is rewritten in the browser from this table (see
 * `src/lib/nav-language-script.mjs`). Only the keys the bar shows are here, and English is
 * absent because English is what the pages already ship.
 *
 * A copy of the catalogs, and therefore asserted against them on every build in
 * `src/i18n/index.ts`, for the same reason {@link PREFIXED_LOCALE_TAGS} is: the injector
 * cannot read TypeScript, and the copy nobody checks is the copy that drifts.
 */
export const SHARED_NAV_TRANSLATIONS = {
  "pt-BR": {
    "nav.primaryLabel": "Site",
    "nav.menu": "Menu",
    "nav.components": "Componentes",
    "nav.showcase": "Telas",
    "nav.docs": "Guia",
    "nav.api": "API",
  },
  ja: {
    "nav.primaryLabel": "サイト",
    "nav.menu": "メニュー",
    "nav.components": "コンポーネント",
    "nav.showcase": "画面例",
    "nav.docs": "ガイド",
    "nav.api": "API",
  },
  "zh-Hans": {
    "nav.primaryLabel": "站点",
    "nav.menu": "菜单",
    "nav.components": "组件",
    "nav.showcase": "界面",
    "nav.docs": "指南",
    "nav.api": "API",
  },
  ko: {
    "nav.primaryLabel": "사이트",
    "nav.menu": "메뉴",
    "nav.components": "컴포넌트",
    "nav.showcase": "화면",
    "nav.docs": "가이드",
    "nav.api": "API",
  },
  de: {
    "nav.primaryLabel": "Website",
    "nav.menu": "Menü",
    "nav.components": "Komponenten",
    "nav.showcase": "Oberflächen",
    "nav.docs": "Handbuch",
    "nav.api": "API",
  },
  fr: {
    "nav.primaryLabel": "Site",
    "nav.menu": "Menu",
    "nav.components": "Composants",
    "nav.showcase": "Écrans",
    "nav.docs": "Guide",
    "nav.api": "API",
  },
  es: {
    "nav.primaryLabel": "Sitio",
    "nav.menu": "Menú",
    "nav.components": "Componentes",
    "nav.showcase": "Pantallas",
    "nav.docs": "Guía",
    "nav.api": "API",
  },
  ru: {
    "nav.primaryLabel": "Сайт",
    "nav.menu": "Меню",
    "nav.components": "Компоненты",
    "nav.showcase": "Экраны",
    "nav.docs": "Руководство",
    "nav.api": "API",
  },
  "zh-Hant": {
    "nav.primaryLabel": "網站",
    "nav.menu": "選單",
    "nav.components": "元件",
    "nav.showcase": "畫面",
    "nav.docs": "指南",
    "nav.api": "API",
  },
};

/**
 * The shape `public/consent.js` reads, built from a catalog. The runtime takes flat keys
 * of its own so that it never has to know the site's key namespace.
 *
 * @param lookup resolves a message key: `messages(locale)` on the site, a plain lookup
 *   into {@link SHARED_EN} in the Javadoc injector
 * @param privacyHref where "what is stored, in full" points, already resolved for the page
 */
export function consentStrings(lookup, privacyHref) {
  return {
    label: lookup("consent.label"),
    title: lookup("consent.title"),
    body: lookup("consent.body"),
    privacyHref,
    privacyLabel: lookup("consent.more"),
    accept: lookup("consent.accept"),
    reject: lookup("consent.reject"),
    choose: lookup("consent.choose"),
    save: lookup("consent.save"),
    alwaysOn: lookup("consent.alwaysOn"),
    necessaryName: lookup("consent.necessaryName"),
    necessaryBody: lookup("consent.necessaryBody"),
    analyticsName: lookup("consent.analyticsName"),
    analyticsBody: lookup("consent.analyticsBody"),
  };
}
