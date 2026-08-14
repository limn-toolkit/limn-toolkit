# The mark

Delivered artwork, authored elsewhere and **checked in as source**. Nothing in this
directory is generated, and nothing here is edited by hand to match a palette: the tones
below are the brand's, and they are deliberately not the toolkit's accent.

| | |
| --- | --- |
| `limn-symbol-{light,dark}.svg` | the mark alone |
| `limn-lockup-{light,dark}.svg` | the mark locked to the wordmark |
| `limn-symbol-{light,dark}-{16…1024}.png` | rasterised, for the places that cannot take an SVG |
| `limn-lockup-{light,dark}.png` | the lockup, rasterised |

**`light` and `dark` name the background, not the theme.** The `light` variant carries dark
ink (`#23252f`) for a light page; the `dark` variant carries light ink (`#e9e9ed`). Both
draw the same violet outline behind the mark, one tone apart.

`scripts/build-icons.mjs` copies the sizes the site actually asks for into `public/` and
writes the web manifest beside them. Add a size here and it ships only once that script
names it, which is what stops twenty exports from becoming twenty requests.
