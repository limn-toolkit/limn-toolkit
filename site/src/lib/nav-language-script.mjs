/**
 * Putting the top bar into the reader's language on the English-only sections.
 *
 * `/docs/` and `/api/` are published in English and stay that way: one Starlight build,
 * one Javadoc tree, both at one URL each. The bar above them is not the page, though. It
 * is the site's furniture, it says Components and Showcase and Menu, and a reader who
 * picked a language two pages ago has no reason to meet those words in English again.
 * {@link localeContinuityScript} already sends those links to the right locale; this puts
 * the right word on them.
 *
 * **The bar only.** Nothing under it is touched, because nothing under it is translated:
 * a page that renamed its navigation and left the prose in English would be claiming a
 * translation that does not exist. The words this rewrites are the four destinations and
 * the two labels a screen reader announces, all of which are in the catalogs already.
 *
 * Plain JavaScript in a `.mjs` for the reason `locale-continuity-script.mjs` gives: the
 * Javadoc injector is a plain Node script and cannot read the site's TypeScript.
 *
 * An element opts in by carrying {@link NAV_KEY_ATTR} for its text, or
 * {@link NAV_LABEL_ATTR} for its `aria-label`. Both take a message key. Unmarked elements
 * are never touched, so the brand, the theme picker and the privacy prompt are out of
 * scope by construction rather than by a list of exceptions.
 */
import { LANGUAGE_STORAGE_KEY } from "./language-script.mjs";

/** The attribute on an element whose TEXT is a message: the value is the key. */
export const NAV_KEY_ATTR = "data-nav-key";

/** The attribute on an element whose `aria-label` is a message: the value is the key. */
export const NAV_LABEL_ATTR = "data-nav-label-key";

/**
 * Builds the script for the English-only pages.
 *
 * Like the continuity script, only an explicit choice does anything: a stored tag the
 * table does not carry, or no stored tag at all, leaves every word alone. With scripting
 * off nothing runs and the bar stays English, which is how this has to degrade — the page
 * under it is English, so an English bar is never a lie.
 *
 * The bar's own `lang` is set alongside the words. Without it the element still claims the
 * document's language, and a screen reader would read Komponenten to a German reader in an
 * English voice, which is worse than leaving it in English.
 *
 * @param translations locale tag to message key to string, for the prefixed locales only:
 *   `SHARED_NAV_TRANSLATIONS` from `src/i18n/shared.mjs`, which the site asserts against
 *   the real catalogs on every build.
 * @param barSelector the element to mark with the chosen `lang`
 */
export function navLanguageScript(translations, barSelector) {
  return `(function(){try{
var T=${JSON.stringify(translations)},K=${JSON.stringify(LANGUAGE_STORAGE_KEY)};
var A=${JSON.stringify(NAV_KEY_ATTR)},L=${JSON.stringify(NAV_LABEL_ATTR)};
function go(){
var s=null;try{s=localStorage.getItem(K)}catch(e){}
var m=s&&Object.prototype.hasOwnProperty.call(T,s)?T[s]:null;
if(!m)return;
var t=document.querySelectorAll("["+A+"]");
for(var i=0;i<t.length;i++){var k=t[i].getAttribute(A);if(m[k])t[i].textContent=m[k];}
var l=document.querySelectorAll("["+L+"]");
for(var j=0;j<l.length;j++){var n=l[j].getAttribute(L);if(m[n])l[j].setAttribute("aria-label",m[n]);}
var bar=document.querySelector(${JSON.stringify(barSelector)});
if(bar)bar.setAttribute("lang",s);
}
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",go,{once:true});
else go();
}catch(e){}})()`;
}
