/**
 * Keeps every tab group on a page that shares a `data-tabs` key on the same choice: a reader who
 * picks Maven in one dependency block sees Maven in the next, and a reader who picks macOS for one
 * command sees the macOS form of the next. The tabs themselves are radios and work without this.
 *
 * Nothing is stored. The choice lasts the page, because the privacy page lists what this site
 * keeps in a browser, and a remembered tab is not on the list.
 *
 * Inlined into `<head>` by both kinds of page (the site's own layout and Starlight's guide), so
 * it is a self-contained string. The change listener is on the document and needs no element to
 * exist yet; the one thing that waits for the document is moving a Mac reader's command tabs to
 * macOS, the user agent being the only signal a static page has, and a wrong guess a click.
 */
export const TABS_SCRIPT = `(function(){document.addEventListener("change",function(e){var t=e.target;if(!(t instanceof HTMLInputElement)||t.type!=="radio")return;var g=t.closest("[data-tabs]");if(!g)return;var k=g.getAttribute("data-tabs");document.querySelectorAll("[data-tabs]").forEach(function(o){if(o===g||o.getAttribute("data-tabs")!==k)return;o.querySelectorAll("input[type=radio]").forEach(function(i){if(i.value===t.value)i.checked=true})})});if(/Mac/.test(navigator.userAgent)){document.addEventListener("DOMContentLoaded",function(){document.querySelectorAll("[data-tabs=os] input[value=macos]").forEach(function(i){i.checked=true})})}})()`;
