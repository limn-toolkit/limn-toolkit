/**
 * The language negotiation, as an inline script.
 *
 * A static host serves files and cannot read `Accept-Language`, so the only place this can
 * happen is the browser. It runs in `<head>`, before the body paints, so a reader whose
 * language is published never sees the English page flash first.
 *
 * Plain JavaScript in a `.mjs` for the same reason as `theme-script.mjs`: it is
 * stringified into the page, never imported by it, and the storage key has to be readable
 * by the components that write it.
 *
 * **Two rules keep this from fighting the reader.**
 *
 * 1. Only the default-locale routes negotiate. A prefixed URL such as `/ja/showcase/` is
 *    somebody's explicit request, whether they typed it, bookmarked it or were sent it, and
 *    a shared link that bounces the recipient somewhere else is a broken link. The layout
 *    emits this script on the English routes and nowhere else, so a translated page carries
 *    no redirect at all.
 * 2. Only an explicit choice is remembered. The picker writes the key; the negotiation
 *    never does. So "what your browser asks for" stays a fallback that a reader can
 *    override once and for good, rather than a decision the site quietly made for them.
 *
 * Nothing is stored and nothing is sent while negotiating: the browser's language list is
 * read once, in the browser, to choose a URL.
 */

/** Where the picker records an explicit choice. Disclosed on the privacy page. */
export const LANGUAGE_STORAGE_KEY = "limn-language";

/**
 * Builds the inline script for one page.
 *
 * @param current the locale this page is published in
 * @param urls every published locale mapped to THIS page's URL in it, already resolved by
 *   `localePath`, so the script joins no paths of its own and cannot drift from the links
 *   in the picker beside it
 */
export function languageScript(current, urls) {
  return `(function(){try{
var C=${JSON.stringify(current)},U=${JSON.stringify(urls)},K=${JSON.stringify(LANGUAGE_STORAGE_KEY)};
var L=Object.keys(U);
var s=localStorage.getItem(K);
var t=(s&&U[s])?s:negotiate();
if(!t||t===C||!U[t])return;
location.replace(U[t]);
function negotiate(){
var w=(navigator.languages&&navigator.languages.length)?navigator.languages:[navigator.language];
for(var i=0;i<w.length;i++){var m=match(String(w[i]||"").toLowerCase());if(m)return m}
return null}
function primary(tag){return tag.split("-")[0]}
function first(p){for(var i=0;i<L.length;i++){if(primary(L[i].toLowerCase())===p)return L[i]}return null}
function match(tag){
if(!tag)return null;
for(var i=0;i<L.length;i++){if(L[i].toLowerCase()===tag)return L[i]}
if(primary(tag)==="zh"){
/* Script first, then region: the two written forms of Chinese are not interchangeable,
   and a reader of one cannot read the other comfortably. */
var want=(tag.indexOf("hant")>=0||/-(tw|hk|mo)(-|$)/.test(tag))?"zh-Hant":"zh-Hans";
return U[want]?want:first("zh")}
return first(primary(tag))}
}catch(e){}})()`;
}
