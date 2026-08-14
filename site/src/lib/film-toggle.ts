/**
 * Wires every play control on the page to the capture beside it.
 *
 * Lives here rather than in the component that first needed it, because two pages publish films
 * now (the component gallery in a card, and the showcase in a plate), and a second copy of this
 * toggle is a second place for "stop" to stop meaning what it means.
 *
 * The control finds its own capture: the nearest `.shot` inside the same container. Nothing is
 * passed by id, so a page may hold any number of these without naming any of them.
 */

/**
 * Buttons already wired. Both components that render a control import this module, so on a page
 * that uses both, this runs twice, and a second listener on one button would toggle the film
 * straight back off, which reads as the control being dead.
 */
const bound = new WeakSet<HTMLElement>();

export function bindFilmControls(root: ParentNode = document): void {
  for (const button of root.querySelectorAll<HTMLButtonElement>("[data-play]")) {
    if (bound.has(button)) continue;
    // The capture is looked up inside the control's own container, so a page with several films
    // never plays a neighbour's: `.shot` is the element Screenshot marks as playing.
    const shot = button.closest("[data-film]")?.querySelector<HTMLElement>(".shot")
      ?? button.parentElement?.querySelector<HTMLElement>(".shot");
    const label = button.querySelector<HTMLElement>("[data-play-text]");
    if (!shot || !label) continue;
    bound.add(button);

    // Shown only now: without script there is nothing to play, and a control that does nothing
    // is worse than no control.
    button.hidden = false;
    button.addEventListener("click", () => {
      const playing = shot.hasAttribute("data-playing");
      if (playing) {
        shot.removeAttribute("data-playing");
        for (const image of shot.querySelectorAll<HTMLImageElement>("[data-anim]")) {
          // Dropped, not just hidden: a hidden animated image is still an animation the browser
          // owns, and stopping should give the memory back rather than move it out of sight. It
          // costs nothing to restore, since the file is in the cache.
          image.removeAttribute("src");
        }
      } else {
        for (const image of shot.querySelectorAll<HTMLImageElement>("[data-anim]")) {
          // Re-assigned on every start, not only the first: an animated WebP restarts when its
          // source is set, and a film that resumes halfway through reads as broken.
          const source = image.dataset.src;
          if (source) image.src = source;
        }
        shot.setAttribute("data-playing", "");
      }
      button.setAttribute("aria-pressed", String(!playing));
      label.textContent = !playing
        ? (button.dataset.stopLabel ?? "")
        : (button.dataset.playLabel ?? "");
    });
  }
}
