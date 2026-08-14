/**
 * Backend-neutral 2D drawing: the immediate-mode {@link limn.graphics.Canvas} every widget
 * paints through, the values it takes ({@link limn.graphics.Color},
 * {@link limn.graphics.Paint}, {@link limn.graphics.Rect}, {@link limn.graphics.Path2D})
 * and the facades for what a drawing loads and measures: {@link limn.graphics.Images},
 * {@link limn.graphics.Fonts}, {@link limn.graphics.TextRulers} and
 * {@link limn.graphics.Icon}s rasterized from SVG or bitmaps. Coordinates are logical
 * points and the backend supplies the pixels, so nothing here imports a graphics library.
 */
package limn.graphics;
