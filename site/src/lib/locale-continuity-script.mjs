/**
 * Carrying the reader's language out of the English-only sections, as an inline script.
 *
 * `/docs/` and `/api/` are published in English only, so their bars are English. But the
 * links back out (the brand, Components, Showcase) land at the default-locale routes, and
 * a reader who chose a language in the picker loses it on the way back. This script
 * rewrites those links to the reader's stored choice, so the way out is a real link to the
 * language they came in with rather than a page that bounces after it loads.
 *
 * Plain JavaScript in a `.mjs` for the same reason as `language-script.mjs`: the Javadoc
 * injector (`scripts/build-api.mjs`) is a plain Node script that renders one of the bars
 * this has to run in, and it cannot read the site's TypeScript.
 *
 * **Emit this on the English-only sections and nowhere else.** On a locale-routed page the
 * links already say where they go, and rewriting them there would fight the URL the reader
 * explicitly chose, the same rule that keeps the language negotiation off prefixed URLs.
 *
 * A link opts in by carrying {@link LOCALE_LINK_ATTR}, whose value is its path below the
 * locale segment: `""` for the home page, `"components/"`, `"showcase/"`. The link's
 * `href` must be that same path on the default-locale route, which is what every bar
 * already writes; the script derives the site root from the pair, so one copy works
 * whether the page addresses the site absolutely (the Astro pages, under any `base`) or
 * relatively (the Javadoc tree, several directories deep).
 */
import { LANGUAGE_STORAGE_KEY } from "./language-script.mjs";

/** The attribute a rewritable link carries: its path below the locale segment. */
export const LOCALE_LINK_ATTR = "data-locale-link";

/**
 * Builds the script for the English-only pages.
 *
 * Only an explicit choice moves a link: the negotiation never stores its guesses, so a
 * reader who has not touched the picker keeps the English links, and the default-locale
 * pages those open still negotiate for that reader, exactly as they do today.
 *
 * With scripting off nothing runs and the links stay English, which is the right way for
 * this to degrade: an English link out of an English page is never wrong, only unhelpful.
 *
 * @param prefixes the locale tags that live under a path prefix: `prefixedLocales()` on
 *   the site, `PREFIXED_LOCALE_TAGS` from `src/i18n/shared.mjs` in the Javadoc injector.
 *   A stored tag outside this list (a locale since unpublished) leaves the links alone.
 */
export function localeContinuityScript(prefixes) {
  return `(function(){try{
var P=${JSON.stringify(prefixes)},K=${JSON.stringify(LANGUAGE_STORAGE_KEY)},A=${JSON.stringify(LOCALE_LINK_ATTR)};
function go(){
var s=null;try{s=localStorage.getItem(K)}catch(e){}
if(!s||P.indexOf(s)<0)return;
var links=document.querySelectorAll("["+A+"]");
for(var i=0;i<links.length;i++){
var l=links[i],p=l.getAttribute(A)||"",h=l.getAttribute("href")||"",n=h.length-p.length;
if(n<0||h.slice(n)!==p||h.slice(0,n).slice(-1)!=="/")continue;
l.setAttribute("href",h.slice(0,n)+s+"/"+p);
/* Dropped once rewritten, so running twice cannot stack a second locale segment. */
l.removeAttribute(A);
}}
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",go,{once:true});
else go();
}catch(e){}})()`;
}
