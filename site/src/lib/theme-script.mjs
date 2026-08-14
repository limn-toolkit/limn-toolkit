/**
 * The pre-paint theme contract, as plain JavaScript so that both consumers can read the
 * same definition: Astro's layout imports it through `theme.ts`, and
 * `scripts/build-api.mjs` imports it directly to inject the identical script into every
 * generated Javadoc page. A second transcription of either value is a drift the build
 * cannot see.
 *
 * **The key and its vocabulary are Starlight's.** Starlight ships a theme switch on every
 * `/docs/` page and stamps `data-theme` from `starlight-theme` before paint; two scripts
 * writing the same attribute from two different keys cannot be kept in sync. `''` is
 * Starlight's spelling of "follow the system".
 */
export const THEME_STORAGE_KEY = "starlight-theme";

/**
 * Inlined into `<head>` ahead of any stylesheet, so it must stay tiny and self-contained:
 * it is stringified, never imported by the page. Stamps the RESOLVED value, so CSS reads
 * `:root[data-theme]` and the media query is only the no-JS fallback.
 */
export const NO_FLASH_SCRIPT = `(function(){try{var k=localStorage.getItem(${JSON.stringify(
  THEME_STORAGE_KEY,
)});var d=k==="dark"||(k!=="light"&&matchMedia("(prefers-color-scheme:dark)").matches);document.documentElement.setAttribute("data-theme",d?"dark":"light")}catch(e){}})()`;

/**
 * Tells the browser what colour to tint its own chrome with: Safari's toolbar on macOS and iOS,
 * and the address bar on Chrome and Edge for Android. It is the `theme-color` meta, and it is
 * written from script rather than authored as markup for two reasons.
 *
 * **The colour is read from the palette, never repeated here.** It is
 * `getComputedStyle(...).getPropertyValue("--limn-background")`, which is the token the page is
 * actually painted with, so a palette change moves the browser's chrome with it and no hex in this
 * file can fall out of step with one in a stylesheet.
 *
 * **It follows the reader's own choice, not only the system's.** A `media` pair in markup answers
 * `prefers-color-scheme`, and this site has a switch: a reader who forces light on a dark desktop
 * would get a dark toolbar over a white page. Watching `data-theme` on the root element covers
 * every path at once (this site's toggle, Starlight's own toggle inside the guide, and the
 * first-paint value the script above stamps), because all three write that one attribute.
 *
 * Deliberately quiet when there is nothing to say: with scripting off, no meta is written and the
 * browser keeps its default chrome, which is what it does today.
 */
export const THEME_COLOR_SCRIPT = `(function(){function s(){try{var c=getComputedStyle(document.documentElement).getPropertyValue("--limn-background").trim();if(!c)return;var m=document.querySelector('meta[name="theme-color"]');if(!m){m=document.createElement("meta");m.setAttribute("name","theme-color");document.head.appendChild(m)}m.setAttribute("content",c)}catch(e){}}
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",s)}else{s()}
try{new MutationObserver(s).observe(document.documentElement,{attributes:true,attributeFilter:["data-theme"]})}catch(e){}
try{addEventListener("storage",s)}catch(e){}})()`;
