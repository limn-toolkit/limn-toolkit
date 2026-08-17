/**
 * The measurement tag, written once.
 *
 * Three kinds of page have to ship the identical one — the marketing pages, the guide under
 * `/docs/`, and `/api/`, which is a Javadoc tree a Node script patches and which no bundler
 * ever sees — so this is a module and not a component, for the same reason
 * `theme-script.mjs` is. A second copy of a measurement tag is how a site ends up counting a
 * visit twice, or counting it on one page and not another.
 *
 * **It is emitted BLOCKED.** `type="text/plain"` is not a script to any browser: the markup
 * below downloads nothing, sets no cookie and sends nothing. `public/consent.js` rewrites it
 * into a real script at the moment the reader grants `analytics`, and never before — the
 * mechanism that file describes as the one thing it exists to enforce. Writing this tag any
 * other way, including "just for a moment", defeats the whole gate.
 *
 * The tag ships in development too, rather than being gated on a production build: the gate
 * is delicate enough that being able to exercise it locally is worth more than the traffic a
 * developer would have to explicitly click "allow" to generate.
 */

/** The GA4 property. Public by design — it identifies the property, it does not authorise. */
export const MEASUREMENT_ID = "G-T8T3JBKK7D";

/**
 * The two tags GA needs — the loader and the configuration — both blocked, in that order.
 *
 * `async` on the loader is carried over by the consent runtime, which copies every attribute
 * but `type` and `data-consent`. The configuration running before the loader arrives is
 * normal and is what `dataLayer` is for: the calls queue and replay.
 *
 * @returns HTML, for `set:html` on a page or for the Javadoc injector's string splice
 */
export function measurementTags() {
  return (
    `<script type="text/plain" data-consent="analytics" async` +
    ` src="https://www.googletagmanager.com/gtag/js?id=${MEASUREMENT_ID}"></script>` +
    `<script type="text/plain" data-consent="analytics">` +
    `window.dataLayer=window.dataLayer||[];` +
    `function gtag(){dataLayer.push(arguments);}` +
    `gtag('js',new Date());` +
    `gtag('config','${MEASUREMENT_ID}');` +
    `</script>`
  );
}
