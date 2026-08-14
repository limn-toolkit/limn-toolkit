/**
 * The consent gate, as one classic script served from the site root.
 *
 * It is a file in `public/` rather than an Astro component because three kinds of page have
 * to run the identical gate: the marketing pages, the guide under `/docs/`, and the API
 * reference under `/api/`, which is a Javadoc tree a Node script patches and which no
 * bundler ever sees. A second copy of a consent decision is a bug that shows up as a reader
 * being asked twice, or as a category that is off on one page and on in another.
 *
 * What the gate actually gates
 * ----------------------------
 * `necessary` is not a toggle and never asks: the theme choice, and this decision itself.
 * Everything else is off until the reader says otherwise, and "off" is enforced here rather
 * than promised in prose. A tag ships as
 *
 *     <script type="text/plain" data-consent="analytics" src="…"></script>
 *
 * which no browser executes, and this file rewrites it into a real script at the moment the
 * category is granted, and not before. Adding a measurement tool any other way is the one
 * mistake this file exists to prevent.
 *
 * The page supplies its own text: every string is read from a JSON block, so the banner is
 * in the language of the page around it. Nothing here is written with innerHTML.
 */
(function () {
  "use strict";

  var KEY = "limn-consent";
  /** Bumped when the categories change: an old record no longer answers the new question. */
  var VERSION = 1;
  /** Everything the reader can decide. `necessary` is listed so the panel can name it. */
  var OPTIONAL = ["analytics"];

  var strings = readStrings();
  if (!strings) return;

  var listeners = [];
  var decision = readDecision();
  /** Undoes what `reserveClearance` did; a no-op while nothing is reserved. */
  var releaseClearance = function () {};

  window.limnConsent = {
    /** True only for a category the reader has allowed; `necessary` is always true. */
    granted: function (category) {
      if (category === "necessary") return true;
      return Boolean(decision && decision[category] === true);
    },
    /** The stored answer, or `null` while the reader has not answered. */
    decision: function () {
      return decision ? JSON.parse(JSON.stringify(decision)) : null;
    },
    /** Records a choice and applies it immediately. `{ analytics: true }`. */
    set: function (choice) {
      save(choice);
    },
    /** Reopens the panel so a reader can change their mind; the footer link calls this. */
    open: function () {
      render(true);
    },
    /** Called with the decision now and on every later change. */
    subscribe: function (listener) {
      listeners.push(listener);
      if (decision) listener(window.limnConsent.decision());
    },
  };

  if (decision) applyGrants();
  else render(false);

  bindOpeners();

  // ----------------------------------------------------------------- storage

  function readDecision() {
    try {
      var raw = localStorage.getItem(KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      // A record from an older set of categories is not an answer to the current question.
      if (!parsed || parsed.v !== VERSION) return null;
      return parsed;
    } catch (error) {
      // Storage blocked, or a corrupt value. Either way the reader has not answered, and
      // nothing optional runs.
      return null;
    }
  }

  function save(choice) {
    var next = { v: VERSION, at: new Date().toISOString() };
    for (var i = 0; i < OPTIONAL.length; i++) {
      next[OPTIONAL[i]] = choice[OPTIONAL[i]] === true;
    }
    decision = next;
    try {
      localStorage.setItem(KEY, JSON.stringify(next));
    } catch (error) {
      // A reader with storage blocked is asked again next time. That is the honest
      // outcome: with nowhere to write it, there is no record of consent to rely on.
    }
    applyGrants();
    for (var j = 0; j < listeners.length; j++) listeners[j](window.limnConsent.decision());
    remove();
  }

  // ------------------------------------------------------------ what it gates

  function applyGrants() {
    var blocked = document.querySelectorAll('script[type="text/plain"][data-consent]');
    for (var i = 0; i < blocked.length; i++) {
      var placeholder = blocked[i];
      if (!window.limnConsent.granted(placeholder.getAttribute("data-consent"))) continue;
      var live = document.createElement("script");
      for (var a = 0; a < placeholder.attributes.length; a++) {
        var attribute = placeholder.attributes[a];
        if (attribute.name === "type" || attribute.name === "data-consent") continue;
        live.setAttribute(attribute.name, attribute.value);
      }
      live.text = placeholder.textContent || "";
      placeholder.parentNode.replaceChild(live, placeholder);
    }
  }

  // ------------------------------------------------------------------ the UI

  /**
   * The page's text, from whichever of the two channels it has: a JSON block on a page a
   * bundler built, or a global that a script set. `/api/` takes the second, because its
   * pages are patched HTML and the prompt would otherwise be inlined several hundred times.
   */
  function readStrings() {
    if (window.limnConsentStrings) return window.limnConsentStrings;
    var block = document.getElementById("limn-consent-strings");
    if (!block) return null;
    try {
      return JSON.parse(block.textContent || "");
    } catch (error) {
      return null;
    }
  }

  function remove() {
    var existing = document.getElementById("limn-consent");
    if (existing) existing.parentNode.removeChild(existing);
    releaseClearance();
  }

  /**
   * While the card is up, the page's scroll range grows by the card's height: a flow
   * spacer at the end of whatever scrolls. The card is fixed to the viewport, so without
   * the spacer the last card-height of every page can never be scrolled out from under
   * it, and on the 404 that band holds the page's own actions. Sized by observation, not
   * once: the card grows when the panel opens and rewraps with the viewport, and a stale
   * size reopens the same hole.
   *
   * "Whatever scrolls" differs by surface: the site's own pages scroll the document, but
   * the Javadoc under `/api/` is a fixed flex frame whose `.flex-content` box does the
   * scrolling, so a spacer at the end of that page's body reserves nothing. If a future
   * Javadoc stops matching the selector, the body fallback is deliberate and not an
   * error: the spacer then merely does nothing, and the card behaves as it did before
   * clearance existed.
   */
  function reserveClearance(root) {
    var host = document.querySelector("body > .flex-box > .flex-content") || document.body;
    var spacer = document.createElement("div");
    spacer.id = "limn-consent-clearance";
    host.appendChild(spacer);
    var size = function () {
      spacer.style.blockSize = root.offsetHeight + "px";
    };
    size();
    var observer = null;
    if (typeof ResizeObserver === "function") {
      observer = new ResizeObserver(size);
      observer.observe(root);
    } else {
      window.addEventListener("resize", size);
    }
    releaseClearance = function () {
      releaseClearance = function () {};
      if (observer) observer.disconnect();
      else window.removeEventListener("resize", size);
      if (spacer.parentNode) spacer.parentNode.removeChild(spacer);
    };
  }

  function render(expanded) {
    remove();
    injectStyle();

    var root = element("div", "limn-consent");
    root.id = "limn-consent";
    root.setAttribute("role", "region");
    root.setAttribute("aria-label", strings.label);

    var box = element("div", "limn-consent__box");
    box.appendChild(text("h2", "limn-consent__title", strings.title));
    box.appendChild(text("p", "limn-consent__body", strings.body));

    if (strings.privacyHref) {
      var more = element("p", "limn-consent__more");
      var link = document.createElement("a");
      link.href = strings.privacyHref;
      link.textContent = strings.privacyLabel;
      more.appendChild(link);
      box.appendChild(more);
    }

    // Built only when it is shown, never built-and-hidden: `[hidden]` is a bare element
    // rule in the user agent stylesheet, so the `display:grid` below would beat it, and
    // this banner also runs on `/api/`, where the site's `[hidden]{display:none!important}`
    // is not loaded to save it.
    var toggles = {};
    if (expanded) {
      var panel = element("div", "limn-consent__panel");
      panel.appendChild(
        categoryRow("necessary", strings.necessaryName, strings.necessaryBody, true, true),
      );
      for (var i = 0; i < OPTIONAL.length; i++) {
        var name = OPTIONAL[i];
        var row = categoryRow(
          name,
          strings[name + "Name"],
          strings[name + "Body"],
          window.limnConsent.granted(name),
          false,
        );
        toggles[name] = row.querySelector("input");
        panel.appendChild(row);
      }
      box.appendChild(panel);
    }

    var actions = element("div", "limn-consent__actions");

    if (!expanded) {
      actions.appendChild(
        button(strings.choose, "limn-consent__button", function () {
          render(true);
        }),
      );
    }

    actions.appendChild(
      button(strings.reject, "limn-consent__button", function () {
        save({});
      }),
    );

    if (expanded) {
      actions.appendChild(
        button(strings.save, "limn-consent__button", function () {
          var choice = {};
          for (var name in toggles) choice[name] = toggles[name].checked;
          save(choice);
        }),
      );
    }

    actions.appendChild(
      button(strings.accept, "limn-consent__button limn-consent__button--primary", function () {
        var choice = {};
        for (var j = 0; j < OPTIONAL.length; j++) choice[OPTIONAL[j]] = true;
        save(choice);
      }),
    );

    box.appendChild(actions);
    root.appendChild(box);

    // First in the body, so it is an early tab stop and a keyboard reader meets it without
    // traversing the page. It is fixed to the bottom of the viewport, so its position in
    // the document costs the layout nothing, and it never steals focus, because it blocks
    // nothing and there is nothing here a reader must answer before reading the page.
    document.body.insertBefore(root, document.body.firstChild);
    reserveClearance(root);

    if (expanded) {
      var heading = root.querySelector(".limn-consent__title");
      if (heading) {
        heading.setAttribute("tabindex", "-1");
        heading.focus();
      }
    }
  }

  function categoryRow(name, label, description, checked, locked) {
    var row = element("div", "limn-consent__category");
    var top = document.createElement("label");
    top.className = "limn-consent__switch";

    var input = document.createElement("input");
    input.type = "checkbox";
    input.checked = checked;
    input.disabled = locked;
    input.setAttribute("data-category", name);

    var caption = document.createElement("span");
    caption.textContent = label;

    top.appendChild(input);
    top.appendChild(caption);
    if (locked) {
      var badge = document.createElement("span");
      badge.className = "limn-consent__always";
      badge.textContent = strings.alwaysOn;
      top.appendChild(badge);
    }

    row.appendChild(top);
    row.appendChild(text("p", "limn-consent__note", description));
    return row;
  }

  function bindOpeners() {
    var openers = document.querySelectorAll("[data-consent-open]");
    for (var i = 0; i < openers.length; i++) {
      openers[i].addEventListener("click", function (event) {
        event.preventDefault();
        render(true);
      });
    }
  }

  function element(tag, className) {
    var node = document.createElement(tag);
    node.className = className;
    return node;
  }

  function text(tag, className, value) {
    var node = element(tag, className);
    node.textContent = value;
    return node;
  }

  function button(label, className, onClick) {
    var node = document.createElement("button");
    node.type = "button";
    node.className = className;
    node.textContent = label;
    node.addEventListener("click", onClick);
    return node;
  }

  function injectStyle() {
    if (document.getElementById("limn-consent-style")) return;
    var style = document.createElement("style");
    style.id = "limn-consent-style";
    // Every colour is a toolkit token, which both the site's stylesheet and the patched
    // Javadoc stylesheet define, and that is what makes one banner belong to three surfaces.
    //
    // The card keeps to a narrow column at the inline end. Widening it, or centering it,
    // puts it back on top of a page's own actions along the same bottom edge: on the 404
    // the primary button sits at the inline start, exactly where a centered band reaches.
    // On a phone the column is the full row anyway, so narrow screens are unaffected.
    style.textContent = [
      ".limn-consent{position:fixed;inset-block-end:0;inset-inline:0;z-index:2147483000;",
      "display:flex;justify-content:flex-end;padding:1rem;pointer-events:none}",
      ".limn-consent__box{pointer-events:auto;inline-size:min(100%,30rem);display:grid;gap:.6rem;",
      "padding:1.1rem 1.25rem;border:1px solid var(--limn-outline);border-radius:14px;",
      "background-color:var(--limn-background);color:var(--limn-text);",
      "box-shadow:0 18px 48px color-mix(in srgb, var(--limn-focus-ring) 26%, transparent);",
      "font-family:system-ui,-apple-system,'Segoe UI',sans-serif;font-size:.875rem;line-height:1.55}",
      ".limn-consent__title{margin:0;font-size:1rem;font-weight:660;letter-spacing:-.01em}",
      ".limn-consent__body,.limn-consent__note{margin:0;color:var(--limn-text-muted)}",
      ".limn-consent__more{margin:0}",
      ".limn-consent__more a{color:var(--limn-primary)}",
      ".limn-consent__panel{display:grid;gap:.9rem;margin-block:.35rem;padding-block:.85rem;",
      "border-block:1px solid color-mix(in srgb, var(--limn-outline) 45%, transparent)}",
      ".limn-consent__category{display:grid;gap:.2rem}",
      ".limn-consent__switch{display:flex;align-items:center;gap:.5rem;font-weight:600}",
      ".limn-consent__switch input{inline-size:16px;block-size:16px;accent-color:var(--limn-primary)}",
      ".limn-consent__note{font-size:.8125rem;padding-inline-start:1.5rem}",
      ".limn-consent__always{padding:.1rem .4rem;border-radius:999px;",
      "background-color:var(--limn-surface);color:var(--limn-text-muted);",
      "font-size:.6875rem;font-weight:560;text-transform:uppercase;letter-spacing:.06em}",
      ".limn-consent__actions{display:flex;flex-wrap:wrap;gap:.5rem;justify-content:flex-end}",
      ".limn-consent__button{min-block-size:40px;padding-inline:.95rem;border-radius:9px;",
      "border:1px solid var(--limn-outline);background-color:transparent;color:var(--limn-text);",
      "font:inherit;font-weight:560;cursor:pointer}",
      ".limn-consent__button:hover{background-color:var(--limn-surface)}",
      ".limn-consent__button--primary{border-color:transparent;",
      "background-color:var(--limn-primary);color:var(--limn-on-primary)}",
      ".limn-consent__button--primary:hover{background-color:var(--limn-primary-hover)}",
      ".limn-consent :is(button,input,a):focus-visible{outline:2px solid var(--limn-focus-ring);",
      "outline-offset:2px}",
      "@media (max-width:34rem){.limn-consent__actions{justify-content:stretch}",
      ".limn-consent__button{flex:1 1 auto}}",
    ].join("");
    document.head.appendChild(style);
  }
})();
